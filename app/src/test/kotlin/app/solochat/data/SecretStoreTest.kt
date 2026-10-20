package app.solochat.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SecretStoreTest {
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
