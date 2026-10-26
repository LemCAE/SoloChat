package app.solochat.core.ports

import app.solochat.core.model.Conversation
import app.solochat.core.model.Event
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import app.solochat.core.model.Usage
import app.solochat.core.model.CallRecord
import app.solochat.core.model.CallConfigSnapshot
import app.solochat.core.model.MediaAsset
import app.solochat.core.model.MessageAttachment
import app.solochat.core.model.InputSubmission
import app.solochat.core.model.MediaDraft
import app.solochat.core.model.MediaVariant
import java.io.InputStream
import app.solochat.core.model.ChatMode
import app.solochat.core.model.ContextRevision
import app.solochat.core.model.TimelineEntry
import app.solochat.core.model.timelineEntries

/**
 * 时钟接口。
 *
 * 存在的唯一理由：**让"等待 4 秒"这种逻辑可以被测试瞬间跑完**。
 * 如果直接 `System.currentTimeMillis()` + `delay()`，防抖与播放器的测试要么慢得没法跑，
 * 要么全是 flaky。测试里换成虚拟时钟即可。
 */
interface ClockPort {
    fun now(): Long
    suspend fun sleep(ms: Long)
}

/** 真实实现。 */
class SystemClock : ClockPort {
    override fun now(): Long = System.currentTimeMillis()
    override suspend fun sleep(ms: Long) {
        if (ms > 0) kotlinx.coroutines.delay(ms)
    }
}

/** app 侧处理文件/解码；core 与请求准备只依赖不可变元数据和稳定变体键。 */
interface MediaContentPort {
    suspend fun asset(mediaId: String): MediaAsset?
    suspend fun prepareSendVariant(mediaId: String): MediaVariant
    fun openSendVariant(variant: MediaVariant): InputStream
}

/**
 * 存储接口。Android 侧由 Room 实现；测试里用内存实现。
 *
 * 模型上下文与聊天时间轴分开读取：聊天使用 [timelineOf] 的统一可见边界，
 * 模型仍按既有上下文规则取原始消息与事件，不随界面查询改变。
 */
interface StoragePort {
    suspend fun listConversations(): List<Conversation>
    suspend fun getConversation(id: String): Conversation?
    suspend fun upsertConversation(conversation: Conversation)
    suspend fun touchConversation(id: String, at: Long)
    suspend fun changeMode(id: String, mode: ChatMode, since: Long)

    /** 恢复会话的持久化暂停；未支持恢复的存储保持原行为。 */
    suspend fun isBackupPaused(conversationId: String): Boolean = false
    suspend fun resumeBackup(conversationId: String) {}

    /** 按 id 升序。 */
    suspend fun eventsOf(conversationId: String, fromId: Long = 0): List<Event>
    suspend fun appendEvent(event: Event): Long
    suspend fun updateEvent(event: Event)

    /** 完整可见时间轴，按稳定位置升序。Room 以只读事务读取两类记录；不限制为最近 400 条。 */
    suspend fun timelineOf(conversationId: String): List<TimelineEntry> =
        timelineEntries(eventsOf(conversationId), recentMessages(conversationId, -1))

    /** 向更早历史读取一页，游标不包含自身；Room 不得先读取全量历史。 */
    suspend fun timelinePage(conversationId: String, before: app.solochat.core.model.TimelinePosition? = null,
        limit: Int = app.solochat.core.model.TIMELINE_PAGE_SIZE): app.solochat.core.model.TimelinePage =
        app.solochat.core.model.timelinePage(timelineOf(conversationId), before, limit)

    suspend fun unpushedUserCount(conversationId: String): Int = unpushedUserMessages(conversationId).size

    /** 精确来源键查找；已删除、未出口或隐藏记录均返回 null。 */
    suspend fun timelineEntry(conversationId: String, key: app.solochat.core.model.TimelineKey): TimelineEntry? =
        timelineOf(conversationId).firstOrNull { it.key == key }

