package app.solochat.core.persona

import app.solochat.core.model.AvatarRef
import app.solochat.core.model.Persona
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 从一份文档里读出来的**一张人物卡**（还没有 id、也没有时间戳）。
 *
 * 与 [Persona] 的区别和 `ScriptEvent` 与 `Event` 的关系一样：这里只有"文档里写了什么"，
 * 没有任何本地事实（id / 创建时间 / 是否出厂）。中间的翻译层就是 [PersonaDocument]。
 *
 * `@Serializable` 是为了**写出去**：导出与模板都走 kotlinx，不手拼 JSON
 * （手拼 JSON 在这个项目里已经栽过两次，见 `ResponseJson` 的注释）。
 */
@Serializable
data class PersonaDraft(
    val name: String = "",
    val tagline: String = "",
    val avatar: String = "",
    val identity: String = "",
    val voice: String = "",
    val sceneVoice: String = "",
    val mannerisms: String = "",
    val surroundings: String = "",
    val depiction: String = "",
    val boundaries: String = "",
    val secretNotes: String = ""
) {
    /**
     * 有没有读到任何实质内容。
     *
     * **只有名字不算** —— 一张空卡导进去没有意义，还会让人以为导入成功了。
     */
    val hasContent: Boolean
        get() = listOf(
            tagline, identity, voice, sceneVoice, mannerisms, surroundings, depiction, boundaries, secretNotes
        ).any { it.isNotBlank() }

    /** 哪些段落有内容。导入预览里逐张列出来，让用户一眼看出"少读了哪段"。 */
    fun filledSections(): List<String> = buildList {
        if (identity.isNotBlank()) add("它是谁")
        if (voice.isNotBlank()) add("怎么说话")
        if (sceneVoice.isNotBlank()) add("面对面时怎么说话")
        if (mannerisms.isNotBlank()) add("习惯与小动作")
        if (surroundings.isNotBlank()) add("它身边的样子")
        if (depiction.isNotBlank()) add("怎么描写动作与景物")
        if (boundaries.isNotBlank()) add("关系与边界")
        if (secretNotes.isNotBlank()) add("只有它知道的事")
    }

    /** 落成可入库的人物卡。**导入的一律不是出厂卡**，否则用户删不掉。 */
    fun toPersona(id: String, now: Long): Persona = Persona(
        id = id,
        name = name.ifBlank { DEFAULT_NAME },
        tagline = tagline,
        identity = identity,
        voice = voice,
        sceneVoice = sceneVoice,
        mannerisms = mannerisms,
        surroundings = surroundings,
        depiction = depiction,
        boundaries = boundaries,
        secretNotes = secretNotes,
        avatar = avatar.ifBlank { "🙂" },
        builtin = false,
        createdAt = now,
        updatedAt = now
    )

    companion object {
        /** 文档里没写名字时的兜底名。刻意让它一眼能看出"这是导入的、还没起名"。 */
        const val DEFAULT_NAME = "导入的人物"
    }
}

/**
 * [PersonaDraft.toPersona] 的反方向。
 *
 * 存在的理由是「导出」这个动作：用户把一张卡导出成文件，既能拿去改，
 * 也能直接当**格式示例**贴给模型（比读规范快得多）。
 */
fun Persona.toDraft(): PersonaDraft = PersonaDraft(
    name = name,
    tagline = tagline,
    avatar = avatar,
    identity = identity,
    voice = voice,
    sceneVoice = sceneVoice,
    mannerisms = mannerisms,
    surroundings = surroundings,
    depiction = depiction,
    boundaries = boundaries,
    secretNotes = secretNotes
)

/**
 * 把文档内容**填进一张已有的卡**（编辑页里的"导入文档"）。
 *
 * 与 [toPersona] 的分工：
 *
 * - [toPersona] 是"从零建一张新卡"，所以它要**发号**（id）和**打时间戳**；
 * - 这里是"改一张已有的卡"，所以要**保留本地事实**：`id` / `builtin` / `createdAt`
 *   一个字都不动。`builtin` 尤其重要 —— 导入不能把一张普通卡变成用户删不掉的出厂卡，
 *   反过来也不能把出厂卡变成普通卡。`updatedAt` 也留着，由保存那一层统一打点。
 *
 * ## 空段落 = "文档里没写这一段"，**不是**"清空"
 *
 * 这是刻意选的**非破坏性**语义：文档里没提到的段落保留编辑框里的原文。
 *
 * 两种语义只在"文档不完整"时才有区别（导出过的完整卡两种行为一样），
 * 而那一刻正是用户最容易出事的时候：他手上这份文档可能只有「怎么说话」一段，
 * 用"清空"语义会**静默删掉**他手敲的另外几段，而且删完看不出来是导入干的。
 * 按这个项目的取舍一贯是"宁可多看一条，也不要让用户发现东西凭空少了"。
 *
 * 代价是用户可能困惑"为什么这段还是旧的"—— 所以界面**必须**在导入前
 * 逐段列出"这次会替换哪几段"，不能只显示一句"导入成功"。
 */
