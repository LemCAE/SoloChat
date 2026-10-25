package app.solochat.ui.settings

import app.solochat.data.ApiProfile
import app.solochat.data.LegacyProviderPricing
import app.solochat.data.RegistryModel
import app.solochat.data.RegistryProvider
import app.solochat.data.RegistrySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2ModelSettingsDraftTest {
    @Test
    fun sameDisplayNameFromDifferentProvidersKeepsDistinctStableReferences() {
        val draft = P2ModelSettingsDraft.fromLegacyProfiles(
            profiles = listOf(
                ApiProfile("deepseek", "DeepSeek", model = "deepseek-v4-pro", modelList = listOf("deepseek-v4-pro")),
                ApiProfile("proxy", "中转站", baseUrl = "https://example.invalid/v1", model = "deepseek-v4-pro", modelList = listOf("deepseek-v4-pro"))
            ),
            activeProviderId = "deepseek",
            activeModelId = "deepseek-v4-pro"
        )

        val first = draft.document.models[0]
        val second = draft.document.models[1]
        assertEquals(first.displayName, second.displayName)
        assertTrue(first.id != second.id)
        assertTrue(first.providerId != second.providerId)

        val selected = draft.withSelection(P2SelectionTarget.CHAT, RegistrySelection.explicit(
            app.solochat.core.model.ModelReference(second.providerId, second.id)
        ))
        assertEquals(second.providerId, selected.document.chatSelection.providerId)
        assertEquals(second.id, selected.document.chatSelection.modelId)
    }

    @Test
    fun inheritedUseSelectionRemainsIndependentFromGlobalDefault() {
        val draft = P2ModelSettingsDraft.fromLegacyProfiles(emptyList(), "", "")

        val inherited = draft.withSelection(P2SelectionTarget.CHAT, RegistrySelection.inherit())

        assertEquals("INHERIT", inherited.document.chatSelection.state)
        assertEquals("NEEDS_CONFIRMATION", inherited.document.globalDefault.state)
    }

    @Test
    fun previewSaveOnlyClearsInMemoryDirtyMarker() {
        val draft = P2ModelSettingsDraft.fromLegacyProfiles(emptyList(), "", "")
        val added = draft.addProvider("示例提供商", "https://example.invalid/v1")
        assertTrue(added.dirty)
        assertEquals(1, added.document.providers.size)
        assertEquals(0, draft.document.providers.size)

        val saved = added.markPreviewSaved()
        assertFalse(saved.dirty)
        assertEquals(added.document, saved.document)
    }

    @Test fun `新增保存移除重新进入后再次新增不会复用提供商模型或凭据身份`() = kotlinx.coroutines.test.runTest {
        val oldProvider = RegistryProvider("old-provider", "旧接口", "https://old.example/v1", "old-key-slot")
        val oldModel = RegistryModel("old-model", oldProvider.id, "旧模型", "old-request")
        val initial = app.solochat.data.ModelRegistryDocument(
            providers = listOf(oldProvider), models = listOf(oldModel),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference(oldProvider.id, oldModel.id)))
        val firstDraft = P2ModelSettingsDraft.fromDocument(initial).addProvider("新接口", "https://new.example/v1")
        val firstProvider = firstDraft.document.providers.single { it.id != oldProvider.id }
        val firstWithModel = firstDraft.addModel(firstProvider.id, "新模型", "new-request")
        val firstModel = firstWithModel.document.models.single { it.providerId == firstProvider.id }
        val slots = mutableMapOf("old-key-slot" to "synthetic-old-key")
        val firstSaved = app.solochat.data.ProviderCredentialPublisher(
            writeKey = { slot, value -> slots[slot] = value }, saveDocument = {}
        ).save(initial, firstWithModel.document, emptyMap())

        val removedDraft = P2ModelSettingsDraft.fromDocument(firstSaved).removeProvider(firstProvider.id)
        val afterRemoval = app.solochat.data.ProviderCredentialPublisher(
            writeKey = { slot, value -> slots[slot] = value }, saveDocument = {}
        ).save(firstSaved, removedDraft.document, emptyMap())
        val secondDraft = P2ModelSettingsDraft.fromDocument(afterRemoval).addProvider("新接口", "https://new.example/v1")
        val secondProvider = secondDraft.document.providers.single { it.id != oldProvider.id }
        val secondWithModel = secondDraft.addModel(secondProvider.id, "新模型", "new-request")
        val secondModel = secondWithModel.document.models.single { it.providerId == secondProvider.id }

        assertNotEquals(firstProvider.id, secondProvider.id)
        assertNotEquals(firstProvider.keyId, secondProvider.keyId)
        assertNotEquals(firstModel.id, secondModel.id)
        assertNotEquals(firstProvider.id, firstProvider.keyId)
        assertTrue(firstProvider.id.startsWith("provider-"))
        assertTrue(firstProvider.keyId.startsWith("key-"))
        assertTrue(firstModel.id.startsWith("model-"))
        assertEquals("synthetic-old-key", slots["old-key-slot"])
    }

    @Test
    fun providerAndModelEditsKeepCredentialSlotPriceDataAndExactRequestId() {
        val originalProvider = RegistryProvider(
            id = "existing",
            name = "原提供商",
            baseUrl = "https://example.invalid/v1",
            keyId = "existing-key-slot",
            legacyPricing = LegacyProviderPricing(priceInput = 1.25, priceOutput = 3.5, priceConfigured = true)
        )
        val originalModel = RegistryModel(
            id = "existing-model",
            providerId = originalProvider.id,
            displayName = "旧名称",
            requestModelId = "vendor/path:model-exact",
            legacyPriceEligible = true
        )
        val original = P2ModelSettingsDraft(
            document = app.solochat.data.ModelRegistryDocument(
                providers = listOf(originalProvider),
                models = listOf(originalModel),
                globalDefault = RegistrySelection.explicit(
                    app.solochat.core.model.ModelReference(originalProvider.id, originalModel.id)
                ),
                chatSelection = RegistrySelection.inherit()
            )
        )

        val edited = original
            .withProvider(originalProvider.copy(name = "修改名称"))
            .withModel(originalModel.copy(displayName = "新名称"))

        assertEquals("existing-key-slot", edited.document.providers.single().keyId)
        assertEquals(originalProvider.legacyPricing, edited.document.providers.single().legacyPricing)
        assertEquals("vendor/path:model-exact", edited.document.models.single().requestModelId)
        assertTrue(edited.document.models.single().legacyPriceEligible)
        assertEquals("原提供商", original.document.providers.single().name)

        val changedRequest = original.withModel(originalModel.copy(requestModelId = "replacement-model"))
        assertFalse(changedRequest.document.models.single().legacyPriceEligible)
    }

    @Test fun `价格页提交的新报价合并进模型草稿且保留未保存编辑`() {
        val provider = RegistryProvider("p", "原名", "https://example.invalid", "slot")
        val model = RegistryModel("m", "p", "模型", "request")
        val original = P2ModelSettingsDraft(app.solochat.data.ModelRegistryDocument(
            providers = listOf(provider), models = listOf(model),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference("p", "m"))))
        val edited = original.withProvider(provider.copy(name = "未保存名称"))
            .withModel(model.copy(displayName = "未保存模型名"))
            .withSelection(P2SelectionTarget.CHAT, RegistrySelection.explicit(
                app.solochat.core.model.ModelReference("p", "m")))
        val price = LegacyProviderPricing(priceInput = 7.0, priceOutput = 9.0, priceConfigured = true)

        val afterPriceSave = edited.withPersistedPricing("p", price)

        assertTrue(afterPriceSave.dirty)
        assertEquals("未保存名称", afterPriceSave.document.providers.single().name)
        assertEquals("未保存模型名", afterPriceSave.document.models.single().displayName)
        assertEquals("EXPLICIT", afterPriceSave.document.chatSelection.state)
        assertEquals(price, afterPriceSave.document.providers.single().legacyPricing)
        assertEquals("原名", original.document.providers.single().name)
    }

    @Test fun `移除提供商要求先显式重选用途并保留原草稿`() {
        val first = RegistryProvider("first", "一", "https://one.example/v1", keyId = "slot-first")
        val second = RegistryProvider("second", "二", "https://two.example/v1", keyId = "slot-second")
        val firstModel = RegistryModel("m1", "first", "模型一", "model-one")
        val secondModel = RegistryModel("m2", "second", "模型二", "model-two")
        val original = P2ModelSettingsDraft(app.solochat.data.ModelRegistryDocument(
            providers = listOf(first, second), models = listOf(firstModel, secondModel),
            globalDefault = RegistrySelection.explicit(app.solochat.core.model.ModelReference("first", "m1"))))

        assertTrue(runCatching { original.removeProvider("first") }.isFailure)
        val moved = original.withSelection(P2SelectionTarget.GLOBAL,
            RegistrySelection.explicit(app.solochat.core.model.ModelReference("second", "m2")))
        val removed = moved.removeProvider("first")

        assertEquals(listOf("second"), removed.document.providers.map { it.id })
        assertEquals(listOf("m2"), removed.document.models.map { it.id })
        assertEquals("second", removed.document.globalDefault.providerId)
        assertEquals("slot-first", original.document.providers.first().keyId)
    }
}
