package app.solochat.core.protocol

import app.solochat.core.model.ChatMode
import app.solochat.core.model.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本解析的回归测试。
 *
 * 铁律是"永远不抛出异常，永远有可用结果"，所以这里一半用例是**各种坏输入**：
 * 被截断的 JSON、模型自创的事件类型、正文为空、模型干脆无视协议写人话。
 * 每一条都对应一种真实会遇到的失败。
 */
class ScriptParserTest {

    @Test
    fun `空输出等于主动不说话`() {
        for (raw in listOf(null, "", "   ", "\n")) {
            val script = ScriptParser.parse(raw)
            assertTrue("raw=$raw 应为空脚本", script.isEmpty)
            assertFalse("不该是降级", script.degraded)
        }
    }

    @Test
    fun `模型无视协议直接写人话 - 降级成一条消息`() {
        val script = ScriptParser.parse("在的，怎么了？")
        assertTrue(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals(EventKind.SAY, script.events[0].kind)
        assertEquals("在的，怎么了？", script.events[0].text)
    }

    @Test
    fun `最小可用脚本 - 一条消息`() {
        val script = ScriptParser.parse("""{"events":[{"type":"say","text":"在的"}]}""")
        assertFalse(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals("在的", script.events[0].text)
    }

    @Test
    fun `多条消息各自独立 - 不合并成长文本`() {
        val script = ScriptParser.parse(
            """
            {"events":[
              {"type":"say","text":"？","gap_ms":1200},
              {"type":"say","text":"怎么了？","gap_ms":900,"typing_ms":600}
            ]}
            """.trimIndent()
        )
        assertEquals(2, script.events.size)
        assertEquals("？", script.events[0].text)
        assertEquals(1200, script.events[0].gapHintMs)
        assertEquals("怎么了？", script.events[1].text)
        assertEquals(900, script.events[1].gapHintMs)
        assertEquals(600, script.events[1].typingHintMs)
    }

    @Test
    fun `围栏包裹也能解析`() {
        val script = ScriptParser.parse(
            "```json\n{\"events\":[{\"type\":\"say\",\"text\":\"嗯\"}]}\n```"
        )
        assertEquals(1, script.events.size)
        assertEquals("嗯", script.events[0].text)
    }

    @Test
    fun `没有 language 标记的围栏`() {
        val script = ScriptParser.parse("```\n{\"events\":[{\"type\":\"say\",\"text\":\"嗯\"}]}\n```")
        assertEquals("嗯", script.events[0].text)
    }

    @Test
    fun `围栏被截断 - 至少不崩`() {
        val script = ScriptParser.parse("```json\n{\"events\":[{\"type\":\"say\",\"text\":\"嗯\"}]}")
        assertEquals(1, script.events.size)
    }

    @Test
    fun `前后有废话 - 只取最外层对象`() {
        val script = ScriptParser.parse(
            "好的，这是我的回复：\n{\"events\":[{\"type\":\"say\",\"text\":\"在的\"}]}\n希望你喜欢"
        )
        assertEquals(1, script.events.size)
        assertEquals("在的", script.events[0].text)
    }

    @Test
    fun `顶层直接是数组`() {
        val script = ScriptParser.parse("""[{"type":"say","text":"嗯"},{"type":"say","text":"好"}]""")
        assertEquals(2, script.events.size)
        assertNull(script.nextMode)
    }

    @Test
    fun `正文里含右花括号也不截断`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"这个集合是 {a, b}"}]}"""
        )
        assertEquals("这个集合是 {a, b}", script.events[0].text)
    }

    @Test
    fun `被截断的 JSON - 不播出半截`() {
        // 模型输出还没写完就断了：绝不能把半截内容当消息发出去
        val script = ScriptParser.parse("""{"events":[{"type":"say","text":"我在想""")
        assertTrue("截断的 JSON 不该解析出正常事件", script.degraded || script.isEmpty)
    }

    @Test
    fun `未知事件类型被丢弃 - 其余照常`() {
        val script = ScriptParser.parse(
            """
            {"events":[
              {"type":"say","text":"在的"},
              {"type":"emoji","text":"😂"},
              {"type":"say","text":"嗯"}
            ]}
            """.trimIndent()
        )
        assertEquals(2, script.events.size)
        assertEquals(1, script.dropped.size)
        assertTrue(script.dropped[0].contains("emoji"))
    }

    @Test
    fun `全部类型未知 - 回退成一条消息而不是空白`() {
        val script = ScriptParser.parse("""{"events":[{"type":"emoji","text":"😂"}]}""")
        assertTrue(script.degraded)
        assertEquals(1, script.events.size)
        assertTrue(script.events[0].text.contains("emoji"))
    }

    @Test
    fun `正文为空的事件被丢弃`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"  "},{"type":"say","text":"好"}]}"""
        )
        assertEquals(1, script.events.size)
        assertEquals("好", script.events[0].text)
    }

    @Test
    fun `wait 允许没有正文`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"wait","reason":"对方看起来还没说完"}]}"""
        )
        assertEquals(1, script.events.size)
        assertEquals(EventKind.WAIT, script.events[0].kind)
        assertEquals("对方看起来还没说完", script.events[0].waitReason)
    }

    @Test
    fun `合法但空的事件数组等于不说话`() {
        val script = ScriptParser.parse("""{"events":[]}""")
        assertTrue(script.isEmpty)
        assertFalse(script.degraded)
    }

    @Test
    fun `scene 是无人称的 - 带 speaker 会被抹掉而不是报错`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"scene","speaker":"她","text":"傍晚的风有点凉"}]}"""
        )
        assertEquals("", script.events[0].speaker)
        assertEquals("傍晚的风有点凉", script.events[0].text)
    }

    @Test
    fun `say 与 act 保留 speaker`() {
        val script = ScriptParser.parse(
            """
            {"events":[
              {"type":"act","speaker":"她","text":"把头发别到耳后"},
              {"type":"say","speaker":"她","text":"你来了"}
            ]}
            """.trimIndent()
        )
        assertEquals("她", script.events[0].speaker)
        assertEquals(EventKind.ACT, script.events[0].kind)
        assertEquals("她", script.events[1].speaker)
    }

    @Test
    fun `模式提议能被读出`() {
        val script = ScriptParser.parse(
            """
            {"events":[{"type":"say","text":"你到了？"}],
             "next_mode":"scene","mode_reason":"聊到见面了"}
            """.trimIndent()
        )
        assertEquals(ChatMode.SCENE, script.nextMode)
        assertEquals("聊到见面了", script.modeReason)
    }

    @Test
    fun `未知的模式值当不表态 - 不瞎切`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"嗯"}],"next_mode":"galgame"}"""
        )
        assertNull(script.nextMode)
    }

    @Test
    fun `chat 模式值也认`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"嗯"}],"next_mode":"chat"}"""
        )
        assertEquals(ChatMode.CHAT, script.nextMode)
    }

    @Test
    fun `离谱的间隔值被夹到区间内`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"嗯","gap_ms":500000,"typing_ms":-100}]}"""
        )
        assertEquals(8000, script.events[0].gapHintMs)
        assertEquals(0, script.events[0].typingHintMs)
    }

    @Test
    fun `数字写成字符串也认`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"嗯","gap_ms":"1200"}]}"""
        )
        assertEquals(1200, script.events[0].gapHintMs)
    }

    @Test
    fun `别名字段 - content 与 messages 与 msg 类型`() {
        val script = ScriptParser.parse(
            """{"messages":[{"type":"msg","content":"在的"}]}"""
        )
        assertEquals(1, script.events.size)
        assertEquals(EventKind.SAY, script.events[0].kind)
        assertEquals("在的", script.events[0].text)
    }

    @Test
    fun `简写形式 text 字段`() {
        val script = ScriptParser.parse("""{"text":"在的"}""")
        assertEquals(1, script.events.size)
        assertEquals("在的", script.events[0].text)
    }

    // ------------------------------------------------------------------
    // 一层"逐条抢救"（真机故障：整轮输出变成一屏原始 JSON）
    //
    // 下面两条是**设备上真实的模型输出**，来自同一个会话的两次降级（消息 id 999 / 1002）。
    // 两次的坏点都是同一个：`{ "type": "typing_ms": 2500 }` —— 值后面多一个冒号，
    // 于是整份 JSON 语法作废，其余 8 条好事件跟着一起被降级成一条纯文本消息。
    // 断言里的条数、留白时长都是照着这两份原文数出来的，不是估的。
    // ------------------------------------------------------------------

    @Test
    fun `真机故障 - typing_ms 被写成一条事件，不该把整轮变成一屏 JSON`() {
        val raw = """
            {
              "events": [
                { "type": "scene", "text": "窗台上那两只并排的杯子被天光照着，大的一只底上留着一圈茶渍" },
                { "type": "act", "speaker": "陆昭", "text": "在床沿上坐了一会儿，没动，尾巴尖在床单上最后晃了两下就停住了" },
                { "type": "typing_ms": 2500 },
                { "type": "act", "speaker": "陆昭", "text": "起身把床头那盏灯拧到底，顺手把地上那件外套捡起来搭在椅背上" },
                { "type": "say", "speaker": "陆昭", "text": "睡吧" },
                { "type": "act", "speaker": "陆昭", "text": "俯身把被角往肩头那边又掖了掖，手掌在他后颈那圈短毛上按了一下就收回来" },
                { "type": "act", "speaker": "陆昭", "text": "光脚踩过地板，走到门口，把门带上只留一条缝" },
                { "type": "typing_ms": 1800 },
                { "type": "say", "speaker": "陆昭", "text": "我去楼下转一圈，中午就回来" }
              ]
            }
        """.trimIndent()

        val script = ScriptParser.parse(raw)

        assertFalse("整轮不该降级", script.degraded)
        assertEquals("两条停顿之外还有 7 条", 7, script.events.size)
        assertEquals("scene", script.events[0].kind.wire)
        assertEquals("2500ms 的停顿要折进紧随其后的那个动作", 2500, script.events[2].typingHintMs)
        assertEquals("1800ms 的停顿要折进最后那句台词", 1800, script.events[6].typingHintMs)
        assertEquals("我去楼下转一圈，中午就回来", script.events[6].text)
    }

    @Test
    fun `真机故障 - 顶层多一个 remember 数组也不影响事件`() {
        val raw = """
            {
              "events": [
                { "type": "scene", "text": "窗帘缝里那道白光淡下去了，窗外不知哪家在收衣服，晾衣杆磕在铁架子上响了一声" },
                { "type": "act", "speaker": "陆昭", "text": "抬手把被角往上掖了掖，一直压到他肩头那圈毛上" },
                { "type": "say", "speaker": "陆昭", "text": "有事是有事，站里那点活一个电话的事" },
                { "type": "say", "speaker": "陆昭", "text": "下午那趟外勤我让别人替我，晚上回来再补" },
                { "type": "act", "speaker": "陆昭", "text": "坐到床沿上，手撑在身后，尾巴从被面上绕过来搭到自己膝盖上" },
                { "type": "say", "speaker": "陆昭", "text": "反正也不是第一次了——这话你说的，我可记着了啊" },
                { "type": "typing_ms": 1800 },
                { "type": "say", "speaker": "陆昭", "text": "躺你的，别看我了，眼睛都快睁不开了还在那儿跟我客气" },
                { "type": "say", "speaker": "陆昭", "text": "中午吃什么，说一个，我好下楼去买" }
              ],
              "remember": [
                { "text": "对方不愿因亲密耽误陆昭的正事，会主动催他出门", "tags": ["习惯"] }
              ]
            }
        """.trimIndent()

        val script = ScriptParser.parse(raw)

        assertFalse("整轮不该降级", script.degraded)
        assertEquals(8, script.events.size)
        assertEquals(1800, script.events[6].typingHintMs)
    }

    @Test
    fun `停顿写成合法 JSON 的类型也认 - 折进下一条的留白`() {
        // 模型把 typing_ms 当成一种事件类型，但这次 JSON 语法是对的
        val script = ScriptParser.parse(
            """{"events":[{"type":"typing_ms","ms":1200},{"type":"say","text":"嗯"}]}"""
        )
        assertFalse(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals(1200, script.events[0].typingHintMs)
        assertTrue(
            "改造过的东西也要留下痕迹",
            script.dropped.any { it.contains("停顿") }
        )
    }

    @Test
    fun `停顿写成一整个带 gap_ms 类型的对象 - 折进下一条的间隔`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"gap_ms","ms":900},{"type":"say","text":"嗯"}]}"""
        )
        assertFalse(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals(900, script.events[0].gapHintMs)
    }

    @Test
    fun `事件自己写的时长优先 - 不被前面那段独立停顿覆盖`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"typing_ms","ms":1200},{"type":"say","text":"嗯","typing_ms":300}]}"""
        )
        assertEquals(300, script.events[0].typingHintMs)
    }

    @Test
    fun `整轮只有一段停顿 - 既不该降级，也不该拿原始 JSON 占一个气泡`() {
        val script = ScriptParser.parse("""{"events":[{"type":"pause","ms":900}]}""")
        assertTrue(script.isEmpty)
        assertFalse("降级才会把 JSON 发出去", script.degraded)
    }

    @Test
    fun `中间一条烂了 - 保住两边`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"一"},{"type":"say","text":},{"type":"say","text":"二"}]}"""
        )
        assertFalse("一条烂不该连累整轮", script.degraded)
        assertEquals(2, script.events.size)
        assertEquals("一", script.events[0].text)
        assertEquals("二", script.events[1].text)
        assertTrue("要说明跳过了一条", script.dropped.any { it.contains("跳过") })
    }

    @Test
    fun `对象之间少逗号 - 大括号配对本身就能分开`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"一"}{"type":"say","text":"二"}]}"""
        )
        assertFalse(script.degraded)
        assertEquals(2, script.events.size)
    }

    @Test
    fun `字段之间少逗号 - 修补层补回来`() {
        val script = ScriptParser.parse("""{"events":[{"type":"say" "text":"嗯"}]}""")
        assertFalse(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals("嗯", script.events[0].text)
    }

    @Test
    fun `数组里混进一句人话 - 好的照常`() {
        // 注：这一条其实第一层（lenient）就吃得下 —— 它守的是"别倒退"，不是抢救层
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"一"},嗯这个我不确定,{"type":"say","text":"二"}]}"""
        )
        assertFalse(script.degraded)
        assertEquals(2, script.events.size)
    }

    @Test
    fun `外层大括号没闭合 - 里面完整的条仍然要播出`() {
        // 模型把最后的收尾写丢了，但两条事件本身都是完整的
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"一"},{"type":"say","text":"二"}"""
        )
        assertFalse(script.degraded)
        assertEquals(2, script.events.size)
    }

    @Test
    fun `弯引号 - 键和值都被当成引号用`() {
        val script = ScriptParser.parse("""{“events”:[{“type”:“say”,“text”:“嗯”}]}""")
        assertFalse(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals("嗯", script.events[0].text)
    }

    @Test
    fun `正文里的中文引号是内容 - 不能被当成语法`() {
        // 这一条本来就是合法 JSON，走的是第一层：正文里的弯引号必须原样留下
        val script = ScriptParser.parse("""{"events":[{"type":"say","text":"他说“嗯”"}]}""")
        assertEquals("他说“嗯”", script.events[0].text)
    }

    @Test
    fun `单引号其实不认 - 交给修补`() {
        // `isLenient` 只放开了"不带引号的键和值"，单引号一直是解析失败的
        val script = ScriptParser.parse("""{'events':[{'type':'say','text':'嗯'}]}""")
        assertFalse(script.degraded)
        assertEquals("嗯", script.events[0].text)
    }

    @Test
    fun `全角冒号与全角逗号`() {
        val script = ScriptParser.parse("""{"events":[{"type"："say"，"text"："嗯"}]}""")
        assertFalse(script.degraded)
        assertEquals("嗯", script.events[0].text)
    }

    @Test
    fun `截断时保住已经完整的那几条 - 半截的那条仍然不播`() {
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"一"},{"type":"say","text":"我在想"""
        )
        assertFalse(script.degraded)
        assertEquals(1, script.events.size)
        assertEquals("一", script.events[0].text)
    }

    @Test
    fun `未转义的引号救不回来 - 但不能猜，猜等于替模型编内容`() {
        // 这是刻意留的边界：`"text":"他说"你好""` 要救只能猜引号该在哪结束，
        // 猜错就会凭空造出正文。宁可降级，让人看见它坏了。
        val script = ScriptParser.parse(
            """{"events":[{"type":"say","text":"他说"你好""}]}"""
        )
        assertTrue(script.degraded)
    }

    @Test
    fun `正文里带真换行 - lenient 本来就认`() {
        // 这条守着"别把已经能用的东西改坏"：模型的台词里常常真有换行
        val script = ScriptParser.parse("{\"events\":[{\"type\":\"say\",\"text\":\"第一行\n第二行\"}]}")
        assertFalse(script.degraded)
        assertEquals("第一行\n第二行", script.events[0].text)
    }

    @Test
    fun `永远不抛异常 - 各种坏输入`() {
        val bad = listOf(
            "{", "}", "[]", "[1,2,3]", "{\"events\":\"不是数组\"}",
            "{\"events\":[1,2]}", "{\"events\":[null]}",
            "```json", "data: {", "\u0000�"
        )
        for (raw in bad) {
            val script = ScriptParser.parse(raw)   // 不抛就算过
            // 结果必须是可用状态：要么有事件，要么明确为空
            assertTrue("raw=$raw", script.events.isNotEmpty() || script.isEmpty)
        }
    }
}
