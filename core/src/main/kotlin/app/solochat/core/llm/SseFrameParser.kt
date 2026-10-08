package app.solochat.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE 分帧解析器 —— 增量喂字节，吐出完整帧。
 *
 * **为什么单独一个类而不是几行正则**：网络把数据切成什么样完全随机，
 * 一次 `data: {...}` 被切成三段的概率不低，而这类 bug 表现为"偶尔丢一条消息"，
 * 极难复现。所以这里把"字节缓冲 + 按空行分帧 + 非法 JSON 静默跳过"集中在一处，
 * 用穷举式的单元测试锁住（见 SseFrameParserTest）。
 *
 * 刻意忽略的东西（都是真实网关会发的）：
 * - 以 `:` 开头的注释行（keep-alive 心跳）
 * - `event:` / `id:` / `retry:` 等其他字段
 * - 不是 `data:` 开头的行
 */
class SseFrameParser {

    private val buffer = StringBuilder()

    /** 已收下但还没提交的 `data:` 行内容（SSE 规范允许多行 data）。 */
    private val pendingData = mutableListOf<String>()

    /** 已收到的 `data:` 载荷（不含前缀，已 trim）。`[DONE]` 也在这里返回，由调用方判断。 */
    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val frames = mutableListOf<String>()

        while (true) {
            // 拆成两个具名变量而不是解构：`indexOfFrameBreak` 返回 null 表示
            // "还凑不出一帧"，这时必须原样保留缓冲等后续数据。
            val frameBreak = indexOfFrameBreak(buffer) ?: break
            val rawFrame = buffer.substring(0, frameBreak.first)
            // 把分隔符一起吃掉；\r\n\r\n 要跳 4 个字符
            buffer.delete(0, frameBreak.second)
            flushPending(rawFrame)?.let { frames += it }
        }
        return frames
    }

    /**
     * 喂**一行**（不含换行符）。行是流式读取的自然单位：HTTP 客户端本来就是按行读的，
     * 这样调用方可以边读边处理，不必等空行才拿到内容。
     *
     * 为什么需要它：只靠 [feed] 的话，调用方必须把每次收到的片段都存起来凑空行；
     * 而流式场景下我们其实很清楚"现在拿到了一整行"。
     */
    fun feedLine(line: String): List<String> {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isEmpty()) {
            // 空行 = 一帧结束：把攒下的 data 行合并提交
            val payload = pendingData.joinToString("\n").trim()
            pendingData.clear()
            return if (payload.isEmpty()) emptyList() else listOf(payload)
        }
        if (trimmed.startsWith(":")) return emptyList()          // 注释 / 心跳
        if (!trimmed.startsWith("data:")) return emptyList()     // event: / id: / retry:
        pendingData += trimmed.removePrefix("data:").removePrefix(" ")
        return emptyList()
    }

    /**
     * 流结束时调用。处理"最后一帧没有跟空行"的情况 ——
     * 不少网关发完最后一条就不补空行，直接关连接。
     */
    fun flush(): List<String> {
        val rest = buffer.toString()
        buffer.setLength(0)
        val out = mutableListOf<String>()
        if (rest.isNotBlank()) extractDataPayload(rest)?.let { out += it }
        // 行模式下攒下的 data 行（还没遇到空行）也要交出去
        val pending = pendingData.joinToString("\n").trim()
        pendingData.clear()
        if (pending.isNotEmpty() && pending !in out) out += pending
        return out
    }

    /**
     * 提交一整帧（可能含多行 data）并清掉暂存。
     * 返回 null 表示这一帧里没有 `data:` 内容（只有注释/心跳）。
     */
    private fun flushPending(rawFrame: String): String? {
        val fromFrame = extractDataPayload(rawFrame)
        val pending = pendingData.joinToString("\n").trim()
        pendingData.clear()
        return when {
            fromFrame != null && pending.isNotEmpty() -> "$fromFrame\n$pending"
            fromFrame != null -> fromFrame
            pending.isNotEmpty() -> pending
            else -> null
        }
    }

    /** 取出所有 `data:` 行的内容并按换行拼接（SSE 规范允许多行 data）。 */
    private fun extractDataPayload(rawFrame: String): String? {
        if (rawFrame.isBlank()) return null
        val parts = mutableListOf<String>()
        for (line in rawFrame.split('\n')) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith(":")) continue              // 注释 / 心跳
            if (!trimmed.startsWith("data:")) continue         // event: / id: / retry:
            // 规范里 `data:` 后面可以跟一个空格，也可以直接跟内容
            parts += trimmed.removePrefix("data:").removePrefix(" ")
        }
        if (parts.isEmpty()) return null
        val joined = parts.joinToString("\n").trim()
        return joined.ifEmpty { null }
    }

    /**
     * 找帧分隔符（空行）。返回「帧内容结束下标」与「分隔符结束下标」。
     * 同时支持 `\n\n` 与 `\r\n\r\n`，并处理 `\n\r\n` 这种混用（少见但存在）。
     */
    private fun indexOfFrameBreak(sb: StringBuilder): Pair<Int, Int>? {
        var i = 0
        val n = sb.length
        while (i < n) {
            val c = sb[i]
            if (c == '\n') {
                // \n\n
                if (i + 1 < n && sb[i + 1] == '\n') return i to (i + 2)
                // \n\r\n
                if (i + 2 < n && sb[i + 1] == '\r' && sb[i + 2] == '\n') return i to (i + 3)
            }
            i++
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// OpenAI 兼容响应体的取值
//
// 这些字段名在不同网关之间**有差异**（同一个语义可能叫 reasoning_content、
// reasoning、thinking），所以取值集中在这里，一处改全部受益。
// ---------------------------------------------------------------------------

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** 解析一帧的 JSON。**解析失败返回 null 而不是抛异常** —— 见类注释。 */
fun parseSseJson(payload: String): JsonObject? = runCatching {
    json.parseToJsonElement(payload).jsonObject
}.getOrNull()

/** `choices[0].delta.content`，取不到返回 null。 */
fun JsonObject.deltaContent(): String? {
    val choices = this["choices"]?.jsonArrayOrNull() ?: return null
    val first = choices.firstOrNull()?.jsonObjectOrNull() ?: return null
    val delta = first["delta"]?.jsonObjectOrNull()
    // 非流式的 message.content 也兼容一下（部分网关在流里混发完整消息）
    val text = delta?.get("content")?.stringOrNull()
        ?: first["message"]?.jsonObjectOrNull()?.get("content")?.stringOrNull()
    return text
}

/** `choices[0].delta.reasoning_content` / `.reasoning` / `.thinking`。 */
fun JsonObject.deltaReasoning(): String? {
    val choices = this["choices"]?.jsonArrayOrNull() ?: return null
    val delta = choices.firstOrNull()?.jsonObjectOrNull()?.get("delta")?.jsonObjectOrNull()
        ?: return null
    return delta["reasoning_content"]?.stringOrNull()
        ?: delta["reasoning"]?.stringOrNull()
        ?: delta["thinking"]?.stringOrNull()
}

/** `choices[0].finish_reason`。 */
fun JsonObject.finishReason(): String? {
    val choices = this["choices"]?.jsonArrayOrNull() ?: return null
    return choices.firstOrNull()?.jsonObjectOrNull()?.get("finish_reason")?.stringOrNull()
}

/** `usage`。字段名各家不同，全部兼容。取不到返回 null（= 要标"估算"）。 */
fun JsonObject.usageOrNull(): LlmDelta.Finished? {
    val usage = this["usage"]?.jsonObjectOrNull() ?: return null
    fun pick(vararg keys: String): Int {
        for (k in keys) usage[k]?.jsonPrimitive?.intOrNull?.let { return it }
        return 0
    }
    return LlmDelta.Finished(
        promptTokens = pick("prompt_tokens", "input_tokens", "promptTokens"),
        completionTokens = pick("completion_tokens", "output_tokens", "completionTokens"),
        cachedTokens = pick(
            "prompt_cache_hit_tokens",           // DeepSeek
            "cached_tokens",                     // OpenAI
            "prompt_tokens_details.cached_tokens"
        ).let { direct ->
            if (direct > 0) direct else {
                // OpenAI 嵌套写法：prompt_tokens_details.cached_tokens
                usage["prompt_tokens_details"]?.jsonObjectOrNull()
                    ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
            }
        },
        reasoningTokens = pick(
            "completion_tokens_details.reasoning_tokens",
            "reasoning_tokens"
        ).let { direct ->
            if (direct > 0) direct else {
                usage["completion_tokens_details"]?.jsonObjectOrNull()
                    ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0
            }
        }
    )
}

/** 流里出现的错误对象：`{"error": {"message": "..."}}`。 */
fun JsonObject.errorMessage(): String? {
    val err = this["error"] ?: return null
    return err.jsonObjectOrNull()?.get("message")?.stringOrNull()
        ?: err.stringOrNull()
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
    runCatching { jsonArray }.getOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
    runCatching { jsonObject }.getOrNull()

private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull
