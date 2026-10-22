package app.solochat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

@Serializable
data class PriceRates(val input: Double = 0.0, val output: Double = 0.0, val cached: Double? = null)

/** 每日峰时段，含起点、不含终点；起点大于终点表示跨午夜，其余时间使用标准/谷价。 */
@Serializable
data class PeakWindow(val startMinute: Int? = null, val endMinute: Int? = null, val timeZone: String = "Asia/Shanghai")

@Serializable
data class ModelPriceEntry(val model: String, val standard: PriceRates = PriceRates(),
    val peak: PriceRates? = null, val peakEnabled: Boolean = false, val window: PeakWindow = PeakWindow(),
    val note: String = "", val source: String = "用户自填", val provider: String = "")

@Serializable
data class PriceTable(val id: String, val name: String, val entries: List<ModelPriceEntry> = emptyList())

@Serializable
data class PriceCatalog(val selectedTableId: String, val tables: List<PriceTable>)

object PriceCatalogFormat {
    private val json = Json { encodeDefaults = true }
    fun encode(catalog: PriceCatalog): String = json.encodeToString(catalog)
    fun decode(text: String): PriceCatalog = json.decodeFromString(text)

    fun validate(catalog: PriceCatalog) {
        require(catalog.tables.isNotEmpty() && catalog.tables.any { it.id == catalog.selectedTableId }) { "请选择有效的价格表" }
        require(catalog.tables.map { it.id }.distinct().size == catalog.tables.size) { "价格表 ID 重复" }
        catalog.tables.forEach { table ->
            require(table.id.isNotBlank() && table.name.isNotBlank()) { "价格表名称不能为空" }
            require(table.entries.map { it.model }.distinct().size == table.entries.size) { "同一价格表中模型名不能重复" }
            table.entries.forEach(::validateEntry)
        }
    }

    fun validateEntry(entry: ModelPriceEntry) {
        require(entry.model.isNotBlank() && entry.model == entry.model.trim().lowercase()) { "模型名需去除首尾空白并使用小写" }
        fun validateRates(rates: PriceRates) {
            require(listOfNotNull(rates.input, rates.output, rates.cached).all { it.isFinite() && it >= 0 }) { "单价需为不小于 0 的有效数字" }
        }
        validateRates(entry.standard)
        entry.peak?.let(::validateRates)
        if (entry.peakEnabled) {
            require(entry.peak != null) { "开启峰谷计价后需填写峰价" }
            val window = entry.window
            require(window.startMinute != null && window.endMinute != null &&
                window.startMinute in 0..1439 && window.endMinute in 0..1439 && window.startMinute != window.endMinute) {
                "请填写不同的峰时起止时间（00:00 ~ 23:59）"
            }
            require(runCatching { ZoneId.of(window.timeZone) }.isSuccess) { "时区无效，例如 Asia/Shanghai" }
        }
    }
}

fun PeakWindow.contains(at: Long): Boolean {
    val start = requireNotNull(startMinute)
    val end = requireNotNull(endMinute)
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.of(timeZone))
    val minute = time.hour * 60 + time.minute
    return if (start < end) minute >= start && minute < end else minute >= start || minute < end
}

/** 匹配方式由调用方复用原有精确/快照规则；这里仅按调用开始时间选择价格并生成冻结来源。 */
fun resolvePriceEntry(table: PriceTable, entry: ModelPriceEntry, at: Long): ModelPrice {
    PriceCatalogFormat.validateEntry(entry)
    val peak = entry.peakEnabled && entry.window.contains(at)
    val rates = if (peak) requireNotNull(entry.peak) else entry.standard
    val period = if (!entry.peakEnabled) "标准价" else {
        "${if (peak) "峰价" else "谷价"}；峰时 ${formatPriceMinute(entry.window.startMinute!!)}～${formatPriceMinute(entry.window.endMinute!!)} ${entry.window.timeZone}"
    }
    return ModelPrice(rates.input, rates.output, rates.cached ?: rates.input,
        source = "价格表：${table.name} / ${entry.model}；$period；${entry.source}", known = true,
        cachedPriceKnown = rates.cached != null)
}

fun formatPriceMinute(minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)

fun parsePriceMinute(text: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
}
