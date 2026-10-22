package app.solochat.data.logweb

import app.solochat.core.model.ChatMode
import app.solochat.core.model.EventKind
import app.solochat.play.ChatItem
import app.solochat.play.SessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class UiTestPiece(val kind: String = "say", val text: String)

@Serializable
data class UiTestCommand(val conversationId: String, val requestId: String, val action: String,
    val text: String = "", val pieces: List<UiTestPiece> = emptyList(), val mode: String = "")

@Serializable
data class UiTestState(val conversationId: String, val title: String, val persona: String, val mode: String,
    val phase: String, val pendingSendCount: Int, val items: List<String>, val pending: List<String>,
    val live: String?, val modeProposal: String?, val error: String?, val requestId: String = "", val duplicate: Boolean = false)

/** 与 UI 共用应用级会话、命令和真实播出。无需保持聊天页面打开，可随时进入同一会话观察。 */
class UiTestGateway(private val resolve: suspend (String) -> SessionController,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private val accepted = linkedMapOf<Pair<String, String>, UiTestCommand>()

    private suspend fun controller(id: String): SessionController {
        require(id.isNotBlank()) { "请指定目标会话" }
        val controller = resolve(id)
        check(controller.state.value.conversationId == id) { "会话与目标不一致" }
        return controller
    }

    suspend fun state(id: String): UiTestState = withContext(dispatcher) { describe(controller(id)) }

    suspend fun execute(command: UiTestCommand): UiTestState = withContext(dispatcher) {
        val controller = controller(command.conversationId)
        require(command.requestId.isNotBlank() && command.requestId.length <= 80) { "requestId 需为 1～80 个字符" }
        require(command.text.length + command.pieces.sumOf { it.text.length } <= 16000) { "测试输入过长" }
        val key = command.conversationId to command.requestId
        accepted[key]?.let {
            check(it == command) { "相同 requestId 不能对应不同命令" }
            return@withContext describe(controller, command.requestId, true)
        }
        check(!controller.state.value.backupPaused) { "导入副本暂停发送，须先在手机确认恢复" }
        check(controller.state.value.personaId.isNotBlank()) { "请先为这个对话选择人物卡" }
        when (command.action) {
            "send" -> {
                require(command.text.isBlank() || command.pieces.isEmpty()) { "text 与 pieces 只能填写一项" }
                val pieces = command.pieces.ifEmpty { listOf(UiTestPiece(text = command.text)) }
                require(pieces.size in 1..3 && pieces.all { it.text.isNotBlank() }) { "请填写 1～3 段非空输入" }
                require(pieces.map { it.kind }.distinct().size == pieces.size) { "每种输入只能有一段" }
                val mapped = pieces.sortedBy { listOf("say", "act", "scene").indexOf(it.kind) }.map {
                    val kind = when (it.kind) {
                        "say" -> EventKind.SAY
                        "act" -> EventKind.ACT
                        "scene" -> EventKind.SCENE
                        else -> throw IllegalArgumentException("kind 只能为 say/act/scene")
                    }
                    require(kind == EventKind.SAY || controller.state.value.mode == ChatMode.SCENE) { "动作与场景输入仅在面对面模式可用" }
                    kind to it.text
                }
                controller.submitInput(mapped)
            }
            "push" -> controller.push()
            "system" -> {
                require(command.text.isNotBlank()) { "系统消息不能为空" }
                controller.sendSystem(command.text)
            }
            "mode" -> controller.applyMode(when (command.mode) {
                "chat" -> ChatMode.CHAT
                "scene" -> ChatMode.SCENE
                else -> throw IllegalArgumentException("mode 只能为 chat/scene")
            })
            else -> throw IllegalArgumentException("action 只能为 send/push/system/mode")
        }
        accepted[key] = command
        if (accepted.size > 128) accepted.remove(accepted.keys.first())
        describe(controller, command.requestId)
    }

    private fun describe(controller: SessionController, requestId: String = "", duplicate: Boolean = false): UiTestState {
        val state = controller.state.value
        return UiTestState(state.conversationId, state.title, state.personaName, state.mode.wire,
            state.turnPhase.name, state.pendingSendCount, state.items.map(::body), state.pendingItems.map(::body),
            state.live?.text, state.modeProposal?.wire, state.error?.message, requestId, duplicate)
    }

    private fun body(item: ChatItem): String = when (item) {
        is ChatItem.Mine -> item.text
        is ChatItem.Theirs -> item.text
        is ChatItem.Action -> item.text
        is ChatItem.MyAction -> item.text
        is ChatItem.Narration -> item.text
        is ChatItem.Divider -> item.text
        is ChatItem.Live -> item.text
    }
}
