package app.solochat.data.logweb

import app.solochat.core.ports.ClockPort
import app.solochat.core.model.EventKind
import app.solochat.data.MediaLibrary
import app.solochat.data.db.P4TestSubjectEntity
import app.solochat.data.db.RoomStore
import app.solochat.play.SessionRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import app.solochat.core.model.TimelineEntry

@Serializable data class P4TestAttachment(val ordinal: Int, val mediaId: String, val sha256: String, val available: Boolean)
@Serializable data class P4TestMessage(val id: Long, val role: String, val text: String, val sentToModel: Boolean,
    val sticker: Boolean, val attachments: List<P4TestAttachment>)
@Serializable data class P4TestEvent(val id: Long, val kind: String, val text: String?, val delivery: String)
@Serializable data class P4TestCall(val id: String, val status: String, val inputTokens: Int?, val outputTokens: Int?)
@Serializable data class P4TestSnapshot(val ui: UiTestState, val messages: List<P4TestMessage>, val events: List<P4TestEvent>,
    val personaStickers: List<app.solochat.core.model.PersonaSticker>, val calls: List<P4TestCall>)

@Serializable data class P4TestSetupRequest(val requestId: String)
@Serializable data class P4TestSetupResult(
    val requestId: String, val personaId: String, val conversationId: String,
    val userStickerId: String, val mediaId: String, val title: String, val createdAt: Long
)
@Serializable data class P4TestCommand(
    val requestId: String, val subjectRequestId: String, val action: String,
    val text: String = "", val mode: String = "", val stickerId: String = "",
    val name: String? = null, val description: String? = null, val tags: List<String>? = null,
    val usageContexts: List<String>? = null, val field: String = ""
)
@Serializable data class P4TestCommandResult(val requestId: String, val accepted: Boolean, val state: P4TestSnapshot)

