package app.solochat.core.model

const val HISTORY_SEARCH_PAGE_SIZE = 40

/** 来源键与位置独立于当前聊天窗口，后续定位不能靠正文或局部下标。 */
data class HistorySearchHit(
    val conversationId: String,
    val key: TimelineKey,
    val position: TimelinePosition,
    val text: String,
    val label: String,
    val conversationTitle: String = "",
    val personaName: String = "",
    val archived: Boolean = false
)

data class HistorySearchPage(val hits: List<HistorySearchHit>, val hasMore: Boolean)

/** LIKE 只用于筛选候选；用户输入的通配符一律按原文匹配。 */
fun historySearchPattern(query: String): String = "%" + query.trim()
    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"

/** 与聊天共用可见记录；动作/环境的存储标记不是可搜索正文。 */
fun TimelineEntry.historySearchHit(): HistorySearchHit = when (this) {
    is TimelineEntry.MessageRow -> HistorySearchHit(message.conversationId, key, position,
        if (message.role == MessageRole.SYSTEM) message.text else UserUtterance.displayText(message.text),
        if (message.role == MessageRole.SYSTEM) "系统记录" else "我")
    is TimelineEntry.EventRow -> HistorySearchHit(event.conversationId, key, position,
        requireNotNull(event.displayText), when (event.kind) {
            EventKind.ACT -> "动作"
            EventKind.SCENE -> "场景"
            else -> event.speaker.ifBlank { "对方" }
        })
}

/** 内存存储的搜索；Room 必须覆盖为数据库候选分页，不能全量加载历史。 */
fun historySearchPage(entries: List<TimelineEntry>, query: String, before: TimelinePosition?, limit: Int): HistorySearchPage {
    require(limit in 1..200)
    val term = query.trim()
    if (term.isEmpty()) return HistorySearchPage(emptyList(), false)
    val hits = entries.asReversed().asSequence()
        .filter { before == null || it.position < before }
        .map { it.historySearchHit() }.filter { it.text.contains(term) }.take(limit + 1).toList()
    return HistorySearchPage(hits.take(limit), hits.size > limit)
}
