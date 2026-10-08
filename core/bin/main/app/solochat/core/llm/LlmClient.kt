package app.solochat.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * 给模型的一条消息。**只用纯文本**：正文里不带任何 JSON 包裹或工具元数据，
 * 这样下一轮直接复用、KV 缓存前缀也最稳（见 docs/01-decisions.md D3）。
 */
data class ApiMessage(
    val role: String,        // "system" | "user" | "assistant"
    val content: String
) {
    init {
        require(role == "system" || role == "user" || role == "assistant") {
            "未知 role: $role"
        }
    }
}

/**
 * 思考强度。档位名与 QQ-agent 的 `thinking.js` 方言表对齐，
 * 实际落成各厂商参数由 [app.solochat.core.llm.ThinkingDialect] 负责。
 */
enum class ThinkingEffort {
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX;

    val wire: String get() = name.lowercase()
}

data class LlmRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double = 0.8,
    val maxTokens: Int = 0,
    val thinkingEffort: ThinkingEffort? = null,

    /** 首字超时（毫秒）。手机上网络不稳，超时时间要给够。 */
    val timeoutMs: Long = 180_000
)

/** 流式增量。 */
sealed class LlmDelta {
    /** 正文增量。 */
    data class Text(val delta: String) : LlmDelta()

    /** 推理过程增量。多数国产模型会给，展示上要能折叠。 */
    data class Reasoning(val delta: String) : LlmDelta()

    /** 本次调用的收尾统计。一定在流结束前出现一次（若厂商给了）。 */
    data class Finished(
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int,
        val reasoningTokens: Int = 0
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
