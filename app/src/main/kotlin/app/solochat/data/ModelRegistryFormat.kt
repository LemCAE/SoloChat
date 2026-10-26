package app.solochat.data

import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** DataStore 中未来唯一权威的 v2 非秘密配置文档。当前运行路径仍只读旧 Snapshot。 */
@Serializable
data class ModelRegistryDocument(
    val version: Int = ModelRegistryFormat.CURRENT_VERSION,
    val revision: Long = 0,
    val providers: List<RegistryProvider>,
    val models: List<RegistryModel>,
    val globalDefault: RegistrySelection,
    val chatSelection: RegistrySelection = RegistrySelection.inherit(),
    val compressionSelection: RegistrySelection = RegistrySelection.inherit(),
    val titleSelection: RegistrySelection = RegistrySelection.inherit()
) {
    fun validate() {
        require(version == ModelRegistryFormat.CURRENT_VERSION) { "不支持模型配置版本：$version" }
        require(revision >= 0) { "模型配置修订号无效" }
        require(providers.map { it.id }.distinct().size == providers.size) { "提供商 ID 重复" }
        require(models.map { it.id }.distinct().size == models.size) { "模型 ID 重复" }
        val providerIds = providers.mapTo(mutableSetOf()) { it.id }
        require(providers.all { it.id.isNotBlank() && it.name.isNotBlank() && it.keyId.matches(Regex("[A-Za-z0-9-]+")) }) {
            "提供商身份或密钥槽位无效"
        }
        providers.forEach { ModelProviderProtocol.valueOf(it.protocol) }
        require(models.all {
            it.id.isNotBlank() && it.providerId in providerIds && it.displayName.isNotBlank() && it.requestModelId.isNotBlank()
        }) { "模型引用无效" }
        val references = models.mapTo(mutableSetOf()) { it.providerId to it.id }
        listOf(globalDefault, chatSelection, compressionSelection, titleSelection).forEach { it.validate(references) }
        providers.forEach { provider ->
            provider.legacyPricing.validate()
            provider.pricing?.validate(provider.id, models.filter { it.providerId == provider.id })
        }
        models.forEach { it.validate() }
    }

    fun toCoreConfiguration(): ModelConfiguration {
        validate()
        return ModelConfiguration(
            version = 1,
            providers = providers.map { it.toCore() },
            models = models.map { it.toCore() },
            globalDefault = globalDefault.toCore(),
            chatSelection = chatSelection.toCore(),
            compressionSelection = compressionSelection.toCore(),
            titleSelection = titleSelection.toCore()
        )
    }
}

@Serializable
data class RegistryProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** SecretStore 槽位 ID；不包含 Key。 */
    val keyId: String,
    val protocol: String = ModelProviderProtocol.OPENAI_COMPATIBLE.name,
    val enabled: Boolean = true,
    val legacyPricing: LegacyProviderPricing = LegacyProviderPricing(),
    val pricing: app.solochat.data.pricing.ProviderPricing? = null
) {
    internal fun toCore() = ModelProvider(
        id, name, baseUrl, keyId, ModelProviderProtocol.valueOf(protocol), enabled
    )
}

/** 原 R4 价格字段原样携带；null 目录与空目录保持不同语义。 */
@Serializable
data class LegacyProviderPricing(
    val useOfficialPrice: Boolean = true,
    val priceCatalog: PriceCatalog? = null,
    val priceInput: Double = 0.0,
    val priceOutput: Double = 0.0,
    val priceCached: Double = 0.0,
    val priceConfigured: Boolean = false,
    val cachedPriceConfigured: Boolean = false
) {
    fun validate() {
        require(listOf(priceInput, priceOutput, priceCached).all { it.isFinite() && it >= 0.0 }) { "旧价格无效" }
        priceCatalog?.let(PriceCatalogFormat::validate)
    }

    companion object {
        fun from(profile: ApiProfile) = LegacyProviderPricing(
            profile.useOfficialPrice, profile.priceCatalog, profile.priceInput, profile.priceOutput,
            profile.priceCached, profile.priceConfigured, profile.cachedPriceConfigured
        )
    }
}

