package app.solochat.data

import app.solochat.core.model.ModelReference
import app.solochat.core.model.CredentialAvailability
import app.solochat.core.agent.ChatConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialPublisherTest {
    private fun document(keyId: String, name: String = "代理") = ModelRegistryDocument(
        providers = listOf(RegistryProvider("provider", name, "https://example.invalid/v1", keyId)),
        models = listOf(RegistryModel("model", "provider", "模型", "request-model")),
        globalDefault = RegistrySelection.explicit(ModelReference("provider", "model"))
    )

    @Test fun `替换凭据先写新槽位再发布指针且保留原槽位`() = kotlinx.coroutines.test.runTest {
        val slots = mutableMapOf("old-slot" to "synthetic-old-key")
        var published: ModelRegistryDocument? = null
        val result = ProviderCredentialPublisher(
            writeKey = { slot, value -> slots[slot] = value },
            saveDocument = { published = it },
            newSlotId = { "new-slot" }
        ).save(document("old-slot"), document("ignored"), mapOf("provider" to "synthetic-new-key"))

        assertEquals("synthetic-old-key", slots["old-slot"])
        assertEquals("synthetic-new-key", slots["new-slot"])
        assertEquals("new-slot", result.providers.single().keyId)
        assertEquals(result, published)
    }

    @Test fun `文档发布失败时旧槽位未变且不清理新槽位`() = kotlinx.coroutines.test.runTest {
        val slots = mutableMapOf("old-slot" to "synthetic-old-key")
        val result = runCatching {
            ProviderCredentialPublisher(
                writeKey = { slot, value -> slots[slot] = value },
                saveDocument = { error("模拟 DataStore 发布失败") },
                newSlotId = { "new-slot" }
            ).save(document("old-slot"), document("ignored"), mapOf("provider" to "synthetic-new-key"))
        }

        assertTrue(result.isFailure)
        assertEquals("synthetic-old-key", slots["old-slot"])
        assertEquals("synthetic-new-key", slots["new-slot"])
        assertNotEquals("new-slot", document("old-slot").providers.single().keyId)
    }

    @Test fun `清空Key发布到新空槽位并保留旧Key`() = kotlinx.coroutines.test.runTest {
        val slots = mutableMapOf("old-slot" to "synthetic-old-key")
        val result = ProviderCredentialPublisher(
            writeKey = { slot, value -> slots[slot] = value },
            saveDocument = {},
            newSlotId = { "empty-slot" }
        ).save(document("old-slot"), document("ignored"), mapOf("provider" to null))

        assertEquals("old-slot", slots.keys.single())
        assertEquals("empty-slot", result.providers.single().keyId)
    }

    @Test fun `新增提供商不填Key始终分配独立空槽且正式预检拒绝`() = kotlinx.coroutines.test.runTest {
        val old = document("old-slot")
        val added = RegistryProvider("provider-new", "新代理", "https://new.example/v1", "old-slot")
        val addedModel = RegistryModel("model-new", "provider-new", "新模型", "new-model")
        val candidate = old.copy(providers = old.providers + added, models = old.models + addedModel)
        val published = ProviderCredentialPublisher(
            writeKey = { _, _ -> error("未填写 Key 不应写入凭据") }, saveDocument = {},
            newSlotId = { "fresh-slot" }
        ).save(old, candidate, emptyMap())

        assertEquals("old-slot", old.providers.single().keyId)
        assertEquals("fresh-slot", published.providers.single { it.id == "provider-new" }.keyId)
        val credentials = resolveProviderCredentials(published.providers) {
            slot -> if (slot == "old-slot") "synthetic-old-key" else ""
        }
        assertEquals(CredentialAvailability.MISSING, credentials.availabilityByProviderId["provider-new"])
        val call = ModelAwareCallPreparation.prepare(
            published.copy(globalDefault = RegistrySelection.explicit(ModelReference("provider-new", "model-new"))),
            app.solochat.core.model.ModelUse.CHAT,
            credentials.availabilityByProviderId,
            credentials.apiKeyBySlot, ChatConfig(), 1L,
            object : app.solochat.core.agent.PriceResolver {
                override fun resolve(model: String, config: ChatConfig) = app.solochat.core.model.ModelPrice()
            }
        ) as ModelCallPreparation.Rejected
        assertEquals(CallPreparationFailure.MODEL_NOT_RESOLVED, call.reason)
    }

    @Test fun `新提供商填写Key只写独立槽位并清空后仍保持缺失`() = kotlinx.coroutines.test.runTest {
        val old = document("old-slot")
        val added = RegistryProvider("provider-new", "新代理", "https://new.example/v1", "old-slot")
        val candidate = old.copy(providers = old.providers + added)
        val slots = mutableMapOf("old-slot" to "synthetic-old-key")
        var nextSlot = 0
        val publisher = ProviderCredentialPublisher(
            writeKey = { slot, value -> slots[slot] = value }, saveDocument = {},
            newSlotId = { "new-slot-${++nextSlot}" }
        )

        val withKey = publisher.save(old, candidate, mapOf("provider-new" to "synthetic-new-key"))
        assertEquals("synthetic-old-key", slots["old-slot"])
        assertEquals("synthetic-new-key", slots[withKey.providers.single { it.id == "provider-new" }.keyId])
        val cleared = publisher.save(withKey, withKey, mapOf("provider-new" to null))
        val emptySlot = cleared.providers.single { it.id == "provider-new" }.keyId
        assertEquals("synthetic-old-key", slots["old-slot"])
        assertEquals("synthetic-new-key", slots[withKey.providers.single { it.id == "provider-new" }.keyId])
        assertEquals(CredentialAvailability.MISSING,
            resolveProviderCredentials(cleared.providers) { slots[it].orEmpty() }.availabilityByProviderId["provider-new"])
        assertEquals("", slots[emptySlot].orEmpty())
    }

    @Test fun `新增提供商明确清空Key时不保留旧提供商槽位`() = kotlinx.coroutines.test.runTest {
        val old = document("old-slot")
        val added = RegistryProvider("provider-new", "新代理", "https://new.example/v1", "old-slot")
        val candidate = old.copy(providers = old.providers + added)
        val slots = mutableMapOf("old-slot" to "synthetic-old-key")
        val result = ProviderCredentialPublisher(
            writeKey = { slot, value -> slots[slot] = value }, saveDocument = {}, newSlotId = { "empty-slot" }
        ).save(old, candidate, mapOf("provider-new" to null))

        assertEquals("old-slot", old.providers.single().keyId)
        assertEquals("empty-slot", result.providers.single { it.id == "provider-new" }.keyId)
        assertEquals("synthetic-old-key", slots["old-slot"])
        assertEquals(CredentialAvailability.MISSING,
            resolveProviderCredentials(result.providers) { slots[it].orEmpty() }.availabilityByProviderId["provider-new"])
    }

    @Test fun `新增Key发布失败时旧槽位与新写入槽位都保留且文档不发布`() = kotlinx.coroutines.test.runTest {
        val old = document("old-slot")
        val added = RegistryProvider("provider-new", "新代理", "https://new.example/v1", "old-slot")
        val candidate = old.copy(providers = old.providers + added)
        val slots = mutableMapOf("old-slot" to "synthetic-old-key")
        val published = runCatching {
            ProviderCredentialPublisher(
                writeKey = { slot, value -> slots[slot] = value },
                saveDocument = { error("模拟配置发布失败") }, newSlotId = { "new-slot" }
            ).save(old, candidate, mapOf("provider-new" to "synthetic-new-key"))
        }

        assertTrue(published.isFailure)
        assertEquals("synthetic-old-key", slots["old-slot"])
        assertEquals("synthetic-new-key", slots["new-slot"])
        assertEquals("old-slot", old.providers.single().keyId)
    }
}
