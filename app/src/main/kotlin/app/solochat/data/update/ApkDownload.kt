package app.solochat.data.update

import android.content.Context
import app.solochat.core.update.ReleaseAsset
import app.solochat.core.update.ReleaseInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class DownloadPhase { IDLE, DOWNLOADING, CANCELED, FAILED, COMPLETE }
internal data class DownloadTarget(val tag: String, val asset: ReleaseAsset)
internal data class ApkDownloadState(val target: DownloadTarget? = null,
    val phase: DownloadPhase = DownloadPhase.IDLE, val received: Long = 0,
    val file: File? = null, val error: String = "")

/** 主线程接收命令；应用级持有，离开页面不取消，也不在进程重启后自动下载。 */
internal class ApkDownloadController(private val scope: CoroutineScope,
    private val transfer: suspend (ReleaseAsset, (Long) -> Unit) -> File) {
    private val mutable = MutableStateFlow(ApkDownloadState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var attemptId = 0L

    fun start(release: ReleaseInfo) {
        val asset = release.apkAsset ?: return
        val target = DownloadTarget(release.tag, asset)
        if (job?.isActive == true) return
        val previous = mutable.value
        if (previous.target == target && previous.phase == DownloadPhase.COMPLETE && previous.file?.isFile == true) return
        previous.file?.delete()
        val id = ++attemptId
        mutable.value = ApkDownloadState(target, DownloadPhase.DOWNLOADING)
        job = scope.launch {
            try {
                val attempt = currentCoroutineContext()
                val file = transfer(asset) { count ->
                    scope.launch {
                        if (id == attemptId && attempt.isActive && mutable.value.phase == DownloadPhase.DOWNLOADING)
                            mutable.value = mutable.value.copy(received = count)
                    }
                }
                if (id == attemptId) mutable.value = mutable.value.copy(phase = DownloadPhase.COMPLETE, file = file, received = asset.size)
                else file.delete()
            } catch (e: CancellationException) {
                if (id == attemptId) mutable.value = mutable.value.copy(phase = DownloadPhase.CANCELED)
                throw e
            } catch (e: Exception) {
                if (id == attemptId) mutable.value = mutable.value.copy(phase = DownloadPhase.FAILED,
                    error = (e as? IOException)?.message ?: "下载失败，请重试或打开发布页")
            }
        }
    }

    fun cancel() {
        if (mutable.value.phase == DownloadPhase.DOWNLOADING) {
            job?.cancel()
            mutable.value = mutable.value.copy(phase = DownloadPhase.CANCELED)
        }
    }
    fun retry() {
        val target = mutable.value.target ?: return
        start(ReleaseInfo(target.tag, assets = listOf(target.asset)))
    }
}

/** 只传公开下载地址；专用无鉴权客户端允许 GitHub CDN 的 HTTPS 重定向。 */
internal class ApkDownloadClient(private val directory: File) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun download(asset: ReleaseAsset, progress: (Long) -> Unit): File = suspendCancellableCoroutine { continuation ->
        if (!ReleaseInfo("", assets = listOf(asset)).apkReady) {
            continuation.resumeWithException(IOException("该发布没有可下载的安装包"))
            return@suspendCancellableCoroutine
        }
        val call = http.newCall(Request.Builder().url(asset.downloadUrl).header("User-Agent", "SoloChat-Update").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("下载连接失败，请重试或打开发布页"))
            }
            override fun onResponse(call: Call, response: Response) {
                // 每次尝试独占文件，取消后的网络收尾不能删掉下一次重试的文件。
                val part = File(directory, "${UUID.randomUUID()}.part")
                val completed = File(directory, "${part.nameWithoutExtension}.apk")
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException("下载失败（HTTP ${it.code}），请稍后重试")
                        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建下载目录")
                        if (directory.usableSpace < asset.size) throw IOException("存储空间不足，请腾出空间后重试")
                        val body = it.body ?: throw IOException("下载内容为空，请重试")
                        if (body.contentLength() >= 0 && body.contentLength() != asset.size)
                            throw IOException("安装包大小已变化，请重新检查更新")
                        body.byteStream().use { input -> part.outputStream().use { output ->
                            copyApk(input, output, asset.size, { continuation.isActive }, progress)
                        } }
                        if (!part.renameTo(completed)) throw IOException("无法保存安装包，请重试")
                    }
                    continuation.resume(completed) { _, file, _ -> file.delete() }
                } catch (e: Exception) {
                    completed.delete()
                    if (continuation.isActive) continuation.resumeWithException(
                        if (e is IOException) e else IOException("下载失败，请重试"))
                } finally { part.delete() }
            }
        })
    }
}

/** 预期大小来自所选 asset；不把截断或超长响应标成下载完成。 */
internal fun copyApk(input: InputStream, output: OutputStream, expected: Long,
    active: () -> Boolean, progress: (Long) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    var received = 0L
    var reported = 0L
    while (true) {
        if (!active()) throw CancellationException()
        val count = input.read(buffer)
        if (count < 0) break
        received += count
        if (received > expected) throw IOException("安装包大小已变化，请重新检查更新")
        output.write(buffer, 0, count)
        if (received - reported >= 256 * 1024 || received == expected) {
            progress(received)
            reported = received
        }
    }
    if (!active()) throw CancellationException()
    if (received != expected) throw IOException("安装包未下载完整，请重试")
}

internal object AppApkDownload {
    @Volatile private var instance: ApkDownloadController? = null
    fun get(context: Context): ApkDownloadController = instance ?: synchronized(this) {
        instance ?: ApkDownloadController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ApkDownloadClient(File(context.applicationContext.cacheDir, "updates"))::download).also { instance = it }
    }
}
