package app.solochat.data.llm

import app.solochat.core.llm.LlmClient
import app.solochat.core.llm.LlmDelta
import app.solochat.core.llm.LlmError
import app.solochat.core.llm.LlmErrorKind
import app.solochat.core.llm.LlmException
import app.solochat.core.llm.LlmRequest
import app.solochat.core.llm.ApiMessage
import app.solochat.core.llm.SseFrameParser
import app.solochat.core.llm.classifyHttpStatus
import app.solochat.core.llm.deltaContent
import app.solochat.core.llm.deltaReasoning
import app.solochat.core.llm.errorMessage
import app.solochat.core.llm.parseSseJson
import app.solochat.core.llm.usageOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容接口的客户端（`/chat/completions` 流式 + `/models`）。
 *
 * 几个必须处理的现实问题：
 *
 * 1. **Base URL 写法五花八门**。用户会填 `https://api.deepseek.com`、
 *    `https://api.deepseek.com/v1`、`https://x.com/openai/v1/`。所以这里拼路径时
 *    统一归一化。普通接口的连通测试遇到 404 可换路径；正式聊天只发一次，
 *    DashScope 的 compatible-mode 路径原样保留。
 *
 * 2. **不同网关的字段名不同**（reasoning_content / reasoning / thinking，
 *    `prompt_cache_hit_tokens` / `prompt_tokens_details.cached_tokens`），
 *    取值逻辑统一放在 core 的 SseFrameParser，这里不重复判断。
 *
 * 3. **取消必须真的掐断连接**。手机上切走界面就该停止烧钱，
 *    所以用 callbackFlow，`awaitClose` 里 cancel 掉 OkHttp 的 Call。
 */
