package app.solochat.data

import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.ModelReference
import app.solochat.core.model.ModelThinking
import app.solochat.core.model.ThinkingControl
import app.solochat.core.model.ThinkingMode
import app.solochat.core.model.CapabilitySource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** 只读迁移输入；会话覆盖由调用方从现有行投影，不扫描调用历史。 */
data class ModelConfigUpgradeSource(
    val settings: Snapshot,
    val conversations: List<LegacyConversationModelOverride>
)

@Serializable
data class LegacyConversationModelOverride(val conversationId: String, val modelOverride: String?)

@Serializable
data class ModelIdAssignment(val providerId: String, val requestModelId: String, val modelId: String)

@Serializable
data class ModelConfigPreparationRecord(
    val sourceRevision: String,
    val modelIds: List<ModelIdAssignment>,
    /** v1 是 A-2 的纯 ID 准备记录；冻结状态以 v2 显式标记。 */
    val version: Int = 1,
    /** A-2 旧记录缺少这些字段时按 PREPARED 读取；升级前仍复用原模型 ID。 */
    val phase: String = "PREPARED",
    val planId: String? = null,
    val frozenPlan: ModelConfigUpgradePlan? = null
) {
    fun validate() {
        require(sourceRevision.matches(Regex("[0-9a-f]{64}"))) { "源版本标记无效" }
        require(version in 1..2) { "不支持配置升级准备记录版本：$version" }
        require(modelIds.all { it.providerId.isNotBlank() && it.requestModelId.isNotBlank() && it.modelId.isNotBlank() }) {
            "模型 ID 映射无效"
        }
        val keys = modelIds.map { it.providerId to it.requestModelId }
        require(keys.distinct().size == keys.size && modelIds.map { it.modelId }.distinct().size == modelIds.size) {
            "模型 ID 映射重复"
        }
        require(phase in setOf("PREPARED", "FROZEN", "ROOM_APPENDED", "READY")) { "未知配置升级阶段：$phase" }
        if (phase == "PREPARED") require(version == 1 && planId == null && frozenPlan == null) {
            "纯准备阶段记录版本或字段无效"
        }
        else {
            require(version == 2) { "冻结阶段需要 v2 准备记录" }
            require(!planId.isNullOrBlank() && frozenPlan != null) { "升级阶段缺少计划身份或冻结计划" }
            require(frozenPlan.sourceRevision == sourceRevision) { "冻结计划源版本不一致" }
            frozenPlan.candidate.validate()
            require(frozenPlan.conversations.map { it.conversationId }.distinct().size == frozenPlan.conversations.size) {
                "冻结计划会话重复"
            }
        }
    }
}

@Serializable
data class ModelConfigConversationPlan(
    val conversationId: String,
    val selection: RegistrySelection,
    val migrationSource: String
)

@Serializable
data class ModelConfigUpgradeIssue(
    val code: String,
    val scope: String,
    val sourceValue: String,
    val reason: String
)

