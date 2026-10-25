package app.solochat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation ORDER BY pinned DESC, updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE conversation SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean): Int

    @Query("UPDATE conversation SET updatedAt = MAX(updatedAt, :at) WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE conversation SET modelOverride = :model, updatedAt = :at WHERE id = :id")
    suspend fun setModel(id: String, model: String?, at: Long)

    @Query("UPDATE conversation SET activeContextRevisionId = :revisionId, compressionMethod = :method, updatedAt = MAX(updatedAt, :at) WHERE id = :id")
    suspend fun setContext(id: String, revisionId: String?, method: String, at: Long)

    @Query("UPDATE conversation SET mode = :mode, modeSince = :since, updatedAt = :since WHERE id = :id")
    suspend fun changeMode(id: String, mode: String, since: Long)

    @Query("UPDATE conversation SET mode = :mode, modeSince = :since, modeSinceEventId = :eventId WHERE id = :id")
    suspend fun setMode(id: String, mode: String, since: Long, eventId: Long)

    @Query("UPDATE conversation SET proactiveCooldownUntil = :until WHERE id = :id")
    suspend fun setProactiveCooldown(id: String, until: Long)
}

@Dao
interface EventDao {
    /** 候选只匹配原文；可见性及兼容映射仍交给统一时间轴规则。 */
    @Query("""
        SELECT r.*, c.title AS conversationTitle, COALESCE(p.name, '待确认人物') AS personaName, c.archived
        FROM (
            SELECT createdAt AS at, CASE WHEN role = 'system' THEN 1 ELSE 0 END AS sourceOrder, id, conversationId
            FROM message WHERE (:conversationId IS NULL OR conversationId = :conversationId) AND role != 'assistant'
                AND text LIKE :pattern ESCAPE '!'
            UNION ALL
            SELECT COALESCE(deliveredAt, createdAt) AS at, 2 AS sourceOrder, id, conversationId
            FROM event WHERE (:conversationId IS NULL OR conversationId = :conversationId) AND text LIKE :pattern ESCAPE '!'
        ) r JOIN conversation c ON c.id = r.conversationId
        LEFT JOIN persona p ON p.id = c.personaId
        WHERE :beforeAt IS NULL OR r.at < :beforeAt
            OR (r.at = :beforeAt AND r.sourceOrder < :beforeOrder)
            OR (r.at = :beforeAt AND r.sourceOrder = :beforeOrder AND r.id < :beforeId)
        ORDER BY r.at DESC, r.sourceOrder DESC, r.id DESC LIMIT :limit
    """)
    suspend fun searchCandidates(conversationId: String?, pattern: String, beforeAt: Long?, beforeOrder: Int?, beforeId: Long?, limit: Int): List<HistorySearchCursorRow>

    @Query("SELECT * FROM event WHERE id IN (:ids)")
    suspend fun searchRows(ids: List<Long>): List<EventEntity>

    @Query("SELECT * FROM event WHERE conversationId = :conversationId AND id IN (:ids)")
    suspend fun timelineRows(conversationId: String, ids: List<Long>): List<EventEntity>

    /** 候选按统一位置倒序取；正文与旧类型映射仍由 Mappers/timelineEntries 判定。 */
    @Query("""
        SELECT * FROM (
            SELECT createdAt AS at, CASE WHEN role = 'system' THEN 1 ELSE 0 END AS sourceOrder, id
            FROM message WHERE conversationId = :conversationId AND role != 'assistant'
            UNION ALL
            SELECT COALESCE(deliveredAt, createdAt) AS at, 2 AS sourceOrder, id
            FROM event WHERE conversationId = :conversationId
        ) WHERE :beforeAt IS NULL OR at < :beforeAt
            OR (at = :beforeAt AND sourceOrder < :beforeOrder)
            OR (at = :beforeAt AND sourceOrder = :beforeOrder AND id < :beforeId)
        ORDER BY at DESC, sourceOrder DESC, id DESC LIMIT :limit
    """)
    suspend fun timelineCandidates(conversationId: String, beforeAt: Long?, beforeOrder: Int?, beforeId: Long?, limit: Int): List<TimelineCursorRow>

