package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「这个会话现在是谁」的判定。
 *
 * 这几条看着琐碎，但它们守的是一件事：**界面显示的和模型用的是同一个人**。
 * 一旦分叉，界面上完全看不出来 —— 只有对话内容会变得莫名其妙
 * （列表上写着小默，回你的却是别人）。所以回退规则只能有一份，就是这里。
 */
class PersonaResolveTest {

    private val xiaomo = Persona(id = "default", name = "小默", builtin = true)
    private val ayan = Persona(id = "ayan", name = "阿岩")

    @Test
    fun `按 id 精确命中`() {
        val personas = listOf(xiaomo, ayan)
        assertEquals("阿岩", resolvePersona(personas, "ayan")?.name)
    }

    @Test
    fun `指向已删掉的人设时退回第一张`() {
        // 这是真实会发生的事：用户删掉了一张卡，而老对话还记得它的 id
        val personas = listOf(xiaomo, ayan)
        assertEquals("小默", resolvePersona(personas, "deleted-one")?.name)
    }

    @Test
    fun `没指定人设时退回第一张`() {
        val personas = listOf(xiaomo, ayan)
        assertEquals("小默", resolvePersona(personas, "")?.name)
        assertEquals("小默", resolvePersona(personas, null)?.name)
    }

    @Test
    fun `一张卡都没有时返回空`() {
        assertNull(resolvePersona(emptyList(), "ayan"))
        assertNull(resolvePersona(emptyList(), ""))
    }

    @Test
    fun `退回的是出厂那张 - 不随列表顺序变化`() {
        // 兜底刻意**不**取"列表里的第一张"：那样用户刚改过哪张卡，就会改变
        // 所有"没指定人设"的会话用的是谁。出厂那张一定存在，也是唯一稳定的答案。
        assertEquals("小默", resolvePersona(listOf(ayan, xiaomo), "不存在的那张")?.name)
        assertEquals("小默", resolvePersona(listOf(xiaomo, ayan), "不存在的那张")?.name)
    }

    @Test
    fun `出厂那张不在时退回第一张能用的`() {
        assertEquals("阿岩", resolvePersona(listOf(ayan), "不存在的那张")?.name)
    }

    @Test
    fun `空 id 属于没指定 - 兜底会跳过 id 为空的坏数据`() {
        val broken = Persona(id = "", name = "没有 id 的坏数据")
        // 坏数据排在第一张也不该被选成兜底：没有任何会话能引用它
        assertEquals("小默", resolvePersona(listOf(broken, xiaomo), "")?.name)
    }
}
