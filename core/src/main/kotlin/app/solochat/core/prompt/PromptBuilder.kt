package app.solochat.core.prompt

import app.solochat.core.llm.ApiMessage
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import app.solochat.core.protocol.ScriptParser

/**
 * 装配一次请求所需的全部材料。
 */
data class PromptContext(
    val resources: PromptResources,
    val persona: Persona,
    val mode: ChatMode,

    /**
     * 当前时间（毫秒）。
     *
     * 为什么要传进来：模型对"现在几点、今天几号"完全没有概念，而聊天里大量语义
     * 依赖时间（"这么晚还不睡""国庆最后一天了""明天上课"）。QQ-agent 在输入末尾
     * 固定给一个【当前时间】锚点，这套做法直接沿用 —— 它的效果确实好。
     */
    val now: Long = System.currentTimeMillis(),

    /** 关于用户的长期记忆（已经在外面排好序，这里不再动）。 */
    val memories: List<Memory> = emptyList(),

    /** 已压缩的场景摘要，按时间正序。 */
    val summaries: List<SceneSummary> = emptyList(),

    /**
     * 已出口的历史事件（只含 `delivery.spoken` 的）。
     * 未出口的绝不能进来 —— 否则模型会以为自己说过（见 Event.DeliveryState 注释）。
     */
    val spokenEvents: List<Event> = emptyList(),

    /** 历史消息（user / assistant / system），按时间正序。 */
    val history: List<Message> = emptyList(),

    /** 最近一轮被中途打断的事实，要显式告知模型。 */
    val interruptedNote: String = "",

    /**
     * 引擎插的"此刻发生了什么"（例如刚切到面对面）。
     *
     * 与 [interruptedNote] 同类：这是**引擎知道的事实**，不是任何一方说的话，
     * 所以只进变动区，绝不落成一条消息。放在变动区最末尾 —— 紧接着就是历史，
     * 指令离"现在"越近越管用。
     */
    val cue: String = ""
)

/**
 * 装配结果。
 *
 * 拆出 [stablePrefixChars] 是为了做成本统计：系统前缀越稳定、越长，
 * KV 缓存命中的比例越高（命中约 1/10 价，但不是免费）。这个数会显示在会话详情里，
 * 让"为什么这轮便宜"变得可解释。
 */
data class BuiltPrompt(
    val messages: List<ApiMessage>,
    val stablePrefixChars: Int,
    val sections: List<String>,
    /**
     * 历史里各角色各有多少条，形如 `对方 5 条 / 我 4 条 / 系统 1 条`。
     *
     * 为什么要专门算这个：**"模型看不到自己说过的话"这个 bug 在界面上是完全隐形的**。
     * 界面的时间轴走的是"事件 + 消息"的投影，对方说过的话一条不少；
     * 而装配上下文读的是**消息表**。两者一旦分叉（事件在、消息不在），
     * 模型就会把好几轮当成一轮来回答，而日志里只看"请求条数"是发现不了的 ——
     * 条数看起来永远正常，只是里面全是对对方的记录。
     */
    val historyBreakdown: String
) {
    /** 历史里"我"说过几条 —— 0 就说明模型这一轮看不到自己的任何发言。 */
    val assistantHistoryCount: Int
        get() = Regex("我 (\\d+) 条").find(historyBreakdown)?.groupValues?.get(1)?.toInt() ?: 0
}

/**
 * 提示词装配。
 *
 * **最重要的一条不变式**：系统前缀（安全规则 + 人物卡 + 协议 + 风格）在任何轮次、
 * 任何模式下都逐字节相同 —— 人物卡不变则前缀不变。这既是 KV 缓存命中的前提，
 * 也让"模型今天怎么变了"这种问题可以排除掉提示词漂移的可能。
 *
 * 变动的部分（记忆、摘要、最近事件）一律放在前缀之后。
 */
object PromptBuilder {

    /** 记忆注入的总字符上限。太多了会把上下文挤爆，也稀释注意力。 */
    const val MEMORY_BUDGET_CHARS = 2_000

