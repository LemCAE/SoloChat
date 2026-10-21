package app.solochat.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @Test fun `价格表集合保存重读并进入正式配置其他分组不能覆盖它`() = withStore { store ->
        val catalog = app.solochat.core.model.PriceCatalog("user", listOf(app.solochat.core.model.PriceTable("user", "用户渠道",
            listOf(app.solochat.core.model.ModelPriceEntry("my-model", app.solochat.core.model.PriceRates(0.0, 1.0))))))
        store.saveSection(SettingsSection.PRICE, store.snapshot().copy(priceCatalog = catalog))
        assertEquals(catalog, store.snapshot().priceCatalog)
        assertEquals(catalog, store.toChatConfig(store.snapshot(), "").priceCatalog)
        store.saveSection(SettingsSection.API, store.snapshot().copy(priceCatalog = null, model = "updated"))
        assertEquals(catalog, store.snapshot().priceCatalog)
        val before = store.snapshot()
        val bad = catalog.copy(tables = catalog.tables.map { it.copy(entries = it.entries.map { row -> row.copy(peakEnabled = true) }) })
        assertTrue(runCatching { store.saveSection(SettingsSection.PRICE, before.copy(priceInput = 99.0, priceCatalog = bad)) }.isFailure)
        assertEquals(before, store.snapshot())
    }
    @Test fun `显式配置零元价格和免费缓存能在重读后保留`() = withStore { store ->
        assertEquals(false, store.snapshot().priceConfigured)
        store.saveSection(SettingsSection.PRICE, store.snapshot().copy(priceConfigured = true, cachedPriceConfigured = true))
        val config = store.toChatConfig(store.snapshot(), "")
        assertEquals(true, config.priceConfigured)
        assertEquals(true, config.cachedPriceConfigured)
        assertEquals(0.0, config.priceInputPerM, 0.0)
    }
    @get:Rule val temporary = TemporaryFolder()

    private fun withStore(block: suspend (SettingsStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(temporary.newFolder(), "settings.preferences_pb")
            val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            block(store)
        } finally { scope.cancel() }
    }

    @Test fun `只保存价格 - 接口草稿和网页开关不覆盖已存配置`() = withStore { store ->
        store.setBaseUrl("https://saved.example/v1")
        store.setModel("saved-model")
        store.setLogWebEnabled(true)
        store.setLogWebPort(9876)
        val original = store.snapshot()
        store.saveSection(SettingsSection.PRICE, original.copy(baseUrl = "https://draft.example",
            model = "draft-model", logWebEnabled = false, logWebPort = 8787, priceInput = 1.5))
        val saved = store.snapshot()
        assertEquals("https://saved.example/v1", saved.baseUrl)
        assertEquals("saved-model", saved.model)
        assertTrue(saved.logWebEnabled)
        assertEquals(9876, saved.logWebPort)
        assertEquals(1.5, saved.priceInput, 0.0)
    }

    @Test fun `只保存接口 - 节奏与发送方式保持原值`() = withStore { store ->
        store.setSendMode(app.solochat.core.agent.SendMode.MANUAL)
        val param = app.solochat.core.protocol.PacingParams.all.first()
        store.setPacingParam(param, 123)
        val original = store.snapshot()
        store.saveSection(SettingsSection.API, original.copy(model = "new-model",
            sendMode = app.solochat.core.agent.SendMode.AUTO,
            pacing = app.solochat.core.protocol.PacingConfig()))
        val saved = store.snapshot()
        assertEquals("new-model", saved.model)
        assertEquals(app.solochat.core.agent.SendMode.MANUAL, saved.sendMode)
        assertEquals(123L, param.read(saved.pacing))
    }

    @Test fun `价格中含非法数字 - 整组拒绝且不先写有效部分`() = withStore { store ->
        store.setPriceInput(2.0)
        store.setPriceOutput(3.0)
        val original = store.snapshot()
        val result = runCatching { store.saveSection(SettingsSection.PRICE,
            original.copy(priceInput = 9.0, priceOutput = Double.NaN)) }
        assertTrue(result.isFailure)
        assertEquals(original, store.snapshot())
    }
}
