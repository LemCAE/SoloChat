package app.solochat.ui.chat

import app.solochat.core.model.*
import app.solochat.core.ports.StoragePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HistoryContextAction { LOCATE, EARLIER, LATER, LATEST }

data class HistoryContextUiState(
    val entries: List<TimelineEntry> = emptyList(),
    val hasEarlier: Boolean = false,
    val hasLater: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
    val missing: Boolean = false,
    val latest: Boolean = false,
    val hasNew: Boolean = false,
    val scrollRequest: Long = 0,
    val scrollTarget: TimelineKey? = null
)

/** 只持有数据库读取端口，不获取 SessionRegistry，避免浏览触发修复、自动代发或打断播出。 */
class HistoryContextState(
    private val scope: CoroutineScope,
    private val store: StoragePort,
    val hit: HistorySearchHit,
    private val loadFailedText: String
) {
    private val mutable = MutableStateFlow(HistoryContextUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var lastAction = HistoryContextAction.LOCATE
    private var closed = false
    private var observedTail: TimelinePosition? = null

    fun locate() = request(HistoryContextAction.LOCATE)
    fun earlier() { if (mutable.value.hasEarlier) request(HistoryContextAction.EARLIER) }
    fun later() { if (mutable.value.hasLater) request(HistoryContextAction.LATER) }
    fun latest() = request(HistoryContextAction.LATEST)
    fun retry() = request(lastAction)
    fun close() { closed = true; job?.cancel() }

    /** 数据库变化只提示，不重置窗口和滚动位置。目标删除后也保留原有前后文供返回。 */
    suspend fun onDatabaseChanged() {
        if (closed || mutable.value.loading) return
        try {
            val tail = store.timelinePage(hit.conversationId, limit = 1).entries.lastOrNull()
            val missing = store.getConversation(hit.conversationId) == null ||
                (!mutable.value.latest && store.timelineEntry(hit.conversationId, hit.key) == null)
            val newer = tail != null && observedTail?.let { tail.position > it } == true
            observedTail = tail?.position
            if (!closed) mutable.value = mutable.value.copy(
                hasNew = (mutable.value.hasNew || newer) && tail != null &&
                    (mutable.value.entries.lastOrNull()?.position?.let { tail.position > it } ?: true), missing = missing)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* 变化提示失败不覆盖可读正文；显式加载仍提供错误与重试。 */ }
    }

    private fun request(action: HistoryContextAction) {
        if (closed || mutable.value.loading) return
        val current = mutable.value
        lastAction = action
        mutable.value = current.copy(loading = true, error = "")
        job = scope.launch {
            try {
                if (store.getConversation(hit.conversationId) == null) {
                    mutable.value = current.copy(loading = false, missing = true)
                    return@launch
                }
                val updated = when (action) {
                    HistoryContextAction.LOCATE -> {
                        val context = store.timelineContext(hit.conversationId, hit.key)
                        if (context == null) current.copy(missing = true) else current.copy(
                            entries = context.entries, hasEarlier = context.hasEarlier, hasLater = context.hasLater,
                            latest = false, missing = false, hasNew = false,
                            scrollRequest = current.scrollRequest + 1, scrollTarget = hit.key)
                    }
                    HistoryContextAction.LATEST -> {
                        val page = store.timelinePage(hit.conversationId)
                        current.copy(entries = page.entries, hasEarlier = page.hasEarlier, hasLater = false,
                            latest = true, missing = false, hasNew = false,
                            scrollRequest = current.scrollRequest + 1, scrollTarget = page.entries.lastOrNull()?.key)
                    }
                    HistoryContextAction.EARLIER -> {
                        val page = store.timelinePage(hit.conversationId, current.entries.firstOrNull()?.position, HISTORY_SEARCH_PAGE_SIZE)
                        current.copy(entries = (page.entries + current.entries).distinctBy { it.key }.sortedBy { it.position },
                            hasEarlier = page.hasEarlier)
                    }
                    HistoryContextAction.LATER -> {
                        val after = requireNotNull(current.entries.lastOrNull()).position
                        val page = store.timelineFollowingPage(hit.conversationId, after)
                        current.copy(entries = (current.entries + page.entries).distinctBy { it.key }.sortedBy { it.position },
                            hasLater = page.hasLater)
                    }
                }
                if (action == HistoryContextAction.LOCATE || action == HistoryContextAction.LATEST) {
                    observedTail = store.timelinePage(hit.conversationId, limit = 1).entries.lastOrNull()?.position
                }
                if (!closed) mutable.value = updated.copy(loading = false, error = "")
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (!closed) mutable.value = current.copy(loading = false, error = loadFailedText)
            }
        }
    }
}
