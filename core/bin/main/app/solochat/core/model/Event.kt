package app.solochat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 事件类型 —— **整个系统的中枢接口**。
 *
 * 六种。前五种是"播出内容"（屏幕和上下文里都会出现的），第六种 [REMEMBER] 是
 * **唯一不播出**的类型：它只是让模型顺手写一条长期记忆。
 *
 * 为什么记忆写入要占一个事件类型，而不是做成工具调用：一轮一次调用的架构下，
 * 让模型"调工具再等结果"会多一次完整前缀往返（贵且慢），而记忆写入**根本不需要
 * 返回值** —— 它只是一句"顺手记下"。所以它作为脚本里的一条事件，成本只有几个
 * 输出 token。这也顺便保住了"模型侧上下文里不含工具元数据"这条纪律。
 *
 * `choice`（galgame 选项）是刻意不实现的保留位，见 docs/01-decisions.md D5：
 * 一旦有选项，用户就从"想说什么说什么"变成"在给定的框里选"，聊天会立刻变成关卡。
 * 协议里给它留着位置，以后想加不用改存储、不用改播放器。
 *
 * 每种类型在两种模式下的渲染：
 *
 * | 类型     | 聊天模式             | 面对面模式        |
 * |----------|----------------------|-------------------|
 * | SAY      | 独立气泡（一条一行） | 角色对白          |
 * | ACT      | 斜体小字一行         | *她把头发别到耳后* |
 * | SCENE    | 居中灰字，少用       | 居中灰字，主力    |
 * | WAIT     | 什么都不播           | 同                |
 * | READ     | 已读标记             | —                 |
 * | REMEMBER | 不播出（只写记忆）   | 同                |
 */
@Serializable
enum class EventKind {
    /** 一条消息 / 一句台词。**每条独立，不合并成长文本** —— 连发三条是三条气泡。 */
    @SerialName("say")
    SAY,

    /** 动作、神态（角色自己的）。 */
    @SerialName("act")
    ACT,

    /** 环境旁白（无人称，不替任何人做决定）。 */
    @SerialName("scene")
    SCENE,

    /** 本轮不回复："还在等你说话" / "不想理你"。 */
    @SerialName("wait")
    WAIT,

    /** 已读回执：显示"已读"再停顿。 */
    @SerialName("read")
    READ,

    /** 写一条长期记忆。**不播出、不进上下文**，由引擎落进记忆本。 */
    @SerialName("remember")
    REMEMBER;

    val wire: String
        get() = when (this) {
            SAY -> "say"
            ACT -> "act"
            SCENE -> "scene"
            WAIT -> "wait"
            READ -> "read"
            REMEMBER -> "remember"
        }

    /** 是否会出现在聊天界面上。 */
    val isVisible: Boolean get() = this != REMEMBER

    companion object {
        /**
         * 解析协议字符串。**未知类型返回 null 而不是抛异常** —— 模型偶尔会自创类型，
         * 那不该让整轮失败；调用方跳过该条并记一次告警即可。
         */
        fun fromWire(raw: String?): EventKind? = when (raw?.trim()?.lowercase()) {
            "say", "msg", "message" -> SAY
            "act", "action", "do" -> ACT
            "scene", "narration", "narrate" -> SCENE
            "wait", "silence", "hold" -> WAIT
            "read", "read_receipt" -> READ
            "remember", "memory", "note", "memo" -> REMEMBER
            else -> null
        }
    }
}

/**
 * 播出状态。这是全项目最容易写错的地方（见 docs/01-decisions.md D4），
 * 五个状态之间**没有模糊地带**：
 *
 * - 已播的（[DELIVERED]）：就是说过了，不回滚 —— 像真人发了就发了。
 * - 正在播被打断的（[INTERRUPTED]）：**它整条已经显示出来了**，但那一轮被你的插话截断，
 *   后面本来要说的那些没出口。下一轮要告诉模型"你刚才被打断了"，而**不是**
 *   "你只说了半句" —— 这是两件不同的事，写错过一次（见下面的说明）。
 * - 没播的（[CANCELED]）：当没说过，**绝不能进上下文**，否则模型会以为自己说过。
 *
 * ⚠️ 关于 [INTERRUPTED] 的语义，有一个反直觉但很要紧的事实：
 * 播出被插话打断时，正在播的那条会被 `Player.interrupt` 标成 `INTERRUPTED`，
 * 而**界面最终会把它的完整正文画出来** —— 因为投影的门禁是 `delivery.spoken`，
 * 而 `INTERRUPTED` 也算出口。也就是说用户看到的是整句，没有"半句话"这回事。
 * 所以任何基于"用户只看到了半句"的推断都是错的。
 */
