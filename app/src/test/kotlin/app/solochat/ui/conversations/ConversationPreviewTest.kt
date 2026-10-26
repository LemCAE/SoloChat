package app.solochat.ui.conversations

import app.solochat.core.model.Conversation
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Persona
import app.solochat.core.model.TimelineKey
import app.solochat.core.model.TimelineSource
import app.solochat.core.model.timelineEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPreviewTest {
    @Test fun `最新记录沿用聊天可见规则并跳过未出口与隐藏事件`() {
        val entries = timelineEntries(
            events = listOf(
                event(1, "已说完", EventKind.SAY, DeliveryState.DELIVERED, at = 100),
                event(2, "中断前已显示", EventKind.SAY, DeliveryState.INTERRUPTED, at = 300),
                event(3, "尚未播出", EventKind.SAY, DeliveryState.PENDING, at = 900),
                event(4, "已取消", EventKind.SAY, DeliveryState.CANCELED, at = 800),
                event(5, "隐藏记忆", EventKind.REMEMBER, DeliveryState.DELIVERED, at = 700),
                event(6, "", EventKind.ACT, DeliveryState.DELIVERED, at = 600),
                event(7, "没有正文的回执", EventKind.WAIT, DeliveryState.DELIVERED, at = 500)
            ),
            messages = listOf(
                Message(1, "c", MessageRole.USER, "用户发言", createdAt = 50, sentToModel = true),
                Message(2, "c", MessageRole.ASSISTANT, "助手副本", createdAt = 950)
            )
        )

        val preview = ConversationPreview.from(entries.last())

        assertEquals("中断前已显示", preview.text)
        assertEquals(300L, preview.activeAt)
        assertEquals(TimelineSource.EVENT, preview.key.source)
        assertFalse(preview.pendingPush)
    }

    @Test fun `用户动作解码换行折叠且代发状态保留`() {
        val entry = timelineEntries(emptyList(), listOf(
            Message(9, "c", MessageRole.USER, "*抬手\n靠近*", createdAt = 120, sentToModel = false)
        )).single()

        val preview = ConversationPreview.from(entry)

        assertEquals("抬手 靠近", preview.text)
        assertEquals(120L, preview.activeAt)
        assertEquals(TimelineSource.MESSAGE, preview.key.source)
        assertTrue(preview.pendingPush)
    }

    @Test fun `系统记录保留聊天显示语义且不标成代发`() {
        val entry = timelineEntries(emptyList(), listOf(
            Message(4, "c", MessageRole.SYSTEM, "—— 模式切换 ——", createdAt = 400)
        )).single()

        val preview = ConversationPreview.from(entry)

        assertEquals("—— 模式切换 ——", preview.text)
        assertFalse(preview.pendingPush)
    }

    @Test fun `图片消息预览先标图片并保留配文`() {
        val entry = timelineEntries(emptyList(), listOf(
            Message(12, "c", MessageRole.USER, "照片的说明", createdAt = 120, sentToModel = false)
        )).single()
        val preview = ConversationPreview.from(entry, imageCount = 2)
        assertEquals("[图片] 照片的说明", preview.text)
        assertTrue(preview.pendingPush)
    }

    @Test fun `跨会话人物活动取最近可见记录且忽略管理时间`() {
        val olderConversation = conversation("old", persona = "p1", updatedAt = 90_000, createdAt = 10)
        val activeConversation = conversation("active", persona = "p1", updatedAt = 1, createdAt = 20)
        val otherPersona = conversation("other", persona = "p2", updatedAt = 999_999, createdAt = 30)
        val previews = mapOf(
            "old" to preview("old", at = 100),
            "active" to preview("active", at = 500),
            "other" to preview("other", at = 300)
        )

        assertTrue(comparePersonaActivity("p1", listOf(olderConversation, activeConversation),
            "p2", listOf(otherPersona), previews) < 0)
        assertEquals(listOf("active", "old"),
            sortConversationsByActivity(listOf(olderConversation, activeConversation), previews).map { it.id })
    }

    @Test fun `会话置顶优先且空项按创建时间与稳定ID排序`() {
        val pinnedEmpty = conversation("b", pinned = true, createdAt = 5, updatedAt = 1)
        val active = conversation("c", createdAt = 1, updatedAt = 999)
        val emptyNewer = conversation("a", createdAt = 20, updatedAt = 0)
        val emptyOlder = conversation("d", createdAt = 10, updatedAt = 9999)
        val previews = mapOf("c" to preview("c", at = 100))

        assertEquals(listOf("b", "c", "a", "d"), sortConversationsByActivity(
            listOf(emptyOlder, active, emptyNewer, pinnedEmpty), previews).map { it.id })
    }

    @Test fun `人物活动时间相同以稳定人物ID收尾且空人物组按创建时间`() {
        val sameA = conversation("a", persona = "a", createdAt = 100)
        val sameB = conversation("b", persona = "b", createdAt = 200)
        val emptyOld = conversation("old", persona = "c", createdAt = 10)
        val emptyNew = conversation("new", persona = "d", createdAt = 20)
        val previews = mapOf("a" to preview("a", 500), "b" to preview("b", 500))

        assertTrue(comparePersonaActivity("a", listOf(sameA), "b", listOf(sameB), previews) < 0)
        assertTrue(comparePersonaActivity("d", listOf(emptyNew), "c", listOf(emptyOld), previews) < 0)
    }

    @Test fun `没有会话的新人物仍建立首页分组`() {
        val older = Persona(id = "older", name = "较早人物", createdAt = 100)
        val persona = Persona(id = "new", name = "刚创建的人物", createdAt = 200)

        val groups = buildPersonaGroups(listOf(older, persona), emptyList(), emptyMap())

        assertEquals(listOf("persona:new", "persona:older"), groups.map { it.key })
        assertEquals(persona, groups.first().persona)
        assertTrue(groups.all { it.conversations.isEmpty() })
    }

    @Test fun `较早启动的刷新代次不能覆盖较新状态`() {
        val generation = RefreshGeneration()
        val oldRequest = generation.begin()
        val newRequest = generation.begin()

        assertFalse(generation.isCurrent(oldRequest))
        assertTrue(generation.isCurrent(newRequest))
    }

    private fun preview(id: String, at: Long) = ConversationPreview(
        conversationId = id,
        key = TimelineKey(TimelineSource.MESSAGE, at),
        activeAt = at,
        text = "preview",
        pendingPush = false
    )

    private fun conversation(
        id: String,
        persona: String = "p",
        pinned: Boolean = false,
        createdAt: Long = 0,
        updatedAt: Long = 0
    ) = Conversation(id = id, personaId = persona, pinned = pinned,
        createdAt = createdAt, updatedAt = updatedAt)

    private fun event(id: Long, text: String, kind: EventKind, delivery: DeliveryState, at: Long) = Event(
        id = id,
        conversationId = "c",
        kind = kind,
        speaker = "角色",
        text = text,
        delivery = delivery,
        createdAt = at,
        deliveredAt = at
    )
}