    /** 定位后向较新方向取一批候选，游标不包含自身。 */
    @Query("""
        SELECT * FROM (
            SELECT createdAt AS at, CASE WHEN role = 'system' THEN 1 ELSE 0 END AS sourceOrder, id
            FROM message WHERE conversationId = :conversationId AND role != 'assistant'
            UNION ALL
            SELECT COALESCE(deliveredAt, createdAt) AS at, 2 AS sourceOrder, id
            FROM event WHERE conversationId = :conversationId
        ) WHERE at > :afterAt OR (at = :afterAt AND sourceOrder > :afterOrder)
            OR (at = :afterAt AND sourceOrder = :afterOrder AND id > :afterId)
        ORDER BY at ASC, sourceOrder ASC, id ASC LIMIT :limit
    """)
    suspend fun timelineFollowingCandidates(conversationId: String, afterAt: Long, afterOrder: Int, afterId: Long, limit: Int): List<TimelineCursorRow>

    @Query("SELECT * FROM event WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun allOf(conversationId: String): List<EventEntity>

    @Query("SELECT * FROM event WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeOf(conversationId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?

    /** 只取"出口过的"，用于装配上下文。 */
    @Query(
        """
        SELECT * FROM event
        WHERE conversationId = :conversationId AND delivery IN ('DELIVERED', 'INTERRUPTED')
        ORDER BY id ASC
        """
    )
    suspend fun spokenOf(conversationId: String): List<EventEntity>

    @Insert
    suspend fun insert(entity: EventEntity): Long

    /**
     * "整段 JSON 被当成一条 `say`"的历史行 —— 早期解析失败的产物，供一次性修复用。
     *
     * 判据是**形状**而不是"能不能解析"：那种行的正文就是模型的完整原文，
     * 一定以 `{` 开头且含 `"events"`（见 `DegradedTurn.looksLikeRawScript`）。
     * 按会话 id 过滤，所以走得到索引，可以在每次打开会话时跑。
     *
     * ⚠️ **只修已经落定的**（`DELIVERED` / `INTERRUPTED` / `CANCELED`）。
     * `PENDING` / `PLAYING` 属于**正在演出的那一轮**：这时把它的行换成几条新的，
     * 演出协程手里的 id 就指向了不存在的行 —— 那条气泡会静默消失。
     * 落定之后再修就没有这个问题（真机上那 27 条全是 `DELIVERED`）。
     */
    @Query(
        """
        SELECT * FROM event
        WHERE conversationId = :conversationId AND kind = 'say' AND text LIKE '{%'
          AND delivery IN ('DELIVERED', 'INTERRUPTED', 'CANCELED')
        ORDER BY id ASC
        """
    )
    suspend fun rawScriptSaysOf(conversationId: String): List<EventEntity>

    @Query("DELETE FROM event WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insertAll(entities: List<EventEntity>)

    @Update
    suspend fun update(entity: EventEntity)

    @Query("UPDATE event SET delivery = :delivery WHERE id = :id")
    suspend fun setDelivery(id: Long, delivery: String)

    @Query("UPDATE event SET delivery = 'DELIVERED', deliveredAt = :at, gapMs = :gapMs, typingMs = :typingMs WHERE id = :id")
    suspend fun markDelivered(id: Long, at: Long, gapMs: Int, typingMs: Int)

    @Query("UPDATE event SET delivery = 'INTERRUPTED', deliveredAt = :at WHERE id = :id")
    suspend fun markInterrupted(id: Long, at: Long)

    /**
     * 进程重启时的孤儿事件收尾。
     *
     * ⚠️ **必须是 CANCELED，不能是 INTERRUPTED** —— 这条曾经写错，代价是
     * "重启后凭空冒出一堆对方的消息"。原因：
     *
     * 助手事件在**模型返回后、播出前**就落盘了（标记 PENDING，为了让用户的气泡
     * 不至于在等待期间消失）。如果进程在这中间被杀（重装 APK、系统回收、闪退），
     * 这些事件就永远是 PENDING。
     *
     * - 标成 `INTERRUPTED`（"说了一半"）：语义是**说过**，于是重启后全部出现在
     *   聊天记录里 —— 用户看到的是自己从没收到过的一堆消息。真机实测一次冒出 7 条。
     * - 标成 `CANCELED`（当没说过）：**这才是事实**，它们确实从没播出去过。
     *
     * 顺带说明另一半为什么不怕：模型侧上下文由"上一轮已出口事件"构成，
     * 而播出中断/取消时下一轮会带上"被打断"的提示，所以标 CANCELED 不会让模型
     * 以为自己失忆。
     */
    @Query("UPDATE event SET delivery = 'CANCELED' WHERE delivery IN ('PENDING', 'PLAYING')")
    suspend fun sweepUnfinished(): Int

    @Query("SELECT * FROM event WHERE conversationId = :conversationId AND delivery IN ('PENDING','PLAYING') ORDER BY id ASC")
    suspend fun unfinishedOf(conversationId: String): List<EventEntity>

    @Query("DELETE FROM event WHERE conversationId = :conversationId")
    suspend fun deleteOf(conversationId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM message WHERE id IN (:ids)")
    suspend fun searchRows(ids: List<Long>): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId AND id IN (:ids)")
    suspend fun timelineRows(conversationId: String, ids: List<Long>): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :conversationId AND role = 'user' AND sentToModel = 0")
    suspend fun unpushedCount(conversationId: String): Int

    /** 保留 Mappers.roleFrom 对旧未知角色按用户显示的兜底，只排除助手副本。 */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId AND role != 'assistant' ORDER BY id ASC")
    suspend fun timelineOf(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun recentOf(conversationId: String, limit: Int): List<MessageEntity>

    /**
     * **模型上下文用**：未推送的用户消息（`sentToModel = 0`）不算。
     *
     * 注意 `role != 'user'` 那一支不能省：assistant / system 行的 `sentToModel`
     * 恒为 0（那个字段对它们没有意义），只按 `sentToModel = 1` 过滤会把模型自己
     * 说过的话全部滤掉 —— 那正是文档里记过的"模型看不到自己说过的话"。
     */
    @Query(
        "SELECT * FROM message WHERE conversationId = :conversationId " +
            "AND (role != 'user' OR sentToModel = 1) ORDER BY id DESC LIMIT :limit"
    )
    suspend fun recentForModel(conversationId: String, limit: Int): List<MessageEntity>

    /** 待推送的用户消息（"代发"），按 id 升序。 */
    @Query(
        "SELECT * FROM message WHERE conversationId = :conversationId " +
            "AND role = 'user' AND sentToModel = 0 ORDER BY id ASC"
    )
    suspend fun unpushedUserOf(conversationId: String): List<MessageEntity>

    /** 标记为已推送。只对用户消息有意义，见 `Message.sentToModel` 的注释。 */
    @Query("UPDATE message SET sentToModel = 1 WHERE id IN (:ids)")
    suspend fun markPushed(ids: List<Long>)

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeOf(conversationId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    /**
     * 用户最近发言的时间戳（倒序），用于防抖的自适应学习。
     *
     * 注意这里返回的是 `List<Long>` 而不是实体 —— 第一版写成返回实体、只 select
     * `createdAt`，Room 在编译期就拒绝了（"columns returned by the query does not have
     * the fields ..."）。要部分列就必须声明对应的投影类型，不能拿实体凑。
     */
    @Query("SELECT createdAt FROM message WHERE conversationId = :conversationId AND role = 'user' ORDER BY id DESC LIMIT :limit")
    suspend fun recentUserTimestamps(conversationId: String, limit: Int): List<Long>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun allOf(conversationId: String): List<MessageEntity>

    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteOf(conversationId: String)
}

@Dao
interface PersonaDao {
    @Query("SELECT * FROM persona ORDER BY builtin DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM persona ORDER BY builtin DESC, updatedAt DESC")
    suspend fun all(): List<PersonaEntity>

    @Query("SELECT * FROM persona WHERE id = :id")
    suspend fun byId(id: String): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonaEntity)

    @Query("DELETE FROM persona WHERE id = :id AND builtin = 0")
    suspend fun delete(id: String)
}

@Dao
interface MemoryDao {
    /**
     * 取记忆。`personaId` 为 null = 全都要（记忆管理页用）；
     * 非 null = "这张人设能看到的"，也就是**它自己的 + 通用的**。
     */
    @Query(
        "SELECT * FROM memory WHERE :personaId IS NULL OR personaId = '' OR personaId = :personaId " +
            "ORDER BY pinned DESC, useCount DESC, lastUsedAt DESC"
    )
    suspend fun all(personaId: String?): List<MemoryEntity>

    @Query(
        "SELECT * FROM memory WHERE :personaId IS NULL OR personaId = '' OR personaId = :personaId " +
            "ORDER BY pinned DESC, useCount DESC, lastUsedAt DESC"
    )
    fun observeAll(personaId: String?): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE memory SET useCount = useCount + 1, lastUsedAt = :at WHERE id IN (:ids)")
    suspend fun touch(ids: List<String>, at: Long)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM scene_summary WHERE conversationId = :conversationId ORDER BY fromEventId ASC")
    suspend fun of(conversationId: String): List<SummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SummaryEntity)

    @Query("DELETE FROM scene_summary WHERE conversationId = :conversationId")
    suspend fun deleteOf(conversationId: String)
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(entity: UsageEntity): Long

    @Query("SELECT * FROM usage WHERE (:conversationId IS NULL OR conversationId = :conversationId) AND at >= :from AND at < :to AND id NOT IN (SELECT usageId FROM call_record WHERE usageId IS NOT NULL) ORDER BY at DESC")
    suspend fun unlinked(conversationId: String?, from: Long, to: Long): List<UsageEntity>

    @Query("SELECT * FROM usage WHERE at >= :from AND at < :to ORDER BY at DESC")
    suspend fun range(from: Long, to: Long): List<UsageEntity>

    @Query("SELECT * FROM usage ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<UsageEntity>

    @Query("SELECT * FROM usage WHERE conversationId = :conversationId ORDER BY at DESC")
    suspend fun ofConversation(conversationId: String): List<UsageEntity>

    @Query("SELECT IFNULL(SUM(promptTokens),0) AS p, IFNULL(SUM(completionTokens),0) AS c, IFNULL(SUM(cachedTokens),0) AS k, COUNT(*) AS n FROM usage WHERE at >= :from AND at < :to")
    suspend fun aggregate(from: Long, to: Long): UsageAggregate

    @Query("DELETE FROM usage")
    suspend fun clear()
}

@Dao
interface CallRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CallRecordEntity)
    @Query("SELECT * FROM call_record WHERE id = :id")
    suspend fun byId(id: String): CallRecordEntity?
    @Query("SELECT * FROM call_record WHERE (:conversationId IS NULL OR conversationId = :conversationId) AND startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC, id DESC")
    suspend fun range(conversationId: String?, from: Long, to: Long): List<CallRecordEntity>
    @Query("DELETE FROM call_record")
    suspend fun clear()
    @Query("UPDATE call_record SET status = 'INTERRUPTED' WHERE status = 'STARTED'")
    suspend fun markUnfinished(): Int
    @Query("DELETE FROM context_segment WHERE revisionId IN (SELECT id FROM context_revision WHERE conversationId = :id)")
    suspend fun deleteSegments(id: String)
    @Query("DELETE FROM context_revision WHERE conversationId = :id")
    suspend fun deleteRevisions(id: String)
    @Query("DELETE FROM client_command WHERE conversationId = :id")
    suspend fun deleteCommands(id: String)
}

@Dao
abstract class ModelConfigDao {
    @Query("SELECT COUNT(*) FROM conversation_model_binding WHERE providerId = :providerId AND selectionState = 'EXPLICIT'")
    abstract suspend fun explicitBindingCount(providerId: String): Int
    @Query("SELECT * FROM conversation_model_binding WHERE conversationId = :conversationId")
    abstract suspend fun conversationBinding(conversationId: String): ConversationModelBindingEntity?

    @Query("SELECT * FROM call_config_snapshot WHERE callId = :callId")
    abstract suspend fun callConfigSnapshot(callId: String): CallConfigSnapshotEntity?

    @Query("SELECT * FROM call_config_snapshot WHERE callId IN (:callIds)")
    abstract suspend fun callConfigSnapshots(callIds: List<String>): List<CallConfigSnapshotEntity>

    @Query("SELECT * FROM call_record WHERE id = :callId")
    protected abstract suspend fun callRecord(callId: String): CallRecordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertConversationBinding(entity: ConversationModelBindingEntity)

    @androidx.room.Update
    protected abstract suspend fun updateConversationBinding(entity: ConversationModelBindingEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCallRecord(entity: CallRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCallConfigSnapshot(entity: CallConfigSnapshotEntity)

    /** 迁移/恢复重试仅接受同值，不能 REPLACE 掩盖冲突。 */
    @androidx.room.Transaction
    open suspend fun putConversationBindingIfAbsentOrSame(entity: ConversationModelBindingEntity) {
        require(entity.selectionState in setOf("INHERIT", "EXPLICIT", "NEEDS_CONFIRMATION")) { "未知会话模型选择状态" }
        when (entity.selectionState) {
            "INHERIT" -> require(entity.providerId == null && entity.modelId == null && entity.pendingReason == null) {
                "继承关联不能携带模型引用或待确认原因"
            }
            "EXPLICIT" -> require(!entity.providerId.isNullOrBlank() && !entity.modelId.isNullOrBlank() && entity.pendingReason == null) {
                "显式关联必须包含稳定模型引用"
            }
            "NEEDS_CONFIRMATION" -> require(!entity.pendingReason.isNullOrBlank() &&
                ((entity.providerId == null) == (entity.modelId == null))) { "待确认关联必须保留原因及完整引用状态" }
        }
        val existing = conversationBinding(entity.conversationId)
        if (existing == null) insertConversationBinding(entity)
        else check(existing == entity) { "会话模型关联冲突：${entity.conversationId}" }
    }

    /** 用户显式修复待确认/失效选择；仅在引用已通过调用方验证后替换关联。 */
    @androidx.room.Transaction
    open suspend fun repairConversationBinding(entity: ConversationModelBindingEntity) {
        require(entity.selectionState == "EXPLICIT" && !entity.providerId.isNullOrBlank() &&
            !entity.modelId.isNullOrBlank() && entity.pendingReason == null) { "修复后的关联必须是完整显式模型选择" }
        check(conversationBinding(entity.conversationId) != null) { "会话模型关联不存在" }
        updateConversationBinding(entity)
    }

    @androidx.room.Transaction
    open suspend fun saveUserConversationBinding(entity: ConversationModelBindingEntity) {
        require(entity.migrationSource == "USER" && entity.upgradePlanId == null) { "用户关联来源无效" }
        validateConversationBinding(entity)
        if (conversationBinding(entity.conversationId) == null) insertConversationBinding(entity)
        else updateConversationBinding(entity)
    }

    private fun validateConversationBinding(entity: ConversationModelBindingEntity) {
        require(entity.selectionState in setOf("INHERIT", "EXPLICIT", "NEEDS_CONFIRMATION")) { "未知会话模型选择状态" }
        when (entity.selectionState) {
            "INHERIT" -> require(entity.providerId == null && entity.modelId == null && entity.pendingReason == null)
            "EXPLICIT" -> require(!entity.providerId.isNullOrBlank() && !entity.modelId.isNullOrBlank() && entity.pendingReason == null)
            "NEEDS_CONFIRMATION" -> require(!entity.pendingReason.isNullOrBlank() &&
                ((entity.providerId == null) == (entity.modelId == null)))
        }
    }

    /** 任务 7 使用的唯一写入事务边界；call_record 与配置快照同成同败。 */
    @androidx.room.Transaction
    open suspend fun insertCallWithConfigIfAbsentOrSame(call: CallRecordEntity, snapshot: CallConfigSnapshotEntity) {
        require(call.id == snapshot.callId) { "调用记录与配置快照 ID 不一致" }
        require(snapshot.version > 0 && snapshot.providerId.isNotBlank() && snapshot.modelId.isNotBlank() &&
            snapshot.requestModelId.isNotBlank()) { "调用配置快照字段无效" }
        val existingCall = callRecord(call.id)
        val existingSnapshot = callConfigSnapshot(snapshot.callId)
        if (existingCall != null || existingSnapshot != null) {
            check(existingCall == call && existingSnapshot == snapshot) { "调用记录与配置快照冲突或不完整：${call.id}" }
            return
        }
        insertCallRecord(call)
        insertCallConfigSnapshot(snapshot)
    }

    @Query("DELETE FROM conversation_model_binding WHERE conversationId = :conversationId")
    abstract suspend fun deleteConversationBinding(conversationId: String)

    @Query("DELETE FROM call_config_snapshot")
    abstract suspend fun clearCallConfigSnapshots()
}

@Dao
interface ContextRevisionDao {
    @Query("SELECT * FROM context_revision WHERE id = :id")
    suspend fun byId(id: String): ContextRevisionEntity?
    @Query("SELECT * FROM context_revision WHERE conversationId = :id ORDER BY createdAt DESC, id DESC")
    suspend fun ofConversation(id: String): List<ContextRevisionEntity>
    @Query("SELECT * FROM context_segment WHERE revisionId = :id ORDER BY ordinal ASC")
    suspend fun segments(id: String): List<ContextSegmentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRevision(entity: ContextRevisionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSegments(entities: List<ContextSegmentEntity>)
    @Query("DELETE FROM context_segment WHERE revisionId = :id")
    suspend fun clearSegments(id: String)
}

/**
 * 聚合结果。**必须给别名与默认值** —— Room 按列名映射，
 * `SUM()` 在没有行时返回 NULL，不处理会让 Kotlin 侧的非空 Int 崩掉。
 */
data class UsageAggregate(
    val p: Long = 0,
    val c: Long = 0,
    val k: Long = 0,
    val n: Long = 0
)
