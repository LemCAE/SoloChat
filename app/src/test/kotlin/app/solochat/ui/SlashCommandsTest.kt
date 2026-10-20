package app.solochat.ui

import app.solochat.ui.chat.SlashCommands
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    @Test fun `压缩命令只认开头和词边界并保留其余草稿`() {
        assertTrue(SlashCommands.suggestCompress("/"))
        assertTrue(SlashCommands.suggestCompress("/comp"))
        assertTrue(SlashCommands.isCompress("/compress 待发草稿"))
        assertEquals("待发草稿", SlashCommands.removeCompressSuggestion("/compress 待发草稿"))
        assertEquals("", SlashCommands.removeCompressSuggestion("/com"))
        assertFalse(SlashCommands.isCompress("/compressed"))
        assertFalse(SlashCommands.isCompress("正文 /compress"))
        assertFalse(SlashCommands.suggestCompress("/未知"))
    }
    @Test fun `开头输入斜杠 - 推荐系统命令，空格后进入正文`() {
        assertTrue(SlashCommands.suggestSystem("/"))
        assertTrue(SlashCommands.suggestSystem("/sys"))
        assertFalse(SlashCommands.suggestSystem("/system "))
        assertFalse(SlashCommands.suggestSystem("文字 /"))
    }
    @Test fun `完整命令与正文之间必须有边界 - 相似词不会变成系统消息`() {
        assertTrue(SlashCommands.isSystem("/system"))
        assertTrue(SlashCommands.isSystem("/system\n时间来到晚上"))
        assertEquals("时间来到晚上", SlashCommands.systemBody("/system  时间来到晚上 "))
        assertFalse(SlashCommands.isSystem("/systematic"))
        assertFalse(SlashCommands.isSystem("文字 /system"))
    }
}
