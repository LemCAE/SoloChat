package app.solochat.data.db

import app.solochat.core.model.Conversation
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import app.solochat.core.model.Usage
import app.solochat.core.ports.EventSink
import app.solochat.core.ports.StoragePort
import app.solochat.core.protocol.DegradedTurn
import app.solochat.data.toEntity
import app.solochat.data.toModel
import androidx.room.withTransaction

/**
 * [StoragePort] 的 Room 实现，同时提供播出状态的写入（[EventSink]）。
 *
 * 一条重要的性能纪律（从 QQ-agent 的经验搬来的）：**不要把重活放在用户发消息的路径上**。
 * 所以 [`rhythmSamples`] 这类统计只取最近 N 条，而不是全表扫描；
 * 装配上下文时也只按 limit 取最近的消息。
 */
class RoomStore(private val db: SoloChatDatabase) : StoragePort, EventSink {

    /** 连发节奏统计的取样条数：够学出手速，又不至于在热路径上做重活。 */
    private val rhythmSampleLimit = 40

    override suspend fun listConversations(): List<Conversation> =
        db.conversations().all().map { it.toModel() }

    override suspend fun getConversation(id: String): Conversation? =
        db.conversations().byId(id)?.toModel()

    override suspend fun upsertConversation(conversation: Conversation) {
        db.conversations().upsert(conversation.toEntity())
    }

    override suspend fun eventsOf(conversationId: String, fromId: Long): List<Event> =
        db.events().allOf(conversationId)
            .filter { it.id > fromId }
            .map { it.toModel() }

    override suspend fun appendEvent(event: Event): Long =
        db.events().insert(event.toEntity())

    override suspend fun updateEvent(event: Event) {
        db.events().update(event.toEntity())
    }

    // limit ≤ 0 原样透传给 DAO：SQLite 的 `LIMIT -1` 就是"不设上限"，
    // 而 `LIMIT 0` 是"一条都不要"。0（= 不限）已经在 ChatConfig.historyWindow 里
    // 翻成了 -1，所以这里**不要**再补一次 coerceAtLeast(1) —— 那会把"不限"变成"只取一条"。
    override suspend fun recentMessages(conversationId: String, limit: Int): List<Message> =
        db.messages().recentOf(conversationId, limit)
            .sortedBy { it.id }          // DAO 按 id 倒序取，这里翻回正序
            .map { it.toModel() }

    // 与上面只差一个 WHERE：未推送的用户消息不算。见 Ports 里的注释 ——
    // 这条过滤是"不按第二次就不发给模型"的唯一实现点。
    override suspend fun modelVisibleMessages(conversationId: String, limit: Int): List<Message> =
        db.messages().recentForModel(conversationId, limit)
            .sortedBy { it.id }
            .map { it.toModel() }

    override suspend fun unpushedUserMessages(conversationId: String): List<Message> =
        db.messages().unpushedUserOf(conversationId).map { it.toModel() }

    override suspend fun markUserMessagesPushed(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.messages().markPushed(ids)
    }

    override suspend fun appendMessage(message: Message): Long =
        db.messages().insert(message.toEntity())

    override suspend fun listPersonas(): List<Persona> =
        db.personas().all().map { it.toModel() }

    override suspend fun getPersona(id: String): Persona? =
        if (id.isBlank()) null else db.personas().byId(id)?.toModel()

    override suspend fun upsertPersona(persona: Persona) {
        db.personas().upsert(persona.toEntity())
    }

    override suspend fun deletePersona(id: String) {
        // DAO 里带了 builtin = 0 条件：内置卡不允许删，改成"复制一份再改"
        db.personas().delete(id)
    }

    override suspend fun listMemories(personaId: String?): List<Memory> =
        db.memories().all(personaId).map { it.toModel() }

    override suspend fun upsertMemory(memory: Memory) {
        db.memories().upsert(memory.toEntity())
    }

    override suspend fun deleteMemory(id: String) {
        db.memories().delete(id)
    }

    override suspend fun summariesOf(conversationId: String): List<SceneSummary> =
        db.summaries().of(conversationId).map { it.toModel() }

    override suspend fun appendSummary(summary: SceneSummary) {
        db.summaries().insert(summary.toEntity())
    }

    override suspend fun recordUsage(
        conversationId: String,
        model: String,
        usage: Usage,
        at: Long
    ) {
        db.usage().insert(
            UsageEntity(
                conversationId = conversationId,
                model = model,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                cachedTokens = usage.cachedTokens,
                reasoningTokens = usage.reasoningTokens,
                imageCount = usage.imageCount,
                estimated = usage.estimated,
                at = at
            )
        )
    }

