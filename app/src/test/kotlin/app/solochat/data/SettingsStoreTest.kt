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
import app.solochat.core.model.ModelPriceEntry
import app.solochat.core.model.PriceCatalog
import app.solochat.core.model.PriceRates
import app.solochat.core.model.PriceTable

class SettingsStoreTest {
    @Test fun `v2文档保存重读不改变旧运行配置且价格Key槽位原样保留`() = withStore { store ->
        store.setModel("legacy-case-sensitive")
        store.migrateApiProfiles { }
        val old = store.snapshot()
        val catalog = PriceCatalog("inactive", listOf(
            PriceTable("active", "未选中的历史表", listOf(ModelPriceEntry(
                "legacy-case-sensitive", standard = PriceRates(1.25, 2.5, 0.5),
                peak = PriceRates(3.0, 4.0), peakEnabled = false, note = "历史备注"
            ))),
            PriceTable("inactive", "当前选择", emptyList())
        ))
        val model = RegistryModel("model-stable", old.apiProfileId, "同名显示", "legacy-case-sensitive", legacyPriceEligible = true)
        val secondModel = RegistryModel("model-second", old.apiProfileId, "同名显示", "Other-Case", legacyPriceEligible = true)
        val newModel = RegistryModel("model-new", old.apiProfileId, "新模型", "new-model")
        val document = ModelRegistryDocument(
            providers = listOf(RegistryProvider(old.apiProfileId, old.apiProfileName, old.baseUrl, old.apiKeyId,
                legacyPricing = LegacyProviderPricing(false, catalog, 1.0, 2.0, 0.0, true, false))),
            models = listOf(model, secondModel, newModel),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference(old.apiProfileId, model.id)),
            chatSelection = RegistrySelection.explicit(app.solochat.core.model.ModelReference(old.apiProfileId, secondModel.id)),
            compressionSelection = RegistrySelection.explicit(app.solochat.core.model.ModelReference(old.apiProfileId, model.id)),
            titleSelection = RegistrySelection.explicit(app.solochat.core.model.ModelReference(old.apiProfileId, secondModel.id))
        )

        val encoded = ModelRegistryFormat.encode(document)
        assertTrue(!encoded.contains("FAKE-SECRET-KEY-MUST-NOT-BE-SAVED"))
        store.saveModelRegistryV2(document)

