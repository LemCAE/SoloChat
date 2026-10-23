package app.solochat.data.db

/** 两张表统一游标的候选位置；不读正文，分批通过 ID 获取原记录。 */
data class TimelineCursorRow(val at: Long, val sourceOrder: Int, val id: Long)
