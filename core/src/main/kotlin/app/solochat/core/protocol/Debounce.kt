package app.solochat.core.protocol

import app.solochat.core.model.EventKind
import kotlin.math.max
import kotlin.math.min

/**
 * 一条刚到达的用户消息（还没决定要不要立刻开工）。
 */
data class IncomingMessage(
    val text: String = "",
    val isMedia: Boolean = false,
    val at: Long,

    /**
     * 这一句是什么：说话 / 动作 / 环境。
     *
     * 只在这里存**原始正文 + 类型**，编码成 `*…*` / `（…）` 是落盘那一刻的事
     * （见 `UserUtterance.encode`）。理由：防抖要看的是**用户真正打的那几个字**
     * —— 带上标记会让"结尾是不是标点"这类判断失准（`*把伞收起来*` 的最后一个字符
     * 是星号，会被当成"没说完"）。
     */
    val kind: EventKind = EventKind.SAY
)

/**
 * 防抖判定的档位。
 *
 * 档位是**给人看的解释**，不是给机器算的分支 —— 用户问"它为什么等这么久"，
 * 界面上要能直接显示这里的 [DebounceDecision.reason]。
 */
enum class DebounceTier {
    /** 一句话说完了（有句末标点 / 换行）。 */
    COMPLETE,

    /** 距上一条已经很久，是新一轮。 */
    NEW_TURN,

    /** 60 秒内已经连发过，正在分句，继续等。 */
    CHUNKING,

    /** 短句、没标点、紧接着上一条 —— 明显是前半句。 */
    UNFINISHED_SHORT,

    /** 图片 / 表情 / 语音，通常独立成条。 */
    MEDIA,

    /** 等太久了，不管像不像说完都开工。 */
    HARD_CAP
}

data class DebounceDecision(
    /** 再等多少毫秒。0 = 立刻开工。 */
    val waitMs: Long,

    val tier: DebounceTier,

    /** 展示用理由，例如"看起来你还在分句，再等一下"。 */
    val reason: String
) {
    val isImmediate: Boolean get() = waitMs <= 0
}

/**
 * 从真实使用中学会"这个人一次要说几条、隔多久"。
 *
 * 为什么值得单独做：**只有你一个人用**，所以个性化收益拉满、成本为零。
 * 通用聊天产品做不到这件事（它们得猜所有人）。
 *
 * 具体学的是「你连发时，两条之间典型会停多久」：这个值一旦收敛，
 * 防抖窗口就自动贴合你的手速 —— 打字快的人不用等，慢条斯理的人也不会被抢话。
 * 前若干条样本太少时用保守默认值（宁可多等一点，也不要对着半句抢答）。
 */
object RhythmLearner {

    /** 开始信任统计结果所需的最少样本数。少于这个数一律用保守默认值。 */
    const val MIN_SAMPLES = 8

    /** 判定"属于同一次连发"的最大间隔：超过就当成新一轮，不参与学习。 */
    const val SAME_TURN_MAX_GAP_MS = 60_000L

    private const val DEFAULT_WINDOW_MS = 3_500L

    /**
     * 学会的典型间隔。取样本的高分位（而不是平均值）——
     * 平均会被少数"停很久"的样本拉偏，导致窗口偏大、延迟明显；
     * 高分位则意味着"大部分情况下都等够了"。
     */
    fun learnedWindowMs(samples: List<Long>): Long {
        // 下界放宽到 0：手速极快的人确实会有几十毫秒的样本，把它们过滤掉会让
        // 学习结果永远停在默认值上（这是一个测试抓出来的 bug）。
        // 上界仍然按"同一次连发"的阈值卡，超过的属于新一轮，不该参与学习。
        val clean = samples.filter { it in 0..SAME_TURN_MAX_GAP_MS }.sorted()
        if (clean.size < MIN_SAMPLES) return DEFAULT_WINDOW_MS
        val index = ((clean.size - 1) * 0.75).toInt()
        return clean[index].coerceIn(600L, 8_000L)
    }

