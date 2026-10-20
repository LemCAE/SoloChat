package app.solochat.data.logweb

import app.solochat.SoloChatApp
import app.solochat.core.agent.AgentEvent
import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.AgentTurn
import app.solochat.core.agent.AgentTurnRunner
import app.solochat.core.log.Log
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Usage
import app.solochat.play.ChatItem
import app.solochat.play.ChatProjector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 给日志网页用的 HTTP 测试接口。
 *
 * **动因**：排查"第几条为什么这么排""为什么回这个"时，只能靠截图和导日志，
 * 一轮要花几十秒，而且没法确定性地重放。有接口之后可以直接：
 *
 * ```powershell
 * curl -X POST "http://127.0.0.1:8787/api/send?t=密码" `
 *      -d "你好，在干嘛" -H "Content-Type: text/plain; charset=utf-8"
 * ```
 *
 * 它会**同步等到这一轮跑完**，把派生的数据一起返回：
 * 用户消息、模型原始输出、解析出的事件（含顺序与 gap）、对方实际会发出的气泡、
 * 以及这一轮花掉的钱。这样"输入 X → 输出 Y"在一个响应里就完整了，
 * 不用再去别处捞日志。
 *
 * 排在**与 /api/logs 同一道密码之后**：它返回的是对话内容，暴露程度和日志一样。
 */
class TestGateway internal constructor(
    private val deps: AgentDeps,
    private val deleteStoredConversation: suspend (String) -> Unit
) {
    constructor(app: SoloChatApp) : this(app.deps, { app.store.deleteConversation(it) })

    /** 跟随模型超时并留出落盘余量；测试接口不播出，不额外等待气泡节奏。 */
    private val turnTimeoutMs: Long get() = deps.config().timeoutMs + 30_000L

    data class TurnResult(
        val conversationId: String,
        val userText: String,
        val rawOutput: String,
        val events: List<EventView>,
        val bubbles: List<String>,
        val model: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int,
        val cost: Double,
        val rough: Boolean,
        val error: String
    )

    data class EventView(
        val kind: String,
        val speaker: String,
        val text: String,
        val gapHintMs: Int?,
        val typingHintMs: Int?
    )

    /**
     * 发送一条用户消息并等这一轮跑完。
     *
     * **刻意不经过 [app.solochat.play.SessionController]**：那是给界面用的，
     * 带有防抖、抢占、逐条播出等交互语义，而测试接口要的是"立刻、确定、一次跑完"。
     * 所以这里直接走 [app.solochat.core.agent.AgentTurnRunner]，共用同一份
     * 上下文装配与解析逻辑（也就是被测的东西），但跳过所有交互节奏。
     */
    suspend fun send(conversationId: String?, text: String, reset: Boolean): TurnResult {
        val body = text.trim()
        val conversation = resolveConversation(conversationId, reset)
        val now = deps.clock.now()

        // 落盘用户消息（与界面路径一致：用户消息由调用方写，执行器只读）
        deps.storage.appendMessage(
            Message(
                conversationId = conversation.id,
                role = MessageRole.USER,
                text = body,
                turnId = now,
                createdAt = now,
                sentToModel = true
            )
        )

        val runner = AgentTurnRunner(deps)
        val turn = AgentTurn(
            conversationId = conversation.id,
            userText = body,
            at = now
        )

        var raw = ""
        val events = mutableListOf<EventView>()
        var bubbles = listOf<String>()
        var usage: Usage? = null
        var error = ""
        var cost = 0.0
        var rough = true

        val finished = withTimeoutOrNull(turnTimeoutMs) {
            runner.run(turn).collect { event ->
                when (event) {
                    is AgentEvent.ScriptReady -> {
                        events.clear()
                        events += event.events.map {
                            EventView(
                                kind = it.kind.wire,
                                speaker = it.speaker,
                                text = it.text,
                                gapHintMs = it.gapHintMs,
                                typingHintMs = it.typingHintMs
                            )
                        }
                        // 模型原始输出：从日志缓冲里取最后一条"模型原始输出"
                        raw = lastRawFromLog()
                        bubbles = event.events.mapNotNull { it.displayText }
                    }

                    is AgentEvent.UsageRecorded -> {
                        usage = event.usage
                        cost = event.cost
                        rough = event.rough
                    }

                    is AgentEvent.Failed -> error = "${event.error.kind}: ${event.error.message}"
                    else -> Unit
                }
            }
            true
        } ?: false

        if (!finished) error = "等这一轮超时（${turnTimeoutMs / 1000} 秒）"

        return TurnResult(
            conversationId = conversation.id,
            userText = body,
            rawOutput = raw,
            events = events,
            bubbles = bubbles,
            model = deps.config().model,
            promptTokens = usage?.promptTokens ?: 0,
            completionTokens = usage?.completionTokens ?: 0,
            cachedTokens = usage?.cachedTokens ?: 0,
            cost = cost,
            rough = rough,
            error = error
        )
    }

    /** 会话列表（含标题、模式、条数）。 */
    suspend fun conversations(): List<Map<String, Any?>> = deps.storage.listConversations().map { c ->
            mapOf(
                "id" to c.id,
                "title" to c.title,
                "mode" to c.mode.wire,
                "updatedAt" to c.updatedAt,
                "updatedAtText" to stamp(c.updatedAt)
            )
        }

    /**
     * 取历史。
     *
     * 直接复用界面那套投影（[ChatProjector]），
     * 所以返回的就是"界面上本该看到的东西" —— 排查顺序问题时这一点很关键：
     * 如果接口返回的顺序是对的而界面上不对，问题就在渲染层。
     */
    suspend fun history(conversationId: String, limit: Int): List<Map<String, Any?>> {
        val events = deps.storage.eventsOf(conversationId)
        val messages = deps.storage.recentMessages(conversationId, limit = 400)
        val items = ChatProjector.project(events, messages)

        return items.takeLast(limit).map { item ->
            when (item) {
                is ChatItem.Mine -> mapOf(
                    "who" to "me",
                    "at" to item.at,
                    "time" to stamp(item.at),
                    "text" to item.text
                )

                is ChatItem.Theirs -> mapOf(
                    "who" to "them",
                    "at" to item.at,
                    "time" to stamp(item.at),
                    "text" to item.text,
                    "sameGroup" to item.sameGroup
                )

                is ChatItem.Action -> mapOf(
                    "who" to "action",
                    "speaker" to item.speaker,
                    "text" to item.text
                )

                // 我的动作：`who` 用 `my_action` 而不是 `action`，
                // 因为"谁做的"是面对面模式里最要紧的信息，接口上不能含糊。
                is ChatItem.MyAction -> mapOf(
                    "who" to "my_action",
                    "text" to item.text
                )

                is ChatItem.Narration -> mapOf(
                    "who" to "scene",
                    "text" to item.text
                )

                is ChatItem.Divider -> mapOf(
                    "who" to "system",
                    "text" to item.text
                )

                is ChatItem.Live -> mapOf(
                    "who" to "live",
                    "text" to item.text
                )
            }
        }
    }

    /** 删掉一个会话（含消息与事件）。测试用，方便从干净状态开始。 */
    suspend fun deleteConversation(conversationId: String) {
        deleteStoredConversation(conversationId)
        Log.info("测试接口", "已删除会话 $conversationId")
    }

    /**
     * 消息表原样导出。
     *
     * 存在的理由很具体：**"模型看不到自己说过的话"** 这个问题在 `/api/history` 上是
     * 看不见的 —— 那个接口走的是"事件 + 消息"的界面投影，看起来一切正常。
     * 而装配上下文读的是**消息表**。两者一旦分叉（事件在、消息不在），
     * 表现就是"模型把好几轮当成一轮来回答"，而从界面上完全看不出来。
     * 所以需要一个直接看消息表的口子。
     */
    suspend fun messages(conversationId: String, limit: Int): List<Map<String, Any?>> =
        deps.storage.recentMessages(conversationId, limit = limit).map { m ->
            mapOf(
                "id" to m.id,
                "role" to m.role.name.lowercase(),
                "turnId" to m.turnId,
                "at" to m.createdAt,
                "time" to stamp(m.createdAt),
                "text" to m.text,
                "sentToModel" to m.sentToModel
            )
        }

    /**
     * 拿到会话；`reset` 为 true 时新建一个干净的。
     *
     * `reset` 只表示忽略传入 id、新建专用测试会话。旧会话与旧测试会话都保留；
     * 清理是独立的显式操作，不能藏在发送路径里。
     */
    private suspend fun resolveConversation(conversationId: String?, reset: Boolean): Conversation {
        val existingId = conversationId?.takeIf { it.isNotBlank() }
            ?.takeIf { !reset }
            ?.let { deps.storage.getConversation(it) }
        if (existingId != null) return existingId

        val now = deps.clock.now()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "测试会话 " + stamp(now),
            mode = ChatMode.CHAT,
            modeSince = now,
            createdAt = now,
            updatedAt = now
        )
        deps.storage.upsertConversation(conversation)
        return conversation
    }

    /**
     * 从日志缓冲里捞最近一条"模型原始输出"。
     *
     * 为什么从日志取而不是让 runner 返回：原始输出目前只存在于日志里
     * （runner 的 ScriptReady 只带解析后的事件）。改动 runner 的返回结构
     * 会牵动界面路径，而这只是测试便利，不值得。捞日志足够可靠 ——
     * 请求和响应是同步发生的，中间不会插进别的模型调用。
     */
    private fun lastRawFromLog(): String {
        val entry = Log.entries.value.lastOrNull { it.text.startsWith("模型原始输出") }
            ?: return ""
        // 文本形如 "模型原始输出（57 字符）:\n{...}"
        val newline = entry.text.indexOf('\n')
        return if (newline >= 0) entry.text.substring(newline + 1).trim() else ""
    }

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun stamp(at: Long): String =
        if (at <= 0) "-" else synchronized(stampFormat) { stampFormat.format(Date(at)) }
}
