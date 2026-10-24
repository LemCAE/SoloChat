package app.solochat.data

import app.solochat.R
import app.solochat.core.model.PriceCatalog
import app.solochat.core.model.PriceTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅用于新建时预填；不创建凭据、不请求网络，也不迁改既有组。 */
enum class ApiProfileTemplate(val labelResId: Int, val baseUrl: String, val hintResId: Int) {
    DEEPSEEK(R.string.api_template_deepseek, "https://api.deepseek.com", R.string.api_template_deepseek_hint),
    DASHSCOPE(R.string.api_template_dashscope, "https://dashscope.aliyuncs.com/compatible-mode/v1", R.string.api_template_dashscope_hint),
    CUSTOM(R.string.api_template_custom, "", R.string.api_template_custom_hint)
}

/** 仅存非秘密元数据；密钥按稳定 ID 在 SecretStore 中独立保存。 */
@Serializable
data class ApiProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val model: String = "",
    val modelList: List<String> = emptyList(),
    val useOfficialPrice: Boolean = true,
    val priceCatalog: PriceCatalog? = PriceCatalog("custom", listOf(PriceTable("custom", "本组价格"))),
    val priceInput: Double = 0.0,
    val priceOutput: Double = 0.0,
    val priceCached: Double = 0.0,
    val priceConfigured: Boolean = false,
    val cachedPriceConfigured: Boolean = false,
    val keyId: String = id
) {
    fun applyTo(snapshot: Snapshot): Snapshot = snapshot.copy(
        apiProfileId = id, apiProfileName = name, apiKeyId = keyId, baseUrl = baseUrl, model = model, modelList = modelList,
        useOfficialPrice = useOfficialPrice, priceCatalog = priceCatalog, priceInput = priceInput,
        priceOutput = priceOutput, priceCached = priceCached, priceConfigured = priceConfigured,
        cachedPriceConfigured = cachedPriceConfigured
    )

    companion object {
        const val LEGACY_ID = "legacy"
        fun from(snapshot: Snapshot) = ApiProfile(snapshot.apiProfileId, snapshot.apiProfileName,
            snapshot.baseUrl, snapshot.model, snapshot.modelList, snapshot.useOfficialPrice,
            snapshot.priceCatalog, snapshot.priceInput, snapshot.priceOutput, snapshot.priceCached,
            snapshot.priceConfigured, snapshot.cachedPriceConfigured, snapshot.apiKeyId)
    }
}

internal object ApiProfilesFormat {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(profiles: List<ApiProfile>): String = json.encodeToString(profiles)
    fun decode(text: String): List<ApiProfile> = json.decodeFromString<List<ApiProfile>>(text).also { profiles ->
        require(profiles.map { it.id }.distinct().size == profiles.size &&
            profiles.all { it.id.matches(Regex("[A-Za-z0-9-]+")) &&
                it.keyId.matches(Regex("[A-Za-z0-9-]+")) && it.name.isNotBlank() }) { "接口配置组记录无效" }
    }
}
