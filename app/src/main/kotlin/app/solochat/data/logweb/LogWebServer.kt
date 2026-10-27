package app.solochat.data.logweb

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.content.Context
import app.solochat.core.log.Log
import app.solochat.core.log.LogEntry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 把日志开成一个 HTTP 端口，方便用电脑浏览器看。
 *
 * **动因**：手机上调日志很难受 —— 要解锁、进页面、长按复制、再想办法传出来。
 * 电脑上开个页面，边复现边看，效率完全不同。
 *
 * ## 安全（这块不能将就）
 *
 * 日志里有**完整提示词和全部对话内容**。所以：
 *
 * 1. **默认只监听 127.0.0.1**（要求 `adb forward` 才能从电脑访问）。
 *    这时不需要密码 —— 只有本机能连。
 * 2. **要开局域网必须设密码**，两条同时满足才允许启动。理由：同一个 WiFi 下
 *    任何人都能扫到这个端口，而里面是你和"她"的全部聊天记录。
 * 3. 密码用 **Bearer token** 校验，并且支持 `?t=<token>`（首次用浏览器打开时带一次，
 *    页面自己存进 localStorage）。不用 Cookie 是因为它要额外的会话状态，
 *    而这里的需求只是"别让邻居看到"。
 * 4. token 每次启动随机生成，不落盘。
 *
 * 刻意**不做**的事：不做 HTTPS（自签证书在手机上装的麻烦远超收益）、不做用户系统、
 * 不把日志写进响应缓存。
 */
