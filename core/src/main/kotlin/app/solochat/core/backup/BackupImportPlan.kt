package app.solochat.core.backup

import app.solochat.core.model.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
    operationImportId: String? = null, mediaIdMapping: Map<String, String> = emptyMap(),
    newId: () -> String = { UUID.randomUUID().toString() }): BackupImportPlan {
    val original = archive.data
    validateBackup(original)
    require(archive.manifest.format == "solochat-backup" && archive.manifest.version in 1..4 &&
        archive.manifest.excluded == BACKUP_EXCLUDED) { "不支持的备份清单" }
    val counts = when (archive.manifest.version) { 1 -> original.counts(); 2 -> original.countsV2(); 3 -> original.countsV3(); else -> original.countsV4() }
    require(archive.manifest.counts == counts) { "备份清单数量不一致" }
    require(listOf(target.lastMessageId, target.lastEventId, target.lastUsageId).all { it >= 0 }) { "目标主键起点无效" }
    val generated = mutableSetOf<String>()
    val oldStringIds = (original.conversations.map { it.id } + original.personas.map { it.id } +
        original.memories.map { it.id } + original.summaries.map { it.id } + original.revisions.map { it.id } +
        original.revisions.flatMap { it.segments }.map { it.id } + original.calls.map { it.record.id } +
        original.stickers.userStickers.map { it.id } + original.stickers.personaStickers.map { it.id }).toSet()
    fun fresh(): String = newId().also {
        require(it.isNotBlank() && it !in oldStringIds && generated.add(it)) { "副本 ID 冲突" }
    }
    fun <T> mapping(rows: List<T>, key: (T) -> String) = rows.associate { key(it) to fresh() }
    fun numeric(ids: List<Long>, last: Long): Map<Long, Long> {
        require(last <= Long.MAX_VALUE - ids.size) { "目标主键已耗尽" }
        return ids.sorted().mapIndexed { index, id -> id to last + index + 1 }.toMap()
    }
    val importId = operationImportId?.also { require(it.isNotBlank() && it !in oldStringIds && generated.add(it)) { "导入操作 ID 冲突" } } ?: fresh()
    val conversations = mapping(original.conversations) { it.id }
    val personas = mapping(original.personas) { it.id }
    val memories = mapping(original.memories) { it.id }
    val summaries = mapping(original.summaries) { it.id }
    val revisions = mapping(original.revisions) { it.id }
    val segments = mapping(original.revisions.flatMap { it.segments }) { it.id }
    val calls = mapping(original.calls) { it.record.id }
    val submissionIds = original.media.submissions.associate { it.id to fresh() }
    val userStickerIds = original.stickers.userStickers.associate { it.id to "user-sticker-${fresh()}" }
    val personaStickerIds = original.stickers.personaStickers.associate { it.id to "persona-sticker-${fresh()}" }
    val messageIds = numeric(original.messages.map { it.id }, target.lastMessageId)
    val eventIds = numeric(original.events.map { it.id }, target.lastEventId)
    val usageIds = numeric(original.usages.map { it.id }, target.lastUsageId)
    require(mediaIdMapping.keys.all { key -> original.media.assets.any { it.id == key } }) { "媒体映射包含未知来源 ID" }
    val mediaIds = original.media.assets.associate { asset -> asset.id to (mediaIdMapping[asset.id] ?: fresh()) }
    val mediaTargetDigests = original.media.assets.groupBy { mediaIds.getValue(it.id) }
    require(mediaTargetDigests.values.all { rows -> rows.map { it.sha256 to it.byteSize }.distinct().size == 1 }) {
        "不同图片内容不能共享副本资源 ID"
    }
    val copiedMediaAssets = original.media.assets.map { asset -> asset.copy(id = mediaIds.getValue(asset.id)) }.distinctBy { it.id }
    val oldAssets = original.media.assets.associate { it.id to it.asModel() }
    val newAssets = copiedMediaAssets.associate { it.id to it.asModel() }
    val newAttachments = original.media.attachments.map { it.copy(messageId = messageIds.getValue(it.messageId), mediaId = mediaIds.getValue(it.mediaId)) }
    val oldMediaByMessage = original.media.attachments.groupBy { it.messageId }.mapValues { (_, rows) ->
        rows.sortedBy { it.ordinal }.map { it to oldAssets.getValue(it.mediaId) }
    }
    val newMediaByMessage = newAttachments.groupBy { it.messageId }.mapValues { (_, rows) ->
        rows.sortedBy { it.ordinal }.map { it to newAssets.getValue(it.mediaId) }
    }
    val operations = original.calls.map { it.record.operationId }.distinct().associateWith { old ->
        calls[old] ?: revisions[old] ?: fresh()
    }
    // 保留缺失来源的原标识，但加本次导入命名空间，绝不匹配本机既有会话/人物卡。
    fun detached(id: String): String = "backup:$importId:missing:$id"
    fun originConversation(id: String) = if (id.isBlank()) "" else conversations[id] ?: detached(id)
    fun owner(id: String) = if (id.isBlank()) "" else personas[id] ?: detached(id)
    fun effectivePersona(id: String): String = resolvePersona(original.personas, id)?.let { personas.getValue(it.id) }
        ?: if (id.isBlank()) "backup:$importId:missing:unassigned" else owner(id)
    fun eventBoundary(id: Long) = if (id == 0L) 0L else eventIds.getValue(id)

    val copiedPersonas = original.personas.map { it.copy(id = personas.getValue(it.id), builtin = false) }
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
    val copiedStickerData = BackupStickerData(
        userStickers = original.stickers.userStickers.map { it.copy(
            id = userStickerIds.getValue(it.id), mediaId = mediaIds.getValue(it.mediaId)) },
        personaStickers = original.stickers.personaStickers.map { sticker -> sticker.copy(
            id = personaStickerIds.getValue(sticker.id), personaId = effectivePersona(sticker.personaId),
            mediaId = sticker.mediaId?.let { mediaIds[it] }) },
        messageStickers = original.stickers.messageStickers.map { sticker -> sticker.copy(
            messageId = messageIds.getValue(sticker.messageId), personaId = effectivePersona(sticker.personaId),
            sourceUserStickerId = sticker.sourceUserStickerId?.let { userStickerIds[it] ?: "backup:$importId:missing-user-sticker:$it" },
            submissionId = submissionIds[sticker.submissionId] ?: "backup:$importId:submission:${sticker.submissionId}") },
        eventStickers = original.stickers.eventStickers.map { sticker -> sticker.copy(
            eventId = eventIds.getValue(sticker.eventId), stickerId = personaStickerIds.getValue(sticker.stickerId),
            personaId = effectivePersona(sticker.personaId), mediaId = mediaIds.getValue(sticker.mediaId),
            assistantMessageId = sticker.assistantMessageId?.let { messageIds.getValue(it) }) },
        turnResults = original.stickers.turnResults.map { result -> result.copy(callId = calls.getValue(result.callId)) }
    )
    val oldConversations = original.conversations.associateBy { it.id }
    val oldHistory = original.messages.sortedBy { it.id }.groupBy { it.conversationId }
    val oldEvents = original.events.sortedBy { it.id }.groupBy { it.conversationId }
    val newMessages = copiedMessages.associateBy { it.id }
    val newEvents = copiedEvents.associateBy { it.id }
    val disabled = linkedMapOf<String, String>()
    val copiedRevisions = original.revisions.map { r ->
        val valid = runCatching {
            validateSegments(r)
            val sourceConversation = oldConversations.getValue(r.conversationId)
            require(resolvePersona(original.personas, sourceConversation.personaId) != null) {
                "源会话人物缺失，摘要保留为未激活历史"
            }
            validateCompressionSource(r.source, sourceConversation, original.personas,
                oldHistory[r.conversationId].orEmpty().filter { it.role != MessageRole.USER || it.sentToModel },
                oldEvents[r.conversationId].orEmpty(), oldMediaByMessage)
        }
        val newConversation = copiedConversations.first { it.id == conversations.getValue(r.conversationId) }
        val sourceMessages = r.source.messages.map { source ->
            val mappedId = messageIds.getValue(source.id)
            if (valid.isSuccess) fingerprintWithAttachments(newMessages.getValue(mappedId), newMediaByMessage[mappedId].orEmpty())
            else source.copy(id = mappedId)
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
                copiedEvents.filter { it.conversationId == newConversation.id }, newMediaByMessage)
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
        },
        conversationModelBindings = original.conversations.map { conversation ->
            val source = original.conversationModelBindings.firstOrNull { it.conversationId == conversation.id }
            val rawOverride = conversation.modelOverride?.takeIf(String::isNotBlank)
            val (providerId, modelId, reason) = when (source?.selectionState) {
                "EXPLICIT" -> Triple(source.providerId, source.modelId, "备份中的模型选择需在本机确认")
                "INHERIT" -> Triple(null, null, "备份中的继承选择需在本机确认")
                "NEEDS_CONFIRMATION" -> Triple(source.providerId, source.modelId,
                    source.pendingReason ?: "备份中的模型选择需在本机确认")
                else -> Triple(null, null, rawOverride?.let { "旧备份模型名需在本机确认：$it" }
                    ?: "旧备份未记录会话模型选择，需在本机确认模型或继承默认值")
            }
            BackupConversationModelBinding(
                conversationId = conversations.getValue(conversation.id),
                selectionState = "NEEDS_CONFIRMATION",
                providerId = providerId,
                modelId = modelId,
                pendingReason = reason,
                migrationSource = "backup:v${archive.manifest.version}:${source?.migrationSource ?: "legacy"}"
            )
        },
        callConfigSnapshots = original.callConfigSnapshots.map { it.copy(callId = calls.getValue(it.callId)) },
        media = BackupMediaData(
            assets = copiedMediaAssets,
            attachments = newAttachments,
            submissions = original.media.submissions.map { row -> row.copy(
                id = submissionIds.getValue(row.id), conversationId = conversations.getValue(row.conversationId),
                messageIds = row.messageIds.map { messageIds.getValue(it) })
            },
            callInputs = original.media.callInputs.map { input ->
                val old = Json.decodeFromString<CallMediaInput>(input.inputJson)
                val copied = old.copy(callId = calls.getValue(input.callId), images = old.images.map { image ->
                    image.copy(messageId = if (image.messageId in messageIds) messageIds.getValue(image.messageId)
                        else -kotlin.math.abs(image.messageId),
                        mediaId = mediaIds[image.mediaId] ?: "missing:${calls.getValue(input.callId)}:${image.mediaId}")
                })
                BackupCallMediaInput(copied.callId, Json.encodeToString(copied), input.recordedAt)
            },
            typedCallInputs = original.media.typedCallInputs.map { input -> input.copy(
                callId = calls.getValue(input.callId), sources = input.sources.map { source -> source.copy(
                    messageId = source.messageId?.let { if (it in messageIds) messageIds.getValue(it) else -kotlin.math.abs(it) },
                    personaStickerId = source.personaStickerId?.let { personaStickerIds[it] ?: "backup:$importId:missing-persona-sticker:$it" },
                    mediaId = mediaIds[source.mediaId] ?: "missing:${calls.getValue(input.callId)}:${source.mediaId}") }) }
        ),
        stickers = copiedStickerData
    )
    validateBackup(data)
    return BackupImportPlan(importId, archive.manifest.id, data, conversations, conversations.values.toSet(), disabled)
}

private fun BackupMediaAsset.asModel() = MediaAsset(id, sha256, mimeType, byteSize, width, height, orientation,
    "objects/$sha256", 0L)
