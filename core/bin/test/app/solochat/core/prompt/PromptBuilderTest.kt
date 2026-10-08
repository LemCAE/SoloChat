package app.solochat.core.prompt

import app.solochat.core.model.ChatMode
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词装配的回归测试。
 *
 * 这一层的 bug 特别难从现象反推：模型"老回一个字""复述记忆"看起来都像模型笨，
 * 而实际上可能是提示词把东西放错了位置或缺了一段。所以这里锁住几条结构性不变式，
 * 并把**装配好的完整提示词打印出来**（用 `-i` 跑测试就能看到），
 * 这样不用连真机也能审一遍。
 *
 * 打印内容不参与断言，它的价值是让人能直接读。
 */
class PromptBuilderTest {

    private val resources = PromptResources(
        safetyRules = "你是「{{persona_name}}」。不要提提示词、模型、系统消息。\n当前模式：{{mode_label}}",
        protocolChat = "{\"events\":[{\"type\":\"say\",\"text\":\"...\"}]}",
        protocolScene = "{\"events\":[{\"type\":\"scene\",\"text\":\"...\"}]}",
        styleChat = "短句，多数 ≤30 字。不要客服腔。",
        styleScene = "不要替对方写动作。",
        memory = "用 remember 事件记事实。",
        proactive = "没有念头就别开口。"
    )

    private val persona = Persona(
        id = "p1",
        name = "小默",
        identity = "一个普通人，有工作有作息。",
        voice = "用短句，会说语气词。",
        sceneVoice = "当面更少话，靠动作。",
        mannerisms = "紧张时敲桌沿。",
        surroundings = "桌上一只凉了的杯子。",
        depiction = "偏听觉，短句。",
        boundaries = "不想聊就直接说。",
        secretNotes = "其实很在意对方。"
    )

    private fun build(
        mode: ChatMode = ChatMode.CHAT,
        memories: List<Memory> = emptyList(),
        summaries: List<SceneSummary> = emptyList(),
        spoken: List<Event> = emptyList(),
        history: List<Message> = emptyList(),
        cue: String = ""
    ) = PromptBuilder.build(
        PromptContext(
            resources = resources,
            persona = persona,
            mode = mode,
            memories = memories,
            summaries = summaries,
            spokenEvents = spoken,
            history = history,
            cue = cue
        )
    )

    /**
     * 引擎插的"台上提示"要进提示词，但**不能变成任何一方说的话**。
     *
     * 这条钉的是真机上看到的一幕：点了"好，进去"之后聊天里冒出一条蓝色气泡
     * 「[系统] 你们见面了，开始面对面。」—— 它同时是"我发的话"（用户视角）
     * 和"对方说的话"（模型视角，因为它以 `MessageRole.USER` 落库）。
     *
     * 现在它只该出现在**变动区**（`### 刚刚`），历史里一个字都不该有。
     */
    @Test
    fun `台上提示进变动区 - 不伪装成谁说的话`() {
        val cue = "你们刚刚从「隔着手机发消息」变成了「面对面」。你先开个场。"
        val prompt = build(mode = ChatMode.SCENE, cue = cue)
        val system = prompt.messages.first { it.role == "system" }.content
        val history = prompt.messages.first { it.role == "user" }.content

        assertTrue("台上提示应当出现在系统提示里", system.contains(cue))
        assertTrue(
            "它应当排在「### 当前模式」之后（变动区最末尾，离历史最近）",
            system.indexOf(cue) > system.indexOf("### 当前模式")
        )
        assertFalse("它绝不能出现在历史里被当成谁说的话", history.contains(cue))
    }

