package app.solochat.data.pricing

import app.solochat.core.agent.ChatConfig
import app.solochat.core.model.*
import app.solochat.data.LegacyProviderPricing
import app.solochat.data.RegistryModel

/** P2-A 旧路径与只读整理计划共用的旧价格输入装配。 */
internal object LegacyPricingInput {
    fun config(legacy: LegacyProviderPricing, model: RegistryModel, base: ChatConfig = ChatConfig(), bundled: PriceCatalog? = null): ChatConfig {
        val eligible = model.legacyPriceEligible
        return base.copy(
            model = model.requestModelId,
            useOfficialPrice = legacy.useOfficialPrice,
            priceCatalog = legacy.priceCatalog ?: if (eligible) bundled else
                PriceCatalog("unpriced", listOf(PriceTable("unpriced", "未配置报价", emptyList()))),
            priceInputPerM = legacy.priceInput.takeIf { eligible } ?: 0.0,
            priceOutputPerM = legacy.priceOutput.takeIf { eligible } ?: 0.0,
            priceCachedPerM = legacy.priceCached.takeIf { eligible } ?: 0.0,
            priceConfigured = legacy.priceConfigured && eligible,
            cachedPriceConfigured = legacy.cachedPriceConfigured && eligible
        )
    }

    fun matchedEntry(legacy: LegacyProviderPricing, model: RegistryModel, bundled: PriceCatalog?): Pair<ModelPriceEntry, PriceOriginKind>? {
        if (legacy.useOfficialPrice) {
            val catalog = legacy.priceCatalog ?: if (model.legacyPriceEligible) bundled else null
            val table = catalog?.tables?.firstOrNull { it.id == catalog.selectedTableId }
            val entries = table?.entries.orEmpty()
            val matched = PricingRules.matchedModelId(entries.mapTo(mutableSetOf()) { it.model }, model.requestModelId)
            if (table != null && matched != null) {
                val entry = entries.first { it.model == matched }
                val origin = if (legacy.priceCatalog == null) PriceOriginKind.LEGACY_BUNDLED else PriceOriginKind.LEGACY_TABLE
                return entry.copy(model = model.requestModelId.lowercase()) to origin
            }
        }
        if (!model.legacyPriceEligible || !legacy.priceConfigured) return null
        return ModelPriceEntry(
            model = model.requestModelId.lowercase(),
            standard = PriceRates(legacy.priceInput, legacy.priceOutput,
                legacy.priceCached.takeIf { legacy.cachedPriceConfigured }),
            source = "旧配置兼容价"
        ) to PriceOriginKind.LEGACY_COMPATIBILITY
    }
}
