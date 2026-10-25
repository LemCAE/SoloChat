package app.solochat.core.agent

import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ThinkingRequestAdapterTest {
    @Test fun `官方 DeepSeek 精确 endpoint 与型号生成 toggle 和档位快照`() {
        val result = ThinkingRequestAdapter.adapt(
            provider("https://api.deepseek.com/v1"), model("deepseek-flash", ModelThinking(ThinkingMode.ON, ThinkingEffort.HIGH)), ModelUse.CHAT
        ) as ThinkingRequestAdaptation.Ready

        assertEquals(CapabilitySource.PROVIDER_MODEL_DECLARATION, result.capability.source)
        assertEquals(ThinkingParameterDialect.DEEPSEEK_CHAT_COMPLETIONS, result.dialect)
        assertEquals(true, result.parameters?.enabled)
        assertEquals(ThinkingEffort.HIGH, result.parameters?.effort)
        assertEquals(listOf(ThinkingParameterSnapshot("thinking.type", "enabled"),
            ThinkingParameterSnapshot("reasoning_effort", "high")), result.actualParameters)
    }

    @Test fun `官方百炼精确 hybrid 型号使用 enable_thinking 且压缩独立关闭`() {
        val result = ThinkingRequestAdapter.adapt(
            provider("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            model("qwen3.8-max", ModelThinking(ThinkingMode.ON)), ModelUse.COMPRESSION
        ) as ThinkingRequestAdaptation.Ready

        assertEquals(ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING, result.dialect)
        assertEquals(false, result.parameters?.enabled)
        assertEquals(listOf(ThinkingParameterSnapshot("enable_thinking", "false")), result.actualParameters)
    }

    @Test fun `官方文档仅确认有思考而未确认可关闭的型号不自动声明 OFF`() {
        val result = ThinkingRequestAdapter.adapt(
            provider("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            model("deepseek-v4.1-flash-1017", ModelThinking(ThinkingMode.OFF)), ModelUse.CHAT
        )
        assertEquals(ThinkingFailure.CAPABILITY_UNKNOWN, (result as ThinkingRequestAdaptation.Unsupported).failure)
    }

    @Test fun `第三方同名模型能力未知时 AUTO 可用但显式 ON 和 OFF 均阻止`() {
        val provider = provider("https://proxy.example/v1")
        val model = model("deepseek-flash", ModelThinking(ThinkingMode.AUTO))
        val automatic = ThinkingRequestAdapter.adapt(provider, model, ModelUse.CHAT) as ThinkingRequestAdaptation.Ready
        assertNull(automatic.dialect)
        assertNull(automatic.parameters)
        assertTrue(ThinkingRequestAdapter.adapt(provider, model.copy(thinking = ModelThinking(ThinkingMode.ON)), ModelUse.CHAT)
            is ThinkingRequestAdaptation.Unsupported)
        assertTrue(ThinkingRequestAdapter.adapt(provider, model.copy(thinking = ModelThinking(ThinkingMode.OFF)), ModelUse.CHAT)
            is ThinkingRequestAdaptation.Unsupported)
    }

    @Test fun `手动能力和方言覆盖自动映射并遵守已声明档位`() {
        val model = model("deepseek-flash", ModelThinking(ThinkingMode.ON, ThinkingEffort.MEDIUM)).copy(
            thinkingCapability = ThinkingCapability(ThinkingControl.ENABLE_AND_DISABLE,
                setOf(ThinkingEffort.MEDIUM), CapabilitySource.USER_CONFIRMED),
            thinkingDialectOverride = ThinkingParameterDialect.OPENAI_REASONING_EFFORT
        )
        val result = ThinkingRequestAdapter.adapt(provider("https://api.deepseek.com"), model, ModelUse.CHAT)
        assertTrue(result is ThinkingRequestAdaptation.Ready)
        result as ThinkingRequestAdaptation.Ready
        assertEquals(ThinkingParameterDialect.OPENAI_REASONING_EFFORT, result.dialect)
        assertEquals(listOf(ThinkingParameterSnapshot("reasoning_effort", "medium")), result.actualParameters)

        val unknownEffort = ThinkingRequestAdapter.adapt(
            provider("https://api.deepseek.com"), model.copy(thinking = ModelThinking(ThinkingMode.ON, ThinkingEffort.HIGH)), ModelUse.CHAT
        )
        assertEquals(ThinkingFailure.EFFORT_UNSUPPORTED, (unknownEffort as ThinkingRequestAdaptation.Unsupported).failure)
    }

    @Test fun `OpenAI effort 方言的 ON 需要明确档位 OFF 使用 none`() {
        val p = provider("https://custom.example/v1")
        val base = model("custom-reasoner", ModelThinking(ThinkingMode.ON)).copy(
            thinkingCapability = ThinkingCapability(ThinkingControl.ENABLE_AND_DISABLE,
                setOf(ThinkingEffort.LOW, ThinkingEffort.HIGH), CapabilitySource.USER_CONFIRMED),
            thinkingDialectOverride = ThinkingParameterDialect.OPENAI_REASONING_EFFORT
        )
        assertTrue(ThinkingRequestAdapter.adapt(p, base, ModelUse.CHAT) is ThinkingRequestAdaptation.Unsupported)
        val off = ThinkingRequestAdapter.adapt(p, base.copy(thinking = ModelThinking(ThinkingMode.OFF)), ModelUse.CHAT)
            as ThinkingRequestAdaptation.Ready
        assertEquals(listOf(ThinkingParameterSnapshot("reasoning_effort", "none")), off.actualParameters)
    }

    private fun provider(url: String) = ModelProvider("provider", "提供商", url, "key-slot", ModelProviderProtocol.OPENAI_COMPATIBLE)
    private fun model(id: String, thinking: ModelThinking) = ModelDefinition(
        id = "model", providerId = "provider", displayName = id, requestModelId = id, thinking = thinking
    )
}
