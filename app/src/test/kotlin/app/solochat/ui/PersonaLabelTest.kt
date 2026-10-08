package app.solochat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「这个对话在用谁」的显示文本。
 *
 * 会话列表和聊天标题栏都调这一个函数，所以它是两处显示**必然一致**的保证。
 * 用例不多，但每一条都对应一种真实组合：有头像 / 没头像 / 没有名字 / 兜底。
 */
class PersonaLabelTest {

    @Test
    fun `头像与名字之间是一个空格`() {
        assertEquals("🌙 小默", personaLabel("🌙", "小默", fallback = false))
    }

    @Test
    fun `没有头像时只显示名字 - 不留前导空格`() {
        assertEquals("小默", personaLabel("", "小默", fallback = false))
        assertEquals("小默", personaLabel("   ", "小默", fallback = false))
    }

    @Test
    fun `兜底的人设要标出来`() {
        // 会话自己没指定人设（或指向一张已删掉的卡）时用的是退回的那张。
        // 不标出来，用户会以为"这个对话用的是我当初选的那个人"。
        assertEquals("🌙 小默（默认）", personaLabel("🌙", "小默", fallback = true))
    }

    @Test
    fun `什么都没有时返回空串 - 让调用方自己决定怎么拼`() {
        assertEquals("", personaLabel("", "", fallback = false))
        assertEquals("", personaLabel("", "", fallback = true))
    }
}
