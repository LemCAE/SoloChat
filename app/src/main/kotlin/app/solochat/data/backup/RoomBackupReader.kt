package app.solochat.data.backup

import androidx.room.withTransaction
import app.solochat.data.db.SoloChatDatabase
import app.solochat.data.MediaLibrary
import app.solochat.core.backup.BackupData
import app.solochat.core.backup.validateBackup
import app.solochat.core.model.MediaAsset
import app.solochat.data.db.MediaAssetEntity
import java.io.FileInputStream
import java.io.InputStream

/** 不修改业务表；包括归档会话、全部历史及已删除会话遗留的记忆和账目。 */
class RoomBackupReader(private val db: SoloChatDatabase) {
    suspend fun read(): BackupData {
        val rows = readLegacyBackupSnapshot(
            transaction = { block -> db.withTransaction { block() } },
            attachmentCount = { db.media().attachmentCount() },
            readRows = {
                val dao = db.backup()
                BackupRows(dao.conversations(), dao.messages(), dao.events(), dao.personas(), dao.memories(),
                    dao.summaries(), dao.revisions(), dao.segments(), dao.usages(), dao.calls(),
                    dao.conversationModelBindings(), dao.callConfigSnapshots())
            }
        )
        return rows.toBackupData()
    }

    /** v3 固定同一 Room 快照后，在事务外流式读取不可变原件。 */
    suspend fun readWithMedia(library: MediaLibrary): BackupExportSnapshot {
        val rows = db.withTransaction {
            val backup = db.backup()
            val media = db.media()
            val attachments = media.allAttachments()
            val referencedIds = attachments.map { it.mediaId }.toSet()
            val assets = media.messageAssets()
            check(assets.map { it.id }.toSet() == referencedIds) { "消息引用的图片资源缺失，无法完整导出" }
            BackupRows(backup.conversations(), backup.messages(), backup.events(), backup.personas(),
                backup.memories(), backup.summaries(), backup.revisions(), backup.segments(), backup.usages(),
                backup.calls(), backup.conversationModelBindings(), backup.callConfigSnapshots(), assets,
                attachments, media.allSubmissions(), media.allCallInputs())
        }
        val data = rows.toBackupData()
        validateBackup(data)
        val models = rows.mediaAssets.map { it.toBackupModel() }.associateBy { it.id }
        val sources = data.media.assets.distinctBy { it.entryKey }.associate { descriptor ->
            val asset = models.getValue(descriptor.id)
            val source = library.backupSource(asset)
            descriptor.entryKey to { FileInputStream(source) }
        }
        return BackupExportSnapshot(data, sources,
            data.media.assets.distinctBy { it.entryKey }.sumOf { it.byteSize })
    }
}

data class BackupExportSnapshot(val data: BackupData, val mediaSources: Map<String, () -> InputStream>, val mediaBytes: Long)

private fun MediaAssetEntity.toBackupModel() = MediaAsset(id, sha256, mimeType, byteSize, width, height,
    orientation, fileKey, createdAt)
