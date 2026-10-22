package app.solochat.data.backup

import androidx.room.withTransaction
import app.solochat.core.backup.*
import app.solochat.data.db.*

/** 文件已在事务前完整读取并确认；数据、来源、账目隔离和暂停标记一次提交。 */
class RoomBackupImporter(private val db: SoloChatDatabase) {
    suspend fun restore(archive: BackupArchive, at: Long): BackupImportPlan = db.withTransaction {
        val dao = db.backup()
        val plan = planBackupImport(archive, BackupTargetIds(dao.lastMessageId(), dao.lastEventId(), dao.lastUsageId()))
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
        dao.addImport(BackupImportEntity(plan.importId, plan.sourceBackupId, archive.manifest.dataChecksum,
            archive.manifest.createdAt, at))
        dao.addOrigins(plan.pausedConversationIds.map { BackupOriginEntity("conversation", it, plan.importId, true) } +
            data.calls.map { BackupOriginEntity("call", it.record.id, plan.importId, false) } +
            data.usages.map { BackupOriginEntity("usage", it.id.toString(), plan.importId, false) })
        plan
    }
}
