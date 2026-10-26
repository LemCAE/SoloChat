package app.solochat.core.model

import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 单表交换格式不含接口、密钥或内部 ID；单位明确，导入只追加本组副本。 */
@Serializable
data class PriceTableDocument(
    val formatVersion: Int,
    val currency: String,
    val unit: String,
    val name: String,
    val entries: List<ModelPriceEntry>
)

object PriceTableTransfer {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    fun encode(table: PriceTable): String {
        val document = PriceTableDocument(if (table.entries.any { it.schedule != null || it.tiers.isNotEmpty() }) 2 else 1, "CNY", "per_million_tokens", table.name, table.entries)
        validate(document)
        val encoded = json.encodeToString(document)
        // v1 保持旧字段集合，旧客户端不会因新增的空默认字段拒绝简单表。
        val text = if (document.formatVersion == 2) encoded else {
            val root = json.parseToJsonElement(encoded).jsonObject
            val entries = root.getValue("entries").jsonArray.map { entry ->
                JsonObject(entry.jsonObject.filterKeys { it != "schedule" && it != "tiers" })
            }
            json.encodeToString(JsonObject(root + ("entries" to JsonArray(entries))))
        }
        return text.also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "价格表超过 2 MiB，请拆分后导出" }
        }
    }

    fun decode(text: String): PriceTableDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "价格表超过 2 MiB，请拆分后导入" }
        val document = try {
            val clean = text.removePrefix("\uFEFF")
            val root = json.parseToJsonElement(clean).jsonObject
            // 缺价格不能被 PriceRates 的默认零值当成免费，所有导入条目须明确标准输入/输出价。
            root.getValue("entries").jsonArray.forEach { element ->
                fun explicitRates(rates: JsonObject) {
                    require(rates.containsKey("input") && rates.containsKey("output")) { "每项需明确填写输入和输出单价" }
                }
                val entry = element.jsonObject
                explicitRates(entry.getValue("standard").jsonObject)
                (entry["peak"] as? JsonObject)?.let(::explicitRates)
                (entry["tiers"] as? JsonArray)?.forEach { tier ->
                    explicitRates(tier.jsonObject.getValue("standard").jsonObject)
                    (tier.jsonObject["peak"] as? JsonObject)?.let(::explicitRates)
                }
                if (root["formatVersion"]?.jsonPrimitive?.intOrNull == 1)
                    require((entry["schedule"] == null || entry["schedule"] == JsonNull) &&
                        ((entry["tiers"] as? JsonArray)?.isEmpty() != false)) { "复杂价格需使用 v2" }
            }
            json.decodeFromString<PriceTableDocument>(clean)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("价格表 JSON 格式不正确，请使用 SoloChat 单表格式")
        } catch (_: NoSuchElementException) {
            throw IllegalArgumentException("价格表缺少必填字段")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("价格表结构不正确，每项需明确输入和输出单价")
        }
        validate(document)
        return document
    }

    fun read(input: InputStream): PriceTableDocument {
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) continue
            size += count
        }
        require(size <= MAX_BYTES) { "价格表超过 2 MiB，请拆分后导入" }
        return decode(String(bytes, 0, size, Charsets.UTF_8))
    }

    fun append(catalog: PriceCatalog, document: PriceTableDocument): PriceCatalog {
        PriceCatalogFormat.validate(catalog)
        validate(document)
        val table = PriceTable(UUID.randomUUID().toString(), document.name.trim(), document.entries)
        return catalog.copy(selectedTableId = table.id, tables = catalog.tables + table)
    }

    private fun validate(document: PriceTableDocument) {
        require(document.formatVersion in 1..2) { "暂不支持此价格表版本" }
        require(document.formatVersion == 2 || document.entries.none { it.schedule != null || it.tiers.isNotEmpty() }) { "复杂价格需使用 v2" }
        require(document.currency == "CNY" && document.unit == "per_million_tokens") { "仅支持人民币 / 百万 token，不自动换汇或换算单位" }
        PriceCatalogFormat.validate(PriceCatalog("import", listOf(PriceTable("import", document.name, document.entries))))
    }
}

/** v3 按报价条目交换；不含本机 Provider/模型 ID、接口地址或凭据。 */
@Serializable
data class PriceExchangeDocument(
    val formatVersion: Int = 3,
    val currency: String = "CNY",
    val unit: String = "per_million_tokens",
    val name: String,
    val entries: List<PriceExchangeEntry>,
    val unassigned: List<PriceExchangeEntry> = emptyList(),
    val legacyArchive: PriceExchangeArchive? = null,
    val archives: List<PriceExchangeArchive> = emptyList()
)

