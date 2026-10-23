package app.solochat.ui.chat

import app.solochat.core.model.HistorySearchHit
import app.solochat.core.model.HistorySearchPage
import app.solochat.core.model.TimelinePosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistorySearchUiState(
    val query: String = "",
    val hits: List<HistorySearchHit> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String = ""
)

/** 搜索只读自己的结果，不触发会话 reload；旧请求不能覆盖新关键词。 */
class HistorySearchState(
    private val scope: CoroutineScope,
    private val search: suspend (String, TimelinePosition?) -> HistorySearchPage
) {
    private val mutable = MutableStateFlow(HistorySearchUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0

    fun setQuery(query: String) {
        job?.cancel()
        revision++
        mutable.value = HistorySearchUiState(query = query, loading = query.isNotBlank())
        if (query.isNotBlank()) request(append = false, debounce = true)
    }

    fun refresh() {
        job?.cancel()
        revision++
        mutable.value = HistorySearchUiState(query = mutable.value.query)
        if (mutable.value.query.isNotBlank()) request(append = false)
    }

    fun loadMore() {
        if (!mutable.value.loading && mutable.value.hasMore) request(append = true)
    }

    fun retry() {
        if (mutable.value.error.isNotEmpty()) request(append = mutable.value.hits.isNotEmpty())
    }

    private fun request(append: Boolean, debounce: Boolean = false) {
        val current = mutable.value
        val version = revision
        mutable.value = current.copy(loading = true, error = "")
        job = scope.launch {
            try {
                if (debounce) delay(300)
                val page = search(current.query.trim(), if (append) current.hits.lastOrNull()?.position else null)
                if (version == revision) mutable.value = current.copy(
                    hits = ((if (append) current.hits else emptyList()) + page.hits).distinctBy { it.conversationId to it.key },
                    hasMore = page.hasMore, loading = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (version == revision) mutable.value = current.copy(loading = false, error = "搜索失败，请重试")
            }
        }
    }
}
