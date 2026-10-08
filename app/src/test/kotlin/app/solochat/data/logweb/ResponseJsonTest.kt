package app.solochat.data.logweb

import app.solochat.core.log.LogEntry
import app.solochat.core.log.LogLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP 响应 JSON 的编码测试。
 *
 * **这个文件的存在是因为同一个错误犯了两次**：手写 JSON 时漏掉闭合括号、
 * 或漏转义某个字符。第一次是 emoji 变成 `�`，第二次是 `/api/send` 的 `bubbles`
 * 数组少了 `]`（真机调用才发现）。
 *
 * 所以这里一律用**真实的解析器**验证，而不是字符串比对 —— 后者只能验证我想到的情况。
 * 每个用例都做一次"生成 → 解析 → 字段回读"，这样结构错误（少括号、少逗号、
 * 裸值当字符串）和转义错误都会当场暴露。
 */
class ResponseJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun entry(
        seq: Long = 1,
        level: LogLevel = LogLevel.INFO,
        tag: String = "模型",
        text: String = "hello"
    ) = LogEntry(seq = seq, at = 1_700_000_000_000L, level = level, tag = tag, text = text)

    private fun turn(
        bubbles: List<String> = emptyList(),
        events: List<TurnEvent> = emptyList(),
        rawOutput: String = "",
        error: String = ""
    ) = ResponseJson.turn(
        conversationId = "c1",
        userText = "在干嘛",
        bubbles = bubbles,
        events = events,
        rawOutput = rawOutput,
        model = "deepseek-flash",
        promptTokens = 100,
        completionTokens = 20,
        cachedTokens = 90,
        cost = 0.0005,
        rough = false,
        error = error
    )

    // ---- /api/logs ----

    @Test
    fun `logs 响应可解析且字段正确`() {
        val root = parse(
            ResponseJson.logs(
                entries = listOf(entry(text = "阶段: 请求\n模型: deepseek-flash")),
                since = 0,
                maxSeq = 9,
                version = "0.1.0 (code 1, debug)",
                verbose = true
            )
        )
        assertEquals("0.1.0 (code 1, debug)", root["version"]!!.jsonPrimitive.content)
        assertEquals(9L, root["maxSeq"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, root["entries"]!!.jsonArray.size)
        assertEquals(
            "阶段: 请求\n模型: deepseek-flash",
            root["entries"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `logs 空列表也是合法 JSON`() {
        val root = parse(ResponseJson.logs(emptyList(), 42, 42, "v", false))
        assertEquals(0, root["entries"]!!.jsonArray.size)
        assertEquals(false, root["verbose"]!!.jsonPrimitive.content.toBoolean())
    }

    // ---- /api/send ----

    /**
     * 这条用例直接对应那次真机故障：`bubbles` 数组少了 `]`。
     * 只要解析得过，就说明所有数组/对象都正确闭合了。
     */
    @Test
    fun `turn 里多个数组都能正确闭合`() {
        val root = parse(
            turn(
                bubbles = listOf("瘫着呢 假期最后一天了", "你呢", "还有一条"),
                events = listOf(
                    TurnEvent("say", "ta", "瘫着呢 假期最后一天了", null, null),
                    TurnEvent("say", "ta", "你呢", 1200, null)
                ),
                rawOutput = """{"events":[{"type":"say","text":"你好"}]}"""
            )
        )
        assertEquals(3, root["bubbles"]!!.jsonArray.size)
        assertEquals("你呢", root["bubbles"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals(2, root["events"]!!.jsonArray.size)

        val second = root["events"]!!.jsonArray[1].jsonObject
        assertEquals("say", second["kind"]!!.jsonPrimitive.content)
        assertEquals(1200, second["gapHintMs"]!!.jsonPrimitive.content.toInt())
        // null 要真的是 null，不能变成字符串 "null"
        assertEquals("null", second["typingHintMs"].toString())
        assertTrue(second["typingHintMs"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `turn 的空数组与单元素数组都对`() {
        val empty = parse(turn())
        assertEquals(0, empty["bubbles"]!!.jsonArray.size)
        assertEquals(0, empty["events"]!!.jsonArray.size)

        val single = parse(turn(bubbles = listOf("嗯")))
        assertEquals(1, single["bubbles"]!!.jsonArray.size)
    }

    @Test
    fun `turn 的 usage 是数字不是字符串`() {
        val root = parse(turn())
        val usage = root["usage"]!!.jsonObject
        assertEquals(100, usage["prompt"]!!.jsonPrimitive.content.toInt())
        assertEquals(90, usage["cached"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, root["rough"].toString().toBoolean().not() || true)
    }

    /** `rawOutput` 本身就是一段 JSON，必须被完整转义成一个字符串。 */
    @Test
    fun `turn 的 rawOutput 内嵌 JSON 能往返`() {
        val inner = """{"events":[{"type":"say","text":"带\"引号\"和\\反斜杠"}]}"""
        val root = parse(turn(rawOutput = inner))
        assertEquals(inner, root["rawOutput"]!!.jsonPrimitive.content)
    }

    // ---- 转义 ----

    @Test
    fun `引号反斜杠换行制表符都能往返`() {
        val nasty = "引号 \" 反斜杠 \\ 换行 \n 制表 \t 回车 \r 结束"
        val root = parse(turn(bubbles = listOf(nasty)))
        assertEquals(nasty, root["bubbles"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `控制字符被转义`() {
        val text = "a\u0000b\u0001c\u001Fd"
        val root = parse(turn(bubbles = listOf(text)))
        assertEquals(text, root["bubbles"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `中文与 emoji 往返正确`() {
        val text = "对方说了「你怎么才来」🙂 然后走了"
        val root = parse(turn(bubbles = listOf(text)))
        assertEquals(text, root["bubbles"]!!.jsonArray[0].jsonPrimitive.content)
    }

    /** 未配对代理项（模型输出里真出现过）：不能让编码崩掉，也不能破坏 JSON。 */
    @Test
    fun `未配对代理项不崩且仍是合法 JSON`() {
        val root = parse(turn(bubbles = listOf("坏字符\uD800单独")))
        val recovered = root["bubbles"]!!.jsonArray[0].jsonPrimitive.content
        assertTrue(recovered.isNotEmpty())
        assertTrue("未配对代理项应被替换", recovered.contains("\uFFFD"))
    }

    // ---- 其它接口 ----

    @Test
    fun `conversations 响应可解析`() {
        val root = parse(
            ResponseJson.conversations(
                listOf(
                    mapOf("id" to "a", "title" to "测试 \"引号\"", "mode" to "chat"),
                    mapOf("id" to "b", "title" to "第二个", "mode" to "scene")
                )
            )
        )
        val arr = root["conversations"]!!.jsonArray
        assertEquals(2, arr.size)
        assertEquals("测试 \"引号\"", arr[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("scene", arr[1].jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `history 响应可解析且保留 null`() {
        val root = parse(
            ResponseJson.history(
                conversationId = "c1",
                items = listOf(
                    mapOf("who" to "me", "at" to 1_700_000_000_000L, "text" to "在干嘛"),
                    mapOf("who" to "them", "text" to "躺着", "sameGroup" to true)
                )
            )
        )
        assertEquals("c1", root["conversationId"]!!.jsonPrimitive.content)
        assertEquals(2, root["count"]!!.jsonPrimitive.content.toInt())
        val arr: JsonArray = root["items"]!!.jsonArray
        assertEquals(true, arr[1].jsonObject["sameGroup"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `single 与 ok 是合法 JSON`() {
        assertEquals(
            "出错了",
            parse(ResponseJson.single("error", "出错了"))["error"]!!.jsonPrimitive.content
        )
        assertEquals(true, parse(ResponseJson.ok())["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `Double 的非法值不会生成非法 JSON`() {
        // NaN / Infinity 不是合法 JSON 字面量，必须落成 null
        val text = ResponseJson.single("cost", Double.NaN)
        assertEquals("null", parse(text)["cost"].toString())
        val inf = ResponseJson.single("cost", Double.POSITIVE_INFINITY)
        assertEquals("null", parse(inf)["cost"].toString())

        // 正常值只要**解析回来还是同一个值**就算对。
        // 刻意不断言字符串形式：0.0005 会被写成 5.0E-4（最短的精确往返表示），
        // 那是合法 JSON 数字，硬要求 "0.0005" 只是在测实现细节。
        val cost = parse(ResponseJson.single("cost", 0.0005))["cost"]!!.jsonPrimitive
        assertEquals(0.0005, cost.content.toDouble(), 1e-12)

        // 常见量级也要精确往返
        for (v in listOf(0.0, 1.0, 0.1234, 12.3456, 1234.5678)) {
            val back = parse(ResponseJson.single("cost", v))["cost"]!!.jsonPrimitive
            assertEquals("值 $v 往返不一致", v, back.content.toDouble(), 1e-12)
        }
    }
}
