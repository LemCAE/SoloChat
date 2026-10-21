package app.solochat.data.pricing

import android.content.Context
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 价格表的匹配与兜底**纯逻辑**。
 *
 * 特意与"从 assets 读文件"分开：纯函数才能用普通的 JUnit 测试覆盖（不需要 Robolectric，
 * 也就不需要在测试运行时下载 android-all —— 那在离线环境下必然失败）。
 * 这也是我把 [ModelPricing] 拆成"加载"和"匹配"两半的唯一理由。
 */
object PricingRules {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从 model-prices.json 的正文解析出 id → 条目。解析失败返回空表（不是抛异常）。 */
    fun parseTable(text: String): Map<String, JsonObject> = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val models = root["models"]?.jsonObject ?: return emptyMap()
        models.mapValues { (_, v) -> runCatching { v.jsonObject }.getOrNull() ?: JsonObject(emptyMap()) }
            .filterValues { it.isNotEmpty() }
    }.getOrElse { emptyMap() }

    /**
     * 匹配内置价。
     *
     * **只做精确 id + 一次快照后缀剥离，绝不模糊猜。** 理由：错误的成本数字比没有数字更糟 ——
     * 用户会拿它做决策。QQ-agent 的 `resolveOfficialPrice` 用前缀匹配，那在中转站
     * 五花八门的模型名上容易套错价（见 docs/01-decisions.md）。
     */
    fun resolve(table: Map<String, JsonObject>, model: String, config: ChatConfig): ModelPrice {
        // 旧调用入口也使用同一份结构化解析，避免维护两套匹配/缓存/兜底规则。
        return resolveCatalog(initialCatalog(table), model, config, 0)
    }

    /** 这个模型名到底匹配到价格表的哪一条；没匹配返回 null。 */
    fun matchedId(table: Map<String, JsonObject>, model: String): String? {
        return matchedModelId(table.keys, model)
    }

    fun matchedModelId(ids: Set<String>, model: String): String? {
        val id = model.trim().lowercase()
        if (id.isEmpty()) return null
        if (id in ids) return id
        return stripSnapshotSuffix(id)?.takeIf { it in ids }
    }

    /** 原包金额保留为快照；peak 只有金额、没有时段，因此初始一律关闭。 */
    fun initialCatalog(table: Map<String, JsonObject>): PriceCatalog {
        fun rates(entry: JsonObject): PriceRates {
            fun num(key: String) = (entry[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            return PriceRates(num("in"), num("out"), num("cached").takeIf { it > 0 })
        }
        return PriceCatalog("bundled", listOf(PriceTable("bundled", "原内置表（随包快照）",
            table.map { (model, entry) -> ModelPriceEntry(model, rates(entry),
                peak = (entry["peak"] as? JsonObject)?.let(::rates),
                note = (entry["note"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                source = "随包快照：" + ((entry["src"] as? JsonPrimitive)?.contentOrNull ?: "来源未注明")) })))
    }

    fun resolveCatalog(catalog: PriceCatalog, model: String, config: ChatConfig, at: Long): ModelPrice {
        if (!config.useOfficialPrice) return fallback(config, "已关闭价格表，用自填单价")
        val selected = catalog.tables.firstOrNull { it.id == catalog.selectedTableId }
            ?: return fallback(config, "价格表不可用，用自填单价")
        val entries = selected.entries.associateBy { it.model }
        val matched = matchedModelId(entries.keys, model)
            ?: return fallback(config, "未匹配价格表，用自填单价（未配置时费用未知）")
        return resolvePriceEntry(selected, entries.getValue(matched), at)
    }

    private fun fallback(config: ChatConfig, source: String) = ModelPrice(
        inputPerM = config.priceInputPerM,
        outputPerM = config.priceOutputPerM,
        cachedPerM = config.priceCachedPerM,
        source = source,
        known = config.priceConfigured ?: (config.priceInputPerM > 0.0 || config.priceOutputPerM > 0.0),
        cachedPriceKnown = config.cachedPriceConfigured ?: (config.priceCachedPerM > 0.0)
    )

    /**
     * 剥掉常见的"快照/版本后缀"，只在精确匹配失败时用一次。
     *
     * 支持：`-0731`、`-20250901`、`-v2`，以及 `:free` / `:beta` / `:preview` / `:latest`。
     * 刻意**不支持** `-pro`、`-mini` 这类语义后缀 —— 那些是另一个模型，价格通常不同。
     */
    fun stripSnapshotSuffix(id: String): String? {
        var candidate = id
        val colon = candidate.indexOf(':')
        if (colon > 0) {
            val suffix = candidate.substring(colon + 1)
            if (suffix in setOf("free", "beta", "preview", "latest")) {
                candidate = candidate.substring(0, colon)
            }
        }
        val regex = Regex("-(v?\\d[\\d.]*)$")
        val stripped = regex.replace(candidate, "")
        return stripped.takeIf { it != id && it.isNotEmpty() }
    }
}

/**
 * 模型价格解析（Android 侧）。
 *
 * 数据是 `assets/data/model-prices.json`，从 QQ-agent 的 `src/model-prices.js`
 * **直接序列化**出来的（140 条，字段 `in/out/cached/peak/image/note/src`，单位：元 / 百万 token）。
 *
 * **不做远程价格表**：QQ-agent 有"远程表按 id 覆盖内置表"的机制，但那需要额外一次网络请求、
 * 一个缓存文件和一个失效策略。价格只影响"展示给用户的估算"，不影响任何请求行为，
 * 用户想精确就填自填单价 —— 第一版不值得为此增加一条网络依赖。
 */
class ModelPricing(context: Context, private val now: () -> Long) : PriceResolver {

    private val table: Map<String, JsonObject> by lazy { load(context) }

    /** 内置表里有多少条（设置页/诊断用）。 */
    val size: Int get() = table.size
    val initialCatalog: PriceCatalog by lazy { PricingRules.initialCatalog(table) }

    override fun resolve(model: String, config: ChatConfig): ModelPrice =
        resolve(model, config, now())

    override fun resolve(model: String, config: ChatConfig, at: Long): ModelPrice =
        PricingRules.resolveCatalog(config.priceCatalog ?: initialCatalog, model, config, at)

    /** 匹配到价格表的哪一条；没匹配返回 null。 */
    fun matchedId(model: String): String? = PricingRules.matchedId(table, model)

    private fun load(context: Context): Map<String, JsonObject> = runCatching {
        val text = context.assets.open("data/model-prices.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        PricingRules.parseTable(text)
    }.getOrElse { emptyMap() }
}
