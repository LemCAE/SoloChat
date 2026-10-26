package app.solochat.data.pricing

import app.solochat.core.model.*
import app.solochat.data.*
import org.junit.Assert.*
import org.junit.Test

class ProviderPricingTest {
    @Test fun `旧v2默认revision零且pricing null与空配置区分`() {
        val document = document()
        val encoded = ModelRegistryFormat.encode(document).replace("\"revision\":0,", "")
        val decoded = ModelRegistryFormat.decode(encoded)
        assertEquals(0L, decoded.revision)
        assertNull(decoded.providers.single().pricing)
        assertEquals(ProviderPricing(), ModelRegistryFormat.decode(ModelRegistryFormat.encode(document.copy(
            providers = listOf(document.providers.single().copy(pricing = ProviderPricing()))))).providers.single().pricing)
    }

    @Test fun `完整规则来源绑定身份缓存null和零均往返`() {
        val entry = ModelPriceEntry("model-a", PriceRates(0.0, 0.0, 0.0),
            peak = PriceRates(1.0, 2.0, 0.0), peakEnabled = true,
            window = PeakWindow(60, 120), note = "n", source = "s", provider = "p",
            schedule = PriceSchedule(listOf(PriceTimeRange(60, 120)), weekdays = listOf(1, 2),
                calendar = PriceCalendar("2026-01-01", "2026-12-31", source = "official", confirmed = true)),
            tiers = listOf(PriceTier(10, PriceRates(0.0, 0.0, null), PriceRates(1.0, 2.0, 0.0)),
                PriceTier(null, PriceRates(3.0, 4.0, null), PriceRates(5.0, 6.0, 0.0))))
        val pricing = ProviderPricing(bindings = mapOf("model-a" to BoundModelPrice("Exact-Case", entry,
            PriceOrigin(PriceOriginKind.REFERENCE, channel = "channel", checkedAt = "2026-10-25", url = "https://example.invalid"),
            PriceConditions("OFF", "a".repeat(64)))))
        val doc = document("Exact-Case").copy(providers = listOf(document().providers.single().copy(pricing = pricing)))
        assertEquals(doc, ModelRegistryFormat.decode(ModelRegistryFormat.encode(doc)))
        val bound = pricing.bindings.getValue("model-a")
        assertNull(bound.rule.tiers.first().standard.cached)
        assertEquals(0.0, bound.rule.tiers.first().peak!!.cached!!, 0.0)
    }

    @Test fun `错误绑定和非法价格配置拒绝`() {
        val doc = document()
        val invalid = ProviderPricing(bindings = mapOf("missing" to BoundModelPrice("model-a",
            ModelPriceEntry("model-a", PriceRates(Double.NaN, 1.0)))))
        assertTrue(runCatching { doc.copy(providers = listOf(doc.providers.single().copy(pricing = invalid))).validate() }.isFailure)
        assertTrue(runCatching { doc.copy(providers = listOf(doc.providers.single().copy(pricing = ProviderPricing(version = 2)))).validate() }.isFailure)
    }

    private fun document(requestId: String = "model-a") = ModelRegistryDocument(
        providers = listOf(RegistryProvider("provider-a", "A", "https://a.example/v1", "slot-a")),
        models = listOf(RegistryModel("model-a", "provider-a", "A", requestId)),
        globalDefault = RegistrySelection.explicit(ModelReference("provider-a", "model-a")))
}