    // ---- 给上层用的便捷查询（不在 StoragePort 里，但同样的数据源） ----

    /**
     * 用户最近发言的时间戳（倒序）。用于防抖的自适应学习。
     * 只取最近 N 条：学习手速不需要全量历史，而全量扫描会随聊天记录增长而变慢。
     */
    suspend fun recentUserTimestamps(conversationId: String, limit: Int = rhythmSampleLimit): List<Long> =
        db.messages().recentUserTimestamps(conversationId, limit)

    /** 跨会话取用户发言时间（用于"全局手速"，不限于某个会话）。 */
    suspend fun globalRhythmSamples(limit: Int = 60): List<Long> {
        // 这里刻意按会话分别取样再加权：不同会话的说话节奏可能差很多（工作时短句、
        // 闲聊时长段），混在一起会把窗口学到两个极端之间的某个没用值。
        val conversations = db.conversations().all().take(8)
        return conversations.flatMap { recentUserTimestamps(it.id) }
    }

    // ---- EventSink：播出状态的写入端 ----

    override suspend fun setDelivery(id: Long, delivery: DeliveryState) {
        db.events().setDelivery(id, delivery.name)
    }

    override suspend fun markDelivered(id: Long, at: Long, gapMs: Int, typingMs: Int) {
        db.events().markDelivered(id, at, gapMs, typingMs)
    }

    override suspend fun markInterrupted(id: Long, at: Long) {
        db.events().markInterrupted(id, at)
    }

    /**
     * 启动时调用：把上次进程残留的 PENDING/PLAYING 收尾。
     *
     * 结果一律是 `CANCELED`（当没说过）—— 它们确实从没播出去过。
     * 详见 DAO 里那段注释（写错成 INTERRUPTED 会表现为"重启后凭空冒出一堆消息"）。
     */
    suspend fun sweepUnfinished(): Int = db.events().sweepUnfinished()

    /**
     * 见 [StoragePort.repairDegradedTurns]。
     *
     * 实现要点：
     * - **在一个事务里**：删旧行 + 插新行要么都成、要么都不成。中途失败留下"一条 `say`
     *   被删了、新的还没插"就是**丢内容**，那是这个功能唯一不能犯的错。
     * - `id` 归零交给数据库自增（`copy(id = 0)`）；`.withTransaction` 由 room-ktx 提供。
     * - 本地事实（会话 / 轮次 / 模式 / 交付状态 / 时间）**从被替换的那一行照抄**：
     *   它们本来就是同一轮、同一次播出里出来的，拆开之后仍然属于那一刻。
     * - 引擎字段（`gapMs` / `typingMs`）只留在第一条上，其余清零 —— 这一轮真正播出过的
     *   是原来那一条，拆出来的几条不会重播。
     */
    override suspend fun repairDegradedTurns(conversationId: String): Int {
        val rows = db.events().rawScriptSaysOf(conversationId)
        if (rows.isEmpty()) return 0

        var repaired = 0
        db.withTransaction {
            for (row in rows) {
                val parts = DegradedTurn.split(row.text) ?: continue
                db.events().deleteById(row.id)
                parts.forEachIndexed { index, part ->
                    db.events().insert(
                        row.copy(
                            id = 0,
                            kind = part.kind.wire,
                            speaker = part.resolvedSpeaker,
                            text = part.text,
                            gapHintMs = part.gapHintMs,
                            typingHintMs = part.typingHintMs,
                            waitReason = part.waitReason,
                            gapMs = if (index == 0) row.gapMs else 0,
                            typingMs = if (index == 0) row.typingMs else 0
                        )
                    )
                }
                repaired++
            }
        }
        return repaired
    }

    suspend fun unfinishedEvents(conversationId: String): List<Event> =
        db.events().unfinishedOf(conversationId).map { it.toModel() }

    suspend fun deleteConversation(id: String) {
        db.events().deleteOf(id)
        db.messages().deleteOf(id)
        db.conversations().delete(id)
    }

    suspend fun clearUsage() = db.usage().clear()

    suspend fun usageBetween(from: Long, to: Long): List<UsageEntity> = db.usage().range(from, to)

    suspend fun usageAggregate(from: Long, to: Long) = db.usage().aggregate(from, to)
}