@Serializable
data class RegistryModel(
    val id: String,
    val providerId: String,
    val displayName: String,
    val requestModelId: String,
    val enabled: Boolean = true,
    val thinkingMode: String = ThinkingMode.AUTO.name,
    val thinkingEffort: String? = null,
    val thinkingControl: String = ThinkingControl.UNKNOWN.name,
    val supportedEfforts: List<String> = emptyList(),
    val capabilitySource: String = CapabilitySource.UNKNOWN.name,
    /** 手动设置的请求方言；null 表示只使用适配器的可信自动声明。 */
    val thinkingDialectOverride: String? = null,
    /** 仅迁入的旧模型可沿用原提供商 R4 价格规则；新模型必须显式为 false。 */
    val legacyPriceEligible: Boolean = false,
    val visionControl: String = VisionControl.UNKNOWN.name,
    val visionCapabilitySource: String = CapabilitySource.UNKNOWN.name
) {
    fun validate() {
        val mode = ThinkingMode.valueOf(thinkingMode)
        val effort = thinkingEffort?.let(ThinkingEffort::valueOf)
        ModelThinking(mode, effort)
        ThinkingControl.valueOf(thinkingControl)
        CapabilitySource.valueOf(capabilitySource)
        VisionControl.valueOf(visionControl)
        CapabilitySource.valueOf(visionCapabilitySource)
        thinkingDialectOverride?.let(ThinkingParameterDialect::valueOf)
        supportedEfforts.forEach(ThinkingEffort::valueOf)
        require(supportedEfforts.distinct().size == supportedEfforts.size) { "思考档位重复" }
        require(ThinkingEffort.OFF.name !in supportedEfforts) { "OFF 不能作为思考强度" }
    }

    internal fun toCore() = ModelDefinition(
        id = id,
        providerId = providerId,
        displayName = displayName,
        requestModelId = requestModelId,
        enabled = enabled,
        thinking = ModelThinking(ThinkingMode.valueOf(thinkingMode), thinkingEffort?.let(ThinkingEffort::valueOf)),
        thinkingCapability = ThinkingCapability(
            ThinkingControl.valueOf(thinkingControl),
            supportedEfforts.mapTo(linkedSetOf(), ThinkingEffort::valueOf),
            CapabilitySource.valueOf(capabilitySource)
        ),
        thinkingDialectOverride = thinkingDialectOverride?.let(ThinkingParameterDialect::valueOf),
        visionCapability = VisionCapability(VisionControl.valueOf(visionControl), CapabilitySource.valueOf(visionCapabilitySource))
    )

    companion object {
        fun from(model: ModelDefinition, legacyPriceEligible: Boolean = false) = RegistryModel(
            id = model.id,
            providerId = model.providerId,
            displayName = model.displayName,
            requestModelId = model.requestModelId,
            enabled = model.enabled,
            thinkingMode = model.thinking.mode.name,
            thinkingEffort = model.thinking.effort?.name,
            thinkingControl = model.thinkingCapability.control.name,
            supportedEfforts = model.thinkingCapability.supportedEfforts.map { it.name }.sorted(),
            capabilitySource = model.thinkingCapability.source.name,
            thinkingDialectOverride = model.thinkingDialectOverride?.name,
            legacyPriceEligible = legacyPriceEligible,
            visionControl = model.visionCapability.control.name,
            visionCapabilitySource = model.visionCapability.source.name
        )
    }
}

@Serializable
data class RegistrySelection(
    val state: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val reason: String? = null
) {
    fun validate(references: Set<Pair<String, String>>) {
        when (state) {
            "INHERIT" -> require(providerId == null && modelId == null && reason == null) { "继承选择不能携带模型引用" }
            "EXPLICIT" -> {
                require(providerId != null && modelId != null && reason == null) { "显式选择缺少模型引用" }
                require(providerId to modelId in references) { "显式模型引用不存在" }
            }
            "NEEDS_CONFIRMATION" -> {
                require(!reason.isNullOrBlank()) { "待确认选择必须说明原因" }
                require((providerId == null) == (modelId == null)) { "待确认引用必须同时保留提供商和模型 ID" }
            }
            else -> error("未知选择状态：$state")
        }
    }

    internal fun toCore(): ModelSelection = when (state) {
        "INHERIT" -> ModelSelection.Inherit
        "EXPLICIT" -> ModelSelection.Explicit(ModelReference(requireNotNull(providerId), requireNotNull(modelId)))
        "NEEDS_CONFIRMATION" -> ModelSelection.NeedsConfirmation(
            if (providerId != null && modelId != null) ModelReference(providerId, modelId) else null,
            requireNotNull(reason)
        )
        else -> error("未知选择状态：$state")
    }

    companion object {
        fun inherit() = RegistrySelection("INHERIT")
        fun explicit(reference: ModelReference) = RegistrySelection("EXPLICIT", reference.providerId, reference.modelId)
        fun needsConfirmation(reference: ModelReference?, reason: String) =
            RegistrySelection("NEEDS_CONFIRMATION", reference?.providerId, reference?.modelId, reason)

        fun from(selection: ModelSelection) = when (selection) {
            ModelSelection.Inherit -> inherit()
            is ModelSelection.Explicit -> explicit(selection.model)
            is ModelSelection.NeedsConfirmation -> needsConfirmation(selection.model, selection.reason)
        }
    }
}

/** 版本化配置及升级准备记录共用严格格式；未知版本不作回退。 */
internal object ModelRegistryFormat {
    const val CURRENT_VERSION = 2
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    fun encode(document: ModelRegistryDocument): String {
        document.validate()
        return json.encodeToString(document)
    }

    fun decode(text: String): ModelRegistryDocument = json.decodeFromString<ModelRegistryDocument>(text).also {
        it.validate()
    }

    fun encodePreparation(record: ModelConfigPreparationRecord): String = json.encodeToString(record)
    fun decodePreparation(text: String): ModelConfigPreparationRecord =
        json.decodeFromString<ModelConfigPreparationRecord>(text).also { it.validate() }
}
