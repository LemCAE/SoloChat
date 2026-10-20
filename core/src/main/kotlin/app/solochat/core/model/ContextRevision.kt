package app.solochat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable enum class CompressionMethod { ORIGINAL, INCREMENTAL, SEGMENTED }
@Serializable data class SourceFingerprint(val id: Long, val checksum: String, val createdAt: Long)
@Serializable data class CompressionSource(
    val conversationId: String, val declaredPersonaId: String, val personaChecksum: String,
    val parentRevisionId: String?, val mode: ChatMode,
    val messages: List<SourceFingerprint>, val events: List<SourceFingerprint>
)
@Serializable data class ContextSegment(val id: String, val fromMessageId: Long, val toMessageId: Long,
    val sourceChecksum: String, val text: String, val model: String)
@Serializable data class ContextRevision(val id: String, val conversationId: String, val personaId: String,
    val parentRevisionId: String?, val method: CompressionMethod, val source: CompressionSource,
    val segments: List<ContextSegment>, val createdAt: Long, val manuallyEdited: Boolean = false,
    val state: String = "DRAFT")

private val fingerprintJson = Json { encodeDefaults = true }
fun checksumOf(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
fun fingerprintOf(message: Message) = SourceFingerprint(message.id,
    checksumOf(fingerprintJson.encodeToString(message)), message.createdAt)
fun fingerprintOf(event: Event) = SourceFingerprint(event.id,
    checksumOf(fingerprintJson.encodeToString(event)), event.createdAt)
fun personaChecksum(personas: List<Persona>, declaredId: String): String =
    checksumOf(fingerprintJson.encodeToString(resolvePersona(personas, declaredId)))

/** 消息 id 是全库序号，不要求相邻整数；按来源列表逐段拼接才能证明无重叠、无遗漏。 */
fun validateSegments(revision: ContextRevision) {
    require(revision.conversationId == revision.source.conversationId && revision.parentRevisionId == revision.source.parentRevisionId) {
        "摘要版本与来源归属不一致"
    }
    val source = revision.source.messages
    require(source.isNotEmpty() && revision.segments.isNotEmpty()) { "摘要范围为空" }
    var cursor = 0
    revision.segments.forEach { segment ->
        require(segment.text.isNotBlank() && segment.text.length <= 200_000) { "摘要正文为空或过长" }
        require(cursor < source.size && source[cursor].id == segment.fromMessageId) { "摘要范围不连续" }
        val end = source.indexOfFirst { it.id == segment.toMessageId }
        require(end >= cursor) { "摘要范围倒置" }
        require(segment.sourceChecksum == checksumOf(source.subList(cursor, end + 1).joinToString("|") { it.checksum })) {
            "摘要来源校验不一致"
        }
        cursor = end + 1
    }
    require(cursor == source.size) { "摘要没有覆盖完整范围" }
}

/** 应用与装配共用来源校验；追加消息不改变早期前缀，编辑或补入早期记录会使校验失败。 */
fun validateCompressionSource(source: CompressionSource, conversation: Conversation,
    personas: List<Persona>, history: List<Message>, events: List<Event>) {
    require(conversation.id == source.conversationId && conversation.personaId == source.declaredPersonaId &&
        personaChecksum(personas, conversation.personaId) == source.personaChecksum) { "人物卡已变化，请重新生成摘要" }
    require(history.take(source.messages.size).map(::fingerprintOf) == source.messages) { "选中原文已变化，请重新生成摘要" }
    val turns = history.take(source.messages.size).filter { it.role == MessageRole.ASSISTANT }.map { it.turnId }.toSet()
    val spoken = events.filter { it.turnId in turns && it.usableAsContext && it.kind in
        setOf(EventKind.SAY, EventKind.ACT, EventKind.SCENE) }.map(::fingerprintOf)
    require(spoken == source.events) { "选中回复已变化，请重新生成摘要" }
}
