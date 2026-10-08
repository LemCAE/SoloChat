package app.solochat.core.model

/**
 * 用户那一句"是什么"：**说话 / 动作 / 环境**。
 *
 * ## 为什么需要一个类型，而不是让用户自己打标记
 *
 * 面对面模式里，"我做了什么""我感觉到什么""我说了什么"是三件不同的事，而模型
 * **只能靠标记分辨** —— 它拿到的是 `对方：<正文>` 这么一行。没有类型，用户写
 * "把伞收起来"，模型只能猜那是台词还是动作，而猜错的代价是它回一句不搭界的话。
 *
 * 感官（"闻到雨味""手有点凉"）**并进动作**，不单开一类：协议里它们本来就都是
 * `act`（"你自己的动作、神态、语气"），第一人称体验和动作是同一类东西，
 * 单开一类只会让用户在三个按钮之间犹豫。
 *
 * ## 为什么标记写进 `Message.text`，而不是给 `message` 加一列
 *
 * 因为**助手侧早就是这么干的**：`PromptBuilder.eventsToAssistantText` 把 assistant 的
 * `act` 渲染成 `*…*`、`scene` 渲染成 `（…）`，然后整段存进 `message.text`。
 * 用户侧照做是沿用既有约定，而且**不用动表结构** —— 这个仓库还没有任何 Migration，
 * 而 `SoloChatDatabase` 刻意没用 `fallbackToDestructiveMigration`
 * （"宁可启动失败也不静默清库"），所以加列的风险是"用户升级后打不开 App"。
 *
 * 代价是界面得反过来认一下前后缀。所以编解码**只有这一处**，投影层与渲染层都走它，
 * 并由 `UserUtteranceTest` 钉住"正文里正常出现星号/括号不会被误判"。
 * 等哪天要加第四、五种类型、或者规则开始需要嵌套与转义，再一次性迁到"加列"。
 */
object UserUtterance {

    /** 用户在输入框里能选的三种。`wait` / `read` / `remember` 对用户没有意义。 */
    val kinds: List<EventKind> = listOf(EventKind.SAY, EventKind.ACT, EventKind.SCENE)

    /** 编码成与助手侧同一套标记。 */
    fun encode(kind: EventKind, text: String): String {
        val body = text.trim()
        return when (kind) {
            EventKind.ACT -> "*$body*"
            EventKind.SCENE -> "（$body）"
            else -> body
        }
    }

    /**
     * 反解。**只认"整条被成对包住"**的情况：
     *
     * - `*…*` 且中间没有别的星号 → 动作
     * - `（…）` 且中间没有别的右括号 → 环境
     * - 其余一律当"说话"
     *
     * 所以「我明天\*可能\*来」（星号在中间）不会被误判成动作，
     * 而「（笑）」这种整条括起来的，按约定就是环境描写。
     */
    fun decode(text: String): Pair<EventKind, String> {
        val t = text.trim()
        if (t.length >= 2 && t.startsWith('*') && t.endsWith('*')) {
            val body = t.substring(1, t.length - 1)
            if (!body.contains('*')) return EventKind.ACT to body.trim()
        }
        if (t.length >= 2 && t.startsWith('（') && t.endsWith('）')) {
            val body = t.substring(1, t.length - 1)
            if (!body.contains('）')) return EventKind.SCENE to body.trim()
        }
        return EventKind.SAY to t
    }

    /** 界面上要显示的正文（标记剥掉）。 */
    fun displayText(text: String): String = decode(text).second
}