        assertEquals(document, store.loadModelRegistryV2())
        assertEquals(old, store.snapshot())
        assertEquals(old.apiKeyId, store.loadModelRegistryV2()!!.providers.single().keyId)
        assertEquals(catalog, store.loadModelRegistryV2()!!.providers.single().legacyPricing.priceCatalog)
        assertEquals(false, store.loadModelRegistryV2()!!.models.single { it.id == newModel.id }.legacyPriceEligible)
        assertEquals(app.solochat.core.model.ModelSelection.Explicit(
            app.solochat.core.model.ModelReference(old.apiProfileId, secondModel.id)
        ), store.loadModelRegistryV2()!!.toCoreConfiguration().chatSelection)
    }

    @Test fun `v2未知版本重复ID及无效文档拒绝且旧偏好不变`() = withStore { store ->
        store.setModel("legacy-model")
        val old = store.snapshot()
        val valid = ModelRegistryDocument(
            providers = listOf(RegistryProvider("provider-a", "A", "https://a.example", "slot-a")),
            models = listOf(RegistryModel("model-a", "provider-a", "A", "model-a")),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference("provider-a", "model-a"))
        )

        assertTrue(runCatching { store.saveModelRegistryV2(valid.copy(version = 99)) }.isFailure)
        assertTrue(runCatching { store.saveModelRegistryV2(valid.copy(models = valid.models + valid.models)) }.isFailure)
        assertEquals(null, store.loadModelRegistryV2())
        assertEquals(old, store.snapshot())
    }

    @Test fun `用户编辑只允许READY文档并与升级冻结写入隔离`() = withStore { store ->
        store.migrateApiProfiles { }
        val source = store.freezeModelConfigUpgrade(emptyList(), newId = { "unused-id" }, newPlanId = { "plan-ready-test" })
        val candidate = requireNotNull(source.frozenPlan).candidate
        assertTrue(runCatching { store.saveReadyModelRegistryV2(candidate) }.isFailure)
        assertEquals(null, store.loadModelRegistryV2())

        store.markModelConfigRoomAppended(requireNotNull(source.planId))
        store.publishModelConfigReady(requireNotNull(source.planId), candidate)
        val edited = candidate.copy(providers = candidate.providers.map { it.copy(name = "用户已保存") })
        store.saveReadyModelRegistryV2(edited)

        assertEquals(edited, store.loadModelRegistryV2())
        assertEquals(source.copy(phase = "READY"), store.loadModelConfigUpgradePreparation())
    }

    @Test fun `旧v2模型缺少方言字段仍可读且手动方言可往返`() {
        val document = ModelRegistryDocument(
            providers = listOf(RegistryProvider("provider-a", "A", "https://a.example/v1", "slot-a")),
            models = listOf(RegistryModel("model-a", "provider-a", "A", "custom-model")),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference("provider-a", "model-a"))
        )
        val oldV2 = ModelRegistryFormat.encode(document).replace(",\"thinkingDialectOverride\":null", "")
        assertEquals(null, ModelRegistryFormat.decode(oldV2).models.single().thinkingDialectOverride)

        val manual = document.copy(models = listOf(document.models.single().copy(
            thinkingControl = app.solochat.core.model.ThinkingControl.ENABLE_AND_DISABLE.name,
            capabilitySource = app.solochat.core.model.CapabilitySource.USER_CONFIRMED.name,
            thinkingDialectOverride = app.solochat.core.model.ThinkingParameterDialect.OPENAI_REASONING_EFFORT.name
        )))
        assertEquals(manual, ModelRegistryFormat.decode(ModelRegistryFormat.encode(manual)))
    }

    @Test fun `升级准备记录持久复用ID并在旧源变化后生成新版本`() = withStore { store ->
        store.setModel("default-model")
        store.setModelList(listOf("default-model", "directory-model"))
        store.migrateApiProfiles { }
        var generated = 0
        val conversations = listOf(LegacyConversationModelOverride("conversation-a", "hand-typed-model"))
        val first = store.prepareModelConfigUpgrade(conversations) { "prepared-${++generated}" }
        val repeated = store.prepareModelConfigUpgrade(conversations) { error("同源重试不得重建 ID") }

        assertEquals(first, repeated)
        assertEquals(3, generated)
        store.saveSection(SettingsSection.API, store.snapshot().copy(model = "changed-default"))
        val refreshed = store.prepareModelConfigUpgrade(conversations) { "refreshed-${++generated}" }
        assertTrue(first.sourceRevision != refreshed.sourceRevision)
        assertTrue(first.modelIds != refreshed.modelIds)
        assertEquals(7, generated)
    }

    @Test fun `官方模板仅预填地址自定义仍为空且均不带入模型价格或改变当前组`() = withStore { store ->
        store.migrateApiProfiles { }
        val current = store.snapshot().apiProfileId
        ApiProfileTemplate.entries.forEach { template ->
            val displayName = when (template) {
                ApiProfileTemplate.DEEPSEEK -> "DeepSeek 官方"
                ApiProfileTemplate.DASHSCOPE -> "阿里云百炼"
                ApiProfileTemplate.CUSTOM -> "自定义接口"
            }
            val id = store.addApiProfile(displayName, template)
            val snapshot = store.snapshot()
            assertEquals(current, snapshot.apiProfileId)
            val profile = snapshot.apiProfiles.first { it.id == id }
            assertEquals(template.baseUrl, profile.baseUrl)
            assertTrue(profile.model.isEmpty() && profile.modelList.isEmpty())
            assertTrue(profile.priceCatalog!!.tables.single().entries.isEmpty())
            assertTrue(!profile.priceConfigured)
        }
    }

    @Test fun `导入价格表只保存当前接口组另组目录与价格保持原值`() = withStore { store ->
        store.migrateApiProfiles { }
        val old = store.snapshot()
        val id = store.addApiProfile("价格导入测试")
        store.selectApiProfile(id)
        val current = store.snapshot()
        val document = app.solochat.core.model.PriceTableDocument(1, "CNY", "per_million_tokens", "导入表",
            listOf(app.solochat.core.model.ModelPriceEntry("import-model", app.solochat.core.model.PriceRates(2.0, 3.0))))
        val catalog = app.solochat.core.model.PriceTableTransfer.append(current.priceCatalog!!, document)
        store.saveSection(SettingsSection.PRICE, current.copy(priceCatalog = catalog))
        assertEquals(catalog, store.snapshot().priceCatalog)
        store.selectApiProfile(old.apiProfileId)
        assertEquals(old.copy(apiProfiles = store.snapshot().apiProfiles), store.snapshot())
    }

    @Test fun `新密钥先写独立槽位元数据失败仍读取原地址原槽位`() = withStore { store ->
        store.setBaseUrl("https://old.example")
        store.migrateApiProfiles { }
        val old = store.snapshot()
        val keys = mutableMapOf(old.apiKeyId to "old-test-key")
        val draft = old.copy(baseUrl = "https://new.example", apiKeyId = "new-slot")
        assertTrue(runCatching {
            store.saveSection(SettingsSection.API, draft) {
                keys[draft.apiKeyId] = "new-test-key"
                error("模拟发布前中断")
            }
        }.isFailure)
        assertEquals(old, store.snapshot())
        assertEquals("old-test-key", keys[store.snapshot().apiKeyId])
        store.saveSection(SettingsSection.API, draft)
        assertEquals("https://new.example", store.snapshot().baseUrl)
        assertEquals("new-test-key", keys[store.snapshot().apiKeyId])
    }

    @Test fun `旧接口模型目录和价格只迁入首组且失败可重试`() = withStore { store ->
        store.setBaseUrl("https://legacy.example/v1")
        store.setModel("legacy-model")
        store.setModelList(listOf("legacy-model", "other"))
        store.saveSection(SettingsSection.PRICE, store.snapshot().copy(priceInput = 8.0, priceConfigured = true))
        val before = store.snapshot()
        assertTrue(runCatching { store.migrateApiProfiles { error("模拟密钥复制失败") } }.isFailure)
        assertEquals(before, store.snapshot())
        var copies = 0
        store.migrateApiProfiles { copies++ }
        store.migrateApiProfiles { error("不得重复迁移") }
        val after = store.snapshot()
        assertEquals(before.copy(apiProfiles = after.apiProfiles), after)
        assertEquals(1, copies)
        assertEquals(1, after.apiProfiles.size)
    }

    @Test fun `切换隔离默认模型目录价格删除当前组不回退旧键`() = withStore { store ->
        store.setBaseUrl("https://legacy.example")
        store.setModel("old")
        store.migrateApiProfiles { }
        val old = store.snapshot()
        val id = store.addApiProfile("新接口")
        assertEquals(old.apiProfileId, store.snapshot().apiProfileId)
        store.selectApiProfile(id)
        val empty = store.snapshot()
        assertEquals("", empty.baseUrl)
        assertEquals("", empty.model)
        assertTrue(empty.modelList.isEmpty())
        assertTrue(empty.priceCatalog!!.tables.single().entries.isEmpty())
        assertTrue(!empty.priceConfigured)
        store.saveSection(SettingsSection.API, empty.copy(baseUrl = "https://new.example", model = "new"))
        store.saveSection(SettingsSection.PRICE, store.snapshot().copy(priceInput = 9.0, priceConfigured = true))
        store.selectApiProfile(old.apiProfileId)
        assertEquals("old", store.snapshot().model)
        assertEquals(old.priceInput, store.snapshot().priceInput, 0.0)
        store.selectApiProfile(id)
        assertEquals(9.0, store.snapshot().priceInput, 0.0)
        store.deleteApiProfile(id)
        assertEquals("", store.snapshot().apiProfileId)
        assertEquals("", store.snapshot().baseUrl)
        store.migrateApiProfiles { error("删除不能复活旧配置") }
        assertEquals("", store.snapshot().apiProfileId)
    }

    @Test fun `旧页面保存不能覆盖新组非法地址不先写密钥`() = withStore { store ->
        store.migrateApiProfiles { }
        val old = store.snapshot()
        val id = store.addApiProfile("新接口")
        store.selectApiProfile(id)
        var keyWritten = false
        assertTrue(runCatching { store.saveSection(SettingsSection.API, old) { keyWritten = true } }.isFailure)
        assertTrue(!keyWritten)
        val current = store.snapshot()
        for (url in listOf("https://bad host", "https://user:pass@example.com", "https://example.com?key=test")) {
            assertTrue(runCatching { store.saveSection(SettingsSection.API, current.copy(baseUrl = url)) { keyWritten = true } }.isFailure)
        }
        assertTrue(!keyWritten)
        assertEquals(current, store.snapshot())
        assertTrue(runCatching { store.saveSection(SettingsSection.PRICE, old) }.isFailure)
    }

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
