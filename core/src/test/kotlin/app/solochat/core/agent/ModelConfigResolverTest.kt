package app.solochat.core.agent

import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigResolverTest {
    private val credentials = mapOf("provider-a" to CredentialAvailability.AVAILABLE, "provider-b" to CredentialAvailability.AVAILABLE)
    private val providerA = ModelProvider("provider-a", "同名渠道", "https://a.invalid", "slot-a", ModelProviderProtocol.OPENAI_COMPATIBLE)
    private val providerB = ModelProvider("provider-b", "同名渠道", "https://b.invalid", "slot-b", ModelProviderProtocol.OPENAI_COMPATIBLE)
    private val modelA = ModelDefinition("model-a", "provider-a", "同名模型", "Case-Sensitive-A")
    private val modelB = ModelDefinition("model-b", "provider-b", "同名模型", "Case-Sensitive-B")

    private fun config(
        global: ModelSelection = ModelSelection.Explicit(modelA.reference),
        chat: ModelSelection = ModelSelection.Inherit,
        compression: ModelSelection = ModelSelection.Inherit,
        title: ModelSelection = ModelSelection.Inherit,
        models: List<ModelDefinition> = listOf(modelA, modelB)
    ) = ModelConfiguration(1, listOf(providerA, providerB), models, global, chat, compression, title)

    private fun success(result: ModelResolution) = (result as ModelResolution.Success).value

    @Test fun `聊天按会话聊天用途全局顺序解析来源`() {
        val configuration = config(chat = ModelSelection.Explicit(modelB.reference))
        val conversation = ConversationModelBinding(ModelSelection.Explicit(modelA.reference))

        assertEquals(SelectionSource.CONVERSATION, success(ModelConfigResolver.resolve(configuration, ModelUse.CHAT, credentials, conversation)).selectionSource)
        assertEquals(SelectionSource.CHAT_PREFERENCE, success(ModelConfigResolver.resolve(configuration, ModelUse.CHAT, credentials)).selectionSource)
        assertEquals(SelectionSource.GLOBAL_DEFAULT, success(ModelConfigResolver.resolve(config(), ModelUse.CHAT, credentials)).selectionSource)
    }

    @Test fun `显式用途选择不依赖全局默认`() {
        val result = ModelConfigResolver.resolve(
            config(global = ModelSelection.NeedsConfirmation(null, "旧引用待确认"), chat = ModelSelection.Explicit(modelB.reference)),
            ModelUse.CHAT,
            credentials
        )

        assertEquals(modelB.reference, success(result).model.reference)
    }

    @Test fun `跨提供商同名模型按稳定引用区分且请求名大小写保留`() {
        val resolved = success(ModelConfigResolver.resolve(config(global = ModelSelection.Explicit(modelB.reference)), ModelUse.CHAT, credentials))

        assertEquals("provider-b", resolved.provider.id)
        assertEquals("Case-Sensitive-B", resolved.model.requestModelId)
    }

    @Test fun `失效或待确认的显式覆盖不向下继承`() {
        val invalid = ModelSelection.Explicit(ModelReference("provider-a", "removed"))
        val missing = ModelConfigResolver.resolve(config(chat = invalid), ModelUse.CHAT, credentials)
        val pending = ModelConfigResolver.resolve(
            config(chat = ModelSelection.NeedsConfirmation(modelA.reference, "来源不明")), ModelUse.CHAT, credentials
        )

        assertEquals(ModelResolutionFailure.MODEL_NOT_FOUND, (missing as ModelResolution.Failure).error.reason)
        assertEquals(ModelResolutionFailure.NEEDS_CONFIRMATION, (pending as ModelResolution.Failure).error.reason)
    }

    @Test fun `禁用引用和凭据不可用都明确失败`() {
        val disabled = ModelConfigResolver.resolve(config(models = listOf(modelA.copy(enabled = false), modelB)), ModelUse.CHAT, credentials)
        val unavailable = ModelConfigResolver.resolve(config(), ModelUse.CHAT, emptyMap())

        assertEquals(ModelResolutionFailure.MODEL_DISABLED, (disabled as ModelResolution.Failure).error.reason)
        assertEquals(ModelResolutionFailure.CREDENTIALS_UNAVAILABLE, (unavailable as ModelResolution.Failure).error.reason)
    }

    @Test fun `标题待确认不会阻塞聊天或压缩解析`() {
        val configuration = config(title = ModelSelection.NeedsConfirmation(null, "标题偏好待确认"))

        assertTrue(ModelConfigResolver.resolve(configuration, ModelUse.CHAT, credentials) is ModelResolution.Success)
        assertTrue(ModelConfigResolver.resolve(configuration, ModelUse.COMPRESSION, credentials) is ModelResolution.Success)
        assertEquals(ModelResolutionFailure.NEEDS_CONFIRMATION,
            ((ModelConfigResolver.resolve(configuration, ModelUse.TITLE, credentials) as ModelResolution.Failure).error.reason))
    }

    @Test fun `聊天 AUTO ON OFF 根据意图能力解析且不生成厂商字段`() {
        val model = modelA.copy(thinkingCapability = ThinkingCapability(ThinkingControl.ENABLE_AND_DISABLE, setOf(ThinkingEffort.HIGH), CapabilitySource.MODEL_CONFIGURATION))
        val auto = ModelConfigResolver.resolveThinking(model, ModelUse.CHAT)
        val on = ModelConfigResolver.resolveThinking(model.copy(thinking = ModelThinking(ThinkingMode.ON, ThinkingEffort.HIGH)), ModelUse.CHAT)
        val off = ModelConfigResolver.resolveThinking(model.copy(thinking = ModelThinking(ThinkingMode.OFF)), ModelUse.CHAT)

        assertEquals(ThinkingMode.AUTO, (auto as ThinkingResolution.Supported).intent.mode)
        assertEquals(ThinkingMode.ON, (on as ThinkingResolution.Supported).intent.mode)
        assertEquals(ThinkingMode.OFF, (off as ThinkingResolution.Supported).intent.mode)
        assertTrue((auto as ThinkingResolution.Supported).intent.effort == null)
    }

    @Test fun `聊天 ON 不会传给压缩且压缩 OFF 不依赖聊天开关能力`() {
        val model = modelA.copy(
            thinking = ModelThinking(ThinkingMode.ON, ThinkingEffort.HIGH),
            thinkingCapability = ThinkingCapability(ThinkingControl.ENABLE_ONLY, setOf(ThinkingEffort.HIGH), CapabilitySource.MODEL_CONFIGURATION)
        )

        val compression = ModelConfigResolver.resolveThinking(model, ModelUse.COMPRESSION) as ThinkingResolution.Unsupported
        assertEquals(ThinkingMode.OFF, compression.intent.mode)
        assertEquals(ThinkingFailure.CANNOT_DISABLE, compression.reason)
        assertEquals(ThinkingSource.COMPRESSION_DEFAULT, compression.source)
    }

    @Test fun `显式 ON 的未知能力和不支持档位返回明确结果`() {
        val unknown = ModelConfigResolver.resolveThinking(modelA.copy(thinking = ModelThinking(ThinkingMode.ON)), ModelUse.CHAT)
        val wrongEffort = ModelConfigResolver.resolveThinking(
            modelA.copy(
                thinking = ModelThinking(ThinkingMode.ON, ThinkingEffort.HIGH),
                thinkingCapability = ThinkingCapability(ThinkingControl.ENABLE_AND_DISABLE, setOf(ThinkingEffort.LOW), CapabilitySource.USER_CONFIRMED)
            ), ModelUse.CHAT
        )

        assertEquals(ThinkingFailure.CAPABILITY_UNKNOWN, (unknown as ThinkingResolution.Unsupported).reason)
        assertEquals(ThinkingFailure.EFFORT_UNSUPPORTED, (wrongEffort as ThinkingResolution.Unsupported).reason)
    }

    @Test fun `压缩 OFF 对未知能力明确失败而 AUTO 允许遵循模型默认`() {
        val unknownModel = modelA.copy(thinking = ModelThinking(ThinkingMode.AUTO))

        assertTrue(ModelConfigResolver.resolveThinking(unknownModel, ModelUse.CHAT) is ThinkingResolution.Supported)
        assertEquals(ThinkingFailure.CAPABILITY_UNKNOWN,
            (ModelConfigResolver.resolveThinking(unknownModel, ModelUse.COMPRESSION) as ThinkingResolution.Unsupported).reason)
        assertFalse(ModelConfigResolver.resolveThinking(unknownModel, ModelUse.COMPRESSION) is ThinkingResolution.Supported)
    }
}
