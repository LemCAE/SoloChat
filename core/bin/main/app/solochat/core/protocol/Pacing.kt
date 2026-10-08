package app.solochat.core.protocol

import app.solochat.core.model.EventKind
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 一次播出（点开对话到对方开始打字）之间的"阅读延迟"。
 */
data class ReadDelay(val ms: Long, val reason: String)

/**
 * 一条气泡的播出节奏。
 */
data class BubblePace(
    /** 与上一条的间隔。 */
    val gapMs: Long,

    /** "正在输入…"的时长；0 = 不显示打字指示，直接出现。 */
    val typingMs: Long
)

/**
 * 播出节奏计算 —— 让"分条发送"看起来像真人在打字。
 *
 * 两条来源**混合**，缺一不可：
 *
 * 1. **模型给的感觉值**（`gap_ms`）：它读得懂当前气氛 —— 吵架时停顿久、玩笑时接得快。
 *    这种信息任何公式都算不出来。
 * 2. **按字数的物理值**：一个字一个字敲出来需要的时间。这部分由引擎算，
 *    因为模型算不准（它会报出 50 秒这种离谱值），而且"越长越慢"这条规律要稳。
 *
 * 取两者加权而不是二选一：模型给值时偏向它（但用物理值兜底），没给值时纯物理。
 *
 * 按字数那条算法移植自 QQ-agent 的 `plugins/send-pacing`（MIT, Copyright (c) 2026 Kondius）：
 * **每个字各自**在区间内随机取值再累加，不是"字数 × 固定速率" ——
 * 所以同一句话每次的总时长都不一样，不死板。
 */
class Pacing(
    private val config: PacingConfig = PacingConfig(),
    private val random: Random = Random.Default
) {

    /**
     * 收到消息后，多久开始打字。
     *
     * @param incomingChars 用户刚发来的内容长度（越长的消息"看得越久"）
     * @param moodHintMs 模型给的情绪提示（可选）：冷淡、在忙、生气时它知道自己该慢一点
     */
    fun readDelay(incomingChars: Int, moodHintMs: Int? = null): ReadDelay {
        val base = when {
            incomingChars <= 0 -> config.readShortMs
            incomingChars < 20 -> config.readShortMs
            incomingChars < 60 -> config.readMediumMs
            else -> config.readLongMs
        }
        val jitter = randomIn(0, config.readJitterMs)
        // moodHintMs 是协议来的 Int，先转 Long 再夹，避免 Int 与 Long 混算
        val hinted = moodHintMs?.toLong()?.coerceIn(0L, config.readHintMaxMs)
        val total = (hinted ?: (base + jitter)).coerceIn(0L, config.readHintMaxMs)
        return ReadDelay(
            ms = total,
            reason = when {
                hinted != null -> "按当前气氛愣了 ${total}ms"
                // ⚠️ 不说"她/他"：这个 App 里的人设可能是任何一个角色（见 02-handoff §3 ⑧）
                incomingChars >= 60 -> "你发了一段长的，看了一会儿"
                else -> "愣了一下才开始打字"
            }
        )
    }

    /**
     * 算一条气泡的播出节奏。
     *
     * @param text 这条气泡的正文
     * @param gapHintMs 模型给的间隔建议
     * @param typingHintMs 模型给的"打了又删"时长
     * @param isMedia 图片 / 表情：间隔另算（不按字数，按"找图"的时间）
     * @param sceneKind **非空表示这一条在面对面模式里**，值是它的事件类型。
     *   面对面走的是另一套节奏，见 [scenePace]。
     */
    fun pace(
        text: String,
        gapHintMs: Int? = null,
        typingHintMs: Int? = null,
        isMedia: Boolean = false,
        sceneKind: EventKind? = null
    ): BubblePace {
        if (sceneKind != null) return scenePace(text, gapHintMs, typingHintMs, sceneKind)

        val typing = when {
            typingHintMs != null && typingHintMs > 0 -> typingHintMs.toLong()
            isMedia -> 0L
            else -> physicalTypingMs(text)
        }.coerceAtMost(config.typingMaxMs)

        val gap = when {
            isMedia -> randomIn(config.mediaGapMinMs, config.mediaGapMaxMs)
            gapHintMs != null && gapHintMs >= 0 -> blend(gapHintMs.toLong(), physicalGapMs(text))
            else -> physicalGapMs(text)
        }.coerceIn(config.gapMinMs, config.gapMaxMs)

        return BubblePace(gapMs = gap, typingMs = typing)
    }

    /**
     * **面对面模式的节奏 —— 和聊天模式是两套东西，不能共用一个公式。**
     *
     * 你们在同一个空间里，不是一个字一个字敲出来。所以：
     *
     * 1. **不算打字时间。** `字数 × 每字耗时` 那套在这里没有意义（"还在输入…"
     *    这个暗示本身就不存在于面对面）。只有模型显式给了 `typing_ms` 才留白 ——
     *    那表示"欲言又止"，是它主动要的沉默。
     * 2. **动作与旁白忽略字数。** 一个动作写三个字还是三十个字，发生的时间差不多；
     *    按字数排会让长句旁白把场景拖住十几秒（真机上就是"一顿一顿"）。
     *    它们之间只留一个固定的小拍子。
     * 3. **台词按字数，但短得多。** 说话确实有长短，但面对面的台词比打字快 ——
     *    没有"发送"这个动作，张口就来。
     *
     * 模型显式给了 `gap_ms` 时一律尊重（面对面协议里虽然让它别给，但给了就是有意为之）。
     */
    private fun scenePace(
        text: String,
        gapHintMs: Int?,
        typingHintMs: Int?,
        kind: EventKind
    ): BubblePace {
        val typing = (typingHintMs?.takeIf { it > 0 }?.toLong() ?: 0L)
            .coerceAtMost(config.typingMaxMs)

        val gap = when {
            gapHintMs != null && gapHintMs >= 0 -> gapHintMs.toLong().coerceAtMost(config.gapMaxMs)
            kind != EventKind.SAY -> config.sceneBeatMs
            else -> (config.sceneSayMinMs + countChars(text) * config.sceneSayMsPerChar)
                .coerceAtMost(config.sceneSayMaxMs)
        }

        return BubblePace(gapMs = gap, typingMs = typing)
    }

    /**
     * 面对面里"对方看了一会儿才开始动作"那段延迟。
     *
     * 聊天模式的 [readDelay] 是按消息长度分档的（你发得越长，对方"看得越久"），
     * 因为它模拟的是"拿起手机、读完一条消息"。面对面里**没有读消息这个动作** ——
     * 话是当场听见的，只该有一个很短的反射时间。
     */
    fun sceneReadDelay(): Long = config.sceneReadMs

    /**
     * 一条图片/表情之前的"找图"停顿（不是打字）。
     * 与上一条的间隔由 [pace] 的 mediaGap 负责，这里只管"发出前的那一小段沉默"。
     */
    fun mediaReactionMs(): Long = randomIn(config.mediaReactMinMs, config.mediaReactMaxMs)

    // ------------------------------------------------------------------

    /**
     * 按字数算的物理间隔：每个字各自随机取值再累加。
     * 这是"越长越慢"的来源，也是模型算不准、必须由引擎兜住的部分。
     */
    private fun physicalGapMs(text: String): Long {
        val chars = countChars(text)
        if (chars <= 0) return config.minMsPerChar
        var total = 0L
        repeat(chars) { total += randomIn(config.minMsPerChar, config.maxMsPerChar) }
        return total
    }

    private fun physicalTypingMs(text: String): Long {
        val chars = countChars(text)
        if (chars <= 0) return 0L
        return chars * config.typingMsPerChar
    }

    /**
     * 模型建议值与物理值的混合：偏向模型但用物理值锚住下限。
     *
     * 权重 0.6/0.4 的理由：模型对"该停顿多久"的直觉比公式准，
     * 但它对"打这么多字要多久"没有概念；物理值负责不让它给出 200ms 这种不可能的值。
     */
    private fun blend(hintMs: Long, physicalMs: Long): Long {
        val mixed = (hintMs * 6 + physicalMs * 4) / 10
        // 物理下限：不要说一句话比打字还快
        return max(mixed, max(physicalMs / 2, 250L))
    }

    /**
     * 计字数：去掉空格与换行，汉字/标点/emoji 各算 1，英文字母逐个算。
     * 与 QQ-agent 的口径一致（那边也是这么算的），便于对照调参。
     */
    private fun countChars(text: String): Int =
        text.count { !it.isWhitespace() }

    private fun randomIn(min: Long, max: Long): Long {
        if (max <= min) return min
        return min + random.nextLong(0, max - min)
    }
}

