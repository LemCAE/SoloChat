package app.solochat.data.pricing

import app.solochat.core.model.*
import java.time.LocalDate

/** 只读核价资料。无法用当前计价结构完整表达的规则没有可复制表。 */
data class ReferencePriceSheet(val name: String, val checkedAt: String, val sources: List<String>,
    val details: String, val copyableTable: PriceTable? = null,
    val channelId: String = "", val region: String = "", val entries: List<ReferencePriceEntry> = emptyList())

data class ReferencePriceEntry(val id: String, val requestModelId: String, val rule: ModelPriceEntry?,
    val thinkingMode: String? = null, val conditions: String, val reasonReadOnly: String? = null) {
    val adoptable: Boolean get() = rule != null && reasonReadOnly == null
}

internal fun adoptReferenceRule(entry: ReferencePriceEntry, conditionsConfirmed: Boolean): ModelPriceEntry {
    val rule = requireNotNull(entry.rule) { "该参考条目不能采用" }
    val schedule = rule.schedule ?: return rule
    val calendar = schedule.calendar ?: return rule
    if (calendar.confirmed) return rule
    require(conditionsConfirmed) { "采用前需核对日期与渠道计费条件" }
    // 已确认参考日历后必须同时启用峰谷，否则全天会错误套用闲时价。
    return rule.copy(peakEnabled = true, schedule = schedule.copy(calendar = calendar.copy(confirmed = true)))
}

internal fun referenceChannelMatches(channelId: String, baseUrl: String): Boolean {
    val endpoint = runCatching {
        val value = baseUrl.trim().let { if ("://" in it) it else "https://$it" }
        java.net.URI(value)
    }.getOrNull() ?: return false
    if (!endpoint.scheme.equals("https", ignoreCase = true) || endpoint.userInfo != null ||
        endpoint.port !in listOf(-1, 443)) return false
    val host = endpoint.host?.lowercase()?.removeSuffix(".") ?: return false
    return when (channelId) {
        "deepseek-api" -> host == "api.deepseek.com"
        "aliyun-bailian" -> host == "dashscope.aliyuncs.com"
        else -> false
    }
}

object ReferencePrices {
    private const val DATE = "2026-10-25"
    private const val DEEPSEEK = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
    private const val ALI = "https://help.aliyun.com/zh/model-studio/model-pricing"
    private const val CACHE = "https://help.aliyun.com/zh/model-studio/context-cache"

    private const val HOLIDAYS = "https://www.beijing.gov.cn/cs/gncs/zcwj/202603/t20260327_4568275.html"
    private fun holidays2026(): List<String> = listOf("01-01" to "01-03", "02-15" to "02-23", "04-04" to "04-06",
        "05-01" to "05-05", "06-19" to "06-21", "09-25" to "09-27", "10-01" to "10-07").flatMap { (start, end) ->
        generateSequence(LocalDate.parse("2026-$start")) { it.plusDays(1) }
            .takeWhile { it <= LocalDate.parse("2026-$end") }.map { it.toString() }.toList()
    }
    private fun deepseekEntry(model: String, standard: PriceRates, peak: PriceRates) = ModelPriceEntry(model, standard,
        peak = peak, peakEnabled = false,
        schedule = PriceSchedule(listOf(PriceTimeRange(540, 720), PriceTimeRange(840, 1080)), weekdays = (1..5).toList(),
            calendar = PriceCalendar("2026-01-01", "2026-12-31", holidays2026(),
                source = "2026 国务院放假调休安排；须核对服务商法定节假日口径；$HOLIDAYS", confirmed = false)),
        note = "仅 DeepSeek 官方。默认峰谷关闭；日历为放假调休草稿，须核对渠道口径并确认；2027 年须更新。",
        source = "$DATE 核对；$DEEPSEEK", provider = "DeepSeek 官方")