class LogWebServer(
    private val context: Context,
    /**
     * 测试接口的后端。
     *
     * 为什么用 provider 而不是直接传 [app.solochat.SoloChatApp]：那会形成
     * "App 持有 LogWebServer、LogWebServer 又持有 App" 的循环。
     * 用 lambda 延迟取，两边都干净；不在测试里跑 agent 时返回 null 即可。
     */
    private val gatewayProvider: () -> TestGateway? = { null },
    private val uiGatewayProvider: () -> UiTestGateway? = { null },
    private val p4TestGatewayProvider: () -> P4TestGateway? = { null }
) {

    /** HTTP 方法：只需要区分读与写。 */
    private val ioDispatcher = kotlinx.coroutines.Dispatchers.IO

    /**
     * 服务状态。界面据此显示"当前地址"和"为什么起不来"。
     */
    data class Status(
        val running: Boolean = false,
        val port: Int = 0,
        val lan: Boolean = false,
        val localUrl: String = "",
        val lanUrl: String = "",
        /** 首次打开用的带 token 地址（方便直接复制到浏览器）。 */
        val localUrlWithToken: String = "",
        val lanUrlWithToken: String = "",
        val error: String = ""
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var engine: ApplicationEngine? = null

    /**
     * 启动。
     *
     * @param port 端口。1024 以下在 Android 上会因权限失败，所以这里夹到 1024~65535。
     * @param lan 是否允许局域网访问。为 true 时 [password] 必须非空。
     * @param password 访问密码。
     *
     * **凭证就是密码本身**（不再另发 token）。理由：密码是用户自己设的、记得住，
     * 复制到电脑浏览器上能直接手输；而随机 token 只在"同一台手机复制粘贴"时方便，
     * 跨设备反而变成"还要先找到那串随机字符"。密码只用于这个服务，
     * 且**明文只出现在带 `?t=` 的 URL 里**，页面拿到后会存进 localStorage 并从地址栏清掉。
     */
    fun start(port: Int, lan: Boolean, password: String): Result<Status> {
        stop()

        val safePort = port.coerceIn(1024, 65535)
        if (lan && password.isBlank()) {
            val message = "要允许局域网访问，必须先设一个访问密码 —— " +
                "日志里有你的全部对话内容，同 WiFi 下任何人都能看到。"
            _status.value = Status(error = message)
            return Result.failure(IllegalArgumentException(message))
        }

        return runCatching {
            val host = if (lan) "0.0.0.0" else "127.0.0.1"

            val server = embeddedServer(CIO, port = safePort, host = host) {
                handleRequests(lan, password)
            }
            // start(wait = false)：不能在调用线程上阻塞，否则界面直接卡死
            server.start(wait = false)
            engine = server

            val localUrl = "http://127.0.0.1:$safePort"
            val lanIp = if (lan) localIpv4() else ""
            val lanUrl = if (lan && lanIp.isNotEmpty()) "http://$lanIp:$safePort" else ""

            val status = Status(
                running = true,
                port = safePort,
                lan = lan,
                localUrl = localUrl,
                lanUrl = lanUrl,
                localUrlWithToken = if (lan) "" else "$localUrl/",
                lanUrlWithToken = if (lanUrl.isNotEmpty()) "$lanUrl/?t=$password" else ""
            )
            _status.value = status
            Log.fields(
                "日志服务",
                pairs = arrayOf(
                    "事件" to "已启动",
                    "监听" to host,
                    "端口" to safePort,
                    "局域网" to lan,
                    "地址" to (if (lan) lanUrl else localUrl),
                    "需要密码" to (if (lan) "是" else "否（仅本机）")
                )
            )
            status
        }.onFailure { error ->
            val message = error.message ?: "启动失败"
            _status.value = Status(error = message)
            Log.error("日志服务", "启动失败：$message")
        }
    }

    fun stop() {
        val had = engine != null
        runCatching { engine?.stop(1_000, 2_000) }
        engine = null
        if (had) Log.info("日志服务", "已停止")
        _status.value = Status()
    }

    /**
     * 请求处理。
     *
     * **刻意不用 `routing { get("/") { ... } }` 这套 DSL**，而是直接挂在路由管线上。
     * 原因：ktor 2 的 Route 上带 DslMarker，在路由 lambda 里取 `call` 会遇到
     * "Unresolved reference: call"（隐式接收者被标记挡住）。我为此试了三种写法都不行，
     * 最后查 `javap` 才看清路由 lambda 的接收者是
     * `PipelineContext<Unit, ApplicationCall>` —— `ApplicationCall` 在**类型参数**位置，
     * 所以只有 `context` 是显式可用的。
     *
     * 直接拦管线的好处是 `context` 毫无歧义、路径匹配也一眼能看懂，
     * 而且少一层 DSL 抽象，将来换 ktor 版本更不容易坏。
     */
    private fun Application.handleRequests(lan: Boolean, password: String) {
        intercept(ApplicationCallPipeline.Call) {
            val call = context
            val path = call.request.path().trimEnd('/').ifEmpty { "/" }
            val method = call.request.httpMethod.value

            when {
                path == "/" && method == "GET" -> call.respondText(
                    LogViewerHtml.PAGE,
                    ContentType.Text.Html.withCharset(Charsets.UTF_8)
                )

                path == "/api/logs" && method == "GET" -> {
                    if (!authorized(call, lan, password)) {
                        call.respondText(
                            """{"error":"unauthorized"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                    } else {
                        // 支持 ?since=<seq> 只取增量，避免每秒把 600 条全传一遍
                        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                        val entries = Log.entries.value.filter { it.seq > since }
                        call.respondText(
                            ResponseJson.logs(
                                entries = entries,
                                since = since,
                                maxSeq = Log.entries.value.lastOrNull()?.seq ?: 0L,
                                version = Log.version,
                                verbose = Log.verbose
                            ),
                            ContentType.Application.Json.withCharset(Charsets.UTF_8)
                        )
                    }
                }

                path == "/api/health" -> call.respondText(
                    """{"ok":true,"version":${ResponseJson.quote(Log.version)}}""",
                    ContentType.Application.Json.withCharset(Charsets.UTF_8)
                )

                // ---- 测试接口：与日志同一道门禁（它返回的是对话内容）----
                path.startsWith("/api/") -> {
                    if (!authorized(call, lan, password)) {
                        call.respondText(
                            """{"error":"unauthorized"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                    } else {
                        handleTestApi(call, path, method)
                    }
                }

                else -> call.respondText(
                    "not found",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotFound
                )
            }
            // 必须 finish：否则请求会继续往下走，最后变成一个没有响应的挂起连接
            finish()
        }
    }

    /**
     * 测试接口路由。
     *
     * 旧执行器接口跑在 [ioDispatcher] 上；UI 同步接口切到会话主调度器提交命令，
     * 不在 HTTP 请求里等待整轮生成和播出，日志页面可继续响应。
     */
    private suspend fun handleTestApi(call: ApplicationCall, path: String, method: String) {
        if (path.startsWith("/api/p4-test/")) { handleP4TestApi(call, path, method); return }
        if (path.startsWith("/api/ui/")) { handleUiApi(call, path, method); return }
        val gateway = gatewayProvider()
        if (gateway == null) {
            call.respondText(
                """{"error":"测试接口未启用"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable
            )
            return
        }

        try {
            when {
                path == "/api/conversations" && method == "GET" ->
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        call.respondJson(ResponseJson.conversations(gateway.conversations()))
                    }

                path == "/api/history" && method == "GET" ->
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        val id = call.request.queryParameters["id"].orEmpty()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 40
                        call.respondJson(ResponseJson.history(id, gateway.history(id, limit)))
                    }

                // 消息表原样导出。排查"模型看不到自己说过的话"只能靠它 ——
                // /api/history 走的是界面投影，两者分叉时前者看起来完全正常。
                path == "/api/messages" && method == "GET" ->
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        val id = call.request.queryParameters["id"].orEmpty()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                        val list = gateway.messages(id, limit)
                        val rows = list.joinToString(",") { row ->
                            buildString {
                                append("{\"id\":").append(row["id"])
                                append(",\"role\":").append(ResponseJson.quote(row["role"].toString()))
                                append(",\"time\":").append(ResponseJson.quote(row["time"].toString()))
                                append(",\"text\":").append(ResponseJson.quote(row["text"].toString()))
                                append(",\"sentToModel\":").append(row["sentToModel"])
                                append('}')
                            }
                        }
                        call.respondJson(
                            "{\"conversationId\":${ResponseJson.quote(id)}," +
                                "\"count\":${list.size},\"items\":[$rows]}"
                        )
                    }

                path == "/api/send" && method == "POST" -> {
                    val body = call.readBodyText().trim()
                    if (body.isEmpty()) {
                        call.respondJson(ResponseJson.single("error", "请求体为空，直接把要发的话写在 body 里"))
                    } else {
                        val id = call.request.queryParameters["id"]?.takeIf { it.isNotBlank() }
                        val reset = call.request.queryParameters["reset"] != "0" && id == null
                        val result = kotlinx.coroutines.withContext(ioDispatcher) {
                            gateway.send(id, body, reset = reset)
                        }
                        call.respondJson(ResponseJson.turn(
                            conversationId = result.conversationId,
                            userText = result.userText,
                            bubbles = result.bubbles,
                            events = result.events.map {
                                TurnEvent(it.kind, it.speaker, it.text, it.gapHintMs, it.typingHintMs)
                            },
                            rawOutput = result.rawOutput,
                            model = result.model,
                            promptTokens = result.promptTokens,
                            completionTokens = result.completionTokens,
                            cachedTokens = result.cachedTokens,
                            cost = result.cost,
                            rough = result.rough,
                            error = result.error
                        ))
                    }
                }

                path.startsWith("/api/delete") && method == "POST" -> {
                    val id = call.request.queryParameters["id"].orEmpty()
                    kotlinx.coroutines.withContext(ioDispatcher) { gateway.deleteConversation(id) }
                    call.respondJson("""{"ok":true}""")
                }

                else -> call.respondText(
                    """{"error":"未知接口：$method $path"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
            }
        } catch (e: Throwable) {
            Log.error("测试接口", "$method $path 失败：${e.message}")
            call.respondText(
                """{"error":${ResponseJson.quote(e.message ?: "内部错误")}}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    /** 专用 P4 路由仍走本服务外层鉴权；body 不接受文件路径或媒体字节。 */
    private suspend fun handleP4TestApi(call: ApplicationCall, path: String, method: String) {
        val gateway = p4TestGatewayProvider()
        if (gateway == null) {
            call.respondText("""{"error":"P4 测试入口未启用"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return
        }
        val json = Json { encodeDefaults = true }
        try {
            when {
                path == "/api/p4-test/setup" && method == "POST" -> {
                    val body = call.readBodyText()
                    require(body.length <= 4000) { "请求体过长" }
                    call.respondJson(json.encodeToString(gateway.setup(json.decodeFromString<P4TestSetupRequest>(body))))
                }
                path == "/api/p4-test/state" && method == "GET" -> {
                    val requestId = call.request.queryParameters["requestId"].orEmpty()
                    call.respondJson(json.encodeToString(gateway.state(requestId)))
                }
                path == "/api/p4-test/command" && method == "POST" -> {
                    val body = call.readBodyText()
                    require(body.length <= 20000) { "命令体过长" }
                    call.respondJson(json.encodeToString(gateway.execute(json.decodeFromString<P4TestCommand>(body))))
                }
                else -> call.respondText("""{"error":"接口不存在"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: IllegalArgumentException) {
            call.respondText(ResponseJson.single("error", error.message.orEmpty()), ContentType.Application.Json, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondText(ResponseJson.single("error", error.message.orEmpty()), ContentType.Application.Json, HttpStatusCode.Conflict)
        }
    }

    /** 复用外层鉴权；这些端点按会话复用应用级控制器，不改全局配置、不直接执行 runner。 */
    private suspend fun handleUiApi(call: ApplicationCall, path: String, method: String) {
        val gateway = uiGatewayProvider()
        if (gateway == null) {
            call.respondText("""{"error":"界面调试入口未启用"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return
        }
        val json = Json { encodeDefaults = true }
        try {
            when {
                path == "/api/ui/state" && method == "GET" ->
                    call.respondJson(json.encodeToString(gateway.state(call.request.queryParameters["id"].orEmpty())))
                path == "/api/ui/command" && method == "POST" -> {
                    val body = call.readBodyText()
                    require(body.length <= 100000) { "命令体过长" }
                    call.respondJson(json.encodeToString(gateway.execute(json.decodeFromString<UiTestCommand>(body))))
                }
                else -> call.respondText("""{"error":"接口不存在"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: IllegalArgumentException) {
            call.respondText(ResponseJson.single("error", error.message.orEmpty()), ContentType.Application.Json, HttpStatusCode.BadRequest)
        } catch (error: IllegalStateException) {
            call.respondText(ResponseJson.single("error", error.message.orEmpty()), ContentType.Application.Json, HttpStatusCode.Conflict)
        }
    }

    private suspend fun ApplicationCall.readBodyText(): String = receiveText()

    private suspend fun ApplicationCall.respondJson(json: String) =
        respondText(json, ContentType.Application.Json.withCharset(Charsets.UTF_8))


    /**
     * 鉴权。
     *
     * 只监听 127.0.0.1 时直接放行 —— 除了本机没有别的客户端能连上，密码是多余的摩擦。
     * 开了局域网就必须带对密码，三种携带方式都收：
     *
     * - `?t=<密码>`：浏览器首次打开用（地址栏里直接带上，页面会存进 localStorage 并从地址栏清掉）
     * - `Authorization: Bearer <密码>`：脚本 / curl 用
     * - `X-Log-Token: <密码>`：同上，省得拼 Bearer
     */
    private fun authorized(call: ApplicationCall, lan: Boolean, password: String): Boolean {
        if (!lan) return true
        if (password.isBlank()) return false

        val fromQuery = call.request.queryParameters["t"]
        if (fromQuery == password) return true

        if (call.request.headers["Authorization"] == "Bearer $password") return true
        if (call.request.headers["X-Log-Token"] == password) return true
        return false
    }

    /**
     * 本机在局域网里的 IPv4 地址。
     *
     * 排除 127.0.0.1 与回环段，取第一个非回环地址 —— 就是界面要显示给用户输入的那一个。
     * 不做"哪个网卡是 WiFi"的判断：多网卡（含 VPN）时那套启发式经常猜错，
     * 而把候选全列出来让用户自己挑更可靠。
     */
    fun localIpv4(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
                .orEmpty()
        }.getOrDefault("")
    }

    /** 所有可用的局域网地址（多网卡/VPN 时给用户备选）。 */
    fun allLocalIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
}

/**
 * 给 ContentType 加上 charset。
 *
 * 用的是"从字符串重建"而不是 `ContentType(...)` 三参构造：ktor 的 ContentType 在
 * 带参数时构造函数签名会变，而字符串形式在各版本都稳定。
 * 中文日志**必须显式带 UTF-8**，否则浏览器会按 Latin-1 解，全变乱码。
 */
private fun ContentType.withCharset(charset: java.nio.charset.Charset): ContentType =
    ContentType.parse("$contentType/$contentSubtype; charset=${charset.name()}")
