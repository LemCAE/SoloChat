package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 缺失的人物必须由用户选择，显示与生成共用精确匹配，不随卡片顺序换人。 */
class PersonaResolveTest {
    private val legacy = Persona(id = "default", name = "小默", builtin = true)
    private val chosen = Persona(id = "chosen", name = "测试人物")

    @Test fun `明确关联旧默认卡的会话仍使用原卡`() {
        assertEquals(legacy, resolvePersona(listOf(chosen, legacy), "default"))
        assertEquals(chosen, resolvePersona(listOf(legacy, chosen), "chosen"))
    }
    @Test fun `未选或已删除的人物不能自动换成其他卡`() {
        for (cards in listOf(listOf(legacy, chosen), listOf(chosen, legacy), listOf(chosen), emptyList())) {
            for (id in listOf(null, "", "   ", "deleted")) assertNull(resolvePersona(cards, id))
        }
    }
    @Test fun `空id的坏卡不能作为未选择会话的人物`() {
        assertNull(resolvePersona(listOf(Persona(id = "", name = "坏数据"), legacy), ""))
    }
}
