package app.solochat.data

import app.solochat.core.log.LogLevel
import app.solochat.core.log.LogSink
import android.util.Log as AndroidLog
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 把内存日志同时送到 logcat 和私有目录中的轮转文件。
 *
 * 为什么值得多这一跳：有时能连 USB 时，`adb logcat -s SoloChat` 比解锁手机翻界面快得多；
 * 而**崩溃前的最后几条**只有 logcat 才抓得到（进程一死，内存缓冲就没了）。
 *
 * tag 统一加 `SoloChat/` 前缀，这样一条命令就能过滤出全部：
 * ```
 * adb logcat -s SoloChat/模型 SoloChat/编排
 * ```
 */
class AppLogSink(directory: File) : LogSink {
    private val files = FileLogSink(directory)
    // 文件 IO 离开发送和播出路径；队列有界，过载时只向 logcat 报告，不递归写业务日志。
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(512), { task -> Thread(task, "SoloChat-日志").apply { isDaemon = true } })

    fun exportFiles(): String = writer.submit<String> { files.export() }.get()

    override fun write(level: LogLevel, tag: String, text: String) {
        val at = System.currentTimeMillis()
        runCatching {
            writer.execute {
                runCatching { files.write(at, level, tag, text) }
                    .onFailure { AndroidLog.e("SoloChat/日志文件", "日志落盘失败", it) }
            }
        }.onFailure { AndroidLog.w("SoloChat/日志文件", "日志队列已满，当前记录未落盘") }
        val priority = when (level) {
            LogLevel.DEBUG -> AndroidLog.DEBUG
            LogLevel.INFO -> AndroidLog.INFO
            LogLevel.WARN -> AndroidLog.WARN
            LogLevel.ERROR -> AndroidLog.ERROR
        }
        // logcat 单条有长度上限，超长内容会被截断 —— 这里主动分片，
        // 否则长提示词在 logcat 里只剩开头，反而误导。
        val full = "SoloChat/$tag"
        if (text.length <= 3_000) {
            AndroidLog.println(priority, full, text)
        } else {
            text.chunked(3_000).forEachIndexed { index, chunk ->
                AndroidLog.println(priority, full, "[${index + 1}] $chunk")
            }
        }
    }
}
