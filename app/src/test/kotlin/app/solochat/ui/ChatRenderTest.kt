package app.solochat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 气泡时间戳的回归测试。
 *
 * 这是用户明确要求的功能（"给每条加个时间戳"），所以不能只靠肉眼看。
 * 重点在两处边界：**没有时间信息时不要显示 1970 年**、**跨天要正确**。
 */
class ChatRenderTest {

    /** 构造一个本地时区的确定时刻，避免测试依赖运行机器的时区。 */
    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    @Test
    fun `正常时刻按 HH mm 显示`() {
        assertEquals("09:04", formatBubbleTime(at(2026, 10, 7, 9, 4)))
        assertEquals("23:59", formatBubbleTime(at(2026, 10, 7, 23, 59)))
        assertEquals("00:00", formatBubbleTime(at(2026, 10, 8, 0, 0)))
    }

    /** 0 与负值表示"没有时间信息"，必须返回空串而不是 1970 年。 */
    @Test
    fun `没有时间信息时返回空串`() {
        assertEquals("", formatBubbleTime(0))
        assertEquals("", formatBubbleTime(-1))
    }

    /** 同一分钟内的两条消息应当显示同一个时间 —— 这是"归组"能成立的前提。 */
    @Test
    fun `同一分钟内的时刻显示一致`() {
        val base = at(2026, 10, 7, 15, 23)
        assertEquals(formatBubbleTime(base), formatBubbleTime(base + 59_000))
    }

    @Test
    fun `跨天会正确进位`() {
        val before = formatBubbleTime(at(2026, 10, 7, 23, 59))
        val after = formatBubbleTime(at(2026, 10, 8, 0, 1))
        assertEquals("23:59", before)
        assertEquals("00:01", after)
        assertTrue("跨天不该显示成同一个时间", before != after)
    }

    @Test
    fun `格式固定为五位`() {
        val text = formatBubbleTime(at(2026, 1, 1, 5, 6))
        assertEquals(5, text.length)
        assertEquals("05:06", text)
    }
}
