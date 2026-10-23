package app.solochat.core.model

import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test fun `同毫秒跨来源分页无重复无遗漏且游标不包含自身`() {
        val all = timelineEntries((1L..31).map { event(it) }, (1L..31).map { message(it) })
        val collected = mutableListOf<TimelineEntry>()
        var before: TimelinePosition? = null
        do {
            val page = timelinePage(all, before, 7)
            assertTrue(page.entries.size <= 7)
            collected.addAll(0, page.entries)
            before = page.entries.firstOrNull()?.position
        } while (page.hasEarlier)
        assertEquals(all, collected)
        assertEquals(62, collected.map { it.key }.toSet().size)
    }

    private fun message(id: Long, role: MessageRole = MessageRole.USER, at: Long = 100) =
        Message(id = id, conversationId = "test", role = role, text = "重复", createdAt = at)
    private fun event(id: Long, delivery: DeliveryState = DeliveryState.DELIVERED,
        kind: EventKind = EventKind.SAY, at: Long = 100) =
        Event(id = id, conversationId = "test", kind = kind, text = "重复", delivery = delivery, createdAt = at)

    @Test fun `同毫秒同文按来源与ID确定排序且不随输入顺序变化`() {
        val messages = listOf(message(8, MessageRole.SYSTEM), message(6), message(2))
        val events = listOf(event(9), event(1))
        val rows = timelineEntries(events, messages)
        assertEquals(listOf(TimelineKey(TimelineSource.MESSAGE, 2), TimelineKey(TimelineSource.MESSAGE, 6),
            TimelineKey(TimelineSource.MESSAGE, 8), TimelineKey(TimelineSource.EVENT, 1), TimelineKey(TimelineSource.EVENT, 9)), rows.map { it.key })
        assertEquals(rows, timelineEntries(events.reversed(), messages.reversed()))
        // 子窗口中位置不变，后续分页不能依赖局部 index。
        val subset = timelineEntries(events.take(1), messages.take(2))
        assertTrue(subset.all { row -> rows.single { it.key == row.key }.position == row.position })
    }

    @Test fun `保留未推送与中断正文但排除助手副本隐藏未出口和空事件`() {
        val events = DeliveryState.entries.mapIndexed { index, delivery -> event(index.toLong(), delivery) } +
            listOf(event(10, kind = EventKind.REMEMBER), event(11, kind = EventKind.WAIT),
                event(12, kind = EventKind.READ), event(13).copy(text = " \n"))
        val rows = timelineEntries(events, listOf(message(1), message(2, MessageRole.ASSISTANT)))
        assertEquals(3, rows.size)
        assertFalse((rows.first() as TimelineEntry.MessageRow).message.sentToModel)
        assertEquals(setOf(DeliveryState.DELIVERED, DeliveryState.INTERRUPTED),
            rows.filterIsInstance<TimelineEntry.EventRow>().map { it.event.delivery }.toSet())
    }

    @Test fun `同ID的消息与事件定位不冲突且时间取交付时刻`() {
        val rows = timelineEntries(listOf(event(1, at = 1).copy(deliveredAt = 200)), listOf(message(1)))
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertEquals(listOf(100L, 200L), rows.map { it.position.at })
        assertEquals(10L, timelineEntries(listOf(event(2, at = 10)), emptyList()).single().position.at)
    }
}
