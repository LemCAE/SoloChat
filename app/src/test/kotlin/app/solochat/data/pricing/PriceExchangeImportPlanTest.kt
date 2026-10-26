package app.solochat.data.pricing

import app.solochat.core.model.*
import app.solochat.data.RegistryModel
import org.junit.Assert.*
import org.junit.Test

class PriceExchangeImportPlanTest {
    private val models = listOf(
        RegistryModel("one", "p", "One", "Exact-Case"),
        RegistryModel("two", "p", "Two", "Exact-Case"),
        RegistryModel("lower", "p", "Lower", "exact-case")
    )
    private fun entry(id: String, request: String) = PriceExchangeEntry(id, request, rule = ModelPriceEntry("sample", PriceRates(1.0, 2.0)))

    @Test fun `只对唯一精确ID提出建议大小写冲突和多重匹配需用户映射`() {
        val doc = PriceExchangeDocument(name = "x", entries = listOf(entry("a", "Exact-Case")))
        val plan = PriceExchangeImportPlan.create(doc, models)
        assertNull(plan.rows.single().suggestedModelId)
        assertEquals(listOf("one", "two"), plan.rows.single().candidates)
        val selected = PriceExchangeImportPlan.create(doc, models, mapOf("a" to "one"))
        assertEquals("one", selected.rows.single().selectedModelId)
    }

    @Test fun `已有报价默认跳过并检测重复目标`() {
        val doc = PriceExchangeDocument(name = "x", entries = listOf(entry("a", "a"), entry("b", "b")))
        val repeatedModels = listOf(models.first().copy(requestModelId = "a"), models.first().copy(id = "three", requestModelId = "b"))
        val plan = PriceExchangeImportPlan.create(doc, repeatedModels, mapOf("a" to "one", "b" to "one"), existingPricingModelIds = setOf("one"))
        assertTrue(plan.rows.all { it.defaultSkip })
        assertTrue(plan.conflicts.isEmpty())
        val overwrite = PriceExchangeImportPlan.create(doc, repeatedModels, mapOf("a" to "one", "b" to "one"),
            overwriteModelIds = setOf("one"), existingPricingModelIds = setOf("one"))
        assertEquals(1, overwrite.conflicts.size)
    }

    @Test fun `重复目标可将单条报价明确保留为未关联并携带适用条件`() {
        val condition = ExchangePriceConditions("OFF", "d".repeat(64))
        val doc = PriceExchangeDocument(name = "x", entries = listOf(
            entry("a", "a").copy(conditions = condition), entry("b", "a").copy(conditions = condition)))
        val repeated = listOf(models.first().copy(requestModelId = "a"))
        val plan = PriceExchangeImportPlan.create(doc, repeated, keepUnassignedEntryIds = setOf("b"))
        assertTrue(plan.ready)
        val applied = plan.applyTo(doc, ProviderPricing(), repeated)
        assertEquals(1, applied.bindings.size)
        assertEquals(PriceConditions("OFF", "d".repeat(64)), applied.unassigned.single().conditions)
    }

    @Test fun `应用预览计划默认不覆盖并保留未关联与只读存档`() {
        val model = models.first().copy(requestModelId = "target")
        val old = BoundModelPrice("target", ModelPriceEntry("exact-case", PriceRates(7.0, 8.0)))
        val current = ProviderPricing(bindings = mapOf("one" to old))
        val archive = PriceExchangeArchive("old", catalogPresent = false)
        val document = PriceExchangeDocument(name = "file", entries = listOf(entry("file", "target")),
            unassigned = listOf(PriceExchangeEntry("orphan", "orphan", rule = null, unassignedReason = "UNKNOWN")),
            legacyArchive = archive)
        val rows = listOf(model)
        val skipPlan = PriceExchangeImportPlan.create(document, rows, existingPricingModelIds = setOf("one"))
        val skipped = skipPlan.applyTo(document, current, rows)
        assertEquals(old, skipped.bindings.getValue("one"))
        assertEquals(1, skipped.unassigned.size)
        assertEquals(listOf(archive), skipped.importedArchives)

        val replacePlan = PriceExchangeImportPlan.create(document, rows, overwriteModelIds = setOf("one"), existingPricingModelIds = setOf("one"))
        val replaced = replacePlan.applyTo(document, current, rows)
        assertEquals(1.0, replaced.bindings.getValue("one").rule.standard.input, 0.0)
    }
}
