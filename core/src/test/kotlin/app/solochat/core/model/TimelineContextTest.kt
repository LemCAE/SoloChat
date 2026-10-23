package app.solochat.core.model

import org.junit.Assert.*
import org.junit.Test

class TimelineContextTest {
    @Test fun `向后分页同毫秒按来源和ID前进且不重复目标`() {
        val entries = timelineEntries((1L..31).map { Event(id = it, conversationId = "c", kind = EventKind.SAY,
            delivery = DeliveryState.DELIVERED, text = "同文", createdAt = 100) },
            (1L..31).map { Message(id = it, conversationId = "c", role = MessageRole.USER, text = "同文", createdAt = 100) })
        val target = entries[12]
        val collected = mutableListOf<TimelineEntry>()
        var cursor = target.position
        do {
            val page = timelineFollowingPage(entries, cursor, 7)
            assertTrue(page.entries.size <= 7)
            collected += page.entries
            page.entries.lastOrNull()?.let { cursor = it.position }
        } while (page.hasLater)
        assertEquals(entries.drop(13), collected)
        assertFalse(collected.any { it.key == target.key })
    }
}
