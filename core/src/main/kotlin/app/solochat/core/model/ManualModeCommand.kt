package app.solochat.core.model

/** 只认明确的切换命令，不把普通系统事实里提到的“面对面”当操作。 */
object ManualModeCommand {
    fun systemBody(body: String): ChatMode? = when (body.trim().lowercase()) {
        "scene", "面对面", "进入面对面", "进入面对面模式", "切换到面对面", "切换到面对面模式" -> ChatMode.SCENE
        "chat", "发消息", "聊天模式", "进入聊天模式", "切换到聊天模式", "切换到发消息" -> ChatMode.CHAT
        else -> null
    }

    fun parse(text: String): ChatMode? = when (text.trim()) {
        "/scene" -> ChatMode.SCENE
        "/chat" -> ChatMode.CHAT
        else -> if (text.startsWith("/system") && text.getOrNull(7)?.isWhitespace() == true)
            systemBody(text.substring(7)) else null
    }
}
