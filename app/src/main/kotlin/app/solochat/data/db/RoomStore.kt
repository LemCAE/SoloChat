package app.solochat.data.db

import app.solochat.core.model.Conversation
import app.solochat.core.model.historySearchHit
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import app.solochat.core.model.Usage
import app.solochat.core.model.CallRecord
import app.solochat.core.model.CallStatus
import app.solochat.core.model.CallConfigSnapshot
import app.solochat.core.model.ChatMode
import app.solochat.core.model.ContextRevision
import app.solochat.core.model.ContextSegment
import app.solochat.core.model.CompressionSource
import app.solochat.core.model.CompressionMethod
import app.solochat.core.model.validateSegments
import app.solochat.core.model.validateCompressionSource
import app.solochat.core.model.InputSubmission
import app.solochat.core.model.SubmissionText
import app.solochat.core.model.UserUtterance
import app.solochat.core.model.MediaAsset
import app.solochat.core.model.MediaDraft
import app.solochat.core.model.MessageAttachment
import app.solochat.core.model.CallMediaInput
import app.solochat.core.agent.CompressionHistory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.solochat.core.ports.EventSink
import app.solochat.core.ports.StoragePort
import app.solochat.core.protocol.DegradedTurn
import app.solochat.data.toEntity
import app.solochat.data.toModel
import androidx.room.withTransaction

/**
 * [StoragePort] 的 Room 实现，同时提供播出状态的写入（[EventSink]）。
 *
 * 一条重要的性能纪律（从 QQ-agent 的经验搬来的）：**不要把重活放在用户发消息的路径上**。
 * 所以 [`rhythmSamples`] 这类统计只取最近 N 条，而不是全表扫描；
 * 装配上下文时也只按 limit 取最近的消息。
 */
class RoomStore(private val db: SoloChatDatabase) : StoragePort, EventSink {
    suspend fun p4TestSubject(requestId: String): P4TestSubjectEntity? = db.stickers().testSubject(requestId)

    suspend fun p4TestCommand(id: String): ClientCommandEntity? = db.stickers().testCommand(id)

    suspend fun claimP4TestCommand(row: ClientCommandEntity): Boolean = db.withTransaction {
        val existing = db.stickers().testCommand(row.id)
        if (existing != null) {
            check(existing.conversationId == row.conversationId && existing.payloadChecksum == row.payloadChecksum) {
                "相同 requestId 不能对应不同命令"
            }
            false
        } else {
            db.stickers().insertTestCommand(row)
            true
        }
    }

    suspend fun finishP4TestCommand(id: String, accepted: Boolean, resultJson: String) = db.withTransaction {
        val row = checkNotNull(db.stickers().testCommand(id)) { "测试命令认领记录不存在" }
        db.stickers().updateTestCommand(row.copy(status = if (accepted) "DONE" else "REJECTED", resultJson = resultJson))
    }

    suspend fun createP4TestSubject(requestId: String, mediaId: String, at: Long): P4TestSubjectEntity = db.withTransaction {
        require(requestId.isNotBlank() && requestId.length <= 80) { "requestId 需为 1～80 个字符" }
        db.stickers().testSubject(requestId)?.let { existing ->
            check(existing.mediaId == mediaId) { "相同 requestId 已绑定其他测试图片" }
            return@withTransaction existing
        }
        val asset = checkNotNull(db.media().asset(mediaId)) { "合成图片原件不存在" }
        check(db.stickers().userStickerByMedia(mediaId) == null) { "合成测试图片已属于既有收藏，拒绝复用" }
        val suffix = java.util.UUID.randomUUID().toString()
        val personaId = "p4-test-persona-$suffix"
        val conversationId = "p4-test-conversation-$suffix"
        val stickerId = "user-sticker-$suffix"
        val title = "TEST-P4-${requestId.take(24)}"
        val persona = Persona(personaId, title, identity = "仅供 P4 合成测试", voice = "")
        val conversation = Conversation(conversationId, title = title, personaId = personaId, createdAt = at, updatedAt = at)
        db.personas().upsert(persona.toEntity())
        db.conversations().upsert(conversation.toEntity())
        db.stickers().insertUserSticker(UserStickerEntity(stickerId, asset.id, at, db.stickers().nextUserSortOrder()))
        P4TestSubjectEntity(requestId, personaId, conversationId, stickerId, asset.id, at).also {
            db.stickers().insertTestSubject(it)
        }
    }

    override suspend fun timelineEntry(conversationId: String, key: app.solochat.core.model.TimelineKey): app.solochat.core.model.TimelineEntry? =
        when (key.source) {
            app.solochat.core.model.TimelineSource.MESSAGE -> app.solochat.core.model.timelineEntries(emptyList(),
                db.messages().timelineRows(conversationId, listOf(key.id)).map { it.toModel() }).singleOrNull()
            app.solochat.core.model.TimelineSource.EVENT -> app.solochat.core.model.timelineEntries(
                db.events().timelineRows(conversationId, listOf(key.id)).map { it.toModel() }, emptyList()).singleOrNull()
        }

    override suspend fun timelineContext(conversationId: String, key: app.solochat.core.model.TimelineKey,
        radius: Int): app.solochat.core.model.TimelineContext? = db.withTransaction {
        super<StoragePort>.timelineContext(conversationId, key, radius)
    }

    override suspend fun timelineFollowingPage(conversationId: String, after: app.solochat.core.model.TimelinePosition,
        limit: Int): app.solochat.core.model.TimelineFollowingPage = db.withTransaction {
        require(limit in 1..200)
        var cursor = after
        val found = mutableListOf<app.solochat.core.model.TimelineEntry>()
        while (found.size <= limit) {
            val candidates = db.events().timelineFollowingCandidates(conversationId, cursor.at, cursor.sourceOrder, cursor.id, limit + 1)
            if (candidates.isEmpty()) break
            val events = db.events().timelineRows(conversationId, candidates.filter { it.sourceOrder == 2 }.map { it.id })
            val messages = db.messages().timelineRows(conversationId, candidates.filter { it.sourceOrder != 2 }.map { it.id })
            found += app.solochat.core.model.timelineEntries(events.map { it.toModel() }, messages.map { it.toModel() })
            val last = candidates.last()
            cursor = app.solochat.core.model.TimelinePosition(last.at, last.sourceOrder, last.id)
            if (candidates.size < limit + 1) break
        }
        app.solochat.core.model.TimelineFollowingPage(found.take(limit), found.size > limit)
    }

