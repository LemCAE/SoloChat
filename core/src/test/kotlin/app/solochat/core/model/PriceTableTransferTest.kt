package app.solochat.core.model

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class PriceTableTransferTest {
    private val table = PriceTable("local-only", "自定义渠道", listOf(ModelPriceEntry("my-model",
        PriceRates(1.0, 2.0, 0.0), peak = PriceRates(3.0, 4.0), peakEnabled = true,
        window = PeakWindow(60, 120, "UTC"), note = "自填报价", provider = "自定义")))

    @Test fun `单表文件往返保留免费缓存峰谷及备注但不包含内部表ID`() {
        val text = PriceTableTransfer.encode(table)
        val document = PriceTableTransfer.read(ByteArrayInputStream(("\uFEFF" + text).toByteArray(Charsets.UTF_8)))
        assertEquals(table.name, document.name)
        assertEquals(table.entries, document.entries)
        assertEquals("CNY", document.currency)
        assertEquals("per_million_tokens", document.unit)
        assertFalse(text.contains("local-only"))
    }

    @Test fun `导入同名表和同模型只追加副本原表及原目录不被覆盖`() {
        val original = PriceCatalog(table.id, listOf(table))
        val document = PriceTableTransfer.decode(PriceTableTransfer.encode(table))
        val imported = PriceTableTransfer.append(original, document)
        assertEquals(listOf(table), original.tables)
        assertEquals(table, imported.tables.first())
        assertEquals(table.entries, imported.tables.last().entries)
        assertNotEquals(table.id, imported.selectedTableId)
        assertEquals(imported.tables.last().id, imported.selectedTableId)
        val twice = PriceTableTransfer.append(imported, document)
        assertEquals(3, twice.tables.map { it.id }.distinct().size)
    }

    @Test fun `缺单价重复模型负价错误币种版本或时段整表拒绝`() {
        val valid = PriceTableTransfer.encode(table)
        val invalid = listOf(
            valid.replace("\"formatVersion\": 1", "\"formatVersion\": 2"),
            valid.replace("\"CNY\"", "\"USD\""),
            valid.replace("per_million_tokens", "per_token"),
            valid.replace("\"input\": 1.0,", ""),
            valid.replace("\"input\": 1.0", "\"input\": -1.0"),
            valid.replace("\"endMinute\": 120", "\"endMinute\": 60"),
            valid.replace("\"formatVersion\": 1,", ""),
            valid.replaceFirst("{", "{\"apiKey\":\"fake-import-secret\",")
        )
        invalid.forEach { text ->
            val result = runCatching { PriceTableTransfer.decode(text) }
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("fake-import-secret"))
        }
        val duplicate = table.copy(entries = table.entries + table.entries)
        assertTrue(runCatching { PriceTableTransfer.encode(duplicate) }.isFailure)
        assertTrue(runCatching { PriceTableTransfer.read(ByteArrayInputStream(ByteArray(PriceTableTransfer.MAX_BYTES + 1))) }.isFailure)
    }
}