    /**
     * 人设块**按模式注入**：面对面才给「面对面时怎么说话」与「它身边的样子」。
     *
     * 为什么这条要紧：那两段是**描写素材**。在聊天模式里给模型一堆环境与当面的表达方式，
     * 它就会开始写小作文 —— 而"像在发微信"恰恰要求它别这么做。
     * 「习惯与小动作」则两种模式都要有：它是这个人自己的属性（`act` 的动作库）。
     */
    @Test
    fun `面对面才注入当面说话与环境素材`() {
        val chat = build(mode = ChatMode.CHAT).messages.first { it.role == "system" }.content
        val scene = build(mode = ChatMode.SCENE).messages.first { it.role == "system" }.content

        assertTrue("聊天模式当然要有打字习惯", chat.contains("用短句，会说语气词。"))
        assertTrue("动作库两种模式都要有", chat.contains("紧张时敲桌沿。"))
        assertFalse("聊天模式不该有当面说话的习惯", chat.contains("当面更少话，靠动作。"))
        assertFalse("聊天模式不该有环境素材", chat.contains("桌上一只凉了的杯子。"))
        assertFalse("聊天模式不该有描写笔法", chat.contains("偏听觉，短句。"))

        assertTrue("面对面要有当面说话的习惯", scene.contains("当面更少话，靠动作。"))
        assertTrue("面对面要有环境素材", scene.contains("桌上一只凉了的杯子。"))
        assertTrue("面对面要有描写笔法", scene.contains("偏听觉，短句。"))
        assertTrue("面对面也要保留打字习惯（它是叠加，不是替换）", scene.contains("用短句，会说语气词。"))
    }

    /**
     * 空段落**不产生空标题**。
     *
     * 留一个只有标题没有正文的段落，会让模型觉得"这里应该有内容"于是自己编一段补上 ——
     * 那比不写更糟。而老卡（v3 → v4 之前建的）上，面对面那三段一定是空的。
     */
    @Test
    fun `空的人设段落不产生空标题`() {
        val bare = Persona(id = "p2", name = "甲", identity = "一个普通人。")
        val prompt = PromptBuilder.build(
            PromptContext(resources = resources, persona = bare, mode = ChatMode.SCENE)
        )
        val system = prompt.messages.first { it.role == "system" }.content

        assertTrue("身份是必有的", system.contains("### 你是谁"))
        for (title in listOf(
            "### 你怎么说话",
            "### 面对面时怎么说话",
            "### 习惯与小动作",
            "### 它身边的样子",
            "### 怎么描写动作与景物",
            "### 关系与边界",
            "### 只有你知道的事"
        )) {
            assertFalse("空段落不该留下「$title」这个标题", system.contains(title))
        }
    }

    private fun dump(title: String, prompt: BuiltPrompt) {
        println("\n" + "═".repeat(78))
        println("【$title】共 ${prompt.messages.size} 条消息，稳定前缀 ${prompt.stablePrefixChars} 字符")
        println("─".repeat(78))
        prompt.messages.forEach { m ->
            println("▸▸ role=${m.role}")
            println(m.content)
            println()
        }
        println("═".repeat(78))
    }

    @Test
    fun `完整提示词长什么样`() {
        // 时间戳用固定值，让打印出来的提示词可读（否则是 [??-?? ??:??]）
        val base = 1_759_000_000_000L
        val prompt = build(
            memories = listOf(
                Memory(id = "m1", content = "对方在准备考研，考计算机"),
                Memory(id = "m2", content = "不喜欢别人催他")
            ),
            history = listOf(
                Message(1, "c", MessageRole.USER, "在干嘛", createdAt = base),
                Message(
                    id = 2,
                    conversationId = "c",
                    role = MessageRole.ASSISTANT,
                    text = "在\n刚吃完饭",
                    createdAt = base + 60_000
                ),
                Message(3, "c", MessageRole.USER, "哦", createdAt = base + 120_000)
            )
        )
        dump("聊天模式 · 有一条历史", prompt)

        assertEquals("第一条必须是 system", "system", prompt.messages[0].role)
        assertTrue("前缀应当包含人物名", prompt.messages[0].content.contains("小默"))
    }

