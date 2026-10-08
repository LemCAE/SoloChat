package app.solochat.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 节奏参数表的回归测试。
 *
 * 这张表是"设置页 / 持久化 / `PacingConfig`"三者的**唯一**映射来源，而它有一个
 * 特别隐蔽的失败模式：**加了字段却忘了进表** —— 那个参数永远是默认值，
 * 用户在设置里怎么改都没用，而且不报任何错。所以这里用反射把两边对上。
 */
class PacingParamsTest {

    @Test
    fun `表覆盖了 PacingConfig 的每一个字段`() {
        val fields = PacingConfig::class.java.declaredFields
            .count { Modifier.isFinal(it.modifiers) && !it.isSynthetic }
        assertEquals(
            "PacingConfig 加了字段却没进 PacingParams 表 —— 那个参数会永远是默认值。" +
                "表里 ${PacingParams.all.size} 个，字段 $fields 个",
            fields,
            PacingParams.all.size
        )
    }

    @Test
    fun `表里的默认值必须和 PacingConfig 的默认值一致`() {
        val defaults = PacingConfig()
        for (p in PacingParams.all) {
            assertEquals(
                "「${p.label}」(${p.key}) 的默认值和 PacingConfig 对不上 ——" +
                    "设置页会显示一个程序并不使用的数字",
                p.default,
                p.read(defaults)
            )
        }
    }

    @Test
    fun `每个参数写进去再读出来都不变`() {
        for (p in PacingParams.all) {
            val written = p.write(PacingConfig(), p.default)
            assertEquals("${p.key} 读写不闭合", p.default, p.read(written))
        }
    }

    @Test
    fun `写一个参数不会误伤别的`() {
        val base = PacingConfig()
        for (p in PacingParams.all) {
            val written = p.write(base, p.default)
            for (other in PacingParams.all) {
                if (other.key == p.key) continue
                assertEquals(
                    "写 ${p.key} 的时候把 ${other.key} 也动了",
                    other.read(base),
                    other.read(written)
                )
            }
        }
    }

    @Test
    fun `键名唯一 - 撞了就会互相覆盖`() {
        val keys = PacingParams.all.map { it.key }
        assertEquals("键名撞了：$keys", keys.size, keys.distinct().size)
    }

    @Test
    fun `上下限合理 - 默认值落在区间内`() {
        for (p in PacingParams.all) {
            assertTrue("${p.key} 的 min > max", p.min <= p.max)
            assertTrue(
                "${p.key} 的默认值 ${p.default} 不在 [${p.min}, ${p.max}] 里",
                p.default in p.min..p.max
            )
        }
    }

    @Test
    fun `每个参数都有名字和说明 - 设置页要显示它们`() {
        for (p in PacingParams.all) {
            assertTrue("${p.key} 没有 label，设置页上会是一片空白", p.label.isNotBlank())
            assertTrue("${p.key} 没有 hint，用户不知道这个数字在管什么", p.hint.isNotBlank())
            assertTrue("${p.key} 没有分组，设置页会漏掉它", p.group.isNotBlank())
        }
    }

    @Test
    fun `分组不漏 - 每个参数都归到了某个分组`() {
        val grouped = PacingParams.grouped().flatMap { it.second }
        assertEquals(PacingParams.all.size, grouped.size)
    }
}
