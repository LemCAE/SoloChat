package app.solochat.play

import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影层的回归测试。
 *
 * **这个文件是用户报的那个观感问题留下的。** 现象是：
 * "先一次性输出两条带时间戳的气泡，然后下面又播放第一条动画，
 *  然后第二条取代掉第一条，放完后就一起消失了。"
 *
 * 根因就在 [ChatProjector.project] 的门禁上：它只判断了 `kind.isVisible`，
 * 没判断这条事件**出口了没有**。而助手事件在"模型返回后、播出前"就以
 * `PENDING` 落盘（为了让用户的气泡不至于在等待期间消失），于是它们被当成历史
 * 画了出来；紧接着活气泡又从第一条开始播动画 —— 屏幕上就是"两条先冒出来，再被重播"。
 *
 * 为什么这个 bug 特别值得单独钉一个文件：**它在真机上抓不住**。
 * `uiautomator dump` 一次要 2.5 秒，而那一幕只存在于一两帧里（实测抓 7 帧全落空）。
 * 投影是纯函数，是唯一能在毫秒级精度上断言"屏幕上该有什么"的地方。
 */
class ChatProjectorTest {
    @Test fun `用户与角色旁白使用相同ID时仍保留不同来源`() {
        val items = ChatProjector.project(
            events = listOf(event(1, "相同环境", DeliveryState.DELIVERED, kind = EventKind.SCENE)),
            messages = listOf(userMessage(1, "（相同环境）")))
        val narrations = items.filterIsInstance<ChatItem.Narration>()
        assertEquals(2, narrations.size)
        assertEquals(setOf(app.solochat.core.model.TimelineSource.MESSAGE, app.solochat.core.model.TimelineSource.EVENT),
            narrations.map { it.source }.toSet())
    }


    private val conversationId = "c1"

    private fun event(
        id: Long,
        text: String,
        delivery: DeliveryState,
        deliveredAt: Long? = null,
        kind: EventKind = EventKind.SAY,
        createdAt: Long = 1_000L
    ) = Event(
        id = id,
        conversationId = conversationId,
        kind = kind,
        speaker = "小默",
        text = text,
        delivery = delivery,
        createdAt = createdAt,
        deliveredAt = deliveredAt
    )

