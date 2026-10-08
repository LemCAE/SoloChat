package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户语句编解码的回归测试。
 *
 * 这层是"用户侧的动作/环境"**唯一**的分辨依据：库里存的就是带标记的纯文本
 * （助手侧本来就是这么存的，见 `UserUtterance` 的注释），所以判错的代价是
 * 界面把它画错、模型把它读错。
 *
 * 重点在**误判**那一半：正文里正常出现星号或括号时，绝不能被当成标记。
 */
class UserUtteranceTest {

    @Test
    fun `说话不加任何标记`() {
        assertEquals("把伞收起来", UserUtterance.encode(EventKind.SAY, "把伞收起来"))
    }

    @Test
    fun `动作与环境的标记和助手侧一致`() {
        assertEquals("*把伞收起来*", UserUtterance.encode(EventKind.ACT, "把伞收起来"))
        assertEquals("（楼道灯闪了一下）", UserUtterance.encode(EventKind.SCENE, "楼道灯闪了一下"))
    }

    @Test
    fun `编码时前后空白被去掉`() {
        assertEquals("*把伞收起来*", UserUtterance.encode(EventKind.ACT, "  把伞收起来  "))
        assertEquals("（楼道灯闪了一下）", UserUtterance.encode(EventKind.SCENE, "\n楼道灯闪了一下 "))
    }

    @Test
    fun `三种类型都能往返`() {
        for (kind in UserUtterance.kinds) {
            val raw = "把伞收起来"
            val (backKind, backText) = UserUtterance.decode(UserUtterance.encode(kind, raw))
            assertEquals("$kind 往返之后类型变了", kind, backKind)
            assertEquals("$kind 往返之后正文变了", raw, backText)
        }
    }

    @Test
    fun `星号在中间不算动作`() {
        val (kind, body) = UserUtterance.decode("我明天*可能*来")
        assertEquals(EventKind.SAY, kind)
        assertEquals("我明天*可能*来", body)
    }

    @Test
    fun `括号只在一头不算环境`() {
        assertEquals(EventKind.SAY, UserUtterance.decode("（笑").first)
        assertEquals(EventKind.SAY, UserUtterance.decode("笑）").first)
    }

    @Test
    fun `单个星号不算动作`() {
        val (kind, body) = UserUtterance.decode("*")
        assertEquals(EventKind.SAY, kind)
        assertEquals("正文原样保留", "*", body)
    }

    @Test
    fun `一对星号里还有星号就不当动作 - 那是正文`() {
        assertEquals(EventKind.SAY, UserUtterance.decode("*他说*算了*").first)
    }

    @Test
    fun `整条括起来的当环境`() {
        val (kind, body) = UserUtterance.decode("（笑）")
        assertEquals(EventKind.SCENE, kind)
        assertEquals("笑", body)
    }

    @Test
    fun `空正文不崩`() {
        assertEquals(EventKind.SAY, UserUtterance.decode("").first)
        assertEquals("", UserUtterance.decode("").second)
    }

    @Test
    fun `displayText 把标记剥掉`() {
        assertEquals("把伞收起来", UserUtterance.displayText("*把伞收起来*"))
        assertEquals("楼道灯闪了一下", UserUtterance.displayText("（楼道灯闪了一下）"))
        assertEquals("你怎么才来", UserUtterance.displayText("你怎么才来"))
    }
}