    override suspend fun searchHistory(conversationId: String?, query: String,
        before: app.solochat.core.model.TimelinePosition?, limit: Int): app.solochat.core.model.HistorySearchPage = db.withTransaction {
        require(limit in 1..200)
        val term = query.trim()
        if (term.isEmpty()) return@withTransaction app.solochat.core.model.HistorySearchPage(emptyList(), false)
        val pattern = app.solochat.core.model.historySearchPattern(term)
        var cursor = before
        val found = mutableListOf<app.solochat.core.model.HistorySearchHit>()
        // 每批有界，越过隐藏内容及仅存储标记命中的候选，直到凑够一页和一个前瞻项。
        while (found.size <= limit) {
            val candidates = db.events().searchCandidates(conversationId, pattern, cursor?.at, cursor?.sourceOrder, cursor?.id, limit + 1)
            if (candidates.isEmpty()) break
            val events = db.events().searchRows(candidates.filter { it.sourceOrder == 2 }.map { it.id })
            val messages = db.messages().searchRows(candidates.filter { it.sourceOrder != 2 }.map { it.id })
            val origins = candidates.associateBy { it.conversationId }
            found += app.solochat.core.model.timelineEntries(events.map { it.toModel() }, messages.map { it.toModel() })
                .asReversed().map { it.historySearchHit() }.filter { it.text.contains(term) }.map { hit ->
                    val origin = origins.getValue(hit.conversationId)
                    hit.copy(conversationTitle = origin.conversationTitle, personaName = origin.personaName, archived = origin.archived)
                }
            val last = candidates.last()
            cursor = app.solochat.core.model.TimelinePosition(last.at, last.sourceOrder, last.id)
            if (candidates.size < limit + 1) break
        }
        app.solochat.core.model.HistorySearchPage(found.take(limit), found.size > limit)
    }

    override suspend fun timelinePage(conversationId: String, before: app.solochat.core.model.TimelinePosition?,
        limit: Int): app.solochat.core.model.TimelinePage = db.withTransaction {
        require(limit in 1..200)
        var cursor = before
        val found = mutableListOf<app.solochat.core.model.TimelineEntry>()
        // 候选可能是隐藏/空白/未出口事件。每批有界，继续跨过这些行直到凑够一页和一个前瞻项。
        while (found.size <= limit) {
            val candidates = db.events().timelineCandidates(conversationId, cursor?.at, cursor?.sourceOrder, cursor?.id, limit + 1)
            if (candidates.isEmpty()) break
            val events = db.events().timelineRows(conversationId, candidates.filter { it.sourceOrder == 2 }.map { it.id })
            val messages = db.messages().timelineRows(conversationId, candidates.filter { it.sourceOrder != 2 }.map { it.id })
            found += app.solochat.core.model.timelineEntries(events.map { it.toModel() }, messages.map { it.toModel() }).asReversed()
            val last = candidates.last()
            cursor = app.solochat.core.model.TimelinePosition(last.at, last.sourceOrder, last.id)
            if (candidates.size < limit + 1) break
        }
        app.solochat.core.model.TimelinePage(found.take(limit).reversed(), found.size > limit)
    }

    override suspend fun unpushedUserCount(conversationId: String): Int = db.messages().unpushedCount(conversationId)

    override suspend fun unpushedMediaCount(conversationId: String): Int = db.media().unpushedAttachmentCount(conversationId)

    override suspend fun timelineOf(conversationId: String): List<app.solochat.core.model.TimelineEntry> = db.withTransaction {
        app.solochat.core.model.timelineEntries(
            db.events().allOf(conversationId).map { it.toModel() },
            db.messages().timelineOf(conversationId).map { it.toModel() })
    }

    private val callJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 连发节奏统计的取样条数：够学出手速，又不至于在热路径上做重活。 */
    private val rhythmSampleLimit = 40

    override suspend fun isBackupPaused(conversationId: String) = db.backup().paused(conversationId)
    override suspend fun resumeBackup(conversationId: String) = db.backup().resume(conversationId)

    suspend fun pausedBackupConversations(): List<Conversation> =
        db.backup().pausedConversations().map { it.toModel() }
    suspend fun allPersonas(): List<Persona> = db.personas().all().map { it.toModel() }

    suspend fun conversationModelBinding(conversationId: String): ConversationModelBindingEntity? =
        db.modelConfigs().conversationBinding(conversationId)

    suspend fun saveConversationModelBinding(entity: ConversationModelBindingEntity) =
        db.modelConfigs().saveUserConversationBinding(entity)

    suspend fun repairPausedBackupPersona(id: String, personaId: String, at: Long) = db.withTransaction {
        require(personaId.isNotBlank() && db.personas().byId(personaId) != null) { "请选择仍然存在的人物卡" }
        check(db.backup().paused(id)) { "仅可修复仍暂停的恢复副本" }
        val current = checkNotNull(db.conversations().byId(id)) { "会话已不存在" }
        check(db.personas().byId(current.personaId) == null || current.personaId == personaId) {
            "该副本已有有效人物归属"
        }
        db.conversations().upsert(current.copy(personaId = personaId, updatedAt = at))
    }

    override suspend fun listConversations(): List<Conversation> =
        db.conversations().all().map { it.toModel() }

    override suspend fun getConversation(id: String): Conversation? =
        db.conversations().byId(id)?.toModel()

    override suspend fun upsertConversation(conversation: Conversation) {
        db.conversations().upsert(conversation.toEntity())
    }
    override suspend fun touchConversation(id: String, at: Long) = db.conversations().touch(id, at)
    override suspend fun changeMode(id: String, mode: ChatMode, since: Long) = db.conversations().changeMode(id, mode.wire, since)

