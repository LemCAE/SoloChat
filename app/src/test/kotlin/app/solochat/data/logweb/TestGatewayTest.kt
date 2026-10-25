package app.solochat.data.logweb

import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.agent.PreparedAgentCall
import app.solochat.core.llm.LlmClient
import app.solochat.core.llm.LlmDelta
import app.solochat.core.llm.LlmRequest
import app.solochat.core.llm.RequestEndpoint
import app.solochat.core.model.CallConfigSnapshot
import app.solochat.core.model.ModelThinking
import app.solochat.core.model.SelectionSource
import app.solochat.core.model.ThinkingSource
import app.solochat.core.model.ThinkingMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.ModelPrice
import app.solochat.core.ports.ClockPort
import app.solochat.core.prompt.PromptResources
import app.solochat.play.SessionControllerTest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 直接调用 HTTP 入口使用的业务类，避免另写一套测试会话逻辑。 */
class TestGatewayTest {
    private class Fixture {
        val callSnapshots = mutableMapOf<String, CallConfigSnapshot>()
        val store = object : SessionControllerTest.FakeStore() {
            override suspend fun recordCallStartedWithConfig(call: app.solochat.core.model.CallRecord,
                snapshot: CallConfigSnapshot) {
                callSnapshots[call.id] = snapshot
                recordCall(call)
            }
        }
        val deleted = mutableListOf<String>()
        val requests = mutableListOf<LlmRequest>()
        var rejectPreflight = false
        val deps = object : AgentDeps {
            override val conversations = app.solochat.core.agent.ConversationOperations()
            override val storage = store
            override val clock = object : ClockPort {
                override fun now() = 1_700_000_000_000L
                override suspend fun sleep(ms: Long) = Unit
            }
            override val llm = object : LlmClient {
                override fun chatStream(request: LlmRequest) = flow {
                    requests += request
                    emit(LlmDelta.Text("""{"events":[{"type":"say","text":"收到"}]}"""))
                    emit(LlmDelta.Finished(100, 20, 0))
                }
                override suspend fun listModels() = listOf("test-model")
                override suspend fun ping(model: String) = 0L
            }
            override val prompts = PromptResources("规则", "聊天协议", "场景协议", "聊天风格", "场景风格", "记忆", "主动")
            override val pricing = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = ModelPrice()
            }
            override fun config() = ChatConfig(baseUrl = "https://example.invalid", apiKey = "test-only", model = "test-model")
            override suspend fun prepareCall(conversationId: String, use: app.solochat.core.model.ModelUse): PreparedAgentCall {
                check(!rejectPreflight) { "v2 选择待确认" }
                val config = config().copy(baseUrl = "https://v2.example/v1", apiKey = "v2-test-only", model = "v2-test-model")
                return PreparedAgentCall(config, CallConfigSnapshot(
                    1, "provider", "测试接口", "model-id", "模型", "v2-test-model", use,
                    SelectionSource.GLOBAL_DEFAULT, ModelThinking(ThinkingMode.AUTO), ThinkingSource.MODEL_PREFERENCE
                ), ModelPrice(1.0, 2.0), clock.now(), RequestEndpoint(config.baseUrl, config.apiKey), null)
            }
            override fun rhythmSamples() = emptyList<Long>()
            override fun debounceWindowMs() = 0L
        }
        val gateway = TestGateway(deps) { id ->
            deleted += id
            store.conversations.remove(id)
            store.messages.removeAll { it.conversationId == id }
            store.events.removeAll { it.conversationId == id }
        }
        suspend fun seed() {
            store.upsertConversation(Conversation(id = "real", title = "旧对话"))
            store.appendMessage(Message(conversationId = "real", role = MessageRole.USER,
                text = "旧内容逐字保留", sentToModel = true))
        }
    }

    @Test
    fun `reset 新建测试会话 - 旧会话与旧消息一个字符都不改`() = runTest {
        val f = Fixture()
        f.seed()
        val oldConversation = f.store.getConversation("real")
        val oldMessages = f.store.recentMessages("real", -1)
        val result = f.gateway.send("real", "新测试输入", reset = true)
        assertTrue("重置不能顺带清空旧会话", f.deleted.isEmpty())
        assertNotEquals("real", result.conversationId)
        assertTrue(f.store.getConversation(result.conversationId)!!.title.startsWith("测试会话 "))
        assertEquals(oldConversation, f.store.getConversation("real"))
        assertEquals(oldMessages, f.store.recentMessages("real", -1))
    }

    @Test
    fun `测试接口发送 - 模型真正看到输入且不留下未推送草稿`() = runTest {
        val f = Fixture()
        val result = f.gateway.send(null, "测试输入不能消失", reset = false)
        assertEquals("", result.error)
        assertEquals(1, f.requests.size)
        assertTrue(f.requests.single().messages.any { it.content.contains("对方：测试输入不能消失") })
        assertEquals("v2-test-model", f.requests.single().model)
        assertEquals("https://v2.example/v1", f.requests.single().endpoint?.baseUrl)
        assertEquals("v2-test-model", result.model)
        assertEquals("provider", f.callSnapshots.values.single().providerId)
        assertEquals("v2-test-model", f.callSnapshots.values.single().requestModelId)
        assertTrue(f.store.unpushedUserMessages(result.conversationId).isEmpty())
    }

    @Test
    fun `reset 连续两次 - 两个专用测试会话都保留`() = runTest {
        val f = Fixture()
        val first = f.gateway.send(null, "第一轮", reset = true)
        val second = f.gateway.send(first.conversationId, "第二轮", reset = true)
        assertNotEquals(first.conversationId, second.conversationId)
        assertNotNull(f.store.getConversation(first.conversationId))
        assertNotNull(f.store.getConversation(second.conversationId))
        assertTrue(f.deleted.isEmpty())
    }

    @Test fun `v2预检拒绝时不落盘用户消息`() = runTest {
        val f = Fixture()
        f.rejectPreflight = true
        val failure = runCatching { f.gateway.send(null, "不得先写入", reset = false) }.exceptionOrNull()
        assertNotNull(failure)
        val conversation = f.store.conversations.values.single()
        assertTrue(f.store.recentMessages(conversation.id, -1).isEmpty())
        assertTrue(f.requests.isEmpty())
    }
}
