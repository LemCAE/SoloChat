package app.solochat.ui.chat

/** 只认输入开头的完整命令；正文中间的斜杠和相似单词仍是普通消息。 */
object SlashCommands {
    const val SYSTEM = "/system"
    const val COMPRESS = "/compress"
    fun isCompress(text: String): Boolean = text == COMPRESS ||
        (text.startsWith(COMPRESS) && text.getOrNull(COMPRESS.length)?.isWhitespace() == true)
    fun compressBody(text: String): String = text.removePrefix(COMPRESS).trimStart()
    fun suggestCompress(text: String): Boolean = isCompress(text) ||
        (text.startsWith("/") && text.none { it.isWhitespace() } && COMPRESS.startsWith(text))
    fun removeCompressSuggestion(text: String): String = if (isCompress(text)) compressBody(text) else ""
    fun isSystem(text: String): Boolean =
        text == SYSTEM || (text.startsWith(SYSTEM) && text.getOrNull(SYSTEM.length)?.isWhitespace() == true)

    fun systemBody(text: String): String = text.removePrefix(SYSTEM).trim()

    fun suggestSystem(text: String): Boolean =
        text.startsWith("/") && text.none { it.isWhitespace() } && SYSTEM.startsWith(text)
}
