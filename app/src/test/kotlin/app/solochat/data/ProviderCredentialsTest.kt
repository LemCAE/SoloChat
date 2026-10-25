package app.solochat.data

import app.solochat.core.model.CredentialAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialsTest {
    @Test fun `providerId与keyId不同时按槽位读取Key并按提供商判断可用性`() {
        val providers = listOf(RegistryProvider("provider-id", "代理", "https://example.invalid", "legacy-key-slot"))

        val resolved = resolveProviderCredentials(providers) { slot ->
            assertEquals("legacy-key-slot", slot)
            "synthetic-key"
        }

        assertEquals(CredentialAvailability.AVAILABLE, resolved.availabilityByProviderId["provider-id"])
        assertEquals("synthetic-key", resolved.apiKeyBySlot["legacy-key-slot"])
        assertEquals(null, resolved.availabilityByProviderId["legacy-key-slot"])
    }

    @Test fun `未保存的新提供商连接测试不探读草稿Key槽位且待保存Key可直接使用`() {
        val persisted = RegistryProvider("old-provider", "旧接口", "https://old.example", "old-slot")
        val draft = RegistryProvider("provider-new", "新接口", "https://new.example", "key-new-slot")
        var readCount = 0

        val missing = runCatching { requireProviderKeyForConnectionTest(draft, listOf(persisted), emptyMap()) {
            readCount++
            "synthetic-old-key"
        } }
        val pending = requireProviderKeyForConnectionTest(draft, listOf(persisted), mapOf(draft.id to "synthetic-new-key")) {
            readCount++
            "synthetic-old-key"
        }
        val cleared = runCatching { requireProviderKeyForConnectionTest(draft, listOf(persisted), mapOf(draft.id to null)) {
            readCount++
            "synthetic-old-key"
        } }

        assertTrue(missing.isFailure)
        assertEquals("synthetic-new-key", pending)
        assertTrue(cleared.isFailure)
        assertEquals(0, readCount)
    }

    @Test fun `已有提供商草稿连接测试只读持久化文档可信槽位`() {
        val saved = RegistryProvider("same-provider", "旧接口", "https://old.example", "trusted-slot")
        val edited = saved.copy(baseUrl = "https://edited.example", keyId = "untrusted-draft-slot")
        val readSlots = mutableListOf<String>()

        val key = requireProviderKeyForConnectionTest(edited, listOf(saved), emptyMap()) {
            readSlots += it
            "synthetic-key"
        }

        assertEquals("synthetic-key", key)
        assertEquals(listOf("trusted-slot"), readSlots)
    }
}