@Serializable
data class ModelConfigUpgradePlan(
    val sourceRevision: String,
    val candidate: ModelRegistryDocument,
    val conversations: List<ModelConfigConversationPlan>,
    val issues: List<ModelConfigUpgradeIssue>
) {
    fun requireCurrent(current: ModelConfigUpgradeSource) {
        require(sourceRevision == computeSourceRevision(current)) { "源配置已变化，此升级草案已过期" }
    }

    companion object {
        private val json = Json { encodeDefaults = true; explicitNulls = true }

        fun computeSourceRevision(source: ModelConfigUpgradeSource): String {
            val profiles = profiles(source.settings)
            val canonical = UpgradeSourceMarker(
                activeProfileId = source.settings.apiProfileId,
                activeModel = source.settings.model,
                thinkingEffort = source.settings.thinkingEffort,
                profiles = profiles,
                conversations = source.conversations.sortedBy { it.conversationId }
            )
            return MessageDigest.getInstance("SHA-256")
                .digest(json.encodeToString(canonical).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /** 仅在保存准备记录时调用；后续重试传回原记录，不再生成 ID。 */
        fun prepare(source: ModelConfigUpgradeSource, newId: () -> String = { UUID.randomUUID().toString() }): ModelConfigPreparationRecord {
            val assignments = linkedMapOf<Pair<String, String>, String>()
            profiles(source.settings).forEach { profile ->
                modelNames(profile).forEach { request -> assignments[profile.id to request] = newId() }
            }
            val activeId = source.settings.apiProfileId
            val activeExists = profiles(source.settings).any { it.id == activeId }
            if (activeExists) source.conversations.mapNotNull { it.modelOverride?.takeIf(String::isNotBlank) }
                .forEach { request -> assignments.getOrPut(activeId to request, newId) }
            return ModelConfigPreparationRecord(
                computeSourceRevision(source),
                assignments.map { (key, modelId) -> ModelIdAssignment(key.first, key.second, modelId) }
            ).also(ModelConfigPreparationRecord::validate)
        }

        fun build(source: ModelConfigUpgradeSource, preparation: ModelConfigPreparationRecord): ModelConfigUpgradePlan {
            preparation.validate()
            require(preparation.sourceRevision == computeSourceRevision(source)) { "源配置已变化，此升级草案已过期" }
            val profiles = profiles(source.settings)
            val idMap = preparation.modelIds.associate { (it.providerId to it.requestModelId) to it.modelId }
            val activeProfile = profiles.firstOrNull { it.id == source.settings.apiProfileId }
            val issues = mutableListOf<ModelConfigUpgradeIssue>()
            val conversationPlans = source.conversations.map { row ->
                val rawOverride = row.modelOverride?.takeIf(String::isNotBlank)
                if (rawOverride == null) {
                    ModelConfigConversationPlan(row.conversationId, RegistrySelection.inherit(), "legacy_no_override")
                } else if (activeProfile == null) {
                    issues += ModelConfigUpgradeIssue(
                        "MISSING_ACTIVE_PROVIDER", "conversation:${row.conversationId}", rawOverride,
                        "旧会话只有模型名，当前有效接口组缺失，需选择提供商和模型"
                    )
                    ModelConfigConversationPlan(row.conversationId,
                        RegistrySelection.needsConfirmation(null, "旧会话模型缺少有效接口组"), "legacy_override_pending")
                } else {
                    val modelId = idMap[activeProfile.id to rawOverride]
                    if (modelId == null) {
                        issues += ModelConfigUpgradeIssue(
                            "MODEL_ID_MAPPING_MISSING", "conversation:${row.conversationId}", rawOverride,
                            "旧会话模型名未能建立稳定映射"
                        )
                        ModelConfigConversationPlan(row.conversationId,
                            RegistrySelection.needsConfirmation(null, "旧会话模型映射缺失"), "legacy_override_pending")
                    } else ModelConfigConversationPlan(
                        row.conversationId,
                        RegistrySelection.explicit(ModelReference(activeProfile.id, modelId)),
                        "legacy_override:${rawOverride}"
                    )
                }
            }

            val selectedDefault = when {
                activeProfile == null -> {
                    issues += ModelConfigUpgradeIssue(
                        "MISSING_ACTIVE_PROVIDER", "global", source.settings.model,
                        "旧全局配置没有有效接口组；不能从其他组或同名模型推断"
                    )
                    RegistrySelection.needsConfirmation(null, "旧全局接口组缺失，需确认提供商与模型")
                }
                source.settings.model.isBlank() -> {
                    issues += ModelConfigUpgradeIssue("EMPTY_DEFAULT_MODEL", "global", "", "旧全局模型为空，需选择模型")
                    RegistrySelection.needsConfirmation(null, "旧全局模型为空，需确认模型")
                }
                idMap[activeProfile.id to source.settings.model] == null -> {
                    issues += ModelConfigUpgradeIssue("MODEL_ID_MAPPING_MISSING", "global", source.settings.model, "旧全局模型名未能建立稳定映射")
                    RegistrySelection.needsConfirmation(null, "旧全局模型映射缺失")
                }
                else -> RegistrySelection.explicit(
                    ModelReference(activeProfile.id, idMap.getValue(activeProfile.id to source.settings.model))
                )
            }

            val targetedModels = buildSet {
                if (activeProfile != null && source.settings.model.isNotBlank()) add(activeProfile.id to source.settings.model)
                if (activeProfile != null) source.conversations.mapNotNull { it.modelOverride?.takeIf(String::isNotBlank) }
                    .forEach { add(activeProfile.id to it) }
            }
            val thinkingConversion = convertLegacyThinking(source.settings.thinkingEffort)
            if (thinkingConversion == null && source.settings.thinkingEffort.isNotBlank()) {
                targetedModels.forEach { (providerId, requestModelId) ->
                    issues += ModelConfigUpgradeIssue(
                        "UNKNOWN_LEGACY_THINKING", "model:$providerId/$requestModelId", source.settings.thinkingEffort,
                        "旧思考设置无法识别，需人工确认；未推导强度档位"
                    )
                }
                if (targetedModels.isEmpty()) issues += ModelConfigUpgradeIssue(
                    "UNKNOWN_LEGACY_THINKING", "global", source.settings.thinkingEffort, "旧思考设置无法识别，需人工确认"
                )
            }

            val providers = profiles.map { profile -> RegistryProvider(
                id = profile.id,
                name = profile.name,
                baseUrl = profile.baseUrl,
                keyId = profile.keyId,
                legacyPricing = LegacyProviderPricing.from(profile)
            ) }
            val models = profiles.flatMap { profile ->
                val requests = (modelNames(profile) + if (profile.id == activeProfile?.id) {
                    source.conversations.mapNotNull { it.modelOverride?.takeIf(String::isNotBlank) }
                } else emptyList()).distinct()
                requests.map { request ->
                val preference = if ((profile.id to request) in targetedModels) thinkingConversion else ModelThinking(ThinkingMode.AUTO)
                RegistryModel(
                    id = idMap[profile.id to request] ?: error("准备记录缺少模型 ID：${profile.id}/$request"),
                    providerId = profile.id,
                    displayName = request,
                    requestModelId = request,
                    thinkingMode = preference?.mode?.name ?: ThinkingMode.AUTO.name,
                    thinkingEffort = preference?.effort?.name,
                    thinkingControl = ThinkingControl.UNKNOWN.name,
                    capabilitySource = CapabilitySource.UNKNOWN.name,
                    legacyPriceEligible = true
                )
            } }
            val document = ModelRegistryDocument(
                providers = providers,
                models = models,
                globalDefault = selectedDefault
            )
            document.validate()
            return ModelConfigUpgradePlan(computeSourceRevision(source), document, conversationPlans, issues)
        }

        private fun profiles(settings: Snapshot) = if (settings.apiProfiles.isEmpty()) {
            listOf(ApiProfile.from(settings))
        } else settings.apiProfiles

        private fun modelNames(profile: ApiProfile): List<String> =
            (profile.modelList + profile.model).filter(String::isNotBlank).distinct()

        private fun convertLegacyThinking(raw: String): ModelThinking? {
            if (raw.isBlank()) return ModelThinking(ThinkingMode.AUTO)
            val value = raw.trim()
            val known = ThinkingEffort.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.wire.equals(value, ignoreCase = true)
            } ?: return null
            return if (known == ThinkingEffort.OFF) ModelThinking(ThinkingMode.OFF)
            else ModelThinking(ThinkingMode.ON)
        }
    }
}

@Serializable
private data class UpgradeSourceMarker(
    val activeProfileId: String,
    val activeModel: String,
    val thinkingEffort: String,
    val profiles: List<ApiProfile>,
    val conversations: List<LegacyConversationModelOverride>
)