class OpenAiCompatClient(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = defaultClient()
) : LlmClient {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun chatStream(request: LlmRequest): Flow<LlmDelta> = callbackFlow {
        val base = normalizeBase(request.endpoint?.baseUrl ?: baseUrlProvider())
        val key = request.endpoint?.apiKey ?: apiKeyProvider()
        val invalid = endpointError(base, key)
        if (invalid != null) {
            close(invalid)
            return@callbackFlow
        }

        val body = buildBody(request).toString()
        val (http, req) = newRequest(base, "chat/completions", body, key = key)

        val parser = SseFrameParser()
        val call = httpClient.newCall(req)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 被主动取消时不报错（用户自己停的，不需要看到红色提示）
                if (call.isCanceled()) {
                    close()
                    return
                }
                trySendError(
                    kind = classifyIOException(e),
                    message = e.message ?: "网络请求失败",
                    detail = e.toString()
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val text = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                        trySendError(
                            kind = classifyHttpStatus(res.code, text),
                            message = "HTTP ${res.code}",
                            detail = text.take(600),
                            status = res.code
                        )
                        return
                    }

                    val source = res.body?.source() ?: run {
                        trySendError(LlmErrorKind.PROTOCOL, "响应体为空", "")
                        return
                    }

                    var sawUsage = false
                    var streamDone = false
                    try {
                        while (!source.exhausted() && !streamDone) {
                            val line = source.readUtf8Line() ?: break
                            // 逐行喂给分帧器。用 feedLine 而不是 feed：HTTP 客户端本来就按行
                            // 读，这样不必把片段攒到空行才处理。
                            //
                            // 注意这里用标志位而不是 `break`/`return@use`：
                            // Kotlin 2.1 仍不允许在 inline lambda 内 break/continue
                            // （2.2 才有），而 `return@use` 会连带跳过 finally 里的 close。
                            for (payload in parser.feedLine(line)) {
                                if (payload == "[DONE]") {
                                    streamDone = true
                                } else {
                                    val obj = parseSseJson(payload)
                                    if (obj != null) {
                                        obj.errorMessage()?.let { msg ->
                                            trySendError(LlmErrorKind.BAD_REQUEST, "接口返回错误", msg)
                                        }
                                        obj.deltaReasoning()?.takeIf { it.isNotEmpty() }?.let {
                                            trySend(LlmDelta.Reasoning(it))
                                        }
                                        obj.deltaContent()?.takeIf { it.isNotEmpty() }?.let {
                                            trySend(LlmDelta.Text(it))
                                        }
                                        obj.usageOrNull()?.let {
                                            sawUsage = true
                                            trySend(it)
                                        }
                                    }
                                }
                                if (streamDone) break
                            }
                        }
                        for (payload in parser.flush()) {
                            if (payload == "[DONE]") continue
                            val obj = parseSseJson(payload) ?: continue
                            obj.deltaContent()?.takeIf { it.isNotEmpty() }?.let { trySend(LlmDelta.Text(it)) }
                            obj.usageOrNull()?.let { sawUsage = true; trySend(it) }
                        }
                    } catch (e: IOException) {
                        trySendError(LlmErrorKind.CONNECT, e.message ?: "读取流失败", e.toString())
                    } finally {
                        if (!sawUsage) {
                            // 明确告知"这次没有 usage"，让上层标"估算"而不是显示 0
                            trySend(LlmDelta.UsageMissing)
                        }
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val base = normalizeBase(baseUrlProvider())
        val key = apiKeyProvider()
        endpointError(base, key)?.let { throw it }
        if (isDashScope(base)) return@withContext listDashScopeModels(base, key)
        val (http, req) = newRequest(base, "models", null, method = "GET", key = key)
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw LlmException(
                    LlmError.Failed(
                        classifyHttpStatus(res.code, text),
                        "HTTP ${res.code}",
                        text.take(400),
                        res.code
                    )
                )
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw LlmException(
                    LlmError.Failed(LlmErrorKind.PROTOCOL, "模型列表格式不对", text.take(300))
                )
            val arr = root["data"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: root["models"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: JsonArray(emptyList())
            arr.mapNotNull { el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                (o["id"] ?: o["name"])?.let { (it as? JsonPrimitive)?.content }
            }.filter { it.isNotBlank() }.sorted()
        }
    }

    /** 百炼模型目录走原生路径与分页，不能使用聊天兼容前缀下的 /models。 */
    private fun listDashScopeModels(base: String, key: String): List<String> {
        val endpoint = base.toHttpUrlOrNull() ?: throw LlmException(LlmError.Failed(LlmErrorKind.NOT_CONFIGURED, "接口地址无效"))
        val result = linkedSetOf<String>()
        var page = 1
        fun protocol(message: String): Nothing = throw LlmException(LlmError.Failed(LlmErrorKind.PROTOCOL, message))
        while (true) {
            // 保持当前官方域名与地域，不转发 Key 到其他服务。
            val url = endpoint.newBuilder().encodedPath("/api/v1/models").query(null).fragment(null)
                .addQueryParameter("page_no", page.toString()).addQueryParameter("page_size", "100")
                .addQueryParameter("capabilities", "TG").build()
            val request = Request.Builder().url(url).get().header("Authorization", "Bearer ${key.trim()}")
                .header("Accept", "application/json").build()
            val root = httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmException(LlmError.Failed(classifyHttpStatus(response.code, text),
                    "模型列表 HTTP ${response.code}", text.take(400), response.code))
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: protocol("模型列表格式不对")
            }
            if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) throw LlmException(
                LlmError.Failed(LlmErrorKind.BAD_REQUEST, "百炼模型列表查询失败",
                    (root["message"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(400)))
            val output = root["output"] as? JsonObject ?: protocol("百炼模型列表缺少 output")
            val total = (output["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                ?: protocol("百炼模型列表缺少有效总数")
            val models = output["models"] as? JsonArray ?: protocol("百炼模型列表缺少 models")
            val before = result.size
            models.forEach { item ->
                val model = ((item as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: protocol("百炼模型列表缺少模型 ID")
                result += model
            }
            if (result.size >= total) return result.sorted()
            if (result.size == before) protocol("百炼模型列表分页未前进，请稍后重新获取")
            page++
        }
    }

    override suspend fun ping(model: String): Long = withContext(Dispatchers.IO) {
        val base = normalizeBase(baseUrlProvider())
        val key = apiKeyProvider()
        endpointError(base, key)?.let { throw it }
        if (model.isBlank()) throw LlmException(LlmError.Failed(LlmErrorKind.NOT_CONFIGURED, "请填写本组模型名"))
        if (isDashScope(base)) {
            // 部分千问模型只支持流式；非流式 ping 失败不能说明正式聊天不可用。
            // 复用正式 SSE 链路，思考参数留空以遵循所选模型自身能力。
            val started = System.currentTimeMillis()
            val completed = withTimeoutOrNull(30_000) {
                chatStream(LlmRequest(model, listOf(ApiMessage("user", "ping")), maxTokens = 1,
                    endpoint = app.solochat.core.llm.RequestEndpoint(base, key))).collect()
                true
            }
            if (completed != true) throw LlmException(
                LlmError.Failed(LlmErrorKind.TIMEOUT, "连通测试超过 30 秒仍未完成")
            )
            return@withContext System.currentTimeMillis() - started
        }
        val body = buildJson {
            put("model", JsonPrimitive(model))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("ping"))
                        }
                    )
                }
            )
            put("stream", JsonPrimitive(false))
            put("max_tokens", JsonPrimitive(1))
        }.toString()

        var lastError: LlmException? = null
        // 只有普通接口的 ping 做双拼法；正式聊天不自动重试。
        // ping 是用户第一次测通的地方，
        // "地址少写/多写 /v1" 这种问题必须在这里自愈，否则用户第一印象就是失败的。
        //
        // ⚠️ 注意语义：**只有 404 才值得换另一种拼法**（说明路径不对）。
        // 其它错误（Key 错、模型不存在、限流）换路径也没用，必须立刻停止重试，
        // 否则用户会看到"测试了很久，最后还是问了同一个错"。
        // 这里用 `shouldRetryOtherPath` 标志位而不是 break ——
        // Kotlin 2.1 不允许在 inline lambda 里 break/continue。
        var shouldRetryOtherPath = true
        for (candidate in pathCandidates(base)) {
            if (!shouldRetryOtherPath) break
            val started = System.currentTimeMillis()
            val req = buildRequest(candidate, "chat/completions", body, key = key)
            try {
                httpClient.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (res.isSuccessful) return@withContext System.currentTimeMillis() - started
                    val kind = classifyHttpStatus(res.code, text)
                    lastError = LlmException(
                        LlmError.Failed(kind, "HTTP ${res.code}", text.take(400), res.code)
                    )
                    if (kind != LlmErrorKind.NOT_FOUND) shouldRetryOtherPath = false
                }
            } catch (e: IOException) {
                lastError = LlmException(
                    LlmError.Failed(
                        classifyIOException(e),
                        e.message ?: "连接失败",
                        e.toString()
                    )
                )
                break
            }
        }
        throw lastError ?: LlmException(
            LlmError.Failed(LlmErrorKind.UNKNOWN, "测试失败")
        )
    }

    // ------------------------------------------------------------------

    private fun buildBody(request: LlmRequest): JsonObject = buildJson {
        put("model", JsonPrimitive(request.model))
        put(
            "messages",
            buildJsonArray {
                for (m in request.messages) {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive(m.role))
                            put("content", JsonPrimitive(m.content))
                        }
                    )
                }
            }
        )
        put("stream", JsonPrimitive(true))
        put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
        put("temperature", JsonPrimitive(request.temperature))
        if (request.maxTokens > 0) put("max_tokens", JsonPrimitive(request.maxTokens))
        val parameters = request.thinkingParameters
        if (parameters != null) {
            require(request.thinkingEffort == null) { "新旧思考参数不能同时指定" }
            when (parameters.dialect) {
                app.solochat.core.model.ThinkingParameterDialect.DEEPSEEK_CHAT_COMPLETIONS -> {
                    put("thinking", buildJsonObject {
                        put("type", JsonPrimitive(if (parameters.enabled) "enabled" else "disabled"))
                    })
                    parameters.effort?.let { put("reasoning_effort", JsonPrimitive(it.wire)) }
                }
                app.solochat.core.model.ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING -> {
                    require(parameters.effort == null) { "百炼开关方言未声明强度字段" }
                    put("enable_thinking", JsonPrimitive(parameters.enabled))
                }
                app.solochat.core.model.ThinkingParameterDialect.OPENAI_REASONING_EFFORT -> {
                    if (!parameters.enabled) put("reasoning_effort", JsonPrimitive("none"))
                    else parameters.effort?.let { put("reasoning_effort", JsonPrimitive(it.wire)) }
                }
            }
        } else {
            // 旧运行入口保持原有 enable_thinking 语义；P2-A-10 前不会切换调用来源。
            when (request.thinkingEffort?.wire) {
                "off" -> put("enable_thinking", JsonPrimitive(false))
                null, "" -> Unit
                else -> put("enable_thinking", JsonPrimitive(true))
            }
        }
    }

    private fun newRequest(
        base: String,
        path: String,
        body: String?,
        method: String = "POST",
        key: String
    ): Pair<OkHttpClient, Request> {
        val candidate = pathCandidates(base).first()
        return httpClient to buildRequest(candidate, path, body, method, key)
    }

    private fun buildRequest(
        base: String,
        path: String,
        body: String?,
        method: String = "POST",
        key: String
    ): Request {
        val url = "$base/$path"
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${key.trim()}")
            .header("Accept", "text/event-stream")
        if (method == "GET") {
            builder.get()
        } else {
            builder.post((body ?: "").toRequestBody(jsonMedia))
        }
        return builder.build()
    }

    /**
     * 路径候选：先 `/v1/...`，再不用 `/v1`。
     *
     * 为什么把 `/v1` 放第一：绝大多数服务（含 DeepSeek 官方、各类中转站）的
     * 规范路径是 `/v1/chat/completions`；只有少数自建网关接受裸 `/chat/completions`。
     */
    private fun pathCandidates(base: String): List<String> {
        val b = base.trimEnd('/')
        return if (b.endsWith("/v1")) {
            listOf(b, b.removeSuffix("/v1"))
        } else {
            listOf("$b/v1", b)
        }
    }

    private fun normalizeBase(raw: String): String {
        var v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return ""
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        return v
    }

    /** 在发请求前拒绝缺凭据或夹带凭据的 URL，避免将错误草稿送到网络。 */
    private fun endpointError(base: String, key: String): LlmException? {
        val url = base.toHttpUrlOrNull()
        val message = when {
            base.isBlank() || key.isBlank() -> "还没配置本组接口地址或 Key"
            url == null || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null -> "接口地址无效，请使用不带凭据、查询参数或片段的 HTTP 地址"
            else -> return null
        }
        return LlmException(LlmError.Failed(LlmErrorKind.NOT_CONFIGURED, message))
    }

    private fun isDashScope(base: String): Boolean {
        val host = base.toHttpUrlOrNull()?.host ?: return false
        return host == "dashscope.aliyuncs.com" || host == "dashscope-intl.aliyuncs.com" ||
            host.endsWith(".dashscope.aliyuncs.com") || host.endsWith(".maas.aliyuncs.com")
    }

    private fun classifyIOException(e: IOException): LlmErrorKind = when {
        e is java.net.UnknownHostException -> LlmErrorKind.CONNECT
        e is java.net.ConnectException -> LlmErrorKind.CONNECT
        e is java.net.SocketTimeoutException -> LlmErrorKind.TIMEOUT
        e is javax.net.ssl.SSLException -> LlmErrorKind.CONNECT
        else -> LlmErrorKind.UNKNOWN
    }

    private fun kotlinx.coroutines.channels.ProducerScope<LlmDelta>.trySendError(
        kind: LlmErrorKind,
        message: String,
        detail: String,
        status: Int? = null
    ) {
        close(LlmException(LlmError.Failed(kind, message, detail, status)))
    }

    private inline fun buildJson(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject(block)

    companion object {
        const val DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 读超时给得很宽：手机上网络切换、模型排队都会让首字很晚才来。
            // 真正的"用户可感知超时"由上层 withTimeout 控制（见 AgentTurnRunner），
            // 这里只做兜底，避免连接永久挂着。
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
