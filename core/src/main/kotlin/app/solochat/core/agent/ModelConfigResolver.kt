package app.solochat.core.agent

import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.*

/** 解析失败原因。解析只检查本地配置，不代表网络连通性已经验证。 */
enum class ModelResolutionFailure {
    UNSUPPORTED_VERSION,
    NOT_CONFIGURED,
    NEEDS_CONFIRMATION,
    PROVIDER_NOT_FOUND,
    MODEL_NOT_FOUND,
    PROVIDER_DISABLED,
    MODEL_DISABLED,
    CREDENTIALS_UNAVAILABLE
}

data class ModelResolutionError(
    val reason: ModelResolutionFailure,
    val source: SelectionSource?,
    val detail: String? = null
)

data class ResolvedModel(
    val provider: ModelProvider,
    val model: ModelDefinition,
    val selectionSource: SelectionSource,
    val credentialAvailability: CredentialAvailability
)

sealed interface ModelResolution {
    data class Success(val value: ResolvedModel) : ModelResolution
    data class Failure(val error: ModelResolutionError) : ModelResolution
}

/** 思考意图是否能由已知能力表达；不产生任何厂商请求字段。 */
sealed interface ThinkingResolution {
    data class Supported(val intent: ModelThinking, val source: ThinkingSource) : ThinkingResolution
    data class Unsupported(val intent: ModelThinking, val reason: ThinkingFailure, val source: ThinkingSource) : ThinkingResolution
}

enum class ThinkingFailure { CANNOT_DISABLE, CAPABILITY_UNKNOWN, CANNOT_ENABLE, EFFORT_UNSUPPORTED }

object ModelConfigResolver {
    const val CURRENT_VERSION = 1

    /** 聊天按会话→聊天用途→全局解析；其他用途按用途→全局解析。 */
    fun resolve(
        configuration: ModelConfiguration,
        use: ModelUse,
        credentialAvailabilityByProvider: Map<String, CredentialAvailability>,
        conversationBinding: ConversationModelBinding? = null
    ): ModelResolution {
        if (configuration.version != CURRENT_VERSION) {
            return failure(ModelResolutionFailure.UNSUPPORTED_VERSION, null, "配置版本 ${configuration.version}")
        }

        val chain = when (use) {
            ModelUse.CHAT -> listOfNotNull(
                conversationBinding?.selection?.takeUnless { it == ModelSelection.Inherit }
                    ?.let { it to SelectionSource.CONVERSATION },
                configuration.chatSelection.takeUnless { it == ModelSelection.Inherit }
                    ?.let { it to SelectionSource.CHAT_PREFERENCE },
                configuration.globalDefault to SelectionSource.GLOBAL_DEFAULT
            )
            ModelUse.COMPRESSION -> listOfNotNull(
                configuration.compressionSelection.takeUnless { it == ModelSelection.Inherit }
                    ?.let { it to SelectionSource.COMPRESSION_PREFERENCE },
                configuration.globalDefault to SelectionSource.GLOBAL_DEFAULT
            )
            ModelUse.TITLE -> listOfNotNull(
                configuration.titleSelection.takeUnless { it == ModelSelection.Inherit }
                    ?.let { it to SelectionSource.TITLE_PREFERENCE },
                configuration.globalDefault to SelectionSource.GLOBAL_DEFAULT
            )
        }

        val (selection, source) = chain.first()
        val reference = when (selection) {
            ModelSelection.Inherit -> return failure(ModelResolutionFailure.NOT_CONFIGURED, source)
            is ModelSelection.NeedsConfirmation ->
                return failure(ModelResolutionFailure.NEEDS_CONFIRMATION, source, selection.reason)
            is ModelSelection.Explicit -> selection.model
        }
        val provider = configuration.providers.firstOrNull { it.id == reference.providerId }
            ?: return failure(ModelResolutionFailure.PROVIDER_NOT_FOUND, source, reference.providerId)
        if (!provider.enabled) return failure(ModelResolutionFailure.PROVIDER_DISABLED, source, reference.providerId)
        val model = configuration.models.firstOrNull { it.providerId == reference.providerId && it.id == reference.modelId }
            ?: return failure(ModelResolutionFailure.MODEL_NOT_FOUND, source, reference.modelId)
        if (!model.enabled) return failure(ModelResolutionFailure.MODEL_DISABLED, source, reference.modelId)
        val credentialAvailability = credentialAvailabilityByProvider[provider.id] ?: CredentialAvailability.UNKNOWN
        if (credentialAvailability != CredentialAvailability.AVAILABLE) {
            return failure(ModelResolutionFailure.CREDENTIALS_UNAVAILABLE, source, credentialAvailability.name)
        }
        return ModelResolution.Success(ResolvedModel(provider, model, source, credentialAvailability))
    }

    /** 聊天使用模型偏好；压缩固定 OFF，不读取聊天模型的思考设置。 */
    fun resolveThinking(model: ModelDefinition, use: ModelUse): ThinkingResolution {
        val source = if (use == ModelUse.COMPRESSION) ThinkingSource.COMPRESSION_DEFAULT else ThinkingSource.MODEL_PREFERENCE
        val intent = if (use == ModelUse.COMPRESSION) ModelThinking(ThinkingMode.OFF) else model.thinking
        val capability = model.thinkingCapability
        val failure = when (intent.mode) {
            ThinkingMode.AUTO -> null
            ThinkingMode.ON -> when {
                capability.control == ThinkingControl.UNKNOWN -> ThinkingFailure.CAPABILITY_UNKNOWN
                capability.control == ThinkingControl.UNSUPPORTED -> ThinkingFailure.CANNOT_ENABLE
                intent.effort != null && intent.effort !in capability.supportedEfforts -> ThinkingFailure.EFFORT_UNSUPPORTED
                else -> null
            }
            ThinkingMode.OFF -> when (capability.control) {
                ThinkingControl.ENABLE_AND_DISABLE -> null
                ThinkingControl.ENABLE_ONLY -> ThinkingFailure.CANNOT_DISABLE
                ThinkingControl.UNKNOWN -> ThinkingFailure.CAPABILITY_UNKNOWN
                ThinkingControl.UNSUPPORTED -> ThinkingFailure.CANNOT_DISABLE
            }
        }
        return if (failure == null) ThinkingResolution.Supported(intent, source)
        else ThinkingResolution.Unsupported(intent, failure, source)
    }

    private fun failure(reason: ModelResolutionFailure, source: SelectionSource?, detail: String? = null) =
        ModelResolution.Failure(ModelResolutionError(reason, source, detail))
}
