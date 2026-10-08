package app.solochat.ui

/**
 * 「这个对话在用谁」的显示文本。
 *
 * 单独成一个纯函数、而不是在会话列表和聊天标题栏各拼一遍：
 * 两处显示的必须是**同一个字符串**。一旦分叉，用户看到的就是
 * "列表上写着小默、点进去标题栏写着阿岩"，而真正的模型用的是哪一个反而无从判断 ——
 * 把人设做成"每个对话一个"之后，这行字就是唯一的凭证。
 *
 * @param fallback 会话自己没有指定人设（空着，或指向一张已删掉的卡），
 *   当前用的是"退回第一张"的兜底。这种情况下必须标出来。
 */
fun personaLabel(avatar: String, name: String, fallback: Boolean): String {
    val head = listOf(avatar.trim(), name.trim())
        .filter { it.isNotBlank() }
        .joinToString(" ")
    if (head.isEmpty()) return ""
    return if (fallback) "$head（默认）" else head
}
