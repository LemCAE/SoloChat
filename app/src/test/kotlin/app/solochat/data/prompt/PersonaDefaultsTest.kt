package app.solochat.data.prompt

import app.solochat.core.model.DEFAULT_PERSONA_ID
import app.solochat.core.persona.PersonaDocument
import app.solochat.core.persona.toDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出厂人物卡的回归测试。
 *
 * 这些卡是**每个新用户看到的第一眼**，新会话需要明确选择 ——
 * 它们坏掉的表现不是一个崩溃，而是"这 App 说话怎么这么像客服"，
 * 而那种问题只能靠人读出来。所以这里钉的是**内容的最低要求**，不是格式。
 */
class PersonaDefaultsTest {

    private val builtins = PersonaDefaults.builtins(now = 1_000L)

    @Test
    fun `每张出厂卡都有名字、头像和身份`() {
        assertEquals(listOf(PersonaDefaults.XIAOJINGYU_ID), builtins.map { it.id })
        for (p in builtins) {
            assertTrue("${p.id} 缺名字", p.name.isNotBlank())
            assertTrue("${p.id} 缺头像", p.avatar.isNotBlank())
            assertTrue("${p.id} 缺身份（模型会不知道自己在跟谁说话）", p.identity.isNotBlank())
            assertTrue("${p.id} 缺说话方式（这一段最影响像不像真人）", p.voice.isNotBlank())
            assertTrue("${p.id} 缺边界", p.boundaries.isNotBlank())
            assertTrue("${p.id} 必须是出厂卡", p.builtin)
        }
    }

    @Test
    fun `出厂卡的 id 不重复 - 默认那张用 core 认得的哨兵`() {
        val ids = builtins.map { it.id }
        assertEquals("id 重复会让种子逻辑只补进去一张", ids.size, ids.toSet().size)
        // 旧模板保留原 id，以兼容已明确关联的历史会话。
        assertEquals(DEFAULT_PERSONA_ID, PersonaDefaults.default(1L).id)
        assertTrue(ids.contains(PersonaDefaults.XIAOJINGYU_ID))
    }

    @Test
    fun `出厂卡都示范了面对面那四段`() {
        // 不写的话用户根本不知道这几段有用 —— 而出厂卡是唯一的学习样本
        for (p in builtins) {
            assertTrue("${p.id} 没写「面对面时怎么说话」", p.sceneVoice.isNotBlank())
            assertTrue("${p.id} 没写「习惯与小动作」", p.mannerisms.isNotBlank())
            assertTrue("${p.id} 没写「它身边的样子」", p.surroundings.isNotBlank())
            assertTrue("${p.id} 没写「怎么描写动作与景物」", p.depiction.isNotBlank())
        }
    }

    @Test
    fun `小鲸鱼带着它自己的设定 - 不是一张随便的卡`() {
        val whale = PersonaDefaults.xiaojingyu(1L)
        assertEquals(PersonaDefaults.XIAOJINGYU_ID, whale.id)
        assertTrue("名字丢了", whale.name.contains("小鲸鱼"))
        assertTrue("雷点丢了（这是这张卡最容易被认出来的地方）", whale.identity.contains("大肥鱼"))
        assertTrue("偷吃 token 这个梗丢了", whale.secretNotes.contains("token"))
        assertTrue("AI 味黑名单丢了", whale.voice.contains("希望这个回答对你有帮助"))
        // 反向：群聊专属的机制**不该**被搬进一对一
        for (banned in listOf("@", "拍一拍", "send_message", "web_search", "群友", "表情包")) {
            assertFalse(
                "小鲸鱼里不该出现群聊/工具相关的东西：$banned",
                (whale.identity + whale.voice + whale.boundaries).contains(banned)
            )
        }
    }

    @Test
    fun `新安装不再自动创建小默`() {
        // 列表按 `updatedAt DESC` 排，同一毫秒会让顺序不确定
        assertFalse(builtins.any { it.id == DEFAULT_PERSONA_ID })
    }

    @Test
    fun `出厂卡导出后能原样读回来`() {
        // 这条同时保证两件事：卡片内容是一份**合法文档**；
        // 用户点「导出」拿到的文件能再导进来（不会被自己的解析器读丢字段）
        for (p in builtins) {
            val back = PersonaDocument.parse(PersonaDocument.toJson(p.toDraft()))
            assertEquals("${p.id} 导出再导入丢内容了。告警：${back.warnings}", 1, back.personas.size)
            assertEquals(p.toDraft(), back.personas.first())
        }
    }
}