    suspend fun changeModel(id: String, model: String?, at: Long) {
        require(model == null || model.isNotBlank()) { "模型名称不能为空" }
        checkNotNull(getConversation(id)) { "对话已不存在" }
        db.conversations().setModel(id, model?.trim(), at)
    }

    override suspend fun eventsOf(conversationId: String, fromId: Long): List<Event> =
        db.events().allOf(conversationId)
            .filter { it.id > fromId }
            .map { it.toModel() }

    override suspend fun appendEvent(event: Event): Long =
        db.events().insert(event.toEntity())

    override suspend fun updateEvent(event: Event) {
        db.events().update(event.toEntity())
    }

    // limit ≤ 0 原样透传给 DAO：SQLite 的 `LIMIT -1` 就是"不设上限"，
    // 而 `LIMIT 0` 是"一条都不要"。0（= 不限）已经在 ChatConfig.historyWindow 里
    // 翻成了 -1，所以这里**不要**再补一次 coerceAtLeast(1) —— 那会把"不限"变成"只取一条"。
    override suspend fun recentMessages(conversationId: String, limit: Int): List<Message> =
        db.messages().recentOf(conversationId, limit)
            .sortedBy { it.id }          // DAO 按 id 倒序取，这里翻回正序
            .map { it.toModel() }

    // 与上面只差一个 WHERE：未推送的用户消息不算。见 Ports 里的注释 ——
    // 这条过滤是"不按第二次就不发给模型"的唯一实现点。
    override suspend fun modelVisibleMessages(conversationId: String, limit: Int): List<Message> =
        db.messages().recentForModel(conversationId, limit)
            .sortedBy { it.id }
            .map { it.toModel() }

    override suspend fun unpushedUserMessages(conversationId: String): List<Message> =
        db.messages().unpushedUserOf(conversationId).map { it.toModel() }

    override suspend fun markUserMessagesPushed(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.messages().markPushed(ids)
    }

    override suspend fun appendMessage(message: Message): Long =
        db.messages().insert(message.toEntity())

    override suspend fun appendSubmission(submission: InputSubmission, messages: List<Message>): List<Long> = db.withTransaction {
        val existing = db.media().submission(submission.id)
        if (existing != null) {
            check(existing.conversationId == submission.conversationId && existing.payloadChecksum == submission.payloadChecksum) {
                "提交 ID 已用于不同内容"
            }
            clearDraftForSubmission(submission)
            return@withTransaction callJson.decodeFromString<List<Long>>(existing.messageIdsJson)
        }
        check(messages.isNotEmpty() && messages.all {
            it.conversationId == submission.conversationId && it.role == app.solochat.core.model.MessageRole.USER
        }) { "提交消息归属无效" }
        val encodedText = messages.map { app.solochat.core.model.UserUtterance.decode(it.text) }
            .filter { it.second.isNotBlank() }.map { app.solochat.core.model.SubmissionText(it.first.wire, it.second) }
        check(encodedText == submission.text) { "提交内容与消息正文不一致" }
        val assets = if (submission.mediaIds.isEmpty()) emptyList() else db.media().assets(submission.mediaIds)
        check(assets.map { it.id }.toSet() == submission.mediaIds.toSet()) { "图片资源已失效，请重新选择" }
        val sayIndex = messages.indexOfFirst { app.solochat.core.model.UserUtterance.decode(it.text).first == app.solochat.core.model.EventKind.SAY }
        check(submission.mediaIds.isEmpty() || sayIndex >= 0) { "图片必须附在说话消息上" }
        val ids = messages.map { db.messages().insert(it.toEntity()) }
        if (submission.mediaIds.isNotEmpty()) {
            db.media().insertAttachments(submission.mediaIds.mapIndexed { ordinal, mediaId ->
                MessageAttachmentEntity(ids[sayIndex], ordinal, mediaId)
            })
        }
        db.media().insertSubmission(InputSubmissionEntity(submission.id, submission.conversationId,
            submission.payloadChecksum, callJson.encodeToString(ids), submission.createdAt))
        clearDraftForSubmission(submission)
        ids
    }

    override suspend fun saveMediaDraft(draft: MediaDraft) = db.withTransaction {
        // 提交完成后到达的旧回调不能重新创建已提交快照的草稿行。
        if (draft.submissionId.isNotBlank()) {
            val committed = db.media().submission(draft.submissionId)
            if (committed != null) {
                check(committed.conversationId == draft.conversationId) { "提交 ID 属于其他会话" }
                return@withTransaction
            }
        }
        check(draft.mediaIds.size <= 4) { "图片草稿数量超限" }
        if (draft.mediaIds.isNotEmpty()) {
            check(db.media().assets(draft.mediaIds).map { it.id }.toSet() == draft.mediaIds.toSet()) { "草稿引用的图片不存在" }
        }
        val existing = db.media().draft(draft.conversationId)?.let { callJson.decodeFromString<MediaDraft>(it.draftJson) }
        if (existing != null) {
            if (existing.version > draft.version) return@withTransaction
            if (existing.version == draft.version) {
                check(existing == draft) { "相同草稿版本对应不同内容" }
                return@withTransaction
            }
        }
        db.media().saveDraft(MediaDraftEntity(draft.conversationId, callJson.encodeToString(draft), draft.updatedAt))
    }

    override suspend fun mediaDraft(conversationId: String): MediaDraft? =
        db.media().draft(conversationId)?.let { callJson.decodeFromString<MediaDraft>(it.draftJson) }

    override suspend fun clearMediaDraft(conversationId: String) = db.media().clearDraft(conversationId)

    private suspend fun clearDraftForSubmission(submission: InputSubmission) {
        if (submission.draftVersion < 0) return
        val row = db.media().draft(submission.conversationId) ?: return
        val draft = callJson.decodeFromString<MediaDraft>(row.draftJson)
        if (draft.version == submission.draftVersion && draft.submissionId == submission.id) {
            db.media().clearDraft(submission.conversationId)
        }
    }

