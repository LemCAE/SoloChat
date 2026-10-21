package app.solochat.data.db

import androidx.room.Dao
import androidx.room.Query

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
}
