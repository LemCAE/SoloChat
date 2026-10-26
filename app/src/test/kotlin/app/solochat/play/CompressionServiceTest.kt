package app.solochat.play

import app.solochat.core.agent.*
import app.solochat.core.llm.*
import app.solochat.core.model.*
import app.solochat.core.ports.ClockPort
import app.solochat.core.prompt.PromptResources
import app.solochat.core.model.CredentialAvailability
import app.solochat.data.ModelAwareCallPreparation
import app.solochat.data.ModelCallPreparation
import app.solochat.data.ModelRegistryDocument
import app.solochat.data.RegistryProvider
import app.solochat.data.RegistryModel
import app.solochat.data.RegistrySelection
import app.solochat.data.LegacyProviderPricing
import app.solochat.data.pricing.BoundModelPrice
import app.solochat.data.pricing.ProviderPricing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 使用实际装配、摘要业务和执行器，FakeStore 只复刻存储语义。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompressionServiceTest {
    private class Store : SessionControllerTest.FakeStore() {
        var people = listOf(Persona("default", "小默"))
        override suspend fun listPersonas() = people
    }
    private class Fixture(respond: suspend FlowCollector<LlmDelta>.(LlmRequest) -> Unit = {
        emit(LlmDelta.Text("周六计划去茶馆。地点在门口，仍未出发；对方不喝咖啡。"))
        emit(LlmDelta.Finished(400, 50, 0, 0))
    }) {
        val store = Store()
        val requests = mutableListOf<LlmRequest>()
        var config = ChatConfig(baseUrl = "https://example.invalid", apiKey = "test", model = "global")
        var price = ModelPrice(1.0, 2.0)
        var timedPrice: ((Long) -> ModelPrice)? = null
        var now = 1700000000000L
        var usePreparedCall = false
        var preparedCallFactory: (suspend (String, ModelUse) -> PreparedAgentCall?)? = null
        var priceRuleRevision = 1
        val deps = object : AgentDeps {
            override val conversations = ConversationOperations()
            override val storage = store
            override val clock = object : ClockPort {
                override fun now() = now
                override suspend fun sleep(ms: Long) = Unit
            }
            override val prompts = PromptResources("安全", "协议", "协议", "风格", "风格", "记忆规则", "主动")
            override val pricing = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = price
                override fun resolve(model: String, config: ChatConfig, at: Long) = timedPrice?.invoke(at) ?: price
            }
            override val llm = object : LlmClient {
                override fun chatStream(request: LlmRequest) = flow { requests += request; respond(request) }
                override suspend fun listModels() = emptyList<String>()
                override suspend fun ping(model: String) = 0L
            }
            override fun config() = config
            override suspend fun prepareCall(conversationId: String, use: ModelUse): PreparedAgentCall? =
                conversations.withConversation(conversationId) {
                    val factory = preparedCallFactory
                    if (!usePreparedCall && factory == null) null else if (factory != null) factory(conversationId, use) else {
                        val frozenConfig = config
                        val revision = priceRuleRevision
                        val frozenPriceResolver = timedPrice
                        val frozenPrice = frozenPriceResolver?.invoke(now) ?: price
                        val snapshot = CallConfigSnapshot(1, "provider", "测试接口", "model-id", "测试模型",
                            frozenConfig.model, use, SelectionSource.GLOBAL_DEFAULT,
                            ModelThinking(ThinkingMode.OFF), ThinkingSource.COMPRESSION_DEFAULT)
                        PreparedAgentCall(frozenConfig, snapshot, frozenPrice, now,
                            RequestEndpoint(frozenConfig.baseUrl, frozenConfig.apiKey), null,
                            "price-rule-$revision") { at -> frozenPriceResolver?.invoke(at) ?: price }
                    }
                }
            override fun rhythmSamples() = emptyList<Long>()
            override fun debounceWindowMs() = 1L
        }
        val service = CompressionService(deps)
        suspend fun init(pairs: Int = 4, size: Int = 0) {
            store.upsertConversation(Conversation("c", personaId = "default"))
            repeat(pairs) { pair(it + 1, size = size) }
        }
        suspend fun pair(number: Int, size: Int = 0, mode: ChatMode = ChatMode.CHAT, delivery: DeliveryState = DeliveryState.DELIVERED) {
            val time = now + number * 1000L
            store.appendMessage(Message(conversationId = "c", role = MessageRole.USER,
                text = "用户$number" + "长".repeat(size), turnId = time, createdAt = time, sentToModel = true))
            store.appendEvent(Event(conversationId = "c", kind = EventKind.SAY, text = "回复$number", turnId = time + 100,
                createdAt = time + 100, deliveredAt = time + 100, mode = mode, delivery = delivery))
            store.appendMessage(Message(conversationId = "c", role = MessageRole.ASSISTANT, text = "回复$number",
                turnId = time + 100, createdAt = time + 100))
        }
        suspend fun preview(pairNumber: Int = 1, method: CompressionMethod = CompressionMethod.ORIGINAL,
            budget: Int = 8000) = service.preview("c", store.messages.filter { it.role == MessageRole.ASSISTANT }[pairNumber - 1].id,
                method, budget, 200)
        suspend fun draft(pairNumber: Int = 1, method: CompressionMethod = CompressionMethod.ORIGINAL) = service.generate(preview(pairNumber, method))
    }

    private suspend fun mustReject(action: suspend () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalArgumentException) { rejected = true } catch (_: IllegalStateException) { rejected = true }
        assertTrue("操作必须拒绝", rejected)
    }

    @Test fun `切点保留最近完整交流和最新未回应输入且不靠同 turnId 拼问答`() = runTest {
        val f = Fixture(); f.init(3)
        f.store.appendMessage(Message(conversationId = "c", role = MessageRole.USER, text = "未回应", sentToModel = true))
        f.store.appendMessage(Message(conversationId = "c", role = MessageRole.USER, text = "代发", sentToModel = false))
        val options = f.service.options("c")
        assertEquals(listOf(2, 4), options.cuts.map { it.count })
        assertEquals(3, options.cuts.last().retained)
        assertFalse(options.history.any { it.text == "代发" })
    }
    @Test fun `同一次调用的多条回复不能从中间切开`() = runTest {
        val f = Fixture(); f.init(1)
        val turn = f.store.messages.last().turnId
        f.store.appendEvent(Event(conversationId = "c", kind = EventKind.SAY, text = "第二句", turnId = turn,
            delivery = DeliveryState.DELIVERED))
        f.store.appendMessage(Message(conversationId = "c", role = MessageRole.ASSISTANT, text = "第二句", turnId = turn))
        f.pair(2)
        val cuts = f.service.options("c").cuts
        assertEquals(listOf(3), cuts.map { it.count })
        mustReject { f.preview(1) }
    }
    @Test fun `来源含未出口回复或消息与事件不一致时不能把该前缀压缩`() = runTest {
        val f = Fixture(); f.init()
        f.store.events[0] = f.store.events[0].copy(delivery = DeliveryState.PENDING)
        assertTrue(f.service.options("c").cuts.isEmpty())
        f.store.events[0] = f.store.events[0].copy(delivery = DeliveryState.DELIVERED, text = "另一句")
        assertTrue(f.service.options("c").cuts.isEmpty())
    }
    @Test fun `生成只保存草稿应用和恢复均不修改任何原消息或事件`() = runTest {
        val f = Fixture(); f.init()
        val messages = f.store.messages.toList(); val events = f.store.events.toList()
        val draft = f.draft()
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)
        assertEquals("DRAFT", f.store.contextRevision(draft.id)!!.state)
        f.service.apply(draft) { true }
        val compressed = ContextPlanner(f.deps).prepare("c")
        assertEquals(draft.id, f.store.getConversation("c")!!.activeContextRevisionId)
        assertFalse(compressed.prompt.messages[1].content.contains("用户1"))
        assertTrue(compressed.prompt.messages[1].content.contains("用户4"))
        assertTrue(compressed.prompt.messages[1].content.indexOf("周六计划") < compressed.prompt.messages[1].content.indexOf("用户2"))
        f.service.restore("c", draft.id) { true }
        assertTrue(ContextPlanner(f.deps).prepare("c").prompt.messages[1].content.contains("用户1"))
        assertEquals(messages, f.store.messages); assertEquals(events, f.store.events)
        assertEquals(1, f.store.revisions.size)
    }

    @Test fun `含图来源摘要生成启用撤销且附件内容变化后旧草稿失效`() = runTest {
        val f = Fixture(); f.init(2)
        val message = f.store.messages.first { it.role == MessageRole.USER }
        val asset = MediaAsset("summary-image", "a".repeat(64), "image/png", 16, 2, 2,
            fileKey = "objects/summary-image", createdAt = message.createdAt)
        f.store.mediaAssets[asset.id] = asset
        f.store.messageAttachments += MessageAttachment(message.id, 0, asset.id)

        val draft = f.draft(1)
        assertTrue(f.requests.single().messages.last().content.contains("图片附件1：本轮未附原图"))
        f.service.apply(draft) { true }
        assertEquals(draft.id, f.store.getConversation("c")!!.activeContextRevisionId)
        f.service.restore("c", draft.id) { true }
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)

        f.store.mediaAssets[asset.id] = asset.copy(sha256 = "c".repeat(64))
        mustReject { f.service.apply(draft) { true } }
    }

    @Test fun `含图来源摘要生成启用撤销并在附件内容变化后拒绝旧草稿`() = runTest {
        val f = Fixture(); f.init(2)
        val message = f.store.messages.first { it.role == MessageRole.USER }
        val asset = MediaAsset("summary-image", "a".repeat(64), "image/png", 16, 2, 2,
            fileKey = "objects/summary-image", createdAt = message.createdAt)
        f.store.mediaAssets[asset.id] = asset
        f.store.messageAttachments += MessageAttachment(message.id, 0, asset.id)

        val draft = f.draft(1)
        assertTrue(f.requests.single().messages.last().content.contains("图片附件1：本轮未附原图"))
        f.service.apply(draft) { true }
        assertEquals(draft.id, f.store.getConversation("c")!!.activeContextRevisionId)
        f.service.restore("c", draft.id) { true }
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)

        f.store.mediaAssets[asset.id] = asset.copy(sha256 = "c".repeat(64))
        mustReject { f.service.apply(draft) { true } }
    }
    @Test fun `生成期间追加交流不使原范围失效并且追加原文保留`() = runTest {
        val f = Fixture(); f.init()
        val draft = f.draft()
        f.pair(5)
        f.service.apply(draft) { true }
        assertTrue(ContextPlanner(f.deps).prepare("c").prompt.messages[1].content.contains("用户5"))
    }
    @Test fun `早期原文变化会拒绝草稿并让已启用摘要回退原文装配`() = runTest {
        val f = Fixture(); f.init()
        val draft = f.draft()
        f.store.messages[0] = f.store.messages[0].copy(text = "原文被修改")
        mustReject { f.service.apply(draft) { true } }
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)
        f.store.messages[0] = f.store.messages[0].copy(text = "用户1")
        f.service.apply(draft) { true }
        f.store.messages[0] = f.store.messages[0].copy(text = "又变了")
        val plan = ContextPlanner(f.deps).prepare("c")
        assertTrue(plan.summaryDisabledReason.contains("来源已变化"))
        assertTrue(plan.prompt.messages[1].content.contains("又变了"))
        assertFalse(plan.prompt.messages[1].content.contains("周六计划"))
    }
    @Test fun `切换人物归属或编辑共享卡后旧草稿不能应用`() = runTest {
        val f = Fixture(); f.init(); val draft = f.draft()
        f.store.conversations["c"] = f.store.getConversation("c")!!.copy(personaId = "other")
        mustReject { f.service.apply(draft) { true } }
        f.store.conversations["c"] = f.store.getConversation("c")!!.copy(personaId = "default")
        f.store.people = listOf(Persona("default", "小默", identity = "新设定"))
        mustReject { f.service.apply(draft) { true } }
    }
    @Test fun `父版本改变会拒绝旧草稿空闲检查同时保护应用和恢复`() = runTest {
        val f = Fixture(); f.init(); val first = f.draft(); val second = f.draft(2)
        mustReject { f.service.apply(first) { false } }
        f.service.apply(second) { true }
        mustReject { f.service.apply(first) { true } }
        mustReject { f.service.restore("c", second.id) { false } }
        assertEquals(second.id, f.store.getConversation("c")!!.activeContextRevisionId)
    }
    @Test fun `系统消息模式事实和已出口的打断内容进入材料未出口的不进入`() = runTest {
        val f = Fixture()
        f.store.upsertConversation(Conversation("c", personaId = "default"))
        f.store.appendMessage(Message(conversationId = "c", role = MessageRole.SYSTEM, text = "【系统消息】还没出发"))
        f.pair(1, mode = ChatMode.SCENE, delivery = DeliveryState.INTERRUPTED)
        f.store.appendEvent(Event(conversationId = "c", kind = EventKind.SAY, text = "没说出口", turnId = f.store.messages.last().turnId,
            mode = ChatMode.SCENE, delivery = DeliveryState.CANCELED))
        f.pair(2); f.pair(3)
        val preview = f.preview()
        assertTrue(preview.chunks.single().text.contains("还没出发"))
        assertTrue(preview.chunks.single().text.contains("scene"))
        assertTrue(preview.chunks.single().text.contains("回复1"))
        assertFalse(preview.chunks.single().text.contains("没说出口"))
        assertFalse(preview.chunks.single().text.contains("←"))
    }
    @Test fun `模式切换后可应用原草稿新增模式事实仍保留在原文`() = runTest {
        val f = Fixture(); f.init(); val draft = f.draft()
        f.store.changeMode("c", ChatMode.SCENE, f.now)
        f.store.appendMessage(Message(conversationId = "c", role = MessageRole.SYSTEM, text = "—— 见面了 ——"))
        f.service.apply(draft) { true }
        assertTrue(ContextPlanner(f.deps).prepare("c").prompt.messages[1].content.contains("见面了"))
    }
    @Test fun `旧摘要加新增原文保留手工编辑而原文重摘要不自动继承`() = runTest {
        val f = Fixture(); f.init()
        val edited = f.service.editedDraft(f.draft(), listOf("手工约定：周六茶馆"))
        f.service.apply(edited) { true }
        val incremental = f.preview(2, CompressionMethod.INCREMENTAL)
        assertTrue(incremental.chunks.single().text.contains("手工约定"))
        assertFalse(incremental.chunks.single().text.contains("用户1"))
        assertTrue(incremental.chunks.single().text.contains("用户2"))
        val original = f.preview(2, CompressionMethod.ORIGINAL)
        assertTrue(original.chunks.single().text.contains("用户1"))
        assertFalse(original.chunks.single().text.contains("手工约定"))
    }
    @Test fun `多段方式不丢旧段不重叠不遗漏且更早切点仅能重新摘要`() = runTest {
        val f = Fixture(); f.init()
        val first = f.draft(2); f.service.apply(first) { true }
        mustReject { f.preview(1, CompressionMethod.SEGMENTED) }
        assertTrue(f.preview(1, CompressionMethod.ORIGINAL).chunks.isNotEmpty())
        val next = f.draft(3, CompressionMethod.SEGMENTED)
        assertEquals(first.segments.first().text, next.segments.first().text)
        assertEquals(first.source.messages.last().id, next.segments.first().toMessageId)
        validateSegments(next)
        f.service.apply(next) { true }
        assertEquals(2, f.store.revisions.size)
        assertEquals(first.segments, f.store.contextRevision(first.id)!!.segments)
        assertEquals(2, f.store.contextRevision(next.id)!!.segments.size)
    }
    @Test fun `超长范围按合法交流分块再合并每次调用均在输入预算内记账`() = runTest {
        val f = Fixture(); f.init(size = 350)
        val preview = f.preview(3, budget = 1000)
        assertTrue(preview.chunks.size > 1)
        val draft = f.service.generate(preview)
        assertEquals(1, draft.segments.size)
        validateSegments(draft)
        assertTrue(f.requests.all { Estimate.promptTokens(it.messages) <= preview.budget })
        assertTrue(f.store.calls.values.all { it.kind == CallKind.SUMMARY && it.status == CallStatus.SUCCESS && it.operationId == draft.id })
        assertTrue(f.requests.size in preview.minimumCalls..preview.maximumCalls)
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)
    }
    @Test fun `摘要预览后跨峰谷不误报配置变化且每段按开始时间冻结价格`() = runTest {
        lateinit var f: Fixture
        f = Fixture { emit(LlmDelta.Text("摘要")); f.now += 1000 }
        f.init(size = 350)
        val boundary = f.now + 1500
        f.timedPrice = { at -> if (at < boundary) ModelPrice(1.0, 2.0) else ModelPrice(3.0, 6.0) }
        val preview = f.preview(3, budget = 1000)
        f.now += 1000
        f.service.generate(preview)
        val calls = f.store.calls.values.toList()
        assertTrue(calls.size > 1)
        assertEquals(1.0, calls.first().price.inputPerM, 0.0)
        assertTrue(calls.drop(1).all { it.price.inputPerM == 3.0 })
    }
    @Test fun `非空准备快照的多段压缩按各次真实开始时间选冻结规则价格`() = runTest {
        lateinit var f: Fixture
        var editedDuringFirstCall = false
        f = Fixture { request ->
            if (!editedDuringFirstCall) {
                editedDuringFirstCall = true
                f.config = f.config.copy(baseUrl = "https://changed.example/v1", model = "changed-model")
                f.timedPrice = { ModelPrice(99.0, 100.0) }
                f.priceRuleRevision++
            }
            assertEquals("global", request.model)
            assertEquals("https://example.invalid", request.endpoint?.baseUrl)
            emit(LlmDelta.Text("摘要")); f.now += 2000
        }
        f.usePreparedCall = true
        val boundary = f.now + 2500
        f.timedPrice = { at -> if (at < boundary) ModelPrice(1.0, 2.0) else ModelPrice(8.0, 9.0) }
        f.init(pairs = 10, size = 350)
        val preview = f.preview(pairNumber = 9, method = CompressionMethod.SEGMENTED, budget = 1000)
        assertNotNull(preview.preparedCall)
        assertTrue(preview.chunks.size > 1)
        val draft = f.service.generate(preview)
        val calls = f.store.calls.values.sortedBy { it.startedAt }
        assertEquals(preview.chunks.size, calls.size)
        assertEquals(1.0, calls.first().price.inputPerM, 0.0)
        assertTrue(calls.any { it.startedAt >= boundary && it.price.inputPerM == 8.0 })
        assertEquals(calls.size, calls.map { it.startedAt }.distinct().size)
        assertEquals("DRAFT", draft.state)
    }
    @Test fun `CompressionService真实ModelAware准备器跨峰时段逐次冻结绑定规则`() = runTest {
        lateinit var f: Fixture
        f = Fixture {
            emit(LlmDelta.Text("摘要"))
            f.now += 60_000
        }
        val model = RegistryModel("model-id", "provider", "模型", "model",
            thinkingControl = ThinkingControl.ENABLE_AND_DISABLE.name,
            capabilitySource = CapabilitySource.USER_CONFIRMED.name,
            thinkingDialectOverride = ThinkingParameterDialect.OPENAI_REASONING_EFFORT.name)
        val entry = ModelPriceEntry("model", PriceRates(1.0, 2.0), peak = PriceRates(8.0, 9.0),
            peakEnabled = true, window = PeakWindow(0, 60))
        val document = ModelRegistryDocument(
            providers = listOf(RegistryProvider("provider", "P", "https://example.invalid/v1", "slot",
                legacyPricing = LegacyProviderPricing(priceInput = 77.0, priceOutput = 88.0, priceConfigured = true),
                pricing = ProviderPricing(bindings = mapOf(model.id to BoundModelPrice("model", entry))))),
            models = listOf(model),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference("provider", model.id)))
        f.preparedCallFactory = { _, use ->
            val result = ModelAwareCallPreparation.prepare(document, use,
                mapOf("provider" to CredentialAvailability.AVAILABLE), mapOf("slot" to "synthetic-key"),
                f.config, f.now, f.deps.pricing)
            assertTrue("${result}", result is ModelCallPreparation.Ready)
            (result as ModelCallPreparation.Ready).call.toAgentCall(f.config)
        }
        f.usePreparedCall = true
        f.now = java.time.Instant.parse("2026-10-24T15:59:30Z").toEpochMilli()
        f.init(pairs = 10, size = 350)
        val preview = f.preview(pairNumber = 9, method = CompressionMethod.SEGMENTED, budget = 1000)
        assertTrue(preview.chunks.size > 1)
        val draft = f.service.generate(preview)
        val calls = f.store.calls.values.sortedBy { it.startedAt }
        assertEquals(preview.chunks.size, calls.size)
        assertEquals(1.0, calls.first().price.inputPerM, 0.0)
        assertTrue(calls.drop(1).any { it.price.inputPerM == 8.0 })
        assertTrue(calls.all { it.model == "model" })
        assertEquals("DRAFT", draft.state)
    }
    @Test fun `准备快照价格规则在预览后改变会在请求前拒绝`() = runTest {
        val f = Fixture(); f.usePreparedCall = true; f.init()
        val preview = f.preview()
        f.priceRuleRevision++
        mustReject { f.service.generate(preview) }
        assertTrue(f.requests.isEmpty())
        assertTrue(f.store.calls.isEmpty())
    }
    @Test fun `奇数分块合并保留落单摘要不重复调用或超过费用预览上限`() = runTest {
        val f = Fixture { emit(LlmDelta.Text("摘".repeat(350))) }; f.init(pairs = 6, size = 350)
        val preview = f.preview(5, budget = 1000)
        assertEquals(3, preview.chunks.size)
        val draft = f.service.generate(preview)
        assertEquals(5, f.requests.size)
        assertEquals(1, draft.segments.size)
        assertEquals(preview.maximumCalls, f.requests.size)
    }
    @Test fun `单段超过预算时预览拒绝而不暗中拆开或调用模型`() = runTest {
        val f = Fixture(); f.init(size = 2000)
        mustReject { f.preview(budget = 1000) }
        assertTrue(f.requests.isEmpty()); assertTrue(f.store.calls.isEmpty())
    }
    @Test fun `分块第二次失败不应用部分摘要但成功和失败调用都保留`() = runTest {
        var count = 0
        val f = Fixture { count++; if (count == 2) throw LlmException(LlmError.Failed(LlmErrorKind.CONNECT, "断网"))
            emit(LlmDelta.Text("部分摘要")) }
        f.init(size = 350)
        try { f.service.generate(f.preview(3, budget = 1000)); fail("必须失败") } catch (_: LlmException) { }
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)
        assertTrue(f.store.revisions.isEmpty())
        assertEquals(listOf(CallStatus.SUCCESS, CallStatus.FAILED), f.store.calls.values.map { it.status })
    }
    @Test fun `取消部分摘要停止后续调用保留尝试且原版本不变`() = runTest {
        val f = Fixture { emit(LlmDelta.Text("部分")); awaitCancellation() }
        f.init(); val preview = f.preview()
        val job = launch { f.service.generate(preview) }
        runCurrent(); job.cancelAndJoin()
        val call = f.store.calls.values.single()
        assertEquals(CallKind.SUMMARY, call.kind); assertEquals(CallStatus.CANCELED, call.status)
        assertTrue(call.outputIncomplete); assertEquals(TokenSource.ESTIMATED, call.output.source)
        assertNull(f.store.getConversation("c")!!.activeContextRevisionId)
        assertTrue(f.store.revisions.isEmpty())
    }
    @Test fun `摘要统计缺字段保留真实零并冻结有效会话模型与单价`() = runTest {
        val f = Fixture { emit(LlmDelta.Text("摘要")); emit(LlmDelta.Finished(promptTokens = 100, cachedTokens = 0)) }
        f.init(); f.store.conversations["c"] = f.store.getConversation("c")!!.copy(modelOverride = "own")
        val draft = f.draft(); f.price = ModelPrice(100.0, 200.0)
        val call = f.store.calls.values.single()
        assertEquals("own", call.model); assertEquals(1.0, call.price.inputPerM, 0.0)
        assertEquals(TokenAmount(0, TokenSource.RETURNED), call.cached)
        assertEquals(TokenSource.ESTIMATED, call.output.source)
        assertEquals(draft.id, call.operationId)
        assertEquals(f.config.baseUrl, f.requests.single().endpoint!!.baseUrl)
        assertEquals(f.config.apiKey, f.requests.single().endpoint!!.apiKey)
    }
    @Test fun `预览不请求模型编辑草稿只影响候选输入且费用仍来自调用记录`() = runTest {
        val f = Fixture(); f.init()
        val preview = f.preview(); assertTrue(f.requests.isEmpty())
        val draft = f.service.generate(preview)
        val before = ContextPlanner(f.deps).prepare("c")
        val edited = f.service.editedDraft(draft, listOf("手工摘要"))
        f.service.afterTokens(edited)
        assertEquals(before.prompt.messages, ContextPlanner(f.deps).prepare("c").prompt.messages)
        assertTrue(edited.manuallyEdited)
        assertEquals(1, f.requests.size)
    }
    @Test fun `摘要目标字数不能误作包含推理的接口输出上限`() = runTest {
        val f = Fixture { request ->
            emit(LlmDelta.Reasoning("事实核对"))
            if (request.maxTokens == 0) emit(LlmDelta.Text("计划去茶馆，尚未预约"))
            emit(LlmDelta.Finished(503, 1712, 0, 1712))
        }
        f.init()
        assertEquals("DRAFT", f.draft().state)
        assertEquals(0, f.requests.single().maxTokens)
        val limited = Fixture(); limited.init(); limited.config = limited.config.copy(maxTokens = 4096)
        limited.draft()
        assertEquals(4096, limited.requests.single().maxTokens)
    }
    @Test fun `损坏覆盖或误指向草稿时正式请求回到原文不丢历史`() = runTest {
        val f = Fixture(); f.init()
        val draft = f.draft()
        f.store.conversations["c"] = f.store.getConversation("c")!!.copy(activeContextRevisionId = draft.id)
        assertTrue(ContextPlanner(f.deps).prepare("c").prompt.messages[1].content.contains("用户1"))
        f.store.revisions[draft.id] = draft.copy(state = "COMPLETE", segments = draft.segments.map { it.copy(sourceChecksum = "bad") })
        val fallback = ContextPlanner(f.deps).prepare("c")
        assertEquals(8, fallback.history.size)
        assertTrue(fallback.summaryDisabledReason.contains("版本不可用"))
        assertEquals(draft.id, f.store.getConversation("c")!!.activeContextRevisionId)
    }
    @Test fun `预览之后配置或价格变化必须重新预览且不产生调用`() = runTest {
        val f = Fixture(); f.init()
        val old = f.preview()
        f.price = ModelPrice(100.0, 200.0)
        mustReject { f.service.generate(old) }
        val next = f.preview()
        f.config = f.config.copy(model = "changed")
        mustReject { f.service.generate(next) }
        assertTrue(f.requests.isEmpty()); assertTrue(f.store.calls.isEmpty())
    }
    @Test fun `带围栏或空白的事件脚本不是摘要失败不留下草稿`() = runTest {
        val f = Fixture { emit(LlmDelta.Text("```json\n{ \"events\": [] }\n```")) }; f.init()
        mustReject { f.service.generate(f.preview()) }
        assertEquals(CallStatus.FAILED, f.store.calls.values.single().status)
        assertTrue(f.store.revisions.isEmpty())
    }
    @Test fun `有限历史窗口仍明确提示且不导致摘要覆盖与原文重复注入`() = runTest {
        val f = Fixture(); f.init(); f.config = f.config.copy(historyLimit = 2)
        val draft = f.draft(); f.service.apply(draft) { true }
        val plan = ContextPlanner(f.deps).prepare("c")
        assertTrue(plan.historyLimited); assertEquals(2, plan.history.size)
        assertFalse(plan.prompt.messages[1].content.contains("用户1"))
        assertTrue(plan.prompt.messages[1].content.contains("周六计划"))
    }
}
