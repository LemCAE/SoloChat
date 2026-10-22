package app.solochat.core.agent

import app.solochat.core.llm.LlmDelta
import app.solochat.core.llm.LlmError
import app.solochat.core.llm.LlmErrorKind
import app.solochat.core.llm.LlmException
import app.solochat.core.llm.LlmRequest
import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.log.Log
import app.solochat.core.log.LogLevel
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Usage
import app.solochat.core.model.toLegacyUsage
import app.solochat.core.model.CallRecord
import app.solochat.core.model.CallStatus
import app.solochat.core.model.measuredUsage
import app.solochat.core.model.mergeUsage
import app.solochat.core.model.recordedCost
import app.solochat.core.protocol.Debounce
import app.solochat.core.protocol.DebounceDecision
import app.solochat.core.protocol.IncomingMessage
import app.solochat.core.protocol.RhythmLearner
import app.solochat.core.protocol.Script
import app.solochat.core.protocol.ScriptEvent
import app.solochat.core.protocol.ScriptParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次回合的执行器。
 *
 * 流水线（与 docs/00-plan.md 第 3 节一一对应）：
 *
 * ```
 * RF1 防抖（不在本类，见 planWait）
 * RF2 上下文装配
 * RF3 一次调用（流式收原文）
 * RF4 解析成事件脚本
 * RF5 事件落盘（播出由播放器做，本类不碰节奏）
 * RF6 用量与成本记账
 * ```
 *
 * **本类不做的事**：
 * - 不等待、不播出（那是播放器与 App 层的调度）
 * - 不改会话模式（模型只能提议，改不改由服务端策略定）
 * - 不判断用户说没说完（那是 Debounce）
 */
class AgentTurnRunner(private val deps: AgentDeps) {

    private companion object {
        /** 日志标签。界面按它分组，方便只看"模型"这一路。 */
        const val TAG = "模型"
    }

    /**
     * 计算"现在该不该开工、再等多久"。纯函数式，**0 次 API 调用**。
     *
     * 放在本类是为了让防抖与回合共享同一份上下文（最近消息、自适应窗口），
     * 但不触发任何网络请求。
     */
    fun planWait(
        conversationId: String,
        pending: List<IncomingMessage>,
        now: Long
    ): DebounceDecision {
        val last = pending.lastOrNull()
            ?: return DebounceDecision(0, app.solochat.core.protocol.DebounceTier.NEW_TURN, "没有待处理消息")
        val previous = pending.dropLast(1).lastOrNull()
        val gap = previous?.let { last.at - it.at }
        val burst = pending.count { last.at - it.at <= RhythmLearner.SAME_TURN_MAX_GAP_MS }
        val waited = pending.first().at.let { now - it }
        return Debounce.decide(
            message = last,
            gapToPreviousMs = gap,
            recentBurstCount = burst,
            windowMs = deps.debounceWindowMs(),
            totalWaitedMs = waited
        )
    }

    /** 这一轮要发给模型的用户侧文本（多条合并成一个气泡组）。 */
    fun userTextOf(pending: List<IncomingMessage>): String =
        pending.joinToString("\n") { it.text.trim() }.trim()

