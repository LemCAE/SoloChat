package app.solochat.core.protocol

/**
 * 把"整段 JSON 被当成一条 `say`"的历史事件**拆回它本来该有的几条**。
 *
 * ## 为什么需要它
 *
 * 2026-10-08 之前，模型的 JSON 只要有一个字符不合法（真机上是
 * `{ "type": "typing_ms": 2500 }` 里那个多出来的冒号），解析层就整份放弃，
 * 于是原文被降级成**一条 `say`** 落盘 —— 时间轴上就是"一条气泡里全是 JSON"。
 * 解析层已经修好（[ScriptParser] 现在分四层，见 `docs/01-decisions.md` D11），
 * 但**已经落进库的那些**不会自己消失：它们躺在 `event` 表里，界面照着画。
 *
 * ## 纪律：只改形状，一个字符的内容都不改
 *
 * - 拆出来的每一条正文都**逐字来自原文** —— `ScriptParser` 只做翻译（选类型、
 *   定说话者、读提示值），**不做改写**，它改不了正文，因为正文就是从 JSON 的
 *   `text` 字段里读出来的。
 * - 拆不动（`degraded`）就返回 null，**原样留着**：那种情况我们也不知道它本来想说什么，
 *   编一份出来比留着更糟（D11）。
 * - 原始输出**仍然留在 `message` 表里**（这一层只动 `event`），要复盘随时能查。
 *
 * ## 它不做什么
 *
 * 不碰交付状态、不碰时间、不碰轮次 —— 那些本地事实由调用方从被替换的那一行**照抄**。
 * 它只回答一个问题："这段 JSON 原本是几条什么。"
 */
object DegradedTurn {

    /**
     * 判据：正文以 `{` 开头，且里面有 `"events"`。
     *
     * 刻意**不**用"能不能解析出来"当判据：解析失败的形式太多（人话、半截 JSON……），
     * 而这里要找的只有一种 —— 早期降级路径的产物，它的形状是确定的
     * （`degradedSay` 存的就是模型的完整原文）。
     */
    fun looksLikeRawScript(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"events\"")
    }

    /**
     * 拆。返回 null = 不是那种行，或者再怎么救也救不回来（那就别动它）。
     *
     * 成功时返回的每一条都带上了 [ScriptEvent.resolvedSpeaker] 口径的说话者，
     * 与正常落盘一致。
     */
    fun split(text: String): List<ScriptEvent>? {
        if (!looksLikeRawScript(text)) return null
        val script = ScriptParser.parse(text)
        if (script.degraded || script.events.isEmpty()) return null
        return script.events
    }
}
