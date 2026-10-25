package app.solochat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert

/** 备份专用全量读取，不复用带时间范围、分页或统计排除条件的界面查询。 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM conversation ORDER BY id")
    suspend fun conversations(): List<ConversationEntity>
    @Query("SELECT * FROM message ORDER BY id")
    suspend fun messages(): List<MessageEntity>
    @Query("SELECT * FROM event ORDER BY id")
    suspend fun events(): List<EventEntity>
    @Query("SELECT * FROM persona ORDER BY id")
    suspend fun personas(): List<PersonaEntity>
    @Query("SELECT * FROM memory ORDER BY id")
    suspend fun memories(): List<MemoryEntity>
    @Query("SELECT * FROM scene_summary ORDER BY id")
    suspend fun summaries(): List<SummaryEntity>
    @Query("SELECT * FROM context_revision ORDER BY id")
    suspend fun revisions(): List<ContextRevisionEntity>
    @Query("SELECT * FROM context_segment ORDER BY revisionId, ordinal")
    suspend fun segments(): List<ContextSegmentEntity>
    @Query("SELECT * FROM usage ORDER BY id")
    suspend fun usages(): List<UsageEntity>
    @Query("SELECT * FROM call_record ORDER BY id")
    suspend fun calls(): List<CallRecordEntity>
    @Query("SELECT * FROM conversation_model_binding ORDER BY conversationId")
    suspend fun conversationModelBindings(): List<ConversationModelBindingEntity>
    @Query("SELECT * FROM call_config_snapshot ORDER BY callId")
    suspend fun callConfigSnapshots(): List<CallConfigSnapshotEntity>

    @Query("SELECT IFNULL(MAX(id), 0) FROM message") suspend fun lastMessageId(): Long
    @Query("SELECT IFNULL(MAX(id), 0) FROM event") suspend fun lastEventId(): Long
    @Query("SELECT IFNULL(MAX(id), 0) FROM usage") suspend fun lastUsageId(): Long
    @Query("SELECT COUNT(*) FROM backup_import WHERE sourceBackupId = :backupId")
    suspend fun previousImports(backupId: String): Int
    @Query("SELECT recordId FROM backup_origin WHERE kind = :kind")
    suspend fun importedIds(kind: String): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM backup_origin WHERE kind = 'conversation' AND recordId = :id AND paused = 1)")
    suspend fun paused(id: String): Boolean
    @Query("SELECT conversation.* FROM conversation INNER JOIN backup_origin ON backup_origin.recordId = conversation.id WHERE backup_origin.kind = 'conversation' AND backup_origin.paused = 1 ORDER BY conversation.updatedAt DESC")
    suspend fun pausedConversations(): List<ConversationEntity>
    @Query("UPDATE backup_origin SET paused = 0 WHERE kind = 'conversation' AND recordId = :id")
    suspend fun resume(id: String)
    @Query("DELETE FROM backup_origin WHERE kind IN ('call', 'usage')")
    suspend fun clearAccountingOrigins()

    // 刻意使用默认 ABORT；与日常 upsert 分开，任何身份冲突都回滚本次导入。
    @Insert suspend fun addConversations(rows: List<ConversationEntity>)
    @Insert suspend fun addMessages(rows: List<MessageEntity>)
    @Insert suspend fun addEvents(rows: List<EventEntity>)
    @Insert suspend fun addPersonas(rows: List<PersonaEntity>)
    @Insert suspend fun addMemories(rows: List<MemoryEntity>)
    @Insert suspend fun addSummaries(rows: List<SummaryEntity>)
    @Insert suspend fun addRevisions(rows: List<ContextRevisionEntity>)
    @Insert suspend fun addSegments(rows: List<ContextSegmentEntity>)
    @Insert suspend fun addUsages(rows: List<UsageEntity>)
    @Insert suspend fun addCalls(rows: List<CallRecordEntity>)
    @Insert suspend fun addConversationModelBindings(rows: List<ConversationModelBindingEntity>)
    @Insert suspend fun addCallConfigSnapshots(rows: List<CallConfigSnapshotEntity>)
    @Insert suspend fun addImport(row: BackupImportEntity)
    @Insert suspend fun addOrigins(rows: List<BackupOriginEntity>)
}
