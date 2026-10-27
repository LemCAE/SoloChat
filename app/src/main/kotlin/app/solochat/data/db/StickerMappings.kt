package app.solochat.data.db

import app.solochat.core.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val stickerJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun UserStickerEntity.toCore() = UserSticker(id, mediaId, createdAt, sortOrder)
internal fun UserSticker.toEntity() = UserStickerEntity(id, mediaId, createdAt, sortOrder)

internal fun PersonaStickerEntity.toCore() = PersonaSticker(
    id = id, personaId = personaId, sha256 = sha256, byteSize = byteSize, mediaId = mediaId,
    status = PersonaStickerStatus.valueOf(status), collectionEpoch = collectionEpoch,
    name = name, description = description,
    tags = stickerJson.decodeFromString(tagsJson), usageContexts = stickerJson.decodeFromString(usageContextsJson),
    nameLocked = nameLocked, descriptionLocked = descriptionLocked, tagsLocked = tagsLocked,
    usageContextsLocked = usageContextsLocked, nameVersion = nameVersion,
    descriptionVersion = descriptionVersion, tagsVersion = tagsVersion,
    usageContextsVersion = usageContextsVersion, createdAt = createdAt, updatedAt = updatedAt,
    useCount = useCount, lastUsedAt = lastUsedAt
)

internal fun PersonaSticker.toEntity() = PersonaStickerEntity(
    id = id, personaId = personaId, sha256 = sha256, byteSize = byteSize, mediaId = mediaId,
    status = status.name, collectionEpoch = collectionEpoch, name = name, description = description,
    tagsJson = stickerJson.encodeToString(tags), usageContextsJson = stickerJson.encodeToString(usageContexts),
    nameLocked = nameLocked, descriptionLocked = descriptionLocked, tagsLocked = tagsLocked,
    usageContextsLocked = usageContextsLocked, nameVersion = nameVersion,
    descriptionVersion = descriptionVersion, tagsVersion = tagsVersion,
    usageContextsVersion = usageContextsVersion, createdAt = createdAt, updatedAt = updatedAt,
    useCount = useCount, lastUsedAt = lastUsedAt
)

internal fun MessageStickerEntity.toCore() = MessageSticker(messageId, ordinal, personaId, sha256, byteSize,
    collectionEpoch, sourceUserStickerId, submissionId)
internal fun MessageSticker.toEntity() = MessageStickerEntity(messageId, ordinal, personaId, sha256, byteSize,
    collectionEpoch, sourceUserStickerId, submissionId)

internal fun EventStickerEntity.toCore() = EventSticker(eventId, stickerId, personaId, collectionEpoch,
    mediaId, sha256, byteSize, nameSnapshot, descriptionSnapshot, assistantMessageId)
internal fun EventSticker.toEntity() = EventStickerEntity(eventId, stickerId, personaId, collectionEpoch,
    mediaId, sha256, byteSize, nameSnapshot, descriptionSnapshot, assistantMessageId)

internal fun StickerTurnResultEntity.toCore() = StickerTurnResult(callId, responseChecksum, resultJson, committedAt)
internal fun StickerTurnResult.toEntity() = StickerTurnResultEntity(callId, responseChecksum, resultJson, committedAt)

internal fun TypedCallMediaInputEntity.toCore(): BackupTypedCallMediaInput =
    stickerJson.decodeFromString(sourceJson)
internal fun BackupTypedCallMediaInput.toEntity(): TypedCallMediaInputEntity =
    TypedCallMediaInputEntity(callId, stickerJson.encodeToString(this), recordedAt)
