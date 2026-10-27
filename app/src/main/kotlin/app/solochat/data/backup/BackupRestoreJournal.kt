package app.solochat.data.backup

import app.solochat.core.backup.BackupManifest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 已确认恢复操作的私有续接记录；ZIP 保留到同一操作提交或用户明确取消。 */
class BackupRestoreJournal(private val directory: File) {
    private val json = Json { encodeDefaults = true }
    private val recordFile get() = File(directory, "operation.json")
    private val archiveFile get() = File(directory, "source.zip")

    fun exists(): Boolean = recordFile.exists() || directory.listFiles()?.isNotEmpty() == true

    @Serializable
    data class Record(val importId: String, val backupId: String, val dataChecksum: String, val createdAt: Long)

    fun persist(sourceZip: File?, importId: String, manifest: BackupManifest): Record {
        directory.parentFile?.mkdirs()
        if (recordFile.isFile) {
            val existing = load() ?: error("恢复续接记录不完整")
            require(existing.importId == importId && existing.backupId == manifest.id &&
                existing.dataChecksum == manifest.dataChecksum) { "已有另一项待续接恢复操作" }
            return existing
        }
        require(sourceZip != null && sourceZip.isFile && sourceZip.length() in 1..(1024L * 1024 * 1024)) { "恢复备份暂存文件无效" }
        val staging = File(directory.parentFile, ".${directory.name}.${java.util.UUID.randomUUID()}.tmp")
        check(staging.mkdirs()) { "无法创建恢复续接目录" }
        try {
            val zip = File(staging, "source.zip")
            FileInputStream(sourceZip).use { input -> FileOutputStream(zip).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            } }
            check(zip.length() == sourceZip.length() && sha256(zip) == sha256(sourceZip)) { "恢复备份暂存不完整" }
            val record = Record(importId, manifest.id, manifest.dataChecksum, manifest.createdAt)
            val recordTemp = File(staging, "operation.tmp")
            FileOutputStream(recordTemp).use { output ->
                output.write(json.encodeToString(record).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(recordTemp.toPath(), File(staging, "operation.json").toPath(), ATOMIC_MOVE)
            if (directory.exists()) error("恢复续接目录已存在")
            Files.move(staging.toPath(), directory.toPath(), ATOMIC_MOVE)
            return record
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun load(): Record? {
        if (!recordFile.isFile) return null
        val record = json.decodeFromString<Record>(recordFile.readText(Charsets.UTF_8))
        require(record.importId.isNotBlank() && record.backupId.isNotBlank() &&
            record.dataChecksum.matches(Regex("[0-9a-f]{64}"))) { "恢复续接元数据无效" }
        return record
    }

    fun sourceZip(record: Record): File {
        require(load() == record) { "恢复续接记录已变化" }
        require(archiveFile.isFile && archiveFile.length() in 1..(1024L * 1024 * 1024)) { "恢复续接备份文件缺失或无效" }
        return archiveFile
    }

    fun clear() {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: error("无法读取恢复续接目录")
        files.filter { it != recordFile }.forEach { file ->
            check(if (file.isDirectory) file.deleteRecursively() else file.delete()) { "恢复续接文件清理失败" }
        }
        if (recordFile.exists()) check(recordFile.delete()) { "恢复续接记录清理失败" }
        // 空目录残留不再代表待续接操作；不要让目录元数据失败覆盖已经清掉的操作记录。
        directory.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class BackupRestoreAttempt(val result: BackupRestoreResult, val cleanupError: Exception? = null)

/** 写库失败保留续接记录；写库成功后清理失败则保留结果和记录供幂等重试。 */
class BackupRestoreRunner(
    private val journal: BackupRestoreJournal,
    private val clearJournal: () -> Unit = journal::clear
) {
    suspend fun run(
        sourceZip: File?,
        importId: String,
        manifest: BackupManifest,
        restore: suspend () -> BackupRestoreResult
    ): BackupRestoreAttempt {
        journal.persist(sourceZip, importId, manifest)
        val result = restore()
        return try {
            withContext(NonCancellable) { clearJournal() }
            BackupRestoreAttempt(result)
        } catch (error: Exception) {
            BackupRestoreAttempt(result, error)
        }
    }
}

/** 屏蔽启动续接检查期间的页面操作，并拒绝已失效的异步完成回调。 */
class BackupRestoreBootstrapGate {
    private var generation = 0L
    private var complete = false

    @Synchronized fun begin(): Long {
        complete = false
        return ++generation
    }

    @Synchronized fun isCurrent(token: Long): Boolean = token == generation && !complete

    @Synchronized fun finish(token: Long): Boolean {
        if (token != generation || complete) return false
        complete = true
        return true
    }

    @Synchronized fun invalidate() {
        generation++
        complete = false
    }

    @Synchronized fun canStartPageAction(): Boolean = complete
}
