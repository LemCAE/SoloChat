package app.solochat.core.protocol

import app.solochat.core.model.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 防抖规则的回归测试。
 *
 * 这里锁住的是"你连发三条半句，它等你说完才回"这条核心体验。
 * 用固定种子（seed = 42）保证随机项可复现，否则断言区间以外的行为会飘。
 */
class DebounceTest {

    private val rnd = Random(42)

    private fun decide(
        text: String,
        gap: Long?,
        burst: Int = 1,
        media: Boolean = false,
        window: Long = 3_500,
        waited: Long = 0
    ) = Debounce.decide(
        message = IncomingMessage(text = text, isMedia = media, at = 0),
        gapToPreviousMs = gap,
        recentBurstCount = burst,
        windowMs = window,
        totalWaitedMs = waited,
        random = rnd
    )

    @Test
    fun `第一条消息直接回`() {
        val d = decide("在吗", gap = null)
        assertEquals(DebounceTier.NEW_TURN, d.tier)
        assertTrue(d.isImmediate)
    }

    @Test
    fun `距上一条很久算新一轮`() {
        val d = decide("在吗", gap = 5 * 60_000L)
        assertEquals(DebounceTier.NEW_TURN, d.tier)
        assertTrue(d.isImmediate)
    }

    @Test
    fun `完整句 - 短等待`() {
        val d = decide("我今天去超市了。", gap = 3_000)
        assertEquals(DebounceTier.COMPLETE, d.tier)
        assertTrue("等待应落在 400-800ms，实际 ${d.waitMs}", d.waitMs in 400..800)
    }

    @Test
    fun `长消息 - 短等待`() {
        val long = "我".repeat(80)
        val d = decide(long, gap = 3_000)
        assertEquals(DebounceTier.COMPLETE, d.tier)
        assertTrue(d.waitMs in 400..800)
    }

    @Test
    fun `短句没标点 - 等很久`() {
        val d = decide("我今天", gap = 3_000)
        assertEquals(DebounceTier.UNFINISHED_SHORT, d.tier)
        assertTrue("等待应 >= 4000ms，实际 ${d.waitMs}", d.waitMs >= 4_000)
    }

    @Test
    fun `结尾是虚词 - 等更久`() {
        val plain = decide("我今天", gap = 3_000).waitMs
        val dangling = decide("我今天去超市买", gap = 3_000)
        // "买" 不在虚词表里，换一个明确在表里的
        val danglingReal = decide("我本来想说的", gap = 3_000)
        assertEquals(DebounceTier.UNFINISHED_SHORT, danglingReal.tier)
        assertTrue("结尾是「的」应该等得更久", danglingReal.waitMs > plain)
        // 同一个随机序列下，两者都不该低于下限
        assertTrue(dangling.waitMs >= 4_000)
    }

    @Test
    fun `连发分句 - 用自适应窗口`() {
        val d = decide("然后我就走了", gap = 3_000, burst = 3, window = 2_500)
        assertEquals(DebounceTier.CHUNKING, d.tier)
        assertTrue("等待应落在 2000-4000ms，实际 ${d.waitMs}", d.waitMs in 2_000..4_000)
    }

    @Test
    fun `连发但有句末标点 - 认为说完`() {
        val d = decide("然后我就走了。", gap = 3_000, burst = 3)
        assertEquals(DebounceTier.COMPLETE, d.tier)
        assertTrue(d.waitMs <= 800)
    }

    @Test
    fun `图片消息 - 按媒体档`() {
        val d = decide("", gap = 3_000, media = true)
        assertEquals(DebounceTier.MEDIA, d.tier)
        assertTrue(d.waitMs in 400..800)
    }

    @Test
    fun `硬上限 - 等太久就开工`() {
        val d = decide("我今天", gap = 3_000, waited = Debounce.HARD_CAP_MS)
        assertEquals(DebounceTier.HARD_CAP, d.tier)
        assertTrue(d.isImmediate)
    }

