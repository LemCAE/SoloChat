package app.solochat.core.backup

import app.solochat.core.model.checksumOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 有界 ZIP，两份 UTF-8 JSON。只读条目，不向文件系统解压。 */
object BackupCodec {
    const val MAX_DATA_BYTES = 64 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private val json = Json { encodeDefaults = true }

    /** 不接管外层流；文件适配器负责关闭并处理写出失败。 */
    fun write(data: BackupData, output: OutputStream, createdAt: Long,
        id: String = UUID.randomUUID().toString()): BackupManifest {
        validateBackup(data)
        val content = json.encodeToString(data)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_DATA_BYTES) { "备份内容超过 64 MiB" }
        val manifest = BackupManifest(version = 2, id = id, createdAt = createdAt,
            dataChecksum = checksumOf(content), counts = data.countsV2())
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(manifest.id.isNotBlank() && manifestBytes.size <= MAX_MANIFEST_BYTES) { "备份清单无效或过大" }
        // 关闭 ZIP 释放压缩器，但不替 SAF 等外层适配器关闭它持有的流。
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush()
        }).use { zip ->
            for ((name, body) in listOf("manifest.json" to manifestBytes, "data.json" to bytes)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return manifest
    }

    fun read(input: InputStream): BackupArchive {
        // 同时限制压缩文件及展开正文，不能只信 ZIP 声明的 size。
        val archiveBytes = readLimited(input, MAX_DATA_BYTES + MAX_MANIFEST_BYTES + 1024 * 1024)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(archiveBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val limit = when (entry.name) {
                    "manifest.json" -> MAX_MANIFEST_BYTES
                    "data.json" -> MAX_DATA_BYTES
                    else -> throw IllegalArgumentException("备份含未知条目或非法路径")
                }
                require(!entry.isDirectory && entry.name !in entries) { "备份条目重复或无效" }
                entries[entry.name] = readLimited(zip, limit)
                zip.closeEntry()
            }
        }
        require(entries.keys == setOf("manifest.json", "data.json")) { "备份缺少清单或正文" }
        val manifest = json.decodeFromString<BackupManifest>(entries.getValue("manifest.json").decodeToString(throwOnInvalidSequence = true))
        require(manifest.format == "solochat-backup" && manifest.version in 1..2) { "不支持的备份格式或版本" }
        require(manifest.id.isNotBlank() && manifest.excluded == BACKUP_EXCLUDED) { "备份清单无效或排除规则不支持" }
        val content = entries.getValue("data.json").decodeToString(throwOnInvalidSequence = true)
        require(manifest.dataChecksum == checksumOf(content)) { "备份正文校验失败" }
        val dataObject = json.parseToJsonElement(content) as? JsonObject ?: error("备份正文必须是 JSON 对象")
        if (manifest.version == 1) require(
            "conversationModelBindings" !in dataObject && "callConfigSnapshots" !in dataObject
        ) { "v1 备份不能携带 v2 配置字段" }
        val data = json.decodeFromString<BackupData>(content)
        val expectedCounts = if (manifest.version == 1) data.counts() else data.countsV2()
        require(manifest.counts == expectedCounts) { "备份数量与清单不一致" }
        validateBackup(data)
        return BackupArchive(manifest, data)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            size += n
            require(size <= limit) { "备份文件或展开内容过大" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
