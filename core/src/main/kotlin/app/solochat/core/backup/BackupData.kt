package app.solochat.core.backup

import app.solochat.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.decodeFromString

/** 备份只包含业务数据；接口配置、密钥、日志和网页命令不进入这个结构。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class BackupData(
    val conversations: List<Conversation> = emptyList(),
    val messages: List<Message> = emptyList(),
    val events: List<Event> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val summaries: List<SceneSummary> = emptyList(),
    val revisions: List<ContextRevision> = emptyList(),
    val usages: List<BackupUsage> = emptyList(),
    val calls: List<BackupCall> = emptyList(),
    val conversationModelBindings: List<BackupConversationModelBinding> = emptyList(),
    val callConfigSnapshots: List<BackupCallConfigSnapshot> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val media: BackupMediaData = BackupMediaData(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stickers: app.solochat.core.model.BackupStickerData = app.solochat.core.model.BackupStickerData()
) {
    /** v1 manifest 的历史计数键；保持原顺序和集合。 */
    fun counts(): Map<String, Int> = linkedMapOf(
        "conversations" to conversations.size, "messages" to messages.size, "events" to events.size,
        "personas" to personas.size, "memories" to memories.size, "summaries" to summaries.size,
        "revisions" to revisions.size, "segments" to revisions.sumOf { it.segments.size },
        "usages" to usages.size, "calls" to calls.size
    )

    fun countsV2(): Map<String, Int> = counts() + linkedMapOf(
        "conversationModelBindings" to conversationModelBindings.size,
        "callConfigSnapshots" to callConfigSnapshots.size
    )

    fun countsV3(): Map<String, Int> = countsV2() + linkedMapOf(
        "mediaAssets" to media.assets.size, "messageAttachments" to media.attachments.size,
        "submissions" to media.submissions.size, "callMediaInputs" to media.callInputs.size
    )

    fun countsV4(): Map<String, Int> = countsV3() + linkedMapOf(
        "userStickers" to stickers.userStickers.size,
        "personaStickers" to stickers.personaStickers.size,
        "messageStickers" to stickers.messageStickers.size,
        "eventStickers" to stickers.eventStickers.size,
        "stickerTurnResults" to stickers.turnResults.size,
        "typedCallMediaInputs" to media.typedCallInputs.size
    )
}

/** v3 媒体原件的传输元数据；entryKey 只允许由内容摘要确定，不是本机文件路径。 */
@Serializable
data class BackupMediaAsset(
    val id: String,
    val sha256: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val entryKey: String
)

@Serializable
data class BackupSubmission(
    val id: String,
    val conversationId: String,
    val payloadChecksum: String,
    val messageIds: List<Long>,
    val createdAt: Long
)

/** 与调用记录同包保存的非秘密图片来源；其历史资源可以已不在消息原件闭包中。 */
@Serializable
data class BackupCallMediaInput(val callId: String, val inputJson: String, val recordedAt: Long)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class BackupMediaData(
    val assets: List<BackupMediaAsset> = emptyList(),
    val attachments: List<app.solochat.core.model.MessageAttachment> = emptyList(),
    val submissions: List<BackupSubmission> = emptyList(),
    val callInputs: List<BackupCallMediaInput> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val typedCallInputs: List<app.solochat.core.model.BackupTypedCallMediaInput> = emptyList()
)

/** 保留旧 usage 表的身份与时间；不能只备份已关联的新调用。 */
@Serializable
data class BackupUsage(val id: Long, val conversationId: String, val model: String,
    val usage: Usage, val at: Long)

/** CallRecord 已包含 detailJson 的业务字段，usageId 单独保存。 */
@Serializable
data class BackupCall(val record: CallRecord, val usageId: Long? = null)

/** 会话选择的非秘密备份来源；导入时必须转为本机待确认状态。 */
@Serializable
data class BackupConversationModelBinding(
    val conversationId: String,
    val selectionState: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val pendingReason: String? = null,
    val migrationSource: String
)

