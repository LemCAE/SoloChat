package app.solochat.data.llm

import app.solochat.core.llm.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

/** 注入现有 HTTP 客户端，直接检查真实请求及 SSE 处理；不启动服务器或请求外部服务。 */
class DashScopeCompatibilityTest {
    private val json = Json
    private val requests = mutableListOf<Request>()
    private val stream = """
        data: {"choices":[{"delta":{"role":"assistant","content":null,"reasoning_content":"思考"}}],"usage":null}

        data: {"choices":[{"delta":{"content":"收到"},"finish_reason":"stop"}],"usage":null}

        data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64},"completion_tokens_details":{"reasoning_tokens":8}}}

        data: [DONE]

    """.trimIndent() + "\n\n"

    private fun body(request: Request): JsonObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun client(base: String = OpenAiCompatClient.DASHSCOPE_BASE_URL,
        respond: (Request) -> Pair<Int, String> = { 200 to stream }): OpenAiCompatClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (status, text) = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("测试响应")
                .body(text.toResponseBody()).build()
        }.build()
        return OpenAiCompatClient({ base }, { "unit-test-key" }, http)
    }

    @Test fun `百炼 compatible-mode 地址保留且聊天解析思考正文与独立用量尾帧`() = runBlocking {
        val deltas = client("${OpenAiCompatClient.DASHSCOPE_BASE_URL}/").chatStream(
            LlmRequest("qwen-plus", listOf(ApiMessage("user", "你好")), thinkingEffort = ThinkingEffort.OFF)
        ).toList()
        val request = requests.single()
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", request.url.toString())
        assertEquals("Bearer unit-test-key", request.header("Authorization"))
        val payload = body(request)
        assertEquals(true, payload.getValue("stream").jsonPrimitive.boolean)
        assertEquals(false, payload.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals(true, payload.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
        assertEquals(listOf(LlmDelta.Reasoning("思考"), LlmDelta.Text("收到"), LlmDelta.Finished(100, 20, 64, 8)), deltas)
    }

    @Test fun `百炼仅流式模型的连通测试走正式 SSE 链路且只调用一次`() = runBlocking {
        val latency = client { request ->
            if (body(request)["stream"]?.jsonPrimitive?.boolean == true) 200 to stream
            else 400 to """{"error":{"message":"only support stream mode"}}"""
        }.ping("qwen3-32b")
        assertTrue(latency >= 0)
        val payload = body(requests.single())
        assertEquals(true, payload.getValue("stream").jsonPrimitive.boolean)
        assertEquals(1, payload.getValue("max_tokens").jsonPrimitive.int)
        assertFalse(payload.containsKey("enable_thinking"))
    }

    @Test fun `业务空间专属域名也使用流式连通测试`() = runBlocking {
        client("https://test-workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1").ping("qwen-plus")
        assertEquals("/compatible-mode/v1/chat/completions", requests.single().url.encodedPath)
        assertEquals(true, body(requests.single()).getValue("stream").jsonPrimitive.boolean)
    }

    @Test fun `百炼模型列表使用相同前缀且不多拼一层v1`() = runBlocking {
        val models = client { 200 to """{"data":[{"id":"qwen-plus"},{"id":"qwen-flash"}]}""" }.listModels()
        assertEquals(listOf("qwen-flash", "qwen-plus"), models)
        assertEquals("GET", requests.single().method)
        assertEquals("/compatible-mode/v1/models", requests.single().url.encodedPath)
    }

    @Test fun `百炼限流保留原错误而不自动重试`() = runBlocking {
        try {
            client { 429 to """{"error":{"message":"限流"}}""" }.ping("qwen-plus")
            fail("应报告限流")
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.RATE_LIMIT, e.error.kind)
        }
        assertEquals(1, requests.size)
    }

    @Test fun `普通接口保留原有非流式连通测试`() = runBlocking {
        client("https://example.invalid/v1") { 200 to "{}" }.ping("ordinary-model")
        assertEquals(false, body(requests.single()).getValue("stream").jsonPrimitive.boolean)
        assertEquals("/v1/chat/completions", requests.single().url.encodedPath)
    }
}
