package app.solochat.data.backup

import app.solochat.core.backup.BackupCall
import app.solochat.core.backup.BackupData
import app.solochat.core.backup.BackupUsage
import app.solochat.core.backup.validateBackup
import app.solochat.core.model.*
import app.solochat.data.db.*
import app.solochat.data.toModel
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
    val calls: List<CallRecordEntity>
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
            }
        )
        validateBackup(data)
        return data
    }
}