    fun build(ctx: PromptContext): BuiltPrompt {
        val sections = mutableListOf<String>()

        // 占位符替换：提示词文件写成"你是「{{persona_name}}」"，这里填进去。
        // 放在 build 里而不是加载时，是为了让同一份资源能被不同人物卡复用（测试也好写）。
        val resources = ctx.resources.substitute(
            name = ctx.persona.name.ifBlank { "ta" },
            modeLabel = if (ctx.mode == ChatMode.SCENE) "面对面" else "发消息"
        )

        // ---- 稳定前缀：顺序即优先级，不得调整 ----
        sections += resources.safetyRules.trim()

        // 人设块**按模式取**：面对面才注入「面对面时怎么说话」「它身边的样子」。
        // 在聊天模式里给模型一堆环境素材与当面的表达方式，它就会开始写小作文，
        // 而"像在发微信"恰恰要求它别这么做。见 docs/03-persona-format.md。
        val personaBlock = ctx.persona.toPromptBlock(ctx.mode)
        if (personaBlock.isNotBlank()) sections += personaBlock

        sections += buildProtocolBlock(resources, ctx.mode)

        val styleBlock = when (ctx.mode) {
            ChatMode.CHAT -> resources.styleChat
            ChatMode.SCENE -> resources.styleScene
        }
        if (styleBlock.isNotBlank()) sections += styleBlock.trim()

        // 记忆的写法（`remember` 事件）两种模式都要有，否则模型只会"用"记忆不会"写"记忆
        if (resources.memory.isNotBlank()) sections += resources.memory.trim()

        // ---- 前缀结束 ----
        val prefix = sections.joinToString("\n\n").trim()
        val prefixChars = prefix.length

        val volatile = buildVolatileBlock(ctx, resources)

        val systemContent = if (volatile.isBlank()) prefix else "$prefix\n\n$volatile"
        val messages = mutableListOf(ApiMessage(role = "system", content = systemContent))
        messages += renderHistory(ctx)

        return BuiltPrompt(
            messages = messages,
            stablePrefixChars = prefixChars,
            sections = sections,
            historyBreakdown = ctx.history
                .groupingBy { it.role }
                .eachCount()
                // 固定顺序，否则同一个状态两次渲染出来的字符串可能不同（map 的迭代序不保证）
                .toSortedMap(compareBy { it.ordinal })
                .entries
                .joinToString(" / ") { (role, count) ->
                    val label = when (role) {
                        MessageRole.USER -> "对方"
                        MessageRole.ASSISTANT -> "我"
                        MessageRole.SYSTEM -> "系统"
                    }
                    "$label $count 条"
                }
        )
    }

    // ------------------------------------------------------------------

    /**
     * 协议说明 + 示例。
     *
     * 为什么这里要花篇幅放完整示例：模型对"输出格式"的学习几乎完全靠 few-shot。
     * 只写字段表不写例子，实测会有相当比例的轮次回退成纯文本（虽然我们有降级兜底，
     * 但那等于浪费了这一轮的结构化能力）。
     */
    private fun buildProtocolBlock(resources: PromptResources, mode: ChatMode): String {
        val protocol = when (mode) {
            ChatMode.CHAT -> resources.protocolChat
            ChatMode.SCENE -> resources.protocolScene
        }
        return buildString {
            appendLine("### 输出格式")
            appendLine(
                "你每次只被调用一次，要在这**一次回复**里把这一轮想说的全部说完，" +
                    "并给出每条之间的节奏。不要调用工具，不要输出任何 JSON 之外的解释。"
            )
            appendLine()
            appendLine(protocol.trim())
        }.trimEnd()
    }

