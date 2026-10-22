package app.solochat.core.backup

import app.solochat.core.model.*
import java.util.UUID

/** 数字主键的起点须由写库事务内的现有最大值产生；本模块不接触数据库。 */
data class BackupTargetIds(val lastMessageId: Long, val lastEventId: Long, val lastUsageId: Long)

/**
 * 这是待写入数据，不是已经执行的恢复。
 * Android 适配层必须在同一事务中写入来源标记与待发送隔离，再让会话出现在列表。
 * 导入账目必须标记为历史副本，不能作为新的实际消费再次累计。
 */
data class BackupImportPlan(
    val importId: String,
    val sourceBackupId: String,
    val data: BackupData,
    val conversationIds: Map<String, String>,
    val pausedConversationIds: Set<String>,
    val disabledRevisions: Map<String, String>
)

/** 完整校验原包后，才创建副本身份；源对象一律不修改。 */
fun planBackupImport(archive: BackupArchive, target: BackupTargetIds,
    newId: () -> String = { UUID.randomUUID().toString() }): BackupImportPlan {
    val original = archive.data
    validateBackup(original)
    require(archive.manifest.format == "solochat-backup" && archive.manifest.version == 1 &&
        archive.manifest.excluded == BACKUP_EXCLUDED) { "不支持的备份清单" }
    require(archive.manifest.counts == original.counts()) { "备份清单数量不一致" }
    require(listOf(target.lastMessageId, target.lastEventId, target.lastUsageId).all { it >= 0 }) { "目标主键起点无效" }
    val generated = mutableSetOf<String>()
    val oldStringIds = (original.conversations.map { it.id } + original.personas.map { it.id } +
        original.memories.map { it.id } + original.summaries.map { it.id } + original.revisions.map { it.id } +
        original.revisions.flatMap { it.segments }.map { it.id } + original.calls.map { it.record.id }).toSet()
    fun fresh(): String = newId().also {
        require(it.isNotBlank() && it !in oldStringIds && generated.add(it)) { "副本 ID 冲突" }
    }
    fun <T> mapping(rows: List<T>, key: (T) -> String) = rows.associate { key(it) to fresh() }
    fun numeric(ids: List<Long>, last: Long): Map<Long, Long> {
        require(last <= Long.MAX_VALUE - ids.size) { "目标主键已耗尽" }
        return ids.sorted().mapIndexed { index, id -> id to last + index + 1 }.toMap()
    }
    val importId = fresh()
    val conversations = mapping(original.conversations) { it.id }
    val personas = mapping(original.personas) { it.id }
    val memories = mapping(original.memories) { it.id }
    val summaries = mapping(original.summaries) { it.id }
    val revisions = mapping(original.revisions) { it.id }
    val segments = mapping(original.revisions.flatMap { it.segments }) { it.id }
    val calls = mapping(original.calls) { it.record.id }
    val messageIds = numeric(original.messages.map { it.id }, target.lastMessageId)
    val eventIds = numeric(original.events.map { it.id }, target.lastEventId)
    val usageIds = numeric(original.usages.map { it.id }, target.lastUsageId)
    val operations = original.calls.map { it.record.operationId }.distinct().associateWith { old ->
        calls[old] ?: revisions[old] ?: fresh()
    }
    val fallbackPersonaId = if (original.personas.isEmpty() && original.conversations.isNotEmpty()) fresh() else null
    // 保留缺失来源的原标识，但加本次导入命名空间，绝不匹配本机既有会话/人物卡。
    fun detached(id: String): String = "backup:$importId:missing:$id"
    fun originConversation(id: String) = if (id.isBlank()) "" else conversations[id] ?: detached(id)
    fun owner(id: String) = if (id.isBlank()) "" else personas[id] ?: detached(id)
    fun effectivePersona(id: String): String = resolvePersona(original.personas, id)?.let { personas.getValue(it.id) }
        ?: fallbackPersonaId ?: owner(id)
    fun eventBoundary(id: Long) = if (id == 0L) 0L else eventIds.getValue(id)

    val copiedPersonas = original.personas.map { it.copy(id = personas.getValue(it.id), builtin = false) } +
        listOfNotNull(fallbackPersonaId?.let { Persona(id = it, name = "对方") })
    val copiedConversations = original.conversations.map { c -> c.copy(
        id = conversations.getValue(c.id), title = "${c.title}（导入副本）",
        personaId = effectivePersona(c.personaId), modeSinceEventId = eventBoundary(c.modeSinceEventId),
        activeContextRevisionId = null
    ) }
    val copiedMessages = original.messages.sortedBy { it.id }.map { m -> m.copy(
        id = messageIds.getValue(m.id), conversationId = conversations.getValue(m.conversationId)
    ) }
    val copiedEvents = original.events.sortedBy { it.id }.map { e -> e.copy(
        id = eventIds.getValue(e.id), conversationId = conversations.getValue(e.conversationId),
        delivery = if (e.delivery == DeliveryState.PENDING || e.delivery == DeliveryState.PLAYING)
            DeliveryState.CANCELED else e.delivery
    ) }
    val oldConversations = original.conversations.associateBy { it.id }
    val oldHistory = original.messages.sortedBy { it.id }.groupBy { it.conversationId }
    val oldEvents = original.events.sortedBy { it.id }.groupBy { it.conversationId }
    val newMessages = copiedMessages.associateBy { it.id }
    val newEvents = copiedEvents.associateBy { it.id }
    val disabled = linkedMapOf<String, String>()
    val copiedRevisions = original.revisions.map { r ->
        val valid = runCatching {
            validateSegments(r)
            validateCompressionSource(r.source, oldConversations.getValue(r.conversationId), original.personas,
                oldHistory[r.conversationId].orEmpty().filter { it.role != MessageRole.USER || it.sentToModel },
                oldEvents[r.conversationId].orEmpty())
        }
        val newConversation = copiedConversations.first { it.id == conversations.getValue(r.conversationId) }
        val sourceMessages = r.source.messages.map { source ->
            val mappedId = messageIds.getValue(source.id)
            if (valid.isSuccess) fingerprintOf(newMessages.getValue(mappedId)) else source.copy(id = mappedId)
        }
        val sourceEvents = r.source.events.map { source ->
            val mappedId = eventIds.getValue(source.id)
            if (valid.isSuccess) fingerprintOf(newEvents.getValue(mappedId)) else source.copy(id = mappedId)
        }
        val source = r.source.copy(conversationId = newConversation.id,
            declaredPersonaId = newConversation.personaId,
            // 失效来源保持明确无效，不能通过重映射把旧摘要“洗成有效”。
            personaChecksum = if (valid.isSuccess) personaChecksum(copiedPersonas, newConversation.personaId) else "",
            parentRevisionId = r.parentRevisionId?.let { revisions.getValue(it) },
            messages = sourceMessages, events = sourceEvents)
        val copied = r.copy(id = revisions.getValue(r.id), conversationId = newConversation.id,
            personaId = effectivePersona(r.personaId), parentRevisionId = source.parentRevisionId, source = source,
            segments = r.segments.map { segment ->
                val from = r.source.messages.indexOfFirst { it.id == segment.fromMessageId }
                val to = r.source.messages.indexOfFirst { it.id == segment.toMessageId }
                segment.copy(id = segments.getValue(segment.id),
                    fromMessageId = messageIds.getValue(segment.fromMessageId), toMessageId = messageIds.getValue(segment.toMessageId),
                    sourceChecksum = if (valid.isSuccess) checksumOf(sourceMessages.subList(from, to + 1).joinToString("|") { it.checksum })
                        else segment.sourceChecksum)
            })
        if (valid.isFailure) disabled[copied.id] = valid.exceptionOrNull()?.message ?: "摘要来源已失效"
        else {
            validateSegments(copied)
            validateCompressionSource(copied.source, newConversation, copiedPersonas,
                copiedMessages.filter { it.conversationId == newConversation.id && (it.role != MessageRole.USER || it.sentToModel) },
                copiedEvents.filter { it.conversationId == newConversation.id })
        }
        copied
    }
    val data = original.copy(
        conversations = copiedConversations.map { c ->
            val old = original.conversations.first { conversations.getValue(it.id) == c.id }
            val active = old.activeContextRevisionId?.let { revisions.getValue(it) }
            c.copy(activeContextRevisionId = active?.takeIf { it !in disabled &&
                copiedRevisions.first { r -> r.id == it }.state == "COMPLETE" })
        },
        messages = copiedMessages, events = copiedEvents, personas = copiedPersonas,
        memories = original.memories.map { it.copy(id = memories.getValue(it.id),
            personaId = owner(it.personaId), sourceConversationId = originConversation(it.sourceConversationId)) },
        summaries = original.summaries.map { it.copy(id = summaries.getValue(it.id),
            conversationId = conversations.getValue(it.conversationId), fromEventId = eventBoundary(it.fromEventId),
            toEventId = eventBoundary(it.toEventId)) },
        revisions = copiedRevisions,
        usages = original.usages.sortedBy { it.id }.map { it.copy(id = usageIds.getValue(it.id),
            conversationId = originConversation(it.conversationId)) },
        calls = original.calls.map { wrapper ->
            val call = wrapper.record
            val missingConversation = call.conversationId !in conversations
            wrapper.copy(usageId = wrapper.usageId?.let { usageIds.getValue(it) }, record = call.copy(
                id = calls.getValue(call.id), operationId = operations.getValue(call.operationId),
                conversationId = originConversation(call.conversationId),
                contextMessageIds = call.contextMessageIds.map { if (missingConversation) -kotlin.math.abs(it) else messageIds.getValue(it) },
                status = if (call.status == CallStatus.STARTED) CallStatus.INTERRUPTED else call.status,
                outputIncomplete = call.outputIncomplete || call.status == CallStatus.STARTED,
                failureKind = if (call.status == CallStatus.STARTED) "导入时原调用未结束" else call.failureKind
            ))
        })
    validateBackup(data)
    return BackupImportPlan(importId, archive.manifest.id, data, conversations, conversations.values.toSet(), disabled)
}