@Serializable
enum class DeliveryState {
    @SerialName("pending")
    PENDING,

    @SerialName("playing")
    PLAYING,

    @SerialName("delivered")
    DELIVERED,

    @SerialName("canceled")
    CANCELED,

    @SerialName("interrupted")
    INTERRUPTED;

    /** 是否已经"出口"（模型应当认为自己说过这句话）。 */
    val spoken: Boolean get() = this == DELIVERED || this == INTERRUPTED
}

/**
 * 一条事件。**持久层的原子单位**。
 *
 * 注意职责划分：
 * - 带"协议"注释的字段来自模型（信念），是可选的建议值；
 * - 带"引擎"注释的字段由本地决定（事实），模型无法影响。
 *
 * 这个划分是刻意的：模型只能提议节奏，真正的时长由引擎算（防它算出一个离谱的值）。
 */
@Serializable
data class Event(
    val id: Long = 0,
    val conversationId: String,
    val kind: EventKind,

    /** 说话者。`say`/`act` 必填（角色名或"我"）；`scene` 留空（无人称）。 */
    val speaker: String = "",

    val text: String = "",

    /** 时间轴上所属的轮次，同一次生成的事件共享一个 turnId。 */
    val turnId: Long = 0,

    /** 这条事件产生时的模式。切回聊天后靠它还原"门口那段是场景"。 */
    val mode: ChatMode = ChatMode.CHAT,

    // ---------- 以下为协议字段（模型给的建议值，引擎只当参考） ----------

    /** 与上一条的间隔"感觉值"（毫秒）。引擎会与按字数的计算值混合。 */
    val gapHintMs: Int? = null,

    /** 显示"正在输入…"但什么都没发（打了又删） —— 最像真人的一个细节。 */
    val typingHintMs: Int? = null,

    /** `wait` 的理由，仅供调试与 UI 提示，不进上下文。 */
    val waitReason: String = "",

    // ---------- 以下为引擎字段（本地事实） ----------

    val delivery: DeliveryState = DeliveryState.PENDING,

    /** 与上一条的**实际**间隔，播出时确定。 */
    val gapMs: Int = 0,

    /** 实际"正在输入"时长，播出时确定。 */
    val typingMs: Int = 0,

    /** 创建时刻（毫秒）。 */
    val createdAt: Long = 0,

    /** 实际播出时刻；未播出为 null。用户连发消息的间隔判定也靠它。 */
    val deliveredAt: Long? = null
) {
    /** 能否进入下一轮上下文。只有"出口了"的才进。 */
    val usableAsContext: Boolean get() = delivery.spoken

    /** 用户可见的文本（`wait` / 纯 `read` 没有正文）。 */
    val displayText: String?
        get() = when (kind) {
            EventKind.WAIT -> null
            EventKind.READ -> null
            else -> text.takeIf { it.isNotBlank() }
        }
}

/**
 * 上下文里的一条**消息**（不是事件）。
 *
 * 为什么区分：模型侧看到的是"你说的话 / 我说的话"这种干净的对话，
 * **不带任何 JSON、工具元数据或播出状态**。所以下一轮直接复用，KV 缓存命中率也更高
 * （见 docs/01-decisions.md D3）。
 */
@Serializable
data class Message(
    val id: Long = 0,
    val conversationId: String,
    val role: MessageRole,

    /**
     * `user` 是用户原话；`assistant` 是**这一轮所有已出口事件的合并文本**
     * （按气泡逐行）；`system` 是引擎插入的事实。
     */
    val text: String = "",

    /** 该消息所属轮次。 */
    val turnId: Long = 0,

    val createdAt: Long = 0,

    /**
     * 该消息里被中途打断的部分。非空时进上下文要额外告知模型
     * "这段话没说完，对方插话了" —— 否则模型会以为自己完整表达过。
     */
    val interruptedAt: String? = null,

    /**
     * **这条消息有没有被某一轮取走。**
     *
     * - **用户消息**：`false` = 界面上的"代发"（手动发送模式下还没按第二次推送）。
     *   它**不进模型上下文** —— 过滤在存储层，见 `StoragePort.modelVisibleMessages`。
     * - **assistant / system**：恒为 `false`，这个字段对它们没有意义。
     *
     * ⚠️ 字段名是历史遗留（当初设想的是"触达模型的时间"，那个注释是错的）。
     * 语义就是"已推送"。**沿用它是为了不做一次数据库迁移** —— 它在此之前是个死字段：
     * 全仓库只有两处写死 `false`，唯一读它的是调试接口 `/api/messages`。
     */
    val sentToModel: Boolean = false
)
