package app.solochat.data

import app.solochat.core.agent.ModelConfigResolver
import app.solochat.core.agent.ModelResolution
import app.solochat.core.agent.ThinkingRequestAdaptation
import app.solochat.core.agent.ThinkingRequestAdapter
import app.solochat.core.llm.ApiMessage
import app.solochat.core.llm.LlmRequest
import app.solochat.core.llm.RequestEndpoint
import app.solochat.core.model.*
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver

enum class CallPreparationFailure {
    BACKUP_PAUSED, MODEL_NOT_RESOLVED, CREDENTIAL_MISSING, THINKING_UNSUPPORTED, PRICE_INVALID
}

sealed interface ModelCallPreparation {
    class Ready(val call: PreparedModelCall) : ModelCallPreparation
    data class Rejected(val reason: CallPreparationFailure, val detail: String) : ModelCallPreparation
}

sealed interface PreparedCallClaim<out T> {
    data class Ready<T>(val call: PreparedModelCall, val claimed: T) : PreparedCallClaim<T>
    data class Rejected(val reason: CallPreparationFailure, val detail: String) : PreparedCallClaim<Nothing>
}

/** 一次解析结果的不可变执行快照；凭据不出现在 toString，也不进入 Room 快照。 */
class PreparedModelCall internal constructor(
    val configSnapshot: CallConfigSnapshot,
    val price: ModelPrice,
    val priceAt: Long,
    val requestModelId: String,
    val temperature: Double,
    val maxTokens: Int,
    val timeoutMs: Long,
    private val endpoint: RequestEndpoint,
    private val thinkingParameters: app.solochat.core.llm.ThinkingRequestParameters?,
    val priceRuleFingerprint: String,
    private val resolvePriceAt: (Long) -> ModelPrice
) {
    fun toAgentCall(config: ChatConfig) = app.solochat.core.agent.PreparedAgentCall(
        config.copy(baseUrl = endpoint.baseUrl,
            apiKey = endpoint.apiKey, model = requestModelId, temperature = temperature,
            maxTokens = maxTokens, timeoutMs = timeoutMs, priceInputPerM = price.inputPerM,
            priceOutputPerM = price.outputPerM, priceCachedPerM = price.cachedPerM,
            priceConfigured = price.known, cachedPriceConfigured = price.cachedPriceKnown),
        configSnapshot, price, priceAt, endpoint, thinkingParameters, priceRuleFingerprint, resolvePriceAt
    )

    fun request(messages: List<ApiMessage>) = LlmRequest(
        model = requestModelId,
        messages = messages.toList(),
        temperature = temperature,
        maxTokens = maxTokens,
        thinkingParameters = thinkingParameters,
        timeoutMs = timeoutMs,
        endpoint = endpoint
    )

    override fun toString() = "PreparedModelCall(provider=${configSnapshot.providerId}, model=${configSnapshot.modelId}, endpoint=<已隐藏>)"
}

/** A-10 正式聊天/压缩入口共用的 v2 调用准备器；先校验，再返回不可变执行快照。 */
object ModelAwareCallPreparation {
    /** 供未来调用入口先完成配置预检，再认领待发；拒绝结果不会执行认领回调。 */
    suspend fun <T> claimAfterPreflight(
        preparation: ModelCallPreparation,
        claimPending: suspend () -> T
    ): PreparedCallClaim<T> = when (preparation) {
        is ModelCallPreparation.Rejected -> PreparedCallClaim.Rejected(preparation.reason, preparation.detail)
        is ModelCallPreparation.Ready -> PreparedCallClaim.Ready(preparation.call, claimPending())
    }

