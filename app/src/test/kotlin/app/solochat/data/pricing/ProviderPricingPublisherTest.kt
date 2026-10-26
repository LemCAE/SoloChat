package app.solochat.data.pricing

import app.solochat.core.model.*
import app.solochat.data.*
import org.junit.Assert.*
import org.junit.Test

class ProviderPricingPublisherTest {
    @Test fun `改请求身份将整条报价解绑且显示名思考修改保留报价`() {
        val original = readyDocument()
        val renamed = original.copy(models = listOf(original.models.single().copy(displayName = "新名", thinkingMode = "ON")))
        val retained = ReadyModelRegistryMerger.merge(original, renamed)
        assertEquals(original.providers.single().pricing, retained.providers.single().pricing)

        val changed = original.copy(models = listOf(original.models.single().copy(requestModelId = "changed")))
        val merged = ReadyModelRegistryMerger.merge(original, changed)
        val pricing = merged.providers.single().pricing!!
        assertTrue(pricing.bindings.isEmpty())
        assertEquals("request", pricing.unassigned.single().requestModelId)
        assertEquals(UnassignedPriceReason.REQUEST_ID_CHANGED, pricing.unassigned.single().reason)
        assertEquals(merged, ReadyModelRegistryMerger.merge(merged, merged))
    }

    @Test fun `同一请求身份再次解绑会保留第二份更新报价`() {
        val original = readyDocument()
        val changed = original.copy(models = listOf(original.models.single().copy(requestModelId = "other")))
        val first = ReadyModelRegistryMerger.merge(original, changed)
        val rebound = first.copy(models = listOf(first.models.single().copy(requestModelId = "request")))
        val reboundReady = ProviderPricingPublisher.edit(
            ReadyModelRegistryMerger.merge(first, rebound), "provider", first.revision,
            upserts = mapOf("model" to bound("request", 9.0)))
        val second = ReadyModelRegistryMerger.merge(reboundReady,
            reboundReady.copy(models = listOf(reboundReady.models.single().copy(requestModelId = "other"))))
        val orphans = second.providers.single().pricing!!.unassigned
        assertEquals(listOf(1.0, 9.0), orphans.map { it.rule!!.standard.input })
        assertEquals(2, orphans.map { it.id }.distinct().size)
    }

    @Test fun `模型草稿不能带入报价且未整理身份变化要求先整理`() {
        val current = legacyDocument()
        val injected = current.copy(providers = listOf(current.providers.single().copy(
            pricing = ProviderPricing(bindings = mapOf("model" to bound("request", 99.0))))))
        val merged = ReadyModelRegistryMerger.merge(current, injected)
        assertNull(merged.providers.single().pricing)
        assertTrue(runCatching { ReadyModelRegistryMerger.merge(current,
            current.copy(models = listOf(current.models.single().copy(requestModelId = "other")))) }.isFailure)
    }

    @Test fun `迁移计划提交重试幂等且过期预览拒绝`() {
        val current = legacyDocument(legacyPricing = LegacyProviderPricing(
            priceInput = 2.0, priceOutput = 3.0, priceConfigured = true))
        val bundled = PriceCatalog("bundle", listOf(PriceTable("bundle", "empty")))
        val plan = ProviderPricingMigrationPlan.create(current, "provider", bundled)
        val migrated = ProviderPricingPublisher.migrate(current, plan, bundled)
        assertEquals(1L, migrated.revision)
        assertEquals(migrated, ProviderPricingPublisher.migrate(migrated, plan, bundled))
        val changed = current.copy(legacyPricingMarker = 1)
        assertTrue(runCatching { ProviderPricingPublisher.migrate(changed, plan, bundled) }.isFailure)
    }

    @Test fun `价格编辑只增revision且空规则与显式零报价不同`() {
        val current = readyDocument().copy(revision = 4)
        val updated = ProviderPricingPublisher.edit(current, "provider", 4,
            upserts = mapOf("model" to bound("request", 0.0)))
        assertEquals(5L, updated.revision)
        assertTrue(updated.providers.single().pricing!!.bindings.getValue("model").rule.standard.input == 0.0)
        assertTrue(runCatching { ProviderPricingPublisher.edit(current.copy(revision = 5), "provider", 4) }.isFailure)
        val cleared = ProviderPricingPublisher.edit(updated, "provider", 5, removes = setOf("model"))
        assertFalse(cleared.providers.single().pricing!!.bindings.containsKey("model"))
    }

    @Test fun `导入存档随原子价格发布保存但不生成绑定`() {
        val current = readyDocument().copy(revision = 6)
        val archive = PriceExchangeArchive("cross-install", catalogPresent = true,
            tables = listOf(PriceExchangeTable("file-table", "inactive", false,
                listOf(ModelPriceEntry("old", PriceRates(4.0, 5.0))))))
        val updated = ProviderPricingPublisher.edit(current, "provider", 6, importedArchives = listOf(archive))
        val pricing = updated.providers.single().pricing!!
        assertEquals(listOf(archive), pricing.importedArchives)
        assertEquals(current.providers.single().pricing!!.bindings, pricing.bindings)
        assertEquals(7L, updated.revision)
    }

    private fun bound(id: String, input: Double) = BoundModelPrice(id,
        ModelPriceEntry("request", PriceRates(input, 1.0)))

    private fun readyDocument() = legacyDocument().copy(providers = listOf(legacyDocument().providers.single().copy(
        pricing = ProviderPricing(bindings = mapOf("model" to bound("request", 1.0))))))

    private fun legacyDocument(legacyPricing: LegacyProviderPricing = LegacyProviderPricing()) = ModelRegistryDocument(
        providers = listOf(RegistryProvider("provider", "P", "https://p.example/v1", "slot", legacyPricing = legacyPricing)),
        models = listOf(RegistryModel("model", "provider", "M", "request")),
        globalDefault = RegistrySelection.explicit(ModelReference("provider", "model")))
}

private fun ModelRegistryDocument.copy(legacyPricingMarker: Int): ModelRegistryDocument = copy(
    providers = providers.map { it.copy(legacyPricing = it.legacyPricing.copy(priceInput = legacyPricingMarker.toDouble())) })
