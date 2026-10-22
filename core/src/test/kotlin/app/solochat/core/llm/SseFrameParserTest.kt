package app.solochat.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 分帧解析的回归测试。
 *
 * 这批测试的重点不是"能解析正常数据"，而是**穷举各种被网络切碎的方式** ——
 * 因为这类 bug 表现为"偶尔丢一条消息"，线上极难复现。
 */
class SseFrameParserTest {
    @Test
    fun `部分 usage 缺少的输出与缓存不能伪装成零`() {
        val usage = parseSseJson("""{"usage":{"prompt_tokens":123}}""")!!.usageOrNull()!!
        assertEquals(123, usage.promptTokens)
        assertNull(usage.completionTokens)
        assertNull(usage.cachedTokens)
        assertNull(usage.reasoningTokens)
    }

    @Test
    fun `非法 usage 字段不能导致整帧崩溃或被记成零`() {
        val usage = parseSseJson("""{"usage":{"prompt_tokens":{},"completion_tokens":-1}}""")!!.usageOrNull()!!
        assertNull(usage.promptTokens)
        assertNull(usage.completionTokens)
    }

    @Test
    fun `接口原始usage保留未知明细并合并不同分片`() {
        val first = parseSseJson("""{"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":20},"cache_creation_input_tokens":8}}""")!!.usageOrNull()!!
        val last = parseSseJson("""{"usage":{"output_tokens":10,"output_tokens_details":{"reasoning_tokens":3}}}""")!!.usageOrNull()!!
        val merged = app.solochat.core.model.mergeUsage(first, last)
        assertEquals(100, merged.promptTokens)
        assertEquals(20, merged.cachedTokens)
        assertEquals(10, merged.completionTokens)
        assertEquals(3, merged.reasoningTokens)
        assertEquals(2, merged.usageFrames.size)
        assertTrue(merged.usageFrames.first().contains("cache_creation_input_tokens"))
    }

    private fun frames(vararg chunks: String): List<String> {
        val p = SseFrameParser()
        val out = mutableListOf<String>()
        for (c in chunks) out += p.feed(c)
        out += p.flush()
        return out
    }

    @Test
    fun `单帧单块`() {
        assertEquals(
            listOf("""{"a":1}"""),
            frames("data: {\"a\":1}\n\n")
        )
    }

    @Test
    fun `单块里有多帧`() {
        val out = frames(
            "data: {\"n\":1}\n\ndata: {\"n\":2}\n\ndata: [DONE]\n\n"
        )
        assertEquals(listOf("""{"n":1}""", """{"n":2}""", "[DONE]"), out)
    }

    @Test
    fun `一个 JSON 被切成两半也能拼回来`() {
        val out = frames("data: {\"a\":", "1}\n\n")
        assertEquals(listOf("""{"a":1}"""), out)
    }

    @Test
    fun `一个 JSON 被切成四段也能拼回来`() {
        val out = frames("da", "ta: {\"a\"", ":1}", "\n\n")
        assertEquals(listOf("""{"a":1}"""), out)
    }

