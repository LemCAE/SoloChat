package app.solochat.data.backup

import androidx.room.withTransaction
import app.solochat.core.backup.BackupData
import app.solochat.data.db.SoloChatDatabase

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
}
