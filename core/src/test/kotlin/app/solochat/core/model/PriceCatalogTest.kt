package app.solochat.core.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PriceCatalogTest {
    private val entry = ModelPriceEntry("model", PriceRates(1.0, 2.0), peak = PriceRates(3.0, 6.0, 0.0),
        peakEnabled = true, window = PeakWindow(9 * 60, 18 * 60, "Asia/Shanghai"))
    private val table = PriceTable("one", "渠道一", listOf(entry))
    private fun at(text: String) = Instant.parse("2026-10-21T${text}:00Z").toEpochMilli()

    @Test fun `峰时含开始不含结束并使用模型配置时区`() {
        assertEquals(1.0, resolvePriceEntry(table, entry, at("00:59")).inputPerM, 0.0)
        assertEquals(3.0, resolvePriceEntry(table, entry, at("01:00")).inputPerM, 0.0)
        assertEquals(3.0, resolvePriceEntry(table, entry, at("09:59")).inputPerM, 0.0)
        assertEquals(1.0, resolvePriceEntry(table, entry, at("10:00")).inputPerM, 0.0)
        assertEquals(1.0, resolvePriceEntry(table, entry.copy(window = entry.window.copy(timeZone = "UTC")), at("01:00")).inputPerM, 0.0)
    }
    @Test fun `跨午夜峰时段在凌晨仍计峰价其余时间用谷价`() {
        val overnight = entry.copy(window = PeakWindow(22 * 60, 6 * 60, "UTC"))
        assertEquals(3.0, resolvePriceEntry(table, overnight, at("23:00")).inputPerM, 0.0)
        assertEquals(3.0, resolvePriceEntry(table, overnight, at("05:59")).inputPerM, 0.0)
        assertEquals(1.0, resolvePriceEntry(table, overnight, at("06:00")).inputPerM, 0.0)
    }
    @Test fun `模型峰谷默认关闭即使保存了峰价也不自动启用`() {
        val disabled = ModelPriceEntry("new-model", entry.standard, peak = entry.peak)
        assertFalse(disabled.peakEnabled)
        assertEquals(1.0, resolvePriceEntry(table, disabled, at("01:00")).inputPerM, 0.0)
    }
    @Test fun `已知免费缓存与随输入价格有不同语义且历史快照不随改表改变`() {
        val price = resolvePriceEntry(table, entry, at("01:00"))
        assertTrue(price.cachedPriceKnown)
        assertEquals(0.0, price.cachedPerM, 0.0)
        assertFalse(resolvePriceEntry(table, entry, at("00:00")).cachedPriceKnown)
        val call = CallRecord("call", conversationId = "c", model = "model", startedAt = at("01:00"), price = price,
            inputCharacters = 0, input = TokenAmount(1000000, TokenSource.RETURNED),
            output = TokenAmount(1000000, TokenSource.RETURNED), cached = TokenAmount(0, TokenSource.RETURNED))
        val amount = recordedCost(call)
        val edited = entry.copy(peak = PriceRates(100.0, 200.0))
        assertEquals(100.0, resolvePriceEntry(table, edited, at("01:00")).inputPerM, 0.0)
        assertEquals(9.0, amount.low!!, 0.0)
        assertEquals(amount, recordedCost(call))
        assertTrue(call.price.source.contains("峰价"))
        assertTrue(call.price.source.contains("渠道一"))
    }
    @Test fun `启用峰谷必须填写有效时段时区与非负有限价格`() {
        val invalid = listOf(entry.copy(window = PeakWindow()), entry.copy(window = PeakWindow(60, 60)),
            entry.copy(window = PeakWindow(0, 60, "wrong-zone")), entry.copy(peak = null),
            entry.copy(standard = PriceRates(Double.NaN, 0.0)), entry.copy(peak = PriceRates(-1.0, 0.0)))
        invalid.forEach { assertTrue(runCatching { PriceCatalogFormat.validateEntry(it) }.isFailure) }
        assertEquals(0, parsePriceMinute("00:00"))
        assertEquals(1439, parsePriceMinute("23:59"))
        assertNull(parsePriceMinute("24:00"))
        assertNull(parsePriceMinute("01:60"))
    }
    @Test fun `表集合往返保留选择每模型时段以及编辑内容并拒绝重复模型`() {
        val catalog = PriceCatalog("two", listOf(table, PriceTable("two", "渠道二", listOf(entry.copy(peakEnabled = false)))))
        PriceCatalogFormat.validate(catalog)
        assertEquals(catalog, PriceCatalogFormat.decode(PriceCatalogFormat.encode(catalog)))
        assertTrue(runCatching { PriceCatalogFormat.validate(catalog.copy(selectedTableId = "missing")) }.isFailure)
        assertTrue(runCatching { PriceCatalogFormat.validate(PriceCatalog("one", listOf(table.copy(entries = listOf(entry, entry))))) }.isFailure)
    }
}
