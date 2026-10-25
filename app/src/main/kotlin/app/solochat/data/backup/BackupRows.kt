package app.solochat.data.backup

import app.solochat.core.backup.BackupCall
import app.solochat.core.backup.BackupCallConfigSnapshot
import app.solochat.core.backup.BackupConversationModelBinding
import app.solochat.core.backup.BackupData
import app.solochat.core.backup.BackupUsage
import app.solochat.core.backup.validateBackup
import app.solochat.core.model.*
import app.solochat.data.db.*
import app.solochat.data.toModel
import app.solochat.data.toEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 事务内只取行，转换和文件编码在事务外进行，避免压缩期间占用数据库。 */
internal data class BackupRows(
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val events: List<EventEntity>,
    val personas: List<PersonaEntity>,
    val memories: List<MemoryEntity>,
    val summaries: List<SummaryEntity>,
    val revisions: List<ContextRevisionEntity>,
    val segments: List<ContextSegmentEntity>,
    val usages: List<UsageEntity>,
    val calls: List<CallRecordEntity>,
    val conversationModelBindings: List<ConversationModelBindingEntity> = emptyList(),
    val callConfigSnapshots: List<CallConfigSnapshotEntity> = emptyList()
) {
    fun toBackupData(): BackupData {
        val json = Json { ignoreUnknownKeys = true }
        val revisionIds = revisions.map { it.id }.toSet()
        require(segments.all { it.revisionId in revisionIds }) { "存在无所属版本的摘要分段，无法完整导出" }
        val byRevision = segments.groupBy { it.revisionId }
        val data = BackupData(
            conversations = conversations.map { it.toModel() },
            messages = messages.map { it.toModel() },
            events = events.map { it.toModel() },
            personas = personas.map { it.toModel() },
            memories = memories.map { it.toModel() },
            summaries = summaries.map { it.toModel() },
            revisions = revisions.map { row ->
                ContextRevision(row.id, row.conversationId, row.personaId, row.parentRevisionId,
                    CompressionMethod.valueOf(row.method),
                    json.decodeFromString<CompressionSource>(row.sourceSnapshotJson),
                    byRevision[row.id].orEmpty().sortedBy { it.ordinal }.map {
                        ContextSegment(it.id, it.fromMessageId, it.toMessageId, it.sourceChecksum, it.text, it.model)
                    }, row.createdAt, row.manuallyEdited, row.state)
            },
            usages = usages.map { BackupUsage(it.id, it.conversationId, it.model, it.toModel(), it.at) },
            calls = calls.map { row ->
                val detail = json.decodeFromString<CallRecord>(row.detailJson)
                // 启动收尾会单独更新索引列的状态，JSON 可能仍是 STARTED；以当前落盘状态为准。
                require(detail.id == row.id && detail.operationId == row.operationId &&
                    detail.conversationId == row.conversationId && detail.model == row.model &&
                    detail.kind.name == row.kind && detail.startedAt == row.startedAt) { "调用索引与明细不一致" }
                val status = CallStatus.valueOf(row.status)
                BackupCall(detail.copy(status = status, finishedAt = row.finishedAt,
                    outputIncomplete = detail.outputIncomplete || status == CallStatus.INTERRUPTED), row.usageId)
            },
            conversationModelBindings = conversationModelBindings.map {
                BackupConversationModelBinding(it.conversationId, it.selectionState, it.providerId, it.modelId,
                    it.pendingReason, it.migrationSource)
            },
            callConfigSnapshots = callConfigSnapshots.map {
                BackupCallConfigSnapshot(it.callId, it.version, it.providerId, it.providerName, it.modelId,
                    it.modelDisplayName, it.requestModelId, it.use, it.selectionSource, it.thinkingMode,
                    it.thinkingEffort, it.thinkingSource, it.actualThinkingParametersJson)
            }
        )
        validateBackup(data)
        return data
    }
}

/** 恢复实际使用的实体编码；与导出解码一起核对业务字段及摘要关联。 */
internal fun BackupData.toBackupRows(): BackupRows {
    val json = Json { encodeDefaults = true }
    return BackupRows(conversations.map { it.toEntity() }, messages.map { it.toEntity() },
        events.map { it.toEntity() }, personas.map { it.toEntity() }, memories.map { it.toEntity() },
        summaries.map { it.toEntity() },
        revisions.map { ContextRevisionEntity(it.id, it.conversationId, it.personaId, it.parentRevisionId,
            it.method.name, json.encodeToString(it.source), it.state, it.createdAt, it.manuallyEdited) },
        revisions.flatMap { revision -> revision.segments.mapIndexed { ordinal, segment ->
            ContextSegmentEntity(segment.id, revision.id, ordinal, segment.fromMessageId, segment.toMessageId,
                segment.sourceChecksum, segment.text, segment.model)
        } },
        usages.map { UsageEntity(it.id, it.conversationId, it.model, it.usage.promptTokens,
            it.usage.completionTokens, it.usage.cachedTokens, it.usage.reasoningTokens, it.usage.imageCount,
            it.usage.estimated, it.at) },
        calls.map { (record, usageId) -> CallRecordEntity(record.id, record.operationId, record.conversationId,
            record.model, record.kind.name, record.status.name, record.startedAt, record.finishedAt,
            usageId, json.encodeToString(record)) },
        conversationModelBindings.map {
            ConversationModelBindingEntity(it.conversationId, it.selectionState, it.providerId, it.modelId,
                it.pendingReason, it.migrationSource, null)
        },
        callConfigSnapshots.map {
            CallConfigSnapshotEntity(it.callId, it.version, it.providerId, it.providerName, it.modelId,
                it.modelDisplayName, it.requestModelId, it.use, it.selectionSource, it.thinkingMode,
                it.thinkingEffort, it.thinkingSource, it.actualThinkingParametersJson)
        })
}