    @Test
    fun `边界 - 11 字且无标点走短句档`() {
        // 阈值是"短于 12 字"，所以 11 个字是最后一个走短句档的长度
        val eleven = "一二三四五六七八九十甲"
        assertEquals(11, eleven.length)
        val d = decide(eleven, gap = 3_000)
        assertEquals(DebounceTier.UNFINISHED_SHORT, d.tier)
    }

    @Test
    fun `边界 - 12 字无标点开始不走短句档`() {
        val twelve = "一二三四五六七八九十甲乙"
        assertEquals(12, twelve.length)
        val d = decide(twelve, gap = 3_000, burst = 1)
        assertTrue("12 字起不该再按短句等，实际 ${d.tier}", d.tier != DebounceTier.UNFINISHED_SHORT)
    }

    @Test
    fun `分条优先于短句 - 连发中的短消息不该被顶到短句上限`() {
        // 这是被测试抓出来的真实 bug：6 个字的"然后我就走了"在连发时
        // 曾被判成 UNFINISHED_SHORT（等 4~6 秒），而正确行为是用自适应窗口（2~4 秒）。
        val d = decide("然后我就走了", gap = 3_000, burst = 3, window = 2_500)
        assertEquals(DebounceTier.CHUNKING, d.tier)
        assertEquals(2_500, d.waitMs)
    }

    @Test
    fun `每一条判定都带人话理由`() {
        val cases = listOf(
            decide("在吗", null),
            decide("我今天去超市了。", 3_000),
            decide("我今天", 3_000),
            decide("", 3_000, media = true),
            decide("然后我就走了", 3_000, burst = 3),
            decide("我今天", 3_000, waited = Debounce.HARD_CAP_MS)
        )
        for (c in cases) {
            assertTrue("档位 ${c.tier} 的理由不该为空", c.reason.isNotBlank())
        }
    }

    @Test
    fun `空文本不崩`() {
        val d = decide("", gap = 3_000)
        assertTrue(d.reason.isNotBlank())
    }
}

/** 自适应窗口的学习测试。 */
class RhythmLearnerTest {

    @Test
    fun `样本不足时用保守默认值`() {
        assertEquals(3_500, RhythmLearner.learnedWindowMs(listOf(1_000, 2_000)))
        assertEquals(3_500, RhythmLearner.learnedWindowMs(emptyList()))
    }

    @Test
    fun `样本足够时收敛到手速`() {
        // 这个人打字很快，两条之间通常不到 1 秒
        val fast = List(20) { 700L + it * 10 }
        val fastWindow = RhythmLearner.learnedWindowMs(fast)
        assertTrue("快手的窗口应该偏小，实际 $fastWindow", fastWindow <= 1_200)

        // 这个人慢条斯理，两条之间常停 5 秒
        val slow = List(20) { 4_500L + it * 20 }
        val slowWindow = RhythmLearner.learnedWindowMs(slow)
        assertTrue("慢手的窗口应该偏大，实际 $slowWindow", slowWindow >= 4_000)
    }

    @Test
    fun `窗口被夹在合理区间内`() {
        // 注意样本数：取的是 75 分位（`(n-1)*0.75`），所以
        // - 上限要 40 个样本才能把分位推到 8000 以上（39*0.75=29，1ms 样本 → 仍取 1）
        // - 下限则是任何样本都够不到，因为 600 本来就是分位结果的下限
        // 第一版这里只给了 20 个样本，于是上限那条断言永远为真 —— 测试等于没测。
        val fast = List(40) { 1L }
        assertTrue(
            "超快样本的窗口应被夹到下限 600，实际 ${RhythmLearner.learnedWindowMs(fast)}",
            RhythmLearner.learnedWindowMs(fast) in 600..1_000
        )

        // 上界样本必须 ≤ 60 秒（超过会被当成"新一轮"过滤掉），但要大于 8000，
        // 这样才真正走到夹取那一步：40 个 20000ms 样本 → 75 分位 = 20000 → 夹到 8000
        val slow = List(40) { 20_000L }
        assertEquals(
            "超慢样本的窗口应被夹到上限 8000",
            8_000,
            RhythmLearner.learnedWindowMs(slow)
        )
    }

