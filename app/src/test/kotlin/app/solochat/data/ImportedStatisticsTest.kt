package app.solochat.data

import app.solochat.core.model.CallRecord
import app.solochat.core.model.ModelPrice
import app.solochat.data.db.UsageEntity
import org.junit.Assert.*
import org.junit.Test

class ImportedStatisticsTest {
    @Test fun `同一副本会话的旧账单独保留而恢复后的新调用仍计入本机累计`() {
        val old = CallRecord("old", conversationId = "copy", model = "m", startedAt = 1,
            inputCharacters = 1, price = ModelPrice(2.0, 3.0))
        val fresh = old.copy(id = "fresh", startedAt = 2)
        val legacy = UsageEntity(1, "copy", "legacy", 12, 0, 0, 0, 0, true, 1)
        val data = partitionStatistics(listOf(old, fresh), listOf(legacy), setOf("old"), setOf("1"))
        assertEquals(listOf(fresh), data.calls)
        assertTrue(data.legacy.isEmpty())
        assertEquals(listOf(old), data.importedCalls)
        assertEquals(listOf(legacy), data.importedLegacy)
        assertEquals(1, data.totals.calls)
        assertEquals(ModelPrice(2.0, 3.0), data.importedCalls.single().price)
    }
}
