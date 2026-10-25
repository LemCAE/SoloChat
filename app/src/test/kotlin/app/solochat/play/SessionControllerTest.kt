package app.solochat.play

import app.solochat.data.logweb.UiTestGateway
import app.solochat.data.logweb.UiTestCommand
import kotlinx.coroutines.test.StandardTestDispatcher
import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.AgentTurnRunner
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.agent.SendMode
import app.solochat.core.llm.LlmClient
import app.solochat.core.llm.LlmDelta
import app.solochat.core.llm.LlmRequest
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.ModelPrice
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import app.solochat.core.model.Usage
import app.solochat.core.model.timelineEntries
import app.solochat.core.ports.ClockPort
import app.solochat.core.ports.EventSink
import app.solochat.core.ports.StoragePort
import app.solochat.core.prompt.PromptResources
import app.solochat.core.protocol.DegradedTurn
import app.solochat.core.protocol.PacingConfig
import app.solochat.ui.conversations.ConversationPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话编排的回归测试。
 *
 * **这个文件的存在本身就是一次修复的产物。** 第一版 [SessionController] 直接依赖
 * `SoloChatApp` 与具体类 `RoomStore`，于是它无法在纯 JVM 里构造 —— 结果是
 * "用户消息在界面上成对出现"这个 bug 只能靠人肉看截图发现。
 * 抽出 [TurnHost] 与 [EventSink] 之后，这里可以在虚拟时间里精确复现那一幕。
 *
 * 被测的核心不变式只有一条：**一批消息有且只有一次被投给模型、被落盘**。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    @Test fun `搜索定位与返回最新期间播出继续且不重复回复`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"定位期间第一条"},{"type":"say","text":"定位期间第二条"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        repeat(180) { store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER,
            text = "旧记录$it", createdAt = it.toLong(), sentToModel = true)) }
        val first = store.messages.first()
        val context = app.solochat.ui.chat.HistoryContextState(backgroundScope, store,
            app.solochat.core.model.HistorySearchHit("c1", app.solochat.core.model.TimelineKey(app.solochat.core.model.TimelineSource.MESSAGE, first.id),
                app.solochat.core.model.TimelinePosition(first.createdAt, 0, first.id), first.text, "我"), "history failed")
        val controller = newController(this, store, host)
        controller.open("c1"); advanceUntilIdle()
        var requested = false
        store.onDelivered = {
            if (!requested) {
                requested = true
                assertEquals(TurnPhase.SENDING, controller.state.value.turnPhase)
                context.locate()
                backgroundScope.launch {
                    context.state.first { !it.loading && it.entries.isNotEmpty() }
                    context.latest()
                }
            }
        }
        controller.send("继续")
        awaitSettled(controller) { llm.calls > 0 }
        runCurrent()
        assertTrue(requested)
        assertTrue(context.state.value.latest)
        assertEquals(1, llm.calls)
        assertEquals(1, controller.state.value.items.count { it is ChatItem.Theirs && it.text == "定位期间第一条" })
        assertEquals(1, controller.state.value.items.count { it is ChatItem.Theirs && it.text == "定位期间第二条" })
        assertFalse(controller.state.value.items.any { it is ChatItem.Mine && it.id == first.id })
        assertNull(controller.state.value.live)
    }

    @Test fun `播出期间请求早页仍逐条落定且不重复回复`() = runTest {
        val store = object : FakeStore() {
            override suspend fun timelinePage(conversationId: String, before: app.solochat.core.model.TimelinePosition?, limit: Int): app.solochat.core.model.TimelinePage {
                if (before != null) delay(20)
                return super.timelinePage(conversationId, before, limit)
            }
        }
        val llm = StubLlm("""{"events":[{"type":"say","text":"第一条"},{"type":"say","text":"第二条"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        repeat(180) { store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER,
            text = "旧历史$it", createdAt = it.toLong(), sentToModel = true)) }
        val controller = newController(this, store, host)
        controller.open("c1"); advanceUntilIdle()
        val previousKeys = controller.state.value.items.filterIsInstance<ChatItem.Mine>().map { it.id }.toSet()
        var requested = false
        store.onDelivered = {
            if (!requested) {
                assertEquals(TurnPhase.SENDING, controller.state.value.turnPhase)
                requested = true
                controller.loadEarlierHistory()
            }
        }
        controller.send("继续")
        awaitSettled(controller) { llm.calls > 0 }
        assertTrue(requested)
        assertEquals(1, llm.calls)
        assertEquals(1, controller.state.value.items.count { it is ChatItem.Theirs && it.text == "第一条" })
        assertEquals(1, controller.state.value.items.count { it is ChatItem.Theirs && it.text == "第二条" })
        assertTrue(controller.state.value.items.filterIsInstance<ChatItem.Mine>().map { it.id }.containsAll(previousKeys))
        assertTrue(controller.state.value.items.size > 80)
        assertNull(controller.state.value.live)
    }

    @Test fun `超过400条消息打开会话仍保留早期用户系统与已出口回复`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("{}")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        host.sendMode = SendMode.MANUAL
        seed(store, "c1")
        store.appendMessage(Message(conversationId = "c1", role = MessageRole.SYSTEM, text = "早期系统", createdAt = 1))
        store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER, text = "早期用户", createdAt = 2, sentToModel = false))
        store.appendEvent(Event(conversationId = "c1", kind = EventKind.SAY, text = "早期回复",
            delivery = DeliveryState.DELIVERED, createdAt = 3, deliveredAt = 3))
        store.appendMessage(Message(conversationId = "c1", role = MessageRole.ASSISTANT, text = "早期回复", createdAt = 3))
        repeat(420) { index ->
            store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER,
                text = "后续$index", createdAt = 4L + index, sentToModel = true))
        }
        val controller = newController(this, store, host)
        controller.open("c1")
        advanceUntilIdle()
        assertEquals(80, controller.state.value.items.size)
        assertTrue(controller.state.value.hasEarlierHistory)
        assertEquals(1, controller.state.value.pendingSendCount)
        while (controller.state.value.hasEarlierHistory) {
            controller.loadEarlierHistory()
            advanceUntilIdle()
        }
        val items = controller.state.value.items
        assertTrue(items.any { it is ChatItem.Mine && it.text == "早期用户" })
        assertTrue(items.any { it is ChatItem.Divider && it.text == "早期系统" })
        assertEquals(1, items.count { it is ChatItem.Theirs && it.text == "早期回复" })
        assertEquals(423, items.size)
        assertEquals(1, controller.state.value.pendingSendCount)
        assertEquals(0, llm.calls)
    }

    @Test fun `早页失败保留记录可重试且返回最新缩回一页`() = runTest {
        var failPage = false
        var pageCalls = 0
        val store = object : FakeStore() {
            override suspend fun timelineOf(conversationId: String): List<app.solochat.core.model.TimelineEntry> =
                error("控制器不得请求完整时间轴")
            override suspend fun timelinePage(conversationId: String, before: app.solochat.core.model.TimelinePosition?, limit: Int): app.solochat.core.model.TimelinePage {
                pageCalls++
                assertEquals(80, limit)
                if (failPage && before != null) throw java.io.IOException("模拟读取失败")
                return app.solochat.core.model.timelinePage(app.solochat.core.model.timelineEntries(
                    events.filter { it.conversationId == conversationId }, messages.filter { it.conversationId == conversationId }), before, limit)
            }
        }
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        host.sendMode = SendMode.MANUAL
        seed(store, "c1")
        repeat(180) { store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER,
            text = "历史$it", createdAt = it.toLong(), sentToModel = true)) }
        val controller = newController(this, store, host)
        controller.open("c1"); advanceUntilIdle()
        assertEquals(1, pageCalls)
        val initial = controller.state.value.items
        failPage = true
        controller.loadEarlierHistory(); controller.loadEarlierHistory(); advanceUntilIdle()
        assertEquals(2, pageCalls)
        assertEquals(initial, controller.state.value.items)
        assertTrue(controller.state.value.historyError.isNotBlank())
        failPage = false
        controller.loadEarlierHistory(); advanceUntilIdle()
        assertEquals(160, controller.state.value.items.size)
        assertTrue(controller.state.value.historyError.isBlank())
        controller.returnToLatestHistory(); advanceUntilIdle()
        assertEquals(initial, controller.state.value.items)
        assertEquals(1L, controller.state.value.latestHistoryRequest)
        assertEquals(180, store.messages.size)
    }

    @Test fun `一次新增超过一页时刷新按页接齐中间历史`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        repeat(180) { store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER,
            text = "旧$it", createdAt = it.toLong(), sentToModel = true)) }
        val controller = newController(this, store, host)
        controller.open("c1"); advanceUntilIdle()
        repeat(170) { store.appendMessage(Message(conversationId = "c1", role = MessageRole.USER,
            text = "新$it", createdAt = 180L + it, sentToModel = true)) }
        controller.reload(); advanceUntilIdle()
        val items = controller.state.value.items.filterIsInstance<ChatItem.Mine>()
        assertEquals(170, items.count { it.text.startsWith("新") })
        assertEquals(items.size, items.map { it.id }.toSet().size)
        while (controller.state.value.hasEarlierHistory) { controller.loadEarlierHistory(); advanceUntilIdle() }
        assertEquals(350, controller.state.value.items.size)
    }

    @Test fun `导入副本打开重开切换发送方式和按发送均不自动推送旧代发`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"好"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "copy")
        store.pausedBackups += "copy"
        store.appendMessage(Message(conversationId = "copy", role = MessageRole.USER, text = "旧代发", sentToModel = false))
        val blocked = AgentTurnRunner(host.deps).run(app.solochat.core.agent.AgentTurn("copy", "", 1)).first()
        assertTrue(blocked is app.solochat.core.agent.AgentEvent.Failed)
        val controller = newController(this, store, host)
        controller.open("copy")
        advanceUntilIdle()
        assertTrue(controller.state.value.backupPaused)
        controller.send("新消息")
        controller.send(listOf(EventKind.SAY to "组合消息"))
        controller.applyMode(ChatMode.SCENE)
        controller.sendSystem("不应写入")
        host.sendMode = SendMode.MANUAL
        controller.push()
        controller.open("copy")
        advanceUntilIdle()
        assertEquals(0, llm.calls)
        assertEquals(1, store.messages.size)
        assertFalse(store.messages.single().sentToModel)
        host.sendMode = SendMode.AUTO
        controller.open("copy")
        advanceUntilIdle()
        assertEquals(0, llm.calls)
        controller.resumeImportedConversation()
        controller.resumeImportedConversation()
        awaitSettled(controller) { llm.calls > 0 }
        assertEquals(1, llm.calls)
        assertFalse(store.isBackupPaused("copy"))
        assertTrue(store.messages.first().sentToModel)
    }

    @Test
    fun `用户系统消息 - 只写系统记录不调用模型，下一轮上下文能看到`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"好"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)
        controller.open("c1")
        awaitSettled(controller)
        controller.sendSystem("现在是晚上")
        advanceUntilIdle()
        assertEquals(0, llm.calls)
        assertEquals(1, store.messages.count { it.role == MessageRole.SYSTEM })
        assertEquals(0, store.messages.count { it.role == MessageRole.USER })
        assertTrue(controller.state.value.items.any { it is ChatItem.Divider && it.text.contains("现在是晚上") })
        controller.send("继续")
        awaitSettled(controller) { llm.calls > 0 }
        val prompt = llm.requests.last().joinToString("\n") { it.content }
        assertTrue(prompt.contains("【系统消息】现在是晚上"))
        assertFalse(prompt.contains("对方：【系统消息】"))
    }

    @Test
    fun `助手历史写入挂起时插话不能丢掉已出口内容`() = runTest {
        val store = object : FakeStore() {
            override suspend fun appendMessage(message: Message): Long {
                if (message.role == MessageRole.ASSISTANT) delay(50)
                return super.appendMessage(message)
            }
        }
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"已出口内容"}]}""",
            """{"events":[{"type":"say","text":"插话后的回复"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)
        controller.open("c1")
        awaitSettled(controller)
        var inserted = false
        store.onDelivered = {
            if (!inserted) { inserted = true; controller.send("用户插话") }
        }
        controller.send("先聊一句")
        awaitSettled(controller) { llm.calls >= 2 && it.items.any { row -> row.textOf() == "插话后的回复" } }
        assertEquals(1, store.messages.count { it.role == MessageRole.ASSISTANT && it.text == "已出口内容" })
        assertTrue(llm.requests.last().any { it.content.contains("已出口内容") })
    }

    @Test
    fun `手动重复推送遇到挂起标记仍只认领一批`() = runTest {
        val store = object : FakeStore() {
            var marks = 0
            override suspend fun markUserMessagesPushed(ids: List<Long>) {
                marks += 1
                delay(marks * 20L)
                super.markUserMessagesPushed(ids)
            }
        }
        val llm = StubLlm("""{"events":[{"type":"say","text":"好"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)
        controller.open("c1")
        awaitSettled(controller)
        controller.send("只推一次")
        advanceUntilIdle()
        controller.push()
        controller.push()
        awaitSettled(controller) { llm.calls >= 1 }
        advanceUntilIdle()
        assertEquals(1, store.marks)
        assertEquals(1, llm.calls)
    }

    @Test
    fun `系统切模式命令直接改变模式且只调用一次开场`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"见面了"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)
        controller.open("c1")
        awaitSettled(controller)
        controller.sendSystem("进入面对面模式")
        awaitSettled(controller) { it.mode == ChatMode.SCENE && llm.calls == 1 }
        assertEquals(ChatMode.SCENE, store.conversations["c1"]!!.mode)
        assertEquals(0, store.messages.count { it.role == MessageRole.USER })
        assertEquals(1, store.messages.count { it.role == MessageRole.SYSTEM })
        assertFalse(store.messages.any { it.text == "进入面对面模式" })
        assertTrue(llm.requests.single().any { it.content.contains("你先开个场") })
        controller.submitInput(listOf(EventKind.SAY to "/chat"))
        awaitSettled(controller) { it.mode == ChatMode.CHAT && llm.calls == 2 }
        assertEquals(0, store.messages.count { it.role == MessageRole.USER })
    }

    @Test
    fun `UI调试复用当前手动控制器且重试不重复发送`() = runTest {
        val store = object : FakeStore() {
            override suspend fun listPersonas() = listOf(Persona(id = "test-card", name = "TEST-PERSONA"))
        }
        val llm = StubLlm("""{"events":[{"type":"say","text":"确认收到"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        store.conversations["c1"] = store.conversations["c1"]!!.copy(title = "TEST-UI", personaId = "test-card")
        val controller = newController(this, store, host)
        controller.open("c1")
        awaitSettled(controller)
        val gateway = UiTestGateway({ controller }, StandardTestDispatcher(testScheduler))
        val send = UiTestCommand("c1", "first", "send", text = "接口发言")
        gateway.execute(send)
        advanceUntilIdle()
        assertEquals(1, controller.state.value.pendingSendCount)
        assertEquals(0, llm.calls)
        assertTrue(gateway.execute(send).duplicate)
        assertEquals(1, store.messages.count { it.role == MessageRole.USER })
        val push = UiTestCommand("c1", "push", "push")
        gateway.execute(push)
        awaitSettled(controller) { llm.calls > 0 }
        assertTrue(gateway.execute(push).duplicate)
        advanceUntilIdle()
        assertEquals(1, llm.calls)
        assertTrue(gateway.state("c1").items.contains("确认收到"))
        assertTrue(runCatching { gateway.execute(send.copy(text = "同id不同内容")) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `接口代发落库挂起时立即推送仍按顺序认领`() = runTest {
        val store = object : FakeStore() {
            override suspend fun appendMessage(message: Message): Long {
                if (message.role == MessageRole.USER) delay(50)
                return super.appendMessage(message)
            }
        }
        val llm = StubLlm("""{"events":[{"type":"say","text":"收到"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)
        controller.open("c1")
        controller.awaitReady()
        val gateway = UiTestGateway({ controller }, StandardTestDispatcher(testScheduler))
        gateway.execute(UiTestCommand("c1", "send", "send", text = "稍慢的写入"))
        gateway.execute(UiTestCommand("c1", "push", "push"))
        advanceUntilIdle()
        assertEquals(1, llm.calls)
        assertTrue(store.messages.filter { it.role == MessageRole.USER }.all { it.sentToModel })
    }

    @Test
    fun `离开聊天页后接口与重进页面复用同一轮生成及播出`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"离页仍完成"}]}""")
        llm.replyDelayMs = 500
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        seed(store, "c2")
        val sessions = SessionRegistry(this) { newController(it, store, host) }
        val gateway = UiTestGateway({ id ->
            checkNotNull(store.conversations[id]) { "对话不存在" }
            sessions.get(id).also { it.awaitReady() }
        }, StandardTestDispatcher(testScheduler))
        // 接口先发送，页面尚未打开；手动代发行为与 UI 完全相同。
        gateway.execute(UiTestCommand("c1", "send", "send", text = "测试"))
        advanceUntilIdle()
        val page = sessions.get("c1")
        assertEquals(1, page.state.value.pendingSendCount)
        gateway.execute(UiTestCommand("c1", "push", "push"))
        runCurrent()
        assertEquals(TurnPhase.THINKING, page.state.value.turnPhase)
        // 确认请求尚在等待时离页，改看另一个会话，不再 close 原实例。
        sessions.get("c2").awaitReady()
        awaitSettled(page) { llm.calls == 1 }
        assertTrue(page === sessions.get("c1"))
        assertTrue(gateway.state("c1").items.contains("离页仍完成"))
        assertEquals(1, llm.calls)
        assertEquals(1, store.messages.count { it.role == MessageRole.ASSISTANT })
        assertTrue(runCatching { gateway.state("missing") }.exceptionOrNull() is IllegalStateException)
        sessions.remove("c1")
        sessions.remove("c2")
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 内存存储 + 播出状态记录。 */
    internal open class FakeStore : StoragePort, EventSink {
        val messages = mutableListOf<Message>()
        val events = mutableListOf<Event>()
        val memories = mutableListOf<Memory>()
        val conversations = mutableMapOf<String, Conversation>()
        val pausedBackups = mutableSetOf<String>()
        override suspend fun isBackupPaused(conversationId: String) = conversationId in pausedBackups
        override suspend fun resumeBackup(conversationId: String) { pausedBackups.remove(conversationId) }
        val deliveryStates = mutableMapOf<Long, DeliveryState>()

        /**
         * 每落定一条就回调一次，在**写入之后、返回之前**同步调用。
         *
         * 存在的理由：`advanceUntilIdle()` 会在一次调用里把整场演出跑完，
         * 外面轮询 `.value` 永远抓不到"第 N 条刚播完、第 N+1 条正在播"那个瞬间。
         * 要精确复刻"用户在第 N+1 条播到一半时插话"，只能从这个点插进去。
         */
        var onDelivered: ((Event) -> Unit)? = null
        var onInterrupted: ((Event) -> Unit)? = null
        private val idSeq = AtomicLong(1)

        override suspend fun listConversations() = conversations.values.toList()
        override suspend fun getConversation(id: String) = conversations[id]
        override suspend fun upsertConversation(conversation: Conversation) {
            conversations[conversation.id] = conversation
        }
        override suspend fun touchConversation(id: String, at: Long) {
            conversations[id]?.let { conversations[id] = it.copy(updatedAt = at) }
        }
        override suspend fun changeMode(id: String, mode: ChatMode, since: Long) {
            conversations[id]?.let { conversations[id] = it.copy(mode = mode, modeSince = since, updatedAt = since) }
        }
        val calls = mutableMapOf<String, app.solochat.core.model.CallRecord>()
        val revisions = mutableMapOf<String, app.solochat.core.model.ContextRevision>()
        override suspend fun contextRevision(id: String) = revisions[id]
        override suspend fun contextRevisions(conversationId: String) = revisions.values.filter { it.conversationId == conversationId }.sortedByDescending { it.createdAt }
        override suspend fun saveContextDraft(revision: app.solochat.core.model.ContextRevision) {
            check(conversations.containsKey(revision.conversationId) && revision.state == "DRAFT")
            check(revisions[revision.id]?.state.let { it == null || it == "DRAFT" })
            app.solochat.core.model.validateSegments(revision)
            revisions[revision.id] = revision
        }
        override suspend fun activateContext(revision: app.solochat.core.model.ContextRevision, at: Long) {
            val conversation = getConversation(revision.conversationId)!!
            require(conversation.activeContextRevisionId == revision.source.parentRevisionId)
            app.solochat.core.model.validateCompressionSource(revision.source, conversation, listPersonas(),
                modelVisibleMessages(conversation.id, -1), eventsOf(conversation.id))
            app.solochat.core.model.validateSegments(revision)
            require(app.solochat.core.agent.CompressionHistory.cuts(modelVisibleMessages(conversation.id, -1),
                eventsOf(conversation.id)).any { it.messageId == revision.source.messages.last().id })
            check(revisions[revision.id]?.state == "DRAFT" && revision.state == "COMPLETE")
            revisions[revision.id] = revision
            conversations[conversation.id] = conversation.copy(activeContextRevisionId = revision.id,
                compressionMethod = revision.method.name, updatedAt = at)
        }
        override suspend fun restoreContext(conversationId: String, expectedRevisionId: String?, at: Long) {
            val conversation = getConversation(conversationId)!!
            require(conversation.activeContextRevisionId == expectedRevisionId)
            conversations[conversationId] = conversation.copy(activeContextRevisionId = null, updatedAt = at)
        }
        override suspend fun recordCall(call: app.solochat.core.model.CallRecord, usage: Usage?) {
            calls[call.id] = call
            if (usage != null) recordUsage(call.conversationId, call.model, usage, call.finishedAt ?: call.startedAt)
        }

        override suspend fun eventsOf(conversationId: String, fromId: Long) =
            events.filter { it.conversationId == conversationId && it.id > fromId }

        override suspend fun appendEvent(event: Event): Long {
            val id = idSeq.getAndIncrement()
            events += event.copy(id = id)
            return id
        }

        override suspend fun updateEvent(event: Event) {
            val index = events.indexOfFirst { it.id == event.id }
            if (index >= 0) events[index] = event
        }

        // 照抄 Room 那条 SQL 的语义：**limit ≤ 0 = 不限**（SQLite 的 `LIMIT -1`）。
        // 不照抄的话 `takeLast(-1)` 会直接抛，而这个值恰恰是默认值 ——
        // 假存储比真实现更严格，就会把"默认全量注入"错报成崩溃。
        override suspend fun recentMessages(conversationId: String, limit: Int): List<Message> {
            val rows = messages.filter { it.conversationId == conversationId }
            return if (limit <= 0) rows else rows.takeLast(limit)
        }

        /**
         * 与 [recentMessages] 只差"未推送的用户消息不算" —— 同样**照抄 SQL 语义**。
         *
         * 这条过滤是手动发送模式的正确性核心（"不按第二次就不发给模型"），
         * 而真机上那条 SQL 在纯 JVM 里跑不了，所以必须在这儿一模一样地复刻：
         * 复刻错了，测试会替真机"证明"一个不存在的行为。
         */
        override suspend fun modelVisibleMessages(conversationId: String, limit: Int): List<Message> {
            val rows = messages.filter {
                it.conversationId == conversationId &&
                    (it.role != MessageRole.USER || it.sentToModel)
            }
            return if (limit <= 0) rows else rows.takeLast(limit)
        }

        override suspend fun unpushedUserMessages(conversationId: String): List<Message> =
            messages.filter {
                it.conversationId == conversationId &&
                    it.role == MessageRole.USER && !it.sentToModel
            }

        override suspend fun markUserMessagesPushed(ids: List<Long>) {
            ids.forEach { id ->
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0) messages[index] = messages[index].copy(sentToModel = true)
            }
        }

        override suspend fun appendMessage(message: Message): Long {
            val id = idSeq.getAndIncrement()
            messages += message.copy(id = id)
            return id
        }

        val personas = mutableMapOf("default" to Persona(id = "default", name = "小默"))
        override suspend fun listPersonas() = personas.values.toList()
        override suspend fun getPersona(id: String) = listPersonas().firstOrNull { it.id == id }
        override suspend fun upsertPersona(persona: Persona) { personas[persona.id] = persona }
        override suspend fun deletePersona(id: String) = Unit
        override suspend fun listMemories(personaId: String?): List<Memory> =
            // 刻意**照抄** Room 那条 SQL 的语义（`null` = 全部；否则 = 自己的 + 通用的），
            // 因为"注入时只给这张人设能看的"是这个功能最要紧的失败模式，
            // 而真机上的 Room 查询没法在纯 JVM 里跑。
            memories.filter { personaId == null || it.personaId.isBlank() || it.personaId == personaId }

        override suspend fun upsertMemory(memory: Memory) {
            memories.removeAll { it.id == memory.id }
            memories += memory
        }

        override suspend fun deleteMemory(id: String) {
            memories.removeAll { it.id == id }
        }
        override suspend fun summariesOf(conversationId: String) = emptyList<SceneSummary>()
        override suspend fun appendSummary(summary: SceneSummary) = Unit

        override suspend fun recordUsage(
            conversationId: String,
            model: String,
            usage: Usage,
            at: Long
        ) = Unit

        /**
         * ⚠️ 必须**同时改事件本身**，不能只记一张旁表。
         *
         * 界面看到的条目是由 [ChatProjector.project] 从事件表投影出来的，而它靠
         * `delivery` 与 `deliveredAt` 决定"这条到底出口了没有"。第一版的替身只往
         * `deliveryStates` 里记一笔，于是投影永远看不到任何事件 ——
         * "演出期间屏幕上到底有几条"这个最要紧的问题在测试里**根本观测不到**。
         */
        override suspend fun markDelivered(id: Long, at: Long, gapMs: Int, typingMs: Int) {
            deliveryStates[id] = DeliveryState.DELIVERED
            var delivered: Event? = null
            val index = events.indexOfFirst { it.id == id }
            if (index >= 0) {
                events[index] = events[index].copy(
                    delivery = DeliveryState.DELIVERED,
                    deliveredAt = at
                )
                delivered = events[index]
            }
            delivered?.let { onDelivered?.invoke(it) }
        }

        override suspend fun markInterrupted(id: Long, at: Long) {
            deliveryStates[id] = DeliveryState.INTERRUPTED
            var interrupted: Event? = null
            val index = events.indexOfFirst { it.id == id }
            if (index >= 0) {
                events[index] = events[index].copy(
                    delivery = DeliveryState.INTERRUPTED,
                    deliveredAt = at
                )
                interrupted = events[index]
            }
            interrupted?.let { onInterrupted?.invoke(it) }
        }

        /**
         * ⚠️ 与 [markDelivered] / [markInterrupted] 同理：**必须同时改事件本身**。
         *
         * 第一版这里只往 `deliveryStates` 记一笔，于是事件表里那条永远是 `PENDING`。
         * 代价是测试里"取消"这件事看起来生效了，实际事件还是悬空的 ——
         * 而 `cancelUnplayed` 的幂等判断、`ChatProjector` 的门禁读的都是事件本身，
         * 于是它们会一直把已取消的事件当成待处理。这个假替身骗过了三轮调试。
         */
        override suspend fun setDelivery(id: Long, delivery: DeliveryState) {
            deliveryStates[id] = delivery
            val index = events.indexOfFirst { it.id == id }
            if (index >= 0) {
                events[index] = events[index].copy(delivery = delivery)
            }
        }

        /** 用户消息的条数 —— 重复 bug 的度量。 */
        fun userMessageCount() = messages.count { it.role == MessageRole.USER }
        fun userTexts() = messages.filter { it.role == MessageRole.USER }.map { it.text }

        /** 被要求修复过哪些会话（守"这件事真的被调用了" —— 这个仓库里栽过两次）。 */
        val repaired = mutableListOf<String>()

        /**
         * 与 Room 实现同一套语义：用的是**同一个** [DegradedTurn.split]，
         * 所以这里测的是"打开会话之后时间轴上就看不到那段 JSON 了"，
         * 而不是"假替身自己会变魔术"。
         */
        override suspend fun repairDegradedTurns(conversationId: String): Int {
            repaired += conversationId
            var count = 0
            // 照抄 Room 那条 SQL 的语义（含"只修已经落定的"这一条）：
            // 假存储比真实现宽松的话，测试会替真机"证明"一个不存在的行为。
            val targets = events.filter {
                it.conversationId == conversationId &&
                    it.kind == EventKind.SAY &&
                    it.delivery != DeliveryState.PENDING &&
                    it.delivery != DeliveryState.PLAYING &&
                    DegradedTurn.looksLikeRawScript(it.text)
            }
            for (row in targets) {
                val parts = DegradedTurn.split(row.text) ?: continue
                val at = events.indexOf(row)
                events.removeAt(at)
                val rebuilt = parts.mapIndexed { index, part ->
                    row.copy(
                        id = idSeq.getAndIncrement(),
                        kind = part.kind,
                        speaker = part.resolvedSpeaker,
                        text = part.text,
                        gapHintMs = part.gapHintMs,
                        typingHintMs = part.typingHintMs,
                        waitReason = part.waitReason,
                        gapMs = if (index == 0) row.gapMs else 0,
                        typingMs = if (index == 0) row.typingMs else 0
                    )
                }
                events.addAll(at, rebuilt)
                count++
            }
            return count
        }
    }

    /**
     * 跟着 `TestCoroutineScheduler` 走的时钟。
     *
     * **必须是虚拟的，不能是 `System.currentTimeMillis()`**：`SessionController` 现在
     * 用宿主的时钟给"交付时间"打点，而交付时间正是"两条回复会不会挤在同一帧出现"
     * 的判据。用真实时间的话，两条回复在虚拟时间里隔了几百毫秒、真实时间却可能落在
     * 同一毫秒上，那条断言就会**随机失败** —— 而它要守的恰恰是这个语义。
     */
    private class VirtualClock(private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) : ClockPort {
        override fun now(): Long = START_EPOCH + scheduler.currentTime
        override suspend fun sleep(ms: Long) = Unit

        companion object {
            /** 一个固定的起始时刻，便于在断言里读懂时间戳。 */
            const val START_EPOCH = 1_700_000_000_000L
        }
    }

    /** 固定脚本的假模型。测的是编排，不是模型。 */
    private class StubLlm(private vararg val scripts: String) : LlmClient {
        var calls = 0
            private set

        /** 每次请求实际发出去的消息。用来断言"模型到底看不看得到自己说过的话"。 */
        val requests = mutableListOf<List<app.solochat.core.llm.ApiMessage>>()

        /**
         * 请求**刚发出**时的回调（在流开始收集的那一刻，同步调）。
         *
         * 存在的理由：`advanceUntilIdle()` 会把整轮跑完，而有些状态只有在"推送成功、
         * 但这一轮还没结束"这个瞬间才是对的 —— 例如发送键该不该还蓝着。
         * 从外面轮询 `.value` 永远抓不到那一刻（和 [FakeStore.onDelivered] 同一个道理）。
         */
        var onRequest: (() -> Unit)? = null
        var replyDelayMs: Long = 0

        override fun chatStream(request: LlmRequest): Flow<LlmDelta> = flow {
            requests += request.messages
            onRequest?.invoke()
            // 第 N 次调用用第 N 个脚本，用完之后一直用最后一个。
            // 为什么需要多个：测试"插话"时第二轮必须给一个**不同的**脚本，
            // 否则两轮产出同样的气泡，断言分不清自己在看哪一轮。
            val index = calls.coerceAtMost(scripts.lastIndex)
            calls += 1
            delay(replyDelayMs)
            emit(LlmDelta.Text(scripts[index]))
        }

        override suspend fun listModels() = listOf("stub")
        override suspend fun ping(model: String) = 1L
    }

    private class FakeHost(
        // 构造参数刻意**不叫** store/llm/clock：内层 AgentDeps 对象也声明了同名属性，
        // 重名会让编译器认为构造参数还没初始化（"Variable 'clock' must be initialized"）。
        private val storage: StoragePort,
        private val llmClient: LlmClient,
        private val timeSource: ClockPort,
        private val windowMs: Long = 200,
        /** 发送方式。测试要能在"自动/手动"之间切，所以配置本身是可变的。 */
        var sendMode: SendMode = SendMode.AUTO
    ) : TurnHost {
        private val chatConfig = ChatConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "stub",
            allowModeSwitch = true
        )

        override val deps: AgentDeps = object : AgentDeps {
            override val conversations = app.solochat.core.agent.ConversationOperations()
            override val storage: StoragePort = this@FakeHost.storage
            override val clock: ClockPort = this@FakeHost.timeSource
            override val llm: LlmClient = this@FakeHost.llmClient
            override val prompts: PromptResources = PromptResources(
                safetyRules = "你是小默。",
                protocolChat = "输出 JSON。",
                protocolScene = "输出 JSON。",
                styleChat = "短句。",
                styleScene = "短句。",
                memory = "记事实。",
                proactive = "别催。"
            )
            override val pricing: PriceResolver = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = ModelPrice()
            }

            // 每次现读 sendMode：设置页改完不重启也要生效，而这是那条路径的唯一入口。
            override fun config() = chatConfig.copy(sendMode = this@FakeHost.sendMode)
            override fun rhythmSamples(): List<Long> = emptyList()
            override fun debounceWindowMs() = windowMs
        }

        override fun config() = chatConfig.copy(sendMode = sendMode)

        /**
         * 节奏参数压到最小。
         *
         * 不是因为"测试跑得慢" —— 虚拟时间不会真的等。压小的理由是**断言要能读懂**：
         * 默认是每个字 700~1100ms，"就是那只蛞蝓猫吧"光间隔就接近 8 秒，
         * 那个量级只会在日志里堆出一屏噪音，让人看不清"第几步发生了什么"。
         */
        override fun pacingConfig() = PacingConfig(
            minMsPerChar = 50,
            maxMsPerChar = 50,
            gapMinMs = 0,
            gapMaxMs = 100,
            typingMsPerChar = 50,
            typingMaxMs = 500,
            readShortMs = 25,
            readMediumMs = 25,
            readLongMs = 25,
            readJitterMs = 0
        )

        override fun refreshRhythm() = Unit
    }

    private fun seed(store: FakeStore, conversationId: String, mode: ChatMode = ChatMode.CHAT) {
        store.conversations[conversationId] = Conversation(
            id = conversationId,
            title = "测一下",
            personaId = "default",
            mode = mode,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
    }

    private fun newController(
        scope: CoroutineScope,
        store: FakeStore,
        host: FakeHost,
        sink: EventSink = store
    ) = SessionController(
        host = host,
        scope = scope,
        store = store,
        sink = sink,
        runner = AgentTurnRunner(host.deps)
    )

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /**
     * 这一条直接复现截图上的那一幕：连发之后，**每条用户消息在待处理队列里只存在一份**。
     *
     * 之所以断言"落盘的条数"，是因为那个 bug 的机理就在这里：
     * 第一版 `pending` 的"取批 + 清空"不在同一个临界区，两条并发路径能各自取到
     * 同一批，于是同一批被**投给模型两次、落盘两次**，用户消息在界面上成对出现。
     *
     * ⚠️ 这条测试原来是自己调 `claimPending()` 去拿批的 —— 那让它在无意中**依赖了
     * `scheduleDebounce` 的一个 bug**（批次根本不会被开工，所以它才拿得到）。
     * 那个 bug 修掉之后（见 `scheduleDebounce` 的注释），批会自动被认领，
     * 于是判据改成从**落盘结果**与**调用次数**看：那才是它真正要守的现象。
     */
    @Test
    fun `连发三条只认领一次 - 每条只有一份`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("{}")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        controller.send("111")
        controller.send("111")
        controller.send("在干嘛")
        advanceUntilIdle()

        assertEquals(
            "三条应当原样各落盘一份。实际：${store.messages.map { "${it.role}:${it.text}" }}",
            listOf("111", "111", "在干嘛"),
            store.userTexts()
        )
        assertEquals("三条只该起一轮，不能投两遍", 1, llm.calls)
        assertTrue(
            "批已经被开工时认领走了，第二次必须是空的",
            controller.claimPending().batch.isEmpty()
        )
    }

    /**
     * 原子认领的不变式：**同一批消息只能被认领一次**。
     *
     * 这是那个重复 bug 的核心保护点，所以值得一条直接的测试。
     * 断言方式是"连着认领两次" —— 第二次必须拿到空批。
     *
     * 刻意**不写成并发**：并发版会因为调度顺序而随机通过，
     * 而这里要锁住的是一个确定性事实（认领会清空待处理队列），
     * 用确定性的写法才不会变成 flaky 测试。
     */
    @Test
    fun `认领两次 - 第二次必须为空`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("在吗")

        val first = controller.claimPending()
        assertEquals("第一次应当认领到这一条", 1, first.batch.size)
        assertEquals("在吗", first.batch[0].text)

        val second = controller.claimPending()
        assertTrue("第二次不该再拿到同一批（这正是重复 bug 的成因）", second.batch.isEmpty())
    }

    /**
     * 按下发送后，消息必须**立刻**出现在界面上。
     *
     * 这一条和"落盘时机"是一对：落盘被推后到回合开始时（为了避免写两遍），
     * 所以界面**必须**有独立的待处理气泡，否则就是"点了发送没反应"。
     * 断言 `pendingItems` 而不是数据库 —— 因为这一刻它本来就还没进库。
     */
    @Test
    fun `按下发送后界面立刻出现气泡`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        controller.send("在吗")

        assertEquals("这一刻还应在待处理里", 0, store.userMessageCount())
        assertEquals(1, controller.state.value.pendingItems.size)
        assertEquals("在吗", controller.state.value.pendingItems[0].textOf())
    }

    /**
     * 认领之后再落盘，且只落一次 —— 这是真正防重复的那一段。
     *
     * 直接按 `flushTurn` 的顺序走一遍（认领 → 落盘），断言库里恰好一条。
     * 这样即使"待处理的取批/清空"被改坏，测试也会立刻发现。
     */
    @Test
    fun `认领后落盘 - 库里恰好一条`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("在吗")

        val claimed = controller.claimPending()
        assertEquals(1, claimed.batch.size)
        // 复刻 flushTurn 的落盘动作
        claimed.batch.forEach { message ->
            store.appendMessage(
                Message(
                    conversationId = claimed.conversationId.orEmpty(),
                    role = MessageRole.USER,
                    text = message.text,
                    turnId = message.at,
                    createdAt = message.at
                )
            )
        }
        assertEquals(1, store.userMessageCount())
        assertEquals(listOf("在吗"), store.userTexts())
    }

    /**
     * 落盘发生在"认领"那一刻，而不是按下发送那一刻。
     *
     * 这是刻意的取舍。写成断言是为了防止以后有人"顺手"在 `send()` 里
     * 再加一次落盘 —— 那会立刻把重复 bug 带回来。
     */
    @Test
    fun `按下发送时还不落盘`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("在吗")

        assertEquals("按下发送这一刻不该已经写库", 0, store.userMessageCount())
    }

    /**
     * 插话之后，两轮的落盘各自只发生一次。
     *
     * 这条用例覆盖"再来一轮"的完整路径：第 1 轮（`在吗`）→ 插话（`算了`）→ 第 2 轮。
     * 断言每轮各落盘一次、以及两次都调用了模型。
     */
    @Test
    fun `插话后两轮各落盘一次`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"第一句"},{"type":"say","text":"第二句"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        controller.send("在吗")
        advanceUntilIdle()
        assertEquals(listOf("在吗"), store.userTexts())

        controller.send("算了")
        advanceUntilIdle()
        assertEquals(listOf("在吗", "算了"), store.userTexts())
        assertEquals("插话这一轮应当也调用了模型", 2, llm.calls)
    }

    /**
     * 模式切换**不能**产生一条"我"的消息。
     *
     * 真机上看到的一幕：点了"好，进去"之后，聊天里冒出一条蓝色气泡写着
     * 「[系统] 你们见面了，开始面对面。」。**两个身份都错了** ——
     * `persist()` 以 `MessageRole.USER` 落库，于是模型看到的是
     * 「对方：[系统] 你们见面了，开始面对面。」（它会以为对方说了这么一句），
     * 而界面上它是一句"我说的话"。
     *
     * 现在：切换这件事只留一条 SYSTEM 消息（界面上是居中小字），
     * "这时候该干嘛"走 `AgentTurn.cue`，只进提示词变动区、不落成消息。
     * 而那一轮仍然要跑起来（批次是空的）—— 否则点了确认之后对方一声不吭。
     */
    @Test
    fun `模式切换只留一条系统小字 - 不产生我发的话`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("{}")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)

        controller.applyMode(ChatMode.SCENE)
        awaitSettled(controller)

        assertEquals("切换不该产生任何用户消息", 0, store.userMessageCount())
        assertEquals(
            "切换的事实只该留一条 SYSTEM 消息（界面上的居中小字）",
            listOf(ChatProjector.dividerFor(ChatMode.SCENE)),
            store.messages.filter { it.role == MessageRole.SYSTEM }.map { it.text }
        )
        assertEquals("切换后引擎仍要叫对方开个场（空批次也算一轮）", 1, llm.calls)
        assertEquals(ChatMode.SCENE, store.conversations["c1"]?.mode)
    }

    /**
     * **已经在面对面里，模型还填 `"next_mode": "scene"` 时，不许弹切换卡片。**
     *
     * 钉的是真机上的一个现象：明明已经面对面了，界面还弹出"看起来你们要见面了"。
     * 机理在 `AgentTurnRunner`：那句"提议与当前模式相同就丢掉"的判断只用来决定
     * **要不要写一条日志事件**，而真正往上抛的 `modeProposal` 用的是**没过滤过的值** ——
     * 于是判断写在那儿、效果一点没有。而且因为它挡掉了日志事件，**这个 bug 在库里查不到**
     * （事件表和消息表都干净，只有界面上多一张卡片）。
     *
     * 诱因是提示词：`10-protocol-scene.txt` 的示例自己写着 `"next_mode": "scene"`，
     * 两边都改了，但**这条测试守的是代码那一侧** —— 提示词只能降低概率，判不掉。
     */
    @Test
    fun `提议切到已经在的模式 - 不弹卡片`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"嗯"}],"next_mode":"scene"}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1", mode = ChatMode.SCENE)
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("你看这个", kind = EventKind.SAY)
        awaitSettled(controller)

        assertNull(
            "已经在面对面，就不该再问一次要不要见面。实际：${controller.state.value.modeProposal}",
            controller.state.value.modeProposal
        )
    }

    /**
     * 上一条的**反面**：真的提议切到**另一个**模式时，卡片必须照旧弹出来。
     *
     * 没有这一条，把判定写成"永远不提议"也能让上一条通过 ——
     * 而这个功能的正面行为（模型说"我先走了" → 问你回不回聊天）就没人守了。
     */
    @Test
    fun `提议切到另一个模式 - 卡片照旧弹`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"那我先走了"}],"next_mode":"chat"}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1", mode = ChatMode.SCENE)
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("路上小心", kind = EventKind.SAY)
        awaitSettled(controller)

        assertEquals(
            "面对面里提议回聊天，是真提议，必须弹卡片",
            ChatMode.CHAT,
            controller.state.value.modeProposal
        )
    }

    // ------------------------------------------------------------------
    // 手动（两段）发送
    // ------------------------------------------------------------------

    /**
     * 手动模式的第一段：**按下发送只进时间线，不起轮**。
     *
     * 钉的是这个模式的全部意义 —— 用户要能"先把话摆出来，再决定什么时候说出去"。
     */
    @Test
    fun `手动模式 - 第一次按下不发给模型，只落进时间线`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("第一条")
        awaitSettled(controller)

        assertEquals("一次 API 调用都不该发生", 0, llm.calls)
        assertEquals("消息要已经落盘（不然切会话/重启就丢了）", listOf("第一条"), store.userTexts())
        assertEquals("界面上应当能看到它", 1, controller.state.value.items.size)
        assertEquals("发送键该变蓝：有 1 条代发", 1, controller.state.value.pendingSendCount)
        assertEquals("代发不该出现在「待处理气泡」那条路径上（那是自动模式的机制）", 0, controller.state.value.pendingItems.size)
    }

    /**
     * 手动模式的第二段：**再按一次才推给模型**，且推完之后"有代发"归零。
     *
     * 顺带钉住"推送后这些消息**能**进上下文" —— 只测"不推不发"会漏掉反方向，
     * 而写反了（比如标记落库在起轮之后）会得到"推了但它还是看不见"。
     */
    @Test
    fun `手动模式 - 第二次按下推给模型，且推完归零`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("推我之前")
        awaitSettled(controller)
        assertEquals(0, llm.calls)

        controller.push()
        awaitSettled(controller) { llm.calls > 0 }

        assertEquals("推了就该起轮", 1, llm.calls)
        assertEquals("推完不该还剩代发", 0, controller.state.value.pendingSendCount)
        assertTrue("库里也应当是已推送", store.messages.filter { it.role == MessageRole.USER }.all { it.sentToModel })
        val sent = llm.requests.last().joinToString("\n") { it.content }
        assertTrue("推出去的内容必须真的进了模型上下文", sent.contains("推我之前"))
    }

    /**
     * **推送成功的那一刻，发送键就该不蓝了** —— 不是等这一轮结束，也不是等再按一次。
     *
     * 钉的是真机上发现的现象：蓝色按钮要点两次才变灰（第一次其实已经发出去了，
     * 而且对话结束时它也会自己变灰）。根因是 `pushUnpushed` 里标完数据库
     * **没刷新界面状态**，于是 `pendingSendCount` 一直停在 1，直到回合收尾那次
     * `reload()` 才被顺手纠正。
     *
     * ⚠️ 这条测试**必须**在"请求已发出、回合还没结束"这个瞬间断言：上面的
     * `推完归零` 那条等整轮跑完再断言，而收尾那次 `reload()` 会把状态修正成 0 ——
     * 所以**它对这个问题是假绿的**。这里用一个同步回调，从流开始收集的那一刻取状态。
     */
    @Test
    fun `手动模式 - 推送成功立刻不蓝，不用等回合结束`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("推我")
        awaitSettled(controller)
        assertEquals("先确认它蓝着", 1, controller.state.value.pendingSendCount)

        // 请求刚发出的那一刻取一份状态：此时"已经推出去"了，但这一轮还没结束
        var atRequest: ChatUiState? = null
        llm.onRequest = { atRequest = controller.state.value }

        controller.push()
        awaitSettled(controller) { llm.calls > 0 }

        assertEquals(
            "推送已经落库了，蓝色就必须消失（不能等回合结束）",
            0,
            atRequest?.pendingSendCount
        )
        assertEquals("轮次结束之后当然也还是 0", 0, controller.state.value.pendingSendCount)
    }

    /**
     * **代发的消息绝不能进模型上下文** —— 哪怕这一轮不是用户推的。
     *
     * 场景是真实存在的：手动模式下用户先写了一句话没推，这时点了一张"要不要见面"的
     * 确认卡片 → 引擎自己起一轮（cue）。如果上下文只按"最近消息"取，那句没推的话
     * 就会被角色看见并回应 —— "不按第二次就不发给模型"这句承诺当场作废，
     * 而且**界面上完全看不出来**。
     */
    @Test
    fun `手动模式 - 代发消息不进模型上下文，引擎起的那轮也看不见`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("这句还没推")
        awaitSettled(controller)

        // 引擎自己起一轮（模式切换会产生 cue，不需要用户消息）
        controller.applyMode(ChatMode.SCENE)
        awaitSettled(controller) { llm.calls > 0 }

        val sent = llm.requests.last().joinToString("\n") { it.content }
        assertFalse(
            "没推送的消息被模型看见了 —— 这就是那个模式的承诺被破坏",
            sent.contains("这句还没推")
        )

        // 推了之后它必须出现（否则等于这句话永远发不出去）
        controller.push()
        awaitSettled(controller) { llm.calls > 1 }
        val afterPush = llm.requests.last().joinToString("\n") { it.content }
        assertTrue("推送之后必须进上下文", afterPush.contains("这句还没推"))
    }

    /**
     * 自动模式**一个字都不能变**：按下就（防抖后）起轮，代发恒为 0。
     *
     * 这条守的是"加开关"这个要求本身 —— 开关默认在自动那一侧，而自动那一侧
     * 必须还是老样子。
     */
    @Test
    fun `自动模式 - 行为与从前一致`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))   // 默认 AUTO
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("照旧")
        awaitSettled(controller) { llm.calls > 0 }

        assertEquals("自动模式该照旧起轮", 1, llm.calls)
        assertEquals("自动模式没有「代发」这个概念", 0, controller.state.value.pendingSendCount)
        assertTrue(
            "自动模式落盘时必须直接标成已推送，否则用户说的话永远进不了上下文",
            store.messages.filter { it.role == MessageRole.USER }.all { it.sentToModel }
        )
        val sent = llm.requests.last().joinToString("\n") { it.content }
        assertTrue(sent.contains("照旧"))
    }

    /**
     * 切回自动模式时，手动模式留下的代发**要自动发出去**。
     *
     * 不补这一步就是个死局：那些消息永远躺在库里，而自动模式又不画"蓝"，
     * 用户没有任何提示，只会觉得"我明明发过那句话"。
     */
    @Test
    fun `手动模式留下代发后切回自动 - 会被发出去`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler), sendMode = SendMode.MANUAL)
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("留下没推的")
        awaitSettled(controller)
        assertEquals(0, llm.calls)

        // 设置页改完 → 回到聊天页会重新 open
        host.sendMode = SendMode.AUTO
        controller.open("c1")
        awaitSettled(controller) { llm.calls > 0 }

        assertEquals("切回自动后不该有代发留在库里", 0, controller.state.value.pendingSendCount)
        val sent = llm.requests.last().joinToString("\n") { it.content }
        assertTrue("留下的那句话必须被发出去", sent.contains("留下没推的"))
    }

    /**
     * **一批里有两条、最后一条又没有句末标点时，也必须开工。**
     *
     * 这条钉的是一个真 bug（原来就有，被"一次发多条"逼出来）：
     * 防抖在等完之后会"再看一眼"，而它当年的判据是 `recheck.waitMs > 0` ——
     * 对"≥2 条、末条无标点"的批次，重新判定**仍然**是 CHUNKING 档、仍然要等，
     * 于是它每次都把这一批让给"下一轮"，而下一轮根本不存在（没有新的 `send()`
     * 就不会再起一个防抖任务）→ **这一批永远不被认领，模型永远不被调用**。
     *
     * 用户看到的就是"我把话发出去了，它一直不回"。
     * 正确判据是"批次变没变"（见 `scheduleDebounce` 里的注释）。
     */
    @Test
    fun `连发两条且末条没标点 - 也必须开工`() = runTest {
        val store = FakeStore()
        val llm = StubLlm("""{"events":[{"type":"say","text":"在的"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        controller.send("在吗")
        controller.send("我想问个事")
        awaitSettled(controller) { it.pendingItems.isEmpty() && it.live == null }

        assertEquals(
            "这一批必须被认领并落盘。实际：${store.messages.map { "${it.role}:${it.text}" }}",
            listOf("在吗", "我想问个事"),
            store.userTexts()
        )
        assertEquals("也必须真的调用了模型", 1, llm.calls)
    }

    /**
     * `remember` 事件写进记忆时必须带上**生效的人设**。
     *
     * 记忆按人设分开存（见 `Memory.personaId`）。写的时候不带人设、或者带了会话上那个
     * **原始**值（可能是空的、可能指向已删掉的卡）而不是 `resolvePersona` 解析之后的
     * 结果，就会出现"记了但从来不出现" —— 因为注入时是按解析后的 id 去查的。
     */
    @Test
    fun `remember 事件按生效的人设存`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"嗯"},{"type":"remember","text":"对方在准备考研"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("在吗")
        awaitSettled(controller) { it.live == null && it.pendingHint.isEmpty() }

        assertEquals("应当写进一条记忆", 1, store.memories.size)
        assertEquals(
            "应当归给解析之后生效的那张卡（FakeStore 里只有 id=default 一张）",
            "default",
            store.memories.single().personaId
        )
    }

    /**
     * **注入记忆时不串人设**：只要"这张人设自己的"和"通用的"。
     *
     * 这条守的是这个功能最要紧的失败模式：让「小默」知道只有跟「阿岩」聊过的事。
     * 注入走的是 `AgentTurnRunner.pickMemories(persona.id)` → 存储层那条
     * "自己的 + 通用的"查询。fake 把 Room 的语义照抄了一份，所以它真正钉住的是
     * **调用方传对了没有** —— 传 `null` 的含义是"全都要"，那正是串味。
     */
    @Test
    fun `注入记忆时不串人设`() = runTest {
        val store = FakeStore()
        store.memories += listOf(
            Memory(id = "m1", content = "这是通用的", personaId = ""),
            Memory(id = "m2", content = "这是小默的", personaId = "default"),
            Memory(id = "m3", content = "这是别人设的", personaId = "someone-else")
        )
        val llm = StubLlm("""{"events":[{"type":"say","text":"嗯"}]}""")
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        awaitSettled(controller)
        controller.send("在吗")
        awaitSettled(controller) { it.live == null && it.pendingHint.isEmpty() }

        val prompt = llm.requests.first().joinToString("\n") { it.content }
        assertTrue("通用的那条该在：\n$prompt", prompt.contains("这是通用的"))
        assertTrue("小默自己的该在：\n$prompt", prompt.contains("这是小默的"))
        assertFalse("别人设的绝不能出现在这里：\n$prompt", prompt.contains("这是别人设的"))
    }

    /** close() 之后不再接受新消息（防止旧 controller 继续写库）。 */
    @Test    fun `close 之后 send 无效`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.close()

        controller.send("在吗")
        advanceUntilIdle()

        assertEquals(0, store.userMessageCount())
    }

    /**
     * 换会话时待处理消息不该被带过去。
     *
     * 断言的是**认领结果**而不是 state：`advanceUntilIdle()` 会把防抖延迟一并推完，
     * 所以等回到调用点时回合可能已经跑过了，state 里自然什么都看不到。
     * 认领结果才是"这批到底属于谁"的直接证据。
     */
    @Test
    fun `换会话清掉待处理消息`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1")
        seed(store, "c2")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("这是 c1 的")

        controller.open("c2")
        advanceUntilIdle()

        val claimed = controller.claimPending()
        assertTrue(
            "换会话后不该还能认领到上一个会话的消息，实际：${claimed.batch.map { it.text }}",
            claimed.batch.isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // 播出期间的屏幕内容
    // ------------------------------------------------------------------

    /**
     * **一条消息在它这一轮里，自始至终只该被画出来一次。**
     *
     * 这条直接对着真机观感写的。用户报的现象是：
     * "先一次性输出两条带时间戳的气泡，然后下面又播放第一条动画，
     *   然后第二条取代掉第一条，放完后就一起消失了。"
     *
     * 用真机截图定位这个问题是**不可能的** —— `uiautomator dump` 一次要 2.5 秒，
     * 而那一幕只存在于一两帧里（我实测抓了 7 帧，一次都没抓到）。
     *
     * ## 根因
     *
     * `ChatProjector` 只判断了 `kind.isVisible`，**没判断这条事件到底出口了没有**，
     * 而 `whenAt = deliveredAt ?: createdAt` 又把这一点掩盖掉了。于是：
     *
     * 助手事件在"模型返回后、播出前"就以 `PENDING` 落盘 —— 它们被当成**历史**
     * 画了出来（两条带时间戳的气泡一次性冒出来），紧接着活气泡又从第一条开始播动画，
     * 第二条再把第一条顶掉。用户描述的就是这一幕。
     *
     * 根因的门禁在 [ChatProjector]，由 `ChatProjectorTest` 直接盯着；这里盯的是
     * **整条链路的后果**："一轮里每一条只出现一次、且都以活气泡的方式出场"。
     *
     * ## 为什么断言最终状态而不是逐帧
     *
     * 我试过逐帧录屏，连着失败两次，两次都是**方法**的问题而不是代码的问题：
     *
     * - 挂 `collect` 到测试 scope：`advanceUntilIdle()` 一口气把整场演出推完，
     *   中间状态写了却没有一帧被观察到。
     * - 挂 `backgroundScope`：`advanceUntilIdle()` **根本不会**推进
     *   `backgroundScope`（实测同一次运行里 `this` 跑了 `[0,1,2]` 而它是 `[]`），
     *   录到 0 帧 —— 断言因为"什么都没看到"而**假绿**，比失败更糟。
     * - 手工 `advanceTimeBy(25)` 采样：一次调用会把该时刻之前的任务全跑完，
     *   活气泡在同一个 tick 里被设置又被撤掉，采样点根本碰不到它。
     *
     * 所以这里改成断言**留下来的事实**：库里每条的状态与交付时间、最终屏幕内容。
     * 这些是确定性的，不依赖调度时序 —— 而"屏幕上的中间帧"这件事，
     * 已经由 `ChatProjectorTest` 从纯函数那一侧钉死了。
     */
    @Test
    fun `一轮跑完之后每条回复只出现一次且各自有时间戳`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"玩过一点"},""" +
                """{"type":"say","text":"就是那只蛞蝓猫吧","gap_ms":300}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("你玩过雨世界吗")
        // 等到"两条回复都进了历史"这个终态，而不是只把虚拟时钟推空
        awaitSettled(controller) { it.items.size >= 3 && it.live == null }

        val state = controller.state.value
        val itemTexts = state.items.map { it.textOf() }
        val log = app.solochat.core.log.Log.entries.value
            .joinToString("\n") { it.text.replace('\n', ' ') }
        val debug = "events=${store.events.map { "${it.id}:${it.text}:${it.delivery}:${it.deliveredAt}" }} " +
            "messages=${store.messages.map { "${it.role}:${it.text}" }} " +
            "conversationId=${state.conversationId} live=${state.live?.text} " +
            "pending=${state.pendingItems.map { it.textOf() }}\n日志：\n$log"

        // 1. 用户消息 + 两条回复，各恰好一次
        assertEquals(
            "最终历史里每条只该出现一次。$debug",
            listOf("你玩过雨世界吗", "玩过一点", "就是那只蛞蝓猫吧"),
            itemTexts
        )

        // 2. 活气泡必须已经让位（不然就是"历史 + 活气泡"各画一份）
        assertEquals("演出结束后不该还剩活气泡", null, state.live)

        // 3. 待处理气泡必须已经交接掉
        assertTrue("演出结束后不该还剩待处理气泡：${state.pendingItems}", state.pendingItems.isEmpty())

        // 4. **逐条**落定，不是整场结束一起补。
        //    统一补会让同一轮的每条共用一个交付时间 —— 真机日志里实测两条回复的
        //    时间戳完全相同，界面上就是"一起蹦出来"。
        val deliveries = store.events.sortedBy { it.id }.map { it.deliveredAt }
        assertEquals("两条回复都该有交付时间", 2, deliveries.filterNotNull().size)
        assertEquals(
            "两条回复的交付时间必须不同（各自在播出的那一刻落定），实际：$deliveries",
            2,
            deliveries.filterNotNull().toSet().size
        )
        assertTrue(
            "交付时间必须递增，实际：$deliveries",
            (deliveries[0] ?: 0L) < (deliveries[1] ?: 0L)
        )
    }

    /**
     * 新一轮开始前，**没出口的事件一个字都不许画到屏幕上**。
     *
     * 这是上一条的"前提版"：`SessionController` 第一次 `reload()` 时，助手事件
     * 已经以 `PENDING` 落盘了。如果投影没挡住它们，用户就会看到上面那两条
     * 还没播的气泡。这条断言直接盯住那一刻的 `state`。
     */
    @Test
    fun `还没播的事件不会出现在屏幕上`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"还没播的第一句"},""" +
                """{"type":"say","text":"还没播的第二句"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        // 等到"事件已经落盘"这一刻。落盘发生在真实调度器那侧，具体第几毫秒不可预测，
        // 而**要观测的那个条件是可判定的**，所以轮询条件而不是猜一个 advance 次数。
        controller.send("在吗")
        awaitSettled(controller) { store.events.isNotEmpty() }

        // ⚠️ 这一步不能只断言"落盘的 PENDING 事件不在 items 里"：等到那一刻时它们
        // 很可能已经被正常播完、名正言顺地进了历史 —— 那样断言会假绿（我第一版就是）。
        // 要断言的是**时序**：助手的话绝不能比用户的待处理气泡先出现。
        val pendingNotYetSent = controller.state.value.pendingItems.isNotEmpty()
        if (pendingNotYetSent) {
            val leaked = store.events
                .filter { !it.delivery.spoken }
                .mapNotNull { it.displayText }
                .filter { text -> controller.state.value.items.any { it.textOf() == text } }
            assertTrue(
                "用户消息还挂在待处理里时，助手的话就被画进了历史：$leaked，" +
                    "界面条目=${controller.state.value.items.map { it.textOf() }}",
                leaked.isEmpty()
            )
        }

        // 这一轮的最终结果里，每一条也只出现一次
        val settledTexts = controller.state.value.items.map { it.textOf() }
        assertEquals(
            "最终历史里每条只该出现一次，实际：$settledTexts",
            listOf("在吗", "还没播的第一句", "还没播的第二句"),
            settledTexts
        )
    }

    @Test
    fun `多事件逐条出口时列表预览只推进到当前已播事件`() = runTest {
        val store = FakeStore()
        val texts = listOf("列表第一句", "列表第二句", "列表第三句")
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"${texts[0]}"},""" +
                """{"type":"say","text":"${texts[1]}"},{"type":"say","text":"${texts[2]}"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)
        controller.open("c1")
        advanceUntilIdle()

        val previewAtEachDelivery = mutableListOf<String>()
        store.onDelivered = { delivered ->
            val visible = timelineEntries(store.events, store.messages)
            val preview = ConversationPreview.from(visible.last())
            assertEquals(delivered.text, preview.text)
            previewAtEachDelivery += preview.text
        }

        controller.send("多事件列表投影测试")
        awaitSettled(controller) { state -> state.live == null && state.pendingHint.isEmpty() }

        assertEquals(texts, previewAtEachDelivery)
        assertEquals(1, llm.calls)
    }

    /**
     * 等状态稳定到满足 [until]。
     *
     * **为什么不挂 `collect`、也不只用 `advanceUntilIdle()`** —— 我在这里连栽三次，
     * 三个坑都值得记住：
     *
     * 1. 挂 `collect` 到测试 scope：`advanceUntilIdle()` 把整场演出一口气推完，
     *    中间状态写了却没有一帧被真正观察到。
     * 2. 挂到 `backgroundScope`：`advanceUntilIdle()` **根本不会**推进它
     *    （实测同一次运行里 `this` 跑了 `[0,1,2]` 而它是 `[]`），录到 0 帧 ——
     *    断言因为"什么都没看到"而**假绿**，比失败更糟。
     * 3. 只用 `withTimeout` + `advanceUntilIdle()` 轮询：`withTimeout` 走的是**虚拟**
     *    时钟，而这里要等的是"真实调度器上那几步跑完"（`markDelivered` 这类挂起调用
     *    会把协程短暂带离 `TestCoroutineScheduler`）。虚拟时间一秒都不会因为真实执行
     *    而流逝，于是必然超时。
     *
     * 所以：推进虚拟时间 + 真睡一小会儿，轮询一个**可判定的终态**。
     */
    private suspend fun TestScope.awaitSettled(
        controller: SessionController,
        attempts: Int = 1_000,
        until: (ChatUiState) -> Boolean = { it.pendingHint.isEmpty() && it.live == null }
    ) {
        repeat(attempts) {
            advanceUntilIdle()
            if (until(controller.state.value)) return
            withContext(Dispatchers.Default) { Thread.sleep(2) }
        }
        throw AssertionError(
            "等了 ${attempts} 轮状态仍未稳定，最后的状态：${controller.state.value}"
        )
    }

    /**
     * 插话打断时，**已经显示给用户的那条气泡不许在时间轴上往后跳**。
     *
     * 真机上看到的怪事：一条 16:34:27 就已经出现在屏幕上的气泡，因为中途被打断，
     * 时间戳被盖成了 16:34:31（打断发生的那一刻）—— 于是它跑到用户刚发的那条消息
     * 下面去了，看起来像顺序错乱。根因是 `interruptPlayback` 把所有被切断的事件
     * 都统一盖上了"当前时间"。
     *
     * 现在每条气泡的交付时间取**它出现的那一刻**（记在 `Playing.startedAt` 里），
     * 正常播完与被打断两条路径口径一致。
     *
     * 复刻的是用户真实操作：**第二条正在播的时候插话**。第一条那时已经稳稳落在
     * 历史里（它是"已经显示给用户、且不会被回滚"的那一条），所以要断言的是它。
     */
    @Test
    fun `插话打断不会把已经显示的气泡往后挪`() = runTest {
        val store = FakeStore()
        // 第二轮给一个不同的脚本，免得两轮的气泡混在一起分不清
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"第一句"},{"type":"say","text":"第二句"},""" +
                """{"type":"say","text":"第三句"}]}""",
            """{"events":[{"type":"say","text":"插话之后的回复"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")

        // 插话的时机：第二条**开始播**的那一刻（也就是第一条刚播完）。
        //
        // 为什么必须从回调里插进去，而不是外面轮询状态：`advanceUntilIdle()` 会在
        // 一次调用里把整场演出跑完，外面永远看不到"第二条正在播"这个瞬间（我在这个
        // 测试上试了三次轮询，次次都错过）。回调是**同步**的，时机是确定的。
        val deliveredTexts = mutableListOf<String>()
        val deliveryTrace = mutableListOf<String>()
        // 第一条落定那一刻的交付时间 —— 这正是"用户看到它的时刻"。
        // 断言它之后不再变，就是"气泡不会在时间轴上往下跳"。
        var firstDeliveredAt: Long? = null
        var interrupted = false
        val controllerRef = arrayOfNulls<SessionController>(1)
        val sink = object : EventSink by store {
            override suspend fun markDelivered(id: Long, at: Long, gapMs: Int, typingMs: Int) {
                store.markDelivered(id, at, gapMs, typingMs)
                val event = store.events.firstOrNull { it.id == id }
                val text = event?.text.orEmpty()
                deliveredTexts += text
                deliveryTrace += "delivered($text)"
                if (text == "第一句") firstDeliveredAt = event?.deliveredAt
                // 第一条播完的那一刻插话 —— 演出正卡在第一条与第二条之间
                if (!interrupted && text == "第一句") {
                    interrupted = true
                    deliveryTrace += "→ send(算了)"
                    controllerRef[0]?.send("算了")
                    deliveryTrace += "← send 返回"
                }
            }

            override suspend fun setDelivery(id: Long, delivery: DeliveryState) {
                store.setDelivery(id, delivery)
                val text = store.events.firstOrNull { it.id == id }?.text.orEmpty()
                deliveryTrace += "setDelivery($text→$delivery)"
            }
        }
        val controller = newController(this, store, host, sink)
        controllerRef[0] = controller

        controller.open("c1")
        advanceUntilIdle()
        controller.send("在吗")
        awaitSettled(controller) { state -> state.items.any { it.textOf() == "插话之后的回复" } }

        val firstShownAt = firstDeliveredAt ?: error("没能观察到第一条落定")
        assertTrue(
            "插话的时机没抓对（应当观察到第一条播完），实际落定顺序：$deliveredTexts",
            interrupted
        )

        // 第一轮的三条都属于同一个 turnId。用 turnId 定位，不要用文字 ——
        // 第二次 `send` 会让用户气泡出现在列表前面，按下标取会取错事件。
        val firstTurnId = store.events.first { it.text == "第一句" }.turnId
        val first = store.events.first { it.turnId == firstTurnId && it.text == "第一句" }

        assertTrue(
            "已经显示过的「第一句」必须留下出口状态，实际 ${first.delivery}",
            first.delivery.spoken
        )
        assertEquals(
            "「第一句」的交付时间必须是它出现的那一刻（$firstShownAt），" +
                "不能被打断时刻覆盖（那会让它在时间轴上往下跳），实际 ${first.deliveredAt}",
            firstShownAt,
            first.deliveredAt
        )

        // ⚠️ 这里**不**断言"第二句是 interrupted 还是 canceled"。
        //
        // 那取决于打断插进哪一帧：`interruptPlayback` 与演出协程的退出是并发的，
        // 两种结果都合理（用户都确实看到了那半句的一部分）。写死一个会让测试变 flaky，
        // 而它并不能反映任何真实的观感问题 —— 真机实测两种情况下界面都正常。
        //
        // 唯一必须守住的是"绝不停在 PENDING"：那才是真正的悬空事件
        // （既没播出去、也没被取消，重启收尾时才会被扫掉）。
        val unplayed = store.events.filter { it.turnId == firstTurnId }
        val dangling = unplayed.filter { it.delivery == DeliveryState.PENDING }
        assertTrue(
            "第一轮不该有任何事件停在 PENDING（悬空事件），实际：$dangling\n" +
                "全部：${unplayed.map { "${it.text}:${it.delivery}" }}",
            dangling.isEmpty()
        )
    }

    /**
     * **下一轮必须能看见我上一轮说过的话。**
     *
     * 用户报的原话：「我觉得你必须把模型自己的回答也放到上下文里面去……模型的回答并没有
     * 计入，所以它又重新回答了"我在睡觉"——显然是把这两句话合并到一次完整语义里面了。」
     *
     * 现象解释得通：如果上下文里只有对方的发言，那么历史会长成这样
     * （对方：「你玩过雨世界吗」/「hello」/「zqx」/「aaaaaaaa」/「bbbbbbbb」），
     * 模型看到的是一串**对方自说自话**，于是它会把自己上一轮已经回答过的内容
     * 重新当成新话题来答 —— 正是用户观察到的"合并到一次完整语义里"。
     *
     * 这条测试走完整的编排链路（SessionController → AgentTurnRunner → PromptBuilder），
     * 断言第二轮请求里：
     * 1. 有 `我：` 开头的行，且内容就是上一轮说过的；
     * 2. 上一轮说过的每一句都在（不能只带一部分）；
     * 3. `我：` 的行出现在**这一轮对方发言之前**（顺序不能反）。
     */
    @Test
    fun `下一轮能看到我上一轮说过的话`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"刚睡醒"},{"type":"say","text":"才看到"}]}""",
            """{"events":[{"type":"say","text":"怎么了"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("在吗")
        awaitSettled(controller) { it.items.size >= 3 && it.live == null }

        // 第一轮结束时，库里必须已经有一条 assistant 消息 —— 那是下一轮唯一的来源
        //
        // ⚠️ 现在是**一个气泡一条消息**（播完就落盘，不等整场演完）。
        // 断言刻意不写死条数以外的东西，但把"两句都在、且各成一条"钉住：
        // 如果哪天又退回"整场演完写一次"，这里会立刻红。
        assertTrue(
            "第一轮结束后消息表里应当有：用户「在吗」+ 助手说的两句（一个气泡一条消息）。" +
                "实际：${store.messages.map { "${it.role}:${it.text}" }}\n" +
                "事件状态：${store.events.map { "${it.text}:${it.delivery}" }}",
            store.messages.count { it.role == MessageRole.ASSISTANT } == 2 &&
                store.messages.filter { it.role == MessageRole.ASSISTANT }.map { it.text }.toSet() ==
                setOf("刚睡醒", "才看到")
        )

        // 第二轮
        controller.send("zqx")
        awaitSettled(controller) { state -> state.items.any { it.textOf() == "怎么了" } }

        assertEquals("应当调用了两次模型", 2, llm.requests.size)
        val secondTurnPrompt = llm.requests[1]
            .joinToString("\n") { it.content }

        assertTrue(
            "第二轮请求里必须有「我：」开头的行（否则模型看不到自己说过的话）。\n$secondTurnPrompt",
            secondTurnPrompt.contains("我：")
        )
        assertTrue(
            "上一轮说的「刚睡醒」必须出现在第二轮上下文里。\n$secondTurnPrompt",
            secondTurnPrompt.contains("刚睡醒")
        )
        assertTrue(
            "上一轮说的「才看到」也必须出现（不能只带一部分）。\n$secondTurnPrompt",
            secondTurnPrompt.contains("才看到")
        )

        // 顺序：我上一轮说的，必须排在对方这一轮发的之前
        val mineAt = secondTurnPrompt.indexOf("我：刚睡醒")
        val theirsAt = secondTurnPrompt.indexOf("对方：zqx")
        assertTrue("两行都该在上下文里（我=$mineAt 对方=$theirsAt）", mineAt >= 0 && theirsAt >= 0)
        assertTrue(
            "顺序反了：我上一轮说的（$mineAt）应当排在对方这一轮发的（$theirsAt）之前。" +
                "\n$secondTurnPrompt",
            mineAt < theirsAt
        )
    }

    /**
     * 插话打断之后，**已经出口的那条仍然留在助手历史里**。
     *
     * 这是"逐条落盘"要保住的东西：不能因为"整场没演完"就把已经说出口的那句
     * 一起丢掉 —— 否则模型下一轮看不到自己说过，于是重复一遍。
     *
     * 同时也是那个竞态的看门人：原来历史是整场演完统一写一次，而插话时
     * `send()` 会先异步去写历史、紧接着就可能开下一轮读消息表，两条协程顺序不保证。
     */
    @Test
    fun `插话打断不会丢掉已出口的那条`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"第一句"},{"type":"say","text":"第二句"}]}""",
            """{"events":[{"type":"say","text":"接了"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        // 第一条一落定就插话 —— 让这一轮在半场被截断（第二条根本没机会播）
        var interrupted = false
        var previewWhenInterrupted: String? = null
        store.onDelivered = {
            if (!interrupted) {
                interrupted = true
                previewWhenInterrupted = ConversationPreview.from(
                    timelineEntries(store.events, store.messages).last()
                ).text
                controller.send("算了")
            }
        }
        controller.send("在吗")
        awaitSettled(controller) { it.live == null && it.pendingHint.isEmpty() }

        val assistantTexts = store.messages
            .filter { it.role == MessageRole.ASSISTANT }
            .map { it.text }
        assertTrue(
            "已经出口的「第一句」必须留在助手历史里（不能因为整场没演完就一起丢掉）。" +
                "实际：$assistantTexts",
            assistantTexts.any { it.contains("第一句") }
        )
        assertEquals("第一句", previewWhenInterrupted)
        val finalTimeline = timelineEntries(store.events, store.messages)
        val finalPreview = ConversationPreview.from(finalTimeline.last())
        assertEquals("接了", finalPreview.text)
        assertFalse("被插话取消的余项不能进入人物列表投影", finalTimeline.any {
            it is app.solochat.core.model.TimelineEntry.EventRow && it.event.displayText == "第二句"
        })
    }

    /**
     * **这一轮说的每一条都要留在屏幕上，而且只留一份。**
     *
     * 用户报的原话：「它好像发信息发完之前只显示一条，逐条覆盖，然后发完之后再都会显示出来」。
     *
     * 这个描述把根因说得很准，翻译成代码就是：
     *
     * - 演出期间 `reload` 会被"演出进行中"挡掉 → 已经播完的那几条**在 `items` 里没有位置**；
     * - 屏幕上只剩当前那条活气泡 → 新的一条出来就把它顶掉（"逐条覆盖"）；
     * - 直到整场演完、收尾那次 `reload` 才把两条**一起**放出来（"发完之后再都会显示出来"）。
     *
     * 修法是**每播完一条就地刷新、并撤掉活气泡**，于是每条出现的那一刻就进了历史，
     * 后面的条目不会把它顶掉。
     *
     * ## 这条测试为什么断言最终状态
     *
     * "第 1 条在第 2 条播出时还在屏幕上"这个中间态，在测试里**抓不到**：
     * `advanceUntilIdle()` 一次把整场推完；而 `onDelivered` 回调发生在写入的**中间**
     * （那时 step 自己的收尾还没跑），拿它当观测点会看到还没刷新的旧状态 ——
     * 我第一版就是这么写的，断言直接红在 `items=[在吗] live=第一句`。
     *
     * 真正能确定性地说明"没有被顶掉"的是**终态**：三条各一份、顺序正确、无残留活气泡。
     * 中间过程由真机连续截屏确认（见 `tools/watch-screen.ps1`）。
     */
    @Test
    fun `一轮里的每一条都会留在屏幕上且只有一份`() = runTest {
        val store = FakeStore()
        val llm = StubLlm(
            """{"events":[{"type":"say","text":"第一句"},{"type":"say","text":"第二句"},""" +
                """{"type":"say","text":"第三句"}]}"""
        )
        val host = FakeHost(store, llm, VirtualClock(testScheduler))
        seed(store, "c1")
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()
        controller.send("在吗")
        awaitSettled(controller) { state -> state.items.size >= 4 && state.live == null }

        val settled = controller.state.value
        assertEquals(
            "三条回复都要在，顺序不能乱，也不能有重复。" +
                "实际：${settled.items.map { it.textOf() }}",
            listOf("在吗", "第一句", "第二句", "第三句"),
            settled.items.map { it.textOf() }
        )
        assertEquals("收尾后不该还剩活气泡（会画成第四条）", null, settled.live)
        assertTrue("收尾后不该还剩待处理气泡", settled.pendingItems.isEmpty())
    }

    /**
     * 真机故障（2026-10-08）的收尾动作：打开会话时，把"整段 JSON 被当成一条 `say`"
     * 的历史脏行还原成本来该有的几条。
     *
     * 守两件事：
     * 1. **接线**（`open()` 里真的调了这件事）。这个仓库里"写了但从来没被调用"已经
     *    栽过两次（见 02-handoff §4 第 1 条），所以"接线"本身值得一条测试。
     * 2. **可见结果 + 内容一个字符都不改**：投影出来的条目是三条正常的气泡/动作/旁白，
     *    而且每一条正文都能在原始 JSON 里**原样找到**（没有改写、没有吞掉）。
     *
     * 用的是真的 [DegradedTurn]（假存储不自己实现一套），所以这条测试也在替
     * Room 那边守"拆分规则只有一份"。
     */
    @Test
    fun `打开会话时还原整段 JSON 的脏行 - 内容一个字符都不改`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1", ChatMode.SCENE)
        val raw = """{"events":[{"type":"scene","text":"窗台上两只杯子被天光照着"},""" +
            """{"type":"typing_ms":1800},""" +
            """{"type":"say","speaker":"陆昭","text":"睡吧"}]}"""
        store.appendEvent(
            Event(
                conversationId = "c1",
                kind = EventKind.SAY,
                speaker = "ta",
                text = raw,
                turnId = 7,
                mode = ChatMode.SCENE,
                delivery = DeliveryState.DELIVERED,
                createdAt = 1_700_000_000_000L,
                deliveredAt = 1_700_000_001_000L
            )
        )
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        val items = controller.state.value.items
        assertEquals(
            "应当还原成两条（三段里有一段是独立停顿，它折进下一条，不产生事件）。" +
                "实际：${items.map { it.textOf() }}",
            listOf("窗台上两只杯子被天光照着", "睡吧"),
            items.map { it.textOf() }
        )
        assertTrue("旁白要还原成 Narration", items.any { it is ChatItem.Narration })
        assertTrue(
            "台词要还原成 Theirs",
            items.any { it is ChatItem.Theirs && it.text == "睡吧" }
        )
        assertEquals(
            "那段 1800ms 的停顿该折进紧随其后的台词",
            1800,
            store.events.single { it.text == "睡吧" }.typingHintMs
        )

        // 内容逐字不变：每一条正文都能在原文里原样找到
        for (item in items) {
            assertTrue(
                "正文被改写了：「${item.textOf()}」不在原文里",
                raw.contains(item.textOf())
            )
        }
        assertEquals("这一轮只该被要求修复一次", listOf("c1"), store.repaired)
    }

    /**
     * 修复**不能碰正在演出的那一轮**。
     *
     * `PENDING` / `PLAYING` 的行属于手里那场演出：这时把行换成几条新的，
     * 演出协程的 id 就指向了不存在的记录，那条气泡会静默消失（真机上表现为
     * "回复凭空没了"，而且日志里看不出）。落定之后再修就没有这个问题。
     */
    @Test
    fun `正在演出的那一轮不动 - 只修已经落定的`() = runTest {
        val store = FakeStore()
        val host = FakeHost(store, StubLlm("{}"), VirtualClock(testScheduler))
        seed(store, "c1", ChatMode.SCENE)
        val raw = """{"events":[{"type":"say","text":"睡吧"}]}"""
        store.appendEvent(
            Event(
                conversationId = "c1",
                kind = EventKind.SAY,
                speaker = "ta",
                text = raw,
                turnId = 7,
                mode = ChatMode.SCENE,
                delivery = DeliveryState.PENDING,
                createdAt = 1_700_000_000_000L
            )
        )
        val controller = newController(this, store, host)

        controller.open("c1")
        advanceUntilIdle()

        assertEquals("PENDING 的那条要原样留着（它属于正在演出的一轮）", 1, store.events.size)
        assertEquals(raw, store.events.single().text)
    }

    private fun ChatItem.textOf(): String = when (this) {
        is ChatItem.Mine -> text
        is ChatItem.MyAction -> text
        is ChatItem.Theirs -> text
        is ChatItem.Action -> text
        is ChatItem.Narration -> text
        is ChatItem.Divider -> text
        is ChatItem.Live -> text
    }
}