    @Test
    fun `从历史消息里统计间隔`() {
        val stamps = listOf(0L, 2_000L, 4_500L, 20_000L)
        val samples = RhythmLearner.observe(stamps, listOf(false, false, false, false))
        assertEquals(listOf(2_000L, 2_500L, 15_500L), samples)
    }

    @Test
    fun `媒体消息的间隔不参与学习`() {
        val stamps = listOf(0L, 1_000L, 3_000L)
        val media = listOf(false, true, false)
        // 两条间隔里只要有一头是媒体，就不代表"打字节奏"，一律排除
        val samples = RhythmLearner.observe(stamps, media)
        assertTrue("媒体间隔不该进样本，实际 $samples", samples.isEmpty())
    }

    @Test
    fun `全是文字时媒体过滤不影响结果`() {
        val stamps = listOf(0L, 1_000L, 3_000L)
        val samples = RhythmLearner.observe(stamps, listOf(false, false, false))
        assertEquals(listOf(1_000L, 2_000L), samples)
    }

    @Test
    fun `超长停顿不算同一次连发`() {
        val stamps = listOf(0L, 5 * 60_000L)
        assertTrue(RhythmLearner.observe(stamps, listOf(false, false)).isEmpty())
    }
}

/** 播出节奏的回归测试。 */
class PacingTest {

    private val pacing = Pacing(random = Random(7))

    @Test
    fun `越长越慢 - 长句的间隔大于短句`() {
        val short = pacing.pace("嗯", gapHintMs = null)
        val long = pacing.pace("我今天下午去了一趟超市买了不少东西", gapHintMs = null)
        assertTrue("长句间隔 ${long.gapMs} 应大于短句 ${short.gapMs}", long.gapMs > short.gapMs)
    }

    @Test
    fun `间隔有下限 - 不会瞬间刷屏`() {
        val p = pacing.pace("嗯")
        assertTrue("间隔 ${p.gapMs} 不该低于下限", p.gapMs >= PacingConfig().gapMinMs)
    }

    @Test
    fun `间隔有上限 - 不会让人干等`() {
        val text = "字".repeat(200)
        val p = pacing.pace(text, gapHintMs = 60_000)
        assertTrue("间隔 ${p.gapMs} 不该超过上限", p.gapMs <= PacingConfig().gapMaxMs)
    }

    @Test
    fun `模型建议值与物理值混合 - 不照抄极端建议`() {
        // 模型要 6 秒，但这句话只有两个字：物理值会把它拉回来
        val p = pacing.pace("嗯嗯", gapHintMs = 6_000)
        val pure = pacing.pace("嗯嗯", gapHintMs = null)
        assertTrue("混合后应大于纯物理值", p.gapMs > pure.gapMs)
        assertTrue("但不该照抄 6000ms", p.gapMs < 6_000)
    }

    @Test
    fun `打了又删的提示被尊重`() {
        val p = pacing.pace("嗯", typingHintMs = 5_000)
        assertEquals(5_000, p.typingMs)
    }

    @Test
    fun `打字时长按字数`() {
        val short = pacing.pace("嗯").typingMs
        val long = pacing.pace("我今天下午去了一趟超市").typingMs
        assertTrue(long > short)
    }

    @Test
    fun `媒体不按字数排间隔`() {
        val p = pacing.pace("", isMedia = true)
        val cfg = PacingConfig()
        assertTrue(p.gapMs in cfg.mediaGapMinMs..cfg.mediaGapMaxMs)
        assertEquals(0L, p.typingMs)
    }

    @Test
    fun `阅读延迟按用户消息长度分档`() {
        val short = pacing.readDelay(5)
        val long = pacing.readDelay(200)
        assertTrue("长消息应看得更久：${short.ms} vs ${long.ms}", long.ms > short.ms)
    }

    @Test
    fun `情绪提示被采纳但封顶`() {
        val angry = pacing.readDelay(10, moodHintMs = 60_000)
        assertTrue(angry.ms <= PacingConfig().readHintMaxMs)
        assertTrue(angry.reason.contains("气氛"))
    }

