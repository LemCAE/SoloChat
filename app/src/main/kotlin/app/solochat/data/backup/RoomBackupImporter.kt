package app.solochat.data.backup

import androidx.room.withTransaction
import app.solochat.core.backup.*
import app.solochat.data.db.*
import app.solochat.data.MediaLibrary
import app.solochat.data.toEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** 文件已在事务前完整读取并确认；数据、来源、账目隔离和暂停标记一次提交。 */
data class BackupRestoreResult(val importId: String, val conversationCount: Int, val alreadyCommitted: Boolean)

class RoomBackupImporter(private val db: SoloChatDatabase) {
    suspend fun committedResult(importId: String, backupId: String, checksum: String): BackupRestoreResult? {
        val previous = db.backup().importRecord(importId) ?: return null
        require(previous.sourceBackupId == backupId && previous.sourceChecksum == checksum) {
            "恢复续接 ID 已关联另一份备份"
        }
        return BackupRestoreResult(importId, db.backup().conversationsForImport(importId).size, true)
    }

    /** 旧适配入口供既有 v1/v2 Room 恢复路径使用；不接受媒体字段。 */
    suspend fun restore(archive: BackupArchive, at: Long): BackupImportPlan {
        require(archive.manifest.version <= 2 && archive.data.media == app.solochat.core.backup.BackupMediaData()) {
            "旧恢复入口不接受 v3 媒体备份"
        }
        return db.withTransaction {
            val dao = db.backup()
            val plan = planBackupImport(archive,
                BackupTargetIds(dao.lastMessageId(), dao.lastEventId(), dao.lastUsageId()))
            val data = plan.data
            val rows = data.toBackupRows()
            dao.addPersonas(rows.personas)
            dao.addConversations(rows.conversations)
            dao.addMessages(rows.messages)
            dao.addEvents(rows.events)
            dao.addMemories(rows.memories)
            dao.addSummaries(rows.summaries)
            dao.addRevisions(rows.revisions)
            dao.addSegments(rows.segments)
            dao.addUsages(rows.usages)
            dao.addCalls(rows.calls)
            dao.addConversationModelBindings(rows.conversationModelBindings)
            dao.addCallConfigSnapshots(rows.callConfigSnapshots)
            dao.addImport(BackupImportEntity(plan.importId, plan.sourceBackupId, archive.manifest.dataChecksum,
                archive.manifest.createdAt, at))
            dao.addOrigins(plan.pausedConversationIds.map { BackupOriginEntity("conversation", it, plan.importId, true) } +
                data.calls.map { BackupOriginEntity("call", it.record.id, plan.importId, false) } +
                data.usages.map { BackupOriginEntity("usage", it.id.toString(), plan.importId, false) })
            plan
        }
    }

