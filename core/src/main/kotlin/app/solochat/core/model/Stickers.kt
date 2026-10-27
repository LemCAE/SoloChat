package app.solochat.core.model

import kotlinx.serialization.Serializable

/** 我的表情库只保存全局收藏关系与顺序，不承载人物语义。 */
@Serializable
data class UserSticker(val id: String, val mediaId: String, val createdAt: Long, val sortOrder: Long) {
    init { require(id.startsWith("user-sticker-") && mediaId.isNotBlank() && createdAt >= 0) }
}

@Serializable
enum class PersonaStickerStatus { ACTIVE, REMOVED }

/** 人物私有收藏。四个可维护字段分别锁定并带版本，移出后保留递增代次墓碑。 */
@Serializable
data class PersonaSticker(
    val id: String,
    val personaId: String,
    val sha256: String,
    val byteSize: Long,
    val mediaId: String?,
    val status: PersonaStickerStatus = PersonaStickerStatus.ACTIVE,
    val collectionEpoch: Long = 0,
    val name: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val usageContexts: List<String> = emptyList(),
    val nameLocked: Boolean = false,
    val descriptionLocked: Boolean = false,
    val tagsLocked: Boolean = false,
    val usageContextsLocked: Boolean = false,
    val nameVersion: Long = 0,
    val descriptionVersion: Long = 0,
    val tagsVersion: Long = 0,
    val usageContextsVersion: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val useCount: Long = 0,
    val lastUsedAt: Long? = null
) {
    init {
        require(id.startsWith("persona-sticker-") && personaId.isNotBlank())
        require(sha256.matches(Regex("[0-9a-f]{64}")) && byteSize > 0)
        require(collectionEpoch >= 0 && listOf(nameVersion, descriptionVersion, tagsVersion, usageContextsVersion).all { it >= 0 })
        require(status == PersonaStickerStatus.REMOVED || !mediaId.isNullOrBlank())
        require(useCount >= 0 && (lastUsedAt == null || lastUsedAt >= 0))
    }
}

/** 用户表情作为独立 USER 消息发送时冻结用途、目标人物、媒体内容与收藏代次。 */
@Serializable
data class MessageSticker(
    val messageId: Long,
    val ordinal: Int,
    val personaId: String,
    val sha256: String,
    val byteSize: Long,
    val collectionEpoch: Long,
    val sourceUserStickerId: String? = null,
    val submissionId: String
) {
    init {
        require(messageId > 0 && ordinal >= 0 && personaId.isNotBlank())
        require(sha256.matches(Regex("[0-9a-f]{64}")) && byteSize > 0 && collectionEpoch >= 0)
        require(submissionId.isNotBlank())
    }
}

/** AI 表情事件发送时快照；后续人物库编辑或移出不能改写历史。 */
@Serializable
data class EventSticker(
    val eventId: Long,
    val stickerId: String,
    val personaId: String,
    val collectionEpoch: Long,
    val mediaId: String,
    val sha256: String,
    val byteSize: Long,
    val nameSnapshot: String,
    val descriptionSnapshot: String = "",
    val assistantMessageId: Long? = null
) {
    init {
        require(eventId > 0 && stickerId.startsWith("persona-sticker-") && personaId.isNotBlank())
        require(collectionEpoch >= 0 && mediaId.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")) && byteSize > 0)
        require(assistantMessageId == null || assistantMessageId > 0)
    }
}

/** 一次调用冻结的许可范围；模型只能引用本快照中明确提供的来源。 */
@Serializable
data class StickerCallScope(
    val callId: String,
    val conversationId: String,
    val personaId: String,
    val inputMessageIds: List<Long>,
    val collectibleSources: List<MessageSticker>,
    val candidates: List<PersonaSticker>,
    val capturedAt: Long
) {
    init {
        require(callId.isNotBlank() && conversationId.isNotBlank() && personaId.isNotBlank())
        require(inputMessageIds.all { it > 0 } && inputMessageIds.distinct().size == inputMessageIds.size)
        require(collectibleSources.all { it.personaId == personaId && it.messageId in inputMessageIds })
        require(candidates.all { it.personaId == personaId && it.status == PersonaStickerStatus.ACTIVE })
    }
}

@Serializable
data class StickerTurnResult(val callId: String, val responseChecksum: String, val resultJson: String, val committedAt: Long) {
    init { require(callId.isNotBlank() && responseChecksum.matches(Regex("[0-9a-f]{64}")) && committedAt >= 0) }
}

/** ZIP v4 对用户收藏、人物收藏及消息/事件语义的传输结构。 */
@Serializable
data class BackupStickerData(
    val userStickers: List<UserSticker> = emptyList(),
    val personaStickers: List<PersonaSticker> = emptyList(),
    val messageStickers: List<MessageSticker> = emptyList(),
    val eventStickers: List<EventSticker> = emptyList(),
    val turnResults: List<StickerTurnResult> = emptyList()
)

/** v4 可以表达没有消息的收藏图和助手事件媒体，不伪造 messageId。 */
@Serializable
data class BackupCallMediaSource(
    val kind: Kind,
    val messageId: Long? = null,
    val ordinal: Int? = null,
    val personaStickerId: String? = null,
    val collectionEpoch: Long? = null,
    val mediaId: String,
    val sha256: String,
    val variant: String,
    val variantSha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int
) {
    @Serializable enum class Kind { MESSAGE, PERSONA_STICKER }
    init {
        require(mediaId.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")) && variantSha256.matches(Regex("[0-9a-f]{64}")) && variant.isNotBlank())
        require((kind == Kind.MESSAGE && messageId != null && messageId > 0 && ordinal != null && ordinal >= 0 && personaStickerId == null) ||
            (kind == Kind.PERSONA_STICKER && messageId == null && ordinal == null && !personaStickerId.isNullOrBlank() && collectionEpoch != null && collectionEpoch >= 0))
        require(mimeType in STATIC_IMAGE_MIME_TYPES && width > 0 && height > 0 && width.toLong() * height <= MAX_MEDIA_PIXELS)
    }
}

@Serializable
data class BackupTypedCallMediaInput(val callId: String, val sources: List<BackupCallMediaSource>, val recordedAt: Long) {
    init { require(callId.isNotBlank() && sources.isNotEmpty()) }
}
