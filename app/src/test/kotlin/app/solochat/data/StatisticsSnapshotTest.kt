package app.solochat.data

import app.solochat.core.model.CallKind
import app.solochat.core.model.CallRecord
import app.solochat.core.model.CallStatus
import app.solochat.core.model.ModelPrice
import app.solochat.data.db.CallConfigSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsSnapshotTest {
    @Test fun `调用明细只按调用ID返回已保存快照且旧调用保持未记录`() {
        val withSnapshot = CallRecord("call-v2", conversationId = "c", model = "request-v2", startedAt = 1,
            price = ModelPrice(), status = CallStatus.SUCCESS, kind = CallKind.CHAT, inputCharacters = 0)
        val withoutSnapshot = CallRecord("call-old", conversationId = "c", model = "legacy", startedAt = 2,
            price = ModelPrice(), status = CallStatus.SUCCESS, kind = CallKind.CHAT, inputCharacters = 0)
        val recorded = CallConfigSnapshotEntity("call-v2", 1, "provider-at-call", "当时接口",
            "model-id", "当时模型", "request-v2", "CHAT", "GLOBAL_DEFAULT", "ON", "HIGH",
            "MODEL_PREFERENCE", "{\"reasoning_effort\":\"high\"}")
        val stats = StatisticsSnapshot(listOf(withSnapshot, withoutSnapshot), emptyList(),
            callConfigSnapshots = mapOf(recorded.callId to recorded))

        assertEquals("当时接口", stats.snapshotsFor(listOf(withSnapshot.id)).getValue(withSnapshot.id).providerName)
        assertEquals("{\"reasoning_effort\":\"high\"}",
            stats.snapshotsFor(listOf(withSnapshot.id)).getValue(withSnapshot.id).actualThinkingParametersJson)
        assertTrue(stats.snapshotsFor(listOf(withoutSnapshot.id)).isEmpty())
        assertFalse(stats.snapshotsFor(listOf(withSnapshot.id)).containsKey(withoutSnapshot.id))
    }
}