/**
 * 节奏参数。全部可调，且**都要给默认值** —— 用户不该为了用起来先配一堆数字。
 */
data class PacingConfig(
    /** 每个字最短 / 最长耗时（毫秒）。各自随机取值再累加。 */
    val minMsPerChar: Long = 700,
    val maxMsPerChar: Long = 1_100,

    /** 单条间隔的下限 / 上限。上限防止长句被压成"等了半分钟"。 */
    val gapMinMs: Long = 300,
    val gapMaxMs: Long = 20_000,

    /** "正在输入…"的每字耗时与总上限。 */
    val typingMsPerChar: Long = 110,
    val typingMaxMs: Long = 12_000,

    /** 图片 / 表情的间隔（相当于"找图"的时间）。 */
    val mediaGapMinMs: Long = 3_000,
    val mediaGapMaxMs: Long = 5_000,
    val mediaReactMinMs: Long = 500,
    val mediaReactMaxMs: Long = 1_300,

    /** 阅读延迟：按用户消息长度分档 + 随机抖动。 */
    val readShortMs: Long = 600,
    val readMediumMs: Long = 1_400,
    val readLongMs: Long = 2_600,
    val readJitterMs: Long = 700,

    /** 情绪提示的封顶：再生气也不该让人干等一分钟。 */
    val readHintMaxMs: Long = 15_000,

    // ---------------- 面对面模式（另一套节奏，见 Pacing.scenePace） ----------------

    /**
     * 动作 / 旁白之间的**固定**小拍子。
     *
     * 刻意不按字数：一个动作写三个字还是三十个字，发生的时间差不多。
     * 按字数排会让长句旁白把场景拖住十几秒 —— 真机上的观感就是"一顿一顿"。
     */
    val sceneBeatMs: Long = 700,

    /** 台词的节奏：有下限、按字数、有上限（比聊天短得多，因为没有"打字"这个动作）。 */
    val sceneSayMinMs: Long = 500,
    val sceneSayMsPerChar: Long = 60,
    val sceneSayMaxMs: Long = 3_000,

    /** 面对面里的"反射时间"：话是当场听见的，没有读消息这个动作。 */
    val sceneReadMs: Long = 350
)