    @Test
    fun `分隔符本身被切开`() {
        // “\n” 和 “\n” 分属两个 chunk
        val out = frames("data: {\"a\":1}\n", "\ndata: {\"a\":2}\n\n")
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), out)
    }

    @Test
    fun `CRLF 分隔符`() {
        assertEquals(
            listOf("""{"a":1}"""),
            frames("data: {\"a\":1}\r\n\r\n")
        )
    }

    @Test
    fun `CRLF 被切开`() {
        assertEquals(
            listOf("""{"a":1}"""),
            frames("data: {\"a\":1}\r", "\n\r\n")
        )
    }

    @Test
    fun `混用 LF 与 CRLF`() {
        assertEquals(
            listOf("""{"a":1}"""),
            frames("data: {\"a\":1}\n\r\n")
        )
    }

    @Test
    fun `data 后没有空格`() {
        assertEquals(listOf("""{"a":1}"""), frames("data:{\"a\":1}\n\n"))
    }

    @Test
    fun `data 后有多个空格`() {
        // 规范只允许一个可选空格，多出来的属于内容，但要能容忍不崩
        val out = frames("data:   {\"a\":1}\n\n")
        assertEquals(1, out.size)
        assertTrue(out[0].contains("\"a\""))
    }

    @Test
    fun `注释行与心跳被忽略`() {
        val out = frames(": keep-alive\n\ndata: {\"a\":1}\n\n: ping\n\n")
        assertEquals(listOf("""{"a":1}"""), out)
    }

    @Test
    fun `event 与 id 等字段被忽略`() {
        val out = frames("event: message\nid: 7\nretry: 1000\ndata: {\"a\":1}\n\n")
        assertEquals(listOf("""{"a":1}"""), out)
    }

    @Test
    fun `多行 data 按换行拼接`() {
        val out = frames("data: line1\ndata: line2\n\n")
        assertEquals(listOf("line1\nline2"), out)
    }

    @Test
    fun `最后一条没有尾随空行 - 靠 flush 兜住`() {
        val out = frames("data: {\"a\":1}\n\ndata: [DONE]")
        assertEquals(listOf("""{"a":1}""", "[DONE]"), out)
    }

    @Test
    fun `完全没有数据的流 - flush 返回空`() {
        assertEquals(emptyList<String>(), frames(""))
        assertEquals(emptyList<String>(), frames(": ping\n\n"))
    }

    @Test
    fun `非法 JSON 的取值函数返回 null 而不抛`() {
        assertNull(parseSseJson("{不是 json"))
        assertNull(parseSseJson(""))
        assertNull(parseSseJson("[1,2,3]"))   // 合法 JSON 但不是对象
    }

    @Test
    fun `正文增量取值`() {
        val obj = parseSseJson(
            """{"choices":[{"delta":{"content":"你好"}}]}"""
        )
        assertNotNull(obj)
        assertEquals("你好", obj!!.deltaContent())
        assertNull(obj.deltaReasoning())
    }

    @Test
    fun `推理增量取值 - 兼容三种字段名`() {
        for (key in listOf("reasoning_content", "reasoning", "thinking")) {
            val obj = parseSseJson("""{"choices":[{"delta":{"$key":"想一下"}}]}""")
            assertEquals("想一下", obj!!.deltaReasoning())
        }
    }

    @Test
    fun `usage 取值 - DeepSeek 口径含缓存命中`() {
        val usage = parseSseJson(
            """{"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":80}}"""
        )!!.usageOrNull()
        assertNotNull(usage)
        assertEquals(100, usage!!.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(80, usage.cachedTokens)
    }

    @Test
    fun `usage 取值 - OpenAI 嵌套口径`() {
        val usage = parseSseJson(
            """{"usage":{"prompt_tokens":100,"completion_tokens":20,
               "prompt_tokens_details":{"cached_tokens":64},
               "completion_tokens_details":{"reasoning_tokens":12}}}"""
        )!!.usageOrNull()!!
        assertEquals(64, usage.cachedTokens)
        assertEquals(12, usage.reasoningTokens)
    }

    @Test
    fun `usage 缺失时返回 null - 上层据此标注估算`() {
        assertNull(parseSseJson("""{"choices":[{"delta":{"content":"x"}}]}""")!!.usageOrNull())
    }

    @Test
    fun `流内错误对象能被取出来`() {
        val obj = parseSseJson("""{"error":{"message":"model not found"}}""")!!
        assertEquals("model not found", obj.errorMessage())
    }

    @Test
    fun `HTTP 状态分类`() {
        assertEquals(LlmErrorKind.AUTH, classifyHttpStatus(401))
        assertEquals(LlmErrorKind.AUTH, classifyHttpStatus(403))
        assertEquals(LlmErrorKind.NOT_FOUND, classifyHttpStatus(404))
        assertEquals(LlmErrorKind.BAD_REQUEST, classifyHttpStatus(400))
        assertEquals(LlmErrorKind.RATE_LIMIT, classifyHttpStatus(429))
        assertEquals(LlmErrorKind.SERVER, classifyHttpStatus(503))
        assertTrue(classifyHttpStatus(429).retryable)
        assertTrue(!classifyHttpStatus(401).retryable)
        assertTrue(!classifyHttpStatus(400).retryable)
    }
}
