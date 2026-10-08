package app.solochat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 会话模式。
 *
 * 这是**会话上的一个可变属性**，不是会话的类型 —— 同一段对话可以从聊天切进面对面、
 * 再切回聊天，历史始终是一条线。这样切回聊天后模型仍然记得门口那段
 * （独立会话做不到这件事）。见 docs/01-decisions.md D7。
 */
@Serializable
enum class ChatMode {
    /** 像微信一样发消息。 */
    @SerialName("chat")
    CHAT,

    /** 面对面：多层次的场景 / 动作 / 对白。 */
    @SerialName("scene")
    SCENE;

    /** 给模型看的字符串（进提示词与协议字段）。 */
    val wire: String get() = if (this == CHAT) "chat" else "scene"
}

@Serializable
enum class MessageRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    /** 系统插入的事实：模式切换分隔行、主动消息触发、被打断标记等。 */
    @SerialName("system")
    SYSTEM
}
