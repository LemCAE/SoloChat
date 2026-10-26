package app.solochat.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaContractTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `无附件扩展指纹保持旧 Message 序列化和摘要`() {
        val message = Message(id = 11, conversationId = "c", role = MessageRole.USER, text = "原句", createdAt = 12)
        val encoded = json.encodeToString(message)
        assertEquals("""{"id":11,"conversationId":"c","role":"user","text":"原句","turnId":0,"createdAt":12,"interruptedAt":null,"sentToModel":false}""", encoded)
        assertEquals(fingerprintOf(message), fingerprintOf(message, emptyList(), emptyMap()).message)
        assertEquals(fingerprintOf(message), fingerprintWithAttachments(message, emptyList()))
        assertTrue("attachments" !in encoded)
    }

    @Test fun `扩展来源指纹绑定有序附件内容而不绑定资源 ID`() {
        val message = Message(id = 5, conversationId = "c", role = MessageRole.USER, text = "看这个", createdAt = 8)
        val first = asset("asset-a", "a".repeat(64))
        val sameContent = asset("asset-b", "a".repeat(64))
        val other = asset("asset-c", "b".repeat(64))
        val left = fingerprintOf(message, listOf(MessageAttachment(5, 0, first.id)), mapOf(first.id to first))
        val right = fingerprintOf(message, listOf(MessageAttachment(5, 0, sameContent.id)), mapOf(sameContent.id to sameContent))
        val changed = fingerprintOf(message, listOf(MessageAttachment(5, 0, other.id)), mapOf(other.id to other))
        assertEquals(left, right)
        assertNotEquals(left, changed)
        assertEquals(fingerprintWithAttachments(message, listOf(MessageAttachment(5, 0, first.id) to first)),
            fingerprintWithAttachments(message, listOf(MessageAttachment(5, 0, sameContent.id) to sameContent)))
        assertNotEquals(fingerprintWithAttachments(message, listOf(MessageAttachment(5, 0, first.id) to first)),
            fingerprintWithAttachments(message, listOf(MessageAttachment(5, 0, other.id) to other)))
    }

    @Test fun `提交校验区分图文内容并保留附件顺序`() {
        val a = InputSubmission("s", "c", listOf(SubmissionText("say", "a")), listOf("1", "2"), 1)
        val b = a.copy(mediaIds = listOf("2", "1"))
        val c = a.copy(text = listOf(SubmissionText("say", "b")))
        assertNotEquals(a.payloadChecksum, b.payloadChecksum)
        assertNotEquals(a.payloadChecksum, c.payloadChecksum)
        assertTrue(runCatching { a.copy(mediaIds = listOf("1", "2", "3", "4", "5")) }.isFailure)
    }

    @Test fun `请求规划按块保留文字与图片交错顺序`() {
        val digest = "a".repeat(64)
        val image = MediaContextReference(4, 0, "m", digest, "png-2048-v1", digest, "image/png", 100, 100, MediaReferenceSource.NEW)
        val plan = MediaContextPlan(listOf(MediaTextBlock("先看"), MediaImageBlock(image), MediaTextBlock("再回答")))
        assertEquals(listOf("先看", image, "再回答"), plan.blocks.map {
            when (it) { is MediaTextBlock -> it.text; is MediaImageBlock -> it.image }
        })
    }

    private fun asset(id: String, sha256: String) = MediaAsset(
        id, sha256, "image/png", 100, 10, 10, fileKey = "objects/$sha256", createdAt = 1
    )
}
