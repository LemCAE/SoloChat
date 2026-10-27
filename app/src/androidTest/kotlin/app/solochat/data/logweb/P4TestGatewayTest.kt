package app.solochat.data.logweb

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.ConversationOperations
import app.solochat.core.agent.PriceResolver
import app.solochat.core.agent.SendMode
import app.solochat.core.llm.LlmClient
import app.solochat.core.llm.LlmDelta
import app.solochat.core.llm.LlmRequest
import app.solochat.core.model.ModelPrice
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P4TestGatewayTest {
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
}
