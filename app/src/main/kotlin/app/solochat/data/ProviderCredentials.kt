package app.solochat.data

import app.solochat.core.model.CredentialAvailability

internal data class ProviderCredentials(
    val availabilityByProviderId: Map<String, CredentialAvailability>,
    val apiKeyBySlot: Map<String, String>
)

/** providerId 标识模型来源；keyId 单独标识 SecretStore 槽位，两者不可互换。 */
internal fun resolveProviderCredentials(
    providers: List<RegistryProvider>,
    readKey: (String) -> String
): ProviderCredentials {
    val keysBySlot = providers.associate { it.keyId to readKey(it.keyId) }
    val availability = providers.associate { provider ->
        provider.id to if (keysBySlot[provider.keyId].orEmpty().isBlank())
            CredentialAvailability.MISSING else CredentialAvailability.AVAILABLE
    }
    return ProviderCredentials(availability, keysBySlot)
}

/** 连接测试只可读持久化文档中同一 providerId 的槽位；新草稿不能按自身 keyId 探读历史密钥。 */
internal fun requireProviderKeyForConnectionTest(
    provider: RegistryProvider,
    persistedProviders: Collection<RegistryProvider>,
    pendingChanges: Map<String, String?>,
    readKey: (String) -> String
): String {
    val key = if (provider.id in pendingChanges) {
        pendingChanges[provider.id].orEmpty()
    } else {
        val persisted = persistedProviders.firstOrNull { it.id == provider.id }
        persisted?.let { readKey(it.keyId) }.orEmpty()
    }
    require(key.isNotBlank()) { "请先设置 API Key" }
    return key
}
