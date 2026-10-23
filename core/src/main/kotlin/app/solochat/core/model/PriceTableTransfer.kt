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
        val document = PriceTableDocument(1, "CNY", "per_million_tokens", table.name, table.entries)
        validate(document)
        return json.encodeToString(document).also {
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
        require(document.formatVersion == 1) { "暂不支持此价格表版本" }
        require(document.currency == "CNY" && document.unit == "per_million_tokens") { "仅支持人民币 / 百万 token，不自动换汇或换算单位" }
        PriceCatalogFormat.validate(PriceCatalog("import", listOf(PriceTable("import", document.name, document.entries))))
    }
}
