package app.solochat.ui

import app.solochat.core.model.*
import app.solochat.ui.chat.HistorySearchState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistorySearchStateTest {
    @Test fun `跨会话同文相同来源键的结果保留各自归属与名称`() = runTest {
        val first = hit(3).copy(conversationId = "a", conversationTitle = "同名对话", personaName = "人物甲")
        val second = hit(3).copy(conversationId = "b", conversationTitle = "同名对话", personaName = "人物乙", archived = true)
        val search = HistorySearchState(backgroundScope) { _, _ -> HistorySearchPage(listOf(first, second), false) }
        search.setQuery("正文"); advanceTimeBy(301); runCurrent()
        assertEquals(listOf(first, second), search.state.value.hits)
        assertEquals(setOf("人物甲", "人物乙"), search.state.value.hits.map { it.personaName }.toSet())
    }

    private fun hit(id: Long) = HistorySearchHit("test", TimelineKey(TimelineSource.MESSAGE, id), TimelinePosition(100, 0, id), "正文", "我")

    @Test fun `关键词改变后旧查询即使迟到也不覆盖新结果`() = runTest {
        val old = CompletableDeferred<HistorySearchPage>()
        val search = HistorySearchState(backgroundScope) { query, _ ->
            if (query == "旧") withContext(NonCancellable) { old.await() }
            else HistorySearchPage(listOf(hit(2)), false)
        }
        search.setQuery("旧")
        advanceTimeBy(301); runCurrent()
        search.setQuery("新")
        advanceTimeBy(301); runCurrent()
        old.complete(HistorySearchPage(listOf(hit(1)), false)); runCurrent()
        assertEquals(2L, search.state.value.hits.single().key.id)
        search.setQuery(" ")
        assertTrue(search.state.value.hits.isEmpty())
        assertFalse(search.state.value.loading)
    }

    @Test fun `后续页失败保留结果并用原游标重试刷新回第一页`() = runTest {
        var fail = true
        val cursors = mutableListOf<TimelinePosition?>()
        val search = HistorySearchState(backgroundScope) { _, before ->
            cursors += before
            if (before == null) HistorySearchPage(listOf(hit(3)), true)
            else if (fail) throw IllegalStateException("失败")
            else HistorySearchPage(listOf(hit(2)), false)
        }
        search.setQuery("正文"); advanceTimeBy(301); runCurrent()
        search.loadMore(); runCurrent()
        assertEquals(listOf(hit(3)), search.state.value.hits)
        assertTrue(search.state.value.error.isNotEmpty())
        fail = false
        search.retry(); runCurrent()
        assertEquals(listOf(hit(3), hit(2)), search.state.value.hits)
        assertEquals(cursors[1], cursors[2])
        search.refresh(); runCurrent()
        assertNull(cursors.last())
        assertEquals(listOf(hit(3)), search.state.value.hits)
    }
}
