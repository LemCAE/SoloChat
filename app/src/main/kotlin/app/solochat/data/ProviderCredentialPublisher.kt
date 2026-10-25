package app.solochat.data

/**
 * 先写入新的加密槽位，再原子发布引用该槽位的 v2 文档。
 * 发布失败时旧槽位保持原样；新槽位可能成为孤儿，但不会清理凭据。
 */
internal class ProviderCredentialPublisher(
    private val writeKey: (slotId: String, value: String) -> Unit,
    private val saveDocument: suspend (ModelRegistryDocument) -> Unit,
    private val newSlotId: () -> String = { "key-${java.util.UUID.randomUUID()}" }
) {
    suspend fun save(
        current: ModelRegistryDocument,
        candidate: ModelRegistryDocument,
        changes: Map<String, String?>
    ): ModelRegistryDocument {
        current.validate()
        candidate.validate()
        val currentProviders = current.providers.associateBy { it.id }
        val next = candidate.copy(providers = candidate.providers.map { provider ->
            currentProviders[provider.id]?.let { provider.copy(keyId = it.keyId) } ?: provider
        })
        val usedSlots = (current.providers.map { it.keyId } + next.providers.map { it.keyId }).toMutableSet()
        val selectedSlots = mutableMapOf<String, String>()
        // 草稿里的 keyId 对新提供商不可信：总是分配独立槽位，避免旧文档/历史槽位被重用。
        next.providers.filterNot { currentProviders.containsKey(it.id) }.forEach { provider ->
            var generated: String
            do { generated = newSlotId() } while (generated in usedSlots)
            require(generated.matches(Regex("[A-Za-z0-9-]+"))) { "新密钥槽位 ID 无效" }
            usedSlots += generated
            selectedSlots[provider.id] = generated
        }
        changes.forEach { (providerId, value) ->
            val provider = checkNotNull(next.providers.firstOrNull { it.id == providerId }) { "密钥对应的提供商不存在" }
            val slot = selectedSlots[providerId] ?: if (currentProviders.containsKey(providerId)) {
                var generated: String
                do { generated = newSlotId() } while (generated in usedSlots)
                require(generated.matches(Regex("[A-Za-z0-9-]+"))) { "新密钥槽位 ID 无效" }
                usedSlots += generated
                generated
            } else error("新增提供商密钥槽位未分配")
            if (value != null) writeKey(slot, value)
            selectedSlots[providerId] = slot
        }
        val published = next.copy(providers = next.providers.map { provider ->
            selectedSlots[provider.id]?.let { provider.copy(keyId = it) } ?: provider
        }).also(ModelRegistryDocument::validate)
        saveDocument(published)
        return published
    }
}
