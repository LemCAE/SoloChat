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
                check(db.stickers().totalStickerCount() == 0) { "旧备份入口不能导出 v11 表情资料，请使用 v4 媒体备份" }
                val dao = db.backup()
                BackupRows(dao.conversations(), dao.messages(), dao.events(), dao.personas(), dao.memories(),
                    dao.summaries(), dao.revisions(), dao.segments(), dao.usages(), dao.calls(),
                    dao.conversationModelBindings(), dao.callConfigSnapshots())
            }
        )
        return rows.toBackupData()
    }

    /** v4 固定同一 Room 快照后，在事务外流式读取不可变原件。 */
    suspend fun readWithMedia(library: MediaLibrary): BackupExportSnapshot {
        val rows = db.withTransaction {
            val backup = db.backup()
            val media = db.media()
            val personas = backup.personas()
            val messages = backup.messages()
            val events = backup.events()
            val calls = backup.calls()
            val attachments = media.allAttachments()
            val userStickers = db.stickers().userStickers()
            val allPersonaStickers = db.stickers().allPersonaStickers()
            val eventStickers = db.stickers().allEventStickers()
            check(eventStickers.isEmpty()) { "当前版本尚不支持 AI 表情事件的备份" }
            val stickerAssets = userStickers.map { it.mediaId } +
                allPersonaStickers.filter { it.status == "ACTIVE" }.mapNotNull { it.mediaId }
            val referencedIds = (attachments.map { it.mediaId } + stickerAssets).toSet()
            val assets = referencedIds.toList().chunked(400).flatMap { media.assets(it) }
            check(assets.map { it.id }.toSet() == referencedIds) { "消息引用的图片资源缺失，无法完整导出" }
            BackupRows(backup.conversations(), messages, events, personas,
                backup.memories(), backup.summaries(), backup.revisions(), backup.segments(), backup.usages(),
                backup.calls(), backup.conversationModelBindings(), backup.callConfigSnapshots(), assets,
                attachments, media.allSubmissions(), media.allCallInputs(),
                userStickers = userStickers,
                personaStickers = allPersonaStickers,
                messageStickers = db.stickers().allMessageStickers(),
                eventStickers = eventStickers,
                typedCallMediaInputs = media.allTypedCallInputs())
        }
        val rawData = rows.toBackupData()
        val messageById = rawData.messages.associateBy { it.id }
        val stickerById = rawData.stickers.personaStickers.associateBy { it.id }
        val mediaIds = rawData.media.assets.map { it.id }.toSet()
        val typedCallInputs = rawData.media.typedCallInputs.map { input -> input.copy(sources = input.sources.map { source ->
            when (source.kind) {
                app.solochat.core.model.BackupCallMediaSource.Kind.MESSAGE -> {
                    val messageId = checkNotNull(source.messageId)
                    if (messageId > 0 && messageById[messageId] == null) source.copy(
                        messageId = -kotlin.math.abs(messageId),
                        mediaId = "missing:message:$messageId:${source.ordinal}") else source
                }
                app.solochat.core.model.BackupCallMediaSource.Kind.PERSONA_STICKER -> {
                    val stickerId = checkNotNull(source.personaStickerId)
                    if (stickerId !in stickerById) source.copy(
                        personaStickerId = "backup:missing-persona-sticker:$stickerId",
                        mediaId = "missing:persona-sticker:$stickerId")
                    else if (source.mediaId !in mediaIds) source.copy(mediaId = "missing:persona-sticker:${source.personaStickerId}")
                    else source
                }
            }
        }) }
        val data = rawData.copy(media = rawData.media.copy(typedCallInputs = typedCallInputs))
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
