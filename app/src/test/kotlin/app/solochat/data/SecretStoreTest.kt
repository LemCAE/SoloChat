package app.solochat.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SecretStoreTest {
    @Test fun `旧密钥幂等复制新组互不回退清空删除均不复活旧密钥`() {
        val values = mutableMapOf("api_key" to "legacy-test-key")
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; editor }
                "remove" -> { values.remove(args!![0] as String); editor }
                "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
        val store = SecretStore({ prefs }, { error("不得降级") })
        store.migrateLegacyApiKey()
        assertEquals("legacy-test-key", store.apiKeyFor(ApiProfile.LEGACY_ID))
        assertEquals("", store.apiKeyFor("other"))
        store.setApiKeyFor("other", "other-test-key")
        assertEquals("legacy-test-key", store.apiKeyFor(ApiProfile.LEGACY_ID))
        store.setApiKeyFor(ApiProfile.LEGACY_ID, "")
        store.migrateLegacyApiKey()
        assertEquals("", store.apiKeyFor(ApiProfile.LEGACY_ID))
        store.deleteApiKeyFor("other")
        assertEquals("", store.apiKeyFor("other"))
        store.deleteApiKeyFor(ApiProfile.LEGACY_ID)
        assertFalse(values.containsKey("api_key"))
    }

    @Test fun `加密不可用不能将配置组密钥写入明文回退`() {
        val store = SecretStore({ error("不可用") }, { preferences() })
        assertTrue(runCatching { store.setApiKeyFor("test", "test-key") }.isFailure)
        assertTrue(runCatching { store.migrateLegacyApiKey() }.isFailure)
    }

    private fun preferences() = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, _, _ -> null } as SharedPreferences

    @Test fun `加密初始化失败 - 明文回退后降级标志仍为真`() {
        var fellBack = false
        val store = SecretStore(
            encrypted = { throw IllegalStateException("模拟 Keystore 不可用") },
            fallback = { fellBack = true; preferences() }
        )
        assertTrue(fellBack)
        assertTrue("降级警告不能被后续属性初始化清掉", store.degraded)
    }

    @Test fun `加密初始化成功 - 不使用明文回退也不显示降级警告`() {
        val store = SecretStore(
            encrypted = { preferences() },
            fallback = { error("不应回退") }
        )
        assertFalse(store.degraded)
    }
}
