package app.solochat.data.pricing

import org.junit.Assert.*
import org.junit.Test

class ReferencePricesTest {
    @Test fun `参考资料含渠道来源日期与条件且不完整条件为只读`() {
        val items = ReferencePrices.sheets.flatMap { it.entries }
        assertTrue(items.any { it.requestModelId == "deepseek-flash" && it.adoptable && it.rule?.schedule?.calendar?.confirmed == false })
        assertTrue(items.any { it.requestModelId == "qwen3.7-max" && it.adoptable })
        assertTrue(items.any { it.requestModelId == "qwen3.8-max" && !it.adoptable })
        assertTrue(ReferencePrices.sheets.all { it.checkedAt.isNotBlank() && it.channelId.isNotBlank() && it.sources.isNotEmpty() })
        assertEquals(0.48, items.single { it.requestModelId == "qwen-max" }.rule!!.standard.cached!!, 0.0)
        val plus = items.single { it.requestModelId == "qwen3.7-plus-2026-05-26" }.rule!!
        assertEquals(listOf(256_000, 1_000_000), plus.tiers.map { it.upToInputTokens })
    }

    @Test fun `确认DeepSeek时段后采用会同时启用峰谷且只匹配可信主机`() {
        val item = ReferencePrices.sheets.flatMap { it.entries }.single { it.requestModelId == "deepseek-flash" }
        assertTrue(runCatching { adoptReferenceRule(item, false) }.isFailure)
        val adopted = adoptReferenceRule(item, true)
        assertTrue(adopted.peakEnabled)
        assertTrue(adopted.schedule!!.calendar!!.confirmed)
        assertFalse(referenceChannelMatches("deepseek-api", "https://api.deepseek.com.evil.example/v1"))
        assertFalse(referenceChannelMatches("deepseek-api", "https://evil.example/api.deepseek.com/v1"))
        assertFalse(referenceChannelMatches("deepseek-api", "http://api.deepseek.com/v1"))
        assertTrue(referenceChannelMatches("deepseek-api", "https://api.deepseek.com/v1"))
        assertFalse(referenceChannelMatches("aliyun-bailian", "https://other-region.aliyuncs.com/v1"))
        assertTrue(referenceChannelMatches("aliyun-bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    }
}
