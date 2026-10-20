package app.solochat.core.prompt

import app.solochat.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptReorderTest {
    private val resources = PromptResources("安全", "协议", "协议", "风格", "风格", "记忆规则", "主动")
    private fun context(history: List<Message> = emptyList()) = PromptContext(resources, Persona("p", "小默"),
        ChatMode.CHAT, now = 1700000000000, history = history,
        memories = listOf(Memory("m", content = "记忆事实")), cue = "本轮提示")

    @Test fun `记忆和台上提示只能进 user 系统消息只保留稳定前缀`() {
        val prompt = PromptBuilder.build(context())
        val system = prompt.messages[0].content
        assertEquals(prompt.stablePrefixChars, system.length)
        assertFalse(system.contains("记忆事实"))
        assertTrue(prompt.messages[1].content.contains("记忆事实"))
        assertTrue(prompt.messages[1].content.contains("本轮提示"))
        assertTrue(prompt.messages[1].content.contains("【当前时间】"))
    }
    @Test fun `新增历史不能改掉上一条用户原文尾部的字节`() {
        val first = Message(1, "c", MessageRole.USER, "较早的一句", createdAt = 1700000000000, sentToModel = true)
        val before = PromptBuilder.build(context(listOf(first))).messages[1].content
        val after = PromptBuilder.build(context(listOf(first, Message(2, "c", MessageRole.ASSISTANT, "收到")))).messages[1].content
        assertFalse(before.contains("← 对方刚发的"))
        val oldLine = before.lines().first { it.contains("较早的一句") }
        assertTrue(after.lines().contains(oldLine))
        assertTrue(after.indexOf("较早的一句") < after.indexOf("记忆事实"))
    }
}
