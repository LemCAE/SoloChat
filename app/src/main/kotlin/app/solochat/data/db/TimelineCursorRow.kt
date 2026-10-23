package app.solochat.data.db

/** 两张表统一游标的候选位置；不读正文，分批通过 ID 获取原记录。 */
data class TimelineCursorRow(val at: Long, val sourceOrder: Int, val id: Long)

/** 搜索候选附带当前会话归属；只联结元数据，不把整库正文装进内存。 */
data class HistorySearchCursorRow(
    val at: Long, val sourceOrder: Int, val id: Long, val conversationId: String,
    val conversationTitle: String, val personaName: String, val archived: Boolean
)
