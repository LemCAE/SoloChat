package app.solochat.core.playback

import app.solochat.core.model.ChatMode
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.protocol.Pacing
import app.solochat.core.protocol.PacingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
/**
 * 播放引擎的回归测试。
 *
 * 重点全在**状态序列**和**中断语义**上，因为这两处错了症状很隐蔽：
 * 说过的当没说过（模型失忆）、没说的当说过（模型幻觉自己说过的话）、
 * 或者消息重复冒出来。这些都是"看一眼没问题，用两天才觉得不对劲"的 bug。
 */
class PlayerTest {

    private val pacing = Pacing(random = Random(11))

    private fun event(
        id: Long,
        kind: EventKind = EventKind.SAY,
        text: String = "嗯",
        turnId: Long = 1000,
        speaker: String = "ta",
        gapHintMs: Int? = null,
        typingHintMs: Int? = null,
        mode: ChatMode = ChatMode.CHAT
    ) = Event(
        id = id,
        conversationId = "c1",
        kind = kind,
        speaker = speaker,
        text = text,
        turnId = turnId,
        mode = mode,
        gapHintMs = gapHintMs,
        typingHintMs = typingHintMs,
        delivery = DeliveryState.PENDING,
        createdAt = 0
    )

    @Test
    fun `三条消息产生阅读加三条气泡`() {
        val events = listOf(event(1, text = "在的"), event(2, text = "怎么了"), event(3, text = "在忙吗"))
        val plan = Player.plan(events, pacing, readDelayMs = 800)

        assertEquals(Player.State.Reading(1), plan.states.first().state)
        val delivering = plan.states.filterIsInstance<SequenceStep>()
            .map { it.state }
            .filterIsInstance<Player.State.Delivering>()
        assertEquals(3, delivering.size)
        assertEquals("在的", delivering[0].status.bubble)
        assertEquals("怎么了", delivering[1].status.bubble)
        assertEquals("在忙吗", delivering[2].status.bubble)
    }

    @Test
    fun `第一条之前不叠加额外间隔`() {
        val events = listOf(event(1), event(2))
        val plan = Player.plan(events, pacing, readDelayMs = 800)
        val firstGapIndex = plan.states.indexOfFirst { it.state is Player.State.Gap }
        val firstDeliverIndex = plan.states.indexOfFirst { it.state is Player.State.Delivering }
        // 第一个间隔必须出现在第一条气泡之后，否则对方会"愣两次"
        assertTrue("间隔不该早于第一条气泡", firstGapIndex > firstDeliverIndex)
    }

    @Test
    fun `阅读延迟为 0 时没有 Reading 状态`() {
        val plan = Player.plan(listOf(event(1)), pacing, readDelayMs = 0)
        assertTrue(plan.states.none { it.state is Player.State.Reading })
    }

    @Test
    fun `有三条消息就有两个间隔`() {
        val events = listOf(event(1), event(2), event(3))
        val plan = Player.plan(events, pacing, readDelayMs = 0)
        val gaps = plan.states.count { it.state is Player.State.Gap }
        assertEquals(2, gaps)
    }

    @Test
    fun `typing 提示会先出正在输入再出气泡`() {
        val events = listOf(event(1, text = "好久没见了", typingHintMs = 3000))
        val plan = Player.plan(events, pacing, readDelayMs = 0)
        val typingIndex = plan.states.indexOfFirst { it.state is Player.State.Typing }
        val deliverIndex = plan.states.indexOfFirst { it.state is Player.State.Delivering }
        assertTrue("Typing 应在 Delivering 之前", typingIndex in 0 until deliverIndex)
        assertEquals(3000L, plan.states[typingIndex].waitMs)
    }

    @Test
    fun `typing 时长被上限夹住`() {
        val events = listOf(event(1, typingHintMs = 60_000))
        val plan = Player.plan(events, pacing, readDelayMs = 0)
        val typing = plan.states.first { it.state is Player.State.Typing }
        assertTrue("typing 不该超过上限", typing.waitMs <= PacingConfig().typingMaxMs)
    }

    @Test
    fun `纯 wait 不演出也不产生气泡`() {
        val events = listOf(event(1, kind = EventKind.WAIT, text = ""))
        val plan = Player.plan(events, pacing, readDelayMs = 500)
        assertTrue("wait 不该有任何播出状态", plan.states.isEmpty())
        assertTrue(plan.outcome.delivered.isEmpty())
    }

    @Test
    fun `remember 不播出`() {
        val events = listOf(
            event(1, text = "对方在准备考研"),
            event(2, text = "好的")
        ).mapIndexed { i, e -> if (i == 0) e.copy(kind = EventKind.REMEMBER) else e }
        val plan = Player.plan(events, pacing, readDelayMs = 0)
        val delivering = plan.states.map { it.state }
            .filterIsInstance<Player.State.Delivering>()
        assertEquals(1, delivering.size)
        assertEquals("好的", delivering[0].status.bubble)
    }

    @Test
    fun `scene 与 act 也会播出但带类型标记`() {
        val events = listOf(
            event(1, kind = EventKind.SCENE, text = "傍晚的风有点凉", speaker = ""),
            event(2, kind = EventKind.ACT, text = "把杯子磕在桌上")
        )
        val plan = Player.plan(events, pacing, readDelayMs = 0)
        val delivering = plan.states.map { it.state }
            .filterIsInstance<Player.State.Delivering>()
        assertEquals(2, delivering.size)
        assertEquals(EventKind.SCENE, delivering[0].status.kind)
        assertEquals(EventKind.ACT, delivering[1].status.kind)
    }