    suspend fun timelineFollowingPage(conversationId: String, after: app.solochat.core.model.TimelinePosition,
        limit: Int = app.solochat.core.model.HISTORY_SEARCH_PAGE_SIZE): app.solochat.core.model.TimelineFollowingPage =
        app.solochat.core.model.timelineFollowingPage(timelineOf(conversationId), after, limit)

    /** 直接读取目标附近的小窗口；Room 覆盖为同一只读事务，不从最新页逐页回翻。 */
    suspend fun timelineContext(conversationId: String, key: app.solochat.core.model.TimelineKey,
        radius: Int = 20): app.solochat.core.model.TimelineContext? {
        require(radius in 1..80)
        if (getConversation(conversationId) == null) return null
        val target = timelineEntry(conversationId, key) ?: return null
        val earlier = timelinePage(conversationId, target.position, radius)
        val later = timelineFollowingPage(conversationId, target.position, radius)
        return app.solochat.core.model.TimelineContext(key, earlier.entries + target + later.entries, earlier.hasEarlier, later.hasLater)
    }

    /** 搜索完整原始历史；null 表示全部会话（含归档），不受上下文压缩或已加载页数影响。 */
    suspend fun searchHistory(conversationId: String?, query: String,
        before: app.solochat.core.model.TimelinePosition? = null,
        limit: Int = app.solochat.core.model.HISTORY_SEARCH_PAGE_SIZE): app.solochat.core.model.HistorySearchPage {
        if (conversationId != null) return app.solochat.core.model.historySearchPage(timelineOf(conversationId), query, before, limit)
        require(limit in 1..200)
        if (query.isBlank()) return app.solochat.core.model.HistorySearchPage(emptyList(), false)
        val conversations = listConversations().associateBy { it.id }
        val personas = listPersonas().associateBy { it.id }
        val entries = conversations.keys.flatMap { timelineOf(it) }.sortedBy { it.position }
        val page = app.solochat.core.model.historySearchPage(entries, query, before, limit)
        return page.copy(hits = page.hits.map { hit ->
            val conversation = conversations.getValue(hit.conversationId)
            hit.copy(conversationTitle = conversation.title, personaName = personas[conversation.personaId]?.name ?: "待确认人物",
                archived = conversation.archived)
        })
    }

    /**
     * 最近 [limit] 条原始消息，实现返回 ID 升序；聊天界面改用 [timelinePage]。
     *
     * **[limit] ≤ 0 = 不限，返回全部**。调用方通常传 `ChatConfig.historyWindow`
     * （它已经把 0 翻成 `LIMIT -1`）；`LIMIT 0` 是"一条都不要"，所以实现里
     * **不能**把这个值原样拼进 SQL —— 那会让上下文整个空掉，而且不报任何错。
     *
     * ⚠️ 这里**包含还没推送的"代发"消息**（`sentToModel = false` 的用户消息）——
     * 界面必须看到它们。要喂给模型请用 [modelVisibleMessages]，那是另一个方法，
     * 刻意**不做成一个布尔默认参数**：默认值一旦给错方向，泄漏是静默的
     * （同样的教训见 `listMemories` 的 `null` = 全部）。
     */
    suspend fun recentMessages(conversationId: String, limit: Int): List<Message>

    /**
     * **模型能看到的**最近消息：已被某一轮取走的用户消息 + 全部 assistant / system。
     *
     * 与 [recentMessages] 只差"未推送的用户消息不算"。这条过滤是"手动发送模式"里
     * "不按第二次就不发给模型"这句承诺的**唯一实现点** —— 所以它必须在存储层，
     * 而不是让每个调用方自己记得剔。
     */
    suspend fun modelVisibleMessages(conversationId: String, limit: Int): List<Message>

    /**
     * 还没推送出去的用户消息（"代发"），按 id 升序。
     *
     * 这是手动发送模式下"有没有代发"的**唯一判定来源**（不是内存里的某个列表）——
     * 所以它天然活过切会话、离开页面、进程被杀。
     */
    suspend fun unpushedUserMessages(conversationId: String): List<Message>

