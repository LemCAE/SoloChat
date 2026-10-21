package app.solochat.data.pricing

import app.solochat.core.agent.ChatConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型价格匹配的回归测试。
 *
 * 用**真实的价格表副本**（`src/test/resources/data/model-prices.json`，与 assets 里的
 * 那份逐字节相同）而不是手搓的小样例：这样能同时发现"转换脚本漏了条目"这类问题。
 *
 * 刻意**不用 Robolectric**：它需要在测试运行时下载 android-all 构件，
 * 在离线/受限网络下必然失败（实测 `MavenDependencyResolver ... FileNotFoundException`）。
 * 所以 [`ModelPricing`] 被拆成"读 assets"和 [`PricingRules`] 纯逻辑两半，测试只覆盖后者。
 */
class PricingRulesTest {
    @Test fun `原内置表所有峰谷默认关闭保留已有峰价且标准价金额不变`() {
        val catalog = PricingRules.initialCatalog(table)
        app.solochat.core.model.PriceCatalogFormat.validate(catalog)
        assertTrue(catalog.tables.single().entries.none { it.peakEnabled })
        assertTrue(catalog.tables.single().entries.any { it.peak != null })
        table.forEach { (model, raw) ->
            fun amount(key: String) = (raw[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val current = PricingRules.resolveCatalog(catalog, model, config(), 0)
            assertEquals(amount("in"), current.inputPerM, 0.0)
            assertEquals(amount("out"), current.outputPerM, 0.0)
        }
    }
    @Test fun `切换表单隔离同名模型价格并保留自填兜底和精确匹配规则`() {
        val one = app.solochat.core.model.PriceTable("one", "一", listOf(app.solochat.core.model.ModelPriceEntry("model", app.solochat.core.model.PriceRates(1.0, 2.0))))
        val two = one.copy(id = "two", name = "二", entries = one.entries.map { it.copy(standard = app.solochat.core.model.PriceRates(5.0, 6.0)) })
        val catalog = app.solochat.core.model.PriceCatalog("one", listOf(one, two))
        assertEquals(1.0, PricingRules.resolveCatalog(catalog, "model-20261021", config(), 0).inputPerM, 0.0)
        assertEquals(5.0, PricingRules.resolveCatalog(catalog.copy(selectedTableId = "two"), "MODEL", config(), 0).inputPerM, 0.0)
        assertEquals(9.0, PricingRules.resolveCatalog(catalog, "model-pro", config(input = 9.0), 0).inputPerM, 0.0)
        assertEquals(7.0, PricingRules.resolveCatalog(catalog, "model", config(useOfficial = false, input = 7.0), 0).inputPerM, 0.0)
    }

    private val table: Map<String, kotlinx.serialization.json.JsonObject> by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("data/model-prices.json")) {
            "测试资源缺失：data/model-prices.json"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        PricingRules.parseTable(text)
    }

    private fun config(
        useOfficial: Boolean = true,
        input: Double = 0.0,
        output: Double = 0.0
    ) = ChatConfig(useOfficialPrice = useOfficial, priceInputPerM = input, priceOutputPerM = output)

    @Test
    fun `价格表被完整读进来`() {
        // 从 QQ-agent 的 model-prices.js 转出来是 140 条。少太多说明转换或解析出问题了。
        assertTrue("内置价格表条数异常：${table.size}", table.size >= 130)
    }

    @Test
    fun `精确 id 能匹配到内置价`() {
        val price = PricingRules.resolve(table, "deepseek-flash", config())
        assertTrue("应匹配到输入价", price.inputPerM > 0)
        assertTrue("应匹配到输出价", price.outputPerM > 0)
    }

    @Test
    fun `大小写与空白被归一化`() {
        val a = PricingRules.resolve(table, "deepseek-flash", config())
        val b = PricingRules.resolve(table, "  DeepSeek-Flash  ", config())
        assertEquals(a.inputPerM, b.inputPerM, 0.0001)
    }

    @Test
    fun `日期快照后缀能被剥掉重试一次`() {
        val base = PricingRules.resolve(table, "deepseek-flash", config())
        val dated = PricingRules.resolve(table, "deepseek-flash-0731", config())
        assertEquals("带日期后缀应命中同一个价", base.inputPerM, dated.inputPerM, 0.0001)
    }

    @Test
    fun `版本后缀能被剥掉`() {
        assertEquals("deepseek-flash", PricingRules.stripSnapshotSuffix("deepseek-flash-v2"))
        assertEquals("deepseek-flash", PricingRules.stripSnapshotSuffix("deepseek-flash-20250901"))
        assertEquals("deepseek-flash", PricingRules.stripSnapshotSuffix("deepseek-flash:free"))
    }

    @Test
    fun `语义后缀不该被剥 - 那是另一个模型`() {
        // -pro / -mini / -vision 指向不同能力与不同价格的模型，剥掉会套错价
        assertNull(PricingRules.stripSnapshotSuffix("deepseek-flash-pro"))
        assertNull(PricingRules.stripSnapshotSuffix("deepseek-flash-mini"))
        assertNull(PricingRules.stripSnapshotSuffix("deepseek-vl-vision"))
    }

    @Test
    fun `未知模型走自填单价而不是瞎猜`() {
        val price = PricingRules.resolve(
            table,
            "some-vendor/definitely-not-in-table-xyz",
            config(input = 3.0, output = 9.0)
        )
        assertEquals(3.0, price.inputPerM, 0.0001)
        assertEquals(9.0, price.outputPerM, 0.0001)
        assertTrue("必须让用户看出这是自填价：${price.source}", price.source.contains("自填"))
    }

    @Test
    fun `未知模型且没填单价时金额为 0 并说明原因`() {
        val price = PricingRules.resolve(table, "totally-unknown-model-abc", config())
        assertEquals(0.0, price.inputPerM, 0.0001)
        assertEquals(0.0, price.outputPerM, 0.0001)
        assertTrue("应说明为什么是 0", price.source.contains("未匹配"))
    }

    @Test
    fun `关掉内置价格表就用自填单价`() {
        val price = PricingRules.resolve(
            table,
            "deepseek-flash",
            config(useOfficial = false, input = 1.5)
        )
        assertEquals(1.5, price.inputPerM, 0.0001)
        assertTrue(price.source.contains("关闭"))
    }

    @Test
    fun `能告诉用户匹配到了哪一条`() {
        assertEquals("deepseek-flash", PricingRules.matchedId(table, "deepseek-flash"))
        assertEquals("deepseek-flash", PricingRules.matchedId(table, "deepseek-flash-0731"))
        assertNull(PricingRules.matchedId(table, "totally-unknown-model-abc"))
        assertNull(PricingRules.matchedId(table, ""))
    }

    @Test
    fun `坏 JSON 不崩 - 退化成空表`() {
        assertTrue(PricingRules.parseTable("{不是 json").isEmpty())
        assertTrue(PricingRules.parseTable("[]").isEmpty())
        assertTrue(PricingRules.parseTable("").isEmpty())
    }

    @Test
    fun `空模型名不崩`() {
        val price = PricingRules.resolve(table, "", config())
        assertEquals(0.0, price.inputPerM, 0.0001)
    }
}