    /**
     * 变动部分：摘要、记忆、被打断提示、当前模式提醒。
     * 顺序上把"最近发生了什么"放在最后，紧贴历史。
     */
    private fun buildVolatileBlock(ctx: PromptContext, resources: PromptResources): String {
        val parts = mutableListOf<String>()

        if (ctx.summaries.isNotEmpty()) {
            parts += buildString {
                appendLine("### 之前发生过的事（已压缩）")
                for (s in ctx.summaries) appendLine("- ${s.text.trim()}")
            }.trimEnd()
        }

        if (ctx.memories.isNotEmpty()) {
            var budget = MEMORY_BUDGET_CHARS
            val lines = mutableListOf<String>()
            for (m in ctx.memories) {
                val line = "- ${m.content.trim()}"
                if (line.length > budget) break
                budget -= line.length
                lines += line
            }
            if (lines.isNotEmpty()) {
                parts += buildString {
                    appendLine("### 关于对方，你记得的事")
                    appendLine("（这些是你以前记住的，不要在对话里逐条复述，自然用上就行）")
                    lines.forEach { appendLine(it) }
                }.trimEnd()
            }
        }

        if (ctx.interruptedNote.isNotBlank()) {
            parts += "### 刚才被打断了\n${ctx.interruptedNote.trim()}"
        }

        parts += "### 当前模式\n" + when (ctx.mode) {
            ChatMode.CHAT -> "你们在发消息聊天。一条一条发，像真人用手机打字。"
            ChatMode.SCENE -> "你们现在面对面，同处一个场景。写这个场景里发生的事。"
        }

        // 引擎插的"此刻该干嘛"。放最末尾：它离历史最近，指令最不容易被忽略。
        if (ctx.cue.isNotBlank()) {
            parts += "### 刚刚\n${ctx.cue.trim()}"
        }

        return parts.joinToString("\n\n")
    }

    /**
     * 历史渲染。
     *
     * **格式照搬 QQ-agent 的输入模式**（`exampleInput.txt` 里那份），因为它在真机上
     * 效果确实好，而我自己那版丢了三样关键的东西：
     *
     * 1. **每条带时间戳** `[10-07 09:04]`。没有时间，模型分不清"刚才"和"很久以前"，
     *    也读不出"这么晚还不睡"里的"晚"。
     * 2. **自己说的话标"我："**。我原来靠 `role=assistant` 区分，但模型对自己的输出
     *    往往是"读过就算"，缺少明确的第一人称归属，于是容易重复自己刚说过的话。
     * 3. **末尾一个【当前时间】锚点**。这是整段语义的落脚点。
     *
     * 全部历史放进**一条 user 消息**里（而不是多条 user/assistant 交替），
     * 也是刻意的：这样"谁在什么时候说了什么"是一份完整的记录，
     * 而模型只需要回答"接下来我说什么"。
     */
    private fun renderHistory(ctx: PromptContext): List<ApiMessage> {
        val lines = mutableListOf<String>()

        // 系统事实（模式切换等）也进这份记录，用【】标出，避免被当成谁说的话
        for (m in ctx.history) {
            val text = m.text.trim()
            if (text.isBlank()) continue
            val stamp = stampOf(m.createdAt)
            when (m.role) {
                MessageRole.USER -> lines += "[$stamp] 对方：$text"
                MessageRole.ASSISTANT -> lines += repairSpoiledAssistantText(text)
                    .split('\n')
                    .filter { it.isNotBlank() }
                    .map { "[$stamp] 我：$it" }
                MessageRole.SYSTEM -> lines += "[$stamp] $text"
            }
        }

        if (lines.isEmpty()) {
            return listOf(
                ApiMessage(role = "user", content = "（这是你们的第一次对话，还没有历史）")
            )
        }

        // 最后一条是对方刚发的 —— 单独标出来，让"该我说话了"这件事没有歧义
        val lastIndex = lines.lastIndex
        val body = lines.mapIndexed { index, line ->
            if (index == lastIndex && ctx.history.lastOrNull()?.role == MessageRole.USER) {
                "$line   ← 对方刚发的，等你回应"
            } else {
                line
            }
        }.joinToString("\n")

        return listOf(
            ApiMessage(
                role = "user",
                content = buildString {
                    appendLine("【你们的聊天记录】按时间排序，你的发言标为「我：」，对方标为「对方：」")
                    appendLine(body)
                    appendLine()
                    appendLine("【当前时间】${formatNow(ctx.now)}")
                    appendLine("【现在】接着上面的记录，用 JSON 输出你这一轮想说的话。")
                }.trimEnd()
            )
        )
    }

    /** 时间戳格式 `MM-dd HH:mm`，与 QQ-agent 一致。 */
    private fun stampOf(at: Long): String {
        if (at <= 0) return "??-?? ??:??"
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = at
        fun pad(v: Int) = v.toString().padStart(2, '0')
        return buildString {
            append(pad(calendar.get(java.util.Calendar.MONTH) + 1))
            append('-')
            append(pad(calendar.get(java.util.Calendar.DAY_OF_MONTH)))
            append(' ')
            append(pad(calendar.get(java.util.Calendar.HOUR_OF_DAY)))
            append(':')
            append(pad(calendar.get(java.util.Calendar.MINUTE)))
        }
    }