    val sheets = listOf(
        ReferencePriceSheet("DeepSeek 官方", DATE, listOf(DEEPSEEK, HOLIDAYS),
            "人民币 / 百万 token；顺序为未命中输入 / 缓存命中输入 / 输出。\n" +
                "deepseek-flash：闲时 1 / 0.02 / 4；峰时 2 / 0.04 / 8。\n" +
                "deepseek-v4-pro：闲时 4.5 / 0.15 / 13.5；峰时 9 / 0.30 / 27。\n" +
                "峰时为北京时间工作日 09:00–12:00、14:00–18:00，排除中国法定节假日；其他时间为闲时。\n" +
                "可复制多时段与星期规则，峰谷默认关闭。日历附国务院 2026 放假调休草稿，尚未确认服务商假日口径；" +
                "须在模型规则编辑中核对并确认日历后再开启峰谷。未知日期不猜价格。仅适用官方渠道。",
            PriceTable("reference-deepseek", "DeepSeek 官方参考（2026-10-25）", listOf(
                deepseekEntry("deepseek-flash", PriceRates(1.0, 4.0, 0.02), PriceRates(2.0, 8.0, 0.04)),
                deepseekEntry("deepseek-v4-pro", PriceRates(4.5, 13.5, 0.15), PriceRates(9.0, 27.0, 0.30))
            )), channelId = "deepseek-api", region = "DeepSeek API 官方渠道",
            entries = listOf(
                ReferencePriceEntry("deepseek:deepseek-flash", "deepseek-flash", deepseekEntry("deepseek-flash", PriceRates(1.0, 4.0, 0.02), PriceRates(2.0, 8.0, 0.04)), conditions = "官方 API；北京工作日峰谷；2026 节假日历须用户确认后采用。"),
                ReferencePriceEntry("deepseek:deepseek-v4-pro", "deepseek-v4-pro", deepseekEntry("deepseek-v4-pro", PriceRates(4.5, 13.5, 0.15), PriceRates(9.0, 27.0, 0.30)), conditions = "官方 API；北京工作日峰谷；2026 节假日历须用户确认后采用。")
            )),
        ReferencePriceSheet("阿里云百炼 · 北京 · 部分文本模型", DATE, listOf(ALI, CACHE),
            "人民币 / 百万 token；仅实时文本推理，不含 Batch、活动、免费额度或其他地域。\n" +
                "qwen3.7-max：输入 12，输出 36；0 < 输入 token ≤ 1M；隐式缓存命中价按输入价的 20% 计算，即 2.4。\n" +
                "qwen-max：非思考输入 2.4、输出 9.6，无阶梯；隐式缓存输入价 20%。\n" +
                "qwen3.8-max：输入 12，输出 36；0 < 输入 token ≤ 1M；缓存价须查控制台，故未纳入可复制表。\n" +
                "qwen3.7-plus-2026-05-26：输入 ≤256K 按 2 / 8，256K～1M 按 6 / 24；隐式缓存为输入价 20%。K=1000；按整次输入选档。显式缓存创建费用未纳入。\n" +
                "本资料只覆盖上述条目；复制前须核对本组地域、模型、模式与实际渠道。峰谷初始关闭。",
            PriceTable("reference-bailian-beijing", "百炼北京文本参考（2026-10-25）", listOf(
                ModelPriceEntry("qwen3.7-max", PriceRates(12.0, 36.0, 2.4),
                    note = "北京实时文本推理，输入不超过 1M；缓存为隐式缓存，不含显式缓存创建费用。",
                    source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼 · 北京"),
                ModelPriceEntry("qwen-max", PriceRates(2.4, 9.6, 0.48),
                    note = "北京非思考实时推理，无阶梯；隐式缓存输入单价 20%，不含 Batch。",
                    source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼 · 北京"),
                ModelPriceEntry("qwen3.7-plus-2026-05-26", PriceRates(2.0, 8.0, 0.4),
                    tiers = listOf(PriceTier(256_000, PriceRates(2.0, 8.0, 0.4)), PriceTier(1_000_000, PriceRates(6.0, 24.0, 1.2))),
                    note = "北京非思考实时推理；按单次请求输入量整档计费；不含 Batch、免费额度与显式缓存创建费用。",
                    source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼 · 北京")
            )), channelId = "aliyun-bailian", region = "中国内地 · 华北 2（北京）",
            entries = listOf(
                ReferencePriceEntry("bailian:qwen3.7-max", "qwen3.7-max",
                    ModelPriceEntry("qwen3.7-max", PriceRates(12.0, 36.0, 2.4), note = "输入≤1M，隐式缓存输入价 20%；不含 Batch。", source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼"),
                    conditions = "中国内地实时推理；思考与非思考模式；0 < 输入 ≤ 1M；排除 Batch/free quota。"),
                ReferencePriceEntry("bailian:qwen-max", "qwen-max",
                    ModelPriceEntry("qwen-max", PriceRates(2.4, 9.6, 0.48), note = "非思考、无阶梯；隐式缓存输入价 20%；不含 Batch。", source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼"),
                    thinkingMode = "OFF", conditions = "中国内地实时非思考推理；无阶梯；排除 Batch/free quota。"),
                ReferencePriceEntry("bailian:qwen3.7-plus-2026-05-26", "qwen3.7-plus-2026-05-26",
                    ModelPriceEntry("qwen3.7-plus-2026-05-26", PriceRates(2.0, 8.0, 0.4), tiers = listOf(
                        PriceTier(256_000, PriceRates(2.0, 8.0, 0.4)), PriceTier(1_000_000, PriceRates(6.0, 24.0, 1.2))),
                        note = "非思考；隐式缓存输入价 20%；不含 Batch/free quota/显式缓存创建。", source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼"),
                    thinkingMode = "OFF", conditions = "中国内地实时非思考推理；256K/1M 阶梯按单次输入量；排除 Batch/free quota。"),
                ReferencePriceEntry("bailian:qwen3.8-max", "qwen3.8-max",
                    ModelPriceEntry("qwen3.8-max", PriceRates(12.0, 36.0), note = "输入≤1M；缓存费须从控制台核对。", source = "$DATE 核对；$ALI", provider = "阿里云百炼"),
                    conditions = "中国内地实时文本推理；输入≤1M；缓存单价需控制台核对。",
                    reasonReadOnly = "缓存单价不在公开页内，当前价格规则无法完整表达。")
            ))
    )
}
