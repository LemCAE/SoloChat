package app.solochat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

@Serializable
data class PriceRates(val input: Double = 0.0, val output: Double = 0.0, val cached: Double? = null)

/** 连续分钟时段；跨午夜时，凌晨部分归属于开始那一天的星期与日历。 */
@Serializable
data class PriceTimeRange(val startMinute: Int, val endMinute: Int)

/** 有界日历；范围外不猜节假日。日期例外可以显式覆盖星期规则。 */
@Serializable
data class PriceCalendar(val validFrom: String, val validThrough: String,
    val excludedDates: List<String> = emptyList(), val includedDates: List<String> = emptyList(),
    val source: String = "用户自填", val confirmed: Boolean = false)

@Serializable
data class PriceSchedule(val ranges: List<PriceTimeRange>, val weekdays: List<Int> = (1..7).toList(),
    val timeZone: String = "Asia/Shanghai", val calendar: PriceCalendar? = null)

/** 上限包含该值；后一档从前档上限加一开始，null 表示无上限。整次调用按同一档结算。 */
@Serializable
data class PriceTier(val upToInputTokens: Int?, val standard: PriceRates, val peak: PriceRates? = null)

@Serializable
data class FrozenPriceTier(val upToInputTokens: Int?, val rates: PriceRates)

/** 每日峰时段，含起点、不含终点；起点大于终点表示跨午夜，其余时间使用标准/谷价。 */
@Serializable
data class PeakWindow(val startMinute: Int? = null, val endMinute: Int? = null, val timeZone: String = "Asia/Shanghai")

@Serializable
data class ModelPriceEntry(val model: String, val standard: PriceRates = PriceRates(),
    val peak: PriceRates? = null, val peakEnabled: Boolean = false, val window: PeakWindow = PeakWindow(),
    val note: String = "", val source: String = "用户自填", val provider: String = "",
    val schedule: PriceSchedule? = null, val tiers: List<PriceTier> = emptyList())

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
        validateTierBounds(entry.tiers.map { it.upToInputTokens })
        entry.tiers.forEach { tier ->
            validateRates(tier.standard)
            tier.peak?.let(::validateRates)
            require(!entry.peakEnabled || tier.peak != null) { "开启峰谷后每档均需填写峰价" }
        }
        entry.schedule?.let(::validateSchedule)
        if (entry.peakEnabled && entry.schedule == null) {
            require(entry.peak != null || entry.tiers.isNotEmpty()) { "开启峰谷计价后需填写峰价" }
            val window = entry.window
            require(window.startMinute != null && window.endMinute != null &&
                window.startMinute in 0..1439 && window.endMinute in 0..1439 && window.startMinute != window.endMinute) {
                "请填写不同的峰时起止时间（00:00 ~ 23:59）"
            }
            require(runCatching { ZoneId.of(window.timeZone) }.isSuccess) { "时区无效，例如 Asia/Shanghai" }
        }
        require(!entry.peakEnabled || entry.peak != null || entry.tiers.isNotEmpty()) { "开启峰谷需填写峰价" }
    }
}

fun validateTierBounds(bounds: List<Int?>) {
    var previous = 0
    bounds.forEachIndexed { index, limit ->
        require(limit != null || index == bounds.lastIndex) { "无上限只能放在最后一档" }
        if (limit != null) {
            require(limit > previous) { "阶梯上限需为严格递增的正整数" }
            previous = limit
        }
    }
}

fun validateSchedule(schedule: PriceSchedule) {
    require(runCatching { ZoneId.of(schedule.timeZone) }.isSuccess) { "时区无效" }
    require(schedule.weekdays.isNotEmpty() && schedule.weekdays.distinct().size == schedule.weekdays.size &&
        schedule.weekdays.all { it in 1..7 }) { "星期需为不重复的 1～7" }
    require(schedule.ranges.isNotEmpty() && schedule.ranges.distinct().size == schedule.ranges.size) { "请填写不重复的峰时段" }
    schedule.ranges.forEach {
        require(it.startMinute in 0..1439 && it.endMinute in 0..1439 && it.startMinute != it.endMinute) { "峰时起止分钟无效" }
    }
    schedule.calendar?.let { calendar ->
        fun date(text: String): LocalDate = runCatching { LocalDate.parse(text) }.getOrNull()
            ?.takeIf { it.toString() == text } ?: throw IllegalArgumentException("日期请使用 yyyy-MM-dd")
        val from = date(calendar.validFrom)
        val through = date(calendar.validThrough)
        require(from <= through) { "日历结束不能早于开始" }
        require(!calendar.confirmed || calendar.source.isNotBlank()) { "确认日历需填写核对来源" }
        val dates = calendar.excludedDates + calendar.includedDates
        require(dates.distinct().size == dates.size) { "日期例外重复或冲突" }
        dates.forEach { require(date(it) in from..through) { "例外日期超出日历有效期" } }
    }
}

