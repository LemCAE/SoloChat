package app.solochat.data.pricing

import app.solochat.core.model.PriceCatalog
import app.solochat.data.ModelRegistryDocument
import app.solochat.data.RegistryModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class PricingUnknown(val modelId: String, val requestModelId: String, val reason: String)

/** 纯只读转换预览；调用方传入所有数据，不访问 assets、时钟、DataStore 或账目。 */
data class ProviderPricingMigrationPlan(
    val providerId: String,
    val baseRevision: Long,
    val sourceFingerprint: String,
    val bindings: Map<String, BoundModelPrice>,
    val unknown: List<PricingUnknown>,
    val unassigned: List<UnassignedModelPrice>,
    val archive: LegacyPricingArchive,
    val result: ProviderPricing
) {
    companion object {
        private val json = Json { encodeDefaults = true; explicitNulls = true }

        fun create(document: ModelRegistryDocument, providerId: String, bundledSnapshot: PriceCatalog?): ProviderPricingMigrationPlan {
            document.validate()
            require(document.revision >= 0)
            val provider = document.providers.firstOrNull { it.id == providerId } ?: error("提供商不存在")
            require(provider.pricing == null) { "提供商报价已整理" }
            val models = document.models.filter { it.providerId == providerId }
            val sourceObject = MigrationFingerprintSource(provider.legacyPricing,
                models.map { MigrationModelFingerprint(it.id, it.providerId, it.requestModelId, it.legacyPriceEligible) }, bundledSnapshot)
            val sourceFingerprint = sha256(json.encodeToString(sourceObject))
            val bound = linkedMapOf<String, BoundModelPrice>()
            val unknown = mutableListOf<PricingUnknown>()
            models.forEach { model ->
                val matched = LegacyPricingInput.matchedEntry(provider.legacyPricing, model, bundledSnapshot)
                if (matched == null) unknown += PricingUnknown(model.id, model.requestModelId, "旧规则未配置或无法匹配")
                else {
                    val (entry, origin) = matched
                    bound[model.id] = BoundModelPrice(model.requestModelId, entry,
                        PriceOrigin(origin, sourceId = when (origin) {
                            PriceOriginKind.LEGACY_TABLE -> provider.legacyPricing.priceCatalog?.selectedTableId
                            PriceOriginKind.LEGACY_BUNDLED -> bundledSnapshot?.selectedTableId
                            else -> "legacy-fallback"
                        }))
                }
            }
            val usedLegacyIds = models.mapNotNull { model ->
                LegacyPricingInput.matchedEntry(provider.legacyPricing, model, bundledSnapshot)
                    ?.takeIf { it.second == PriceOriginKind.LEGACY_TABLE }?.first?.model
            }.toSet()
            val activeUnmatched = if (provider.legacyPricing.useOfficialPrice)
                provider.legacyPricing.priceCatalog?.tables?.firstOrNull { it.id == provider.legacyPricing.priceCatalog.selectedTableId }
                    ?.entries.orEmpty().filterNot { it.model in usedLegacyIds }.map { entry ->
                    UnassignedModelPrice("legacy:${entry.model}", entry.model, entry,
                        PriceOrigin(PriceOriginKind.LEGACY_TABLE, provider.legacyPricing.priceCatalog?.selectedTableId),
                        UnassignedPriceReason.LEGACY_UNMATCHED)
                } else emptyList()
            val archive = LegacyPricingArchive(provider.legacyPricing, bundledSnapshot, sourceFingerprint)
            val result = ProviderPricing(bindings = bound, unassigned = activeUnmatched, legacyArchive = archive)
            result.validate(providerId, models)
            return ProviderPricingMigrationPlan(providerId, document.revision, sourceFingerprint, bound, unknown,
                activeUnmatched, archive, result)
        }

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class MigrationFingerprintSource(
    val legacy: app.solochat.data.LegacyProviderPricing,
    val models: List<MigrationModelFingerprint>,
    val bundled: PriceCatalog?
)

@Serializable
private data class MigrationModelFingerprint(
    val id: String,
    val providerId: String,
    val requestModelId: String,
    val legacyPriceEligible: Boolean
)
