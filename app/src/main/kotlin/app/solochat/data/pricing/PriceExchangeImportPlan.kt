package app.solochat.data.pricing

import app.solochat.core.model.*
import app.solochat.data.RegistryModel
import java.util.UUID

/** 跨安装导入只提出映射；目标选择和覆盖确认由 UI 显式传入，未调用发布器。 */
data class PriceExchangeImportPlan(
    val rows: List<Row>,
    val conflicts: List<String>
) {
    data class Row(
        val fileEntryId: String,
        val requestModelId: String,
        val suggestedModelId: String?,
        val candidates: List<String>,
        val selectedModelId: String?,
        val defaultSkip: Boolean,
        val reason: String?,
        val keepUnassigned: Boolean = false
    )

    val ready: Boolean get() = conflicts.isEmpty() && rows.none { it.selectedModelId != null && it.reason != null }

    /** 将用户确认后的映射应用于价格草稿；不会触碰配置存储或活动模型。 */
    fun applyTo(document: PriceExchangeDocument, current: ProviderPricing, models: List<RegistryModel>): ProviderPricing {
        check(ready) { "导入目标冲突尚未解决" }
        val entries = document.entries.associateBy { it.fileEntryId }
        val bindings = current.bindings.toMutableMap()
        val unmatched = current.unassigned.toMutableList()
        rows.forEach { row ->
            val entry = requireNotNull(entries[row.fileEntryId])
            val modelId = row.selectedModelId
            if (modelId == null || row.keepUnassigned) unmatched += UnassignedModelPrice(UUID.randomUUID().toString(), entry.requestModelId,
                entry.rule, entry.origin.toImportOrigin(), UnassignedPriceReason.IMPORT_UNMATCHED, entry.conditions.toImportConditions())
            else if (!row.defaultSkip && entry.rule != null) {
                val model = models.firstOrNull { it.id == modelId } ?: error("目标模型已变化，请重新预览导入")
                val rule = requireNotNull(entry.rule)
                bindings[modelId] = BoundModelPrice(model.requestModelId,
                    rule.copy(model = model.requestModelId.trim().lowercase()), entry.origin.toImportOrigin(), entry.conditions.toImportConditions())
            }
        }
        unmatched += document.unassigned.map { item -> UnassignedModelPrice(UUID.randomUUID().toString(), item.requestModelId,
            item.rule, item.origin.toImportOrigin(), UnassignedPriceReason.IMPORT_UNMATCHED, item.conditions.toImportConditions()) }
        return current.copy(bindings = bindings, unassigned = unmatched,
            importedArchives = current.importedArchives + listOfNotNull(document.legacyArchive) + document.archives)
    }

    companion object {
        fun create(document: PriceExchangeDocument, models: List<RegistryModel>,
            selectedMappings: Map<String, String> = emptyMap(), overwriteModelIds: Set<String> = emptySet(),
            existingPricingModelIds: Set<String> = emptySet(), keepUnassignedEntryIds: Set<String> = emptySet()): PriceExchangeImportPlan {
            val active = document.entries
            val rows = active.map { entry ->
                val exact = models.filter { it.requestModelId == entry.requestModelId }.map { it.id }
                val candidates = exact.ifEmpty { models.filter { it.requestModelId.equals(entry.requestModelId, ignoreCase = true) }.map { it.id } }
                val selected = selectedMappings[entry.fileEntryId]
                val modelId = if (entry.rule == null) null else selected ?: exact.singleOrNull()
                val reason = when {
                    entry.rule == null -> "报价规则未知，不能导入为零价"
                    candidates.isEmpty() -> "未匹配目标模型，保留为未关联"
                    exact.isEmpty() && selected == null -> "请求 ID 仅大小写相似；需手动确认目标"
                    selected != null && selected !in models.map { it.id } -> "目标模型不属于所选提供商"
                    candidates.size > 1 && selected == null -> "多个模型使用相同精确请求 ID，请选择"
                    else -> null
                }
                Row(entry.fileEntryId, entry.requestModelId, candidates.singleOrNull(), candidates,
                    modelId, modelId != null && modelId in existingPricingModelIds && modelId !in overwriteModelIds, reason,
                    entry.fileEntryId in keepUnassignedEntryIds)
            }
            val collisions = rows.filterNot { it.defaultSkip || it.keepUnassigned }.mapNotNull { it.selectedModelId }.groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys.map { "同一目标模型收到多条报价：$it" }
            return PriceExchangeImportPlan(rows, collisions)
        }
    }
}

private fun ExchangePriceOrigin.toImportOrigin() = PriceOrigin(runCatching { PriceOriginKind.valueOf(kind) }
    .getOrDefault(PriceOriginKind.IMPORT), sourceId, channel, checkedAt, url, note)
private fun ExchangePriceConditions.toImportConditions() = PriceConditions(thinkingMode, connectionFingerprint)
