package app.solochat.data.logweb

import app.solochat.core.ports.ClockPort
import app.solochat.core.model.MediaDraft
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

@Serializable data class P4TestSetupRequest(val requestId: String)
@Serializable data class P4TestSetupResult(
    val requestId: String, val personaId: String, val conversationId: String,
    val userStickerId: String, val mediaId: String, val title: String, val createdAt: Long
)
@Serializable data class P4TestCommand(
    val requestId: String, val subjectRequestId: String, val action: String,
    val text: String = "", val mode: String = ""
)
@Serializable data class P4TestCommandResult(val requestId: String, val accepted: Boolean, val state: UiTestState)

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

    suspend fun state(requestId: String): UiTestState = withContext(dispatcher) {
        val subject = ownedSubject(requestId)
        ui.state(subject.conversationId)
    }

    suspend fun execute(command: P4TestCommand): P4TestCommandResult = withContext(dispatcher) {
        require(command.requestId.isNotBlank() && command.requestId.length <= 80) { "requestId 需为 1～80 个字符" }
        val subject = ownedSubject(command.subjectRequestId)
        val controller = checkNotNull(store.getConversation(subject.conversationId)).let {
            check(it.personaId == subject.personaId) { "测试会话人物归属已变化" }
            sessions.get(subject.conversationId).also { it.awaitReady() }
        }
        val accepted = when (command.action) {
            "sticker" -> withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                controller.enqueueSticker(subject.conversationId, subject.personaId,
                    subject.userStickerId, subject.mediaId,
                    submissionId = "p4test-${subject.requestId}-${command.requestId}")
            }
            "image" -> withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                controller.submitMedia(MediaDraft(subject.conversationId, emptyList(), listOf(subject.mediaId),
                    clock.now(), version = 0, submissionId = "p4test-${subject.requestId}-${command.requestId}"))
            }
            "send", "push", "mode" -> {
                val uiCommand = when (command.action) {
                    "send" -> UiTestCommand(subject.conversationId, "p4test-${subject.requestId}-${command.requestId}",
                        "send", text = command.text)
                    "push" -> UiTestCommand(subject.conversationId, "p4test-${subject.requestId}-${command.requestId}", "push")
                    else -> UiTestCommand(subject.conversationId, "p4test-${subject.requestId}-${command.requestId}", "mode", mode = command.mode)
                }
                ui.execute(uiCommand)
                true
            }
            else -> throw IllegalArgumentException("action 只能为 sticker/image/send/push/mode")
        }
        P4TestCommandResult(command.requestId, accepted, ui.state(subject.conversationId))
    }

    private suspend fun ownedSubject(requestId: String): P4TestSubjectEntity {
        require(requestId.isNotBlank()) { "请指定本轮测试资料 requestId" }
        return checkNotNull(store.p4TestSubject(requestId)) { "没有找到本轮创建的 P4 测试资料" }
    }
}
