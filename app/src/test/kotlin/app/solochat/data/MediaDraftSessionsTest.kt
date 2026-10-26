package app.solochat.data

import app.solochat.core.model.MediaAsset
import app.solochat.core.model.SubmissionText
import app.solochat.core.ports.StoragePort
import app.solochat.play.SessionControllerTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDraftSessionsTest {
    private fun asset(id: String) = MediaAsset(id, (if (id.endsWith("a")) "a" else "b").repeat(64), "image/png", 10, 4, 4,
        fileKey = "objects/$id", createdAt = 1)

    private class GateStore(val backing: SessionControllerTest.FakeStore = SessionControllerTest.FakeStore()) :
        StoragePort by backing {
        var holdNextSave = false
        var failHeldSave = false
        var saveEntered = CompletableDeferred<Unit>()
        var releaseSave = CompletableDeferred<Unit>()

        override suspend fun saveMediaDraft(draft: app.solochat.core.model.MediaDraft) {
            if (draft.submissionId in backing.inputSubmissions) return
            if (holdNextSave) {
                holdNextSave = false
                saveEntered.complete(Unit)
                releaseSave.await()
                if (failHeldSave) error("测试存储故障")
            }
            backing.saveMediaDraft(draft)
        }

        fun holdNext(fail: Boolean = false) {
            holdNextSave = true
            failHeldSave = fail
            saveEntered = CompletableDeferred()
            releaseSave = CompletableDeferred()
        }
    }

    @Test fun `等存储锁时捕获点击快照，新编辑和历史引用不会被旧快照覆盖`() = runTest {
        val store = GateStore()
        store.backing.mediaAssets["image-a"] = asset("image-a")
        store.backing.mediaAssets["image-b"] = asset("image-b")
        val sessions = MediaDraftSessions(store, this)
        sessions.load("c1")
        val oldText = listOf(SubmissionText("say", "点击时文字"))
        sessions.updateText("c1", oldText, 1)

        assertTrue(sessions.beginImport("c1"))
        store.holdNext()
        val importWrite = async { sessions.finishImport("c1", listOf(asset("image-a")), 2) }
        store.saveEntered.await()

        val clicked = sessions.observe("c1").value.draft!!
        val snapshot = sessions.captureSubmission("c1", clicked.version, oldText, oldText, listOf("image-a"), 3)
        val newText = listOf(SubmissionText("say", "之后的新文字"))
        sessions.updateText("c1", newText, 4)
        val quote = async { sessions.addReference("c1", "image-b", 5) }
        val delayedSubmit = async { sessions.persistSubmissionSnapshot(snapshot, 6) }

        store.releaseSave.complete(Unit)
        importWrite.await()
        quote.await()
        assertTrue(delayedSubmit.await())

        assertEquals(newText, sessions.observe("c1").value.draft?.text)
        assertEquals(listOf("image-a", "image-b"), sessions.observe("c1").value.draft?.mediaIds)
        assertEquals(sessions.observe("c1").value.draft, store.backing.mediaDrafts["c1"])
    }

    @Test fun `前次提交成功后迟到的旧点击被拒绝且不能重新持久化草稿`() = runTest {
        val store = GateStore()
        store.backing.mediaAssets["image-a"] = asset("image-a")
        val sessions = MediaDraftSessions(store, this)
        sessions.load("c1")
        sessions.beginImport("c1")
        sessions.finishImport("c1", listOf(asset("image-a")), 1)
        val text = listOf(SubmissionText("say", "旧提交"))
        sessions.updateText("c1", text, 2)
        val old = sessions.observe("c1").value.draft!!
        val captured = sessions.captureSubmission("c1", old.version, text, text, listOf("image-a"), 3)
        sessions.finishSubmission("c1", captured.draft.submissionId, success = true, now = 4)
        store.backing.inputSubmissions[captured.draft.submissionId] = "committed" to listOf(1L)
        store.backing.mediaDrafts.remove("c1")

        assertTrue(runCatching {
            sessions.captureSubmission("c1", old.version, text, text, listOf("image-a"), 5)
        }.isFailure)
        assertFalse(sessions.persistSubmissionSnapshot(captured, 6))
        assertEquals(emptyList<SubmissionText>(), sessions.observe("c1").value.draft?.text)
        assertEquals(null, store.backing.mediaDrafts["c1"])
    }

    @Test fun `已取消页面协程等待存储锁释放后仍清除导入标记`() = runTest {
        val store = GateStore()
        val sessions = MediaDraftSessions(store, this)
        sessions.load("c1")
        sessions.updateText("c1", listOf(SubmissionText("say", "仍保留")), 1)
        store.holdNext()
        advanceTimeBy(300)
        runCurrent()
        store.saveEntered.await()
        assertTrue(sessions.beginImport("c1"))

        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + StandardTestDispatcher(testScheduler))
        val cancel = callerScope.launch { sessions.cancelImport("c1") }
        runCurrent()
        callerScope.cancel()
        store.releaseSave.complete(Unit)
        runCurrent()
        assertTrue(cancel.isCompleted)
        assertTrue(cancel.isCancelled)
        assertFalse(sessions.observe("c1").value.importing)

        sessions.updateText("c1", listOf(SubmissionText("say", "继续编辑")), 2)
        assertTrue(sessions.beginImport("c1"))
        assertEquals("继续编辑", sessions.observe("c1").value.draft?.text?.single()?.text)
    }

    @Test fun `快照持久化失败撤销 inFlight 但保留内容可重试`() = runTest {
        val store = GateStore()
        store.backing.mediaAssets["image-a"] = asset("image-a")
        val sessions = MediaDraftSessions(store, this)
        sessions.load("c1")
        sessions.beginImport("c1")
        sessions.finishImport("c1", listOf(asset("image-a")), 1)
        val text = listOf(SubmissionText("say", "要保留"))
        sessions.updateText("c1", text, 2)
        val current = sessions.observe("c1").value.draft!!
        val snapshot = sessions.captureSubmission("c1", current.version, text, text, listOf("image-a"), 3)
        store.holdNext(fail = true)

        val save = async { runCatching { sessions.persistSubmissionSnapshot(snapshot, 4) }.exceptionOrNull() }
        store.saveEntered.await()
        assertTrue(sessions.observe("c1").value.inFlightSubmissionIds.contains(snapshot.draft.submissionId))
        store.releaseSave.complete(Unit)
        assertEquals("测试存储故障", save.await()?.message)

        val state = sessions.observe("c1").value
        assertTrue(state.inFlightSubmissionIds.isEmpty())
        assertEquals(text, state.draft?.text)
        assertEquals(listOf("image-a"), state.draft?.mediaIds)
        assertTrue(sessions.persistSubmissionSnapshot(snapshot, 5))
    }
}
