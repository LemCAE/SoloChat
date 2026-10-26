package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDraftSessionTest {
    private fun session(): MediaDraftSession {
        var next = 0
        return MediaDraftSession("c1") { "submission-${++next}" }
    }

    private fun asset(id: String) = MediaAsset(id, "a".repeat(64), "image/png", 10, 4, 4,
        fileKey = "objects/$id", createdAt = 1)

    @Test fun `保存失败保留草稿而重试沿用同一个提交身份`() {
        val session = session()
        session.restore(null, emptyList())
        assertTrue(session.beginImport())
        session.finishImport(listOf(asset("image-a")), 1)
        session.updateText(listOf(SubmissionText("say", "说明文字")), 2)
        val first = session.captureSubmission(session.state().draft!!.version,
            listOf(SubmissionText("say", "说明文字")), listOf(SubmissionText("say", "说明文字")), listOf("image-a"), 3)

        session.beginSubmission(first.submissionId)
        session.finishSubmission(first.submissionId, success = false, now = 4)
        val retry = session.captureSubmission(session.state().draft!!.version, first.text, first.text, first.mediaIds, 5)

        assertEquals(first.submissionId, retry.submissionId)
        assertEquals(first.version, retry.version)
        assertEquals(first.text, session.state().draft?.text)
        assertEquals(first.mediaIds, session.state().draft?.mediaIds)
    }

    @Test fun `提交期间新编辑不会被旧提交成功回执清空`() {
        val session = session()
        session.restore(null, emptyList())
        session.addReference(asset("image-a"), 1)
        session.updateText(listOf(SubmissionText("say", "旧文字")), 2)
        val submitted = session.captureSubmission(session.state().draft!!.version,
            listOf(SubmissionText("say", "旧文字")), listOf(SubmissionText("say", "旧文字")), listOf("image-a"), 2)
        session.beginSubmission(submitted.submissionId)

        session.updateText(listOf(SubmissionText("say", "新文字")), 3)
        session.finishSubmission(submitted.submissionId, success = true, now = 4)

        assertEquals(listOf(SubmissionText("say", "新文字")), session.state().draft?.text)
        assertEquals(listOf("image-a"), session.state().draft?.mediaIds)
        assertNotEquals(submitted.submissionId, session.state().draft?.submissionId)
    }

    @Test fun `补选处理中阻止重入和提交且超额不会截断`() {
        val session = session()
        session.restore(null, emptyList())
        assertTrue(session.beginImport())
        assertFalse(session.beginImport())
        assertTrue(runCatching { session.captureSubmission(session.state().draft!!.version, emptyList(), emptyList(), listOf("image-a"), 1) }.isFailure)
        session.finishImport((1..3).map { asset("image-$it") }, 2)
        assertTrue(session.beginImport())
        assertTrue(runCatching { session.finishImport(listOf(asset("image-4"), asset("image-5")), 3) }.isFailure)
        assertEquals(listOf("image-1", "image-2", "image-3"), session.state().draft?.mediaIds)
    }

    @Test fun `历史引用更新图片时保留当前说明文字`() {
        val session = session()
        val text = listOf(SubmissionText("say", "还没写完的说明"))
        session.restore(MediaDraft("c1", text, listOf("image-a"), 1, 2, "draft-2"), listOf(asset("image-a")))

        session.addReference(asset("image-b"), 3)

        assertEquals(text, session.state().draft?.text)
        assertEquals(listOf("image-a", "image-b"), session.state().draft?.mediaIds)
    }

    @Test fun `过期点击不能在较新编辑之后倒灌旧文字`() {
        val session = session()
        session.restore(null, emptyList())
        session.addReference(asset("image-a"), 1)
        val oldText = listOf(SubmissionText("say", "点击时文字"))
        session.updateText(oldText, 2)
        val clickedVersion = session.state().draft!!.version
        val captured = session.captureSubmission(clickedVersion, oldText, oldText, listOf("image-a"), 3)

        val newText = listOf(SubmissionText("say", "更新后的文字"))
        session.updateText(newText, 4)

        assertTrue(runCatching {
            session.captureSubmission(clickedVersion, oldText, oldText, listOf("image-a"), 5)
        }.isFailure)
        assertEquals(newText, session.state().draft?.text)
        assertEquals(oldText, captured.text)
    }

    @Test fun `完整草稿保留场景文本且同一发送快照复用身份`() {
        val session = session()
        session.restore(null, emptyList())
        session.addReference(asset("image-a"), 1)
        val fullText = mutableListOf(SubmissionText("say", "当前说明"), SubmissionText("act", "保留动作"))
        session.updateText(fullText, 2)
        val version = session.state().draft!!.version
        val first = session.captureSubmission(version, fullText, listOf(fullText.first()), listOf("image-a"), 3)
        fullText.clear()
        val originalText = listOf(SubmissionText("say", "当前说明"), SubmissionText("act", "保留动作"))
        val retry = session.captureSubmission(first.version, originalText, listOf(originalText.first()), listOf("image-a"), 4)

        assertEquals(first.submissionId, retry.submissionId)
        assertEquals(originalText, retry.text)
    }

    @Test fun `纯图片草稿允许空文字且重试仍复用同一身份`() {
        val session = session()
        session.restore(null, emptyList())
        session.beginImport()
        session.finishImport(listOf(asset("image-a"), asset("image-b")), 1)
        val version = session.state().draft!!.version

        val first = session.captureSubmission(version, emptyList(), emptyList(), listOf("image-a", "image-b"), 2)
        val retry = session.captureSubmission(first.version, emptyList(), emptyList(), listOf("image-a", "image-b"), 3)

        assertTrue(first.text.isEmpty())
        assertEquals(listOf("image-a", "image-b"), first.mediaIds)
        assertEquals(first.submissionId, retry.submissionId)
        assertEquals(first.version, retry.version)
    }
}
