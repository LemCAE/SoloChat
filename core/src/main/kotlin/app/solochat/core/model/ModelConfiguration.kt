package app.solochat.core.model

import app.solochat.core.llm.ThinkingEffort

/** 提供商协议。首版只承载现有 OpenAI 兼容请求。 */
enum class ModelProviderProtocol {
    OPENAI_COMPATIBLE
}

/** 稳定的提供商身份。keyId 只是 SecretStore 槽位，不包含凭据。 */
data class ModelProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyId: String,
    val protocol: ModelProviderProtocol,
    val enabled: Boolean = true
)

/** 稳定模型身份与实际请求名分开保存；请求名保留原大小写。 */
data class ModelReference(val providerId: String, val modelId: String)

data class ModelDefinition(
    val id: String,
    val providerId: String,
    val displayName: String,
    val requestModelId: String,
    val enabled: Boolean = true,
    val thinking: ModelThinking = ModelThinking(),
    val thinkingCapability: ThinkingCapability = ThinkingCapability.unknown(),
    /** 用户明确指定的参数方言；空值表示按可信厂商声明解析。 */
    val thinkingDialectOverride: ThinkingParameterDialect? = null,
    val visionCapability: VisionCapability = VisionCapability.unknown()
) {
    val reference: ModelReference get() = ModelReference(providerId, id)
}

/** AUTO 不发送控制字段；ON/OFF 是明确意图。 */
enum class ThinkingMode { AUTO, ON, OFF }

data class ModelThinking(
    val mode: ThinkingMode = ThinkingMode.AUTO,
    val effort: ThinkingEffort? = null
) {
    init {
        require(mode == ThinkingMode.ON || effort == null) {
            "只有 ON 思考模式可以指定强度"
        }
        require(effort != ThinkingEffort.OFF) { "OFF 不是思考强度档位" }
    }
}

enum class ThinkingControl { ENABLE_AND_DISABLE, ENABLE_ONLY, UNSUPPORTED, UNKNOWN }
enum class CapabilitySource { MODEL_CONFIGURATION, PROVIDER_PROTOCOL, PROVIDER_MODEL_DECLARATION, USER_CONFIRMED, UNKNOWN }

enum class VisionControl { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** 图片输入能力必须来自精确声明或用户确认；未知时不得尝试图片请求。 */
data class VisionCapability(
    val control: VisionControl,
    val source: CapabilitySource = CapabilitySource.UNKNOWN
) {
    companion object {
        fun unknown() = VisionCapability(VisionControl.UNKNOWN)
    }
}

/** 首版仅覆盖现有 OpenAI-compatible 客户端实际能编码的三种思考方言。 */
enum class ThinkingParameterDialect {
    DEEPSEEK_CHAT_COMPLETIONS,
    DASHSCOPE_ENABLE_THINKING,
    OPENAI_REASONING_EFFORT
}

/** 思考开关能力与强度档位分开记录，未知能力不会按模型名称推测。 */
data class ThinkingCapability(
    val control: ThinkingControl,
    val supportedEfforts: Set<ThinkingEffort> = emptySet(),
    val source: CapabilitySource = CapabilitySource.UNKNOWN
) {
    init {
        require(ThinkingEffort.OFF !in supportedEfforts) { "OFF 是开关意图，不是强度档位" }
    }

    companion object {
        fun unknown() = ThinkingCapability(ThinkingControl.UNKNOWN)
    }
}

/** 用途偏好：未设置时继承全局，待确认引用不能被当作未设置。 */
sealed interface ModelSelection {
    data object Inherit : ModelSelection
    data class Explicit(val model: ModelReference) : ModelSelection
    data class NeedsConfirmation(
        val model: ModelReference?,
        val reason: String
    ) : ModelSelection
}

enum class ModelUse { CHAT, COMPRESSION, TITLE }

/** 版本化、非秘密的提供商/模型目录与用途选择。 */
data class ModelConfiguration(
    val version: Int,
    val providers: List<ModelProvider>,
    val models: List<ModelDefinition>,
    val globalDefault: ModelSelection,
    val chatSelection: ModelSelection = ModelSelection.Inherit,
    val compressionSelection: ModelSelection = ModelSelection.Inherit,
    val titleSelection: ModelSelection = ModelSelection.Inherit
) {
    init {
        require(version > 0) { "配置版本必须为正数" }
        require(globalDefault !is ModelSelection.Inherit) { "全局默认必须明确选定或待确认" }
        require(providers.map { it.id }.toSet().size == providers.size) { "提供商 ID 重复" }
        require(models.map { it.id }.toSet().size == models.size) { "模型 ID 重复" }
    }

    fun selectionFor(use: ModelUse): ModelSelection = when (use) {
        ModelUse.CHAT -> chatSelection
        ModelUse.COMPRESSION -> compressionSelection
        ModelUse.TITLE -> titleSelection
    }
}

/** 会话级绑定只覆盖聊天用途。 */
data class ConversationModelBinding(val selection: ModelSelection)

enum class SelectionSource { CONVERSATION, CHAT_PREFERENCE, COMPRESSION_PREFERENCE, TITLE_PREFERENCE, GLOBAL_DEFAULT }
enum class CredentialAvailability { AVAILABLE, MISSING, UNKNOWN }

/** 冻结到单次调用的非秘密配置快照字段。实际参数由请求适配任务填入。 */
data class CallConfigSnapshot(
    val version: Int,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelDisplayName: String,
    val requestModelId: String,
    val use: ModelUse,
    val selectionSource: SelectionSource,
    val thinking: ModelThinking,
    val thinkingSource: ThinkingSource,
    val actualThinkingParameters: List<ThinkingParameterSnapshot> = emptyList()
)

enum class ThinkingSource { MODEL_PREFERENCE, COMPRESSION_DEFAULT }

data class ThinkingParameterSnapshot(val name: String, val value: String)