/** 调用开始时的非秘密快照，仅作为账目历史；不用于注册或解析本机模型。 */
@Serializable
data class BackupCallConfigSnapshot(
    val callId: String,
    val version: Int,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelDisplayName: String,
    val requestModelId: String,
    val use: String,
    val selectionSource: String,
    val thinkingMode: String,
    val thinkingEffort: String? = null,
    val thinkingSource: String,
    val actualThinkingParametersJson: String
)

@Serializable
data class BackupManifest(
    val format: String = "solochat-backup",
    val version: Int = 1,
    val id: String,
    val createdAt: Long,
    val dataChecksum: String,
    val counts: Map<String, Int>,
    val excluded: List<String> = BACKUP_EXCLUDED
)

/** 首版协议明确排除这些项目，避免把运行权限或本机路径当成已恢复的能力。 */
val BACKUP_EXCLUDED = listOf("secrets", "interface-settings", "preferences", "logs", "client-commands", "avatar-files")

data class BackupArchive(val manifest: BackupManifest, val data: BackupData, val compressedBytes: Long = 0)

/**
 * 校验所有必须存在的关系。已删除会话的记忆/账目来源、已删除人物卡归属是合法历史。
 * 摘要来源指纹是否仍有效由导入规划单独判断，失效版本保留文本而不重新激活。
 */
