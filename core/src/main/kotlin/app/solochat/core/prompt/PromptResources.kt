package app.solochat.core.prompt

/**
 * 提示词资源。
 *
 * 为什么不把提示词硬编码在 Kotlin 里：**这是整个项目迭代最频繁的部分**。
 * 改一句约束就要重新编译 APK 的话，调语气调不动。所以放在 assets 里按段拆文件，
 * 加载时拼装（Android 侧读 assets，测试里直接用字符串）。
 *
 * 分段的理由是有层级：安全规则必须在最前面且**优先级最高**，
 * 任何人物卡 / 记忆 / 扩展都不能覆盖它（这条是从 QQ-agent 学来的——
 * 一次误配置就能把"不泄露密钥"整套删掉，见那边 prompt.js 的注释）。
 */
data class PromptResources(
    /** 最高优先级：身份、安全、绝不可被覆盖的规则。 */
    val safetyRules: String,

    /** 事件协议说明（事件类型表 + 示例）。这段决定模型输出格式是否稳定。 */
    val protocolChat: String,
    val protocolScene: String,

    /** 聊天模式的说话风格约束（分条、长度、反模式清单）。 */
    val styleChat: String,

    /** 面对面模式的约束（不替用户决定、场景描写尺度）。 */
    val styleScene: String,

    /** 怎么记东西（`remember` 事件的用法与红线）。 */
    val memory: String,

    /** 主动消息的约束（什么时候可以主动开口、一次最多几条）。 */
    val proactive: String
) {
    /**
     * 占位符替换。
     *
     * 提示词文件里用 `{{persona_name}}` 这类占位符而不是让 Kotlin 拼字符串，
     * 是为了让文案在文件里读起来是完整的（"你是「{{persona_name}}」"），
     * 而不是散在代码里的 `"你是「" + name + "」"`。
     */
    fun substitute(name: String, modeLabel: String): PromptResources = copy(
        safetyRules = safetyRules.replace("{{persona_name}}", name).replace("{{mode_label}}", modeLabel),
        protocolChat = protocolChat.replace("{{persona_name}}", name).replace("{{mode_label}}", modeLabel),
        protocolScene = protocolScene.replace("{{persona_name}}", name).replace("{{mode_label}}", modeLabel),
        styleChat = styleChat.replace("{{persona_name}}", name),
        styleScene = styleScene.replace("{{persona_name}}", name),
        memory = memory.replace("{{persona_name}}", name),
        proactive = proactive.replace("{{persona_name}}", name)
    )
    companion object {
        /**
         * 拼装顺序即优先级，**不要随意调整**：
         * 安全规则 → 人物设定 → 模式与协议 → 记忆与上下文。
         */
        fun order(): List<String> = listOf(
            "safety rules",
            "persona",
            "protocol",
            "style",
            "memory",
            "history"
        )
    }
}