    /** 当前时间要带星期 —— "明天要上课"这种话需要知道今天周几。 */
    private fun formatNow(now: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        fun pad(v: Int) = v.toString().padStart(2, '0')
        return buildString {
            append(calendar.get(java.util.Calendar.YEAR))
            append('-')
            append(pad(calendar.get(java.util.Calendar.MONTH) + 1))
            append('-')
            append(pad(calendar.get(java.util.Calendar.DAY_OF_MONTH)))
            append(' ')
            append(pad(calendar.get(java.util.Calendar.HOUR_OF_DAY)))
            append(':')
            append(pad(calendar.get(java.util.Calendar.MINUTE)))
            append('（')
            append(weekdays[calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1])
            append('）')
        }
    }

    /**
     * 把已出口的事件按轮次合并成 assistant 侧文本。
     * 这是"事件 → 消息"的关键翻译：模型下一轮看到的应该是自己发的气泡，
     * 而不是一串带 id 和状态的行。
     */
    fun eventsToAssistantText(events: List<Event>): String {
        val lines = mutableListOf<String>()
        for (e in events) {
            if (!e.usableAsContext) continue
            val body = e.displayText ?: continue
            renderEventLine(e.kind, e.speaker, body)?.let { lines += it }
        }
        return lines.joinToString("\n")
    }

    /**
     * 一条事件的"历史行"写法。**这是模型区分"说出口的话"与"做了什么事"的唯一依据**，
     * 所以历史重建（[repairSpoiledAssistantText]）与正常落盘（[eventsToAssistantText]）
     * 必须共用它 —— 两处写成两种格式，模型就会把动作当成台词。
     *
     * 返回 null = 这一条不进历史（`wait` / `read` 没有正文；`remember` 是写给自己的笔记，
     * 进了上下文模型会在聊天里把记忆内容复述出来）。
     */
    private fun renderEventLine(kind: EventKind, speaker: String, body: String): String? {
        if (body.isBlank()) return null
        return when (kind) {
            EventKind.SAY -> body
            // 动作与旁白加标记，让模型能区分"说的是话"还是"做的是事"
            EventKind.ACT -> if (speaker.isBlank()) "*$body*" else "*$speaker$body*"
            EventKind.SCENE -> "（$body）"
            EventKind.WAIT, EventKind.READ, EventKind.REMEMBER -> null
        }
    }

    /**
     * 历史里"整段 JSON 被当成一条消息"的那些轮 —— **不能原样喂回给模型**。
     *
     * 为什么要专门处理（真机实测，不是洁癖）：解析失败的那一轮，模型的原文会被
     * 降级成一条 `say` 落进消息表，于是历史里出现一整块 JSON。而模型读历史时看到的
     * 是**自己上一次的输出**，里面赫然写着 `{ "type": "typing_ms": 1800 }` ——
     * 它把这当成"协议允许的写法"照抄，同一处错误一轮一轮复制自己。
     *
     * 实测（2026-10-08，一次请求的提示词）：**11 处**这样的历史行，
     * 而正文里那句"`typing_ms` 不是一种事件类型"的警告就在它们上面几行 ——
     * 只改提示词治不了这个环，必须让历史里不再出现这个"范例"。
     *
     * 解析层现在能救回绝大部分（见 [ScriptParser]），救回来就按正常格式还原成几行；
     * **救不回来的原样留着** —— 那种情况我们也不知道它本来想说什么，
     * 编一份出来比留着更糟（docs/01-decisions.md D11）。
     */
    private fun repairSpoiledAssistantText(text: String): String {
        if (!text.startsWith("{") || !text.contains("\"events\"")) return text
        val script = ScriptParser.parse(text)
        if (script.degraded || script.events.isEmpty()) return text
        val lines = script.events.mapNotNull { renderEventLine(it.kind, it.speaker, it.text) }
        return if (lines.isEmpty()) text else lines.joinToString("\n")
    }
}
