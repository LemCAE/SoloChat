package app.solochat.core.model

import kotlinx.serialization.Serializable

/**
 * 会话。**模式是它身上的一个可变属性**（[mode]），不是它的类型 —— 见 docs/01-decisions.md D7。
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",

    val mode: ChatMode = ChatMode.CHAT,

    /** 进入当前模式的时刻（毫秒）。用于界面上"—— 相遇 ——"这类分隔行的定位。 */
    val modeSince: Long = 0,

    /** 当前模式开始时的最后一条事件序号。切回聊天时靠它找回场景段落做摘要。 */
    val modeSinceEventId: Long = 0,

    /** 人物卡 id（可为空 = 用默认人设）。 */
    val personaId: String = "",

    val createdAt: Long = 0,
    val updatedAt: Long = 0,

    /** 置顶。 */
    val pinned: Boolean = false,

    /** 归档（不删数据，只是从主列表里收起来）。 */
    val archived: Boolean = false,

    /** 主动消息冷却到什么时候（毫秒）。0 = 不限。 */
    val proactiveCooldownUntil: Long = 0,
    /** null = 继承全局，仅覆盖模型 id。 */
    val modelOverride: String? = null,
    val activeContextRevisionId: String? = null,
    val compressionMethod: String = "ORIGINAL"
)

/**
 * 人物卡。
 *
 * ## 字段是"这个人"的七个侧面，而且是**按模式注入**的
 *
 * 分这么多段不是为了好看，而是每一段进的是提示词里不同的位置、管的是不同的东西：
 *
 * | 段落 | 管什么 | 发消息 | 面对面 |
 * |---|---|---|---|
 * | [identity] 你是谁 | 身份、背景、性格、关系（**外形也写这里**） | ✅ | ✅ |
 * | [voice] 你怎么说话 | 打字时的口头禅、句长、标点 | ✅ | ✅ |
 * | [sceneVoice] 面对面时怎么说话 | 当面说话与打字的差别 | — | ✅ |
 * | [mannerisms] 习惯与小动作 | `act` 的素材：动作、神态、身体反应 | ✅ | ✅ |
 * | [surroundings] 它身边的样子 | `scene` 的素材：住处、光线、声音、物件 | — | ✅ |
 * | [depiction] 怎么描写动作与景物 | **描写这些素材时的笔法**（写成什么样） | — | ✅ |
 * | [boundaries] 关系与边界 | 称呼、亲密程度、不该做的事 | ✅ | ✅ |
 * | [secretNotes] 只有你知道的事 | 里设定，明确要求不要直说 | ✅ | ✅ |
 *
 * [mannerisms] / [surroundings] 是**素材**（写什么），[depiction] 是**笔法**（怎么写）——
 * 三者一起决定面对面模式的观感。后两段与 [sceneVoice] 一样只在面对面注入：
 * 它们是描写素材与描写笔法，在发消息模式里给模型这些东西，它就会开始写小作文 ——
 * 而"像在发微信"恰恰要求它别这么做。详见 docs/03-persona-format.md。
 *
 * QQ-agent 效果好的关键恰恰是 [voice] 那批反模式约束（不要客服腔、单条 ≤30 字）。
 */
