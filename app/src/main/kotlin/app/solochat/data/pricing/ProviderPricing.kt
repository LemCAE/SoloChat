package app.solochat.data.pricing

import app.solochat.core.model.*
import app.solochat.data.LegacyProviderPricing
import app.solochat.data.RegistryModel
import kotlinx.serialization.Serializable

/** Provider 唯一生效的模型绑定报价；null 与空配置有意保持不同含义。 */
@Serializable
data class ProviderPricing(
    val version: Int = CURRENT_VERSION,
    val bindings: Map<String, BoundModelPrice> = emptyMap(),
    val unassigned: List<UnassignedModelPrice> = emptyList(),
    val legacyArchive: LegacyPricingArchive? = null,
    /** 用户主动导入的只读文件存档，不参与运行选择。 */
    val importedArchives: List<PriceExchangeArchive> = emptyList()
) {
    fun validate(providerId: String, models: List<RegistryModel>) {
        require(version == CURRENT_VERSION) { "不支持报价配置版本：$version" }
        val modelById = models.associateBy { it.id }
        bindings.forEach { (modelId, bound) ->
            val model = modelById[modelId] ?: error("报价绑定的模型不存在")
            require(bound.requestModelId.isNotBlank() && bound.requestModelId == model.requestModelId) { "报价绑定的请求身份已变化" }
            bound.conditions.thinkingMode?.let { app.solochat.core.model.ThinkingMode.valueOf(it) }
            bound.conditions.connectionFingerprint?.let {
                require(it.matches(Regex("[0-9a-f]{64}"))) { "报价连接指纹无效" }
            }
            validateEntry(bound.rule)
        }
        require(unassigned.map { it.id }.distinct().size == unassigned.size) { "未关联报价 ID 重复" }
        unassigned.forEach {
            require(it.id.isNotBlank() && it.requestModelId.isNotBlank()) { "未关联报价身份无效" }
            it.conditions.thinkingMode?.let { mode -> app.solochat.core.model.ThinkingMode.valueOf(mode) }
            it.conditions.connectionFingerprint?.let { fingerprint ->
                require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "未关联报价连接指纹无效" }
            }
            it.rule?.let(::validateEntry)
        }
        legacyArchive?.legacy?.validate()
        importedArchives.forEach { archive ->
            PriceExchangeTransfer.encode(PriceExchangeDocument(name = "archive-validation", entries = emptyList(), legacyArchive = archive))
        }
        require(providerId.isNotBlank())
    }

    companion object { const val CURRENT_VERSION = 1 }
}

@Serializable
data class BoundModelPrice(
    val requestModelId: String,
    val rule: ModelPriceEntry,
    val origin: PriceOrigin = PriceOrigin(PriceOriginKind.MANUAL),
    val conditions: PriceConditions = PriceConditions()
)

@Serializable
data class UnassignedModelPrice(
    val id: String,
    val requestModelId: String,
    val rule: ModelPriceEntry?,
    val origin: PriceOrigin,
    val reason: UnassignedPriceReason,
    val conditions: PriceConditions = PriceConditions()
)

@Serializable
data class LegacyPricingArchive(
    val legacy: LegacyProviderPricing,
    val bundledSnapshot: PriceCatalog? = null,
    val sourceFingerprint: String? = null,
    val appliedPlanId: String? = null
)

@Serializable
data class PriceOrigin(
    val kind: PriceOriginKind,
    val sourceId: String? = null,
    val channel: String? = null,
    val checkedAt: String? = null,
    val url: String? = null,
    val note: String? = null
)

@Serializable
enum class PriceOriginKind { MANUAL, LEGACY_TABLE, LEGACY_COMPATIBILITY, LEGACY_BUNDLED, IMPORT, REFERENCE }

@Serializable
data class PriceConditions(
    val thinkingMode: String? = null,
    /** 只存非秘密连接指纹，不存 endpoint 或 Key。 */
    val connectionFingerprint: String? = null
)

@Serializable
enum class UnassignedPriceReason { LEGACY_UNMATCHED, REQUEST_ID_CHANGED, MODEL_REMOVED, IMPORT_UNMATCHED }

internal fun validateEntry(entry: ModelPriceEntry) {
    PriceCatalogFormat.validateEntry(entry)
    require(entry.model == entry.model.trim().lowercase())
}