/** null 表示该日期尚未核对；不静默将未知日期当作普通工作日。 */
fun PriceSchedule.contains(at: Long): Boolean? {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.of(timeZone))
    val minute = time.hour * 60 + time.minute
    var unknown = false
    ranges.forEach { range ->
        val overnight = range.startMinute > range.endMinute
        val inRange = if (overnight) minute >= range.startMinute || minute < range.endMinute
            else minute >= range.startMinute && minute < range.endMinute
        if (inRange) {
            val day = if (overnight && minute < range.endMinute) time.toLocalDate().minusDays(1) else time.toLocalDate()
            val calendar = calendar
            if (calendar != null && (!calendar.confirmed || day < LocalDate.parse(calendar.validFrom) || day > LocalDate.parse(calendar.validThrough))) unknown = true
            else {
                val date = day.toString()
                val included = calendar?.includedDates.orEmpty()
                val excluded = calendar?.excludedDates.orEmpty()
                if (date !in excluded && (date in included || day.dayOfWeek.value in weekdays)) return true
            }
        }
    }
    return if (unknown) null else false
}

fun PeakWindow.contains(at: Long): Boolean {
    val start = requireNotNull(startMinute)
    val end = requireNotNull(endMinute)
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.of(timeZone))
    val minute = time.hour * 60 + time.minute
    return if (start < end) minute >= start && minute < end else minute >= start || minute < end
}

/** 时间规则在调用开始时求值，所有输入档位的当时价格一并冻结，收尾不再查当前设置。 */
fun resolvePriceEntry(table: PriceTable, entry: ModelPriceEntry, at: Long): ModelPrice {
    PriceCatalogFormat.validateEntry(entry)
    val peak = if (!entry.peakEnabled) false else entry.schedule?.contains(at) ?: if (entry.schedule != null) null else entry.window.contains(at)
    val source = "价格表：${table.name} / ${entry.model}；${entry.source}"
    if (peak == null) return ModelPrice(source = "$source；日历未确认或调用日期超出已核对范围，费用未知", known = false)
    val period = if (!entry.peakEnabled) "标准价" else if (peak) "峰价" else "谷价"
    val tiers = entry.tiers.map { FrozenPriceTier(it.upToInputTokens, if (peak) requireNotNull(it.peak) else it.standard) }
    val rates = if (tiers.isNotEmpty()) tiers.first().rates else if (peak) requireNotNull(entry.peak) else entry.standard
    return ModelPrice(rates.input, rates.output, rates.cached ?: rates.input,
        source = "$source；$period", known = true, cachedPriceKnown = rates.cached != null, frozenTiers = tiers)
}

/** 只使用接口返回的输入总量选档；本地估算跨档风险不能伪装为已知单价。 */
fun ModelPrice.forInput(input: TokenAmount): ModelPrice {
    if (frozenTiers.isEmpty() || !known) return this
    if (input.source != TokenSource.RETURNED || input.value == null)
        return copy(known = false, source = "$source；阶梯计价缺少接口输入用量")
    val tier = frozenTiers.firstOrNull { it.upToInputTokens == null || input.value <= it.upToInputTokens }
        ?: return copy(known = false, source = "$source；输入量超出已配置阶梯")
    val index = frozenTiers.indexOf(tier)
    val lower = if (index == 0) 0 else requireNotNull(frozenTiers[index - 1].upToInputTokens)
    return copy(inputPerM = tier.rates.input, outputPerM = tier.rates.output,
        cachedPerM = tier.rates.cached ?: tier.rates.input, cachedPriceKnown = tier.rates.cached != null,
        source = "$source；输入档位 ($lower, ${tier.upToInputTokens ?: "不限"}]", frozenTiers = emptyList())
}

fun validateFrozenTiers(tiers: List<FrozenPriceTier>) {
    validateTierBounds(tiers.map { it.upToInputTokens })
    tiers.forEach { tier ->
        require(listOfNotNull(tier.rates.input, tier.rates.output, tier.rates.cached).all { it.isFinite() && it >= 0 }) { "冻结阶梯价格无效" }
    }
}

fun formatPriceMinute(minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)

fun parsePriceMinute(text: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
}
