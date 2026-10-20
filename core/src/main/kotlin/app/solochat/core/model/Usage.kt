package app.solochat.core.model

import kotlinx.serialization.Serializable

/**
 * 关于用户的一条长期记忆。
 *
 * 与 QQ-agent 的差别：那边是"每个群友一份档案"（多人多维），这边只有你一个人，
 * 所以简化为**一条一条带标签的事实**，靠标签和相似度检索而不是靠人来分组。
 */
@Serializable
data class Memory(
    val id: String,
    val content: String,

    /**
     * 这条记忆属于哪张人物卡。**空字符串 = 通用**（哪张人设都能看到）。
     *
     * 为什么必须区分：不同人设是不同的人。共用一份记忆会让「小默」知道只有跟
     * 「阿岩」聊过的事 —— 那是这个 App 里最容易破坏沉浸感的一种错，
     * 而且用户很难指出哪里不对，只会觉得"它怎么知道这个"。
     *
     * 空值当"通用"也是**升级路径**：v1 的库没有这一列，迁移时把能从来源会话推出
     * 人设的那些补上，补不出来的留成通用（宁可多看一条，也不要让用户发现记忆少了）。
     */
    val personaId: String = "",

    /** 标签，用于检索与分组（如"喜好"、"作息"、"工作"）。 */
    val tags: List<String> = emptyList(),

    val createdAt: Long = 0,

    /** 最近一次被注入上下文或被检索到。用于淘汰。 */
    val lastUsedAt: Long = 0,

    /** 被提及次数。与 [lastUsedAt] 一起决定淘汰顺序。 */
    val useCount: Int = 0,

    /** 来源会话，便于回溯"它怎么知道的"。 */
    val sourceConversationId: String = "",

    /** 用户手工固定，永不淘汰。 */
    val pinned: Boolean = false
)

/**
 * 一段场景的摘要。
 *
 * 面对面模式的上下文增长是聊天模式的 3~10 倍，所以"场景结束压缩成摘要"
 * **不是优化，是这个模式能长期使用的前提**（见 docs/01-decisions.md D7 的代价一节）。
 */
@Serializable
data class SceneSummary(
    val id: String,
    val conversationId: String,

    /** 被压缩的事件范围（闭区间），用于还原与去重。 */
    val fromEventId: Long,
    val toEventId: Long,

    /** 摘要正文。 */
    val text: String,

    /** 生成摘要时用的模型，便于排查"摘要质量变差"。 */
    val model: String = "",

    val createdAt: Long = 0
)

/**
 * 一次调用的用量。价格按"元 / 百万 token"记，与模型价格表口径一致。
 */
@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,

    /** 命中 KV 缓存的前缀 token 数（多数厂商会在 usage 里给）。 */
    val cachedTokens: Int = 0,

    /** 推理 token（部分厂商单独计费）。 */
    val reasoningTokens: Int = 0,

    /** 图片计费口径（张数），没有则为 0。 */
    val imageCount: Int = 0,

    /**
     * true 表示这几个 token 数是本地估的（网关没返回 usage）。
     * **UI 上必须明确标注"估算"** —— 不能让用户以为这是账单数字。
     */
    val estimated: Boolean = false
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/**
 * 一个模型的单价。字段可与 QQ-agent 的价格表对齐（元 / 百万 token）。
 */
@Serializable
data class ModelPrice(
    val inputPerM: Double = 0.0,
    val outputPerM: Double = 0.0,

    /** 命中缓存的输入单价。留 0 时按 [inputPerM] 计。 */
    val cachedPerM: Double = 0.0,

    /** 图片附加计费（元 / 张），没有则 0。 */
    val imagePerCall: Double = 0.0,

    /** 价格来源说明（"内置表" / "自填" / "未匹配"）。 */
    val source: String = "",
    val known: Boolean = inputPerM > 0.0 || outputPerM > 0.0,
    val cachedPriceKnown: Boolean = cachedPerM > 0.0,
    val currency: String = "CNY"
)

/** 成本估算结果。UI 只展示这个，不自己算。 */
@Serializable
data class CostEstimate(
    val money: Double = 0.0,

    /** true = 用了兜底单价或本地估的 token 数，金额只是个数量级。 */
    val rough: Boolean = true,

    /** 展示用说明，例如"未匹配官方价，按自填单价计"。 */
    val note: String = ""
)

/**
 * 按 token 数与单价估算成本。
 *
 * 刻意做成纯函数放在 model 层：它要被单元测试反复验证（缓存价、图片价、兜底价三种口径），
 * 也决定了 UI 上那个数字是否可信。
 */
fun estimateCost(usage: Usage, price: ModelPrice): CostEstimate {
    if (price.inputPerM <= 0.0 && price.outputPerM <= 0.0) {
        return CostEstimate(money = 0.0, rough = true, note = "未设置单价")
    }
    val notes = mutableListOf<String>()

    // 缓存命中的部分单独计价：命中约 1/10 价，不是免费，所以要分开算。
    val cached = usage.cachedTokens.coerceAtMost(usage.promptTokens).coerceAtLeast(0)
    val freshPrompt = (usage.promptTokens - cached).coerceAtLeast(0)

    val cachedRate = if (price.cachedPerM > 0.0) price.cachedPerM else price.inputPerM
    if (price.cachedPerM <= 0.0 && cached > 0) notes += "缓存价未设，按输入价计"

    val inputMoney = freshPrompt / 1_000_000.0 * price.inputPerM +
        cached / 1_000_000.0 * cachedRate
    val outputMoney = usage.completionTokens / 1_000_000.0 * price.outputPerM
    val imageMoney = usage.imageCount * price.imagePerCall

    if (usage.estimated) notes += "token 为本地估算"
    if (price.source.isBlank()) notes += "单价来源未知"

    return CostEstimate(
        money = inputMoney + outputMoney + imageMoney,
        rough = usage.estimated || price.inputPerM <= 0.0,
        note = notes.joinToString("；")
    )
}
