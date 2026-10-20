package app.solochat.data

import app.solochat.core.log.LogLevel
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 私有目录中的 UTF-8 JSONL 日志；最多四份各 2MiB，整条记录跨重启保留。 */
class FileLogSink(
    private val directory: File,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val fileCount: Int = 4
) {
    init { require(maxBytes > 0 && fileCount > 0) }

    @Synchronized
    fun write(at: Long, level: LogLevel, tag: String, text: String) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建日志目录" }
        val bytes = (buildJsonObject {
            put("at", at); put("level", level.name); put("tag", tag); put("text", text)
        }.toString() + "\n").toByteArray(Charsets.UTF_8)
        val current = File(directory, "current.jsonl")
        if (current.length() > 0 && current.length() + bytes.size > maxBytes) {
            val oldest = File(directory, "previous-${fileCount - 1}.jsonl")
            if (oldest.exists()) check(oldest.delete())
            for (index in fileCount - 2 downTo 1) {
                val source = File(directory, "previous-$index.jsonl")
                if (source.exists()) check(source.renameTo(File(directory, "previous-${index + 1}.jsonl")))
            }
            if (fileCount > 1) check(current.renameTo(File(directory, "previous-1.jsonl")))
            else check(current.delete())
        }
        // 每次关闭输出流，已写记录不依赖内存缓冲；单条超过预算时完整保留，不切断 JSON。
        current.appendBytes(bytes)
    }

    @Synchronized
    fun export(): String = buildString {
        for (index in fileCount - 1 downTo 1) {
            val file = File(directory, "previous-$index.jsonl")
            if (file.exists()) append(file.readText(Charsets.UTF_8))
        }
        val current = File(directory, "current.jsonl")
        if (current.exists()) append(current.readText(Charsets.UTF_8))
    }
}
