package app.solochat.data

import app.solochat.core.log.LogLevel
import app.solochat.core.log.LogSink
import android.util.Log as AndroidLog

/**
 * 把内存日志同时送到 logcat。
 *
 * 为什么值得多这一跳：有时能连 USB 时，`adb logcat -s SoloChat` 比解锁手机翻界面快得多；
 * 而**崩溃前的最后几条**只有 logcat 才抓得到（进程一死，内存缓冲就没了）。
 *
 * tag 统一加 `SoloChat/` 前缀，这样一条命令就能过滤出全部：
 * ```
 * adb logcat -s SoloChat/模型 SoloChat/编排
 * ```
 */
class AppLogSink : LogSink {
    override fun write(level: LogLevel, tag: String, text: String) {
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
