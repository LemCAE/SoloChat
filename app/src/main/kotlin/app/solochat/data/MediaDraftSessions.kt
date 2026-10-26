package app.solochat.data

import app.solochat.core.model.MediaDraft
import app.solochat.core.model.MediaDraftSession
import app.solochat.core.model.MediaDraftSessionState
import app.solochat.core.model.SubmissionText
import app.solochat.core.ports.StoragePort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用内按会话唯一的草稿协调器；聊天页和历史引用共用同一状态与串行写入者。 */
class MediaDraftSessions(private val store: StoragePort, private val scope: CoroutineScope) {
    private class Entry(conversationId: String) {
        val session = MediaDraftSession(conversationId) { UUID.randomUUID().toString() }
        val state = MutableStateFlow(session.state())
        val mutex = Mutex()
        var persistJob: Job? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private fun entry(id: String) = entries.getOrPut(id) { Entry(id) }

    fun observe(conversationId: String): StateFlow<MediaDraftSessionState> = entry(conversationId).state.asStateFlow()

    suspend fun load(conversationId: String) {
        val entry = entry(conversationId)
        entry.mutex.withLock {
            if (entry.session.state().loaded) return
            val draft = store.mediaDraft(conversationId)
            val missing = draft?.mediaIds.orEmpty().filter { store.mediaAsset(it) == null }
            val assets = draft?.mediaIds.orEmpty().mapNotNull { store.mediaAsset(it) }
            entry.state.value = entry.session.restore(draft, assets, missing)
        }
    }

    fun beginImport(conversationId: String): Boolean {
        val entry = entry(conversationId)
        val started = entry.session.beginImport()
        if (started) entry.state.value = entry.session.state()
        return started
    }

    suspend fun cancelImport(conversationId: String) {
        val entry = entry(conversationId)
        entry.mutex.withLock { entry.state.value = entry.session.cancelImport() }
    }

    suspend fun finishImport(conversationId: String, assets: List<app.solochat.core.model.MediaAsset>, now: Long) {
        load(conversationId)
        val entry = entry(conversationId)
        entry.mutex.withLock {
            val state = entry.session.finishImport(assets, now)
            entry.state.value = state
            persist(entry, state.draft!!)
        }
    }

    fun updateText(conversationId: String, text: List<SubmissionText>, now: Long) {
        val entry = entry(conversationId)
        entry.state.value = entry.session.updateText(text, now)
        schedulePersist(conversationId, entry)
    }

    suspend fun removeMedia(conversationId: String, mediaId: String, now: Long) {
        load(conversationId)
        val entry = entry(conversationId)
        entry.mutex.withLock {
            entry.state.value = entry.session.removeMedia(mediaId, now)
            schedulePersist(conversationId, entry)
        }
    }

    suspend fun clearMissing(conversationId: String, now: Long) {
        load(conversationId)
        val entry = entry(conversationId)
        entry.mutex.withLock {
            entry.state.value = entry.session.clearMissing(now)
            schedulePersist(conversationId, entry)
        }
    }

    suspend fun addReference(conversationId: String, mediaId: String, now: Long) {
        load(conversationId)
        val entry = entry(conversationId)
        val asset = requireNotNull(store.mediaAsset(mediaId)) { "图片资源不存在" }
        entry.mutex.withLock {
            entry.persistJob?.cancel()
            val state = entry.session.addReference(asset, now)
            entry.state.value = state
            persist(entry, state.draft!!)
        }
    }

    /** 先冲刷并固定输入快照；存储失败时仍保留会话状态供重试。 */
    suspend fun prepareSubmission(conversationId: String, text: List<SubmissionText>, mediaIds: List<String>, now: Long): MediaDraft {
        load(conversationId)
        val entry = entry(conversationId)
        return entry.mutex.withLock {
            entry.persistJob?.cancel()
            val draft = entry.session.prepareSubmission(text, mediaIds, now)
            val state = entry.session.state()
            entry.state.value = state
            persist(entry, draft)
            draft
        }
    }

    suspend fun finishSubmission(conversationId: String, submissionId: String, success: Boolean, now: Long) {
        val entry = entry(conversationId)
        entry.mutex.withLock {
            if (success) entry.persistJob?.cancel()
            val state = entry.session.finishSubmission(submissionId, success, now)
            entry.state.value = state
            val current = state.draft
            if (success && current != null && current.submissionId != submissionId &&
                (current.mediaIds.isNotEmpty() || current.text.isNotEmpty())) {
                persist(entry, state.draft!!)
            }
        }
    }

    private fun schedulePersist(conversationId: String, entry: Entry) {
        entry.persistJob?.cancel()
        entry.persistJob = scope.launch {
            delay(300)
            entry.mutex.withLock {
                val draft = entry.session.state().draft ?: return@withLock
                runCatching { persist(entry, draft) }.onFailure {
                    entry.state.value = entry.session.state().copy(saveError = it.message ?: "草稿保存失败")
                }
            }
        }
    }

    private suspend fun persist(entry: Entry, draft: MediaDraft) {
        runCatching { store.saveMediaDraft(draft) }.onFailure {
            entry.state.value = entry.session.state().copy(saveError = it.message ?: "草稿保存失败")
            throw it
        }
        if (entry.session.state().draft?.version == draft.version) {
            entry.state.value = entry.session.state().copy(saveError = null)
        }
    }
}
