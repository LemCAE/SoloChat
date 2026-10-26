package app.solochat.core.model

import org.junit.Assert.*
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

class PriceExchangeTransferTest {
    private val document = PriceExchangeDocument(name = "roundtrip", entries = listOf(
        PriceExchangeEntry("file-1", "Exact-Model", "显示名", ModelPriceEntry("exact-model",
            PriceRates(0.0, 2.5, null), peak = PriceRates(1.0, 3.0, 0.0), peakEnabled = false,
            schedule = PriceSchedule(listOf(PriceTimeRange(60, 120)), weekdays = listOf(1),
                calendar = PriceCalendar("2026-01-01", "2026-12-31", listOf("2026-01-01"), source = "holiday", confirmed = false)),
            tiers = listOf(PriceTier(1000, PriceRates(0.0, 2.5, null))), source = "origin", provider = "channel"),
            ExchangePriceOrigin("REFERENCE", channel = "channel", checkedAt = "2026-01-01", url = "https://example.invalid"),
            ExchangePriceConditions("AUTO", "a".repeat(64)))
    ), unassigned = listOf(PriceExchangeEntry("file-2", "orphan", rule = null,
        origin = ExchangePriceOrigin("REFERENCE", channel = "channel"),
        conditions = ExchangePriceConditions("OFF", "b".repeat(64)), unassignedReason = "MODEL_REMOVED")),
        legacyArchive = PriceExchangeArchive("legacy", catalogPresent = true, selectedTableFileId = "t1",
            tables = listOf(PriceExchangeTable("t1", "old active", true, listOf(ModelPriceEntry("archived", PriceRates(3.0, 4.0)))),
                PriceExchangeTable("t2", "old inactive", false, listOf(ModelPriceEntry("unused", PriceRates(5.0, 6.0))))),
            legacyPricing = ExchangeLegacyPricing(true, 0.0, 7.0, 0.0, false, false)))

    @Test fun `v3往返保留规则来源未知和未关联`() {
        val decoded = PriceExchangeTransfer.decode(PriceExchangeTransfer.encode(document))
        assertEquals(document, decoded)
        assertNull(decoded.unassigned.single().rule)
        assertEquals(document.unassigned.single().conditions, decoded.unassigned.single().conditions)
    }

    @Test fun `大小写冲突和未知字段拒绝且不回显文件内容`() {
        val duplicate = document.copy(entries = document.entries + document.entries.single().copy(fileEntryId = "2", requestModelId = "exact-model"))
        assertEquals(2, PriceExchangeTransfer.decode(PriceExchangeTransfer.encode(duplicate)).entries.size)
        val text = PriceExchangeTransfer.encode(document).replace("\"name\": \"roundtrip\",", "\"name\": \"secret-sentinel\",\n  \"unexpected\": true,")
        val failure = runCatching { PriceExchangeTransfer.decode(text) }.exceptionOrNull()!!
        assertFalse(failure.message.orEmpty().contains("secret-sentinel"))
    }

    @Test fun `公开Schema必需字段覆盖Kotlin解码契约且示例可读`() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "docs/reference/pricing-exchange-v3.schema.json") }
            .firstOrNull(File::isFile) ?: error("未找到 v3 JSON Schema")
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(root.readText()).jsonObject
        val defs = schema.getValue("$" + "defs").jsonObject
        fun required(name: String) = defs.getValue(name).jsonObject.getValue("required").jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("model", "standard", "peak", "peakEnabled", "window", "schedule", "tiers", "note", "source", "provider"), required("rule"))
        assertEquals(setOf("input", "output", "cached"), required("rates"))
        val ruleProperties = defs.getValue("rule").jsonObject.getValue("properties").jsonObject
        assertEquals(setOf("startMinute", "endMinute", "timeZone"),
            ruleProperties.getValue("window").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("ranges", "weekdays", "timeZone", "calendar"), required("schedule"))
        assertEquals(setOf("validFrom", "validThrough", "excludedDates", "includedDates", "source", "confirmed"), required("calendar"))
        val entryProperties = defs.getValue("entry").jsonObject.getValue("properties").jsonObject
        assertEquals(setOf("kind", "sourceId", "channel", "checkedAt", "url", "note"),
            entryProperties.getValue("origin").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("thinkingMode", "connectionFingerprint"),
            entryProperties.getValue("conditions").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val example = File(root.parentFile, "pricing-exchange-v3.example.json")
        assertNotNull(PriceExchangeTransfer.decode(example.readText()))
    }

    @Test fun `v3拒绝Schema禁止但Kotlin默认值可补出的嵌套字段缺失`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(PriceExchangeTransfer.encode(document)).jsonObject
        val entry = root.getValue("entries").jsonArray.single().jsonObject
        val rule = entry.getValue("rule").jsonObject
        val window = rule.getValue("window").jsonObject
        val missingZone = JsonObject(rule + ("window" to JsonObject(window - "timeZone")))
        val missingZoneEntry = JsonObject(entry + ("rule" to missingZone))
        val missingZoneDocument = JsonObject(root + ("entries" to JsonArray(listOf(missingZoneEntry))))
        assertTrue(runCatching { PriceExchangeTransfer.decode(missingZoneDocument.toString()) }.isFailure)

        val schedule = rule.getValue("schedule").jsonObject
        val calendar = schedule.getValue("calendar").jsonObject
        val missingCalendarSource = JsonObject(rule + ("schedule" to JsonObject(schedule +
            ("calendar" to JsonObject(calendar - "source")))))
        val missingCalendarEntry = JsonObject(entry + ("rule" to missingCalendarSource))
        val missingCalendarDocument = JsonObject(root + ("entries" to JsonArray(listOf(missingCalendarEntry))))
        assertTrue(runCatching { PriceExchangeTransfer.decode(missingCalendarDocument.toString()) }.isFailure)

        val origin = entry.getValue("origin").jsonObject
        val missingOriginKindEntry = JsonObject(entry + ("origin" to JsonObject(origin - "kind")))
        val missingOriginKindDocument = JsonObject(root + ("entries" to JsonArray(listOf(missingOriginKindEntry))))
        assertTrue(runCatching { PriceExchangeTransfer.decode(missingOriginKindDocument.toString()) }.isFailure)

        val conditions = entry.getValue("conditions").jsonObject
        val missingThinkingModeEntry = JsonObject(entry + ("conditions" to JsonObject(conditions - "thinkingMode")))
        val missingThinkingModeDocument = JsonObject(root + ("entries" to JsonArray(listOf(missingThinkingModeEntry))))
        assertTrue(runCatching { PriceExchangeTransfer.decode(missingThinkingModeDocument.toString()) }.isFailure)
    }
}
