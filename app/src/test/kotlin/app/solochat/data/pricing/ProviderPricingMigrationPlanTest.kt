package app.solochat.data.pricing

import app.solochat.core.agent.ChatConfig
import app.solochat.core.model.*
import app.solochat.data.*
import org.junit.Assert.*
import org.junit.Test

class ProviderPricingMigrationPlanTest {
    @Test fun `计划稳定只读并为禁用同名快照模型复制完整规则`() {
        val rule = ModelPriceEntry("model-x", PriceRates(1.0, 2.0, null), peak = PriceRates(3.0, 4.0, 0.0),
            peakEnabled = true, window = PeakWindow(100, 200),
            schedule = PriceSchedule(listOf(PriceTimeRange(100, 200)), calendar = PriceCalendar(
                "2026-01-01", "2026-12-31", source = "checked", confirmed = true)),
            tiers = listOf(PriceTier(null, PriceRates(1.0, 2.0, null), PriceRates(3.0, 4.0, 0.0))))
        val legacy = LegacyProviderPricing(priceCatalog = PriceCatalog("active", listOf(PriceTable("active", "active", listOf(
            rule, ModelPriceEntry("other", PriceRates(9.0, 9.0)))),
            PriceTable("inactive", "inactive", listOf(ModelPriceEntry("inactive-rule", PriceRates(8.0, 8.0)))))))
        val doc = document(legacy, listOf(
            RegistryModel("m1", "p", "one", "Model-X", enabled = false, legacyPriceEligible = false),
            RegistryModel("m2", "p", "two", "model-x", legacyPriceEligible = false),
            RegistryModel("m3", "p", "new", "new-model", legacyPriceEligible = false)))
        val bundled = PriceCatalog("bundled", listOf(PriceTable("bundled", "bundle", emptyList())))
        val first = ProviderPricingMigrationPlan.create(doc, "p", bundled)
        val second = ProviderPricingMigrationPlan.create(doc, "p", bundled)
        assertEquals(first, second)
        assertEquals(setOf("m1", "m2"), first.bindings.keys)
        assertEquals("Model-X", first.bindings.getValue("m1").requestModelId)
        assertEquals(rule, first.bindings.getValue("m1").rule)
        assertEquals(listOf("m3"), first.unknown.map { it.modelId })
        assertEquals("other", first.unassigned.single().requestModelId)
        assertEquals(legacy, first.archive.legacy)
        assertNull(doc.providers.single().pricing)
    }

    @Test fun `关闭表和无资格不继承兜底而零价及缓存零保留`() {
        val legacy = LegacyProviderPricing(useOfficialPrice = false, priceInput = 0.0, priceOutput = 0.0,
            priceCached = 99.0, priceConfigured = true, cachedPriceConfigured = true,
            priceCatalog = PriceCatalog("active", listOf(PriceTable("active", "old", listOf(
                ModelPriceEntry("known", PriceRates(7.0, 8.0)))))))
        val doc = document(legacy, listOf(
            RegistryModel("eligible", "p", "eligible", "eligible", legacyPriceEligible = true),
            RegistryModel("new", "p", "new", "new")))
        val plan = ProviderPricingMigrationPlan.create(doc, "p", PriceCatalog("b", listOf(PriceTable("b", "bundle", emptyList()))))
        val bound = plan.bindings.getValue("eligible")
        assertEquals(0.0, bound.rule.standard.input, 0.0)
        assertEquals(0.0, bound.rule.standard.output, 0.0)
        assertEquals(99.0, bound.rule.standard.cached!!, 0.0)
        assertEquals(listOf("new"), plan.unknown.map { it.modelId })
        assertTrue(plan.unassigned.isEmpty())
        assertEquals(99.0, plan.archive.legacy.priceCached, 0.0)
    }

