package app.solochat.data.pricing

import android.content.Context
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.model.ModelPrice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        if (!config.useOfficialPrice) return fallback(config, "已关闭内置价格表，用自填单价")

        val id = model.trim().lowercase()
        if (id.isEmpty()) return fallback(config, "未选模型")

        val entry = table[id] ?: stripSnapshotSuffix(id)?.let { table[it] }
        if (entry == null) {
            return if (config.priceInputPerM > 0 || config.priceOutputPerM > 0) {
                fallback(config, "未匹配内置价，用自填单价")
            } else {
                fallback(config, "未匹配内置价，也未填单价")
            }
        }

        fun num(key: String): Double = runCatching {
            entry[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
        }.getOrDefault(0.0)

        return ModelPrice(
            inputPerM = num("in"),
            outputPerM = num("out"),
            // cached 为 null/0 在源数据里表示"该模型没有缓存优惠"，
            // 这时按输入价计（estimateCost 里的逻辑），而不是当成免费。
            cachedPerM = num("cached"),
            // 图片在源数据里是按 token 封顶计，不按张；不折成金额以避免虚报
            imagePerCall = 0.0,
            source = entry["src"]?.jsonPrimitive?.contentOrNull ?: "内置表"
        )
    }

    /** 这个模型名到底匹配到价格表的哪一条；没匹配返回 null。 */
    fun matchedId(table: Map<String, JsonObject>, model: String): String? {
        val id = model.trim().lowercase()
        if (id.isEmpty()) return null
        if (table.containsKey(id)) return id
        return stripSnapshotSuffix(id)?.takeIf { table.containsKey(it) }
    }

    private fun fallback(config: ChatConfig, source: String) = ModelPrice(
        inputPerM = config.priceInputPerM,
        outputPerM = config.priceOutputPerM,
        cachedPerM = config.priceCachedPerM,
        source = source
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
class ModelPricing(context: Context) : PriceResolver {

    private val table: Map<String, JsonObject> by lazy { load(context) }

    /** 内置表里有多少条（设置页/诊断用）。 */
    val size: Int get() = table.size

    override fun resolve(model: String, config: ChatConfig): ModelPrice =
        PricingRules.resolve(table, model, config)

    /** 匹配到价格表的哪一条；没匹配返回 null。 */
    fun matchedId(model: String): String? = PricingRules.matchedId(table, model)

    private fun load(context: Context): Map<String, JsonObject> = runCatching {
        val text = context.assets.open("data/model-prices.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        PricingRules.parseTable(text)
    }.getOrElse { emptyMap() }
}
