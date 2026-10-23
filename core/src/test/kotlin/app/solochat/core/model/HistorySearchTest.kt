package app.solochat.core.model

import org.junit.Assert.*
import org.junit.Test

class HistorySearchTest {
    @Test fun `全局同文跨会话同毫秒分页不混淆且范围缩回单会话`() {
        val messages = (1L..51).map { message(it).copy(conversationId = if (it % 2L == 0L) "a" else "b") }
        val events = (1L..51).map { event(it).copy(conversationId = if (it % 2L == 0L) "b" else "a") }
        val all = timelineEntries(events, messages)
        val collected = mutableListOf<HistorySearchHit>()
        var before: TimelinePosition? = null
        do {
            val page = historySearchPage(all, "中文🐳", before, 13)
            collected += page.hits
            before = page.hits.lastOrNull()?.position
        } while (page.hasMore)
        assertEquals(102, collected.size)
        assertEquals(102, collected.map { it.conversationId to it.key }.toSet().size)
        assertEquals(setOf("a", "b"), collected.map { it.conversationId }.toSet())
        val single = timelineEntries(events.filter { it.conversationId == "a" }, messages.filter { it.conversationId == "a" })
        assertTrue(historySearchPage(single, "中文🐳", null, 200).hits.all { it.conversationId == "a" })
        assertEquals(51, historySearchPage(single, "中文🐳", null, 200).hits.size)
    }

    private fun message(id: Long, text: String = "中文🐳重复", role: MessageRole = MessageRole.USER) =
        Message(id = id, conversationId = "test", role = role, text = text, createdAt = 100)
    private fun event(id: Long, kind: EventKind = EventKind.SAY, delivery: DeliveryState = DeliveryState.DELIVERED) =
        Event(id = id, conversationId = "test", kind = kind, delivery = delivery, text = "中文🐳重复", createdAt = 100)

    @Test fun `完整历史搜索跨过最近80条且同毫秒同文按来源分页无重无漏`() {
        val all = timelineEntries((1L..65).map { event(it) }, (1L..65).map { message(it) })
        val hits = mutableListOf<HistorySearchHit>()
        var before: TimelinePosition? = null
        do {
            val page = historySearchPage(all, "中文🐳", before, 11)
            assertTrue(page.hits.size <= 11)
            hits += page.hits
            before = page.hits.lastOrNull()?.position
        } while (page.hasMore)
        assertEquals(all.asReversed().map { it.key }, hits.map { it.key })
        assertEquals(130, hits.map { it.key }.toSet().size)
    }

    @Test fun `隐藏未出口和助手副本不命中而代发与中断正文可查`() {
        val rows = timelineEntries(DeliveryState.entries.mapIndexed { i, d -> event(i.toLong(), delivery = d) } +
            listOf(event(10, EventKind.REMEMBER), event(11, EventKind.WAIT), event(12, EventKind.READ)),
            listOf(message(1), message(2, role = MessageRole.ASSISTANT)))
        assertEquals(3, historySearchPage(rows, "重复", null, 40).hits.size)
        assertFalse((rows.first() as TimelineEntry.MessageRow).message.sentToModel)
    }

    @Test fun `搜索动作正文而不命中包裹标记且空词无结果`() {
        val rows = timelineEntries(emptyList(), listOf(message(1, "*抬头🐳*")))
        assertEquals("抬头🐳", historySearchPage(rows, "🐳", null, 40).hits.single().text)
        assertTrue(historySearchPage(rows, "*", null, 40).hits.isEmpty())
        assertTrue(historySearchPage(rows, "  ", null, 40).hits.isEmpty())
        assertTrue(historySearchPage(rows, "不存在", null, 40).hits.isEmpty())
    }

    @Test fun `百分号下划线及转义符按字面量匹配且保留反斜杠`() {
        assertEquals("%a!%!_!!\\b%", historySearchPattern(" a%_!\\b "))
        val rows = timelineEntries(emptyList(), listOf(message(1, "a%_!\\b"), message(2, "axxb")))
        assertEquals(1L, historySearchPage(rows, "%_!\\", null, 40).hits.single().key.id)
    }

    @Test fun `继续分页时目标删除或新增不导致重复且刷新可见新增`() {
        val all = timelineEntries(emptyList(), (1L..6).map { message(it) })
        val first = historySearchPage(all, "重复", null, 2)
        val changed = all.filter { it.key.id != 5L } + TimelineEntry.MessageRow(message(7))
        assertEquals(listOf(4L, 3L), historySearchPage(changed.sortedBy { it.position }, "重复", first.hits.last().position, 2).hits.map { it.key.id })
        assertEquals(7L, historySearchPage(changed.sortedBy { it.position }, "重复", null, 2).hits.first().key.id)
    }
}
