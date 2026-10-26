package app.solochat.data.pricing

import app.solochat.data.ModelRegistryDocument
import app.solochat.data.RegistryProvider
import app.solochat.data.RegistryModel

/** READY 模型编辑只合并该页有权编辑的字段；报价与旧源始终取当前文档。 */
object ReadyModelRegistryMerger {
    /** 仅供内存草稿保持可校验；发布仍须重新以当前文档计算并执行整理门禁。 */
    fun project(current: ModelRegistryDocument, candidate: ModelRegistryDocument): ModelRegistryDocument {
        val oldModels = current.models.associateBy { it.id }
        val nextModels = candidate.models.associateBy { it.id }
        val currentProviders = current.providers.associateBy { it.id }
        return candidate.copy(providers = candidate.providers.map { draft ->
            val old = currentProviders[draft.id] ?: return@map draft.copy(pricing = ProviderPricing())
            draft.copy(legacyPricing = old.legacyPricing,
                pricing = old.pricing?.let { unbindChanged(it, oldModels, nextModels, draft.id) })
        })
    }

    fun merge(current: ModelRegistryDocument, candidate: ModelRegistryDocument): ModelRegistryDocument {
        current.validate()
        require(candidate.revision == current.revision) { "模型配置已变化，请重新载入后再保存" }
        val currentProviders = current.providers.associateBy { it.id }
        candidate.providers.forEach { next ->
            val old = currentProviders[next.id]
            if (old != null) require(next.keyId == old.keyId) { "不能从模型草稿修改密钥槽位" }
        }
        require(candidate.models.all { model ->
            current.models.firstOrNull { it.id == model.id }?.let { it.providerId == model.providerId } ?: true
        }) { "已有模型不能改归属提供商" }
        val oldModels = current.models.associateBy { it.id }
        val nextModels = candidate.models.associateBy { it.id }
        val byProvider = candidate.providers.associate { it.id to candidate.models.filter { model -> model.providerId == it.id } }
        val mergedProviders = candidate.providers.map { draftProvider ->
            val old = currentProviders[draftProvider.id]
            if (old == null) draftProvider.copy(pricing = ProviderPricing())
            else {
                val providerModels = byProvider[draftProvider.id].orEmpty()
                val changedIdentity = providerModels.any { model -> oldModels[model.id]?.requestModelId != model.requestModelId } ||
                    oldModels.values.any { it.providerId == draftProvider.id && it.id !in nextModels }
                if (changedIdentity && old.pricing == null)
                    error("该提供商旧价格尚未整理，请先预览并提交整理计划")
                val pricing = old.pricing?.let { unbindChanged(it, oldModels, nextModels, draftProvider.id) }
                draftProvider.copy(legacyPricing = old.legacyPricing, pricing = pricing)
            }
        }
        return candidate.copy(providers = mergedProviders, revision = current.revision)
            .also(ModelRegistryDocument::validate)
    }

    private fun unbindChanged(
        pricing: ProviderPricing,
        oldModels: Map<String, RegistryModel>,
        nextModels: Map<String, RegistryModel>,
        providerId: String
    ): ProviderPricing {
        val bindings = pricing.bindings.toMutableMap()
        val unassigned = pricing.unassigned.toMutableList()
        oldModels.values.filter { it.providerId == providerId }.forEach { old ->
            val next = nextModels[old.id]
            if (next == null || next.requestModelId != old.requestModelId) {
                val bound = bindings.remove(old.id) ?: return@forEach
                val reason = if (next == null) UnassignedPriceReason.MODEL_REMOVED else UnassignedPriceReason.REQUEST_ID_CHANGED
                // 每次新的解绑都是新的待处理报价，即使模型和请求 ID 恢复过相同值。
                unassigned += UnassignedModelPrice(java.util.UUID.randomUUID().toString(), bound.requestModelId,
                    bound.rule, bound.origin, reason, bound.conditions)
            }
        }
        return pricing.copy(bindings = bindings, unassigned = unassigned)
    }
}
