package app.solochat.core.agent

import app.solochat.core.model.*
import app.solochat.core.prompt.BuiltPrompt
import app.solochat.core.prompt.PromptBuilder
import app.solochat.core.prompt.PromptContext
import app.solochat.core.prompt.PromptSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationOperations {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withConversation(id: String, action: suspend () -> T): T =
        locks.computeIfAbsent(id) { Mutex() }.withLock { action() }
}

data class EffectiveConfig(val config: ChatConfig, val inherited: Boolean)
fun effectiveConfig(conversation: Conversation, global: ChatConfig): EffectiveConfig {
    val model = conversation.modelOverride?.trim()?.takeIf { it.isNotEmpty() }
    return EffectiveConfig(global.copy(model = model ?: global.model), model == null)
}

/** 正式请求、压缩前后预览与“当前上下文”共用此结果。 */
data class ContextPlan(val conversation: Conversation, val effective: EffectiveConfig,
    val history: List<Message>, val prompt: BuiltPrompt, val estimatedTokens: Int,
    val summaryDisabledReason: String, val historyLimited: Boolean,
    val mediaByMessage: Map<Long, List<Pair<MessageAttachment, MediaAsset>>> = emptyMap())

class ContextPlanner(private val deps: AgentDeps) {
    suspend fun prepare(id: String, cue: String = "", attachedMedia: List<MediaContextReference> = emptyList()): ContextPlan = deps.conversations.withConversation(id) {
        prepareUnlocked(id, cue, attachedMedia = attachedMedia)
    }

    /** 仅供已经持有同一会话锁的业务调用，避免重复获取不可重入的 Mutex。 */
    internal suspend fun prepareUnlocked(id: String, cue: String = "", preview: ContextRevision? = null,
        usePreview: Boolean = false, configOverride: ChatConfig? = null,
        attachedMedia: List<MediaContextReference> = emptyList()): ContextPlan {
        val conversation = checkNotNull(deps.storage.getConversation(id)) { "会话不存在" }
        val effective = if (configOverride == null) effectiveConfig(conversation, deps.config())
            else EffectiveConfig(configOverride, false)
        val allHistory = deps.storage.modelVisibleMessages(id, -1)
        val spoken = deps.storage.eventsOf(id).filter { it.usableAsContext && it.displayText != null }
        val revision = if (usePreview) preview else conversation.activeContextRevisionId?.let { deps.storage.contextRevision(it) }
        val mediaIds = revision?.source?.messages?.map { it.id }.orEmpty()
        val mediaByMessage = deps.storage.mediaAssetsForMessages(mediaIds)
        val sourceValid = revision != null && revision.conversationId == id &&
            (revision.state == "COMPLETE" || usePreview && revision.state == "DRAFT") && runCatching {
            validateSegments(revision)
            validateCompressionSource(revision.source, conversation, deps.storage.listPersonas(), allHistory, spoken, mediaByMessage)
        }.isSuccess
        val retained = if (sourceValid) allHistory.drop(revision!!.source.messages.size) else allHistory
        val history = if (effective.config.historyLimit > 0) retained.takeLast(effective.config.historyLimit) else retained
        val historyMedia = deps.storage.mediaAssetsForMessages(history.map { it.id })
        val summaries = if (sourceValid) revision!!.segments.map { segment ->
            PromptSummary(segment.text, revision.source.messages.first { it.id == segment.fromMessageId }.createdAt,
                revision.source.messages.first { it.id == segment.toMessageId }.createdAt)
        } else emptyList()
        val persona = resolvePersona(deps.storage.listPersonas(), conversation.personaId)
            ?: Persona(id = "", name = "未选择人物")
        val memories = deps.storage.listMemories(persona.id)
        val orderedMemories = memories.filter { it.pinned }.sortedByDescending { it.createdAt } +
            memories.filterNot { it.pinned }.sortedWith(compareByDescending<Memory> { it.useCount }.thenByDescending { it.lastUsedAt })
        val retainedTurns = retained.map { it.turnId }.toSet()
        val cut = spoken.filter { it.delivery == DeliveryState.INTERRUPTED && it.turnId in retainedTurns }
        val interrupted = if (cut.isEmpty()) "" else {
            val tail = cut.takeLast(3).joinToString(" / ") { it.displayText ?: "" }.trim()
            "你正说着（「$tail」），对方插了话，你这一轮就没再往下说 —— " +
                "所以后面本来打算说的那几句**没说出口**。别把已经说过的这句再重复一遍。"
        }
        val legacySummaries = deps.storage.summariesOf(id)
        // 来源不匹配时恢复原文装配，不偷偷删除用户保存的版本或改启用指针。
        val prompt = PromptBuilder.build(PromptContext(resources = deps.prompts, persona = persona,
            mode = conversation.mode, now = deps.clock.now(), memories = orderedMemories.take(24),
            spokenEvents = spoken, history = history, mediaByMessage = historyMedia, attachedMedia = attachedMedia,
            summaries = summaries, interruptedNote = interrupted, cue = cue))
        return ContextPlan(conversation, effective, history, prompt, Estimate.promptTokens(prompt.messages),
            if (sourceValid) "已压缩 ${revision!!.source.messages.size} 条早期记录，${revision.segments.size} 段摘要；保留 ${history.size} 条原文" else
                if (revision != null || conversation.activeContextRevisionId != null && !usePreview) "摘要来源已变化或版本不可用，当前使用原文" else
                if (legacySummaries.isNotEmpty()) "旧摘要没有消息覆盖依据，未启用" else "尚未压缩",
            effective.config.historyLimit > 0, historyMedia)
    }
}
