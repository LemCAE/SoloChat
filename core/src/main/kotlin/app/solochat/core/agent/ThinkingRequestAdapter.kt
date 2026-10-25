package app.solochat.core.agent

import app.solochat.core.llm.ThinkingRequestParameters
import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.*
import java.net.URI

sealed interface ThinkingRequestAdaptation {
    data class Ready(
        val capability: ThinkingCapability,
        val dialect: ThinkingParameterDialect?,
        /** AUTO 时为 null，表示不添加任何思考控制字段。 */
        val parameters: ThinkingRequestParameters?,
        val actualParameters: List<ThinkingParameterSnapshot>
    ) : ThinkingRequestAdaptation

    data class Unsupported(val failure: ThinkingFailure, val detail: String) : ThinkingRequestAdaptation
}

/** 只使用精确官方 endpoint + 精确请求模型 ID 自动声明能力；不推测代理或同名模型。 */
object ThinkingRequestAdapter {
    private data class Declaration(
        val dialect: ThinkingParameterDialect,
        val capability: ThinkingCapability
    )

    private val deepSeekLevels = setOf(ThinkingEffort.LOW, ThinkingEffort.HIGH, ThinkingEffort.MAX)

    fun adapt(provider: ModelProvider, model: ModelDefinition, use: ModelUse): ThinkingRequestAdaptation {
        if (model.providerId != provider.id) return unsupported("模型与提供商不匹配")

        val automatic = if (provider.protocol == ModelProviderProtocol.OPENAI_COMPATIBLE)
            officialDeclaration(provider.baseUrl, model.requestModelId) else null
        val configured = model.thinkingCapability.takeIf {
            it.source == CapabilitySource.USER_CONFIRMED || it.source == CapabilitySource.MODEL_CONFIGURATION
        }
        val capability = configured ?: automatic?.capability ?: model.thinkingCapability
        val dialect = model.thinkingDialectOverride ?: automatic?.dialect
        val intent = if (use == ModelUse.COMPRESSION) ModelThinking(ThinkingMode.OFF) else model.thinking
        if (intent.mode == ThinkingMode.AUTO) {
            return ThinkingRequestAdaptation.Ready(capability, dialect, null, emptyList())
        }

        val resolved = ModelConfigResolver.resolveThinking(
            model.copy(thinking = intent, thinkingCapability = capability), use
        )
        if (resolved is ThinkingResolution.Unsupported) {
            return ThinkingRequestAdaptation.Unsupported(
                resolved.reason, "思考意图无法由已确认能力表达：${resolved.reason}"
            )
        }
        if (dialect == null) return unsupported("尚未确认该模型的思考参数方言")
        if (dialect == ThinkingParameterDialect.OPENAI_REASONING_EFFORT &&
            intent.mode == ThinkingMode.ON && intent.effort == null
        ) return unsupported("该方言需要一个明确支持的 ON 强度档位")

        val enabled = intent.mode == ThinkingMode.ON
        val parameters = ThinkingRequestParameters(dialect, enabled, intent.effort)
        val actual = when (dialect) {
            ThinkingParameterDialect.DEEPSEEK_CHAT_COMPLETIONS -> buildList {
                add(ThinkingParameterSnapshot("thinking.type", if (enabled) "enabled" else "disabled"))
                if (intent.effort != null) add(ThinkingParameterSnapshot("reasoning_effort", intent.effort.wire))
            }
            ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING -> listOf(
                ThinkingParameterSnapshot("enable_thinking", enabled.toString())
            )
            ThinkingParameterDialect.OPENAI_REASONING_EFFORT -> listOf(
                ThinkingParameterSnapshot("reasoning_effort", if (enabled) intent.effort!!.wire else "none")
            )
        }
        return ThinkingRequestAdaptation.Ready(capability, dialect, parameters, actual)
    }

    private fun officialDeclaration(baseUrl: String, requestModelId: String): Declaration? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty().trimEnd('/')

        if (host == "api.deepseek.com" && path in setOf("", "/v1")) {
            if (requestModelId in setOf("deepseek-flash", "deepseek-v4-pro")) {
                return Declaration(
                    ThinkingParameterDialect.DEEPSEEK_CHAT_COMPLETIONS,
                    ThinkingCapability(ThinkingControl.ENABLE_AND_DISABLE, deepSeekLevels,
                        CapabilitySource.PROVIDER_MODEL_DECLARATION)
                )
            }
        }
        if (host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com") &&
            path == "/compatible-mode/v1"
        ) {
            val control = when (requestModelId) {
                "qwen3.8-max", "qwen3.8-flash", "qwen3.7-max", "qwen3.7-plus", "qwen3.7-flash",
                "deepseek-v4.1-flash" -> ThinkingControl.ENABLE_AND_DISABLE
                "qwen3.7-max-preview", "qwen3.8-2.4t-a95b" -> ThinkingControl.ENABLE_ONLY
                else -> return null
            }
            return Declaration(
                ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING,
                ThinkingCapability(control, source = CapabilitySource.PROVIDER_MODEL_DECLARATION)
            )
        }
        return null
    }

    private fun unsupported(detail: String) =
        ThinkingRequestAdaptation.Unsupported(ThinkingFailure.CAPABILITY_UNKNOWN, detail)
}