    override suspend fun mediaAsset(mediaId: String): MediaAsset? = db.media().asset(mediaId)?.toCore()

    override suspend fun mediaAssetsForMessages(messageIds: List<Long>): Map<Long, List<Pair<MessageAttachment, MediaAsset>>> {
        if (messageIds.isEmpty()) return emptyMap()
        val attachments = messageIds.distinct().chunked(400).flatMap { db.media().attachments(it) }
        if (attachments.isEmpty()) return emptyMap()
        val assets = db.media().assets(attachments.map { it.mediaId }.distinct()).associate { it.id to it.toCore() }
        return attachments.mapNotNull { row -> assets[row.mediaId]?.let {
            row.messageId to (MessageAttachment(row.messageId, row.ordinal, row.mediaId) to it)
        } }.groupBy({ it.first }, { it.second })
    }

    /** 原件入库先做摘要去重；文件存在性由 MediaLibrary 在事务外核验，库只发布完整元数据。 */
    suspend fun publishMediaAsset(asset: MediaAsset): MediaAsset = db.withTransaction {
        val previous = db.media().assetByDigest(asset.sha256, asset.byteSize)
        if (previous != null) return@withTransaction previous.toCore()
        db.media().insertAsset(asset.toEntity())
        asset
    }

    suspend fun mediaAssetByDigest(sha256: String, byteSize: Long): MediaAsset? =
        db.media().assetByDigest(sha256, byteSize)?.toCore()

    override suspend fun userStickers(): List<app.solochat.core.model.UserSticker> =
        db.stickers().userStickers().map { it.toCore() }

    override suspend fun addUserSticker(id: String, mediaId: String, at: Long): app.solochat.core.model.UserSticker = db.withTransaction {
        val asset = checkNotNull(db.media().asset(mediaId)) { "图片资源不存在" }
        db.stickers().userStickerByMedia(asset.id)?.let { return@withTransaction it.toCore() }
        val row = UserStickerEntity(id, asset.id, at, db.stickers().nextUserSortOrder())
        db.stickers().insertUserSticker(row)
        row.toCore()
    }

    override suspend fun removeUserSticker(id: String) {
        db.stickers().deleteUserSticker(id)
    }

    override suspend fun reorderUserSticker(id: String, sortOrder: Long) {
        check(db.stickers().reorderUserSticker(id, sortOrder) == 1) { "我的表情已不存在" }
    }

    override suspend fun personaStickers(personaId: String): List<app.solochat.core.model.PersonaSticker> =
        db.stickers().personaStickers(personaId).map { it.toCore() }

    override suspend fun addPersonaSticker(id: String, personaId: String, mediaId: String, at: Long): app.solochat.core.model.PersonaSticker = db.withTransaction {
        check(db.personas().byId(personaId) != null) { "人物卡已不存在" }
        val asset = checkNotNull(db.media().asset(mediaId)) { "图片资源不存在" }
        val existing = db.stickers().personaStickerByContent(personaId, asset.sha256, asset.byteSize)
        if (existing != null) {
            if (existing.status == "REMOVED") {
                db.stickers().updatePersonaSticker(existing.copy(mediaId = asset.id, status = "ACTIVE", updatedAt = at))
                return@withTransaction existing.copy(mediaId = asset.id, status = "ACTIVE", updatedAt = at).toCore()
            }
            return@withTransaction existing.toCore()
        }
        val created = app.solochat.core.model.PersonaSticker(id, personaId, asset.sha256, asset.byteSize,
            asset.id, createdAt = at, updatedAt = at)
        db.stickers().insertPersonaSticker(created.toEntity())
        created
    }

    override suspend fun editPersonaSticker(sticker: app.solochat.core.model.PersonaSticker, lockFields: Set<String>) = db.withTransaction {
        val row = checkNotNull(db.stickers().personaSticker(sticker.id)) { "人物表情已不存在" }
        check(row.personaId == sticker.personaId && row.status == "ACTIVE") { "人物表情归属或状态已变化" }
        val current = row.toCore()
        check((sticker.name == current.name || current.nameVersion == sticker.nameVersion) &&
            (sticker.description == current.description || current.descriptionVersion == sticker.descriptionVersion) &&
            (sticker.tags == current.tags || current.tagsVersion == sticker.tagsVersion) &&
            (sticker.usageContexts == current.usageContexts || current.usageContextsVersion == sticker.usageContextsVersion)) {
            "人物表情字段已变化或已锁定，请刷新后重试"
        }
        val updated = current.copy(
            name = sticker.name.takeIf { it != current.name && current.nameVersion == sticker.nameVersion } ?: current.name,
            description = sticker.description.takeIf { it != current.description && current.descriptionVersion == sticker.descriptionVersion } ?: current.description,
            tags = sticker.tags.takeIf { it != current.tags && current.tagsVersion == sticker.tagsVersion } ?: current.tags,
            usageContexts = sticker.usageContexts.takeIf { it != current.usageContexts && current.usageContextsVersion == sticker.usageContextsVersion } ?: current.usageContexts,
            nameLocked = current.nameLocked || ("name" in lockFields && sticker.name != current.name && current.nameVersion == sticker.nameVersion),
            descriptionLocked = current.descriptionLocked || ("description" in lockFields && sticker.description != current.description && current.descriptionVersion == sticker.descriptionVersion),
            tagsLocked = current.tagsLocked || ("tags" in lockFields && sticker.tags != current.tags && current.tagsVersion == sticker.tagsVersion),
            usageContextsLocked = current.usageContextsLocked || ("usageContexts" in lockFields && sticker.usageContexts != current.usageContexts && current.usageContextsVersion == sticker.usageContextsVersion),
            nameVersion = current.nameVersion + if (sticker.name != current.name && current.nameVersion == sticker.nameVersion) 1 else 0,
            descriptionVersion = current.descriptionVersion + if (sticker.description != current.description && current.descriptionVersion == sticker.descriptionVersion) 1 else 0,
            tagsVersion = current.tagsVersion + if (sticker.tags != current.tags && current.tagsVersion == sticker.tagsVersion) 1 else 0,
            usageContextsVersion = current.usageContextsVersion + if (sticker.usageContexts != current.usageContexts && current.usageContextsVersion == sticker.usageContextsVersion) 1 else 0,
            updatedAt = maxOf(current.updatedAt, sticker.updatedAt)
        )
        db.stickers().updatePersonaSticker(updated.toEntity())
    }