    private fun userMessage(id: Long, text: String) = Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.USER,
        text = text,
        createdAt = 900L
    )

    /**
     * **还没出口的事件一条都不许画。**
     *
     * 这是那个 bug 的核心断言。注意 `createdAt = 1_000`、`deliveredAt = null` ——
     * 老代码的 `whenAt = e.deliveredAt ?: e.createdAt` 正好把这种情况伪装成
     * "有合法时间戳的历史条目"，所以光看时间戳是发现不了的，必须看 `delivery`。
     */
    @Test
    fun `PENDING 的事件不进历史`() {
        val items = ChatProjector.project(
            events = listOf(
                event(1, "还没播的第一句", DeliveryState.PENDING),
                event(2, "还没播的第二句", DeliveryState.PENDING)
            ),
            messages = listOf(userMessage(1, "在吗"))
        )

        assertEquals(
            "只有用户消息该出现，实际：${items.map { it.textOf() }}",
            listOf("在吗"),
            items.map { it.textOf() }
        )
    }

    /** 被取消的（用户在播出前插话）同样一条都不许画 —— 语义就是"没说过"。 */
    @Test
    fun `CANCELED 的事件不进历史`() {
        val items = ChatProjector.project(
            events = listOf(event(1, "被取消的话", DeliveryState.CANCELED)),
            messages = listOf(userMessage(1, "在吗"))
        )
        assertEquals(listOf("在吗"), items.map { it.textOf() })
    }

    /** 出口过的才画，且用 `deliveredAt`（交付时刻）而不是 `createdAt` 排时间轴。 */
    @Test
    fun `说过的事件用交付时间进历史`() {
        val items = ChatProjector.project(
            events = listOf(
                event(1, "玩过一点", DeliveryState.DELIVERED, deliveredAt = 2_000L),
                event(2, "就是那只蛞蝓猫吧", DeliveryState.DELIVERED, deliveredAt = 2_500L)
            ),
            messages = listOf(userMessage(1, "你玩过雨世界吗"))
        )

        assertEquals(
            listOf("你玩过雨世界吗", "玩过一点", "就是那只蛞蝓猫吧"),
            items.map { it.textOf() }
        )
        val theirs = items.filterIsInstance<ChatItem.Theirs>()
        assertEquals(
            "对方那两条的时间戳应当是**各自的交付时间**，不是同一个 createdAt",
            listOf(2_000L, 2_500L),
            theirs.map { it.at }
        )
    }

    /**
     * "说了一半"也算出口 —— 用户确实看到了那半句，所以它必须留在历史里，
     * 而且要参与上下文（否则模型以为自己没说过）。
     */
    @Test
    fun `说了一半的事件仍然进历史`() {
        val items = ChatProjector.project(
            events = listOf(event(1, "说到一半就被打断", DeliveryState.INTERRUPTED, deliveredAt = 2_000L)),
            messages = emptyList()
        )
        assertEquals(listOf("说到一半就被打断"), items.map { it.textOf() })
    }

    /**
     * `projectVisible` 要如实报出"这一份投影里包含了哪些事件"。
     *
     * 交接活气泡时靠它判断"这条是不是已经落进历史了"。它必须是**事件 id**，
     * 不能是条目 id —— `ChatItem.Mine` 的 id 是消息 id，两者混在一个集合里，
     * 一旦撞号就会把一条正在播的气泡误判成"已经落定"而整条不显示。
     */
    @Test
    fun `projectVisible 只报出口过的事件 id`() {
        val projection = ChatProjector.projectVisible(
            events = listOf(
                event(10, "说过", DeliveryState.DELIVERED, deliveredAt = 2_000L),
                event(11, "还没播", DeliveryState.PENDING)
            ),
            // 故意让消息 id 与事件 id 撞号，确保两边没有被混在一起
            messages = listOf(userMessage(10, "在吗"))
        )

        assertEquals("只该报出出口过的那条事件", setOf(10L), projection.eventIds)
        assertEquals(listOf("在吗", "说过"), projection.items.map { it.textOf() })
    }

    /** `wait` / `read` 没有正文，不该产生气泡（它们是"这一轮不回复"）。 */
    @Test
    fun `没有正文的事件不产生气泡`() {
        val items = ChatProjector.project(
            events = listOf(
                event(1, "", DeliveryState.DELIVERED, deliveredAt = 2_000L, kind = EventKind.WAIT),
                event(2, "", DeliveryState.DELIVERED, deliveredAt = 2_000L, kind = EventKind.READ)
            ),
            messages = emptyList()
        )
        assertTrue("wait / read 不该画任何东西，实际：${items.map { it.textOf() }}", items.isEmpty())
    }

    /**
     * 用户在面对面里写"我做了什么"，要画成**我的动作**，而且标记要被剥掉。
     *
     * 这是"用户侧的动作/环境"整条链路的第一段：库里的 `*把伞收起来*`
     * （见 `UserUtterance`）必须在投影层就变成正确的条目类型，
     * 否则渲染层只能看到一个带星号的普通气泡 —— 用户会以为 App 把他的字改坏了。
     */
    @Test
    fun `用户写动作 - 画成我的动作且剥掉星号`() {
        val items = ChatProjector.project(
            events = emptyList(),
            messages = listOf(userMessage(1, "*把伞收起来*"))
        )
        assertEquals(1, items.size)
        assertTrue("应当是 ChatItem.MyAction，实际：${items[0]}", items[0] is ChatItem.MyAction)
        assertEquals("把伞收起来", items[0].textOf())
    }

    /** 用户写环境 —— 环境是无人称的，所以和对方写的旁白用同一种条目。 */
    @Test
    fun `用户写环境 - 画成旁白`() {
        val items = ChatProjector.project(
            events = emptyList(),
            messages = listOf(userMessage(1, "（楼道灯闪了一下）"))
        )
        assertEquals(1, items.size)
        assertTrue("应当是 ChatItem.Narration，实际：${items[0]}", items[0] is ChatItem.Narration)
        assertEquals("楼道灯闪了一下", items[0].textOf())
    }

    /** 普通的"说话"不受影响 —— 而且正文里出现星号也不该被当成动作。 */
    @Test
    fun `用户说话还是普通气泡`() {
        val items = ChatProjector.project(
            events = emptyList(),
            messages = listOf(userMessage(1, "我明天*可能*来"))
        )
        assertTrue("应当是 ChatItem.Mine，实际：${items[0]}", items[0] is ChatItem.Mine)
        assertEquals("我明天*可能*来", items[0].textOf())
    }

    private fun ChatItem.textOf(): String = when (this) {
        is ChatItem.Mine -> text
        is ChatItem.MyAction -> text
        is ChatItem.Theirs -> text
        is ChatItem.Action -> text
        is ChatItem.Narration -> text
        is ChatItem.Divider -> text
        is ChatItem.Live -> text
    }
}