    /** 把这几条用户消息标记为已推送（`sentToModel = true`）。 */
    suspend fun markUserMessagesPushed(ids: List<Long>)

    suspend fun appendMessage(message: Message): Long

    /** 媒体能力端口；旧测试存储只允许纯文本，Room 以单事务保存消息、附件和幂等映射。 */
    suspend fun appendSubmission(submission: InputSubmission, messages: List<Message>): List<Long> {
        require(submission.mediaIds.isEmpty()) { "当前存储不支持图片提交" }
        require(messages.isNotEmpty())
        return messages.map { appendMessage(it) }
    }
    suspend fun saveMediaDraft(draft: MediaDraft) { require(draft.mediaIds.isEmpty()) { "当前存储不支持图片草稿" } }
    suspend fun mediaDraft(conversationId: String): MediaDraft? = null
    suspend fun clearMediaDraft(conversationId: String) {}
    suspend fun mediaAsset(mediaId: String): MediaAsset? = null
    suspend fun mediaAssetsForMessages(messageIds: List<Long>): Map<Long, List<Pair<MessageAttachment, MediaAsset>>> = emptyMap()

    /**
     * 把"整段 JSON 被当成一条 `say`"的历史事件还原成它本来该有的几条。
     *
     * 那是 2026-10-08 之前解析失败的产物（真机上 27 条）：界面上是一条全是 JSON 的气泡。
     * 内容**逐字不动** —— 只把一条 `say` 换成它原文里本来就有的那几条事件
     * （判定与拆分见 `app.solochat.core.protocol.DegradedTurn`）。
     *
     * 为什么放在存储层：**判定与写入只能有一处**。放在界面或引擎里，
     * 任何一个直接读事件的地方都会看到没修过的数据。
     *
     * **幂等**：修完之后没有行再匹配，所以不需要"只跑一次"的开关 ——
     * 这也是它敢在每次打开会话时都跑一遍的原因（按会话 id 过滤，走索引）。
     * 返回还原了几轮。
     */
    suspend fun repairDegradedTurns(conversationId: String): Int

    suspend fun listPersonas(): List<Persona>
    suspend fun getPersona(id: String): Persona?
    suspend fun upsertPersona(persona: Persona)
    suspend fun deletePersona(id: String)

    /**
     * 取记忆。
     *
     * @param personaId `null` = 全部（记忆管理页要看到所有条目）；
     *   非 null = "这张人设能看到的"，也就是**它自己的 + 通用的**。
     *   这条语义放在存储层、而不是让调用方自己筛：跨人设串味是这个功能最要紧的
     *   失败模式，而它只有一处实现就不会有人漏掉。
     */
    suspend fun listMemories(personaId: String? = null): List<Memory>
    suspend fun upsertMemory(memory: Memory)
    suspend fun deleteMemory(id: String)

    suspend fun summariesOf(conversationId: String): List<SceneSummary>
    suspend fun appendSummary(summary: SceneSummary)

    suspend fun recordUsage(conversationId: String, model: String, usage: Usage, at: Long)
    suspend fun recordCall(call: CallRecord, usage: Usage? = null)

    /** 首次调用记录与非秘密配置快照；Room 实现以单事务写入，旧测试存储沿用旧入口。 */
    suspend fun recordCallStartedWithConfig(call: CallRecord, snapshot: CallConfigSnapshot) {
        recordCall(call)
    }
    suspend fun contextRevision(id: String): ContextRevision?
    suspend fun contextRevisions(conversationId: String): List<ContextRevision>
    suspend fun saveContextDraft(revision: ContextRevision)
    /** 事务内重验来源、人设与父指针，再完整保存版本和切换唯一启用指针。 */
    suspend fun activateContext(revision: ContextRevision, at: Long)
    /** 恢复只清指针，保留摘要与所有原文。 */
    suspend fun restoreContext(conversationId: String, expectedRevisionId: String?, at: Long)
}