/** 只对本轮创建记录中的合成会话和合成收藏开放的窄测试入口。 */
class P4TestGateway(
    private val store: RoomStore,
    private val media: MediaLibrary,
    private val sessions: SessionRegistry,
    private val clock: ClockPort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val setupMutex = Mutex()
    private val ui = UiTestGateway(resolve = { id ->
        checkNotNull(store.getConversation(id)) { "测试会话不存在" }
        sessions.get(id).also { it.awaitReady() }
    })

    suspend fun setup(request: P4TestSetupRequest): P4TestSetupResult = withContext(dispatcher) {
        require(request.requestId.isNotBlank() && request.requestId.length <= 80) { "requestId 需为 1～80 个字符" }
        setupMutex.withLock {
            val existing = store.p4TestSubject(request.requestId)
            val row = existing ?: run {
                val asset = media.importSyntheticTestImage(clock.now())
                store.createP4TestSubject(request.requestId, asset.id, clock.now())
            }
            val title = checkNotNull(store.getConversation(row.conversationId)).title
            P4TestSetupResult(row.requestId, row.personaId, row.conversationId, row.userStickerId,
                row.mediaId, title, row.createdAt)
        }
    }

    suspend fun state(requestId: String): P4TestSnapshot = withContext(dispatcher) {
        val subject = ownedSubject(requestId)
        validateSubject(subject)
        snapshot(subject)
    }

    suspend fun execute(command: P4TestCommand): P4TestCommandResult = withContext(dispatcher) {
        require(command.requestId.isNotBlank() && command.requestId.length <= 80) { "requestId 需为 1～80 个字符" }
        val subject = ownedSubject(command.subjectRequestId)
        validateSubject(subject)
        val operationId = operationId(subject.requestId, command.requestId)
        val controller = checkNotNull(store.getConversation(subject.conversationId)).let {
            check(it.personaId == subject.personaId) { "测试会话人物归属已变化" }
            sessions.get(subject.conversationId).also { it.awaitReady() }
        }
        val accepted = when (command.action) {
            "sticker" -> withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                check(store.userStickers().any { it.id == subject.userStickerId && it.mediaId == subject.mediaId }) {
                    "本轮测试用户表情已移除"
                }
                controller.enqueueSticker(subject.conversationId, subject.personaId,
                    subject.userStickerId, subject.mediaId,
                    submissionId = operationId)
            }
            "image" -> withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                controller.submitTestImage(subject.mediaId, operationId, clock.now())
            }
            "remove-user-sticker" -> {
                check(command.stickerId == subject.userStickerId) { "只允许移除本轮测试收藏" }
                store.removeUserSticker(subject.userStickerId); true
            }
            "remove-persona-sticker" -> {
                val sticker = store.personaStickers(subject.personaId).firstOrNull { it.id == command.stickerId }
                checkNotNull(sticker) { "人物表情不属于本轮测试人物" }
                store.removePersonaSticker(sticker.id, clock.now()); true
            }
            "edit-persona-sticker" -> {
                val sticker = store.personaStickers(subject.personaId).firstOrNull { it.id == command.stickerId }
                checkNotNull(sticker) { "人物表情不属于本轮测试人物" }
                val updated = sticker.copy(name = command.name ?: sticker.name, description = command.description ?: sticker.description,
                    tags = command.tags ?: sticker.tags, usageContexts = command.usageContexts ?: sticker.usageContexts, updatedAt = clock.now())
                store.editPersonaSticker(updated, setOf("name", "description", "tags", "usageContexts").filterTo(mutableSetOf()) { field ->
                    when (field) { "name" -> updated.name != sticker.name; "description" -> updated.description != sticker.description
                        "tags" -> updated.tags != sticker.tags; else -> updated.usageContexts != sticker.usageContexts }
                }); true
            }
            "unlock-persona-sticker" -> {
                val sticker = store.personaStickers(subject.personaId).firstOrNull { it.id == command.stickerId }
                checkNotNull(sticker) { "人物表情不属于本轮测试人物" }
                store.unlockPersonaStickerField(sticker.id, command.field); true
            }
            "send" -> withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                require(command.text.isNotBlank()) { "测试文字不能为空" }
                controller.submitTestInput(listOf(EventKind.SAY to command.text), operationId, clock.now())
            }
            "push", "mode" -> {
                val uiCommand = when (command.action) {
                    "push" -> UiTestCommand(subject.conversationId, operationId, "push")
                    else -> UiTestCommand(subject.conversationId, operationId, "mode", mode = command.mode)
                }
                ui.execute(uiCommand)
                true
            }
            else -> throw IllegalArgumentException("action 不在 P4 测试允许范围内")
        }
        P4TestCommandResult(command.requestId, accepted, snapshot(subject))
    }

    private suspend fun ownedSubject(requestId: String): P4TestSubjectEntity {
        require(requestId.isNotBlank()) { "请指定本轮测试资料 requestId" }
        return checkNotNull(store.p4TestSubject(requestId)) { "没有找到本轮创建的 P4 测试资料" }
    }

    private suspend fun validateSubject(subject: P4TestSubjectEntity) {
        val conversation = checkNotNull(store.getConversation(subject.conversationId)) { "P4 测试会话已不存在" }
        check(conversation.personaId == subject.personaId) { "测试会话人物归属已变化" }
        val sticker = store.userStickers().firstOrNull { it.id == subject.userStickerId }
        check(sticker == null || sticker.mediaId == subject.mediaId) { "测试收藏或媒体归属已变化" }
        check(store.mediaAsset(subject.mediaId) != null) { "测试媒体已不存在" }
    }

    private suspend fun snapshot(subject: P4TestSubjectEntity): P4TestSnapshot {
        val timeline = store.timelineOf(subject.conversationId)
        val messages = timeline.filterIsInstance<TimelineEntry.MessageRow>().map { it.message }
        val ids = messages.map { it.id }
        val attached = store.mediaAssetsForMessages(ids)
        val stickers = store.messageStickers(ids).mapTo(mutableSetOf()) { it.messageId }
        val rows = timeline.mapNotNull { entry -> when (entry) {
            is TimelineEntry.MessageRow -> {
                val files = attached[entry.message.id].orEmpty().map { (attachment, asset) ->
                    P4TestAttachment(attachment.ordinal, asset.id, asset.sha256, runCatching { media.file(asset).isFile }.getOrDefault(false))
                }
                P4TestMessage(entry.message.id, entry.message.role.name.lowercase(), entry.message.text, entry.message.sentToModel,
                    entry.message.id in stickers, files)
            }
            is TimelineEntry.EventRow -> P4TestEvent(entry.event.id, entry.event.kind.name.lowercase(), entry.event.displayText, entry.event.delivery.name)
        } }
        val calls = store.calls(subject.conversationId).map { P4TestCall(it.id, it.status.name, it.input.value, it.output.value) }
        return P4TestSnapshot(ui.state(subject.conversationId), rows.filterIsInstance<P4TestMessage>(),
            rows.filterIsInstance<P4TestEvent>(), store.personaStickers(subject.personaId), calls)
    }

    private fun operationId(subjectId: String, requestId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$subjectId\u0000$requestId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "p4test-$digest"
    }
}