fun PersonaDraft.applyTo(base: Persona): Persona = base.copy(
    name = name.ifBlank { base.name },
    tagline = tagline.ifBlank { base.tagline },
    avatar = avatar.ifBlank { base.avatar },
    identity = identity.ifBlank { base.identity },
    voice = voice.ifBlank { base.voice },
    sceneVoice = sceneVoice.ifBlank { base.sceneVoice },
    mannerisms = mannerisms.ifBlank { base.mannerisms },
    surroundings = surroundings.ifBlank { base.surroundings },
    depiction = depiction.ifBlank { base.depiction },
    boundaries = boundaries.ifBlank { base.boundaries },
    secretNotes = secretNotes.ifBlank { base.secretNotes }
)

/**
 * 一次解析的结果。
 *
 * [warnings] 是**要显示给用户看的**，不是给开发者看的日志：没人会读格式规范，
 * 用户只能靠"我们读到了什么、放弃了什么"来纠正文档。
 */
data class PersonaImport(
    val personas: List<PersonaDraft>,
    val warnings: List<String> = emptyList(),
    /** 实际按哪种格式读的：`markdown` / `json` / 空字符串（什么都没读到）。 */
    val format: String = ""
) {
    val isEmpty: Boolean get() = personas.isEmpty()
}

/**
 * 人设文档解析 —— 把一份文档变成一张或多张人物卡。
 *
 * ## 为什么需要它
 *
 * 真实用法是"让模型写一份人设，再贴进来"，而模型每次给的排版都不一样：
 * 有时 `## 你是谁`，有时 `**身份**`，有时写成 `你是谁：…` 的表单，
 * 有时外面还包一层 ``` 围栏。要求用户按严格规范改写，等于把这个功能废掉。
 * 所以纪律和 [app.solochat.core.protocol.ScriptParser] 一致：
 *
 * **永不抛异常，永不返回"没有结果"**，三条降级路径：
 *
 * 1. 认不出任何小节标题 → 整段正文当作「它是谁」（宁可不分段，也不丢内容）
 * 2. 整份文档被 ``` 包住 → 剥掉围栏再解析
 * 3. 整份读不出东西、而文档里有代码块 → 拿最大的那段代码块重试一次
 *
 * ## 两种格式
 *
 * - **Markdown**（主推）：`# 名字` 分卡，`## 小节` 分字段，也认 `键：值` 表单
 * - **JSON**：`{"personas":[…]}` / `[…]` / 单个对象都认
 *
 * 别名表刻意宽松，因为**同一个意思有太多种写法**（「它是谁」「你是谁」「身份」「人设」
 * 「角色设定」指的是一回事）。但宽松只到"小节标题"这一层：**正文一个字都不猜、不改写**。
 */
object PersonaDocument {

    /**
     * 解析配置。三项容错都对应真实输入：
     * - `ignoreUnknownKeys`：模型爱多带字段（`age`、`gender`、`tags` 之类）
     * - `isLenient`：不问号引号、单引号都见过
     * - `allowTrailingComma`：多一个尾逗号是最常见的"小错"，为它整份失败不值得
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * 写出用的配置。与读的 [json] 刻意分开：
     *
     * - `prettyPrint`：导出的文件是给人看、给模型看的，一行挤成一条没法读
     * - `encodeDefaults`：**空段落也必须写出来**。否则导出的文件里看不出有哪些字段，
     *   而"文件自己就是文档"正是选 JSON 的主要理由
     */
    private val writer = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** 文件格式标识。写进文件里，让"这是不是一份人物卡文档"变成可判定的事。 */
    const val FORMAT_ID = "solochat.persona"

    /**
     * 格式版本。**加字段不用动它**（多出来的字段会被忽略、缺的当空），
     * 只有改了已有字段的语义时才 +1 —— 那时候旧文件读进来会给出告警。
     */
    const val FORMAT_VERSION = 1

    /** 导出文件的顶层结构。 */
    @Serializable
    private data class PersonaFile(
        val format: String,
        val version: Int,
        val personas: List<PersonaDraft>
    )

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /**
     * 解析一份文档。
     *
     * @param raw 文档正文
     * @param fallbackName 文档里没写名字时用的名字。界面传文件名去扩展名 ——
     *   因为「小默.md」里的名字几乎一定就是小默。
     */
    fun parse(raw: String?, fallbackName: String? = null): PersonaImport {
        val text = raw?.removePrefix("\uFEFF")?.trim().orEmpty()
        if (text.isEmpty()) {
            return PersonaImport(emptyList(), listOf("文档是空的"), "")
        }

        val warnings = mutableListOf<String>()

        // 候选正文。规则只有一条，但它决定了两件相反的事：
        //
        // - **正文自己有结构**（有 `# 名字` 或小节标题）→ 只读正文，围栏里的东西当示例丢掉。
        //   不然 `docs/03-persona-format.md` 里那段示例模板会把真正的人设盖掉。
        // - **正文没结构但文档里有代码块** → 先读代码块。这是"模型先说一句'给你'，
        //   然后把整份人设放进 ``` 里"的常见形状。
        //
        // 反过来做会在其中一种情况下读错东西，而且两种错法都很隐蔽（导进来的是示例/说明）。
        val outside = dropFencedRegions(text)
        val fenced = fencedBlocks(text).sortedByDescending { it.length }
        val candidates: List<String> = when {
            fenced.isEmpty() -> listOf(text)
            hasStructure(outside) -> listOf(text) + fenced
            else -> fenced + listOf(outside)
        }

        for ((index, candidate) in candidates.withIndex()) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                parseJson(trimmed, warnings)?.let {
                    return finish(it, warnings, "json", fallbackName)
                }
            }

