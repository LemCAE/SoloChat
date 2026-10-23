package app.solochat.core.model

import java.time.Instant
import app.solochat.core.backup.BackupCall
import app.solochat.core.backup.BackupData
import app.solochat.core.backup.validateBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdvancedPricingTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private val schedule = PriceSchedule(listOf(PriceTimeRange(540, 720), PriceTimeRange(840, 1080)),
        weekdays = (1..5).toList(), calendar = PriceCalendar("2026-01-01", "2026-12-31", listOf("2026-10-01"), confirmed = true))
    private val entry = ModelPriceEntry("model", PriceRates(1.0, 2.0, 0.0), PriceRates(3.0, 6.0, 0.1),
        peakEnabled = true, schedule = schedule)
    private fun freeze(e: ModelPriceEntry = entry, time: String = "2026-10-23T01:00:00Z") =
        resolvePriceEntry(PriceTable("t", "测试"), e, at(time))

    @Test fun `多个峰段含开始不含结束午休周末和排除日期用谷价`() {
        assertEquals(3.0, freeze().inputPerM, 0.0)
        assertEquals(1.0, freeze(time = "2026-10-23T04:00:00Z").inputPerM, 0.0)
        assertEquals(3.0, freeze(time = "2026-10-23T06:00:00Z").inputPerM, 0.0)
        assertEquals(1.0, freeze(time = "2026-10-23T10:00:00Z").inputPerM, 0.0)
        assertEquals(1.0, freeze(time = "2026-10-24T01:00:00Z").inputPerM, 0.0)
        assertEquals(1.0, freeze(time = "2026-10-01T01:00:00Z").inputPerM, 0.0)
        assertFalse(freeze(time = "2027-01-01T01:00:00Z").known)
        assertFalse(freeze(entry.copy(schedule = schedule.copy(calendar = schedule.calendar!!.copy(confirmed = false)))).known)
    }

    @Test fun `跨午夜凌晨归开始日期且显式日期可覆盖星期`() {
        val overnight = entry.copy(schedule = schedule.copy(ranges = listOf(PriceTimeRange(1320, 120))))
        assertEquals(3.0, freeze(overnight, "2026-10-23T17:00:00Z").inputPerM, 0.0) // 周六凌晨属周五
        assertEquals(1.0, freeze(overnight, "2026-10-24T17:00:00Z").inputPerM, 0.0)
        val override = entry.copy(schedule = schedule.copy(calendar = schedule.calendar!!.copy(includedDates = listOf("2026-10-24"))))
        assertEquals(3.0, freeze(override, "2026-10-24T01:00:00Z").inputPerM, 0.0)
    }

    @Test fun `阶梯按接口输入总量选整档且跨时段改表不影响冻结结果`() {
        val tiered = entry.copy(tiers = listOf(PriceTier(100, PriceRates(1.0, 2.0), PriceRates(3.0, 6.0, 0.0)),
            PriceTier(200, PriceRates(2.0, 4.0), PriceRates(5.0, 10.0, 0.0))))
        val price = freeze(tiered)
        fun call(input: Int, source: TokenSource = TokenSource.RETURNED) = CallRecord("c", conversationId = "c",
            model = "model", startedAt = 0, price = price, inputCharacters = 0,
            input = TokenAmount(input, source), output = TokenAmount(1_000_000, TokenSource.RETURNED),
            cached = TokenAmount(0, TokenSource.RETURNED))
        assertEquals(6.0003, recordedCost(call(100)).low!!, 0.00000001)
        assertEquals(10.000505, recordedCost(call(101)).low!!, 0.00000001)
        assertNull(recordedCost(call(201)).low)
        assertNull(recordedCost(call(100, TokenSource.ESTIMATED)).low)
        val before = recordedCost(call(101))
        freeze(tiered.copy(tiers = listOf(PriceTier(null, PriceRates(99.0, 99.0), PriceRates(99.0, 99.0)))), "2026-10-24T01:00:00Z")
        assertEquals(before, recordedCost(call(101)))
        val restored = Json.decodeFromString<CallRecord>(Json.encodeToString(call(101)))
        assertEquals(before, recordedCost(restored))
    }

    @Test fun `复杂表导出v2并完整往返简单表仍导出v1`() {
        val complex = entry.copy(tiers = listOf(PriceTier(100, entry.standard, entry.peak),
            PriceTier(null, PriceRates(2.0, 4.0, 0.0), PriceRates(5.0, 10.0, 0.2))))
        val table = PriceTable("t", "测试", listOf(complex))
        val text = PriceTableTransfer.encode(table)
        assertEquals(2, PriceTableTransfer.decode(text).formatVersion)
        assertEquals(table.entries, PriceTableTransfer.decode(text).entries)
        val simple = PriceTableTransfer.encode(table.copy(entries = listOf(entry.copy(schedule = null, peakEnabled = false))))
        assertEquals(1, PriceTableTransfer.decode(simple).formatVersion)
        assertFalse(simple.contains("schedule"))
        assertTrue(runCatching { PriceTableTransfer.decode(text.replace("\"formatVersion\": 2", "\"formatVersion\": 1")) }.isFailure)
    }

    @Test fun `拒绝无序阶梯缺峰价坏日期和冲突例外`() {
        val bad = listOf(entry.copy(tiers = listOf(PriceTier(10, PriceRates()))),
            entry.copy(tiers = listOf(PriceTier(20, PriceRates(), PriceRates()), PriceTier(10, PriceRates(), PriceRates()))),
            entry.copy(schedule = schedule.copy(calendar = PriceCalendar("bad", "2026-12-31"))),
            entry.copy(schedule = schedule.copy(calendar = schedule.calendar!!.copy(includedDates = listOf("2026-10-01"), confirmed = true))))
        bad.forEach { assertTrue(runCatching { PriceCatalogFormat.validateEntry(it) }.isFailure) }
    }

    @Test fun `旧价格JSON不回填阶梯且备份拒绝非法冻结价`() {
        val legacy = Json.decodeFromString<ModelPrice>("""{"inputPerM":1.0,"outputPerM":2.0}""")
        assertTrue(legacy.frozenTiers.isEmpty())
        val record = CallRecord("old", conversationId = "detached", model = "model", startedAt = 0,
            price = legacy, inputCharacters = 0, input = TokenAmount(1_000_000, TokenSource.RETURNED),
            output = TokenAmount(1_000_000, TokenSource.RETURNED))
        assertEquals(3.0, recordedCost(record).low!!, 0.0)
        val valid = record.copy(price = legacy.copy(frozenTiers = listOf(FrozenPriceTier(null, PriceRates(4.0, 5.0, 0.0)))))
        validateBackup(BackupData(calls = listOf(BackupCall(valid))))
        val restored = Json.decodeFromString<BackupData>(Json.encodeToString(BackupData(calls = listOf(BackupCall(valid)))))
        assertEquals(recordedCost(valid), recordedCost(restored.calls.single().record))
        val bad = valid.copy(price = valid.price.copy(frozenTiers = listOf(FrozenPriceTier(null, PriceRates(-1.0, 2.0)))))
        assertTrue(runCatching { validateBackup(BackupData(calls = listOf(BackupCall(bad)))) }.isFailure)
    }
}
