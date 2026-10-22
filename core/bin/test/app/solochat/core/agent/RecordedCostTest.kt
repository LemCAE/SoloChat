package app.solochat.core.agent

import app.solochat.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RecordedCostTest {
    private fun call(price: ModelPrice = ModelPrice(1.0, 2.0, 0.1)) = CallRecord("id", conversationId = "c",
        model = "m", startedAt = 0, price = price, inputCharacters = 100,
        input = TokenAmount(1000, TokenSource.RETURNED), output = TokenAmount(100, TokenSource.RETURNED))
    @Test fun `缓存未知且有优惠应显示范围而非假装全部未命中`() {
        val cost = recordedCost(call())
        assertEquals(0.0003, cost.low!!, 0.0000001)
        assertEquals(0.0012, cost.high!!, 0.0000001)
        assertTrue(cost.approximate)
    }
    @Test fun `已知零价与没有配置价格必须区分`() {
        assertNull(recordedCost(call(ModelPrice())).low)
        assertEquals(0.0, recordedCost(call(ModelPrice(known = true))).low!!, 0.0)
    }
    @Test fun `推理是输出子项不能重复收钱缓存也是输入子项`() {
        val cost = recordedCost(call().copy(cached = TokenAmount(500, TokenSource.RETURNED),
            reasoning = TokenAmount(80, TokenSource.RETURNED)))
        assertEquals(0.00075, cost.low!!, 0.0000001)
        assertFalse(cost.approximate)
    }
    @Test fun `缺失输出的费用未知旧版记录不拿今天单价补造`() {
        val c = call().copy(output = TokenAmount())
        assertNull(recordedCost(c).low)
        val totals = summarizeCalls(listOf(c), listOf(Usage(100, 20)))
        assertEquals(2, totals.calls)
        assertEquals(2, totals.missingCost)
        assertEquals(1, totals.missingOutput)
        assertEquals(1, totals.legacyCalls)
    }
    @Test fun `异币种不混入人民币合计`() {
        assertNull(recordedCost(call(ModelPrice(1.0, 2.0, currency = "USD"))).low)
    }
    @Test fun `超过旧两十万上限的真实装配仍完整统计`() {
        val short = Estimate.promptTokens(listOf(app.solochat.core.llm.ApiMessage("user", "短")))
        val long = Estimate.promptTokens(listOf(app.solochat.core.llm.ApiMessage("user", "长".repeat(250000))))
        assertTrue(long > 250000)
        assertTrue(long > short)
    }
}
