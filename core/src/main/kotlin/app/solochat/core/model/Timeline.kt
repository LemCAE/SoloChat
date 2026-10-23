package app.solochat.core.model

/** 两张表的自增 ID 不共享命名空间，定位必须保留来源。 */
enum class TimelineSource { MESSAGE, EVENT }
data class TimelineKey(val source: TimelineSource, val id: Long)

const val TIMELINE_PAGE_SIZE = 80
data class TimelinePage(val entries: List<TimelineEntry>, val hasEarlier: Boolean)

/** 内存存储的分页实现；Android 的 Room 适配必须用数据库游标查询。 */
fun timelinePage(entries: List<TimelineEntry>, before: TimelinePosition?, limit: Int): TimelinePage {
    require(limit in 1..200)
    val eligible = entries.filter { before == null || it.position < before }
    return TimelinePage(eligible.takeLast(limit), eligible.size > limit)
}

/** 同毫秒按用户、系统、出口事件排序，再按各自 ID；不依赖当前页的局部 index。 */
data class TimelinePosition(val at: Long, val sourceOrder: Int, val id: Long) : Comparable<TimelinePosition> {
    override fun compareTo(other: TimelinePosition): Int =
        compareValuesBy(this, other, TimelinePosition::at, TimelinePosition::sourceOrder, TimelinePosition::id)
}

sealed class TimelineEntry {
    abstract val key: TimelineKey
    abstract val position: TimelinePosition

    data class MessageRow(val message: Message) : TimelineEntry() {
        init { require(message.role == MessageRole.USER || message.role == MessageRole.SYSTEM) }
        override val key = TimelineKey(TimelineSource.MESSAGE, message.id)
        override val position = TimelinePosition(message.createdAt, if (message.role == MessageRole.USER) 0 else 1, message.id)
    }
    data class EventRow(val event: Event) : TimelineEntry() {
        init { require(event.kind.isVisible && event.delivery.spoken && event.displayText != null) }
        override val key = TimelineKey(TimelineSource.EVENT, event.id)
        override val position = TimelinePosition(event.deliveredAt ?: event.createdAt, 2, event.id)
    }
}

/** 聊天可见历史唯一合并规则；保留代发，不含助手消息副本、隐藏或未出口事件。 */
fun timelineEntries(events: List<Event>, messages: List<Message>): List<TimelineEntry> = buildList {
    messages.filter { it.role == MessageRole.USER || it.role == MessageRole.SYSTEM }
        .forEach { add(TimelineEntry.MessageRow(it)) }
    events.filter { it.kind.isVisible && it.delivery.spoken && it.displayText != null }
        .forEach { add(TimelineEntry.EventRow(it)) }
}.sortedBy { it.position }