    /**
     * 从这个人的历史消息里统计连发间隔。
     *
     * @param stamps 历史消息时间戳（任意顺序，函数内部会排）。
     * @param mediaFlags 与 stamps 一一对应，标记是否媒体消息。
     */
    fun observe(stamps: List<Long>, mediaFlags: List<Boolean>): List<Long> {
        if (stamps.size < 2) return emptyList()
        val pairs = stamps.indices
            .map { stamps[it] to (mediaFlags.getOrNull(it) ?: false) }
            .sortedBy { it.first }

        val samples = mutableListOf<Long>()
        for (i in 1 until pairs.size) {
            val (prevAt, prevMedia) = pairs[i - 1]
            val (nowAt, nowMedia) = pairs[i]
            // 媒体消息的间隔不代表打字节奏，排除掉免得污染样本
            if (prevMedia || nowMedia) continue
            val gap = nowAt - prevAt
            if (gap in 200..SAME_TURN_MAX_GAP_MS) samples += gap
        }
        return samples
    }
}

/**
 * 用户侧防抖 —— **规则 + 自适应，0 次 API 调用**。
 *
 * 为什么不做语义预判（"问模型他说完了吗"）：那样每条消息都要带完整上下文调一次模型，
 * 成本和正式回复一样贵，而收益只是省几秒。更关键的是**代价不对称**：
 * 唯一会犯的错是早开工（拿到半句就生成，答偏且半句已落盘难收拾），
 * 而晚开工的代价只是一点延迟。所以目标是"判断错也没关系"，不是"判断准"。
 * 详见 docs/01-decisions.md D6。
 *
 * 判断权最后交给读得懂语义的那一方：模型可以用 `wait` 事件表示"我在等你把话说完"。
 */
object Debounce {

    /** 任何情况下都不超过这个等待：用户会以为软件卡死。 */
    const val HARD_CAP_MS = 30_000L

    /** 距上一条超过这么久，认为是新一轮。 */
    private const val NEW_TURN_GAP_MS = 60_000L

    private const val COMPLETE_MIN = 400L
    private const val COMPLETE_MAX = 800L
    private const val MEDIA_MIN = 400L
    private const val MEDIA_MAX = 800L
    private const val CHUNKING_MIN = 2_000L
    private const val CHUNKING_MAX = 4_000L
    private const val SHORT_MIN = 4_000L
    private const val SHORT_MAX = 6_000L

    /** 短句阈值：短于这个字数且没有标点，倾向于认为没说完。 */
    private const val SHORT_TEXT_LIMIT = 12

    /** 长消息阈值：这么长基本是一段话的完结。 */
    private const val LONG_TEXT_LIMIT = 60

    private val END_PUNCTUATION = setOf(
        '。', '！', '？', '…', '~', '～', '!', '?', '.', '；', ';', '：', ':', '\n', '】', ')', '）'
    )

    /** 句末"未完成"的强信号：这些结尾几乎一定还有下文。 */
    private val DANGLING_ENDINGS = listOf(
        "我", "你", "他", "她", "它", "的", "了", "在", "和", "跟", "把", "被", "让",
        "是", "有", "要", "想", "去", "来", "说", "给", "对", "从", "向", "因为", "所以",
        "但是", "然后", "而且", "如果", "那么", "就", "都", "也", "还", "又"
    )