    override suspend fun unlockPersonaStickerField(id: String, field: String) = db.withTransaction {
        val row = checkNotNull(db.stickers().personaSticker(id)) { "人物表情已不存在" }
        val core = row.toCore()
        val next = when (field) {
            "name" -> core.copy(nameLocked = false, nameVersion = core.nameVersion + 1)
            "description" -> core.copy(descriptionLocked = false, descriptionVersion = core.descriptionVersion + 1)
            "tags" -> core.copy(tagsLocked = false, tagsVersion = core.tagsVersion + 1)
            "usageContexts" -> core.copy(usageContextsLocked = false, usageContextsVersion = core.usageContextsVersion + 1)
            else -> error("未知人物表情字段")
        }
        db.stickers().updatePersonaSticker(next.toEntity())
    }

    override suspend fun removePersonaSticker(id: String, at: Long) = db.withTransaction {
        val row = checkNotNull(db.stickers().personaSticker(id)) { "人物表情已不存在" }
        if (row.status == "ACTIVE") db.stickers().updatePersonaSticker(row.copy(status = "REMOVED", collectionEpoch = row.collectionEpoch + 1, updatedAt = maxOf(at, row.updatedAt)))
    }

    override suspend fun appendStickerSubmission(
        submission: InputSubmission,
        leadingMessages: List<Message>,
        message: Message,
        intent: app.solochat.core.model.StickerSubmissionIntent
    ): app.solochat.core.model.StickerSubmissionAppendResult = db.withTransaction {
        require(submission.stickerIntent == intent && submission.conversationId == message.conversationId &&
            submission.mediaIds.size == 1 && submission.draftVersion == -1L) { "表情提交快照无效" }
        db.stickers().testCommand(submission.id)?.takeIf { it.status == "REMOVED" }?.let { removed ->
            check(removed.conversationId == submission.conversationId && removed.payloadChecksum == submission.payloadChecksum) {
                "提交 ID 已用于不同内容"
            }
            val messageId = callJson.decodeFromString<Long>(removed.resultJson)
            return@withTransaction app.solochat.core.model.StickerSubmissionAppendResult(messageId, inserted = false,
                leadingMessagesConsumed = 0)
        }
        val existing = db.media().submission(submission.id)
        if (existing != null) {
            check(existing.conversationId == submission.conversationId && existing.payloadChecksum == submission.payloadChecksum) { "提交 ID 已用于不同内容" }
            val ids = callJson.decodeFromString<List<Long>>(existing.messageIdsJson)
            check(ids.isNotEmpty() && db.stickers().messageSticker(ids.last(), 0)?.submissionId == submission.id) { "表情提交映射不完整" }
            val storedById = db.messages().byIds(ids.dropLast(1)).associateBy { it.id }
            val storedLeading = ids.dropLast(1).mapNotNull { storedById[it]?.toModel() }
            val matchesRetry = storedLeading.size == leadingMessages.size && storedLeading.zip(leadingMessages).all { (stored, pending) ->
                stored.copy(id = 0) == pending
            }
            return@withTransaction app.solochat.core.model.StickerSubmissionAppendResult(ids.last(), inserted = false,
                leadingMessagesConsumed = if (matchesRetry) leadingMessages.size else 0)
        }
        check(!db.backup().paused(submission.conversationId)) { "恢复副本仍处于暂停状态" }
        val conversation = checkNotNull(db.conversations().byId(submission.conversationId)) { "会话已不存在" }
        check(conversation.personaId == intent.personaId && db.personas().byId(intent.personaId) != null) { "会话人物归属无效" }
        check(message.role == app.solochat.core.model.MessageRole.USER && message.text.isBlank() &&
            !message.sentToModel && message.turnId == submission.createdAt) { "表情消息必须是独立的空正文待发消息" }
        check(leadingMessages.all { it.conversationId == submission.conversationId &&
            it.role == app.solochat.core.model.MessageRole.USER && !it.sentToModel }) { "表情前的待处理消息归属无效" }
        val userSticker = checkNotNull(db.stickers().userSticker(intent.userStickerId)) { "我的表情已移除" }
        check(userSticker.mediaId == submission.mediaIds.single()) { "表情收藏与媒体不匹配" }
        val asset = checkNotNull(db.media().asset(userSticker.mediaId)) { "表情原图已丢失" }
        val personaSticker = db.stickers().personaStickerByContent(intent.personaId, asset.sha256, asset.byteSize)
        val epoch = personaSticker?.collectionEpoch ?: 0L
        val leadingIds = leadingMessages.map { db.messages().insert(it.toEntity()) }
        val messageId = db.messages().insert(message.toEntity())
        db.media().insertAttachments(listOf(MessageAttachmentEntity(messageId, 0, asset.id)))
        db.stickers().insertMessageSticker(MessageStickerEntity(messageId, 0, intent.personaId, asset.sha256,
            asset.byteSize, epoch, userSticker.id, submission.id))
        db.media().insertSubmission(InputSubmissionEntity(submission.id, submission.conversationId,
            submission.payloadChecksum, callJson.encodeToString(leadingIds + messageId), submission.createdAt))
        app.solochat.core.model.StickerSubmissionAppendResult(messageId, inserted = true,
            leadingMessagesConsumed = leadingMessages.size)
    }

    override suspend fun messageStickers(messageIds: List<Long>): List<app.solochat.core.model.MessageSticker> =
        if (messageIds.isEmpty()) emptyList() else messageIds.distinct().chunked(400)
            .flatMap { db.stickers().messageStickers(it) }.map { it.toCore() }

