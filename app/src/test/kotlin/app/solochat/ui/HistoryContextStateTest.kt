package app.solochat.ui

import app.solochat.core.model.*
import app.solochat.play.SessionControllerTest.FakeStore
import app.solochat.ui.chat.HistoryContextState
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryContextStateTest {
    private fun seed(store: FakeStore) {
        store.conversations["c"] = Conversation("c", title = "测试定位")
        store.messages += (1L..200).map { Message(id = it, conversationId = "c", role = MessageRole.USER,
            text = "记录$it", createdAt = it, sentToModel = it > 10) }
        store.events += Event(id = 1, conversationId = "c", kind = EventKind.SAY,
            delivery = DeliveryState.DELIVERED, text = "目标回复", createdAt = 100)
    }
    private fun target() = HistorySearchHit("c", TimelineKey(TimelineSource.EVENT, 1), TimelinePosition(100, 2, 1), "目标回复", "对方")

    @Test fun `精确定位同ID事件而非用户消息且前后各20条不改原记录`() = runTest {
        val store = FakeStore(); seed(store)
        val messages = store.messages.toList(); val events = store.events.toList()
        val context = HistoryContextState(backgroundScope, store, target())
        context.locate(); runCurrent()
        val state = context.state.value
        assertEquals(41, state.entries.size)
        assertEquals(target().key, state.entries[20].key)
        assertTrue(state.hasEarlier); assertTrue(state.hasLater)
        assertEquals(messages, store.messages); assertEquals(events, store.events)
        assertEquals(target().key, state.scrollTarget)
    }

    @Test fun `后续页失败保留窗口重试不滚动且向两侧加载无重复`() = runTest {
        val store = object : FakeStore() {
            var fail = false
            override suspend fun timelineFollowingPage(conversationId: String, after: TimelinePosition, limit: Int): TimelineFollowingPage {
                if (fail) throw IllegalStateException("失败")
                return super.timelineFollowingPage(conversationId, after, limit)
            }
        }
        seed(store)
        val context = HistoryContextState(backgroundScope, store, target())
        context.locate(); runCurrent()
        val initial = context.state.value
        store.fail = true; context.later(); runCurrent()
        assertEquals(initial.entries, context.state.value.entries)
        assertTrue(context.state.value.error.isNotEmpty())
        store.fail = false; context.retry(); runCurrent()
        context.earlier(); runCurrent()
        assertEquals(121, context.state.value.entries.size)
        assertEquals(121, context.state.value.entries.map { it.key }.toSet().size)
        assertEquals(initial.scrollRequest, context.state.value.scrollRequest)
    }

    @Test fun `目标取消或删除可恢复到最新会话删除则保留返回路径`() = runTest {
        val store = FakeStore(); seed(store)
        store.events[0] = store.events[0].copy(delivery = DeliveryState.CANCELED)
        val context = HistoryContextState(backgroundScope, store, target())
        context.locate(); runCurrent()
        assertTrue(context.state.value.missing)
        assertTrue(context.state.value.entries.isEmpty())
        context.latest(); runCurrent()
        assertFalse(context.state.value.missing)
        assertEquals(80, context.state.value.entries.size)
        store.conversations.remove("c")
        context.locate(); runCurrent()
        assertTrue(context.state.value.missing)
        assertEquals(80, context.state.value.entries.size)
    }

    @Test fun `新消息只提示不移动阅读位置返回最新后才更新窗口`() = runTest {
        val store = FakeStore(); seed(store)
        val context = HistoryContextState(backgroundScope, store, target())
        context.locate(); runCurrent()
        val initial = context.state.value
        store.messages += Message(id = 201, conversationId = "c", role = MessageRole.SYSTEM, text = "新记录", createdAt = 201)
        context.onDatabaseChanged()
        assertTrue(context.state.value.hasNew)
        assertEquals(initial.entries, context.state.value.entries)
        assertEquals(initial.scrollRequest, context.state.value.scrollRequest)
        context.latest(); runCurrent()
        assertEquals(201L, context.state.value.entries.last().key.id)
        assertTrue(context.state.value.latest)
        assertFalse(context.state.value.hasNew)
        assertEquals(initial.scrollRequest + 1, context.state.value.scrollRequest)
    }

    @Test fun `已展示目标被删除后只提示且重新定位仍可返回搜索`() = runTest {
        val store = FakeStore(); seed(store)
        val context = HistoryContextState(backgroundScope, store, target())
        context.locate(); runCurrent()
        val entries = context.state.value.entries
        store.events.clear()
        context.onDatabaseChanged()
        assertTrue(context.state.value.missing)
        assertEquals(entries, context.state.value.entries)
        context.locate(); runCurrent()
        assertTrue(context.state.value.missing)
        context.close()
        context.latest(); runCurrent()
        assertEquals(entries, context.state.value.entries)
    }
}