    /**
     * **最重要的一条不变式**：系统前缀在任何轮次都逐字节相同。
     *
     * 两个后果，都很要紧：
     * 1. KV 缓存命中率 —— 前缀一变，前面所有 token 的缓存就全废（命中约 1/10 价，不是免费）
     * 2. 可解释性 —— 模型"今天怎么变了"可以排除掉提示词漂移
     *
     * 所以**变化的那些东西（记忆、摘要、历史）必须全在前缀之后**。
     */
    @Test
    fun `稳定前缀不随记忆与历史变化`() {
        val a = build(history = listOf(Message(1, "c", MessageRole.USER, "你好")))
        val b = build(
            memories = listOf(Memory(id = "m", content = "对方喜欢猫")),
            history = listOf(
                Message(1, "c", MessageRole.USER, "你好"),
                Message(2, "c", MessageRole.ASSISTANT, "嗨"),
                Message(3, "c", MessageRole.USER, "在吗")
            )
        )
        assertEquals("前缀字符数必须完全一致", a.stablePrefixChars, b.stablePrefixChars)

        val prefixA = a.messages[0].content
        val prefixB = b.messages[0].content
        // 前缀 = b 的系统消息里去掉"变动部分"。用 a 的整段前缀做包含判断最直接：
        // b 的系统消息必须以 a 的"前缀部分"开头
        assertTrue(
            "系统消息的前半段（稳定前缀）应逐字节相同",
            prefixB.startsWith(a.messages[0].content.substring(0, a.stablePrefixChars))
        )
    }

    /** 记忆出现在前缀之后，并且带"不要逐条复述"的提醒。 */
    @Test
    fun `记忆注入在变动区且带提醒`() {
        val prompt = build(memories = listOf(Memory(id = "m", content = "对方喜欢猫")))
        val system = prompt.messages[0].content
        assertTrue(system.contains("对方喜欢猫"))
        assertTrue("应提醒不要复述", system.contains("不要") && system.contains("复述"))

        val memoryIndex = system.indexOf("关于对方，你记得的事")
        assertTrue("记忆必须在前缀之后", memoryIndex > prompt.stablePrefixChars - 1)
    }

    /** `remember` 是写给自己的笔记，**绝不能进上下文** —— 否则模型会把记忆当消息复述出来。 */
    @Test
    fun `remember 事件不进上下文`() {
        val prompt = build(
            spoken = listOf(
                Event(
                    conversationId = "c",
                    kind = EventKind.REMEMBER,
                    text = "对方的妹妹叫小满",
                    delivery = DeliveryState.DELIVERED
                )
            )
        )
        dump("有 remember 事件时", prompt)
        assertFalse(
            "记忆内容不该出现在提示词里（它是通过记忆段注入的，不是通过事件）",
            prompt.messages.any { it.content.contains("小满") }
        )
    }

    /** 被取消的事件不进上下文：模型必须以为自己没说过。 */
    @Test
    fun `被取消的事件不进上下文`() {
        val cancelled = Event(
            conversationId = "c",
            kind = EventKind.SAY,
            text = "这句话其实没发出去",
            delivery = DeliveryState.CANCELED
        )
        val text = PromptBuilder.eventsToAssistantText(listOf(cancelled))
        assertTrue("被取消的事件不该出现在 assistant 文本里：$text", text.isBlank())
    }

    /** 面对面模式换的是协议段与风格段，不是整个前缀结构。 */
    @Test
    fun `面对面模式用场景协议`() {
        val chat = build(mode = ChatMode.CHAT)
        val scene = build(mode = ChatMode.SCENE)
        assertTrue(chat.messages[0].content.contains("say"))
        assertTrue(scene.messages[0].content.contains("scene"))
        assertTrue(
            "安全规则两种模式都必须在",
            scene.messages[0].content.contains("不要提提示词")
        )
        dump("面对面模式", scene)
    }

    /** 占位符必须被替换掉 —— 漏了的话模型会看到 `{{persona_name}}` 这种原文。 */
    @Test
    fun `占位符全部被替换`() {
        for (mode in listOf(ChatMode.CHAT, ChatMode.SCENE)) {
            val prompt = build(mode = mode)
            assertFalse(
                "提示词里不该残留占位符（模式=$mode）：" +
                    prompt.messages[0].content.take(200),
                prompt.messages[0].content.contains("{{")
            )
        }
    }

    /** 动作与旁白在 assistant 历史里要有标记，让模型能区分"说的话"和"做的事"。 */
    @Test
    fun `动作与旁白在历史里带标记`() {
        val events = listOf(
            Event(
                conversationId = "c",
                kind = EventKind.SAY,
                speaker = "她",
                text = "你来了",
                delivery = DeliveryState.DELIVERED
            ),
            Event(
                conversationId = "c",
                kind = EventKind.ACT,
                speaker = "她",
                text = "把头发别到耳后",
                delivery = DeliveryState.DELIVERED
            ),
            Event(
                conversationId = "c",
                kind = EventKind.SCENE,
                text = "傍晚的风有点凉",
                delivery = DeliveryState.DELIVERED
            )
        )

        val text = PromptBuilder.eventsToAssistantText(events)
        println("\n【assistant 历史投影】\n$text")
        assertTrue(text.contains("你来了"))
        assertTrue("动作要带星号标记", text.contains("*"))
        assertTrue("旁白要用括号", text.contains("（傍晚的风有点凉）"))
    }