fun validateBackup(data: BackupData) {
    fun <T, K> unique(rows: List<T>, name: String, key: (T) -> K): Map<K, T> {
        val result = rows.associateBy(key)
        require(result.size == rows.size) { "$name 存在重复 ID" }
        require(result.keys.all {
            when (it) {
                is Long -> it > 0
                is String -> it.isNotBlank()
                is Pair<*, *> -> it.first is Long && (it.first as Long) > 0 && it.second is Int && (it.second as Int) >= 0
                else -> false
            }
        }) { "$name ID 无效" }
        return result
    }
    val conversations = unique(data.conversations, "会话") { it.id }
    val messages = unique(data.messages, "消息") { it.id }
    val events = unique(data.events, "事件") { it.id }
    unique(data.personas, "人物卡") { it.id }
    unique(data.memories, "记忆") { it.id }
    unique(data.summaries, "旧摘要") { it.id }
    val revisions = unique(data.revisions, "摘要版本") { it.id }
    unique(data.revisions.flatMap { it.segments }, "摘要分段") { it.id }
    val usages = unique(data.usages, "用量") { it.id }
    val calls = unique(data.calls, "调用") { it.record.id }
    val bindings = unique(data.conversationModelBindings, "会话模型关联") { it.conversationId }
    val snapshots = unique(data.callConfigSnapshots, "调用配置快照") { it.callId }
    val mediaAssets = unique(data.media.assets, "媒体资源") { it.id }
    unique(data.media.submissions, "图文提交") { it.id }
    unique(data.media.callInputs, "调用图片清单") { it.callId }
    unique(data.media.typedCallInputs, "类型化调用图片清单") { it.callId }
    val userStickers = unique(data.stickers.userStickers, "我的表情") { it.id }
    val personaStickers = unique(data.stickers.personaStickers, "人物表情") { it.id }
    val messageStickers = unique(data.stickers.messageStickers, "表情消息") { it.messageId to it.ordinal }
    val eventStickers = unique(data.stickers.eventStickers, "表情事件") { it.eventId }
    unique(data.stickers.turnResults, "表情响应结果") { it.callId }
    data.messages.forEach { require(it.conversationId in conversations) { "消息缺少所属会话" } }
    val attachmentsByMessage = data.media.attachments.groupBy { it.messageId }
    require(data.media.attachments.map { it.messageId to it.ordinal }.distinct().size == data.media.attachments.size) { "图片附件序号重复" }
    data.media.attachments.forEach { attachment ->
        val message = messages[attachment.messageId]
        val declaredStickerAttachment = data.stickers.eventStickers.any { it.assistantMessageId == attachment.messageId }
        require(message != null && (message.role == MessageRole.USER ||
            message.role == MessageRole.ASSISTANT && declaredStickerAttachment) && attachment.ordinal >= 0 && attachment.mediaId in mediaAssets) {
            "图片附件消息或资源缺失"
        }
    }
    attachmentsByMessage.forEach { (_, rows) ->
        require(rows.sortedBy { it.ordinal }.map { it.ordinal } == rows.indices.toList() && rows.size <= 4) { "图片附件序号或数量无效" }
    }
    val stickerMediaIds = data.stickers.userStickers.map { it.mediaId }.toSet() +
        data.stickers.personaStickers.filter { it.status == app.solochat.core.model.PersonaStickerStatus.ACTIVE }.mapNotNull { it.mediaId } +
        data.stickers.eventStickers.map { it.mediaId }
    require(mediaAssets.keys == data.media.attachments.map { it.mediaId }.toSet() + stickerMediaIds) { "备份媒体资源必须属于消息或表情闭包" }
    data.media.assets.forEach { asset ->
        require(asset.id.isNotBlank() && asset.sha256.matches(Regex("[0-9a-f]{64}")) &&
            asset.mimeType in STATIC_IMAGE_MIME_TYPES && asset.byteSize in 1L..MAX_BACKUP_MEDIA_BYTES &&
            asset.width > 0 && asset.height > 0 && asset.width.toLong() * asset.height <= MAX_MEDIA_PIXELS &&
            asset.orientation in 0..8 && asset.entryKey == "media/${asset.sha256}-${asset.byteSize}.bin") { "媒体清单字段无效" }
    }
    val uniqueMediaPayloads = data.media.assets.distinctBy { it.sha256 to it.byteSize }
    require(data.media.assets.size <= MAX_BACKUP_MEDIA_ASSETS && uniqueMediaPayloads.size <= MAX_BACKUP_MEDIA_ASSETS &&
        uniqueMediaPayloads.sumOf { it.byteSize } <= MAX_BACKUP_MEDIA_TOTAL_BYTES &&
        uniqueMediaPayloads.sumOf { it.width.toLong() * it.height } <= MAX_BACKUP_MEDIA_TOTAL_PIXELS) { "媒体备份超过数量、体积或像素预算" }
    require(data.media.assets.groupBy { it.entryKey }.values.all { rows ->
        rows.map { listOf(it.sha256, it.mimeType, it.byteSize.toString(), it.width.toString(), it.height.toString(), it.orientation.toString()) }.distinct().size == 1
    }) { "同一媒体条目声明了不同内容" }
    require(data.media.assets.groupBy { it.sha256 }.values.all { rows -> rows.map { it.byteSize }.distinct().size == 1 }) {
        "相同媒体摘要声明了不同长度"
    }
    data.media.submissions.forEach { submission ->
        require(submission.id.isNotBlank() && submission.conversationId in conversations &&
            submission.payloadChecksum.matches(Regex("[0-9a-f]{64}")) && submission.messageIds.isNotEmpty() &&
            submission.messageIds.distinct().size == submission.messageIds.size &&
            submission.messageIds.all { messages[it]?.conversationId == submission.conversationId }) { "图文提交映射缺失或不一致" }
    }
    data.media.callInputs.forEach { input ->
        val call = calls[input.callId]?.record
        require(call != null) { "调用图片清单缺少调用记录" }
        val decoded = runCatching { Json.decodeFromString<app.solochat.core.model.CallMediaInput>(input.inputJson) }.getOrNull()
        require(decoded != null && decoded.callId == input.callId) { "调用图片清单格式无效" }
        decoded.images.forEach { image ->
            require(image.messageId != 0L && image.ordinal >= 0 && image.mediaId.isNotBlank() &&
                image.width > 0 && image.height > 0 && maxOf(image.width, image.height) <= 2048 &&
                image.width.toLong() * image.height <= MAX_MEDIA_PIXELS && when (image.variant) {
                    "jpeg-2048-q85-v1" -> image.mimeType == "image/jpeg"
                    "png-2048-v1" -> image.mimeType == "image/png"
                    else -> false
                } &&
                image.source in app.solochat.core.model.MediaReferenceSource.entries) { "调用图片来源字段无效" }
            val message = messages[image.messageId]
            if (message != null) {
                require(message.conversationId == call.conversationId) { "调用图片来源消息跨会话" }
                val attachment = data.media.attachments.firstOrNull {
                    it.messageId == image.messageId && it.ordinal == image.ordinal && it.mediaId == image.mediaId
                }
                val asset = mediaAssets[image.mediaId]
                require(attachment != null && asset != null && asset.sha256 == image.sourceSha256) {
                    "调用图片来源与消息附件原件不一致"
                }
            }
        }
    }
    userStickers.values.forEach { sticker ->
        require(sticker.mediaId in mediaAssets && sticker.id.startsWith("user-sticker-")) { "我的表情引用的媒体缺失" }
    }
    require(data.stickers.userStickers.map { it.mediaId }.distinct().size == data.stickers.userStickers.size) { "我的表情包含重复内容关系" }
    val personasById = data.personas.associateBy { it.id }
    personaStickers.values.forEach { sticker ->
        require(sticker.id.startsWith("persona-sticker-") && sticker.personaId in personasById) { "人物表情归属缺失" }
        val asset = sticker.mediaId?.let { mediaAssets[it] }
        if (sticker.status == app.solochat.core.model.PersonaStickerStatus.ACTIVE) {
            require(asset != null && asset.sha256 == sticker.sha256 && asset.byteSize == sticker.byteSize) { "有效人物表情原件缺失或身份不一致" }
        } else if (sticker.mediaId != null) {
            require(asset != null && asset.sha256 == sticker.sha256 && asset.byteSize == sticker.byteSize) { "人物表情墓碑原件身份不一致" }
        }
    }
    require(data.stickers.personaStickers.map { Triple(it.personaId, it.sha256, it.byteSize) }.distinct().size == data.stickers.personaStickers.size) {
        "同一人物同一内容存在重复表情身份"
    }
    messageStickers.values.forEach { sticker ->
        val message = messages[sticker.messageId]
        val attachment = attachmentsByMessage[sticker.messageId].orEmpty().firstOrNull { it.ordinal == sticker.ordinal }
        val asset = mediaAssets[attachment?.mediaId]
        require(message?.role == app.solochat.core.model.MessageRole.USER && attachment != null &&
            asset?.sha256 == sticker.sha256 && asset.byteSize == sticker.byteSize &&
            conversations[message.conversationId]?.personaId == sticker.personaId) {
            "表情消息与用户附件或人物身份不匹配"
        }
    }
    eventStickers.values.forEach { sticker ->
        val event = events[sticker.eventId]
        val conversation = event?.let { conversations[it.conversationId] }
        val asset = mediaAssets[sticker.mediaId]
        val personaSticker = personaStickers[sticker.stickerId]
        require(event != null && event.delivery.spoken && event.kind == app.solochat.core.model.EventKind.SAY &&
            event.mode == app.solochat.core.model.ChatMode.CHAT && conversation?.personaId == sticker.personaId &&
            asset?.sha256 == sticker.sha256 && asset.byteSize == sticker.byteSize &&
            (personaSticker == null || personaSticker.personaId == sticker.personaId && personaSticker.sha256 == sticker.sha256 &&
                personaSticker.byteSize == sticker.byteSize && sticker.collectionEpoch <= personaSticker.collectionEpoch)) {
            "表情事件快照与人物或原件不匹配"
        }
        sticker.assistantMessageId?.let { assistantMessageId ->
            val assistant = messages[assistantMessageId]
            val attached = attachmentsByMessage[assistantMessageId].orEmpty()
            require(assistant?.role == MessageRole.ASSISTANT && assistant.conversationId == event.conversationId &&
                attached.size == 1 && attached.single().mediaId == sticker.mediaId) { "表情事件的助手历史图片关联无效" }
        }
    }
    data.stickers.turnResults.forEach { require(it.callId in calls) { "表情响应结果缺少调用记录" } }
    require(data.stickers.turnResults.isEmpty()) { "本地表情执行回执不能导入或恢复" }
    data.media.typedCallInputs.forEach { input ->
        val call = calls[input.callId]?.record ?: error("类型化图片清单缺少调用记录")
        val callConversation = conversations[call.conversationId]
        input.sources.forEach { source ->
            when (source.kind) {
                app.solochat.core.model.BackupCallMediaSource.Kind.MESSAGE -> {
                    val messageId = checkNotNull(source.messageId)
                    if (messageId < 0) {
                        require(source.mediaId.startsWith("missing:") && messageId !in messages) { "已删除消息图片来源必须显式隔离" }
                    } else {
                        val message = messages[messageId]
                        val attachment = data.media.attachments.firstOrNull {
                            it.messageId == messageId && it.ordinal == source.ordinal && it.mediaId == source.mediaId
                        }
                        val asset = mediaAssets[source.mediaId]
                        require(message != null && message.conversationId == call.conversationId && attachment != null &&
                            asset?.sha256 == source.sha256 && asset.byteSize == source.byteSize) {
                            "类型化消息图片来源必须属于本次调用会话且匹配原件"
                        }
                    }
                }
                app.solochat.core.model.BackupCallMediaSource.Kind.PERSONA_STICKER -> {
                    val sticker = personaStickers[source.personaStickerId]
                    val asset = mediaAssets[source.mediaId]
                    if (sticker == null) {
                        require(source.personaStickerId.orEmpty().startsWith("backup:") && source.mediaId.startsWith("missing:")) {
                            "已删除人物表情来源必须显式隔离"
                        }
                    } else {
                        require(sticker.personaId == callConversation?.personaId && sticker.sha256 == source.sha256 &&
                            sticker.byteSize == source.byteSize && checkNotNull(source.collectionEpoch) <= sticker.collectionEpoch) {
                            "类型化人物表情来源与调用人物或历史身份不匹配"
                        }
                        if (source.mediaId.startsWith("missing:")) require(asset == null) { "隔离来源不能引用包内资源" }
                        else require(asset?.sha256 == source.sha256 && asset.byteSize == source.byteSize) { "类型化人物表情原件不匹配" }
                    }
                }
            }
        }
    }
    data.events.forEach { require(it.conversationId in conversations) { "事件缺少所属会话" } }
    bindings.forEach { (conversationId, binding) ->
        require(conversationId in conversations) { "会话模型关联缺少所属会话" }
        require(binding.migrationSource.isNotBlank()) { "会话模型关联缺少来源" }
        when (binding.selectionState) {
            "INHERIT" -> require(binding.providerId == null && binding.modelId == null && binding.pendingReason == null) {
                "继承关联不能携带模型引用或待确认原因"
            }
            "EXPLICIT" -> require(!binding.providerId.isNullOrBlank() && !binding.modelId.isNullOrBlank() && binding.pendingReason == null) {
                "显式关联必须包含完整模型引用"
            }
            "NEEDS_CONFIRMATION" -> require(!binding.pendingReason.isNullOrBlank() &&
                ((binding.providerId == null) == (binding.modelId == null))) { "待确认关联必须保留原因及完整引用状态" }
            else -> error("会话模型选择状态未知：${binding.selectionState}")
        }
    }
    snapshots.forEach { (callId, snapshot) ->
        require(callId in calls) { "调用配置快照缺少对应调用记录" }
        require(snapshot.version > 0 && snapshot.providerId.isNotBlank() && snapshot.modelId.isNotBlank() &&
            snapshot.providerName.isNotBlank() && snapshot.modelDisplayName.isNotBlank() &&
            snapshot.requestModelId.isNotBlank() && snapshot.use.isNotBlank() &&
            snapshot.selectionSource.isNotBlank() && snapshot.thinkingMode.isNotBlank() &&
            snapshot.thinkingSource.isNotBlank()) { "调用配置快照字段无效" }
        require(snapshot.actualThinkingParametersJson.isNotBlank()) { "调用思考参数快照不能为空" }
        val parameters = runCatching { Json.parseToJsonElement(snapshot.actualThinkingParametersJson) }.getOrNull()
        val legacyArrayIsValid = (parameters as? JsonArray)?.all { item ->
            val fields = item as? JsonObject ?: return@all false
            val name = (fields["name"] as? JsonPrimitive)?.contentOrNull
            val value = (fields["value"] as? JsonPrimitive)?.contentOrNull
            !name.isNullOrBlank() && value != null
        } == true
        require(parameters is JsonObject || legacyArrayIsValid) {
            "调用思考参数快照必须是对象或兼容的旧参数数组"
        }
    }
    fun eventBoundary(id: Long, conversationId: String) {
        require(id == 0L || events[id]?.conversationId == conversationId) { "事件边界缺失或跨会话" }
    }
    data.conversations.forEach { c ->
        require(c.compressionMethod in CompressionMethod.entries.map { it.name }) { "压缩方式未知" }
        eventBoundary(c.modeSinceEventId, c.id)
        c.activeContextRevisionId?.let { require(revisions[it]?.conversationId == c.id) { "启用摘要缺失或跨会话" } }
    }
    data.summaries.forEach {
        require(it.conversationId in conversations) { "旧摘要缺少所属会话" }
        eventBoundary(it.fromEventId, it.conversationId)
        eventBoundary(it.toEventId, it.conversationId)
    }
    data.revisions.forEach { r ->
        require(r.conversationId in conversations && r.source.conversationId == r.conversationId) { "摘要归属无效" }
        require(r.state in setOf("DRAFT", "COMPLETE")) { "摘要状态未知" }
        require(r.parentRevisionId == r.source.parentRevisionId) { "摘要父版本不一致" }
        r.parentRevisionId?.let { require(revisions[it]?.conversationId == r.conversationId) { "摘要父版本缺失或跨会话" } }
        require(r.source.messages.map { it.id }.distinct().size == r.source.messages.size &&
            r.source.events.map { it.id }.distinct().size == r.source.events.size) { "摘要来源重复" }
        r.source.messages.forEach { require(messages[it.id]?.conversationId == r.conversationId) { "摘要来源消息缺失或跨会话" } }
        r.source.events.forEach { require(events[it.id]?.conversationId == r.conversationId) { "摘要来源事件缺失或跨会话" } }
        r.segments.forEach {
            require(it.fromMessageId in r.source.messages.map { source -> source.id } &&
                it.toMessageId in r.source.messages.map { source -> source.id }) { "摘要分段边界缺失" }
        }
    }
    // 用迭代路径而非递归，坏包不能用极深父链耗尽调用栈。
    val done = mutableSetOf<String>()
    revisions.keys.forEach { start ->
        val path = mutableSetOf<String>()
        var id: String? = start
        while (id != null && id !in done) {
            require(path.add(id)) { "摘要父版本形成循环" }
            id = revisions.getValue(id).parentRevisionId
        }
        done += path
    }
    val linkedUsage = mutableSetOf<Long>()
    data.calls.forEach { call ->
        val record = call.record
        validateFrozenTiers(record.price.frozenTiers)
        require(record.operationId.isNotBlank() && record.conversationId.isNotBlank()) { "调用来源无效" }
        require(listOf(record.price.inputPerM, record.price.outputPerM, record.price.cachedPerM,
            record.price.imagePerCall).all { it.isFinite() && it >= 0.0 }) { "调用价格无效" }
        call.usageId?.let {
            val usage = usages[it]
            require(usage != null && usage.conversationId == record.conversationId && usage.model == record.model) { "调用用量关联缺失或不一致" }
            require(linkedUsage.add(it)) { "同一用量关联了多个调用" }
        }
        record.contextMessageIds.forEach {
            // 删除会话后账目仍在；副本用负数保留旧消息号，避免误指向本机的正数主键。
            require(it != 0L && (it !in messages || messages[it]?.conversationId == record.conversationId)) { "调用上下文跨会话" }
            require(record.conversationId !in conversations || it in messages) { "调用上下文消息缺失" }
        }
    }
    data.usages.forEach {
        require(it.conversationId.isNotBlank()) { "用量来源为空" }
        require(listOf(it.usage.promptTokens, it.usage.completionTokens, it.usage.cachedTokens,
            it.usage.reasoningTokens, it.usage.imageCount).all { n -> n >= 0 }) { "用量不能为负数" }
    }
}

const val MAX_BACKUP_MEDIA_ASSETS = 2048
const val MAX_BACKUP_MEDIA_BYTES = 32L * 1024 * 1024
const val MAX_BACKUP_MEDIA_TOTAL_BYTES = 512L * 1024 * 1024
const val MAX_BACKUP_MEDIA_TOTAL_PIXELS = 512_000_000L
