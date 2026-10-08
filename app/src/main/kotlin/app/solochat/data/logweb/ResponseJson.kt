package app.solochat.data.logweb

import app.solochat.core.log.LogEntry

/**
 * HTTP 响应的 JSON 编码。
 *
 * **这个文件是为了堵住一个已经犯过两次的错误**：手写 JSON 时漏掉闭合括号、
 * 或漏转义某个字符。第一次是 emoji 被替换成 `�`，第二次是 `bubbles` 数组少了 `]`
 * —— 症状都是「接口看起来在工作，但浏览器/调用方解析失败」，而且要连到真机才发现。
 *
 * 所以这里的原则是：**任何要拼 JSON 的地方都走这个文件**，它全部是纯函数，
 * 有 `ResponseJsonTest` 用**真实的解析器**逐条验证往返。
 * 手写而不是用 kotlinx.serialization 序列化：响应结构很小，
 * 而 core 层的模型不该为了传输格式加 `@Serializable`（那会把传输细节渗进领域模型）。
 */
object ResponseJson {

    /** 日志列表（`/api/logs`）。 */
    fun logs(
        entries: List<LogEntry>,
        since: Long,
        maxSeq: Long,
        version: String,
        verbose: Boolean
    ): String = buildString {
        append("{\"version\":").append(quote(version))
        append(",\"verbose\":").append(verbose)
        append(",\"since\":").append(since)
        append(",\"maxSeq\":").append(maxSeq)
        append(",\"entries\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append("{\"seq\":").append(entry.seq)
            append(",\"at\":").append(entry.at)
            append(",\"clock\":").append(quote(entry.clock))
            append(",\"level\":").append(quote(entry.level.name))
            append(",\"tag\":").append(quote(entry.tag))
            append(",\"text\":").append(quote(entry.text))
            append('}')
        }
        append("]}")
    }

    /** 会话列表（`/api/conversations`）。 */
    fun conversations(items: List<Map<String, Any?>>): String =
        objectArray("conversations", items)

    /** 历史（`/api/history`）。 */
    fun history(conversationId: String, items: List<Map<String, Any?>>): String = buildString {
        append("{\"conversationId\":").append(quote(conversationId))
        append(",\"count\":").append(items.size)
        append(",\"items\":").append(arrayOfObjects(items))
        append('}')
    }

    /**
     * 一轮的结果（`/api/send`）。
     *
     * 参数逐个传而不是传 TestGateway.TurnResult：让这个文件不依赖那一层，
     * 测试里也不必构造整个对象。
     */
    fun turn(
        conversationId: String,
        userText: String,
        bubbles: List<String>,
        events: List<TurnEvent>,
        rawOutput: String,
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int,
        cost: Double,
        rough: Boolean,
        error: String
    ): String = buildString {
        append("{\"conversationId\":").append(quote(conversationId))
        append(",\"userText\":").append(quote(userText))

        append(",\"bubbles\":[")
        bubbles.forEachIndexed { index, bubble ->
            if (index > 0) append(',')
            append(quote(bubble))
        }
        append(']')

        append(",\"events\":[")
        events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append('{')
            append("\"kind\":").append(quote(event.kind))
            append(",\"speaker\":").append(quote(event.speaker))
            append(",\"text\":").append(quote(event.text))
            append(",\"gapHintMs\":").append(event.gapHintMs ?: "null")
            append(",\"typingHintMs\":").append(event.typingHintMs ?: "null")
            append('}')
        }
        append(']')

        append(",\"rawOutput\":").append(quote(rawOutput))
        append(",\"model\":").append(quote(model))
        append(",\"usage\":{\"prompt\":").append(promptTokens)
        append(",\"completion\":").append(completionTokens)
        append(",\"cached\":").append(cachedTokens)
        append('}')
        append(",\"cost\":").append(cost)
        append(",\"rough\":").append(rough)
        append(",\"error\":").append(quote(error))
        append('}')
    }

    /** 单个字段的对象，例如 `{"error":"..."}`。 */
    fun single(key: String, value: Any?): String =
        "{" + quote(key) + ":" + value(value) + "}"

    fun ok(): String = """{"ok":true}"""

    // ------------------------------------------------------------------

    private fun objectArray(key: String, items: List<Map<String, Any?>>): String =
        "{" + quote(key) + ":" + arrayOfObjects(items) + "}"

    private fun arrayOfObjects(items: List<Map<String, Any?>>): String = buildString {
        append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append('{')
            var first = true
            for ((k, v) in item) {
                if (!first) append(',')
                first = false
                append(quote(k)).append(':').append(value(v))
            }
            append('}')
        }
        append(']')
    }

    /**
     * 只允许 JSON 原生的"裸值"，其余一律走 [quote]。
     *
     * 关于 Double：直接用 `toString()`，**不做四舍五入**。
     * Kotlin 的 `Double.toString()` 给出的是"能精确往返的最短表示"，
     * 所以 0.0005 会写成 `5.0E-4` —— 这是合法的 JSON 数字，调用方解析成同样的值。
     * 若为了好看去格式化（比如 "%.4f"），既有精度损失，又可能把很小的成本压成 0.0000。
     * 非有限值（NaN / Infinity）不是合法 JSON 字面量，必须落成 null。
     */
    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Float -> if (v.isFinite()) v.toString() else "null"
        else -> quote(v.toString())
    }

    /**
     * JSON 字符串转义。
     *
     * ⚠️ 这里有个容易写错的地方（第一版就错了）：**配对的代理项不能动**。
     * emoji 与很多 CJK 扩展字在 UTF-16 里是"高代理 + 低代理"两个 char，
     * 如果见到代理项就替换，所有 emoji 都会变成 `�`。只有**落单的**代理项才是坏数据。
     */
    fun quote(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 -> append("\\u%04x".format(ch.code))
                ch.isHighSurrogate() -> {
                    val next = value.getOrNull(index + 1)
                    if (next != null && next.isLowSurrogate()) {
                        append(ch); append(next); index += 1
                    } else {
                        append('\uFFFD')
                    }
                }
                ch.isLowSurrogate() -> append('\uFFFD')
                else -> append(ch)
            }
            index += 1
        }
        append('"')
    }
}

/** `/api/send` 返回里的一个事件。 */
data class TurnEvent(
    val kind: String,
    val speaker: String,
    val text: String,
    val gapHintMs: Int?,
    val typingHintMs: Int?
)
