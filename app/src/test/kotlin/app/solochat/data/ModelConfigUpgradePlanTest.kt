package app.solochat.data

import app.solochat.core.model.ModelPriceEntry
import app.solochat.core.model.PriceCatalog
import app.solochat.core.model.PriceRates
import app.solochat.core.model.PriceTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigUpgradePlanTest {
    private fun source(
        settings: Snapshot = Snapshot(
            apiProfileId = "provider-a",
            apiProfileName = "同品牌接口",
            apiKeyId = "slot-a",
            apiProfiles = listOf(
                ApiProfile("provider-a", "同品牌接口", "https://a.example/v1", model = "qwen-plus", modelList = listOf("qwen-plus", "directory-only"), keyId = "slot-a"),
                ApiProfile("provider-b", "同品牌接口", "https://b.example/v1", model = "qwen-plus", modelList = listOf("qwen-plus"), keyId = "slot-b")
            ),
            model = "qwen-plus"
        ),
        conversations: List<LegacyConversationModelOverride> = emptyList()
    ) = ModelConfigUpgradeSource(settings, conversations)

    private fun preparation(source: ModelConfigUpgradeSource): ModelConfigPreparationRecord {
        var id = 0
        return ModelConfigUpgradePlan.prepare(source) { "stable-${++id}" }
    }

    @Test fun `旧提供商和Key槽位逐个保留同名品牌不合并`() {
        val source = source()
        val plan = ModelConfigUpgradePlan.build(source, preparation(source))

        assertEquals(listOf("provider-a", "provider-b"), plan.candidate.providers.map { it.id })
        assertEquals(listOf("slot-a", "slot-b"), plan.candidate.providers.map { it.keyId })
        assertEquals(2, plan.candidate.models.count { it.requestModelId == "qwen-plus" })
        assertNotEquals(plan.candidate.models.first { it.providerId == "provider-a" }.id,
            plan.candidate.models.first { it.providerId == "provider-b" }.id)
    }

    @Test fun `目录外手填模型和会话覆盖按原请求名保留`() {
        val source = source(conversations = listOf(
            LegacyConversationModelOverride("conversation-a", "manual-CaseModel"),
            LegacyConversationModelOverride("conversation-b", null)
        ))
        val plan = ModelConfigUpgradePlan.build(source, preparation(source))
        val manual = plan.candidate.models.single { it.requestModelId == "manual-CaseModel" }

        assertEquals("manual-CaseModel", manual.displayName)
        assertEquals(true, manual.legacyPriceEligible)
        assertEquals("EXPLICIT", plan.conversations.first().selection.state)
        assertEquals("INHERIT", plan.conversations.last().selection.state)
        assertEquals("provider-a", plan.conversations.first().selection.providerId)
    }

    @Test fun `无有效活动接口组的全局与会话引用保留待确认`() {
        val source = source(
            settings = source().settings.copy(apiProfileId = "deleted", model = "qwen-plus"),
            conversations = listOf(LegacyConversationModelOverride("conversation-a", "qwen-plus"))
        )
        val plan = ModelConfigUpgradePlan.build(source, preparation(source))

        assertEquals("NEEDS_CONFIRMATION", plan.candidate.globalDefault.state)
        assertEquals("NEEDS_CONFIRMATION", plan.conversations.single().selection.state)
        assertTrue(plan.issues.any { it.code == "MISSING_ACTIVE_PROVIDER" })
        assertFalse(plan.candidate.models.any { it.providerId == "deleted" })
    }

    @Test fun `旧思考空值OFF与已知档位仅迁移实际使用模型`() {
        val base = source(conversations = listOf(LegacyConversationModelOverride("conversation-a", "manual-CaseModel")))
        val cases = listOf(
            "" to ("AUTO" to null),
            "off" to ("OFF" to null),
            "HIGH" to ("ON" to null)
        )
        cases.forEach { (legacy, expected) ->
            val candidateSource = base.copy(settings = base.settings.copy(thinkingEffort = legacy))
            val plan = ModelConfigUpgradePlan.build(candidateSource, preparation(candidateSource))
            val targeted = plan.candidate.models.filter { it.providerId == "provider-a" && it.requestModelId in setOf("qwen-plus", "manual-CaseModel") }
            assertTrue(targeted.all { it.thinkingMode == expected.first && it.thinkingEffort == expected.second })
            assertEquals("AUTO", plan.candidate.models.single { it.providerId == "provider-a" && it.requestModelId == "directory-only" }.thinkingMode)
            assertEquals("AUTO", plan.candidate.models.single { it.providerId == "provider-b" && it.requestModelId == "qwen-plus" }.thinkingMode)
        }
    }

    @Test fun `未知旧思考保留待处理原因且不伪造强度`() {
        val source = source(settings = source().settings.copy(thinkingEffort = "ULTRA-UNKNOWN"))
        val plan = ModelConfigUpgradePlan.build(source, preparation(source))
        val model = plan.candidate.models.single { it.providerId == "provider-a" && it.requestModelId == "qwen-plus" }

        assertEquals("AUTO", model.thinkingMode)
        assertEquals(null, model.thinkingEffort)
        assertTrue(plan.issues.any { it.code == "UNKNOWN_LEGACY_THINKING" && it.scope == "model:provider-a/qwen-plus" })
    }

    @Test fun `全部旧价格原文与null目录语义保留且新模型默认无兼容资格`() {
        val catalog = PriceCatalog("inactive", listOf(
            PriceTable("inactive", "未生效表", listOf(ModelPriceEntry(
                "qwen-plus", standard = PriceRates(1.25, 2.5, 0.5), peak = PriceRates(3.0, 4.0), peakEnabled = false,
                note = "原备注", source = "原来源", provider = "原提供商"
            )))
        ))
        val profiles = listOf(
            ApiProfile("provider-a", "接口 A", "https://a.example", modelList = listOf("qwen-plus"),
                useOfficialPrice = false, priceCatalog = catalog, priceInput = 0.0, priceOutput = 9.5, priceCached = 1.0,
                priceConfigured = true, cachedPriceConfigured = true, keyId = "slot-a"),
            ApiProfile("provider-b", "接口 B", "https://b.example", modelList = listOf("other"), priceCatalog = null, keyId = "slot-b")
        )
        val source = source(settings = Snapshot(apiProfileId = "provider-a", model = "qwen-plus", apiProfiles = profiles))
        val plan = ModelConfigUpgradePlan.build(source, preparation(source))
        val prices = plan.candidate.providers.associateBy { it.id }

        assertEquals(LegacyProviderPricing.from(profiles[0]), prices.getValue("provider-a").legacyPricing)
        assertEquals(null, prices.getValue("provider-b").legacyPricing.priceCatalog)
        assertTrue(plan.candidate.models.all { it.legacyPriceEligible })
        val newModel = RegistryModel.from(plan.candidate.models.first().toCore(), legacyPriceEligible = false)
        assertFalse(newModel.legacyPriceEligible)
    }

    @Test fun `准备记录映射稳定且源变化后旧草案拒绝`() {
        val original = source()
        var nextId = 0
        val prepared = ModelConfigUpgradePlan.prepare(original) { "id-${++nextId}" }
        val plan = ModelConfigUpgradePlan.build(original, prepared)
        plan.requireCurrent(original.copy(conversations = original.conversations.toList()))

        val changed = original.copy(settings = original.settings.copy(model = "directory-only"))
        assertTrue(runCatching { ModelConfigUpgradePlan.build(changed, prepared) }.isFailure)
        assertTrue(runCatching { plan.requireCurrent(changed) }.isFailure)
        assertEquals(3, nextId)
    }

    @Test fun `版本格式拒绝未知版本重复ID和非JSON密钥字段`() {
        val source = source()
        val plan = ModelConfigUpgradePlan.build(source, preparation(source))
        val encoded = ModelRegistryFormat.encode(plan.candidate)
        assertFalse(encoded.contains("FAKE-SECRET-KEY"))
        assertTrue(runCatching { ModelRegistryFormat.decode(encoded.replace("\"version\":2", "\"version\":99")) }.isFailure)
        assertTrue(runCatching { ModelRegistryFormat.encode(plan.candidate.copy(models = plan.candidate.models + plan.candidate.models.first())) }.isFailure)
    }
}
