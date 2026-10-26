package app.solochat.ui.settings

import app.solochat.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedPriceEditorDraftTest {
    @Test fun `进入高级编辑时顶部第一档草稿先写入规则且其余档位保留`() {
        val second = PriceTier(null, PriceRates(7.0, 8.0, null), PriceRates(9.0, 10.0, 0.0))
        val schedule = PriceSchedule(listOf(PriceTimeRange(60, 120)), weekdays = listOf(1, 2))
        val original = AdvancedPriceRules(schedule, listOf(
            PriceTier(100, PriceRates(1.0, 2.0, null), PriceRates(3.0, 4.0, null)), second))

        val entered = withTopTierDraft(original, PriceRates(5.0, 6.0, 0.0), PriceRates(7.0, 8.0, null))

        assertEquals(PriceRates(5.0, 6.0, 0.0), entered.tiers.first().standard)
        assertEquals(PriceRates(7.0, 8.0, null), entered.tiers.first().peak)
        assertEquals(second, entered.tiers.last())
        assertEquals(schedule, entered.schedule)
    }
}
