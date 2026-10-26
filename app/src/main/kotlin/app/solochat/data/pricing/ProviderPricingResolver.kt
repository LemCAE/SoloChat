package app.solochat.data.pricing

import app.solochat.core.model.*
import app.solochat.data.RegistryModel
import app.solochat.data.RegistryProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** B 后唯一按稳定本机身份选择报价的解析器；不访问旧表或随包规则。 */
object ProviderPricingResolver {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    fun resolve(provider: RegistryProvider, model: RegistryModel, use: ModelUse, at: Long): ModelPrice {
        val pricing = requireNotNull(provider.pricing) { "提供商报价尚未整理" }
        val bound = pricing.bindings[model.id]
            ?: return ModelPrice(source = "未配置模型报价", known = false)
        if (bound.requestModelId != model.requestModelId)
            return ModelPrice(source = "报价请求身份不匹配", known = false)
        val intent = if (use == ModelUse.COMPRESSION) ThinkingMode.OFF.name else model.thinkingMode
        if (bound.conditions.thinkingMode != null && bound.conditions.thinkingMode != intent)
            return ModelPrice(source = "报价适用思考模式不匹配", known = false)
        if (bound.conditions.connectionFingerprint != null &&
            bound.conditions.connectionFingerprint != connectionFingerprint(provider))
            return ModelPrice(source = "报价连接适用性未确认", known = false)
        val table = PriceTable("bound", "模型报价", listOf(bound.rule))
        return resolvePriceEntry(table, bound.rule, at)
    }

    fun fingerprint(provider: RegistryProvider, model: RegistryModel, use: ModelUse): String {
        val bound = provider.pricing?.bindings?.get(model.id)
        val effectiveIntent = if (use == ModelUse.COMPRESSION) "OFF" else
            "${model.thinkingMode}:${model.thinkingEffort.orEmpty()}"
        val canonical = listOf(provider.id, model.id, model.requestModelId, use.name, effectiveIntent,
            bound?.let { json.encodeToString(it) }.orEmpty()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun connectionFingerprint(provider: RegistryProvider): String = MessageDigest.getInstance("SHA-256")
        .digest("${provider.protocol}|${provider.baseUrl}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