    /**
     * 判定该等多久。
     *
     * @param gapToPreviousMs 距上一条用户消息的间隔；null = 这是第一条
     * @param recentBurstCount 最近 60 秒内（含这一条）用户发了几条
     * @param windowMs 自适应窗口，由 [RhythmLearner.learnedWindowMs] 给出
     * @param totalWaitedMs 从这一批第一条到现在已经等了多久（用于硬上限）
     */
    fun decide(
        message: IncomingMessage,
        gapToPreviousMs: Long?,
        recentBurstCount: Int,
        windowMs: Long = 3_500L,
        totalWaitedMs: Long = 0,
        /** 注入随机源，测试里传固定种子就能断言确定值。 */
        random: kotlin.random.Random = kotlin.random.Random.Default
    ): DebounceDecision {
        // 硬上限优先：等太久了必须开工，否则界面像卡死
        if (totalWaitedMs >= HARD_CAP_MS) {
            return DebounceDecision(
                waitMs = 0,
                tier = DebounceTier.HARD_CAP,
                reason = "已经等了 ${HARD_CAP_MS / 1000} 秒，先回你"
            )
        }

        // 距上一条很久（或这是第一条）→ 新一轮，不用犹豫
        if (gapToPreviousMs == null || gapToPreviousMs > NEW_TURN_GAP_MS) {
            return DebounceDecision(0, DebounceTier.NEW_TURN, "新的话题，直接回")
        }

        if (message.isMedia) {
            val wait = randomIn(MEDIA_MIN, MEDIA_MAX, random)
            return DebounceDecision(wait, DebounceTier.MEDIA, "收到图片/表情，愣了一下")
        }

        val text = message.text.trim()
        val len = text.length

        // 长消息：一段话讲完了，不用等
        if (len >= LONG_TEXT_LIMIT) {
            val wait = randomIn(COMPLETE_MIN, COMPLETE_MAX, random)
            return DebounceDecision(wait, DebounceTier.COMPLETE, "你说了一整段，直接回")
        }

        val endsComplete = text.isNotEmpty() && END_PUNCTUATION.contains(text.last())

        // 顺序很重要：**"你正在连发"要先判**。
        //
        // 反例（这正是第一版写错的地方）：你在分条发"然后我就走了"（6 个字，没标点），
        // 如果先按"短句"处理，就会判定成"还在打字"并等 4~6 秒；
        // 而实际上你已经发了三条，需要的只是"再给你一点时间把下一条打出来"。
        // 分条时的等待应由自适应窗口决定（贴合你的手速），而不是被短句规则顶到上限。
        if (recentBurstCount >= 2 && !endsComplete) {
            val wait = windowMs.coerceIn(CHUNKING_MIN, CHUNKING_MAX)
            return DebounceDecision(wait, DebounceTier.CHUNKING, "你在分开发的，再等一下")
        }

        // 短句 + 没标点 + 紧接着上一条 → 明显是前半句，等久点
        if (len in 1 until SHORT_TEXT_LIMIT && !endsComplete) {
            val dangling = DANGLING_ENDINGS.any { text.endsWith(it) }
            val base = randomIn(SHORT_MIN, SHORT_MAX, random)
            val wait = if (dangling) base + 1_500 else base
            return DebounceDecision(
                wait,
                DebounceTier.UNFINISHED_SHORT,
                if (dangling) "这句话明显没说完（结尾是「${text.takeLast(2)}」），等你打完" else "像是还在打字，等你打完"
            )
        }

        // 有句末标点：一句完整的话
        if (endsComplete) {
            val wait = randomIn(COMPLETE_MIN, COMPLETE_MAX, random)
            return DebounceDecision(wait, DebounceTier.COMPLETE, "一句完整的话，直接回")
        }

        // 兜底：中等长度、没标点、不像是分句 —— 用自适应窗口，但不拖太久
        val wait = min(max(windowMs, COMPLETE_MAX), CHUNKING_MAX)
        return DebounceDecision(wait, DebounceTier.CHUNKING, "稍等一下，怕你还有下半句")
    }

    /**
     * 区间随机，避免"每次都刚好 600ms"这种机械感。
     * 之所以要把 [random] 一路传下来：测试需要确定性，而生产需要随机性。
     */
    private fun randomIn(min: Long, max: Long, random: kotlin.random.Random): Long {
        if (max <= min) return min
        return min + random.nextLong(0, max - min)
    }
}