    @Test fun `旧新规则经R4对照峰谷阶梯日历和未知用量`() {
        val entry = ModelPriceEntry("model-a", PriceRates(1.0, 2.0, null), peak = PriceRates(4.0, 5.0, 0.0),
            peakEnabled = true, window = PeakWindow(0, 60),
            schedule = PriceSchedule(listOf(PriceTimeRange(0, 60)), calendar = PriceCalendar(
                "2026-01-01", "2026-12-31", source = "checked", confirmed = true)),
            tiers = listOf(PriceTier(100, PriceRates(1.0, 2.0, null), PriceRates(4.0, 5.0, 0.0)),
                PriceTier(null, PriceRates(3.0, 4.0, null), PriceRates(6.0, 7.0, 0.0))))
        val catalog = PriceCatalog("active", listOf(PriceTable("active", "table", listOf(entry))))
        val doc = document(LegacyProviderPricing(priceCatalog = catalog), listOf(
            RegistryModel("m", "p", "m", "model-a", legacyPriceEligible = true)))
        val plan = ProviderPricingMigrationPlan.create(doc, "p", PriceCatalog("b", listOf(PriceTable("b", "bundle"))))
        val migrated = plan.bindings.getValue("m").rule
        val oldStandard = PricingRules.resolveCatalog(catalog, "model-a", ChatConfig(useOfficialPrice = true, priceCatalog = catalog),
            java.time.Instant.parse("2026-10-24T18:00:00Z").toEpochMilli())
        val newStandard = resolvePriceEntry(PriceTable("bound", "table", listOf(migrated)), migrated,
            java.time.Instant.parse("2026-10-24T18:00:00Z").toEpochMilli())
        assertEquals(oldStandard.known, newStandard.known)
        assertEquals(oldStandard.inputPerM, newStandard.inputPerM, 0.0)
        assertEquals(oldStandard.cachedPriceKnown, newStandard.cachedPriceKnown)
        assertFalse(newStandard.forInput(TokenAmount(null, TokenSource.ESTIMATED)).known)
        assertTrue(newStandard.forInput(TokenAmount(100, TokenSource.RETURNED)).known)
    }

    @Test fun `null随包源空目录和未配置兜底保持未知语义且指纹区分`() {
        val legacy = LegacyProviderPricing(priceInput = 12.0, priceOutput = 14.0,
            priceCached = 99.0, priceConfigured = true, cachedPriceConfigured = false)
        val doc = document(legacy, listOf(
            RegistryModel("eligible", "p", "eligible", "eligible", legacyPriceEligible = true),
            RegistryModel("unconfigured", "p", "unconfigured", "unconfigured", legacyPriceEligible = true)))
        val emptyBundled = PriceCatalog("bundle", listOf(PriceTable("bundle", "empty")))
        val withNull = ProviderPricingMigrationPlan.create(doc, "p", null)
        val withEmpty = ProviderPricingMigrationPlan.create(doc, "p", emptyBundled)
        assertNotEquals(withNull.sourceFingerprint, withEmpty.sourceFingerprint)
        assertEquals(setOf("eligible", "unconfigured"), withNull.bindings.keys)
        assertEquals(12.0, withNull.bindings.getValue("eligible").rule.standard.input, 0.0)
        assertNull(withNull.bindings.getValue("eligible").rule.standard.cached)

        val notConfigured = doc.copy(providers = listOf(doc.providers.single().copy(
            legacyPricing = legacy.copy(priceConfigured = false, priceInput = 0.0, priceOutput = 0.0))))
        val unknown = ProviderPricingMigrationPlan.create(notConfigured, "p", null)
        assertTrue(unknown.bindings.isEmpty())
        assertEquals(setOf("eligible", "unconfigured"), unknown.unknown.map { it.modelId }.toSet())
    }

    private fun document(legacy: LegacyProviderPricing, models: List<RegistryModel>) = ModelRegistryDocument(
        providers = listOf(RegistryProvider("p", "P", "https://p.example/v1", "slot", legacyPricing = legacy)),
        models = models, globalDefault = RegistrySelection.explicit(ModelReference("p", models.first().id)))
}
