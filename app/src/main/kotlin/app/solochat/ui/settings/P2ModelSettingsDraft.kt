package app.solochat.ui.settings

import app.solochat.core.model.ModelReference
import app.solochat.core.model.ModelUse
import app.solochat.data.ApiProfile
import app.solochat.data.LegacyProviderPricing
import app.solochat.data.ModelRegistryDocument
import app.solochat.data.RegistryModel
import app.solochat.data.RegistryProvider
import app.solochat.data.RegistrySelection

/** A-8/A-9 设置页未保存编辑只在内存中暂存；明确保存后由应用发布到 READY v2。 */
internal data class P2ModelSettingsDraft(
    val document: ModelRegistryDocument,
    val selectedProviderId: String = document.providers.firstOrNull()?.id.orEmpty(),
    val selectedModelId: String? = null,
    val dirty: Boolean = false
) {
    fun selection(target: P2SelectionTarget): RegistrySelection = when (target) {
        P2SelectionTarget.GLOBAL -> document.globalDefault
        P2SelectionTarget.CHAT -> document.chatSelection
        P2SelectionTarget.COMPRESSION -> document.compressionSelection
        P2SelectionTarget.TITLE -> document.titleSelection
    }

    fun withSelection(target: P2SelectionTarget, selection: RegistrySelection): P2ModelSettingsDraft {
        require(target != P2SelectionTarget.GLOBAL || selection.state != "INHERIT") {
            "全局默认必须显式选择或待确认"
        }
        val updated = when (target) {
            P2SelectionTarget.GLOBAL -> document.copy(globalDefault = selection)
            P2SelectionTarget.CHAT -> document.copy(chatSelection = selection)
            P2SelectionTarget.COMPRESSION -> document.copy(compressionSelection = selection)
            P2SelectionTarget.TITLE -> document.copy(titleSelection = selection)
        }
        updated.validate()
        return copy(document = updated, dirty = true)
    }

    fun withProvider(provider: RegistryProvider): P2ModelSettingsDraft {
        val updated = document.copy(providers = document.providers.map { if (it.id == provider.id) provider else it })
        updated.validate()
        return copy(document = updated, dirty = true)
    }

    /** 价格页保存独立 provider 价格时合并最新值，保留本页尚未保存的模型/用途编辑。 */
    fun withPersistedPricing(providerId: String, pricing: LegacyProviderPricing): P2ModelSettingsDraft {
        val updated = document.copy(providers = document.providers.map {
            if (it.id == providerId) it.copy(legacyPricing = pricing) else it
        })
        if (updated == document) return this
        updated.validate()
        return copy(document = updated)
    }

    fun removeProvider(providerId: String): P2ModelSettingsDraft {
        require(document.providers.any { it.id == providerId }) { "提供商已不存在" }
        val selections = listOf(document.globalDefault, document.chatSelection, document.compressionSelection, document.titleSelection)
        require(selections.none { it.state == "EXPLICIT" && it.providerId == providerId }) {
            "请先将全局和用途模型改到其他提供商，再移除该提供商"
        }
        val updated = document.copy(providers = document.providers.filterNot { it.id == providerId },
            models = document.models.filterNot { it.providerId == providerId })
        updated.validate()
        return copy(document = updated,
            selectedProviderId = if (selectedProviderId == providerId) updated.providers.firstOrNull()?.id.orEmpty() else selectedProviderId,
            dirty = true)
    }

    fun addProvider(name: String, baseUrl: String): P2ModelSettingsDraft {
        val usedProviderIds = document.providers.mapTo(mutableSetOf()) { it.id }
        val usedKeyIds = document.providers.mapTo(mutableSetOf()) { it.keyId }
        val id = newUniqueId(usedProviderIds, "provider-")
        val keyId = newUniqueId(usedKeyIds, "key-")
        val provider = RegistryProvider(id, name.trim(), baseUrl.trim(), keyId = keyId)
        val updated = document.copy(providers = document.providers + provider)
        updated.validate()
        return copy(document = updated, selectedProviderId = id, dirty = true)
    }

    fun withModel(model: RegistryModel): P2ModelSettingsDraft {
        val existing = document.models.firstOrNull { it.id == model.id }
        val adjusted = if (existing != null && existing.requestModelId != model.requestModelId)
            model.copy(legacyPriceEligible = false) else model
        val updated = if (existing != null) {
            document.copy(models = document.models.map { if (it.id == model.id) adjusted else it })
        } else document.copy(models = document.models + adjusted)
        updated.validate()
        return copy(document = updated, dirty = true)
    }

    fun addModel(providerId: String, displayName: String, requestModelId: String): P2ModelSettingsDraft {
        require(document.providers.any { it.id == providerId })
        val id = newUniqueId(document.models.mapTo(mutableSetOf()) { it.id }, "model-")
        val model = RegistryModel(id, providerId, displayName.trim(), requestModelId.trim())
        val updated = document.copy(models = document.models + model)
        updated.validate()
        return copy(document = updated, selectedModelId = id, dirty = true)
    }

    fun markPreviewSaved(): P2ModelSettingsDraft = copy(dirty = false)

    fun resolve(selection: RegistrySelection): ModelReference? = when (selection.state) {
        "EXPLICIT" -> if (selection.providerId != null && selection.modelId != null) {
            ModelReference(selection.providerId, selection.modelId)
        } else null
        "INHERIT" -> null
        else -> null
    }

    companion object {
        private fun newUniqueId(used: Set<String>, prefix: String = ""): String {
            var id: String
            do { id = prefix + java.util.UUID.randomUUID().toString() } while (id in used)
            return id
        }

        fun fromDocument(document: ModelRegistryDocument): P2ModelSettingsDraft = P2ModelSettingsDraft(
            document = document.also(ModelRegistryDocument::validate),
            selectedProviderId = document.globalDefault.providerId
                ?: document.providers.firstOrNull()?.id.orEmpty(),
            selectedModelId = document.globalDefault.modelId
        )

        /** 只投影旧配置供页面评审；调用方不得保存该投影或用它发布配置升级。 */
        fun fromLegacyProfiles(profiles: List<ApiProfile>, activeProviderId: String, activeModelId: String): P2ModelSettingsDraft {
            val providers = profiles.map { profile ->
                RegistryProvider(
                    id = profile.id,
                    name = profile.name,
                    baseUrl = profile.baseUrl,
                    keyId = profile.keyId,
                    legacyPricing = LegacyProviderPricing.from(profile)
                )
            }
            val models = profiles.flatMap { profile ->
                val ids = (profile.modelList + profile.model).map(String::trim).filter(String::isNotEmpty).distinct()
                ids.mapIndexed { index, requestId ->
                    RegistryModel(
                        id = "preview-${profile.id}-model-$index",
                        providerId = profile.id,
                        displayName = requestId,
                        requestModelId = requestId,
                        legacyPriceEligible = true
                    )
                }
            }
            val globalModel = models.firstOrNull { it.providerId == activeProviderId && it.requestModelId == activeModelId }
            val global = globalModel?.let { RegistrySelection.explicit(ModelReference(it.providerId, it.id)) }
                ?: RegistrySelection.needsConfirmation(null, "当前配置没有可预览的全局模型")
            return P2ModelSettingsDraft(
                document = ModelRegistryDocument(
                    providers = providers,
                    models = models,
                    globalDefault = global
                ),
                selectedProviderId = providers.firstOrNull { it.id == activeProviderId }?.id
                    ?: providers.firstOrNull()?.id.orEmpty()
            )
        }
    }
}

internal enum class P2SelectionTarget { GLOBAL, CHAT, COMPRESSION, TITLE }