    /**
     * 历史是**一条** user 消息，而不是多条 user/assistant 交替。
     *
     * 这是照 QQ-agent 的输入格式改的（见 exampleInput.txt）：整份聊天记录作为
     * 一份带时间戳的记录交出去，模型只需要回答"接下来我说什么"。
     * 好处是模型不会把自己的输出当成"又一次用户输入"来读，语义归属更清楚。
     */
    @Test
    fun `历史合并成一条 user 消息`() {
        val prompt = build(
            history = listOf(
                Message(1, "c", MessageRole.USER, "第一条"),
                Message(2, "c", MessageRole.USER, "第二条")
            )
        )
        // system + 一条历史
        assertEquals(2, prompt.messages.size)
        assertEquals("user", prompt.messages[1].role)
        assertTrue(prompt.messages[1].content.contains("第一条"))
        assertTrue(prompt.messages[1].content.contains("第二条"))
    }

    /** 每条历史必须带时间戳，自己说的话标「我：」—— 这两样是 QQ-agent 输入格式的核心。 */
    @Test
    fun `历史带时间戳与第一人称标签`() {
        val at = 1_700_000_000_000L
        val prompt = build(
            history = listOf(
                Message(1, "c", MessageRole.USER, "在干嘛", createdAt = at),
                Message(2, "c", MessageRole.ASSISTANT, "在家瘫着呢", createdAt = at + 60_000)
            )
        )
        val history = prompt.messages[1].content

        assertTrue("对方的话要标「对方：」：$history", history.contains("对方：在干嘛"))
        assertTrue("自己的话要标「我：」，否则模型容易重复自己说过的话：$history", history.contains("我：在家瘫着呢"))

        // 时间戳形如 [10-07 09:04]
        val stamped = Regex("""\[\d{2}-\d{2} \d{2}:\d{2}\]""").containsMatchIn(history)
        assertTrue("每条要有时间戳：$history", stamped)
    }

    /** 末尾要有【当前时间】锚点（含星期），否则"明天要上课"这类话没依据。 */
    @Test
    fun `末尾有当前时间锚点`() {
        val prompt = build(history = listOf(Message(1, "c", MessageRole.USER, "在吗")))
        val history = prompt.messages[1].content
        assertTrue("要有【当前时间】：$history", history.contains("【当前时间】"))
        val hasWeekday = listOf("周", "星期").any { history.contains(it) }
        assertTrue("当前时间要带星期：$history", hasWeekday)
        assertTrue("要明确指出该谁说话", history.contains("用 JSON 输出你这一轮想说的话"))
    }

    /** 没有历史时也要给一句明确的"这是第一次对话"，不能让模型面对空白。 */
    @Test
    fun `没有历史时给出明确起点`() {
        val prompt = build(history = emptyList())
        assertEquals("system", prompt.messages[0].role)
        assertEquals("user", prompt.messages[1].role)
        assertTrue(prompt.messages[1].content.contains("第一次对话"))
    }

    /** 系统事实（模式切换分隔行）用【】标出，不伪装成谁说的话。 */
    @Test
    fun `系统事实不伪装成发言`() {
        val prompt = build(
            history = listOf(
                Message(1, "c", MessageRole.USER, "我到你楼下了", createdAt = 1_700_000_000_000L),
                Message(2, "c", MessageRole.SYSTEM, "—— 见面了 ——", createdAt = 1_700_000_060_000L)
            )
        )
        val history = prompt.messages[1].content
        // 系统行既不该标「对方：」也不该标「我：」
        val systemLine = history.lines().first { it.contains("见面了") }
        assertTrue("系统事实不该带发言者标签：$systemLine", !systemLine.contains("对方：") && !systemLine.contains("我："))
    }

