package app.solochat.play

import app.solochat.core.agent.*
import app.solochat.core.llm.*
import app.solochat.core.model.*
import app.solochat.core.ports.ClockPort
import app.solochat.core.prompt.PromptResources
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

/** 实际执行器的统计与配置回归，不复制请求装配或记账算法。 */
class CallAccountingTest {
    private class Store : SessionControllerTest.FakeStore() {
        val usages = mutableListOf<Usage>()
        override suspend fun recordUsage(conversationId: String, model: String, usage: Usage, at: Long) {
            usages += usage
        }
    }
    private fun deps(store: Store,
        configProvider: () -> ChatConfig = { ChatConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "global") },
        priceProvider: () -> ModelPrice = { ModelPrice(1.0, 2.0) },
        respond: suspend kotlinx.coroutines.flow.FlowCollector<LlmDelta>.(LlmRequest) -> Unit) =
        object : AgentDeps {
            override val conversations = ConversationOperations()
            override val storage = store
            override val clock = object : ClockPort {
                override fun now() = 1_700_000_000_000L
                override suspend fun sleep(ms: Long) = Unit
            }
            override val prompts = PromptResources("安全", "协议", "协议", "风格", "风格", "记忆", "主动")
            override val pricing = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = priceProvider()
            }
            override val llm = object : LlmClient {
                override fun chatStream(request: LlmRequest) = flow { respond(request) }
                override suspend fun listModels() = emptyList<String>()
                override suspend fun ping(model: String) = 0L
            }
            override fun config() = configProvider()
            override fun rhythmSamples() = emptyList<Long>()
            override fun debounceWindowMs() = 200L
        }
    private suspend fun runWith(text: String): Usage {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        store.appendMessage(Message(conversationId = "c", role = MessageRole.USER, text = text, sentToModel = true))
        AgentTurnRunner(deps(store) { emit(LlmDelta.Text("""{"events":[{"type":"say","text":"收到"}]}""")) })
            .run(AgentTurn("c", text, 0)).toList()
        return store.usages.single()
    }
    @Test fun `未关联人物卡时拒绝调用而不自动选择默认卡`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c"))
        var called = false
        val events = AgentTurnRunner(deps(store) { called = true })
            .run(AgentTurn("c", "", 0)).toList()
        assertFalse(called)
        assertTrue(store.calls.isEmpty())
        assertTrue(events.any { it is AgentEvent.Failed })
    }
    @Test fun `缺 usage 时长正文应增加输入估算且完整脚本输出不能算零`() = runTest {
        val short = runWith("短")
        val long = runWith("长".repeat(8000))
        assertTrue(long.promptTokens > short.promptTokens + 5000)
        assertTrue(long.completionTokens > 0)
    }
    @Test fun `回合收尾不能把调用期间更新的会话名覆盖回去`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default", title = "旧名"))
        AgentTurnRunner(deps(store) {
            store.upsertConversation(store.getConversation("c")!!.copy(title = "新名"))
            emit(LlmDelta.Text("""{"events":[{"type":"say","text":"收到"}]}"""))
        }).run(AgentTurn("c", "输入", 0)).toList()
        assertEquals("新名", store.getConversation("c")!!.title)
    }
    @Test fun `两会话指定模型隔离且继承在下一请求读取全局新值`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("a", personaId = "default", modelOverride = "  own  "))
        store.upsertConversation(Conversation("b", personaId = "default"))
        var global = ChatConfig(baseUrl = "https://example.invalid", apiKey = "test", model = "global")
        val models = mutableListOf<String>()
        val runner = AgentTurnRunner(deps(store, configProvider = { global }) {
            models += it.model
            emit(LlmDelta.Text("""{"events":[]}"""))
        })
        runner.run(AgentTurn("a", "", 0)).toList()
        runner.run(AgentTurn("b", "", 0)).toList()
        global = global.copy(model = "new-global")
        runner.run(AgentTurn("b", "", 0)).toList()
        runner.run(AgentTurn("a", "", 0)).toList()
        assertEquals(listOf("own", "global", "new-global", "own"), models)
        assertEquals("new-global", global.model)
        assertEquals(4, store.calls.size)
        assertTrue(store.calls.values.all { it.status == CallStatus.SILENT })
    }
    @Test fun `部分用量只补缺失字段并保留真实零缓存`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        AgentTurnRunner(deps(store) {
            emit(LlmDelta.Finished(promptTokens = 123, cachedTokens = 0, usageFrames = listOf("{\"prompt_tokens\":123}")))
            emit(LlmDelta.Finished(reasoningTokens = 9))
            emit(LlmDelta.Text("""{"events":[]}"""))
        }).run(AgentTurn("c", "", 0)).toList()
        val call = store.calls.values.single()
        assertEquals(TokenAmount(123, TokenSource.RETURNED), call.input)
        assertEquals(TokenAmount(0, TokenSource.RETURNED), call.cached)
        assertEquals(TokenAmount(9, TokenSource.RETURNED), call.reasoning)
        assertEquals(TokenSource.ESTIMATED, call.output.source)
        assertEquals(listOf("{\"prompt_tokens\":123}"), call.usageFrames)
    }
    @Test fun `修改单价不会重新计算已结束调用的价格快照`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        var price = ModelPrice(1.0, 2.0, source = "测试")
        val runner = AgentTurnRunner(deps(store, priceProvider = { price }) {
            price = ModelPrice(100.0, 200.0)
            emit(LlmDelta.Finished(1000, 100, 0))
            emit(LlmDelta.Text("""{"events":[]}"""))
        })
        runner.run(AgentTurn("c", "", 0)).toList()
        val call = store.calls.values.single()
        assertEquals(1.0, call.price.inputPerM, 0.0)
        assertEquals(0.0012, recordedCost(call).low!!, 0.0000001)
    }

    @Test fun `回合中切接口组保留原凭据与单价后续回合使用新组且保留会话模型`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default", modelOverride = "own"))
        var global = ChatConfig(baseUrl = "https://old.example", apiKey = "old-test-key", model = "old-default")
        var price = ModelPrice(1.0, 2.0)
        val requests = mutableListOf<LlmRequest>()
        val runner = AgentTurnRunner(deps(store, configProvider = { global }, priceProvider = { price }) { request ->
            requests += request
            global = global.copy(baseUrl = "https://new.example", apiKey = "new-test-key", model = "new-default")
            price = ModelPrice(10.0, 20.0)
            emit(LlmDelta.Finished(1000, 100, 0))
            emit(LlmDelta.Text("""{"events":[]}"""))
        })
        runner.run(AgentTurn("c", "", 0)).toList()
        runner.run(AgentTurn("c", "", 0)).toList()
        assertEquals(listOf("https://old.example", "https://new.example"), requests.map { it.endpoint!!.baseUrl })
        assertEquals(listOf("old-test-key", "new-test-key"), requests.map { it.endpoint!!.apiKey })
        assertTrue(requests.all { it.model == "own" })
        assertEquals(listOf(1.0, 10.0), store.calls.values.map { it.price.inputPerM })
        assertEquals("own", store.getConversation("c")!!.modelOverride)
    }
    @Test fun `接口失败也留一次尝试而未收到的输出不能算零`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        AgentTurnRunner(deps(store) { throw LlmException(LlmError.Failed(LlmErrorKind.CONNECT, "失败")) })
            .run(AgentTurn("c", "", 0)).toList()
        val call = store.calls.values.single()
        assertEquals(CallStatus.FAILED, call.status)
        assertEquals(TokenSource.ESTIMATED, call.input.source)
        assertNull(call.output.value)
        assertTrue(store.usages.isEmpty())
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `取消收到部分输出的调用仍收尾一次且标明不完整`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        val runner = AgentTurnRunner(deps(store) { emit(LlmDelta.Text("半份脚本")); awaitCancellation() })
        val job = launch { runner.run(AgentTurn("c", "", 0)).toList() }
        runCurrent()
        assertEquals(CallStatus.STARTED, store.calls.values.single().status)
        job.cancel(); job.join()
        val call = store.calls.values.single()
        assertEquals(CallStatus.CANCELED, call.status)
        assertEquals(TokenSource.ESTIMATED, call.output.source)
        assertTrue(call.outputIncomplete)
        assertTrue(store.events.isEmpty())
    }
    @Test fun `限流服务端错误与接口超时只调用一次且不承诺自动重试`() = runTest {
        for (kind in listOf(LlmErrorKind.RATE_LIMIT, LlmErrorKind.SERVER, LlmErrorKind.TIMEOUT)) {
            val store = Store()
            store.upsertConversation(Conversation("c", personaId = "default"))
            var requests = 0
            val events = AgentTurnRunner(deps(store) {
                requests++
                throw LlmException(LlmError.Failed(kind, "请求失败"))
            }).run(AgentTurn("c", "", 0)).toList()
            val error = events.filterIsInstance<AgentEvent.Failed>().single().error
            assertEquals(kind, error.kind)
            assertFalse(error.advice().contains("自动重试"))
            assertEquals(1, requests)
            assertEquals(CallStatus.FAILED, store.calls.values.single().status)
            assertEquals(kind.name, store.calls.values.single().failureKind)
        }
    }
    @Test fun `执行器等待超时显示超时失败并保留部分输出用量而不重试`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        var requests = 0
        val events = AgentTurnRunner(deps(store, configProvider = {
            ChatConfig(baseUrl = "https://example.invalid", apiKey = "test", model = "global", timeoutMs = 1000)
        }) {
            requests++
            emit(LlmDelta.Text("半份脚本"))
            awaitCancellation()
        }).run(AgentTurn("c", "", 0)).toList()
        val error = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertEquals(LlmErrorKind.TIMEOUT, error.kind)
        val call = store.calls.values.single()
        assertEquals(CallStatus.FAILED, call.status)
        assertEquals("TIMEOUT", call.failureKind)
        assertEquals(TokenSource.ESTIMATED, call.output.source)
        assertTrue(call.outputIncomplete)
        assertEquals(1, requests)
        assertTrue(store.events.isEmpty())
        assertTrue(store.usages.isEmpty())
    }
    @Test fun `上层等待超时仍传播取消而不转换为请求超时提示`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        val events = mutableListOf<AgentEvent>()
        val result = kotlinx.coroutines.withTimeoutOrNull(1000) {
            AgentTurnRunner(deps(store) { awaitCancellation() })
                .run(AgentTurn("c", "", 0)).toList(events)
            true
        }
        assertNull(result)
        assertTrue(events.none { it is AgentEvent.Failed })
        val call = store.calls.values.single()
        assertEquals(CallStatus.CANCELED, call.status)
        assertEquals("CANCELED", call.failureKind)
    }
    @Test fun `统计预览与实际请求全文一致且代发不进入上下文`() = runTest {
        val store = Store()
        store.upsertConversation(Conversation("c", personaId = "default"))
        store.appendMessage(Message(conversationId = "c", role = MessageRole.USER, text = "已推送", sentToModel = true))
        store.appendMessage(Message(conversationId = "c", role = MessageRole.USER, text = "代发不要泄漏", sentToModel = false))
        var actual: LlmRequest? = null
        val dependencies = deps(store) { actual = it; emit(LlmDelta.Text("""{"events":[]}""")) }
        val plan = ContextPlanner(dependencies).prepare("c")
        assertEquals(0, store.calls.size)
        AgentTurnRunner(dependencies).run(AgentTurn("c", "", 0)).toList()
        assertEquals(plan.prompt.messages, actual!!.messages)
        assertEquals(plan.estimatedTokens, store.calls.values.single().contextEstimate)
        assertFalse(actual!!.messages.any { it.content.contains("代发不要泄漏") })
    }
}
