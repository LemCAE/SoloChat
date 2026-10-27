package app.solochat.core.backup

import app.solochat.core.model.checksumOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 有界 ZIP，两份 UTF-8 JSON。只读条目，不向文件系统解压。 */
object BackupCodec {
    const val MAX_DATA_BYTES = 64 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_COMPRESSED_BYTES = 1024L * 1024 * 1024
    private val json = Json { encodeDefaults = true }

    /** 不接管外层流；文件适配器负责关闭并处理写出失败。 */
    fun write(data: BackupData, output: OutputStream, createdAt: Long,
        id: String = UUID.randomUUID().toString()): BackupManifest {
        require(data.media == BackupMediaData()) { "v1/v2 备份不能携带媒体资料" }
        require(data.stickers == app.solochat.core.model.BackupStickerData()) { "v1/v2 备份不能携带表情资料" }
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
        return read(input) { _, _ -> error("此调用未提供媒体暂存位置") }
    }

    /** v3 ZIP 的原件逐条流交给调用方暂存；本编解码器不把压缩包或图片载入内存。 */
    fun read(input: InputStream, consumeMedia: (BackupMediaAsset, InputStream) -> Unit): BackupArchive {
        val entries = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var mediaDescriptors: Map<String, BackupMediaAsset>? = null
        var totalMediaBytes = 0L
        val boundedInput = LimitedInputStream(input, MAX_COMPRESSED_BYTES)
        ZipInputStream(boundedInput).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && seen.add(entry.name)) { "备份条目重复或无效" }
                when (entry.name) {
                    "manifest.json" -> entries[entry.name] = readLimited(zip, MAX_MANIFEST_BYTES)
                    "data.json" -> entries[entry.name] = readLimited(zip, MAX_DATA_BYTES)
                    else -> {
                        require(entry.name.startsWith("media/")) { "备份含未知条目或非法路径" }
                        val descriptors = mediaDescriptors ?: run {
                            val manifestBytes = requireNotNull(entries["manifest.json"]) { "备份缺少首部清单" }
                            val dataBytes = requireNotNull(entries["data.json"]) { "备份媒体条目前缺少正文" }
                            val manifest = decodeManifest(manifestBytes)
                            require(manifest.version in 3..4) { "旧版备份不能包含媒体条目" }
                            val data = decodeData(dataBytes, manifest)
                            data.media.assets.distinctBy { it.entryKey }.associateBy { it.entryKey }
                                .also { mediaDescriptors = it }
                        }
                        val descriptor = requireNotNull(descriptors[entry.name]) { "备份含未知条目或非法路径" }
                        require(descriptor.entryKey.startsWith("media/") && descriptor.entryKey == "media/${descriptor.sha256}-${descriptor.byteSize}.bin") {
                            "媒体条目路径无效"
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        val verifier = object : InputStream() {
                            override fun read(): Int {
                                val value = zip.read()
                                if (value >= 0) {
                                    size++
                                    require(size <= descriptor.byteSize && size <= MAX_BACKUP_MEDIA_BYTES &&
                                        totalMediaBytes + size <= MAX_BACKUP_MEDIA_TOTAL_BYTES) { "媒体条目展开超限或长度不符" }
                                    digest.update(value.toByte())
                                }
                                return value
                            }
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val count = zip.read(buffer, offset, length)
                                if (count > 0) {
                                    size += count
                                    require(size <= descriptor.byteSize && size <= MAX_BACKUP_MEDIA_BYTES &&
                                        totalMediaBytes + size <= MAX_BACKUP_MEDIA_TOTAL_BYTES) { "媒体条目展开超限或长度不符" }
                                    digest.update(buffer, offset, count)
                                }
                                return count
                            }
                        }
                        consumeMedia(descriptor, verifier)
                        // Consumer 可能只读声明长度后返回；必须继续走同一个 verifier 到条目 EOF，
                        // 才能检测被消费者遗漏的尾部、真实长度上限和摘要。
                        val drainBuffer = ByteArray(8192)
                        while (verifier.read(drainBuffer) >= 0) Unit
                        require(size == descriptor.byteSize && size <= MAX_BACKUP_MEDIA_BYTES) { "媒体条目长度与清单不符" }
                        val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(actualSha == descriptor.sha256) { "媒体条目摘要校验失败" }
                        totalMediaBytes += size
                        require(totalMediaBytes <= MAX_BACKUP_MEDIA_TOTAL_BYTES) { "媒体总展开体积超限" }
                    }
                }
                zip.closeEntry()
            }
        }
        boundedInput.drain()
        val manifestBytes = requireNotNull(entries["manifest.json"]) { "备份缺少清单" }
        val dataBytes = requireNotNull(entries["data.json"]) { "备份缺少正文" }
        val manifest = decodeManifest(manifestBytes)
        val data = decodeData(dataBytes, manifest)
        val expectedEntries = setOf("manifest.json", "data.json") + if (manifest.version in 3..4)
            data.media.assets.map { it.entryKey }.toSet() else emptySet()
        require(seen == expectedEntries) { "备份条目与清单不一致，存在缺失或多余文件" }
        if (manifest.version in 3..4) require(mediaDescriptors != null || data.media.assets.isEmpty()) { "媒体条目未完整校验" }
        return BackupArchive(manifest, data, boundedInput.bytesRead)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun writeV3(data: BackupData, output: OutputStream, createdAt: Long,
        mediaSources: Map<String, () -> InputStream>, id: String = UUID.randomUUID().toString()): BackupManifest {
        require(data.stickers == app.solochat.core.model.BackupStickerData() && data.media.typedCallInputs.isEmpty()) {
            "v3 备份不能携带表情关系或类型化图片来源"
        }
        validateBackup(data)
        val expected = data.media.assets.distinctBy { it.entryKey }.associateBy { it.entryKey }
        require(mediaSources.keys == expected.keys) { "媒体文件源与清单不一致" }
        val dataBuffer = BoundedByteArrayOutputStream(MAX_DATA_BYTES)
        json.encodeToStream(data, dataBuffer)
        val dataBytes = dataBuffer.toByteArray()
        val manifest = BackupManifest(version = 3, id = id, createdAt = createdAt,
            dataChecksum = checksumOf(dataBytes), counts = data.countsV3())
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(manifest.id.isNotBlank() && manifestBytes.size <= MAX_MANIFEST_BYTES) { "备份清单无效或过大" }
        val bounded = LimitedOutputStream(output, MAX_COMPRESSED_BYTES)
        ZipOutputStream(object : FilterOutputStream(bounded) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush()
        }).use { zip ->
            zipEntry(zip, "manifest.json", manifestBytes)
            zipEntry(zip, "data.json", dataBytes)
            var total = 0L
            for ((entryKey, descriptor) in expected) {
                zip.putNextEntry(ZipEntry(entryKey))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                mediaSources.getValue(entryKey)().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= descriptor.byteSize && size <= MAX_BACKUP_MEDIA_BYTES) { "原件长度超过媒体清单" }
                        total += count
                        require(total <= MAX_BACKUP_MEDIA_TOTAL_BYTES) { "媒体总导出体积超限" }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                require(size == descriptor.byteSize && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == descriptor.sha256) {
                    "媒体原件缺失、长度或摘要不符，备份未完成"
                }
                zip.closeEntry()
            }
        }
        return manifest
    }

    /** v4 承载双收藏、事件快照与完整媒体闭包；旧格式读取语义保持不变。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun writeV4(data: BackupData, output: OutputStream, createdAt: Long,
        mediaSources: Map<String, () -> InputStream>, id: String = UUID.randomUUID().toString()): BackupManifest {
        validateBackup(data)
        val expected = data.media.assets.distinctBy { it.entryKey }.associateBy { it.entryKey }
        require(mediaSources.keys == expected.keys) { "媒体文件源与清单不一致" }
        val dataBuffer = BoundedByteArrayOutputStream(MAX_DATA_BYTES)
        json.encodeToStream(data, dataBuffer)
        val dataBytes = dataBuffer.toByteArray()
        val manifest = BackupManifest(version = 4, id = id, createdAt = createdAt,
            dataChecksum = checksumOf(dataBytes), counts = data.countsV4())
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(manifest.id.isNotBlank() && manifestBytes.size <= MAX_MANIFEST_BYTES) { "备份清单无效或过大" }
        val bounded = LimitedOutputStream(output, MAX_COMPRESSED_BYTES)
        ZipOutputStream(object : FilterOutputStream(bounded) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush()
        }).use { zip ->
            zipEntry(zip, "manifest.json", manifestBytes)
            zipEntry(zip, "data.json", dataBytes)
            var total = 0L
            for ((entryKey, descriptor) in expected) {
                zip.putNextEntry(ZipEntry(entryKey))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                mediaSources.getValue(entryKey)().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= descriptor.byteSize && size <= MAX_BACKUP_MEDIA_BYTES) { "原件长度超过媒体清单" }
                        total += count
                        require(total <= MAX_BACKUP_MEDIA_TOTAL_BYTES) { "媒体总导出体积超限" }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                require(size == descriptor.byteSize && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == descriptor.sha256) {
                    "媒体原件缺失、长度或摘要不符，备份未完成"
                }
                zip.closeEntry()
            }
        }
        return manifest
    }

    private fun zipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }

    private fun decodeManifest(bytes: ByteArray): BackupManifest {
        val manifest = json.decodeFromString<BackupManifest>(bytes.decodeToString(throwOnInvalidSequence = true))
        require(manifest.format == "solochat-backup" && manifest.version in 1..4) { "不支持的备份格式或版本" }
        require(manifest.id.isNotBlank() && manifest.excluded == BACKUP_EXCLUDED) { "备份清单无效或排除规则不支持" }
        return manifest
    }

    private fun decodeData(bytes: ByteArray, manifest: BackupManifest): BackupData {
        val content = bytes.decodeToString(throwOnInvalidSequence = true)
        require(manifest.dataChecksum == checksumOf(content)) { "备份正文校验失败" }
        val dataObject = json.parseToJsonElement(content) as? JsonObject ?: error("备份正文必须是 JSON 对象")
        if (manifest.version == 1) require("conversationModelBindings" !in dataObject && "callConfigSnapshots" !in dataObject) {
            "v1 备份不能携带 v2 配置字段"
        }
        if (manifest.version < 3) require("media" !in dataObject) { "v1/v2 备份不能携带媒体字段" }
        if (manifest.version < 4) {
            require("stickers" !in dataObject) { "v1/v2/v3 备份不能携带表情字段" }
            val media = dataObject["media"] as? kotlinx.serialization.json.JsonObject
            require(media == null || "typedCallInputs" !in media) { "v1/v2/v3 备份不能携带类型化图片来源" }
        }
        val data = json.decodeFromString<BackupData>(content)
        val expectedCounts = when (manifest.version) { 1 -> data.counts(); 2 -> data.countsV2(); 3 -> data.countsV3(); else -> data.countsV4() }
        require(manifest.counts == expectedCounts) { "备份数量与清单不一致" }
        validateBackup(data)
        return data
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

    private fun checksumOf(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private class BoundedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < limit) { "备份正文超过 64 MiB" }
            super.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count.toLong() + length <= limit) { "备份正文超过 64 MiB" }
            super.write(buffer, offset, length)
        }
    }

    private class LimitedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
        var bytesRead = 0L
            private set
        override fun read(): Int {
            val value = source.read()
            if (value >= 0) { bytesRead++; require(bytesRead <= limit) { "压缩备份超过 1 GiB" } }
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, length)
            if (read > 0) { bytesRead += read; require(bytesRead <= limit) { "压缩备份超过 1 GiB" } }
            return read
        }
        override fun close() = Unit
        fun drain() {
            val buffer = ByteArray(8192)
            while (true) if (read(buffer) < 0) break
        }
    }

    private class LimitedOutputStream(private val target: OutputStream, private val limit: Long) : OutputStream() {
        private var count = 0L
        override fun write(value: Int) { count++; require(count <= limit) { "压缩备份超过 1 GiB" }; target.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            count += length; require(count <= limit) { "压缩备份超过 1 GiB" }; target.write(buffer, offset, length)
        }
        override fun flush() = target.flush()
        override fun close() = flush()
    }
}
