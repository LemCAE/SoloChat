package app.solochat.data.db

import app.solochat.core.model.CallConfigSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun CallConfigSnapshot.toEntity(callId: String): CallConfigSnapshotEntity {
    require(callId.isNotBlank() && version > 0 && providerId.isNotBlank() && modelId.isNotBlank() && requestModelId.isNotBlank()) {
        "调用配置快照身份无效"
    }
    require(actualThinkingParameters.map { it.name }.distinct().size == actualThinkingParameters.size) {
        "调用思考参数名称重复"
    }
    val parameters = JsonObject(actualThinkingParameters.associate { item ->
        item.name to JsonPrimitive(item.value)
    }).toString()
    return CallConfigSnapshotEntity(
        callId = callId,
        version = version,
        providerId = providerId,
        providerName = providerName,
        modelId = modelId,
        modelDisplayName = modelDisplayName,
        requestModelId = requestModelId,
        use = use.name,
        selectionSource = selectionSource.name,
        thinkingMode = thinking.mode.name,
        thinkingEffort = thinking.effort?.name,
        thinkingSource = thinkingSource.name,
        actualThinkingParametersJson = parameters
    )
}