    override suspend fun removeUnpushedStickerMessage(messageId: Long): Boolean = db.withTransaction {
        val message = db.messages().byIds(listOf(messageId)).singleOrNull() ?: return@withTransaction false
        val sticker = db.stickers().messageSticker(messageId, 0)
        check(message.role == "user" && !message.sentToModel && sticker != null) {
            "只能移除尚未推送的表情消息"
        }
        val receipt = db.media().submission(sticker.submissionId)
        val originalMessageIds = receipt?.let { callJson.decodeFromString<List<Long>>(it.messageIdsJson) }.orEmpty()
        val leadingIds = originalMessageIds.filterNot { it == messageId }
        if (receipt != null) db.media().deleteSubmission(receipt.id)
        if (leadingIds.isNotEmpty()) {
            val leadingRows = db.messages().byIds(leadingIds).sortedBy { it.id }
            leadingRows.chunked(3).forEachIndexed { index, chunk ->
                val texts = chunk.map { row ->
                    val (kind, text) = UserUtterance.decode(row.text)
                    SubmissionText(kind.wire, text)
                }
                val id = "adjust-${app.solochat.core.model.checksumOf("${sticker.submissionId}:$index")}"
                val replacement = InputSubmission(id, message.conversationId, texts, emptyList(), chunk.first().createdAt)
                db.media().insertSubmission(InputSubmissionEntity(id, message.conversationId, replacement.payloadChecksum,
                    callJson.encodeToString(chunk.map { it.id }), replacement.createdAt))
            }
        }
        if (receipt != null && db.stickers().testCommand(sticker.submissionId) == null) {
            db.stickers().insertTestCommand(ClientCommandEntity(sticker.submissionId, message.conversationId,
                receipt.payloadChecksum, "REMOVED", callJson.encodeToString(messageId), receipt.createdAt))
        }
        db.stickers().deleteMessageSticker(messageId)
        db.stickers().deleteAttachmentsForMessage(messageId)
        db.messages().deleteUnpushedUserMessage(messageId) == 1
    }

    /** 含图调用首写与原 call_record/配置快照共用一次 Room 事务。 */
    override suspend fun recordCallStartedWithConfigAndMedia(call: CallRecord, snapshot: CallConfigSnapshot, input: CallMediaInput) = db.withTransaction {
        require(call.status == CallStatus.STARTED && call.finishedAt == null && input.callId == call.id) { "图片清单必须随同一调用开始记录" }
        require(input.images.isNotEmpty() && input.images.all { db.media().asset(it.mediaId)?.sha256 == it.sourceSha256 }) { "调用图片清单与本地原件不一致" }
        validateSnapshot(call, snapshot)
        val config = snapshot.toEntity(call.id)
        db.modelConfigs().insertCallWithConfigIfAbsentOrSame(
            CallRecordEntity(call.id, call.operationId, call.conversationId, call.model,
                call.kind.name, call.status.name, call.startedAt, null, null, callJson.encodeToString(call)), config
        )
        val row = CallMediaInputEntity(input.callId, callJson.encodeToString(input), input.recordedAt)
        val existing = db.media().callInput(input.callId)
        if (existing == null) db.media().insertCallInput(row)
        else check(existing == row) { "调用媒体输入清单冲突" }
    }

    suspend fun callMediaInput(callId: String): CallMediaInput? =
        db.media().callInput(callId)?.let { callJson.decodeFromString<CallMediaInput>(it.inputJson) }

    private fun MediaAssetEntity.toCore() = MediaAsset(id, sha256, mimeType, byteSize, width, height, orientation, fileKey, createdAt)
    private fun MediaAsset.toEntity() = MediaAssetEntity(id, sha256, mimeType, byteSize, width, height, orientation, fileKey, createdAt)

    override suspend fun listPersonas(): List<Persona> =
        db.personas().all().map { it.toModel() }

    override suspend fun getPersona(id: String): Persona? =
        if (id.isBlank()) null else db.personas().byId(id)?.toModel()

    override suspend fun upsertPersona(persona: Persona) {
        db.personas().upsert(persona.toEntity())
    }

    override suspend fun deletePersona(id: String) {
        db.withTransaction {
            val persona = db.personas().byId(id) ?: return@withTransaction
            // DAO 里带了 builtin = 0 条件：内置卡不允许删，改成"复制一份再改"
            db.personas().delete(id)
            if (!persona.builtin && db.personas().byId(id) == null) db.stickers().deletePersonaStickers(id)
        }
    }

    override suspend fun listMemories(personaId: String?): List<Memory> =
        db.memories().all(personaId).map { it.toModel() }

    override suspend fun upsertMemory(memory: Memory) {
        db.memories().upsert(memory.toEntity())
    }

    override suspend fun deleteMemory(id: String) {
        db.memories().delete(id)
    }

    override suspend fun summariesOf(conversationId: String): List<SceneSummary> =
        db.summaries().of(conversationId).map { it.toModel() }

    override suspend fun appendSummary(summary: SceneSummary) {
        db.summaries().insert(summary.toEntity())
    }

    override suspend fun recordUsage(
        conversationId: String,
        model: String,
        usage: Usage,
        at: Long
    ) {
        db.usage().insert(
            UsageEntity(
                conversationId = conversationId,
                model = model,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                cachedTokens = usage.cachedTokens,
                reasoningTokens = usage.reasoningTokens,
                imageCount = usage.imageCount,
                estimated = usage.estimated,
                at = at
            )
        )
    }

    /** 调用明细与旧表兼容行在同一事务中关联，重试收尾不重复插入 usage。 */
    override suspend fun recordCall(call: CallRecord, usage: Usage?) = db.withTransaction {
        val old = db.calls().byId(call.id)
        val usageId = old?.usageId ?: usage?.let {
            db.usage().insert(UsageEntity(conversationId = call.conversationId, model = call.model,
                promptTokens = it.promptTokens, completionTokens = it.completionTokens,
                cachedTokens = it.cachedTokens, reasoningTokens = it.reasoningTokens, imageCount = it.imageCount,
                estimated = it.estimated, at = call.finishedAt ?: call.startedAt))
        }
        db.calls().upsert(CallRecordEntity(call.id, call.operationId, call.conversationId, call.model,
            call.kind.name, call.status.name, call.startedAt, call.finishedAt, usageId, callJson.encodeToString(call)))
    }

