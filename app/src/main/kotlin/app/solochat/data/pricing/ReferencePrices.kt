package app.solochat.data.pricing

import app.solochat.core.model.ModelPriceEntry
import app.solochat.core.model.PriceRates
import app.solochat.core.model.PriceTable

/** 只读核价资料。无法用当前计价结构完整表达的规则没有可复制表。 */
data class ReferencePriceSheet(val name: String, val checkedAt: String, val sources: List<String>,
    val details: String, val copyableTable: PriceTable? = null)

object ReferencePrices {
    private const val DATE = "2026-10-23"
    private const val DEEPSEEK = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
    private const val ALI = "https://help.aliyun.com/zh/model-studio/model-pricing"
    private const val CACHE = "https://help.aliyun.com/zh/model-studio/context-cache"

    val sheets = listOf(
        ReferencePriceSheet("DeepSeek 官方", DATE, listOf(DEEPSEEK),
            "人民币 / 百万 token；顺序为未命中输入 / 缓存命中输入 / 输出。\n" +
                "deepseek-flash：闲时 1 / 0.02 / 4；峰时 2 / 0.04 / 8。\n" +
                "deepseek-v4-pro：闲时 4.5 / 0.15 / 13.5；峰时 9 / 0.30 / 27。\n" +
                "峰时为北京时间工作日 09:00–12:00、14:00–18:00，排除中国法定节假日；其他时间为闲时。\n" +
                "本应用尚不能表达工作日、节假日及每天多个时段，因此仅供核价，不提供整表复制。" +
                "请按实际情况自行配置估算价；不把官方价套给百炼或中转渠道。"),
        ReferencePriceSheet("阿里云百炼 · 北京 · 部分文本模型", DATE, listOf(ALI, CACHE),
            "人民币 / 百万 token；仅实时文本推理，不含 Batch、活动、免费额度或其他地域。\n" +
                "qwen3.7-max：输入 12，输出 36；0 < 输入 token ≤ 1M；隐式缓存命中价按输入价的 20% 计算，即 2.4。\n" +
                "qwen-max：输入 2.4，输出 9.6，无阶梯；缓存价未收录，复制后缓存费用按输入价估算。\n" +
                "qwen3.8-max：输入 12，输出 36；0 < 输入 token ≤ 1M；缓存价须查控制台，故未纳入可复制表。\n" +
                "其他阶梯模型未收录；不能拿第一档价格代表全部输入长度。显式缓存创建与命中的计费不同，未纳入本表。\n" +
                "本资料只覆盖上述条目；复制前须核对本组地域、模型、模式与实际渠道。峰谷初始关闭。",
            PriceTable("reference-bailian-beijing", "百炼北京文本参考（2026-10-23）", listOf(
                ModelPriceEntry("qwen3.7-max", PriceRates(12.0, 36.0, 2.4),
                    note = "北京实时文本推理，输入不超过 1M；缓存为隐式缓存，不含显式缓存创建费用。",
                    source = "$DATE 核对；$ALI；$CACHE", provider = "阿里云百炼 · 北京"),
                ModelPriceEntry("qwen-max", PriceRates(2.4, 9.6),
                    note = "北京非思考实时文本推理，无阶梯；缓存单价未收录，按输入价估算。",
                    source = "$DATE 核对；$ALI", provider = "阿里云百炼 · 北京")
            )))
    )
}