            val drafts = parseMarkdown(trimmed, warnings)
            if (drafts.isNotEmpty()) {
                if (index > 0) warnings += "正文里读不出人设，已改用文档中的代码块"
                return finish(drafts, warnings, "markdown", fallbackName)
            }
        }

        return PersonaImport(
            personas = emptyList(),
            warnings = warnings + "没读到可用的人设内容。最少要有一个 `# 名字` 加一段正文。",
            format = ""
        )
    }

    /**
     * 导出成 JSON —— **这是正式格式**。
     *
     * 为什么最后选了 JSON 而不是 Markdown：
     *
     * - **键名与字段一一对应，不用猜。** Markdown 要认别名、标题层级、加粗、代码围栏，
     *   读错了还只能靠一句告警提示用户 —— 那是在替一份本来就没有歧义的格式擦屁股。
     * - **文件自己就是文档。** 空段落也写成 `""`，于是把这份文件贴给模型看，
     *   它一眼就知道有哪些字段、该填什么，不需要另外给它一段格式说明。
     * - 任何语言/工具都能校验，往返一个字都不丢。
     *
     * Markdown **仍然读得进来**（见 [parse]），但那是兼容老文件与"顺手粘一段"的退路，
     * 不是格式本身。
     */
    fun toJson(drafts: List<PersonaDraft>): String = writer.encodeToString(
        PersonaFile.serializer(),
        PersonaFile(FORMAT_ID, FORMAT_VERSION, drafts)
    )

    fun toJson(draft: PersonaDraft): String = toJson(listOf(draft))

    /**
     * 空白模板（也是 JSON，理由同上）。
     *
     * 它由 [toJson] 生成而不是手写一段字符串：手写的话，将来加字段忘了改模板，
     * 用户复制到的就是一份**缺字段的**模板 —— 而那种错误只有导入时才暴露。
     *
     * 占位文字都写成**一行**：JSON 里的多行要转义成 `\n`，反而更难读。
     */
    fun template(): String = toJson(
        PersonaDraft(
            name = "小默",
            tagline = "一句话介绍（只给你自己看，不会进对话）",
            avatar = "🌙",
            identity = "名字、年龄、身份、背景、性格、和你是什么关系。写具体的经历和立场，不要只堆性格标签。外貌（长什么样、常穿什么）也写在这里。",
            voice = "隔着手机打字时的习惯：句子长短、常用语气词、标点习惯、什么时候会沉默、哪些词不说。这一段最影响像不像真人。",
            sceneVoice = "面对面时和打字不一样的地方：台词更短更碎、更多停顿、更靠动作而不是把情绪说出来。留空就沿用上面那段。",
            mannerisms = "常做的小动作和神态：紧张时会做什么、高兴时会做什么、心虚时会做什么。这是写 act 的素材库，写具体动作（把头发别到耳后），不要写形容词（有点紧张）。",
            surroundings = "待的地方：光线、声音、气味、桌上的东西、窗外。这是写 scene 的素材库，只在面对面模式用到。",
            depiction = "描写动作与景物时的笔法：偏视觉还是偏听觉、句子长短、用不用比喻、观察距离是冷是近、一句里给几个焦点。留空就只受全局风格约束。",
            boundaries = "怎么称呼你、亲密程度、哪些话题不聊、哪些事不做。",
            secretNotes = "心里知道、但不会直接说出来的设定（顾虑、打算、没说出口的话）。"
        )
    )

    // ------------------------------------------------------------------
    // 槽位与别名
    // ------------------------------------------------------------------

    /** 文档里的一个槽位。名字与头像也在内 —— `## 头像` 这种写法确实见过。 */
    private enum class Slot {
        NAME, AVATAR, TAGLINE, IDENTITY, VOICE,
        /** 面对面时怎么说话。⚠️ 判定必须排在 VOICE 之前：「面对面时怎么说话」里含「说话」。 */
        SCENE_VOICE,
        MANNERISMS, SURROUNDINGS,
        /** 怎么描写动作与景物。⚠️ 判定要排在 MANNERISMS 之前：「动作怎么写」里含「动作」。 */
        DEPICTION,
        BOUNDARIES, SECRET
    }

    /** 归一化时抹掉的字符：标题标记、加粗、各种标点与空白。 */
    private const val NOISE =
        " \t\u3000#*_~-—–·.。!！?？,，、:：;；\"'“”‘’「」『』【】()（）[]<>《》/\\|+="

    private val NAME_KEYS = listOf("名字", "姓名", "名称", "人名", "角色名", "称呼名", "name")
    private val AVATAR_KEYS = listOf("头像", "头像emoji", "emoji", "图标", "表情", "avatar", "icon")
    private val TAGLINE_KEYS = listOf(
        "简介", "一句话简介", "一句话描述", "一句话", "人物简介", "描述", "定位", "副标题",
        "tagline", "description", "desc", "summary"
    )

    private val IDENTITY_KEYS = listOf(
        "你是谁", "它是谁", "他是谁", "她是谁", "身份", "人设", "人物设定", "角色设定",
        "角色", "人物", "设定", "背景", "背景设定", "基本设定", "基本信息", "性格",
        "人物背景", "身份背景", "identity", "persona", "character", "background", "who"
    )
    private val VOICE_KEYS = listOf(
        "你怎么说话", "怎么说话", "说话", "说话方式", "说话风格", "说话习惯", "语言风格",
        "语气", "口吻", "口头禅", "表达方式", "表达习惯", "怎么聊", "聊天风格",
        "voice", "speech", "style", "tone", "language"
    )
    private val SCENE_VOICE_KEYS = listOf(
        "面对面时怎么说话", "面对面怎么说话", "当面怎么说话", "见面时怎么说话",
        "面对面说话", "当面说话", "当面聊", "线下怎么说话", "见面的说话方式",
        "scenevoice", "scenetalk", "facetoface", "facevoice", "inperson"
    )
    private val MANNERISMS_KEYS = listOf(
        "习惯与小动作", "习惯动作", "小动作", "习惯性动作", "动作与神态", "神态",
        "举止", "肢体语言", "身体语言", "习惯", "作息", "小癖好", "招牌动作",
        "mannerisms", "gestures", "habits", "bodylanguage", "quirks"
    )
    /**
     * 别名表里同时收着「它 / 他 / 她」三种写法（`她身边的样子`、`只有她知道的事`…）。
     *
     * ⚠️ **这不是在假定性别，而是在认用户会怎么写。** 用别人的角色卡时，
     * 文档里写「她是谁」「他身边的样子」都很常见 —— 认不出来才是 bug。
     * 界面与出厂人设里的文案**不用**任何第三人称性别代词（见 PersonaScreen / PersonaDefaults）。
     */
    private val SURROUNDINGS_KEYS = listOf(
        "它身边的样子", "他身边的样子", "她身边的样子", "身边的样子", "身边",
        "它周围", "周围", "环境", "环境描写", "景物", "景物描写", "住处", "生活场景",
        "它待的地方", "常在的地方", "场景", "它的世界", "身边的东西", "房间",
        "surroundings", "environment", "setting", "world"
    )
    /**
     * 「怎么描写」：写的是**笔法**（写成什么样），不是素材。
     *
     * 别名刻意排除了光秃秃的「描写」两个字 ——「环境描写」「景物描写」是**素材**那一类，
     * 已经在上面的 SURROUNDINGS_KEYS 里精确命中；判定顺序上也把 SURROUNDINGS 放在前面兜。
     */
    private val DEPICTION_KEYS = listOf(
        "怎么描写", "怎么描写动作与景物", "动作与景物怎么写", "怎么写动作与景物",
        "描写方式", "描写风格", "描写手法", "描写习惯", "写法", "怎么写",
        "旁白风格", "叙述方式", "笔法", "笔调", "文笔",
        "depiction", "narration", "descriptionstyle", "prose", "writingstyle"
    )
    private val BOUNDARIES_KEYS = listOf(
        "关系与边界", "边界与关系", "关系", "边界", "设定边界", "边界设定", "关系设定",
        "称呼", "称呼与关系", "不该做的事", "不能做的事", "禁区", "禁忌", "红线", "规则", "约束",
        "boundaries", "boundary", "relationship", "rules", "taboo"
    )
    private val SECRET_KEYS = listOf(
        "只有你知道的事", "只有它知道的事", "只有他知道的事", "只有她知道的事", "只有你知道",
        "里设定", "内设定", "隐藏设定", "隐藏人设", "隐藏背景", "秘密", "秘密设定", "暗设定",
        "不为人知的事", "背景秘密", "深层设定",
        "secret", "secrets", "hidden", "hiddennotes", "secretnotes", "hiddensetting"
    )

    private val EXACT: Map<String, Slot> = buildMap {
        NAME_KEYS.forEach { put(it, Slot.NAME) }
        AVATAR_KEYS.forEach { put(it, Slot.AVATAR) }
        TAGLINE_KEYS.forEach { put(it, Slot.TAGLINE) }
        IDENTITY_KEYS.forEach { put(it, Slot.IDENTITY) }
        VOICE_KEYS.forEach { put(it, Slot.VOICE) }
        SCENE_VOICE_KEYS.forEach { put(it, Slot.SCENE_VOICE) }
        MANNERISMS_KEYS.forEach { put(it, Slot.MANNERISMS) }
        SURROUNDINGS_KEYS.forEach { put(it, Slot.SURROUNDINGS) }
        DEPICTION_KEYS.forEach { put(it, Slot.DEPICTION) }
        BOUNDARIES_KEYS.forEach { put(it, Slot.BOUNDARIES) }
        SECRET_KEYS.forEach { put(it, Slot.SECRET) }
    }

    private fun normalizeKey(raw: String): String = raw.lowercase().filterNot { it in NOISE }

    /**
     * 精确匹配优先，不中再按包含关系猜一次（顺序的理由见 [fuzzySlot]）。
     *
     * 这个函数用于**被分隔符界定的键**（`键：值` 的键、JSON 的键），所以可以放心地宽松；
     * 而"独立一行算不算小节标题"要严得多，见 [sectionOf]。
     */
    private fun slotOfKey(key: String): Slot? {
        val k = normalizeKey(key)
        if (k.isEmpty()) return null
        EXACT[k]?.let { return it }
        return fuzzySlot(k)
    }

    /**
     * 宽松兜底：键里**含**某个词就认。
     *
     * 顺序全是刻意的，而且每一条都对应一种真实会读错的写法：
     *
     * 1. **SECRET 必须在 IDENTITY 之前**：「里设定」里含「设定」，而「设定」是 IDENTITY 的别名。
     *    判错会让"要求不要直接说出来的内容"混进"它是谁"。
     * 2. **SCENE_VOICE 必须在 VOICE 之前**：「面对面时怎么说话」里含「说话」。
     *    判错会让当面说话的习惯被当成打字习惯，两种模式就串了。
     * 3. **DEPICTION 必须在 MANNERISMS 之前**：「动作怎么写」「描写方式」里含「动作」，
     *    而 MANNERISMS 是靠「动作」这个词认的。判错会把"笔法"当成"素材库"。
     *    它靠"怎么写 / 如何写 / 描写 / 旁白 / 叙述 / 笔调"这几个词触发，都是**谈写法**的信号。
     * 4. **MANNERISMS 在 BOUNDARIES 之前**：「习惯与小动作」这类标题里常带「动作」，
     *    而 BOUNDARIES 那边有「不该做的事」这种句式 —— 先判边界会把动作库吞掉。
     * 5. **SURROUNDINGS 与 DEPICTION 都排在 IDENTITY 之后**：「环境」「场景」这类词最泛，
     *    让它们只在前面的规则都不中时才生效，免得把「背景」这类真属于身份的东西抢走。
     *    而 SURROUNDINGS 又在 DEPICTION 之前：「环境描写」「景物描写」是**素材**，不是笔法。
     */
    private fun fuzzySlot(k: String): Slot? = when {
        k.contains("只有你") || k.contains("只有它") || k.contains("只有他") ||
            k.contains("里设定") || k.contains("内设定") || k.contains("隐藏") ||
            k.contains("秘密") || k.contains("暗设定") || k.contains("不为人知") -> Slot.SECRET

        k.contains("面对面") || k.contains("当面") || k.contains("见面时") ||
            k.contains("线下") || k.contains("inperson") -> Slot.SCENE_VOICE

        k.contains("怎么写") || k.contains("如何写") || k.contains("写法") ||
            k.contains("描写") || k.contains("旁白") || k.contains("叙述") ||
            k.contains("笔调") || k.contains("笔法") || k.contains("文笔") ||
            k.contains("depiction") || k.contains("narration") -> Slot.DEPICTION

        k.contains("小动作") || k.contains("习惯") || k.contains("动作") ||
            k.contains("神态") || k.contains("举止") || k.contains("肢体") ||
            k.contains("作息") || k.contains("癖好") -> Slot.MANNERISMS

        k.contains("边界") || k.contains("关系") || k.contains("禁区") ||
            k.contains("禁忌") || k.contains("红线") || k.contains("不该做") ||
            k.contains("不能做") || k.contains("称呼") || k.contains("约束") -> Slot.BOUNDARIES

        k.contains("说话") || k.contains("语气") || k.contains("口吻") ||
            k.contains("口头禅") || k.contains("表达") || k.contains("语言") ||
            k.contains("怎么聊") -> Slot.VOICE

        k.contains("是谁") || k.contains("身份") || k.contains("人设") ||
            k.contains("角色") || k.contains("性格") || k.contains("背景") ||
            k.contains("设定") || k.contains("资料") || k.contains("介绍") -> Slot.IDENTITY

        k.contains("身边") || k.contains("周围") || k.contains("环境") ||
            k.contains("景物") || k.contains("住处") || k.contains("场景") ||
            k.contains("房间") || k.contains("摆设") ||
            k.contains("surround") || k.contains("environment") -> Slot.SURROUNDINGS

        else -> null
    }

    /**
     * 这一行是不是一个**独立的小节标题**。
     *
     * 两条判据都是踩出来的：
     *
     * 1. `你是谁：一个普通人` 这种「标题：值」形式**不算标题**（走 [splitMeta]），
     *    否则冒号后面的内容会被整段吃掉 —— 而那恰好是模型最常写的排版。
     *    反过来，`你是谁：` 后面什么都不写就**算**标题，后面的行会续进这一段，
     *    于是"表单式文档"（多行值）也能正确解析。
     * 2. 宽松匹配**只对明确写成标题的行生效**（`#` 开头或 `**加粗**`）。
     *    否则正文里一句「说话很直」会因为含"说话"被当成小节标题，
     *    后面整段正文就都归到「怎么说话」里去了。这一条是写往返测试时抓出来的。
     */
    private fun sectionOf(line: String): Slot? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 40) return null

        val colon = maxOf(trimmed.lastIndexOf(':'), trimmed.lastIndexOf('：'))
        if (colon >= 0 && trimmed.substring(colon + 1).isNotBlank()) return null

        val cleaned = trimmed
            .trimStart('#', '-', '+', '>', '*', '_', ' ', '\t', '\u3000')
            .trimEnd(':', '：', '*', '_', '#', ' ', '\t', '\u3000')
            .trim()
        if (cleaned.isEmpty() || cleaned.length > 20) return null
        // 带句末标点或逗号的是一句话，不是标题
        if (cleaned.any { it in "。！？!?；;，," }) return null
        if (cleaned.contains('#')) return null

        val key = normalizeKey(cleaned)
        if (key.isEmpty()) return null
        EXACT[key]?.let { return it }
        val marked = trimmed.startsWith("#") || trimmed.startsWith("**")
        return if (marked) fuzzySlot(key) else null
    }

    /** `键：值` 形式。返回 (键, 值)；不像键值对就返回 null。 */
    private fun splitMeta(line: String): Pair<String, String>? {
        val raw = line.trim()
            .trimStart('-', '*', '•', '+', '>', ' ', '\t', '\u3000')
            .trimEnd('*', '_', ' ', '\t')
        if (raw.isEmpty() || raw.length > 400) return null
        val colon = raw.indexOfFirst { it == ':' || it == '：' }
        if (colon <= 0) return null
        val key = raw.substring(0, colon).trim().trim('*', '_', ' ', '#')
        val value = raw.substring(colon + 1).trim().trim('*', '_', ' ')
        if (key.isEmpty() || key.length > 14 || value.isEmpty()) return null
        // 键里出现句末标点或逗号 = 这是一句话，不是键值对
        if (key.any { it in "。！？!?；;，,、" }) return null
        return key to value
    }

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    private fun parseMarkdown(text: String, warnings: MutableList<String>): List<PersonaDraft> {
        // 代码块（``` 围栏）里的内容**不是正文**：它多半是示例、模板或字段说明。
        // 必须在这里先整段挖掉，否则示例里的 `# 名字` 会被当成第二个人设导进来。
        val rawLines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val insideFence = BooleanArray(rawLines.size)
        var open = false
        for (i in rawLines.indices) {
            if (isFenceLine(rawLines[i])) {
                insideFence[i] = true
                open = !open
                continue
            }
            insideFence[i] = open
        }
        val lines = rawLines.filterIndexed { index, _ -> !insideFence[index] }

        val h1 = lines.indices.filter { isH1(lines[it]) }

        val blocks = mutableListOf<Pair<String?, List<String>>>()
        if (h1.isEmpty()) {
            blocks += null to lines
        } else {
            for ((n, from) in h1.withIndex()) {
                val to = if (n + 1 < h1.size) h1[n + 1] else lines.size
                // 第一个标题之前的行并进第一块，其中的「名字：」「头像：」照常生效
                val head = if (n == 0) lines.subList(0, from) else emptyList()
                blocks += titleOf(lines[from]) to (head + lines.subList(from + 1, to))
            }
        }

        val out = mutableListOf<PersonaDraft>()
        for ((index, block) in blocks.withIndex()) {
            val (title, body) = block
            parseBlock(title, body, warnings, index + 1)?.let { out += it }
        }
        return out
    }

    private fun parseBlock(
        title: String?,
        body: List<String>,
        warnings: MutableList<String>,
        humanIndex: Int
    ): PersonaDraft? {
        val buffers = linkedMapOf<Slot, StringBuilder>()
        val leftover = mutableListOf<String>()
        var current: Slot? = null
        var sawStructure = false

        // 标题里常常带头像（`# 🌙 小默`）
        var name = title.orEmpty()
        var avatar = ""
        if (name.isNotBlank()) {
            splitAvatar(name)?.let { (a, n) ->
                avatar = a
                name = n
            }
        }

        for (rawLine in body) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                // 空行只当分隔：不打断当前小节（正文里本来就有空行），也不产生内容
                val here = current
                if (here != null) buffers.getOrPut(here) { StringBuilder() }.appendLine()
                continue
            }

            val section = sectionOf(line)
            if (section != null) {
                current = section
                sawStructure = true
                buffers.getOrPut(section) { StringBuilder() }
                continue
            }

            val meta = splitMeta(line)
            val metaSlot = meta?.let { slotOfKey(it.first) }
            if (meta != null && metaSlot != null) {
                when (metaSlot) {
                    Slot.NAME -> if (name.isBlank()) name = meta.second
                    Slot.AVATAR -> if (avatar.isBlank()) avatar = meta.second
                    // ⚠️ 元数据行**不设 current**：`简介：话不多` 后面那行正文
                    // 如果被吸进简介，用户就会看到"简介变成了一整段"。
                    else -> {
                        buffers.getOrPut(metaSlot) { StringBuilder() }.appendLine(meta.second)
                        sawStructure = true
                    }
                }
                continue
            }

            // 前言里单独一行的引用 / 斜体 = 一句话简介
            if (current == null) {
                val quote = quoteOf(line)
                if (quote != null) {
                    buffers.getOrPut(Slot.TAGLINE) { StringBuilder() }.appendLine(quote)
                    continue
                }
            }

            val here = current
            if (here == null) leftover += line
            else buffers.getOrPut(here) { StringBuilder() }.appendLine(line)
        }

        fun text(slot: Slot): String = buffers[slot]?.toString()?.trim().orEmpty()

        if (name.isBlank()) name = text(Slot.NAME)
        if (avatar.isBlank()) avatar = text(Slot.AVATAR)

        var identity = text(Slot.IDENTITY)
        val leftoverText = leftover.joinToString("\n").trim()
        val foldedLeftover = identity.isBlank() && leftoverText.isNotBlank()
        if (foldedLeftover) identity = leftoverText

        val draft = PersonaDraft(
            name = name.trim(),
            // 简介只取第一行：多行的话那是正文被误归了
            tagline = text(Slot.TAGLINE).lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
            avatar = cleanAvatar(avatar),
            identity = identity,
            voice = text(Slot.VOICE),
            sceneVoice = text(Slot.SCENE_VOICE),
            mannerisms = text(Slot.MANNERISMS),
            surroundings = text(Slot.SURROUNDINGS),
            depiction = text(Slot.DEPICTION),
            boundaries = text(Slot.BOUNDARIES),
            secretNotes = text(Slot.SECRET)
        )

        if (!draft.hasContent) {
            if (draft.name.isNotBlank()) {
                warnings += "第 $humanIndex 张「${draft.name}」只有名字、没有正文，已跳过"
            }
            return null
        }

        if (draft.name.isBlank()) {
            warnings += "第 $humanIndex 张没有名字，导入后先叫「${PersonaDraft.DEFAULT_NAME}」"
        }
        if (!sawStructure) {
            warnings += "第 $humanIndex 张没认到小节标题，整段正文当成了「它是谁」"
        }
        if (foldedLeftover && sawStructure) {
            warnings += "第 $humanIndex 张有一段没归入小节的正文，已放进「它是谁」"
        }
        if (!foldedLeftover && sawStructure && leftoverText.length > 40) {
            warnings += "第 $humanIndex 张有 ${leftoverText.length} 个字符没归入任何小节，已忽略"
        }
        return draft
    }

    private fun isH1(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("#") && !t.startsWith("##") && t.removePrefix("#").isNotBlank()
    }

    private fun titleOf(line: String): String = line
        .trimStart()
        .removePrefix("#")
        .trim()
        .trimEnd('#', ':', '：', ' ', '\t', '\u3000')
        .trim()

    private fun isFenceLine(line: String): Boolean = line.trimStart().startsWith("```")

    /** 单独一行的 `> 引用` 或 `*斜体*` 当作一句话简介。太长的不认（那是正文）。 */
    private fun quoteOf(line: String): String? {
        val t = line.trim()
        val body = when {
            t.startsWith(">") -> t.trimStart('>', ' ', '\u3000').trim()
            t.length > 2 && ((t.startsWith("*") && t.endsWith("*")) ||
                (t.startsWith("_") && t.endsWith("_"))) -> t.substring(1, t.length - 1).trim()
            else -> return null
        }
        if (body.isEmpty() || body.length > 120) return null
        return body
    }

    /**
     * 把 `🌙 小默` / `小默 🌙` 拆成 (头像, 名字)。
     * 判据是"这一小段里一个字母或数字都没有" —— 只按"非字母数字"判断会把全角冒号也算成头像。
     */
    private fun splitAvatar(raw: String): Pair<String, String>? {
        val text = raw.trim()
        Regex("^(\\S+)\\s+(.+)$").find(text)?.let { m ->
            if (isEmojiToken(m.groupValues[1])) return m.groupValues[1] to m.groupValues[2].trim()
        }
        Regex("^(.+?)\\s+(\\S+)$").find(text)?.let { m ->
            if (isEmojiToken(m.groupValues[2])) return m.groupValues[2] to m.groupValues[1].trim()
        }
        return null
    }

    private fun isEmojiToken(token: String): Boolean {
        if (token.isEmpty() || token.length > 8) return false
        var sawSymbol = false
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp)) {
                Character.OTHER_SYMBOL.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                // 变体选择符（U+FE0F）与组合记号，emoji 序列里很常见
                Character.NON_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> sawSymbol = true

                else -> return false
            }
        }
        return sawSymbol
    }

    private fun cleanAvatar(raw: String): String = if (AvatarRef.imageId(raw.trim()) != null) raw.trim() else raw
        .trim()
        .trim('`', '"', '\'', '*', '「', '」', '【', '】')
        .trim()
        .substringBefore(' ')
        .take(4)

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    private val NESTED_ARRAY_KEYS =
        listOf("personas", "persona_list", "characters", "character_list", "cards", "card_list", "list", "items")
    private val NESTED_OBJECT_KEYS = listOf("persona", "character", "card", "data", "result", "profile")
    private val PERSONA_FIELD_KEYS =
        listOf("name", "名字", "identity", "你是谁", "voice", "怎么说话", "persona", "character")

    private fun parseJson(text: String, warnings: MutableList<String>): List<PersonaDraft>? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (element == null) {
            warnings += "看起来是 JSON，但没解析成功（已按 Markdown 再试一次）"
            return null
        }
        if (element is JsonObject) checkFormatHeader(element, warnings)

        val objects: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { runCatching { it.jsonObject }.getOrNull() }

            is JsonObject -> {
                val nestedArray = NESTED_ARRAY_KEYS.firstNotNullOfOrNull { key ->
                    (element[key] as? JsonArray)?.takeIf { it.isNotEmpty() }
                }
                when {
                    nestedArray != null -> nestedArray.mapNotNull { runCatching { it.jsonObject }.getOrNull() }

                    // {"persona": {...}} 这种单层包裹
                    PERSONA_FIELD_KEYS.none { element[it] != null } ->
                        NESTED_OBJECT_KEYS.firstNotNullOfOrNull { element[it] as? JsonObject }
                            ?.let { listOf(it) } ?: listOf(element)

                    else -> listOf(element)
                }
            }

            else -> emptyList()
        }

        val drafts = objects.map { it.toDraft() }.filter { it.hasContent }
        if (drafts.isEmpty()) {
            warnings += "JSON 里没有读到有内容的人物卡"
            return null
        }
        if (drafts.size < objects.size) {
            warnings += "JSON 里有 ${objects.size - drafts.size} 条只有名字或完全为空，已跳过"
        }
        return drafts
    }

    /**
     * 认一下文件里的 `format` / `version`。
     *
     * 刻意**不拒绝**：认得出是人设文档就尽力读，只在不对劲时给一句人能看懂的告警。
     * 理由是"读不出来"和"读出来了但缺字段"之间，用户更需要后者 ——
     * 他至少能在预览里看见读到了什么、再决定要不要导入。
     */
    private fun checkFormatHeader(root: JsonObject, warnings: MutableList<String>) {
        val format = (root["format"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (format.isNotEmpty() && format != FORMAT_ID) {
            warnings += "文件的 format 是「$format」，不是 $FORMAT_ID —— 按人物卡尽力读了"
        }
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
        if (version != null && version > FORMAT_VERSION) {
            warnings += "文件标的是版本 $version，比这个 App 认识的 $FORMAT_VERSION 新，可能有字段读不到"
        }
    }

    private fun JsonObject.toDraft(): PersonaDraft {
        // JsonObject 是 Map<String, JsonElement>，这里要的是"文档里实际出现了哪些键"
        val presentKeys: List<String> = this.keys.toList()
        fun bySlot(slot: Slot): String {
            explicitKeysFor(slot).forEach { k -> pick(k).takeIf { it.isNotEmpty() }?.let { return it } }
            // 兜底：拿实际存在的键去撞同一张别名表，于是 {"你是谁": "…"} 也认
            for (key in presentKeys) {
                if (slotOfKey(key) == slot) pick(key).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        return PersonaDraft(
            name = pick("name", "名字", "姓名", "名称", "角色名"),
            tagline = pick("tagline", "简介", "一句话简介", "描述", "description", "desc", "summary"),
            avatar = cleanAvatar(pick("avatar", "头像", "emoji", "icon", "图标")),
            identity = bySlot(Slot.IDENTITY),
            voice = bySlot(Slot.VOICE),
            sceneVoice = bySlot(Slot.SCENE_VOICE),
            mannerisms = bySlot(Slot.MANNERISMS),
            surroundings = bySlot(Slot.SURROUNDINGS),
            depiction = bySlot(Slot.DEPICTION),
            boundaries = bySlot(Slot.BOUNDARIES),
            secretNotes = bySlot(Slot.SECRET)
        )
    }

    private fun explicitKeysFor(slot: Slot): Array<String> = when (slot) {
        Slot.IDENTITY -> arrayOf(
            "identity", "你是谁", "它是谁", "身份", "人设", "background", "persona", "character", "设定"
        )
        Slot.VOICE -> arrayOf("voice", "怎么说话", "说话方式", "语气", "语言风格", "speech", "style")
        Slot.SCENE_VOICE -> arrayOf(
            "sceneVoice", "scene_voice", "面对面时怎么说话", "当面怎么说话", "facetoface", "inPerson"
        )
        Slot.MANNERISMS -> arrayOf(
            "mannerisms", "gestures", "habits", "习惯与小动作", "小动作", "动作与神态", "举止", "习惯"
        )
        Slot.SURROUNDINGS -> arrayOf(
            "surroundings", "environment", "setting", "它身边的样子", "身边的样子", "环境", "景物", "住处", "场景"
        )
        Slot.DEPICTION -> arrayOf(
            "depiction", "narration", "prose", "怎么描写", "怎么描写动作与景物",
            "动作与景物怎么写", "描写方式", "描写风格", "旁白风格", "笔调"
        )
        Slot.BOUNDARIES -> arrayOf("boundaries", "boundary", "关系与边界", "关系", "边界", "rules")
        Slot.SECRET -> arrayOf(
            "secretNotes", "secret_notes", "secret", "里设定", "秘密", "hidden", "hiddenNotes"
        )
        else -> emptyArray()
    }

    private fun JsonObject.pick(vararg keys: String): String {
        for (k in keys) {
            val primitive = this[k] as? JsonPrimitive ?: continue
            val text = primitive.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    // ------------------------------------------------------------------
    // 收尾与围栏
    // ------------------------------------------------------------------

    private fun finish(
        drafts: List<PersonaDraft>,
        warnings: MutableList<String>,
        format: String,
        fallbackName: String?
    ): PersonaImport {
        val named = drafts.map { draft ->
            if (draft.name.isNotBlank() || fallbackName.isNullOrBlank()) draft
            else draft.copy(name = fallbackName.trim())
        }
        if (named.size > 1) warnings += "这份文档里有 ${named.size} 张人物卡，会一起导入"
        return PersonaImport(personas = named, warnings = warnings, format = format)
    }

    /** 取出文档里所有成对 ``` 围栏的内容。半截围栏不算。 */
    private fun fencedBlocks(text: String): List<String> {
        val out = mutableListOf<String>()
        val lines = text.replace("\r\n", "\n").split('\n')
        var start = -1
        for ((index, line) in lines.withIndex()) {
            if (!isFenceLine(line)) continue
            if (start < 0) start = index
            else {
                out += lines.subList(start + 1, index).joinToString("\n")
                start = -1
            }
        }
        return out
    }

    /** 把 ``` 围栏里的内容整段挖掉，只留正文。 */
    private fun dropFencedRegions(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        var open = false
        for (line in lines) {
            if (isFenceLine(line)) {
                open = !open
                continue
            }
            if (!open) out += line
        }
        return out.joinToString("\n")
    }

    /** 这段文字里有没有"人设文档该有的骨架"：`# 名字` 或任意一个认识的小节标题。 */
    private fun hasStructure(text: String): Boolean =
        text.replace("\r\n", "\n").split('\n').any { isH1(it) || sectionOf(it) != null }
}