    suspend fun restore(archive: BackupArchive, mediaFiles: Map<String, File>, library: MediaLibrary,
        at: Long, importId: String): BackupRestoreResult = library.withBackupImportLock {
        val previous = db.withTransaction { db.backup().importRecord(importId) }
        if (previous != null) {
            require(previous.sourceBackupId == archive.manifest.id && previous.sourceChecksum == archive.manifest.dataChecksum) {
                "导入操作 ID 已被其他备份占用"
            }
            val count = db.backup().conversationsForImport(importId).size
            return@withBackupImportLock BackupRestoreResult(importId, count, true)
        }

        val expectedEntries = archive.data.media.assets.distinctBy { it.entryKey }.map { it.entryKey }.toSet()
        require(mediaFiles.keys == expectedEntries) { "恢复媒体暂存文件与清单不一致" }
        require((archive.manifest.version in 3..4) || archive.data.media == app.solochat.core.backup.BackupMediaData()) {
            "旧版备份不能包含媒体资料"
        }
        val promoted = linkedMapOf<String, MediaLibrary.BackupPromotion>()
        try {
            archive.data.media.assets.distinctBy { it.entryKey }.forEach { descriptor ->
                promoted[descriptor.entryKey] = library.promoteBackupAsset(descriptor,
                    mediaFiles.getValue(descriptor.entryKey), UUID.randomUUID().toString(), at)
            }
            val mediaIdMapping = archive.data.media.assets.associate { descriptor ->
                descriptor.id to promoted.getValue(descriptor.entryKey).asset.id
            }
            val result = db.withTransaction {
                val dao = db.backup()
                val committed = dao.importRecord(importId)
                if (committed != null) {
                    require(committed.sourceBackupId == archive.manifest.id && committed.sourceChecksum == archive.manifest.dataChecksum) {
                        "导入操作 ID 已被其他备份占用"
                    }
                    return@withTransaction BackupRestoreResult(importId, dao.conversationsForImport(importId).size, true)
                }
                val plan = planBackupImport(archive,
                    BackupTargetIds(dao.lastMessageId(), dao.lastEventId(), dao.lastUsageId()),
                    operationImportId = importId, mediaIdMapping = mediaIdMapping)
                var data = plan.data
                val mediaDao = db.media()
                val assetsToInsert = data.media.assets.map { descriptor -> promoted.getValue(descriptor.entryKey).asset }
                    .distinctBy { it.id }.filter { mediaDao.asset(it.id) == null }
                if (assetsToInsert.isNotEmpty()) mediaDao.insertAssets(assetsToInsert.map { it.toEntity() })
                if (archive.manifest.version == 4) {
                    var nextOrder = db.stickers().nextUserSortOrder()
                    val userIdMapping = linkedMapOf<String, String>()
                    val restoredUsers = data.stickers.userStickers.map { sticker ->
                        val existing = db.stickers().userStickerByMedia(sticker.mediaId)
                        if (existing != null) {
                            userIdMapping[sticker.id] = existing.id
                            sticker.copy(id = existing.id, sortOrder = existing.sortOrder)
                        } else {
                            userIdMapping[sticker.id] = sticker.id
                            sticker.copy(sortOrder = nextOrder++)
                        }
                    }
                    data = data.copy(stickers = data.stickers.copy(
                        userStickers = restoredUsers,
                        messageStickers = data.stickers.messageStickers.map { row ->
                            row.copy(sourceUserStickerId = row.sourceUserStickerId?.let { userIdMapping[it] ?: it })
                        }
                    ))
                }
                val rows = data.toBackupRows()
                dao.addPersonas(rows.personas)
                dao.addConversations(rows.conversations)
                dao.addMessages(rows.messages)
                dao.addEvents(rows.events)
                dao.addMemories(rows.memories)
                dao.addSummaries(rows.summaries)
                dao.addRevisions(rows.revisions)
                dao.addSegments(rows.segments)
                dao.addUsages(rows.usages)
                dao.addCalls(rows.calls)
                dao.addConversationModelBindings(rows.conversationModelBindings)
                dao.addCallConfigSnapshots(rows.callConfigSnapshots)
                if (data.media.attachments.isNotEmpty()) mediaDao.insertAttachments(data.media.attachments.map {
                    MessageAttachmentEntity(it.messageId, it.ordinal, it.mediaId)
                })
                if (data.media.submissions.isNotEmpty()) mediaDao.insertSubmissions(data.media.submissions.map {
                    InputSubmissionEntity(it.id, it.conversationId, it.payloadChecksum,
                        kotlinx.serialization.json.Json.encodeToString(it.messageIds), it.createdAt)
                })
                if (data.media.callInputs.isNotEmpty()) mediaDao.insertCallInputs(data.media.callInputs.map {
                    CallMediaInputEntity(it.callId, it.inputJson, it.recordedAt)
                })
                if (data.media.typedCallInputs.isNotEmpty()) mediaDao.insertTypedCallInputs(data.media.typedCallInputs.map { it.toEntity() })
                rows.userStickers.filter { db.stickers().userSticker(it.id) == null }.forEach { db.stickers().insertUserSticker(it) }
                rows.personaStickers.forEach { db.stickers().insertPersonaSticker(it) }
                rows.messageStickers.forEach { db.stickers().insertMessageSticker(it) }
                rows.eventStickers.forEach { db.stickers().insertEventSticker(it) }
                rows.stickerTurnResults.forEach { db.stickers().insertTurnResult(it) }
                dao.addImport(BackupImportEntity(plan.importId, plan.sourceBackupId, archive.manifest.dataChecksum,
                    archive.manifest.createdAt, at))
                dao.addOrigins(plan.pausedConversationIds.map { BackupOriginEntity("conversation", it, plan.importId, true) } +
                    data.calls.map { BackupOriginEntity("call", it.record.id, plan.importId, false) } +
                    data.usages.map { BackupOriginEntity("usage", it.id.toString(), plan.importId, false) })
                BackupRestoreResult(importId, data.conversations.size, false)
            }
            return@withBackupImportLock result
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                promoted.values.mapNotNull { it.createdFile }.distinct().forEach { file ->
                    val matching = archive.data.media.assets.firstOrNull { descriptor ->
                        promoted[descriptor.entryKey]?.createdFile == file
                    }
                    if (matching != null) {
                        val published = runCatching { db.media().assetByDigest(matching.sha256, matching.byteSize) }
                        if (published.isSuccess && published.getOrNull() == null) file.delete()
                    }
                }
            }
            throw failure
        }
    }
}

private fun app.solochat.core.model.MediaAsset.toEntity() = MediaAssetEntity(id, sha256, mimeType, byteSize,
    width, height, orientation, fileKey, createdAt)