    fun prepare(
        document: ModelRegistryDocument,
        use: ModelUse,
        credentialAvailability: Map<String, CredentialAvailability>,
        apiKeyBySlot: Map<String, String>,
        behavior: ChatConfig,
        priceAt: Long,
        pricing: PriceResolver,
        conversationBinding: ConversationModelBinding? = null,
        backupPaused: Boolean = false
    ): ModelCallPreparation {
        if (backupPaused) return rejected(CallPreparationFailure.BACKUP_PAUSED, "备份副本仍处于暂停状态")
        if (use == ModelUse.TITLE) return rejected(CallPreparationFailure.MODEL_NOT_RESOLVED, "标题用途尚无调用入口")
        val configuration = runCatching { document.toCoreConfiguration() }.getOrElse {
            return rejected(CallPreparationFailure.MODEL_NOT_RESOLVED, "模型配置无效：${it.message.orEmpty()}")
        }
        val resolved = ModelConfigResolver.resolve(configuration, use, credentialAvailability, conversationBinding)
        if (resolved !is ModelResolution.Success) {
            return rejected(CallPreparationFailure.MODEL_NOT_RESOLVED, "模型选择无法解析：${(resolved as ModelResolution.Failure).error.reason}")
        }
        val selected = resolved.value
        if (selected.credentialAvailability != CredentialAvailability.AVAILABLE) {
            return rejected(CallPreparationFailure.CREDENTIAL_MISSING, "所选提供商凭据不可用")
        }
        val apiKey = apiKeyBySlot[selected.provider.keyId].orEmpty()
        if (apiKey.isBlank()) return rejected(CallPreparationFailure.CREDENTIAL_MISSING, "所选提供商缺少凭据")

        val thinking = ThinkingRequestAdapter.adapt(selected.provider, selected.model, use)
        if (thinking !is ThinkingRequestAdaptation.Ready) {
            return rejected(CallPreparationFailure.THINKING_UNSUPPORTED, (thinking as ThinkingRequestAdaptation.Unsupported).detail)
        }

        val registryProvider = document.providers.firstOrNull { it.id == selected.provider.id }
            ?: return rejected(CallPreparationFailure.MODEL_NOT_RESOLVED, "模型提供商配置已失效")
        val registryModel = document.models.firstOrNull { it.id == selected.model.id && it.providerId == selected.provider.id }
            ?: return rejected(CallPreparationFailure.MODEL_NOT_RESOLVED, "模型配置已失效")
        val legacyPricing = registryProvider.legacyPricing
        val mayUseLegacyFallback = registryModel.legacyPriceEligible
        val priceCatalog = legacyPricing.priceCatalog ?: if (mayUseLegacyFallback) null else
            PriceCatalog("unpriced", listOf(PriceTable("unpriced", "未配置报价", emptyList())))
        val priceConfig = behavior.copy(
            baseUrl = selected.provider.baseUrl,
            apiKey = "",
            model = selected.model.requestModelId,
            useOfficialPrice = legacyPricing.useOfficialPrice,
            priceCatalog = priceCatalog,
            priceInputPerM = legacyPricing.priceInput.takeIf { mayUseLegacyFallback } ?: 0.0,
            priceOutputPerM = legacyPricing.priceOutput.takeIf { mayUseLegacyFallback } ?: 0.0,
            priceCachedPerM = legacyPricing.priceCached.takeIf { mayUseLegacyFallback } ?: 0.0,
            priceConfigured = legacyPricing.priceConfigured && mayUseLegacyFallback,
            cachedPriceConfigured = legacyPricing.cachedPriceConfigured && mayUseLegacyFallback
        )
        val resolveFrozenPrice: (Long) -> ModelPrice = { at ->
            pricing.resolve(selected.model.requestModelId, priceConfig, at)
        }
        val frozenPrice = runCatching { resolveFrozenPrice(priceAt) }
            .getOrElse { return rejected(CallPreparationFailure.PRICE_INVALID, "价格解析失败：${it.message.orEmpty()}") }
        // ChatConfig 不含秘密的价格输入字段在上方已固定，apiKey 明确为空；用于预览后检测规则是否改动。
        val priceRuleFingerprint = priceConfig.toString()
        val intent = if (use == ModelUse.COMPRESSION) ModelThinking(ThinkingMode.OFF) else selected.model.thinking
        val snapshot = CallConfigSnapshot(
            version = 1,
            providerId = selected.provider.id,
            providerName = selected.provider.name,
            modelId = selected.model.id,
            modelDisplayName = selected.model.displayName,
            requestModelId = selected.model.requestModelId,
            use = use,
            selectionSource = selected.selectionSource,
            thinking = intent,
            thinkingSource = if (use == ModelUse.COMPRESSION) ThinkingSource.COMPRESSION_DEFAULT else ThinkingSource.MODEL_PREFERENCE,
            actualThinkingParameters = thinking.actualParameters
        )
        return ModelCallPreparation.Ready(PreparedModelCall(
            configSnapshot = snapshot,
            price = frozenPrice,
            priceAt = priceAt,
            requestModelId = selected.model.requestModelId,
            temperature = if (use == ModelUse.COMPRESSION) 0.2 else behavior.temperature,
            maxTokens = behavior.maxTokens,
            timeoutMs = behavior.timeoutMs,
            endpoint = RequestEndpoint(selected.provider.baseUrl, apiKey),
            thinkingParameters = thinking.parameters,
            priceRuleFingerprint = priceRuleFingerprint,
            resolvePriceAt = resolveFrozenPrice
        ))
    }

    private fun rejected(reason: CallPreparationFailure, detail: String) = ModelCallPreparation.Rejected(reason, detail)
}
