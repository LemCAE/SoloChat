package app.solochat.data.llm

import app.solochat.core.llm.*
import app.solochat.core.model.ThinkingParameterDialect
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
    @Test fun `请求快照覆盖全局凭据且空密钥不回退其他组`() = runBlocking {
        val client = client("https://new.example/v1")
        val endpoint = RequestEndpoint("https://old.example/v1", "old-test-key")
        val request = LlmRequest("old-model", listOf(ApiMessage("user", "测试")), endpoint = endpoint)
        val pending = client.chatStream(request)
        pending.toList()
        assertEquals("old.example", requests.single().url.host)
        assertEquals("Bearer old-test-key", requests.single().header("Authorization"))
        assertFalse(request.toString().contains("old-test-key"))
        client.chatStream(request.copy(model = "new-model", endpoint = RequestEndpoint("https://new.example/v1", "new-test-key"))).toList()
        assertEquals("new.example", requests.last().url.host)
        assertEquals("Bearer new-test-key", requests.last().header("Authorization"))
        assertEquals("new-model", body(requests.last())["model"]?.jsonPrimitive?.content)
        try {
            client.chatStream(request.copy(endpoint = RequestEndpoint("https://empty.example/v1", ""))).toList()
            fail("空密钥必须失败")
        } catch (error: LlmException) {
            assertEquals(LlmErrorKind.NOT_CONFIGURED, error.error.kind)
        }
        assertEquals(2, requests.size)
    }

    @Test fun `目录和连通检查缺少本组凭据或地址无效时不发请求`() = runBlocking {
        for ((url, key) in listOf("https://example.invalid" to "", "https://bad host" to "test-key",
            "https://example.invalid?key=invalid" to "test-key")) {
            val client = client(url, key)
            for (list in listOf(true, false)) {
                try {
                    if (list) client.listModels() else client.ping("model")
                    fail("应在发送前拒绝")
                } catch (error: LlmException) {
                    assertEquals(LlmErrorKind.NOT_CONFIGURED, error.error.kind)
                }
            }
        }
        assertTrue(requests.isEmpty())
    }

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
        key: String = "unit-test-key",
        respond: (Request) -> Pair<Int, String> = { 200 to stream }): OpenAiCompatClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (status, text) = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("测试响应")
                .body(text.toResponseBody()).build()
        }.build()
        return OpenAiCompatClient({ base }, { key }, http)
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
        val usage = """{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64},"completion_tokens_details":{"reasoning_tokens":8}}"""
        assertEquals(listOf(LlmDelta.Reasoning("思考"), LlmDelta.Text("收到"), LlmDelta.Finished(100, 20, 64, 8, listOf(usage))), deltas)
    }

    @Test fun `类型化思考参数按 DeepSeek 方言生成嵌套开关与强度`() = runBlocking {
        client("https://api.deepseek.com/v1").chatStream(LlmRequest(
            model = "deepseek-flash",
            messages = listOf(ApiMessage("user", "你好")),
            thinkingParameters = ThinkingRequestParameters(
                ThinkingParameterDialect.DEEPSEEK_CHAT_COMPLETIONS, enabled = true, effort = ThinkingEffort.HIGH
            )
        )).toList()
        val payload = body(requests.single())
        assertEquals("enabled", payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("high", payload.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(payload.containsKey("enable_thinking"))
    }

    @Test fun `类型化思考参数按百炼方言仅生成 enable_thinking`() = runBlocking {
        client().chatStream(LlmRequest(
            model = "qwen3.8-max",
            messages = listOf(ApiMessage("user", "你好")),
            thinkingParameters = ThinkingRequestParameters(
                ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING, enabled = false
            )
        )).toList()
        val payload = body(requests.single())
        assertEquals(false, payload.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse(payload.containsKey("thinking"))
        assertFalse(payload.containsKey("reasoning_effort"))
    }

    @Test fun `类型化思考参数使用 OpenAI 方言并保留旧请求路径`() = runBlocking {
        client("https://custom.example/v1").chatStream(LlmRequest(
            model = "custom-reasoner",
            messages = listOf(ApiMessage("user", "你好")),
            thinkingParameters = ThinkingRequestParameters(
                ThinkingParameterDialect.OPENAI_REASONING_EFFORT, enabled = false
            )
        )).toList()
        val payload = body(requests.single())
        assertEquals("none", payload.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(payload.containsKey("thinking"))
        assertFalse(payload.containsKey("enable_thinking"))
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

    @Test fun `百炼原生模型列表使用同域api路径并读取模型ID和全部分页`() = runBlocking {
        val models = client { request ->
            val model = if (request.url.queryParameter("page_no") == "1") "qwen-plus" else "qwen-flash"
            200 to """{"success":true,"output":{"total":2,"models":[{"model":"$model","name":"中文展示名称"}]}}"""
        }.listModels()
        assertEquals(listOf("qwen-flash", "qwen-plus"), models)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.url.host == "dashscope.aliyuncs.com" && it.url.encodedPath == "/api/v1/models" })
        assertEquals(listOf("1", "2"), requests.map { it.url.queryParameter("page_no") })
    }

    @Test fun `普通接口模型列表仍使用OpenAI路径与data结构`() = runBlocking {
        val models = client("https://example.invalid/v1") { 200 to """{"data":[{"id":"model"}]}""" }.listModels()
        assertEquals(listOf("model"), models)
        assertEquals("/v1/models", requests.single().url.encodedPath)
    }

    @Test fun `百炼列表业务错误不能被当成成功的空列表`() = runBlocking {
        try {
            client { 200 to """{"success":false,"code":"InvalidParameter","message":"参数错误"}""" }.listModels()
            fail("必须报告业务错误")
        } catch (_: LlmException) { }
    }

    @Test fun `百炼列表重复分页不能无限请求或返回不完整结果`() = runBlocking {
        try {
            client { 200 to """{"success":true,"output":{"total":2,"models":[{"model":"same"}]}}""" }.listModels()
            fail("必须报告分页未前进")
        } catch (_: LlmException) { }
        assertTrue(requests.size <= 2)
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
