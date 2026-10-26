package app.solochat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 用户明确导入的静态图片原件。文件键是应用私有目录内的相对键，不是路径或身份。 */
@Serializable
data class MediaAsset(
    val id: String,
    val sha256: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val orientation: Int = 0,
    val fileKey: String,
    val createdAt: Long
) {
    init {
        require(id.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(mimeType in STATIC_IMAGE_MIME_TYPES && byteSize > 0)
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_MEDIA_PIXELS)
        require(orientation in 0..8)
        require(fileKey.isNotBlank() && !fileKey.startsWith('/') && '\\' !in fileKey && ':' !in fileKey &&
            fileKey.split('/').all { it.isNotBlank() && it != "." && it != ".." })
    }
}

@Serializable
data class MessageAttachment(val messageId: Long, val ordinal: Int, val mediaId: String) {
    init { require(messageId > 0 && ordinal >= 0 && mediaId.isNotBlank()) }
}

@Serializable
data class SubmissionText(val kind: String, val text: String) {
    init { require(kind in setOf("say", "act", "scene") && text.length <= MAX_SUBMISSION_TEXT) }
}

/** 一次用户点击的不可变输入；媒体在存储中通过 attachment 表关联，不改旧 Message JSON。 */
@Serializable
data class InputSubmission(
    val id: String,
    val conversationId: String,
    val text: List<SubmissionText>,
    val mediaIds: List<String>,
    val createdAt: Long,
    val draftVersion: Long = -1
) {
    init {
        require(id.isNotBlank() && conversationId.isNotBlank() && draftVersion >= -1)
        require(text.size <= 3 && text.all { it.text.isNotBlank() })
        require(mediaIds.size <= MAX_SUBMISSION_IMAGES && mediaIds.all(String::isNotBlank))
        require(text.isNotEmpty() || mediaIds.isNotEmpty())
    }
    val payloadChecksum: String get() = checksumOf(payloadJson.encodeToString(SubmissionPayload(text, mediaIds)))
}

@Serializable
private data class SubmissionPayload(val text: List<SubmissionText>, val mediaIds: List<String>)

private val payloadJson = Json { encodeDefaults = true }

@Serializable
data class MediaDraft(
    val conversationId: String,
    val text: List<SubmissionText>,
    val mediaIds: List<String>,
    val updatedAt: Long,
    val version: Long = 0,
    val submissionId: String = ""
) {
    init {
        require(conversationId.isNotBlank() && text.size <= 3 && mediaIds.size <= MAX_SUBMISSION_IMAGES && version >= 0)
        require(text.all { it.text.length <= MAX_SUBMISSION_TEXT } && mediaIds.all(String::isNotBlank))
    }
}

@Serializable
data class MediaContextReference(
    val messageId: Long,
    val ordinal: Int,
    val mediaId: String,
    val sourceSha256: String,
    val variant: String,
    val variantSha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val source: MediaReferenceSource
)

@Serializable
enum class MediaReferenceSource { NEW, EXPLICIT, RECENT_HISTORY, TEXT_ONLY }

@Serializable
sealed class MediaContextBlock

@Serializable
data class MediaTextBlock(val text: String) : MediaContextBlock()

@Serializable
data class MediaImageBlock(val image: MediaContextReference) : MediaContextBlock()

@Serializable
data class MediaContextPlan(val blocks: List<MediaContextBlock>, val omittedHistoryCount: Int = 0) {
    init {
        require(omittedHistoryCount >= 0)
        val images = blocks.filterIsInstance<MediaImageBlock>().map { it.image }
        require(images.all { it.messageId > 0 && it.ordinal >= 0 && it.mediaId.isNotBlank() &&
            it.sourceSha256.matches(Regex("[0-9a-f]{64}")) && it.variantSha256.matches(Regex("[0-9a-f]{64}")) &&
            it.variant.isNotBlank() && it.mimeType in STATIC_IMAGE_MIME_TYPES && it.width > 0 && it.height > 0 })
        require(images.map { Triple(it.messageId, it.ordinal, it.mediaId) }.distinct().size == images.size)
    }
}

/** 固定请求的不可变缩放变体；文件通过端口读取，清单不包含平台 URI 或路径。 */
@Serializable
data class MediaVariant(val mediaId: String, val variant: String, val sha256: String, val mimeType: String,
    val byteSize: Long, val width: Int, val height: Int, val cacheKey: String) {
    init {
        require(mediaId.isNotBlank() && variant.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(mimeType in STATIC_IMAGE_MIME_TYPES && byteSize > 0)
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_MEDIA_PIXELS)
        require(cacheKey.startsWith("media-variants/") && '\\' !in cacheKey && ".." !in cacheKey.split('/'))
    }
}

@Serializable
data class CallMediaInput(val callId: String, val images: List<MediaContextReference>, val recordedAt: Long) {
    init {
        require(callId.isNotBlank() && images.isNotEmpty())
        require(images.map { Triple(it.messageId, it.ordinal, it.mediaId) }.distinct().size == images.size)
        require(images.all { it.sourceSha256.matches(Regex("[0-9a-f]{64}")) && it.variantSha256.matches(Regex("[0-9a-f]{64}")) })
    }
}

/** 给备份 v3 使用的数据行；A 只定义契约，C 才接 ZIP 流程。 */
@Serializable
data class BackupMediaData(
    val assets: List<MediaAsset> = emptyList(),
    val attachments: List<MessageAttachment> = emptyList(),
    val submissions: Map<String, List<Long>> = emptyMap(),
    val callInputs: List<CallMediaInput> = emptyList()
)

@Serializable
data class MediaMessageFingerprint(val version: Int = 1, val message: SourceFingerprint, val orderedAssets: List<Pair<Int, String>>) {
    init { require(version == 1 && orderedAssets.map { it.first } == orderedAssets.indices.toList()) }
}

fun fingerprintOf(message: Message, attachments: List<MessageAttachment>, assets: Map<String, MediaAsset>): MediaMessageFingerprint {
    val ordered = attachments.filter { it.messageId == message.id }.sortedBy { it.ordinal }
    require(ordered.map { it.ordinal } == ordered.indices.toList()) { "图片附件顺序不连续" }
    val content = ordered.map { attachment ->
        val asset = requireNotNull(assets[attachment.mediaId]) { "图片资源不存在" }
        attachment.ordinal to "${asset.sha256}:${asset.mimeType}:${asset.width}x${asset.height}:${asset.orientation}"
    }
    return MediaMessageFingerprint(message = fingerprintOf(message), orderedAssets = content)
}

/** 无附件时逐字节沿用旧消息指纹；有附件时把有序原件内容摘要并入同一来源校验入口。 */
fun fingerprintWithAttachments(message: Message,
    attached: List<Pair<MessageAttachment, MediaAsset>>): SourceFingerprint {
    if (attached.isEmpty()) return fingerprintOf(message)
    val byId = attached.associate { it.first.mediaId to it.second }
    val fingerprint = fingerprintOf(message, attached.map { it.first }, byId)
    val json = Json { encodeDefaults = true }
    return fingerprint.message.copy(checksum = checksumOf(json.encodeToString(fingerprint)))
}

const val MAX_SUBMISSION_IMAGES = 4
const val MAX_SUBMISSION_TEXT = 200_000
const val MAX_MEDIA_PIXELS = 40_000_000L
val STATIC_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
