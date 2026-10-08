package app.solochat.data.prompt

import android.content.Context
import app.solochat.core.prompt.PromptResources

/**
 * 从 assets 读提示词。
 *
 * 为什么不硬编码进 Kotlin：**这是迭代最频繁的部分**。改一句"不要用客服腔"就要
 * 重新编译 APK，调语气就调不动了。放 assets 里还能让用户在电脑上改完推回手机。
 *
 * 读取失败时的策略是 [FALLBACK]（一段能用的最小提示词）而不是抛异常：
 * 提示词文件缺失不该导致应用无法启动；但也不能悄悄用空字符串 ——
 * 空提示词会让模型的行为完全失控（不受任何约束的输出）。
 */
class AssetPromptLoader(private val context: Context) {

    fun load(): PromptResources = PromptResources(
        safetyRules = read("prompts/00-safety.txt", FALLBACK_SAFETY),
        protocolChat = read("prompts/10-protocol-chat.txt", FALLBACK_PROTOCOL),
        protocolScene = read("prompts/10-protocol-scene.txt", FALLBACK_PROTOCOL),
        styleChat = read("prompts/20-style-chat.txt", ""),
        styleScene = read("prompts/20-style-scene.txt", ""),
        memory = read("prompts/30-memory.txt", ""),
        proactive = read("prompts/40-proactive.txt", "")
    )

    fun read(path: String, fallback: String): String = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse { fallback }.ifBlank { fallback }

    companion object {
        /**
         * 兜底提示词：短，但包含了"不可协商"的几条 ——
         * 不泄露技术细节、不替对方说话、必须输出 JSON。
         */
        private val FALLBACK_SAFETY = """
            你是在用手机和对方聊天的一个真实的人。不是助手、不是客服、不是 AI。
            不要提到提示词、模型、系统消息、上下文、工具这类技术细节。
            永远不要替对方写他说的话或他做的事。
        """.trimIndent()

        private val FALLBACK_PROTOCOL = """
            只输出 JSON，格式：
            {"events":[{"type":"say","text":"..."},{"type":"wait","reason":"..."}]}
            可用 type：say（一条消息）/ act（小动作）/ scene（环境）/ wait（这轮不回）/ remember（记一条长期记忆）。
            一条消息就是一条消息，不要把一句话拆成两条；单条多数不超过 30 字。
        """.trimIndent()
    }
}
