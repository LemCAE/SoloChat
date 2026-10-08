package app.solochat.core.protocol

import app.solochat.core.model.ChatMode
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史脏行的还原（见 [DegradedTurn]）。
 *
 * 这里的断言分两类，**第二类才是重点**：
 * 1. 形状对不对（拆成几条、什么类型）；
 * 2. **内容一个字符都没改** —— 每一条正文都必须能在原始 JSON 里原样找到。
 *    这条比"拆开了"重要得多：拆错类型的后果只是个头不对，改写了正文
 *    就是**篡改用户的聊天记录**。
 */
class DegradedTurnTest {

    /** 真机原文（消息 id 1002，2026-10-08 · 陆昭）。坏点是 `"typing_ms": 2500` 那个多余的冒号。 */
    private val realRaw = """
        {
          "events": [
            { "type": "scene", "text": "窗台上那两只并排的杯子被天光照着，大的一只底上留着一圈茶渍" },
            { "type": "act", "speaker": "陆昭", "text": "在床沿上坐了一会儿，没动，尾巴尖在床单上最后晃了两下就停住了" },
            { "type": "typing_ms": 2500 },
            { "type": "act", "speaker": "陆昭", "text": "起身把床头那盏灯拧到底，顺手把地上那件外套捡起来搭在椅背上" },
            { "type": "say", "speaker": "陆昭", "text": "睡吧" }
          ]
        }
    """.trimIndent()

    @Test
    fun `真机原文 - 拆开之后的每一条正文都能在原文里原样找到`() {
        val parts = DegradedTurn.split(realRaw)

        assertEquals("四段内容：scene / act / act / say，另有一段独立停顿不产生事件", 4, parts!!.size)
        assertEquals(EventKind.SCENE, parts[0].kind)
        assertEquals(EventKind.ACT, parts[1].kind)
        assertEquals(EventKind.ACT, parts[2].kind)
        assertEquals(EventKind.SAY, parts[3].kind)
        assertEquals("那个多余的冒号不该让整条丢掉", "睡吧", parts[3].text)

        for (part in parts) {
            assertTrue(
                "正文被改写了：「${part.text}」不在原文里",
                realRaw.contains(part.text)
            )
        }
    }

    @Test
    fun `停顿折进下一条 - 不产生空事件`() {
        val parts = DegradedTurn.split(realRaw)!!
        assertEquals(
            "2500ms 该落在它后面那个动作上",
            2500,
            parts[2].typingHintMs
        )
        assertTrue("不该凭空多出一条空正文的事件", parts.none { it.text.isBlank() })
    }

    @Test
    fun `说话者按落盘口径补占位 - scene 保持无人称`() {
        val raw = """{"events":[{"type":"say","text":"在的"},{"type":"scene","text":"风停了"}]}"""
        val parts = DegradedTurn.split(raw)!!
        assertEquals("没给说话者的台词补 ta（与正常落盘一致）", "ta", parts[0].resolvedSpeaker)
        assertEquals("旁白保持无人称", "", parts[1].resolvedSpeaker)
    }

    @Test
    fun `救不回来的原样留着 - 一个字符都不动`() {
        // 未转义的引号是刻意不修的：猜它该在哪结束等于替模型编内容（D11）
        val broken = """{"events":[{"type":"say","text":"他说"你好""}]}"""
        assertNull(DegradedTurn.split(broken))
    }

    @Test
    fun `不是整段 JSON 的历史行 - 不该被碰`() {
        assertFalse("人话不是那种行", DegradedTurn.looksLikeRawScript("在的，怎么了？"))
        assertNull(DegradedTurn.split("在的，怎么了？"))
        assertNull("普通的一句人话", DegradedTurn.split("（窗台上两只杯子被天光照着）"))
        assertNull("只有 JSON 的形状但没有 events", DegradedTurn.split("""{"text":"在的"}"""))
    }

    /**
     * 拆出来的东西要能直接落成事件：本地事实从被替换的那一行照抄，
     * 只有形状变。这条用真机原文再走一遍那条路径。
     */
    @Test
    fun `拆出来的能直接落成事件 - 本地事实照抄`() {
        val replaced = Event(
            id = 42,
            conversationId = "c1",
            kind = EventKind.SAY,
            speaker = "ta",
            text = realRaw,
            turnId = 7,
            mode = ChatMode.SCENE,
            delivery = DeliveryState.DELIVERED,
            gapMs = 120,
            typingMs = 340,
            createdAt = 1_700_000_000_000L,
            deliveredAt = 1_700_000_001_000L
        )

        val rebuilt = DegradedTurn.split(replaced.text)!!.mapIndexed { index, part ->
            replaced.copy(
                id = 0,
                kind = part.kind,
                speaker = part.resolvedSpeaker,
                text = part.text,
                gapMs = if (index == 0) replaced.gapMs else 0,
                typingMs = if (index == 0) replaced.typingMs else 0
            )
        }

        assertEquals(4, rebuilt.size)
        assertEquals("轮次照抄", 7L, rebuilt[1].turnId)
        assertEquals("模式照抄", ChatMode.SCENE, rebuilt[1].mode)
        assertEquals("交付状态照抄（没出口过的仍然不算说过）", DeliveryState.DELIVERED, rebuilt[1].delivery)
        assertEquals("交付时间照抄", 1_700_000_001_000L, rebuilt[1].deliveredAt)
        assertEquals("id 交回数据库分配", 0L, rebuilt[1].id)
        assertEquals("引擎字段只留在第一条", 120, rebuilt[0].gapMs)
        assertEquals(0, rebuilt[1].gapMs)
    }
}
