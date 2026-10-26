package app.solochat.data.pricing

import app.solochat.core.model.PriceCatalog
import app.solochat.data.ModelRegistryDocument

/** 价格页仅提交明确差异；在最新文档上定向合并并产生下一 revision。 */
object ProviderPricingPublisher {
    fun edit(
        current: ModelRegistryDocument,
        providerId: String,
        expectedRevision: Long,
        upserts: Map<String, BoundModelPrice> = emptyMap(),
        removes: Set<String> = emptySet(),
        unassigned: List<UnassignedModelPrice>? = null,
        importedArchives: List<app.solochat.core.model.PriceExchangeArchive>? = null
    ): ModelRegistryDocument {
        requireRevision(current, expectedRevision)
        val provider = current.providers.firstOrNull { it.id == providerId } ?: error("提供商不存在")
        val active = provider.pricing ?: error("旧价格尚未整理，请先提交只读整理计划")
        require(upserts.keys.intersect(removes).isEmpty()) { "同一模型不能同时添加和清除报价" }
        val nextBindings = active.bindings.toMutableMap().apply {
            removes.forEach(::remove)
            putAll(upserts)
        }
        val nextPricing = active.copy(bindings = nextBindings, unassigned = unassigned ?: active.unassigned,
            importedArchives = importedArchives ?: active.importedArchives)
        val updated = current.copy(revision = expectedRevision + 1, providers = current.providers.map {
            if (it.id == providerId) it.copy(pricing = nextPricing) else it
        })
        updated.validate()
        return updated
    }

    /** 预览计划显式提交；重算源与模型指纹，过期时拒绝，不触碰旧字段。 */
    fun migrate(
        current: ModelRegistryDocument,
        plan: ProviderPricingMigrationPlan,
        bundledSnapshot: PriceCatalog?
    ): ModelRegistryDocument {
        val provider = current.providers.firstOrNull { it.id == plan.providerId } ?: error("提供商不存在")
        val applied = provider.pricing?.legacyArchive?.appliedPlanId
        if (applied == plan.sourceFingerprint) return current
        requireRevision(current, plan.baseRevision)
        val fresh = ProviderPricingMigrationPlan.create(current, plan.providerId, bundledSnapshot)
        check(fresh.sourceFingerprint == plan.sourceFingerprint && fresh.result == plan.result) {
            "舊價格或模型身份已變化，請重新預覽整理計劃"
        }
        require(current.revision < Long.MAX_VALUE) { "模型配置修订号已耗尽" }
        val migrated = plan.result.copy(legacyArchive = plan.archive.copy(appliedPlanId = plan.sourceFingerprint))
        val updated = current.copy(revision = current.revision + 1, providers = current.providers.map {
            if (it.id == plan.providerId) it.copy(pricing = migrated) else it
        })
        updated.validate()
        return updated
    }

    private fun requireRevision(current: ModelRegistryDocument, expected: Long) {
        require(expected >= 0 && expected < Long.MAX_VALUE) { "模型配置修订号已耗尽或无效" }
        check(current.revision == expected) { "模型配置已变化，请重新载入后再保存" }
    }
}