    /**
     * 跑一轮。事件按顺序推给界面；**出错不抛异常**，而是推 [AgentEvent.Failed]。
     *
     * 为什么不让异常冒出去：调用方是前台服务的协程，异常冒出去要么被静默吞掉
     * （用户永远等不到回复，也不知道为什么），要么崩掉整个服务。
     */
    fun run(turn: AgentTurn, pending: List<IncomingMessage> = emptyList()): Flow<AgentEvent> = flow {
        if (deps.storage.isBackupPaused(turn.conversationId)) {
            emit(AgentEvent.Failed(LlmError.Canceled("导入副本已暂停，请先在会话中确认恢复发送")))
            return@flow
        }
        if (deps.storage.getConversation(turn.conversationId) == null) {
            emit(AgentEvent.Failed(LlmError.Failed(LlmErrorKind.NOT_CONFIGURED, "会话不存在")))
            return@flow
        }
        val plan = ContextPlanner(deps).prepare(turn.conversationId, turn.cue)
        val config = plan.effective.config
        if (!config.isConfigured) {
            emit(
                AgentEvent.Failed(
                    LlmError.Failed(
                        kind = LlmErrorKind.NOT_CONFIGURED,
                        message = "还没配置模型接口"
                    )
                )
            )
            return@flow
        }

        val turnId = deps.clock.now()
        val conversation = plan.conversation

        // ---- RF2 上下文装配 ----
        //
        // ⚠️ **这里刻意不写用户消息**。
        //
        // 第一版在这里 `appendMessage` 了一遍，而调用方（SessionController）也写了一遍
        // —— 于是每条用户消息在库里都有两条，界面上成对出现。修掉之后约定变成：
        // **用户消息由调用方落盘，执行器只负责读**。
        //
        // 这个约定还有个好处：调用方能在"认领待处理批次"这一原子操作里把落盘做掉
        // （见 SessionController.claimPending），从而避免防抖任务反复重启导致同一批被写多次。
        //
        // 这里不再需要 `turn.userText`：调用方落盘之后，那条消息已经在
        // `recentMessages` 里了，装配上下文时自然会带上。保留该字段只是为了日志与排查。
        val prompt = plan.prompt
        emit(AgentEvent.Thinking(turn.at))

        // ---- 打点：把真正发出去的东西记进日志 ----
        //
        // 这一段是排查能力的核心。没有它，"模型怎么老回一个字"这类问题只能靠猜：
        // 提示词里那 40 条历史、系统前缀的实际内容、注入的记忆，全都是不可见的。
        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "请求",
                "会话" to conversation.id,
                "模式" to conversation.mode.wire,
                "模型" to config.model,
                "请求条数" to prompt.messages.size,
                "系统前缀字符" to prompt.stablePrefixChars,
                "温度" to config.temperature,
                "超时秒" to (config.timeoutMs / 1000),
                "本轮用户输入" to (if (pending.isNotEmpty()) userTextOf(pending) else turn.userText),
                // 空批次 + 有台上提示 = "没有新消息，是引擎叫我开口"（例如刚切到面对面）。
                // 这种情况必须能从日志里一眼认出来，否则会被当成"模型自己抢话"。
                "台上提示" to turn.cue.ifBlank { "-" },
                // ⚠️ 这一行是排查"模型看不到自己说过的话"的关键。
                //
                // 那个 bug 的可怕之处在于**界面上完全看不出来**：`/api/history` 走的是
                // "事件 + 消息"的投影，对方说过的话一条不少；而装配上下文读的是**消息表**。
                // 两者一旦分叉，模型就会把好几轮当成一轮来回答（"它又回答了一遍我在睡觉"）。
                // 把条数打出来，"我："在上下文里到底有没有，一眼就能确认。
                "历史构成" to prompt.historyBreakdown
            )
        )
        Log.body(TAG, "完整提示词", prompt.messages.joinToString("\n\n") { "【${it.role}】\n${it.content}" })

        // ---- RF3 一次调用 ----
        val buffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        var returnedUsage: LlmDelta.Finished? = null
        val startedAt = deps.clock.now()
        val call = CallRecord(
            id = java.util.UUID.randomUUID().toString(), conversationId = conversation.id,
            model = config.model, startedAt = startedAt, price = deps.pricing.resolve(config.model, config, startedAt),
            inputCharacters = prompt.messages.sumOf { it.content.length },
            contextMessageIds = plan.history.map { it.id }, stablePrefixChars = prompt.stablePrefixChars,
            contextEstimate = plan.estimatedTokens)
        deps.storage.recordCall(call)
        var terminal = false
        suspend fun finish(status: CallStatus, failure: String = ""): UsedUsage {
            val complete = status == CallStatus.SUCCESS || status == CallStatus.SILENT
            val amounts = measuredUsage(returnedUsage, plan.estimatedTokens,
                Estimate.roughTokensOf(buffer.toString() + reasoningBuffer), complete || buffer.isNotEmpty() || reasoningBuffer.isNotEmpty())
            val record = call.copy(status = status, finishedAt = deps.clock.now(), input = amounts.input,
                output = amounts.output, cached = amounts.cached, reasoning = amounts.reasoning,
                outputCharacters = buffer.length + reasoningBuffer.length,
                outputIncomplete = !complete, failureKind = failure)
            val usage = record.toLegacyUsage()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                deps.storage.recordCall(record, if (complete) usage else null)
            }
            terminal = true
            val cost = recordedCost(record)
            return UsedUsage(usage, cost.low ?: 0.0, cost.approximate || cost.low == null || cost.low != cost.high)
        }
        try {
            try {
                val request = LlmRequest(
                    model = config.model,
                    messages = prompt.messages,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    thinkingEffort = config.thinkingEffort.toEffortOrNull(),
                    timeoutMs = config.timeoutMs
                )
                // 只把本次请求的等待上限归为超时；上层作用域取消仍向外传播。
                val completed = withTimeoutOrNull(config.timeoutMs) {
                    deps.llm.chatStream(request).collect { delta ->
                        when (delta) {
                            is LlmDelta.Text -> buffer.append(delta.delta)
                            is LlmDelta.Reasoning -> reasoningBuffer.append(delta.delta)
                            is LlmDelta.Finished -> returnedUsage = mergeUsage(returnedUsage, delta)
                            LlmDelta.UsageMissing -> Unit
                        }
                    }
                    true
                }
                if (completed != true) {
                    throw LlmException(LlmError.Failed(
                        LlmErrorKind.TIMEOUT, "请求超过 ${config.timeoutMs / 1000} 秒仍未完成"
                    ))
                }
            } catch (e: LlmException) {
                finish(CallStatus.FAILED, e.error.kind.name)
                Log.error(TAG, "请求失败：${e.error.kind} ${e.error.message}\n${e.error.detail}")
                emit(AgentEvent.Failed(e.error))
                return@flow
            } catch (e: kotlinx.coroutines.CancellationException) {
                finish(CallStatus.CANCELED, "CANCELED")
                throw e
            } catch (e: Throwable) {
                finish(CallStatus.FAILED, "UNKNOWN")
                Log.error(TAG, "请求异常：${e.message}\n$e")
                emit(
                    AgentEvent.Failed(
                        LlmError.Failed(
                            kind = LlmErrorKind.UNKNOWN,
                            message = e.message ?: "未知错误",
                            detail = e.toString()
                        )
                    )
                )
                return@flow
            }

            val elapsed = deps.clock.now() - startedAt
            val raw = buffer.toString()
            Log.fields(
                TAG,
                pairs = arrayOf(
                    "阶段" to "响应",
                    "耗时毫秒" to elapsed,
                    "原始输出字符" to raw.length,
                    "usage" to "输入 ${returnedUsage?.promptTokens ?: "估算 ${plan.estimatedTokens}"} / " +
                        "输出 ${returnedUsage?.completionTokens ?: "本地估算"} / 缓存 ${returnedUsage?.cachedTokens ?: "未知"}"
                )
            )
            Log.body(TAG, "模型原始输出", raw)

            // ---- RF4 解析 ----
            val script = ScriptParser.parse(raw)
            // 事件只记**种类计数**：正文就在上面那条"模型原始输出"里，两边都写会让
            // 一轮日志多出几百字符（真机上量过）。要逐条看正文时看原始输出那条。
            val byKind = script.events.groupingBy { it.kind.wire }.eachCount()
                .entries.joinToString(" ") { "${it.key}×${it.value}" }
            Log.fields(
                TAG,
                level = if (script.degraded) LogLevel.WARN else LogLevel.INFO,
                pairs = arrayOf(
                    "阶段" to "解析",
                    "事件数" to script.events.size,
                    "降级" to script.degraded,
                    "事件构成" to byKind.ifBlank { "-" },
                    "留白" to script.events.count { it.typingHintMs != null || it.gapHintMs != null },
                    "丢弃" to (script.dropped.take(3).joinToString("；").ifBlank { "-" }),
                    "模式提议" to (script.nextMode?.wire ?: "无")
                )
            )

            // 模型选择不说话：不落 assistant 消息，界面也不该冒出空气泡
            if (script.isEmpty) {
                val used = finish(CallStatus.SILENT)
                deps.storage.touchConversation(conversation.id, turn.at)
                emit(AgentEvent.Silent(script.dropped.firstOrNull() ?: "这一轮没有要说的"))
                emit(AgentEvent.UsageRecorded(used.usage, used.money, used.rough))
                return@flow
            }

            // ---- RF5 事件落盘 ----
            //
            // 落盘前先做"与上一轮重复"的抑制。理由是真机日志暴露出来的一个高频现象：
            // 模型连续两轮用同一个词开头（实测「哟」连着出现两次），而用户看到的
            // 就是同一个气泡上下重复。提示词里也加了对应约束，但**光靠提示词不够**：
            // 这是纯可见的观感问题，值得在代码里兜一层。
            val deduped = dropRepeatedFromPreviousTurn(conversation.id, script.events, turnId)
            val persisted = persistEvents(conversation, turnId, script.copy(events = deduped), turn.at)
            // 模式提议：只在配置允许时向上抛，是否切换由 App 侧策略决定。
            //
            // ⚠️ **提议"切到已经在的那个模式"必须在这里就丢掉。** 真机上出现过的现象：
            // 已经在面对面里，模型照样填 `"next_mode": "scene"`，于是界面弹出
            // "看起来你们要见面了" 的卡片 —— 而你们本来就面对面。
            // （诱因是提示词里的示例自己写着 `"next_mode": "scene"`，见 `10-protocol-scene.txt`。）
            //
            // **为什么不靠提示词解决**：提示词只能降低概率，判不掉；而"提议的目标是不是
            // 当前模式"在这里是**确定可知**的。能确定性判断的事不要交给模型，
            // 更不该让用户去点一张无意义的卡片。
            //
            // 判定只留这一处：界面层**不再兜第二遍**。同一件事有两个判定，
            // 以后改坏其中一个也不会有人发现（见 02-handoff §3 ③）。
            val proposed = if (config.allowModeSwitch) script.nextMode else null
            val proposal = proposed?.takeIf { it != conversation.mode }
            if (proposed != null && proposal == null) {
                Log.info(TAG, "模式提议「${proposed.wire}」与当前模式（${conversation.mode.wire}）相同，已忽略：不弹卡片")
            }
            if (proposal != null) {
                // 记一条系统事件（不播出），保证"为什么切成那样"可回溯
                deps.storage.appendEvent(
                    Event(
                        conversationId = conversation.id,
                        kind = EventKind.SCENE,
                        text = "【模式提议】${proposal.wire}：${script.modeReason}",
                        turnId = turnId,
                        mode = conversation.mode,
                        createdAt = turn.at,
                        delivery = app.solochat.core.model.DeliveryState.CANCELED
                    )
                )
            }

            // 生成完成即记账，不依赖播出是否继续；播放打断不抹掉一次已发生的调用。
            val used = finish(CallStatus.SUCCESS)
            deps.storage.touchConversation(conversation.id, turn.at)
            emit(
                AgentEvent.ScriptReady(
                    turnId = turnId,
                    events = persisted,
                    modeProposal = proposal,
                    modeReason = script.modeReason,
                    degraded = script.degraded
                )
            )

            // ⚠️ **assistant 侧历史不在这里写**（原来在这里，是个严重的 bug）。
            //
            // 原来的代码是：
            //
            // ```
            // val assistantText = PromptBuilder.eventsToAssistantText(persisted)
            // if (assistantText.isNotBlank()) deps.storage.appendMessage(...)   // 永远不执行
            // ```
            //
            // `eventsToAssistantText` 只收"已经出口"的事件（`usableAsContext`），而此刻
            // 这些事件**全部还是 `PENDING`** —— 播出发生在返回之后。于是 `assistantText`
            // 恒为空字符串，`appendMessage` 一次都没执行过。
            //
            // 后果极其隐蔽：**界面上完全看不出问题**（时间轴走的是"事件"那条路），
            // 但模型侧的上下文里只有对方的发言，它会把自己上一轮已经回答过的内容
            // 当成新话题重新答一遍 —— 用户报的原话是"它又重新回答了'我在睡觉'"。
            //
            // 现在改由**播出方**（SessionController）在播出/打断落定之后写这条消息 ——
            // 那一刻才真正知道"哪几句说出去了"。约定与用户消息一致：
            // **写入由知道结果的那一层负责，执行器只负责产出事件。**

            // ---- RF6 记账 ----
            emit(AgentEvent.UsageRecorded(used.usage, used.money, used.rough))

            // 会话的 updatedAt 更新，让列表排序正确
        } finally {
            if (!terminal) finish(CallStatus.INTERRUPTED, "未完成收尾")
        }
    }

    // ------------------------------------------------------------------

    /**
     * 丢掉"与上一轮助手消息完全相同"的 `say`。
     *
     * 真机日志里出现过：用户连着两次发"嗨喽"，模型也就连着两次用「哟」开头 ——
     * 界面上就是同一个气泡上下重复，观感很差（而模型自己似乎完全没意识到）。
     *
     * 判据刻意保守，避免误杀合理的重复：
     *
     * - 只看**上一轮的 assistant 消息**（再往前不管：隔了几轮又说同一句是正常的呼应）
     * - 只处理 `say`（动作/旁白/记忆不参与）
     * - 只比对**非空且短**的文本（≤20 字）
     * - **一轮不能全被丢掉**：如果消完一条不剩，就原样保留 —— 那说明模型就是要这么说，
     *   宁可重复也别让用户收到一个空白回合
     *
     * 另外只在句子级比对，不做"包含"判断：模型说"哟"和"哟，是你啊"是两句不同的话。
     */
    private suspend fun dropRepeatedFromPreviousTurn(
        conversationId: String,
        events: List<ScriptEvent>,
        turnId: Long
    ): List<ScriptEvent> {
        if (events.isEmpty()) return events

        // 取最近的历史消息，把**上一轮的全部** assistant 文本合起来比。
        //
        // ⚠️ 不能只看 `lastOrNull { assistant }` 那一条：assistant 历史现在是
        // **一个气泡一条消息**（播完就落盘，见 SessionController.appendSpokenAssistantLine），
        // 只取最后一条就变成"只跟上一轮的最后一句比"，判据会松掉一大截。
        //
        // ⚠️ 窗口给足 60 而不是 12：同样因为"一个气泡一条消息"，一轮就占 4~6 行，
        // 再加上本轮用户刚落盘的那几条，12 行有可能**整个窗口都是本轮的** ——
        // 于是"上一轮"找不到、`?: return events` 静默跳过去重，功能无声失效
        // （表现只是"偶尔又重复了一句"，看不出是窗口太小）。
        val recent = deps.storage.recentMessages(conversationId, limit = 60)
        val previousTurnId = recent.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.turnId != turnId
        }?.turnId ?: return events
        val previousLines = recent
            .filter { it.role == MessageRole.ASSISTANT && it.turnId == previousTurnId }
            .flatMap { it.text.lines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val isDuplicate = { event: ScriptEvent ->
            event.kind == EventKind.SAY &&
                event.text.isNotBlank() &&
                event.text.length <= 20 &&
                event.text.trim() in previousLines
        }
        val kept = events.filterNot(isDuplicate)

        if (kept.size == events.size) return events
        if (kept.isEmpty()) {
            // 全被丢掉了：说明模型这一轮只想重复上一轮的话。保留原样更诚实，
            // 也比"什么都不说"或"空回合"好。
            Log.warn(TAG, "重复抑制：本轮全部内容与上一轮重复，为避免空回合而保留原样")
            return events
        }

        Log.fields(
            TAG,
            level = LogLevel.WARN,
            pairs = arrayOf(
                "阶段" to "重复抑制",
                "丢掉" to events.filter(isDuplicate)
                    .joinToString(" / ") { "${it.kind.wire}「${it.text}」" },
                "保留" to kept.size,
                "原因" to "与上一轮助手消息完全相同（上一轮：${previousLines.joinToString(" / ").take(40)}）"
            )
        )
        return kept
    }

    private suspend fun persistEvents(
        conversation: Conversation,
        turnId: Long,
        script: Script,
        at: Long
    ): List<Event> {
        val out = mutableListOf<Event>()
        for (scriptEvent in script.events) {
            val event = toEvent(conversation, turnId, scriptEvent, at)
            val id = deps.storage.appendEvent(event)
            out += event.copy(id = id)
        }
        return out
    }

    private fun toEvent(
        conversation: Conversation,
        turnId: Long,
        scriptEvent: ScriptEvent,
        at: Long
    ): Event = Event(
        conversationId = conversation.id,
        kind = scriptEvent.kind,
        // say/act 没有说话者时补一个占位，否则界面上会出现无主气泡；
        // scene 是无人称旁白，保持空。规则只有一份实现，见 [ScriptEvent.resolvedSpeaker]。
        speaker = scriptEvent.resolvedSpeaker,
        text = scriptEvent.text,
        turnId = turnId,
        mode = conversation.mode,
        gapHintMs = scriptEvent.gapHintMs,
        typingHintMs = scriptEvent.typingHintMs,
        waitReason = scriptEvent.waitReason,
        delivery = DeliveryState.PENDING,
        createdAt = at
    )

    private data class UsedUsage(val usage: Usage, val money: Double, val rough: Boolean)


}

/** token 数本地估算。公式粗糙是刻意的：**精度不可得，量级要够用**。 */
object Estimate {
    /** 对实际装配全文与每条角色封装估算；没有 tokenizer，始终只是本地估算。 */
    fun promptTokens(messages: List<app.solochat.core.llm.ApiMessage>): Int =
        (3L + messages.sumOf { roughTokensOf(it.content).toLong() + roughTokensOf(it.role) + 4 })
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** 中文按字符、其余约四字符一 token；仅是本地近似，不宣称 tokenizer 精度。 */
    fun roughTokensOf(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x3000..0x9FFF || ch.code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + other / 4 + 1
    }
}

private fun String.toEffortOrNull(): ThinkingEffort? {
    if (isBlank()) return null
    return ThinkingEffort.entries.firstOrNull { it.name.equals(this, ignoreCase = true) || it.wire == lowercase() }
}