    @Test
    fun `同一句话多次算出的间隔不是同一个值 - 不死板`() {
        // 注意：这里**不能**断言"两次一定不同"（随机撞上同一个值完全可能，
        // 那会让测试变成偶发失败）。正确的不变式是"结果不是一个常数"。
        val results = List(12) { pacing.pace("今天天气不错").gapMs }
        assertTrue("间隔取值应有变化，实际 $results", results.distinct().size >= 2)
    }

    // ---------------- 面对面：另一套节奏（见 Pacing.scenePace） ----------------

    /**
     * **动作与旁白忽略字数** —— 这是"面对面一顿一顿"的根治点。
     *
     * 一个动作写三个字还是三十个字，发生的时间差不多；按字数排会让长句旁白
     * 把场景拖住十几秒。所以这里必须**相等**，不能只是"差不多"。
     */
    @Test
    fun `面对面 - 动作与旁白忽略字数`() {
        val short = pacing.pace("嗯", sceneKind = EventKind.ACT).gapMs
        val long = pacing.pace("把两只手揣进袖子里然后把脖子缩了一缩，往台阶上挪了半步", sceneKind = EventKind.ACT).gapMs
        assertEquals("动作不该按字数伸缩", short, long)
        assertEquals("动作/旁白用固定的小拍子", PacingConfig().sceneBeatMs, short)

        val scene = pacing.pace("楼道口的灯忽明忽暗，风顺着台阶一级一级灌下来，把落叶推到墙角", sceneKind = EventKind.SCENE).gapMs
        assertEquals("旁白也走同一个固定拍子", PacingConfig().sceneBeatMs, scene)
    }

    /** 台词按字数，但**比聊天短得多**：没有"打字"这个动作，张口就来。 */
    @Test
    fun `面对面 - 台词按字数但比聊天短`() {
        val text = "你怎么才来啊我都等半天了"
        val scene = pacing.pace(text, sceneKind = EventKind.SAY).gapMs
        val chat = pacing.pace(text).gapMs
        assertTrue("面对面台词（$scene）应当比聊天（$chat）短", scene < chat)
        // 长短仍然有区别（只是幅度小）
        val longer = pacing.pace(text + text, sceneKind = EventKind.SAY).gapMs
        assertTrue("长台词还是要比短的久一点：$scene vs $longer", longer >= scene)
        assertTrue("但不能无上限地拖", longer <= PacingConfig().sceneSayMaxMs)
    }

    /** 面对面**不算打字时间** —— "正在输入…"这个暗示在同一个空间里不存在。 */
    @Test
    fun `面对面 - 不算打字时间`() {
        assertEquals(0L, pacing.pace("把伞收起来", sceneKind = EventKind.ACT).typingMs)
        assertEquals(0L, pacing.pace("楼道灯闪了一下", sceneKind = EventKind.SCENE).typingMs)
        assertEquals(0L, pacing.pace("你怎么才来", sceneKind = EventKind.SAY).typingMs)
    }

    /** 但模型显式给的 `typing_ms` 要留着 —— 那是它主动要的"欲言又止"。 */
    @Test
    fun `面对面 - 模型给的留白仍然生效`() {
        val p = pacing.pace("……", typingHintMs = 2_500, sceneKind = EventKind.SAY)
        assertEquals(2_500L, p.typingMs)
    }

    /** 模型显式给的 `gap_ms` 一律尊重（面对面协议里虽然让它别给，给了就是有意为之）。 */
    @Test
    fun `面对面 - 模型给的间隔优先于固定拍子`() {
        val p = pacing.pace("把伞收起来", gapHintMs = 1_200, sceneKind = EventKind.ACT)
        assertEquals(1_200L, p.gapMs)
    }

    /** 面对面的"看了一会儿"退化成反射时间，不再按字数分档。 */
    @Test
    fun `面对面 - 阅读延迟不按字数`() {
        assertEquals(PacingConfig().sceneReadMs, pacing.sceneReadDelay())
        assertTrue("应当明显短于聊天模式的下限", pacing.sceneReadDelay() < PacingConfig().readShortMs)
    }
}