@Serializable
data class PriceExchangeEntry(
    val fileEntryId: String,
    val requestModelId: String,
    val displayName: String? = null,
    val rule: ModelPriceEntry? = null,
    val origin: ExchangePriceOrigin = ExchangePriceOrigin(),
    val conditions: ExchangePriceConditions = ExchangePriceConditions(),
    val unassignedReason: String? = null
)

@Serializable
data class ExchangePriceOrigin(
    val kind: String = "MANUAL",
    val sourceId: String? = null,
    val channel: String? = null,
    val checkedAt: String? = null,
    val url: String? = null,
    val note: String? = null
)

@Serializable
data class ExchangePriceConditions(
    val thinkingMode: String? = null,
    /** 仅允许非秘密 SHA-256 指纹；连接本身不会随价格文件导出。 */
    val connectionFingerprint: String? = null
)

@Serializable
data class PriceExchangeArchive(
    val name: String,
    val catalogPresent: Boolean,
    val selectedTableFileId: String? = null,
    val tables: List<PriceExchangeTable> = emptyList(),
    val legacyPricing: ExchangeLegacyPricing? = null,
    val bundledCatalogPresent: Boolean = false,
    val bundledSelectedTableFileId: String? = null,
    val source: String? = null
)

@Serializable
data class PriceExchangeTable(val fileTableId: String, val name: String, val selected: Boolean,
    val entries: List<ModelPriceEntry>, val sourceGroup: String = "LEGACY")

@Serializable
data class ExchangeLegacyPricing(
    val useOfficialPrice: Boolean,
    val priceInput: Double,
    val priceOutput: Double,
    val priceCached: Double,
    val priceConfigured: Boolean,
    val cachedPriceConfigured: Boolean
)

