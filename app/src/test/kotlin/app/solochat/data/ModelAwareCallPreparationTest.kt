package app.solochat.data

import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.llm.ApiMessage
import app.solochat.core.model.*
import app.solochat.data.pricing.PricingRules
import app.solochat.data.pricing.BoundModelPrice
import app.solochat.data.pricing.PriceConditions
import app.solochat.data.pricing.ProviderPricing
import app.solochat.data.pricing.ProviderPricingResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelAwareCallPreparationTest {
    @Test fun `能力未知时聊天 AUTO 可准备且同一快照冻结模型 endpoint 和时间价格`() {
        var priceAt = -1L
        var priceModel = ""
        val result = ModelAwareCallPreparation.prepare(
            document(modelId = "custom-model", baseUrl = "https://proxy.example/v1"),
            ModelUse.CHAT,
            mapOf("provider" to CredentialAvailability.AVAILABLE),
            mapOf("slot" to "selected-secret"),
            behavior = ChatConfig(baseUrl = "https://old.example/v1", apiKey = "stale-secret", model = "old-model",
                temperature = 0.65, maxTokens = 321, timeoutMs = 4567),
            priceAt = 9876L,
            pricing = object : PriceResolver {
                override fun resolve(model: String, config: ChatConfig): ModelPrice = error("必须使用带调用时间的解析")
                override fun resolve(model: String, config: ChatConfig, at: Long): ModelPrice {
                    priceAt = at
                    priceModel = model
                    return ModelPrice(inputPerM = 3.0, outputPerM = 4.0, source = "test", known = true)
                }
            }
        ) as ModelCallPreparation.Ready

        val prepared = result.call
        assertEquals(9876L, priceAt)
        assertEquals("custom-model", priceModel)
        assertEquals("provider", prepared.configSnapshot.providerId)
        assertEquals("model", prepared.configSnapshot.modelId)
        assertEquals(emptyList<ThinkingParameterSnapshot>(), prepared.configSnapshot.actualThinkingParameters)
        val request = prepared.request(listOf(ApiMessage("user", "测试")))
        assertEquals("custom-model", request.model)
        assertEquals("https://proxy.example/v1", request.endpoint?.baseUrl)
        assertEquals("selected-secret", request.endpoint?.apiKey)
        assertEquals(0.65, request.temperature, 0.0)
        assertEquals(321, request.maxTokens)
        assertFalse(prepared.toString().contains("selected-secret"))
        assertFalse(request.toString().contains("selected-secret"))
    }

    @Test fun `未知显式能力在产生请求或记账前失败且压缩不继承聊天ON`() {
        val custom = document(modelId = "custom-model", baseUrl = "https://proxy.example/v1",
            thinkingMode = ThinkingMode.ON.name)
        val unknown = ModelAwareCallPreparation.prepare(
            custom, ModelUse.CHAT, available, keys, ChatConfig(), 1L, prices
        ) as ModelCallPreparation.Rejected
        assertEquals(CallPreparationFailure.THINKING_UNSUPPORTED, unknown.reason)
        var claimed = false
        val rejectedClaim = runBlocking {
            ModelAwareCallPreparation.claimAfterPreflight(unknown) { claimed = true; "batch" }
        }
        assertFalse(claimed)
        assertTrue(rejectedClaim is PreparedCallClaim.Rejected)

        val qwen = document(modelId = "qwen3.8-max", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            thinkingMode = ThinkingMode.ON.name, priceEligible = false)
        val compression = ModelAwareCallPreparation.prepare(
            qwen, ModelUse.COMPRESSION, available, keys, ChatConfig(), 2L, prices
        ) as ModelCallPreparation.Ready
        assertEquals(ThinkingMode.OFF, compression.call.configSnapshot.thinking.mode)
        assertEquals(ThinkingSource.COMPRESSION_DEFAULT, compression.call.configSnapshot.thinkingSource)
        assertEquals(listOf(ThinkingParameterSnapshot("enable_thinking", "false")),
            compression.call.configSnapshot.actualThinkingParameters)
        assertEquals(false, compression.call.request(emptyList()).thinkingParameters?.enabled)
        val successfulClaim = runBlocking {
            ModelAwareCallPreparation.claimAfterPreflight(compression) { "claimed-batch" }
        } as PreparedCallClaim.Ready
        assertSame(compression.call, successfulClaim.call)
        assertEquals("claimed-batch", successfulClaim.claimed)
    }

    @Test fun `新模型不继承旧兼容价迁入模型保留原价且均只解析一次`() {
        val configs = mutableListOf<ChatConfig>()
        val resolver = object : PriceResolver {
            override fun resolve(model: String, config: ChatConfig) = ModelPrice()
            override fun resolve(model: String, config: ChatConfig, at: Long): ModelPrice {
                configs += config
                return ModelPrice(source = model, known = config.priceConfigured == true)
            }
        }
        val behavior = ChatConfig(priceInputPerM = 9.0, priceOutputPerM = 10.0, priceConfigured = true)
        val old = ModelAwareCallPreparation.prepare(
            document(modelId = "legacy-request", baseUrl = "https://proxy.example/v1", priceEligible = true),
            ModelUse.CHAT, available, keys, behavior, 10L, resolver
        ) as ModelCallPreparation.Ready
        val new = ModelAwareCallPreparation.prepare(
            document(modelId = "new-request", baseUrl = "https://proxy.example/v1", priceEligible = false),
            ModelUse.CHAT, available, keys, behavior, 11L, resolver
        ) as ModelCallPreparation.Ready

        assertEquals(2, configs.size)
        assertEquals(1.25, configs[0].priceInputPerM, 0.0)
        assertEquals(true, configs[0].priceConfigured)
        assertEquals("unpriced", configs[1].priceCatalog?.selectedTableId)
        assertEquals(0.0, configs[1].priceInputPerM, 0.0)
        assertEquals(false, configs[1].priceConfigured)
        assertEquals("legacy-request", old.call.requestModelId)
        assertEquals("new-request", new.call.requestModelId)
    }

    @Test fun `新模型保留用户明确报价但不套用随包同名价格`() {
        val builtIn = PricingRules.initialCatalog(PricingRules.parseTable(
            """{"models":{"deepseek-v4-pro":{"in":1.0,"out":2.0}}}"""))
        val resolver = object : PriceResolver {
            override fun resolve(model: String, config: ChatConfig) = ModelPrice()
            override fun resolve(model: String, config: ChatConfig, at: Long) = PricingRules.resolveCatalog(
                config.priceCatalog ?: builtIn, model, config, at)
        }
        val explicit = PriceCatalog("user", listOf(PriceTable("user", "自填价格", listOf(
            ModelPriceEntry("deepseek-v4-pro", PriceRates(9.0, 12.0))
        ))))
        val explicitProvider = RegistryProvider("provider", "提供商", "https://proxy.example/v1", "slot",
            legacyPricing = LegacyProviderPricing(priceCatalog = explicit))
        val document = document("deepseek-v4-pro", priceEligible = false).copy(providers = listOf(explicitProvider))

        val explicitlyPriced = ModelAwareCallPreparation.prepare(document, ModelUse.CHAT, available, keys,
            ChatConfig(), 100L, resolver) as ModelCallPreparation.Ready
        val unconfigured = ModelAwareCallPreparation.prepare(
            document("deepseek-v4-pro", priceEligible = false), ModelUse.CHAT, available, keys,
            ChatConfig(), 100L, resolver) as ModelCallPreparation.Ready
        val eligibleLegacy = ModelAwareCallPreparation.prepare(
            document("deepseek-v4-pro", priceEligible = true), ModelUse.CHAT, available, keys,
            ChatConfig(), 100L, resolver) as ModelCallPreparation.Ready

        assertTrue(explicitlyPriced.call.price.known)
        assertEquals(9.0, explicitlyPriced.call.price.inputPerM, 0.0)
        assertFalse(unconfigured.call.price.known)
        assertTrue(eligibleLegacy.call.price.known)
        assertEquals(1.0, eligibleLegacy.call.price.inputPerM, 0.0)
    }

    @Test fun `待确认缺凭据和暂停副本在准备阶段拒绝`() {
        val pending = document(modelId = "custom-model", baseUrl = "https://proxy.example/v1").copy(
            globalDefault = RegistrySelection.needsConfirmation(
                ModelReference("provider", "model"), "恢复来源需确认"
            )
        )
        val unresolved = ModelAwareCallPreparation.prepare(pending, ModelUse.CHAT, available, keys, ChatConfig(), 1L, prices)
        assertEquals(CallPreparationFailure.MODEL_NOT_RESOLVED, (unresolved as ModelCallPreparation.Rejected).reason)

        val missing = ModelAwareCallPreparation.prepare(document(), ModelUse.CHAT,
            mapOf("provider" to CredentialAvailability.MISSING), keys, ChatConfig(), 1L, prices)
        assertEquals(CallPreparationFailure.MODEL_NOT_RESOLVED, (missing as ModelCallPreparation.Rejected).reason)

        val paused = ModelAwareCallPreparation.prepare(document(), ModelUse.CHAT, available, keys, ChatConfig(),
            1L, prices, backupPaused = true)
        assertEquals(CallPreparationFailure.BACKUP_PAUSED, (paused as ModelCallPreparation.Rejected).reason)
    }

    @Test fun `整理后按稳定模型身份精确取价且条件按用途核对不回退旧表`() {
        val base = document(modelId = "Case-Request", priceEligible = true)
        val entry = ModelPriceEntry("case-request", PriceRates(2.0, 3.0, 0.0))
        val provider = base.providers.single().copy(pricing = ProviderPricing(bindings = mapOf(
            "model" to BoundModelPrice("Case-Request", entry, conditions = PriceConditions(ThinkingMode.AUTO.name)))))
        val organized = base.copy(providers = listOf(provider))
        val call = ModelAwareCallPreparation.prepare(organized, ModelUse.CHAT, available, keys,
            ChatConfig(), 1L, prices) as ModelCallPreparation.Ready
        assertTrue(call.call.price.known)
        assertEquals(2.0, call.call.price.inputPerM, 0.0)
        assertEquals("Case-Request", call.call.requestModelId)
        assertFalse(call.call.priceRuleFingerprint.contains("selected-secret"))

        val noBinding = organized.copy(providers = listOf(provider.copy(pricing = ProviderPricing())))
        val unknown = ModelAwareCallPreparation.prepare(noBinding, ModelUse.CHAT, available, keys,
            ChatConfig(), 1L, prices) as ModelCallPreparation.Ready
        assertFalse(unknown.call.price.known)
        assertEquals("未配置模型报价", unknown.call.price.source)

        val mismatch = ProviderPricingResolver.resolve(provider, base.models.single().copy(requestModelId = "different"),
            ModelUse.CHAT, 1L)
        assertFalse(mismatch.known)
    }

    private val available = mapOf("provider" to CredentialAvailability.AVAILABLE)
    private val keys = mapOf("slot" to "selected-secret")
    private val prices = object : PriceResolver {
        override fun resolve(model: String, config: ChatConfig) = ModelPrice()
    }

    private fun document(modelId: String = "custom-model", baseUrl: String = "https://proxy.example/v1",
        thinkingMode: String = ThinkingMode.AUTO.name, priceEligible: Boolean = false) = ModelRegistryDocument(
        providers = listOf(RegistryProvider("provider", "提供商", baseUrl, "slot",
            legacyPricing = LegacyProviderPricing(priceInput = 1.25, priceOutput = 2.5, priceConfigured = true))),
        models = listOf(RegistryModel("model", "provider", "展示名", modelId,
            thinkingMode = thinkingMode, legacyPriceEligible = priceEligible)),
        globalDefault = RegistrySelection.explicit(ModelReference("provider", "model"))
    )
}
