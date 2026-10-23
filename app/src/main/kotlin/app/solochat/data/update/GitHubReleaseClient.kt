package app.solochat.data.update

import app.solochat.core.update.ReleaseInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 专用无鉴权客户端，不复用模型请求的地址、拦截器或凭据。 */
class GitHubReleaseClient {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun releases(): List<ReleaseInfo> {
        val result = mutableListOf<ReleaseInfo>()
        for (page in 1..10) {
            val request = Request.Builder().url("https://api.github.com/repos/LemCAE/SoloChat/releases?per_page=100&page=$page")
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "SoloChat-Update").build()
            val (body, more) = fetch(request)
            result += json.decodeFromString<List<ReleaseInfo>>(body)
            if (!more) return result
        }
        throw IOException("发布记录过多，暂不能完整比较，请打开版本页查看")
    }

    private suspend fun fetch(request: Request): Pair<String, Boolean> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("连接 GitHub 失败，请稍后重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) {
                            val wait = it.header("Retry-After")?.toLongOrNull()?.let { seconds -> "，请在 $seconds 秒后重试" }
                                ?: it.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it > System.currentTimeMillis() / 1000 }
                                    ?.let { reset -> "，请在 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                        .format(java.util.Date(reset * 1000)) + " 后重试" }.orEmpty()
                            throw IOException(if (it.code == 403 || it.code == 429) "GitHub 拒绝或限制了本次检查$wait"
                                else "GitHub 检查失败（HTTP ${it.code}）")
                        }
                        val body = it.body?.string() ?: throw IOException("GitHub 返回了空内容")
                        val more = it.header("Link").orEmpty().contains("rel=\"next\"")
                        if (continuation.isActive) continuation.resume(body to more)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
}