object PriceExchangeTransfer {
    const val MAX_BYTES = PriceTableTransfer.MAX_BYTES
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    fun encode(document: PriceExchangeDocument): String {
        validate(document)
        return json.encodeToString(document).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "价格文件超过 2 MiB" }
        }
    }

    fun decode(text: String): PriceExchangeDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "价格文件超过 2 MiB" }
        val document = try {
            val clean = text.removePrefix("\uFEFF")
            val root = json.parseToJsonElement(clean).jsonObject
            require(setOf("formatVersion", "currency", "unit", "name", "entries", "unassigned", "legacyArchive", "archives").all(root::containsKey))
            fun validateRates(value: kotlinx.serialization.json.JsonElement?) {
                if (value == null || value is JsonNull) return
                require(setOf("input", "output", "cached").all(value.jsonObject::containsKey))
            }
            fun validateRuleShape(value: kotlinx.serialization.json.JsonElement) {
                val rule = value.jsonObject
                require(setOf("model", "standard", "peak", "peakEnabled", "window", "schedule", "tiers", "note", "source", "provider").all(rule::containsKey))
                validateRates(rule["standard"]); validateRates(rule["peak"])
                val window = rule.getValue("window").jsonObject
                require(setOf("startMinute", "endMinute", "timeZone").all(window::containsKey))
                rule["schedule"]?.let { if (it !is JsonNull) {
                    val schedule = it.jsonObject
                    require(setOf("ranges", "weekdays", "timeZone", "calendar").all(schedule::containsKey))
                    schedule.getValue("ranges").jsonArray.forEach { range ->
                        require(setOf("startMinute", "endMinute").all(range.jsonObject::containsKey))
                    }
                    schedule["calendar"]?.let { calendarValue -> if (calendarValue !is JsonNull)
                        require(setOf("validFrom", "validThrough", "excludedDates", "includedDates", "source", "confirmed")
                            .all(calendarValue.jsonObject::containsKey))
                    }
                } }
                rule.getValue("tiers").jsonArray.forEach { tier ->
                    val tierObject = tier.jsonObject
                    require(setOf("upToInputTokens", "standard", "peak").all(tierObject::containsKey))
                    validateRates(tierObject["standard"]); validateRates(tierObject["peak"])
                }
            }
            fun validateEntryShape(element: kotlinx.serialization.json.JsonElement) {
                val entry = element.jsonObject
                require(setOf("fileEntryId", "requestModelId", "displayName", "rule", "origin", "conditions", "unassignedReason").all(entry::containsKey))
                require(setOf("kind", "sourceId", "channel", "checkedAt", "url", "note").all(entry.getValue("origin").jsonObject::containsKey))
                require(setOf("thinkingMode", "connectionFingerprint").all(entry.getValue("conditions").jsonObject::containsKey))
                entry["rule"]?.let { if (it !is JsonNull) validateRuleShape(it) }
            }
            (root["entries"]?.jsonArray ?: error("价格文件缺少条目")).forEach(::validateEntryShape)
            (root["unassigned"]?.jsonArray ?: error("价格文件缺少未关联分区")).forEach(::validateEntryShape)
            fun archiveShape(value: kotlinx.serialization.json.JsonElement) {
                val archive = value.jsonObject
                require(setOf("name", "catalogPresent", "selectedTableFileId", "tables", "legacyPricing",
                    "bundledCatalogPresent", "bundledSelectedTableFileId", "source").all(archive::containsKey))
                archive["tables"]!!.jsonArray.forEach { tableValue ->
                    val table = tableValue.jsonObject
                    require(setOf("fileTableId", "name", "selected", "entries", "sourceGroup").all(table::containsKey))
                    table["entries"]!!.jsonArray.forEach(::validateRuleShape)
                }
                archive["legacyPricing"]?.let { legacy -> if (legacy !is JsonNull)
                    require(setOf("useOfficialPrice", "priceInput", "priceOutput", "priceCached", "priceConfigured", "cachedPriceConfigured").all(legacy.jsonObject::containsKey))
                }
            }
            root["legacyArchive"]?.let { if (it !is JsonNull) archiveShape(it) }
            root["archives"]!!.jsonArray.forEach(::archiveShape)
            json.decodeFromString<PriceExchangeDocument>(clean)
        }
        catch (_: SerializationException) { throw IllegalArgumentException("价格文件格式不正确") }
        catch (_: NoSuchElementException) { throw IllegalArgumentException("价格文件缺少必填字段") }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("价格文件结构不正确") }
        validate(document)
        return document
    }

    fun read(input: InputStream): PriceExchangeDocument {
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count > 0) size += count
        }
        require(size <= MAX_BYTES) { "价格文件超过 2 MiB" }
        return decode(String(bytes, 0, size, Charsets.UTF_8))
    }

    private fun validate(document: PriceExchangeDocument) {
        require(document.formatVersion == 3) { "不支持此价格文件版本" }
        require(document.currency == "CNY" && document.unit == "per_million_tokens") { "仅支持人民币 / 百万 token" }
        require(document.name.isNotBlank())
        val entries = document.entries + document.unassigned
        require(entries.map { it.fileEntryId }.distinct().size == entries.size && entries.all { it.fileEntryId.isNotBlank() }) { "文件条目 ID 重复或为空" }
        require((document.entries + document.unassigned).map { it.requestModelId }.all(String::isNotBlank)) { "请求模型 ID 不能为空" }
        require(document.entries.all { it.rule != null && it.unassignedReason == null }) { "生效分区必须包含完整规则" }
        require(document.unassigned.all { it.unassignedReason != null }) { "未关联分区必须说明原因" }
        (document.archives + listOfNotNull(document.legacyArchive)).forEach { archive ->
            require(archive.name.isNotBlank())
            require(archive.tables.map { it.fileTableId }.distinct().size == archive.tables.size)
            require(archive.tables.all { it.fileTableId.isNotBlank() })
            archive.tables.flatMap { it.entries }.forEach(PriceCatalogFormat::validateEntry)
            require(archive.selectedTableFileId == null || archive.tables.any { it.fileTableId == archive.selectedTableFileId && it.sourceGroup == "LEGACY" })
            require(archive.bundledSelectedTableFileId == null || archive.tables.any { it.fileTableId == archive.bundledSelectedTableFileId && it.sourceGroup == "BUNDLED" })
            archive.legacyPricing?.let { legacy ->
                require(listOf(legacy.priceInput, legacy.priceOutput, legacy.priceCached).all { it.isFinite() && it >= 0.0 })
            }
        }
        entries.forEach { entry ->
            entry.rule?.let { rule ->
                require(rule.model == rule.model.trim().lowercase())
                PriceCatalogFormat.validateEntry(rule)
                require(rule.standard.input.isFinite() && rule.standard.output.isFinite())
            }
            entry.conditions.connectionFingerprint?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
            entry.conditions.thinkingMode?.let { ThinkingMode.valueOf(it) }
        }
    }

}
