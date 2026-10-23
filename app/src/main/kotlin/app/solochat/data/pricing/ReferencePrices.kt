package app.solochat.data.pricing

import app.solochat.core.model.*
import java.time.LocalDate

/** 只读核价资料。无法用当前计价结构完整表达的规则没有可复制表。 */
data class ReferencePriceSheet(val name: String, val checkedAt: String, val sources: List<String>,
    val details: String, val copyableTable: PriceTable? = null)

object ReferencePrices {
    private const val DATE = "2026-10-24"
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
            PriceTable("reference-deepseek", "DeepSeek 官方参考（2026-10-24）", listOf(
                deepseekEntry("deepseek-flash", PriceRates(1.0, 4.0, 0.02), PriceRates(2.0, 8.0, 0.04)),
                deepseekEntry("deepseek-v4-pro", PriceRates(4.5, 13.5, 0.15), PriceRates(9.0, 27.0, 0.30))
            ))),
        ReferencePriceSheet("阿里云百炼 · 北京 · 部分文本模型", DATE, listOf(ALI, CACHE),
            "人民币 / 百万 token；仅实时文本推理，不含 Batch、活动、免费额度或其他地域。\n" +
                "qwen3.7-max：输入 12，输出 36；0 < 输入 token ≤ 1M；隐式缓存命中价按输入价的 20% 计算，即 2.4。\n" +
                "qwen-max：输入 2.4，输出 9.6，无阶梯；缓存价未收录，复制后缓存费用按输入价估算。\n" +
                "qwen3.8-max：输入 12，输出 36；0 < 输入 token ≤ 1M；缓存价须查控制台，故未纳入可复制表。\n" +
                "qwen3.7-plus-2026-05-26：输入 ≤256K 按 2 / 8，256K～1M 按 6 / 24；隐式缓存为输入价 20%。K=1000；按整次输入选档。显式缓存创建费用未纳入。\n" +
                "本资料只覆盖上述条目；复制前须核对本组地域、模型、模式与实际渠道。峰谷初始关闭。",
            PriceTable("reference-bailian-beijing", "百炼北京文本参考（2026-10-24）", listOf(
                ModelPriceEntry("qwen3.7-max", PriceRates(12.0, 36.0, 2.4),
                    note = "北京实时文本推理，输入不超过 1M；缓存为隐式缓存，不含显式缓存创建费用。",
                    source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼 · 北京"),
                ModelPriceEntry("qwen-max", PriceRates(2.4, 9.6),
                    note = "北京非思考实时文本推理，无阶梯；缓存单价未收录，按输入价估算。",
                    source = "$DATE 核对；$ALI", provider = "阿里云百炼 · 北京"),
                ModelPriceEntry("qwen3.7-plus-2026-05-26", PriceRates(2.0, 8.0, 0.4),
                    tiers = listOf(PriceTier(256_000, PriceRates(2.0, 8.0, 0.4)), PriceTier(1_000_000, PriceRates(6.0, 24.0, 1.2))),
                    note = "北京实时推理；按接口返回输入量整档计费；不含促销、Batch 与显式缓存创建费用。",
                    source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼 · 北京")
            )))
    )
}