@Serializable
data class Persona(
    val id: String,
    val name: String,

    /** 一行简介，只给用户看（不进提示词）。 */
    val tagline: String = "",

    /** 人是谁：身份、背景、性格、与用户的关系。**外形也写在这里**（面对面时会用到）。 */
    val identity: String = "",

    /** 怎么说话（隔着手机打字时）：口头禅、语气、标点习惯、主动程度。 */
    val voice: String = "",

    /**
     * 面对面时怎么说话。
     *
     * 和 [voice] 分开是因为**当面和打字真的不一样**：台词更短、更碎、更容易断在半句，
     * 而且表达状态主要靠动作，不靠把情绪说出来。留空就沿用 [voice] 的约束。
     */
    val sceneVoice: String = "",

    /**
     * 习惯与小动作：常做的动作、神态、身体反应。
     *
     * 这是 `act` 的素材库。写"紧张时会做什么、高兴时会做什么"，
     * 提示词里会要求模型**反复用这几个**，而不是每轮发明一个新花样 ——
     * 固定的习惯才像一个人。
     */
    val mannerisms: String = "",

    /**
     * 它身边的样子：住处、常在的环境、光线声音气味、身边的东西。
     *
     * 这是 `scene`（景物描写）的素材库。写具体的东西（"桌角有只鲸鱼摆件"），
     * 不要写"环境很温馨"这种形容词 —— 形容词模型自己会编，具体的东西它编不出来。
     */
    val surroundings: String = "",

    /**
     * 怎么描写动作与景物：**这个人的笔法**。
     *
     * [mannerisms] / [surroundings] 管"写什么"，这一段管"**写成什么样**"：
     * 细节取向（偏视觉还是偏听觉）、句子长短、用不用比喻、观察距离是冷是近、
     * 一句里给几个焦点。
     *
     * 为什么值得单独一段：全局的 `20-style-scene.txt` 管的是**反模式**
     * （不写内心独白、不堆排比、不写成小说）—— 那是所有人的底线；
     * 而"这个人被描写出来是什么质感"是人物的一部分：同一条底线之上，
     * 有人该是干燥短促的，有人该是黏稠多细节的。留空就只受全局风格约束。
     */
    val depiction: String = "",

    /** 关系与边界：称呼、亲密程度、不该做的事。 */
    val boundaries: String = "",

    /** 只给提示词用的补充设定（用户看不见的"里设定"）。 */
    val secretNotes: String = "",

    /** 头像：文字/emoji 或 AvatarRef 本机图片引用，不保存绝对文件路径。 */
    val avatar: String = "🙂",

    /** 是否内置模板（内置的不可删除，只能复制）。 */
    val builtin: Boolean = false,

    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    /**
     * 拼进提示词的人物设定正文。**按模式注入**（见类注释的表格）。
     *
     * 空段落直接不出现（而不是留一个空标题）：留空标题会让模型觉得"这里应该有内容"，
     * 于是自己编一段补上 —— 那比没有更糟。
     */
    fun toPromptBlock(mode: ChatMode = ChatMode.CHAT): String = buildString {
        fun section(title: String, body: String) {
            if (body.isBlank()) return
            appendLine()
            appendLine("### $title")
            appendLine(body.trim())
        }

        appendLine("### 你是谁")
        appendLine(identity.ifBlank { "（未设定）" })
        section("你怎么说话", voice)
        if (mode == ChatMode.SCENE) section("面对面时怎么说话", sceneVoice)
        section("习惯与小动作", mannerisms)
        if (mode == ChatMode.SCENE) {
            section("它身边的样子", surroundings)
            // 素材之后紧跟"怎么写这些素材"：顺序即阅读顺序，模型先拿到东西再拿到笔法
            section("怎么描写动作与景物", depiction)
        }
        section("关系与边界", boundaries)
        section("只有你知道的事（不要直接说出来）", secretNotes)
    }.trimEnd()
}

/**
 * 出厂那张卡的 id（见 `PersonaDefaults.default()`）。
 *
 * 定义在这里而不是散在两侧：它同时被"出厂的种子"和"兜底人设"用，
 * 两边写两个字符串早晚会对不上，而对不上的表现是"所有没指定人设的对话突然换了个人"。
 */
const val DEFAULT_PERSONA_ID = "default"

/**
 * 会话**当前实际生效**的人设。
 *
 * 为什么要有这个函数、而不是各写各的：这个"先按 id 找、找不到退回出厂那张"的回退规则
 * 原本埋在 `AgentTurnRunner` 里，而现在**界面也要显示同一件事**（会话列表与聊天标题栏
 * 都要标出"这个对话在用谁"）。两处各写一遍，早晚出现"界面显示的是甲、模型用的是乙" ——
 * 而这种分叉在界面上完全看不出来，只有对话内容会变得莫名其妙。
 *
 * 兜底优先用**出厂那张**（而不是列表里的第一张）：出厂卡一定存在，而且不随
 * "最近改过哪张卡"变化。否则用户删掉一张卡之后，老对话会突然换到另一张刚改过的卡上。
 */
fun resolvePersona(personas: List<Persona>, personaId: String?): Persona? {
    if (personas.isEmpty()) return null
    if (!personaId.isNullOrBlank()) {
        personas.firstOrNull { it.id == personaId }?.let { return it }
    }
    // id 为空的坏数据没有任何会话能引用到它，让它当兜底只会表现为"所有对话突然换了个人"
    return personas.firstOrNull { it.id == DEFAULT_PERSONA_ID }
        ?: personas.firstOrNull { it.id.isNotBlank() }
        ?: personas.first()
}