    /** 场景摘要进变动区。 */
    @Test
    fun `场景摘要被注入`() {
        val prompt = build(
            summaries = listOf(
                SceneSummary(
                    id = "s1",
                    conversationId = "c",
                    fromEventId = 1,
                    toEventId = 20,
                    text = "在门口聊了十五分钟，对方答应了周末一起吃饭"
                )
            )
        )
        assertTrue(prompt.messages[0].content.contains("门口聊了十五分钟"))
        assertTrue(prompt.messages[0].content.contains("已压缩"))
    }

    /**
     * 装配这一层**不许**自己截历史：条数由 `ChatConfig.historyWindow` 决定，
     * 而它默认是"不限"。这里给出 200 条，要求一条不少地出现在提示词里。
     *
     * 钉的是这个失败模式：某处悄悄加一个 `take(N)`，模型就开始忘事，
     * 而日志里的"请求条数"永远正常 —— 看不出来。
     */
    @Test
    fun `历史不在这里被截断 - 200 条一条不少`() {
        val history = (1..200).map { i ->
            Message(
                id = i.toLong(),
                conversationId = "c",
                role = if (i % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                text = "第${i}条",
                createdAt = 1_700_000_000_000L + i * 1000L
            )
        }
        val prompt = build(history = history)
        val body = prompt.messages[1].content
        assertTrue("开头那条该在", body.contains("第1条"))
        assertTrue("末尾那条该在", body.contains("第200条"))
        assertEquals(
            "200 条都该出现",
            200,
            (1..200).count { body.contains("第${it}条") }
        )
    }

    /**
     * 真机故障（2026-10-08）：解析失败的那一轮，原文会被降级成一条 `say` 落盘，
     * 于是历史里出现一整块 JSON。模型读到的是**自己上一次的输出**，里面写着
     * `{ "type": "typing_ms": 1800 }` —— 它把这当成允许的写法照抄，错误一轮轮自我复制。
     * 实测一次请求的提示词里有 **11 处**这样的历史行。
     *
     * 所以历史里必须把它还原成正常的几行（动作带星号、旁白带括号、台词原样）。
     */
    @Test
    fun `历史里整段 JSON 的那一轮 - 还原成正常的几行，不喂回给模型`() {
        val spoiled = """
            {
              "events": [
                { "type": "scene", "text": "窗台上两只杯子被天光照着" },
                { "type": "act", "speaker": "陆昭", "text": "把外套搭在椅背上" },
                { "type": "typing_ms": 1800 },
                { "type": "say", "speaker": "陆昭", "text": "睡吧" }
              ]
            }
        """.trimIndent()
        val prompt = build(
            history = listOf(
                Message(1, "c", MessageRole.USER, "我先躺一下", createdAt = 1_700_000_000_000L),
                Message(2, "c", MessageRole.ASSISTANT, spoiled, createdAt = 1_700_000_060_000L)
            )
        )
        val body = prompt.messages[1].content

        assertFalse("历史里不该再出现 `type` 这种协议字段：$body", body.contains("\"type\""))
        assertTrue("旁白要还原成括号：$body", body.contains("（窗台上两只杯子被天光照着）"))
        assertTrue("动作用星号标记：$body", body.contains("*陆昭把外套搭在椅背上*"))
        assertTrue("台词原样：$body", body.contains("我：睡吧"))
    }

    /** 救不回来的历史原样留着 —— 编一份出来比留着更糟（D11）。 */
    @Test
    fun `救不回来的历史 - 原样留着不编造`() {
        val broken = """{"events":[{"type":"say","text":"他说"你好""}]}"""
        val prompt = build(
            history = listOf(
                Message(1, "c", MessageRole.USER, "在吗", createdAt = 1_700_000_000_000L),
                Message(2, "c", MessageRole.ASSISTANT, broken, createdAt = 1_700_000_060_000L)
            )
        )
        assertTrue("原样留着：${prompt.messages[1].content}", prompt.messages[1].content.contains(broken))
    }

    /** 正常的历史（不是 JSON）一个字符都不能动。 */
    @Test
    fun `正常历史不被这条规则碰到`() {
        val prompt = build(
            history = listOf(
                Message(1, "c", MessageRole.ASSISTANT, "在家瘫着呢", createdAt = 1_700_000_000_000L)
            )
        )
        assertTrue(prompt.messages[1].content.contains("我：在家瘫着呢"))
    }
}
