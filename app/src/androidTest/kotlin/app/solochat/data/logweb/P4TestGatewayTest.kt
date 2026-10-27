package app.solochat.data.logweb

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.ConversationOperations
import app.solochat.core.agent.PriceResolver
import app.solochat.core.agent.PreparedAgentCall
import app.solochat.core.agent.SendMode
import app.solochat.core.llm.LlmClient
import app.solochat.core.llm.LlmDelta
import app.solochat.core.llm.LlmRequest
import app.solochat.core.llm.RequestEndpoint
import app.solochat.core.model.CallConfigSnapshot
import app.solochat.core.model.CapabilitySource
import app.solochat.core.model.ModelPrice
import app.solochat.core.model.ModelThinking
import app.solochat.core.model.ModelUse
import app.solochat.core.model.SelectionSource
import app.solochat.core.model.ThinkingMode
import app.solochat.core.model.ThinkingSource
import app.solochat.core.model.VisionCapability
import app.solochat.core.model.VisionControl
import app.solochat.core.ports.ClockPort
import app.solochat.core.prompt.PromptResources
import app.solochat.core.protocol.PacingConfig
import app.solochat.data.MediaLibrary
import app.solochat.data.db.ConversationModelBindingEntity
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.data.logweb.P4TestCommand
import app.solochat.data.logweb.P4TestGateway
import app.solochat.data.logweb.P4TestSetupRequest
import app.solochat.play.SessionController
import app.solochat.play.SessionRegistry
import app.solochat.play.TurnHost
import app.solochat.test.IsolatedMediaContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P4TestGatewayTest {
    @Test fun p4CommandsReachSyntheticModelInOrderForAutoAndManualAndPushReplayDoesNotClaimLaterInput() = runBlocking {
        for (mode in listOf(SendMode.AUTO, SendMode.MANUAL)) {
            val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "p4-gateway-${mode.wire}")
            val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).allowMainThreadQueries().build()
            val store = RoomStore(database)
            val media = MediaLibrary(context, store)
            val clock = object : ClockPort {
                override fun now() = System.currentTimeMillis()
                override suspend fun sleep(ms: Long) {}
            }
            val host = SyntheticTurnHost(store, media, clock, mode)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val sessions = SessionRegistry(scope) { controllerScope -> SessionController(host, controllerScope, store, store) }
            val gateway = P4TestGateway(store, media, sessions, clock)
            val conversationIds = mutableListOf<String>()
            try {
                val subject = gateway.setup(P4TestSetupRequest("ordered-${mode.wire}-${System.nanoTime()}"))
                conversationIds += subject.conversationId
                assertTrue(gateway.execute(P4TestCommand("text-a", subject.requestId, "send", text = "P4 第一段文字")).accepted)
                assertTrue(gateway.execute(P4TestCommand("sticker", subject.requestId, "sticker")).accepted)
                assertTrue(gateway.execute(P4TestCommand("text-b", subject.requestId, "send", text = "P4 第二段文字")).accepted)
                assertTrue(gateway.execute(P4TestCommand("image", subject.requestId, "image")).accepted)

                val beforePush = store.recentMessages(subject.conversationId, -1).sortedBy { it.id }
                assertEquals(4, beforePush.size)
                assertEquals(listOf(false, false, false, false), beforePush.map { it.sentToModel })
                assertEquals("P4 第一段文字", app.solochat.core.model.UserUtterance.decode(beforePush[0].text).second)
                assertEquals("", beforePush[1].text)
                assertEquals("P4 第二段文字", app.solochat.core.model.UserUtterance.decode(beforePush[2].text).second)
                assertEquals("", beforePush[3].text)

                assertTrue(gateway.execute(P4TestCommand("first-push", subject.requestId, "push")).accepted)
                withTimeout(10_000) {
                    while (host.requests.isEmpty()) delay(10)
                }
                val request = host.requests.single()
                val userRequests = request.messages.filter { it.role == "user" }
                val transcript = request.messages.joinToString("\n") { it.content }
                val textA = transcript.indexOf("P4 第一段文字")
                val stickerImage = transcript.indexOf("本轮附图第1张")
                val textB = transcript.indexOf("P4 第二段文字")
                val ordinaryImage = transcript.indexOf("本轮附图第2张")
                assertTrue("请求正文须按文字A、表情、文字B、普通图片保留关联顺序",
                    textA >= 0 && textA < stickerImage && stickerImage < textB && textB < ordinaryImage)
                assertEquals(2, request.messages.sumOf { it.images.size })
                assertTrue(userRequests.isNotEmpty())
                val sentRows = store.recentMessages(subject.conversationId, -1).filter { it.sentToModel }
                assertEquals(4, sentRows.size)

                assertTrue(gateway.execute(P4TestCommand("text-after", subject.requestId, "send", text = "P4 推送后文字")).accepted)
                val replay = gateway.execute(P4TestCommand("first-push", subject.requestId, "push"))
                assertFalse(replay.accepted)
                assertTrue(replay.state.ui.duplicate)
                assertEquals(1, host.requests.size)
                assertEquals(listOf("P4 推送后文字"), store.unpushedUserMessages(subject.conversationId)
                    .map { app.solochat.core.model.UserUtterance.decode(it.text).second })

                if (mode == SendMode.AUTO) {
                    host.debounceMs = 0L
                    assertTrue(gateway.execute(P4TestCommand("auto-send", subject.requestId, "send", text = "P4 自动防抖验证")).accepted)
                    withTimeout(10_000) {
                        while (host.requests.size < 2) delay(10)
                    }
                    assertTrue(host.requests.last().messages.joinToString("\n") { it.content }.contains("P4 自动防抖验证"))
                }
            } finally {
                conversationIds.forEach { sessions.remove(it) }
                scope.cancel()
                database.close()
                context.clean()
            }
        }
    }

    @Test fun pushPreflightRejectionClaimsRequestIdBeforeLaterInputArrives() = runBlocking {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "p4-push-rejection")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        val store = RoomStore(database)
        val media = MediaLibrary(context, store)
        val clock = object : ClockPort {
            override fun now() = 10L
            override suspend fun sleep(ms: Long) {}
        }
        val host = RejectingTurnHost(store, clock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sessions = SessionRegistry(scope) { controllerScope -> SessionController(host, controllerScope, store, store) }
        val gateway = P4TestGateway(store, media, sessions, clock)
        var conversationId: String? = null
        try {
            val subject = gateway.setup(P4TestSetupRequest("request-${System.nanoTime()}"))
            conversationId = subject.conversationId
            store.saveConversationModelBinding(ConversationModelBindingEntity(subject.conversationId,
                "NEEDS_CONFIRMATION", null, null, "合成预检拒绝", "p4-test", null))

            assertTrue(gateway.execute(P4TestCommand("send-before", subject.requestId, "send", text = "第一条代发")).accepted)
            val firstPush = gateway.execute(P4TestCommand("old-push", subject.requestId, "push"))
            assertFalse(firstPush.accepted)
            assertEquals(1, store.unpushedUserMessages(subject.conversationId).size)

            assertTrue(gateway.execute(P4TestCommand("send-after", subject.requestId, "send", text = "后来新增")).accepted)
            val replay = gateway.execute(P4TestCommand("old-push", subject.requestId, "push"))
            assertFalse(replay.accepted)
            assertTrue("命中上次已拒绝的同一 P4 requestId", replay.state.ui.duplicate)
            assertEquals(listOf("第一条代发", "后来新增"), store.unpushedUserMessages(subject.conversationId).map { it.text }
                .map { app.solochat.core.model.UserUtterance.decode(it).second })
            assertTrue(store.calls(subject.conversationId).isEmpty())
        } finally {
            conversationId?.let { sessions.remove(it) }
            scope.cancel()
            database.close()
            context.clean()
        }
    }

    private class RejectingTurnHost(private val store: RoomStore, private val clock: ClockPort) : TurnHost {
        override val deps: AgentDeps = object : AgentDeps {
            override val conversations = ConversationOperations()
            override val storage = store
            override val clock = this@RejectingTurnHost.clock
            override val llm = object : LlmClient {
                override fun chatStream(request: LlmRequest) = flow { emit(LlmDelta.Text("{\"events\":[]}")) }
                override suspend fun listModels() = emptyList<String>()
                override suspend fun ping(model: String) = 0L
            }
            override val prompts = PromptResources("规则", "聊天协议", "场景协议", "聊天风格", "场景风格", "记忆", "主动")
            override val pricing = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = ModelPrice()
            }
            override fun config() = ChatConfig(sendMode = SendMode.MANUAL)
            override fun rhythmSamples() = emptyList<Long>()
            override fun debounceWindowMs() = 0L
        }

        override fun config() = ChatConfig(sendMode = SendMode.MANUAL)
        override fun pacingConfig() = PacingConfig()
        override fun refreshRhythm() {}
        override suspend fun prepareChatCall(conversationId: String?) = error("合成测试预检拒绝")
    }

    private class SyntheticTurnHost(
        store: RoomStore,
        media: MediaLibrary,
        private val clock: ClockPort,
        private val sendMode: SendMode
    ) : TurnHost {
        val requests = mutableListOf<LlmRequest>()
        var debounceMs = 60_000L
        override val deps: AgentDeps = object : AgentDeps {
            override val conversations = ConversationOperations()
            override val storage = store
            override val clock = this@SyntheticTurnHost.clock
            override val mediaContent = media
            override val llm = object : LlmClient {
                override fun chatStream(request: LlmRequest) = flow {
                    requests += request
                    emit(LlmDelta.Text("{\"events\":[]}"))
                }
                override suspend fun listModels() = emptyList<String>()
                override suspend fun ping(model: String) = 0L
            }
            override val prompts = PromptResources("合成规则", "合成聊天协议", "合成场景协议", "合成聊天风格", "合成场景风格", "合成人物记忆", "合成主动性")
            override val pricing = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = ModelPrice()
            }
            override fun config() = ChatConfig(baseUrl = "https://fake.invalid/v1", apiKey = "virtual-key",
                model = "synthetic-p4", sendMode = sendMode)
            override fun rhythmSamples() = emptyList<Long>()
            override fun debounceWindowMs() = this@SyntheticTurnHost.debounceMs
        }

        override fun config() = deps.config()
        override fun pacingConfig() = PacingConfig()
        override fun refreshRhythm() {}
        override suspend fun prepareChatCall(conversationId: String?) = PreparedAgentCall(
            config = config(),
            snapshot = CallConfigSnapshot(1, "synthetic", "合成测试", "synthetic-p4", "P4 synthetic", "synthetic-p4",
                ModelUse.CHAT, SelectionSource.GLOBAL_DEFAULT, ModelThinking(ThinkingMode.AUTO), ThinkingSource.MODEL_PREFERENCE),
            price = ModelPrice(),
            priceAt = clock.now(),
            endpoint = RequestEndpoint("https://fake.invalid/v1", "virtual-key"),
            thinkingParameters = null,
            visionCapability = VisionCapability(VisionControl.SUPPORTED, CapabilitySource.USER_CONFIRMED)
        )
    }
}
