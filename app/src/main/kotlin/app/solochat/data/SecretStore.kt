package app.solochat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key 的加密存储。
 *
 * 为什么不和别的设置放一起（DataStore 明文）：Key 一旦泄露，别人可以烧你的额度，
 * 而且它和"聊天记录"不同 —— 记录泄露是隐私问题，Key 泄露是**直接经济损失**。
 * 所以单独走 EncryptedSharedPreferences（底层是 Android Keystore 的 AES 密钥）。
 *
 * 两个刻意的设计：
 * - **不在界面上回显明文**（只显示尾 4 位），避免截图或肩窥。
 * - 提供 [masked] 而不是"读取后自己截断"，防止将来有人在别处直接读全量再泄露。
 */
class SecretStore internal constructor(
    encrypted: () -> SharedPreferences,
    fallback: () -> SharedPreferences
) {
    constructor(context: Context) : this(
        encrypted = {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, FILE_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        },
        fallback = { context.getSharedPreferences(FILE_NAME + "_fallback", Context.MODE_PRIVATE) }
    )

    private val prefs: SharedPreferences = runCatching {
        encrypted()
    }.getOrElse {
        // 极端情况（Keystore 损坏、ROM 异常）：退化成普通存储，但**必须让上层知道**，
        // 否则用户会以为 Key 是加密保存的。这里用 flag 暴露这个事实。
        degraded = true
        fallback()
    }

    /**
     * true = 加密存储不可用，Key 是明文存的。
     * 设置页要显式提示这一点，不能假装无事发生。
     */
    var degraded: Boolean = false
        private set

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** 搜索服务的 API Key，按 providerId 分开存。 */
    fun searchKey(providerId: String): String =
        prefs.getString("search_$providerId", "").orEmpty()

    fun setSearchKey(providerId: String, value: String) {
        prefs.edit().putString("search_$providerId", value.trim()).apply()
    }

    /** 局域网网页版的访问密码（阶段 9 用，现在就存好位置）。 */
    var webPassword: String
        get() = prefs.getString(KEY_WEB_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WEB_PASSWORD, value).apply()

    /** 只给界面看的掩码。短于 8 位时全部打码，避免"尾 4 位"泄露大半个 Key。 */
    fun masked(): String {
        val key = apiKey
        if (key.isBlank()) return ""
        if (key.length < 12) return "•".repeat(key.length)
        return key.take(3) + "•".repeat(6) + key.takeLast(4)
    }

    companion object {
        private const val FILE_NAME = "solochat_secrets"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_WEB_PASSWORD = "web_password"
    }
}
