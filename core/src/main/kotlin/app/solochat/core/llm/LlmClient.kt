package app.solochat.core.llm

import kotlinx.coroutines.flow.Flow
import app.solochat.core.model.ThinkingParameterDialect

/**
 * 给模型的一条消息。**只用纯文本**：正文里不带任何 JSON 包裹或工具元数据，
 * 这样下一轮直接复用、KV 缓存前缀也最稳（见 docs/01-decisions.md D3）。
 */
data class ApiMessage(
    val role: String,        // "system" | "user" | "assistant"
    val content: String,
    /** 仅用于图片请求；纯文本仍编码为原有字符串形状。 */
    val images: List<ApiImage> = emptyList()
) {
    init {
        require(role == "system" || role == "user" || role == "assistant") {
            "未知 role: $role"
        }
        require(images.isEmpty() || role == "user") { "图片只能附在 user 消息上" }
    }

    override fun toString(): String = "ApiMessage(role=$role, content=<隐藏>, images=${images.size})"
}

/** 调用请求内联的已处理图片字节；日志输出不包含 data URL。 */
class ApiImage(val mimeType: String, val dataUrl: String) {
    init {
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp"))
        require(dataUrl.startsWith("data:$mimeType;base64,") && dataUrl.length > dataUrl.indexOf(',') + 1)
    }
    override fun toString(): String = "ApiImage($mimeType, <隐藏>)"
}

/**
 * 思考强度。档位名与 QQ-agent 的 `thinking.js` 方言表对齐，
 * 实际落成各厂商参数由 [app.solochat.core.llm.ThinkingDialect] 负责。
 */
enum class ThinkingEffort {
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX;

    val wire: String get() = name.lowercase()
}

/** 单次请求的凭据快照；禁止通过默认 toString 输出密钥。 */
class RequestEndpoint(val baseUrl: String, val apiKey: String) {
    override fun toString(): String = "RequestEndpoint(<已隐藏>)"
}

/** 经能力预检后可直接编码的思考控制；null 表示 AUTO 且不发送控制字段。 */
data class ThinkingRequestParameters(
    val dialect: ThinkingParameterDialect,
    val enabled: Boolean,
    val effort: ThinkingEffort? = null
) {
    init {
        require(enabled || effort == null) { "关闭思考时不能携带强度档位" }
        require(effort != ThinkingEffort.OFF) { "OFF 不是思考强度档位" }
        require(dialect != ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING || effort == null) {
            "百炼开关方言没有已实现的强度字段"
        }
        require(dialect != ThinkingParameterDialect.OPENAI_REASONING_EFFORT || !enabled || effort != null) {
            "OpenAI reasoning_effort 方言的 ON 必须携带明确强度"
        }
    }
}

data class LlmRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double = 0.8,
    val maxTokens: Int = 0,
    val thinkingEffort: ThinkingEffort? = null,
    /** 新模型调用使用已校验的协议参数；保留旧 thinkingEffort 以兼容现有入口。 */
    val thinkingParameters: ThinkingRequestParameters? = null,

    /** 首字超时（毫秒）。手机上网络不稳，超时时间要给够。 */
    val timeoutMs: Long = 180_000,
    val endpoint: RequestEndpoint? = null
)

/** 流式增量。 */
sealed class LlmDelta {
    /** 正文增量。 */
    data class Text(val delta: String) : LlmDelta()

    /** 推理过程增量。多数国产模型会给，展示上要能折叠。 */
    data class Reasoning(val delta: String) : LlmDelta()

    /** 本次调用的收尾统计。一定在流结束前出现一次（若厂商给了）。 */
    data class Finished(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val cachedTokens: Int? = null,
        val reasoningTokens: Int? = null,
        /** 仅保存接口 usage 对象，不包含消息正文或凭据。 */
        val usageFrames: List<String> = emptyList()
    ) : LlmDelta()

    /** 网关没有返回 usage —— 必须是显式的一等公民，UI 上要标"估算"。 */
    data object UsageMissing : LlmDelta()
}

/**
 * 模型客户端接口。
 *
 * 做成接口而不是直接写实现，是为了让"某厂商原生协议"（Anthropic 等）
 * 和测试用的假客户端都能插进来，而不用改上层 Agent。
 */
interface LlmClient {
    /**
     * 流式对话。**正常结束 = 流走完，不抛异常**；
     * 出错时抛 [LlmException]，调用方按 [LlmErrorKind] 决定重试或提示。
     */
    fun chatStream(request: LlmRequest): Flow<LlmDelta>

    /** 拉取可用模型列表（设置页"获取列表"用）。 */
    suspend fun listModels(): List<String>

    /** 发个最小请求验连通性，返回延迟毫秒数。 */
    suspend fun ping(model: String): Long
}

class LlmException(val error: LlmError) : Exception(
    "${error.kind}: ${error.message}"
)
