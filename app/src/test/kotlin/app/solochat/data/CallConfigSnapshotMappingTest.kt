package app.solochat.data

import app.solochat.core.model.*
import app.solochat.data.db.toEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CallConfigSnapshotMappingTest {
    @Test fun `Room快照映射保留思考参数但不包含连接凭据`() {
        val snapshot = CallConfigSnapshot(
            version = 1,
            providerId = "provider-id",
            providerName = "提供商",
            modelId = "stable-model-id",
            modelDisplayName = "模型",
            requestModelId = "request-model",
            use = ModelUse.CHAT,
            selectionSource = SelectionSource.CONVERSATION,
            thinking = ModelThinking(ThinkingMode.ON, app.solochat.core.llm.ThinkingEffort.HIGH),
            thinkingSource = ThinkingSource.MODEL_PREFERENCE,
            actualThinkingParameters = listOf(
                ThinkingParameterSnapshot("thinking.type", "enabled"),
                ThinkingParameterSnapshot("reasoning_effort", "high")
            )
        )
        val entity = snapshot.toEntity("call-id")
        val parameters = Json.parseToJsonElement(entity.actualThinkingParametersJson).jsonObject
        assertEquals("call-id", entity.callId)
        assertEquals("stable-model-id", entity.modelId)
        assertEquals("request-model", entity.requestModelId)
        assertEquals("ON", entity.thinkingMode)
        assertEquals("HIGH", entity.thinkingEffort)
        assertEquals("enabled", parameters.getValue("thinking.type").jsonPrimitive.content)
        assertEquals("high", parameters.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(entity.toString().contains("apiKey"))
        assertFalse(entity.toString().contains("secret"))
    }

    @Test fun `Room快照身份无效时拒绝映射`() {
        val invalid = CallConfigSnapshot(1, "", "provider", "model", "model", "model",
            ModelUse.CHAT, SelectionSource.GLOBAL_DEFAULT, ModelThinking(), ThinkingSource.MODEL_PREFERENCE)
        assertTrue(runCatching { invalid.toEntity("call") }.isFailure)
    }
}