    /** A-7 首次记账：call_record 与开始时非秘密配置快照同事务写入。 */
    override suspend fun recordCallStartedWithConfig(call: CallRecord, snapshot: CallConfigSnapshot) {
        require(call.status == CallStatus.STARTED && call.finishedAt == null) { "配置快照只能随调用开始记录" }
        require(call.id.isNotBlank())
        validateSnapshot(call, snapshot)
        val config = snapshot.toEntity(call.id)
        db.modelConfigs().insertCallWithConfigIfAbsentOrSame(
            CallRecordEntity(call.id, call.operationId, call.conversationId, call.model,
                call.kind.name, call.status.name, call.startedAt, null, null, callJson.encodeToString(call)),
            config
        )
    }

    private fun validateSnapshot(call: CallRecord, snapshot: CallConfigSnapshot) {
        require(call.model == snapshot.requestModelId) { "调用记录模型与配置快照请求 ID 不一致" }
    }

    suspend fun calls(conversationId: String? = null, from: Long = 0, to: Long = Long.MAX_VALUE): List<CallRecord> =
        db.calls().range(conversationId, from, to).map { row ->
            val record = callJson.decodeFromString<CallRecord>(row.detailJson)
            val status = CallStatus.entries.firstOrNull { it.name == row.status } ?: CallStatus.INTERRUPTED
            record.copy(status = status, outputIncomplete = record.outputIncomplete || status == CallStatus.INTERRUPTED)
        }
    suspend fun legacyUsage(conversationId: String? = null, from: Long = 0, to: Long = Long.MAX_VALUE): List<UsageEntity> =
        db.usage().unlinked(conversationId, from, to)
    suspend fun statistics(conversationId: String? = null, from: Long = 0, to: Long = Long.MAX_VALUE) = db.withTransaction {
        val callRows = db.calls().range(conversationId, from, to)
        val callRecords = callRows.map { row ->
            val record = callJson.decodeFromString<CallRecord>(row.detailJson)
            val status = CallStatus.entries.firstOrNull { it.name == row.status } ?: CallStatus.INTERRUPTED
            record.copy(status = status, outputIncomplete = record.outputIncomplete || status == CallStatus.INTERRUPTED)
        }
        val base = app.solochat.data.partitionStatistics(callRecords, legacyUsage(conversationId, from, to),
            db.backup().importedIds("call").toSet(), db.backup().importedIds("usage").toSet())
        val snapshots = if (callRows.isEmpty()) emptyList()
            else db.modelConfigs().callConfigSnapshots(callRows.map { it.id })
        base.copy(callConfigSnapshots = snapshots.associateBy { it.callId })
    }

    override suspend fun contextRevision(id: String): ContextRevision? = db.withTransaction {
        db.contexts().byId(id)?.let { decodeRevision(it) }
    }
    override suspend fun contextRevisions(conversationId: String): List<ContextRevision> = db.withTransaction {
        db.contexts().ofConversation(conversationId).map { decodeRevision(it) }
    }
    private suspend fun decodeRevision(row: ContextRevisionEntity) = ContextRevision(row.id, row.conversationId,
        row.personaId, row.parentRevisionId, CompressionMethod.valueOf(row.method),
        callJson.decodeFromString<CompressionSource>(row.sourceSnapshotJson),
        db.contexts().segments(row.id).map { ContextSegment(it.id, it.fromMessageId, it.toMessageId,
            it.sourceChecksum, it.text, it.model) }, row.createdAt, row.manuallyEdited, row.state)

    private suspend fun saveRevision(revision: ContextRevision) {
        db.contexts().saveRevision(ContextRevisionEntity(revision.id, revision.conversationId, revision.personaId,
            revision.parentRevisionId, revision.method.name, callJson.encodeToString(revision.source), revision.state,
            revision.createdAt, revision.manuallyEdited))
        db.contexts().clearSegments(revision.id)
        // 旧段在新版本保留正文与来源，行 id 重新生成，不能 REPLACE 掉旧版本的段。
        db.contexts().saveSegments(revision.segments.mapIndexed { index, segment -> ContextSegmentEntity(
            "${revision.id}:$index", revision.id, index, segment.fromMessageId, segment.toMessageId,
            segment.sourceChecksum, segment.text, segment.model) })
    }
    override suspend fun saveContextDraft(revision: ContextRevision) = db.withTransaction {
        checkNotNull(getConversation(revision.conversationId)) { "会话已不存在" }
        check(revision.state == "DRAFT") { "只能保存摘要草稿" }
        check(db.contexts().byId(revision.id)?.state.let { it == null || it == "DRAFT" }) { "已完成版本不能原地编辑" }
        validateSegments(revision)
        saveRevision(revision)
    }
    override suspend fun activateContext(revision: ContextRevision, at: Long) = db.withTransaction {
        val conversation = checkNotNull(getConversation(revision.conversationId)) { "会话不存在" }
        require(conversation.activeContextRevisionId == revision.source.parentRevisionId) { "启用摘要已变化，请重新生成" }
        val history = modelVisibleMessages(conversation.id, -1)
        val events = eventsOf(conversation.id)
        validateSegments(revision)
        val mediaByMessage = mediaAssetsForMessages(revision.source.messages.map { it.id })
        val stickersByMessage = messageStickers(revision.source.messages.map { it.id }).groupBy { it.messageId }
        validateCompressionSource(revision.source, conversation, listPersonas(), history, events, mediaByMessage, stickersByMessage)
        require(CompressionHistory.cuts(history, events).any { it.messageId == revision.source.messages.last().id }) { "切点已变化" }
        check(db.contexts().byId(revision.id)?.state == "DRAFT" && revision.state == "COMPLETE") { "摘要草稿已变化或不可应用" }
        saveRevision(revision)
        db.conversations().setContext(conversation.id, revision.id, revision.method.name, at)
    }
    override suspend fun restoreContext(conversationId: String, expectedRevisionId: String?, at: Long) = db.withTransaction {
        val conversation = checkNotNull(getConversation(conversationId)) { "会话不存在" }
        require(conversation.activeContextRevisionId == expectedRevisionId) { "启用摘要已变化，请刷新" }
        db.conversations().setContext(conversationId, null, conversation.compressionMethod, at)
    }

