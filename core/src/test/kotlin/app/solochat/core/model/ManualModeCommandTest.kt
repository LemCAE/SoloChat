package app.solochat.core.model

import org.junit.Assert.*
import org.junit.Test

class ManualModeCommandTest {
    @Test fun `明确的斜杠与系统切换命令直接解析为目标模式`() {
        assertEquals(ChatMode.SCENE, ManualModeCommand.parse("/scene"))
        assertEquals(ChatMode.SCENE, ManualModeCommand.parse("/system 进入面对面模式"))
        assertEquals(ChatMode.CHAT, ManualModeCommand.parse("/chat"))
        assertEquals(ChatMode.CHAT, ManualModeCommand.parse("/system chat"))
    }
    @Test fun `系统事实提到模式或相似前缀不触发切换`() {
        assertNull(ManualModeCommand.parse("/system 不要进入面对面模式"))
        assertNull(ManualModeCommand.parse("/system 今天我们面对面上课"))
        assertNull(ManualModeCommand.parse("/systematic scene"))
        assertNull(ManualModeCommand.parse("/scene 只是一个词"))
        assertNull(ManualModeCommand.parse("普通消息 /scene"))
    }
}
