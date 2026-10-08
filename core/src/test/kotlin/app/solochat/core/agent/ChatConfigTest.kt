package app.solochat.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史注入条数的语义。
 *
 * 为什么这件小事值得单独一个测试文件：`0` 和 `-1` 在 SQLite 里是
 * **"一条都不要"** 和 **"全都要"**，只差一个字面量，效果却是整个上下文。
 * 而它错掉的时候没有任何症状 —— 界面正常、日志正常，只是模型突然失忆。
 *
 * 约定：设置里的 0 / 负数 = 不限，翻成 `LIMIT -1` 交给存储层。
 */
class ChatConfigTest {

    @Test
    fun `默认就是不限 - 不是 0 条`() {
        val config = ChatConfig()
        assertEquals("默认该是不限", 0, config.historyLimit)
        assertEquals("要翻成 LIMIT -1，绝不能把 0 直接交下去", -1, config.historyWindow)
    }

    @Test
    fun `显式条数原样透传`() {
        assertEquals(40, ChatConfig(historyLimit = 40).historyWindow)
        assertEquals(1, ChatConfig(historyLimit = 1).historyWindow)
        assertEquals(100_000, ChatConfig(historyLimit = 100_000).historyWindow)
    }

    @Test
    fun `负数也当不限 - 不出现负数进 SQL`() {
        assertEquals(-1, ChatConfig(historyLimit = -5).historyWindow)
    }

    /**
     * 这一条钉的是最容易写错的那一处：`LIMIT 0` 是空上下文。
     * 如果哪天有人把 `historyWindow` 改成 `historyLimit.coerceAtLeast(0)`，
     * 这里会红。
     */
    @Test
    fun `不限绝不能翻成 0`() {
        val window = ChatConfig().historyWindow
        assertEquals(ChatConfig.UNLIMITED, window)
        assertEquals("LIMIT 0 = 一条都不要，那不是'不限'", false, window == 0)
    }
}