    // ---- 给上层用的便捷查询（不在 StoragePort 里，但同样的数据源） ----

    /**
     * 用户最近发言的时间戳（倒序）。用于防抖的自适应学习。
     * 只取最近 N 条：学习手速不需要全量历史，而全量扫描会随聊天记录增长而变慢。
     */
    suspend fun recentUserTimestamps(conversationId: String, limit: Int = rhythmSampleLimit): List<Long> =
        db.messages().recentUserTimestamps(conversationId, limit)

    /** 跨会话取用户发言时间（用于"全局手速"，不限于某个会话）。 */
    suspend fun globalRhythmSamples(limit: Int = 60): List<Long> {
        // 这里刻意按会话分别取样再加权：不同会话的说话节奏可能差很多（工作时短句、
        // 闲聊时长段），混在一起会把窗口学到两个极端之间的某个没用值。
        val conversations = db.conversations().all().take(8)
        return conversations.flatMap { recentUserTimestamps(it.id) }
    }

    // ---- EventSink：播出状态的写入端 ----

    override suspend fun setDelivery(id: Long, delivery: DeliveryState) {
        db.events().setDelivery(id, delivery.name)
    }

    override suspend fun markDelivered(id: Long, at: Long, gapMs: Int, typingMs: Int) {
        db.events().markDelivered(id, at, gapMs, typingMs)
    }

    override suspend fun markInterrupted(id: Long, at: Long) {
        db.events().markInterrupted(id, at)
    }

    /**
     * 启动时调用：把上次进程残留的 PENDING/PLAYING 收尾。
     *
     * 结果一律是 `CANCELED`（当没说过）—— 它们确实从没播出去过。
     * 详见 DAO 里那段注释（写错成 INTERRUPTED 会表现为"重启后凭空冒出一堆消息"）。
     */
    suspend fun sweepUnfinished(): Int = db.events().sweepUnfinished()
    suspend fun sweepUnfinishedCalls(): Int = db.calls().markUnfinished()

    /**
     * 见 [StoragePort.repairDegradedTurns]。
     *
     * 实现要点：
     * - **在一个事务里**：删旧行 + 插新行要么都成、要么都不成。中途失败留下"一条 `say`
     *   被删了、新的还没插"就是**丢内容**，那是这个功能唯一不能犯的错。
     * - `id` 归零交给数据库自增（`copy(id = 0)`）；`.withTransaction` 由 room-ktx 提供。
     * - 本地事实（会话 / 轮次 / 模式 / 交付状态 / 时间）**从被替换的那一行照抄**：
     *   它们本来就是同一轮、同一次播出里出来的，拆开之后仍然属于那一刻。
     * - 引擎字段（`gapMs` / `typingMs`）只留在第一条上，其余清零 —— 这一轮真正播出过的
     *   是原来那一条，拆出来的几条不会重播。
     */
    override suspend fun repairDegradedTurns(conversationId: String): Int {
        val rows = db.events().rawScriptSaysOf(conversationId)
        if (rows.isEmpty()) return 0

        var repaired = 0
        db.withTransaction {
            for (row in rows) {
                val parts = DegradedTurn.split(row.text) ?: continue
                db.events().deleteById(row.id)
                parts.forEachIndexed { index, part ->
                    db.events().insert(
                        row.copy(
                            id = 0,
                            kind = part.kind.wire,
                            speaker = part.resolvedSpeaker,
                            text = part.text,
                            gapHintMs = part.gapHintMs,
                            typingHintMs = part.typingHintMs,
                            waitReason = part.waitReason,
                            gapMs = if (index == 0) row.gapMs else 0,
                            typingMs = if (index == 0) row.typingMs else 0
                        )
                    )
                }
                repaired++
            }
        }
        return repaired
    }

    suspend fun unfinishedEvents(conversationId: String): List<Event> =
        db.events().unfinishedOf(conversationId).map { it.toModel() }

    /** 改名只更新名称与时间，避免用界面快照覆盖会话的其他属性。 */
    suspend fun renameConversation(id: String, title: String, now: Long) {
        require(title.isNotBlank()) { "对话名称不能为空" }
        db.conversations().rename(id, title.trim(), now)
    }

    suspend fun setConversationPinned(id: String, pinned: Boolean) {
        check(db.conversations().setPinned(id, pinned) == 1) { "对话已不存在" }
    }

    suspend fun deleteConversation(id: String) = db.withTransaction {
        db.stickers().deleteMessageStickersForConversation(id)
        db.stickers().deleteEventStickersForConversation(id)
        db.media().deleteAttachments(id)
        db.media().deleteSubmissions(id)
        db.media().clearDraft(id)
        db.modelConfigs().deleteConversationBinding(id)
        db.events().deleteOf(id)
        db.messages().deleteOf(id)
        db.summaries().deleteOf(id)
        db.calls().deleteSegments(id)
        db.calls().deleteRevisions(id)
        db.calls().deleteCommands(id)
        db.conversations().delete(id)
    }

    suspend fun clearUsage() = db.withTransaction {
        db.stickers().clearTurnResults()
        db.stickers().clearTypedCallInputs()
        db.calls().clear(); db.modelConfigs().clearCallConfigSnapshots(); db.usage().clear(); db.backup().clearAccountingOrigins()
    }

    suspend fun usageBetween(from: Long, to: Long): List<UsageEntity> = db.usage().range(from, to)

    suspend fun usageAggregate(from: Long, to: Long) = db.usage().aggregate(from, to)
}