    @Test
    fun `同一轮的事件共享 turnId - 界面据此归组`() {
        val events = listOf(event(1, turnId = 77), event(2, turnId = 77))
        val plan = Player.plan(events, pacing, readDelayMs = 0)
        val delivering = plan.states.map { it.state }
            .filterIsInstance<Player.State.Delivering>()
        assertTrue(delivering.all { it.status.turnId == 77L })
    }

    // ---- 中断矩阵：在每一个可能的位置打断，语义都不能错 ----

    @Test
    fun `中断 - 一条都没播出时全部取消`() {
        val events = listOf(event(1), event(2), event(3))
        val plan = Player.plan(events, pacing, readDelayMs = 0).outcome
        val cut = Player.interrupt(plan, playedCount = 0, playingIndex = null)

        assertTrue("没播的不算说过", cut.delivered.isEmpty())
        assertNull(cut.interrupted)
        assertEquals(3, cut.canceled.size)
    }

    @Test
    fun `中断 - 播出一条后被打断`() {
        val events = listOf(event(1, text = "我"), event(2, text = "想"), event(3, text = "说"))
        val plan = Player.plan(events, pacing, readDelayMs = 0).outcome
        val cut = Player.interrupt(plan, playedCount = 1, playingIndex = null)

        assertEquals(1, cut.delivered.size)
        assertEquals("我", cut.delivered[0].text)
        assertEquals(2, cut.canceled.size)
        assertNull(cut.interrupted)
    }

    @Test
    fun `中断 - 正好在第二条打字时被打断`() {
        val events = listOf(event(1, text = "我"), event(2, text = "想"), event(3, text = "说"))
        val plan = Player.plan(events, pacing, readDelayMs = 0).outcome
        // playedCount=1（第一条已完整播出），playingIndex=1（第二条正在打）
        val cut = Player.interrupt(plan, playedCount = 1, playingIndex = 1)

        assertEquals(1, cut.delivered.size)
        assertEquals("我", cut.delivered[0].text)
        assertEquals("想", cut.interrupted?.text)
        assertEquals(1, cut.canceled.size)
        assertEquals("说", cut.canceled[0].text)
    }

    @Test
    fun `中断 - 全部播完后被打断等于没打断`() {
        val events = listOf(event(1), event(2))
        val plan = Player.plan(events, pacing, readDelayMs = 0).outcome
        val cut = Player.interrupt(plan, playedCount = 2, playingIndex = null)
        assertEquals(2, cut.delivered.size)
        assertTrue(cut.canceled.isEmpty())
        assertNull(cut.interrupted)
    }

    @Test
    fun `中断 - playingIndex 越界不崩`() {
        val events = listOf(event(1))
        val plan = Player.plan(events, pacing, readDelayMs = 0).outcome
        val cut = Player.interrupt(plan, playedCount = 0, playingIndex = 5)
        assertNull(cut.interrupted)
        assertTrue(cut.canceled.isEmpty())
    }

    @Test
    fun `三次消息的间隔合起来不会太久`() {
        val events = listOf(
            event(1, text = "在吗", gapHintMs = 400),
            event(2, text = "那个", gapHintMs = 900),
            event(3, text = "算了", gapHintMs = 700)
        )
        val plan = Player.plan(events, pacing, readDelayMs = 500)
        val totalWait = plan.states.sumOf { it.waitMs }
        // 一轮三条 + 阅读延迟，整体等待不该超过 20 秒（否则用户会以为卡死）
        assertTrue("整轮等待 $totalWait ms 太久了", totalWait < 20_000)
    }

    /**
     * **面对面不排"正在输入"，两条之间的间隔也不按字数伸缩。**
     *
     * 这条守的是两条接线的正确性（`Player.plan` 必须把事件类型递下去）：
     * 规则写在 `Pacing.scenePace` 里、也有它自己的测试，但只要这一处忘了传
     * `sceneKind`，面对面就会**静默退回**聊天模式那套节奏 —— 而那种退化的症状
     * 只是"场景读起来一顿一顿"，不会有任何报错。所以这里钉住"接线通没通"。
     */
    @Test
    fun `面对面 - 不排正在输入且间隔不按字数`() {
        val events = listOf(
            event(1, kind = EventKind.SCENE, text = "楼道口的灯忽明忽暗", mode = ChatMode.SCENE),
            event(2, kind = EventKind.ACT, text = "嗯", mode = ChatMode.SCENE),
            event(3, kind = EventKind.ACT, text = "把手揣进袖子里然后把脖子缩了一缩", mode = ChatMode.SCENE)
        )
        val plan = Player.plan(events, pacing, readDelayMs = 350)

        val kinds = plan.states.map { it.state::class.simpleName }
        assertTrue("面对面不该出现打字状态，实际：$kinds", kinds.none { it == "Typing" })

        val gaps = plan.states
            .filter { it.state is Player.State.Gap }
            .map { it.waitMs }
        assertEquals("三条之间应当有两个间隔", 2, gaps.size)
        assertEquals("动作/旁白忽略字数，两个间隔必须一样：$gaps", 1, gaps.distinct().size)
    }

    /** 同一批事件放在聊天模式里，行为必须**完全不变**（回归保护）。 */
    @Test
    fun `聊天模式仍然排打字与按字数的间隔`() {
        val events = listOf(
            event(1, text = "在吗"),
            event(2, text = "那个")
        )
        val plan = Player.plan(events, pacing, readDelayMs = 500)
        val kinds = plan.states.map { it.state::class.simpleName }
        assertTrue("聊天模式该有打字状态，实际：$kinds", kinds.any { it == "Typing" })
    }
}
