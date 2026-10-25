package app.solochat.core.agent

import app.solochat.core.llm.LlmError
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Event
import app.solochat.core.model.ModelPrice
import app.solochat.core.model.Usage
import app.solochat.core.protocol.DebounceTier

/**
 * 一次回合过程中推给界面的事件。
 *
 * 用 sealed class 而不是几个回调，是为了让界面**必须显式处理每一种** ——
 * 尤其是 [Failed] 与 [UsageRecorded]，这两个最容易被忘记（忘记前者用户看不到错误，
 * 忘记后者用量页永远是 0）。
 */
sealed class AgentEvent {

    /** 防抖阶段：还在等用户说完。界面据此显示"对方正在输入…"以外的等待提示。 */
    data class Waiting(
        val tier: DebounceTier,
        val reason: String,
        val waitMs: Long
    ) : AgentEvent()

    /** 阅读延迟结束，开始请求模型。 */
    data class Thinking(val since: Long) : AgentEvent()

    /**
     * 模型已经产出了完整脚本，即将开始播出。
     *
     * 注意 `events` 此时已经**落盘**（带 id），播放器只需要按节奏播放。
     */
    data class ScriptReady(
        val turnId: Long,
        val events: List<Event>,
        val modeProposal: ChatMode?,
        val modeReason: String,
        /** 解析降级了（模型没按协议输出），界面可以提一句"这轮格式没走协议"。 */
        val degraded: Boolean
    ) : AgentEvent()

    /** 这一轮模型选择不说话（`wait`）。 */
    data class Silent(val reason: String) : AgentEvent()

    /** 出错。界面展示 `message` + `advice`，并把 `detail` 收进可展开的区域。 */
    data class Failed(val error: LlmError) : AgentEvent()

    /** 用量与成本已记账。界面刷新用量条。 */
    data class UsageRecorded(val usage: Usage, val cost: Double, val rough: Boolean) : AgentEvent()
}

/**
 * 一轮的产出。
 */
data class TurnOutcome(
    val turnId: Long,
    val events: List<Event>,
    val modeProposal: ChatMode?,
    val degraded: Boolean,
    val usage: Usage?,
    val error: LlmError?
)

/** 一次回合的入参（让 [AgentTurnRunner] 是无状态的，方便并发控制）。 */
data class AgentTurn(
    val conversationId: String,
    val userText: String,
    val at: Long,

    /**
     * 引擎插给这一轮的"台上提示"：**此刻发生了什么，所以你现在该干嘛**。
     *
     * 存在的理由是一次真机实测出来的双错：模式刚切换时要触发下一轮，原来的做法是
     * 往待处理队列里塞一条 `"[系统] 你们见面了，开始面对面。"` —— 而待处理消息一律
     * 以 `MessageRole.USER` 落库、以"我"的气泡显示。于是**模型看到的是**
     * 「对方：[系统] 你们见面了，开始面对面。」（它会以为对方说了这么一句），
     * 而**用户看到的是一条自己发的系统通知**。
     *
     * 现在：切换这件事由一条 `MessageRole.SYSTEM` 消息（`—— 见面了 ——`）进历史，
     * 而"这时候该干嘛"走这里 —— 它只进提示词的变动区，**不落成任何一方的消息**。
     * 与 `PromptContext.interruptedNote` 是同一类东西。
     */
    val cue: String = ""
)

/** 经应用层校验后的单次调用快照。凭据仅留在私有 endpoint 中，日志字符串不含凭据。 */
class PreparedAgentCall(
    val config: ChatConfig,
    val snapshot: app.solochat.core.model.CallConfigSnapshot,
    val price: ModelPrice,
    val priceAt: Long,
    private val endpoint: app.solochat.core.llm.RequestEndpoint,
    private val thinkingParameters: app.solochat.core.llm.ThinkingRequestParameters?,
    val priceRuleFingerprint: String = "",
    private val resolvePriceAt: (Long) -> ModelPrice = { price }
) {
    /** 在已冻结的配置与价格规则内，按本次实际调用开始时间选择时段。 */
    fun priceAt(at: Long): ModelPrice = resolvePriceAt(at)

    fun request(messages: List<app.solochat.core.llm.ApiMessage>) = app.solochat.core.llm.LlmRequest(
        model = config.model,
        messages = messages.toList(),
        temperature = config.temperature,
        maxTokens = config.maxTokens,
        thinkingParameters = thinkingParameters,
        timeoutMs = config.timeoutMs,
        endpoint = endpoint
    )

    override fun toString() = "PreparedAgentCall(provider=${snapshot.providerId}, model=${snapshot.modelId}, endpoint=<已隐藏>)"
}

/**
 * 发送方式。
 *
 * 两种模式**共用同一套流水线**（防抖判定、认领、落盘、回合、播出都没变），
 * 唯一的分歧是"什么时候把那批消息交给模型"：
 *
 * - [AUTO]（默认，原来的行为）：按下发送 → 防抖定时器（按学习到的手速等一两秒）→ 自动开工。
 * - [MANUAL]：按下发送**只落进时间线**（"代发"），**再按一次发送键**才推给模型。
 *
 * 手动模式下"有没有代发"这件事**以数据库为准**（`Message.sentToModel == false`
 * 的用户消息），所以它天然能活过切会话、离开页面、进程被杀。
 */
enum class SendMode {
    AUTO,
    MANUAL;

    val wire: String get() = name.lowercase()

    companion object {
        /** 认不出的一律当 [AUTO]：默认行为必须是那个"不改动"的。 */
        fun fromWire(raw: String?): SendMode {
            val v = raw?.trim()?.lowercase()
            return values().firstOrNull { it.wire == v } ?: AUTO
        }
    }
}

/**
 * 运行配置。全部来自设置页。
 */
data class ChatConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.85,
    val thinkingEffort: String = "",
    val maxTokens: Int = 0,

    /** 兜底单价（元 / 百万 token），价格表没匹配到时用。 */
    val priceInputPerM: Double = 0.0,
    val priceOutputPerM: Double = 0.0,
    val priceCachedPerM: Double = 0.0,
    val priceConfigured: Boolean? = null,
    val cachedPriceConfigured: Boolean? = null,

    /** 是否优先用内置价格表。 */
    val useOfficialPrice: Boolean = true,
    /** null 使用随包快照；非 null 使用用户保存的价格表集合。 */
    val priceCatalog: app.solochat.core.model.PriceCatalog? = null,

    /**
     * 历史注入上限（条）。**0（或负数）= 不限，全部注入**（默认）。
     *
     * 为什么默认改成不限：历史在提示词里**本来就很小**。真机上最长的那场会话
     * （153 条消息）全量注入约 4500 字符，而稳定前缀（安全 + 人物卡 + 协议 + 风格）
     * 就有约 4000 字符 —— 掐历史省下的那点 token，远不如"模型记得住"值钱。
     * 所以这里**不做节流**：以后加上下文压缩时换成按 token 预算的老少搭配
     * （近 N 轮原文 + 更早的压成摘要），**不按轮切**。
     *
     * ⚠️ 单位是**气泡**，不是"轮"。`message` 表一行 = 一个气泡，而面对面模式
     * 一轮通常就有 4~6 行（一次说三句 + 两个动作），所以旧默认值 40 条其实只够
     * 4~6 轮 —— 它按"一轮一行"的旧语义挑的，语义在"一个气泡一条消息"上线后就偏了。
     */
    val historyLimit: Int = 0,

    /** 读超时（毫秒）。手机上给宽一点。 */
    val timeoutMs: Long = 180_000,

    /** 是否允许模型提议切换到面对面模式。 */
    val allowModeSwitch: Boolean = true,

    /** 模式切换策略：AUTO / CONFIRM / OFF（默认 CONFIRM，见 docs/01-decisions.md D7）。 */
    val modeSwitchPolicy: String = "CONFIRM",

    /** 发送方式：自动（防抖）还是手动（两段）。见 [SendMode]。 */
    val sendMode: SendMode = SendMode.AUTO
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 真正交给 `StoragePort.recentMessages` 的条数。
     *
     * 0 / 负数 = 不限，翻成 SQLite 的 `LIMIT -1`（负数在 SQLite 里就是"没有上限"）。
     * 为什么要在**这里**翻译一次：`LIMIT 0` 是"一条都不要"，`LIMIT -1` 是"全都要" ——
     * 两个只差一个字面量的取值，差别却是整个上下文。这种翻译只该有一处，
     * 存储层（Room）不需要知道 0 的含义。
     */
    val historyWindow: Int
        get() = if (historyLimit > 0) historyLimit else UNLIMITED

    companion object {
        /** `LIMIT -1`：SQLite 里表示不设上限。 */
        const val UNLIMITED: Int = -1
    }
}

/** 模型价格解析：内置表 + 兜底单价 + 峰谷。 */
interface PriceResolver {
    fun resolve(model: String, config: ChatConfig): ModelPrice
    /** 调用开始时间；旧实现保持兼容，不启用未经配置的峰谷规则。 */
    fun resolve(model: String, config: ChatConfig, at: Long): ModelPrice = resolve(model, config)
}

/**
 * 对模型的依赖收口成接口。
 *
 * 为什么不让 [AgentTurnRunner] 直接持有 LlmClient：因为运行器还需要
 * 只读配置、价格表、提示词资源、存储，把它们都塞进构造器会让测试构造出一个
 * 七参数的怪物。收成一个 [AgentDeps] 之后，测试里只需要实现这一个接口。
 */
interface AgentDeps  {
    /** 配置写入与请求快照共用同一个会话锁，不在网络等待期间持锁。 */
    val conversations: ConversationOperations
    val storage: app.solochat.core.ports.StoragePort
    val clock: app.solochat.core.ports.ClockPort
    val llm: app.solochat.core.llm.LlmClient
    val prompts: app.solochat.core.prompt.PromptResources
    val pricing: PriceResolver

    /** Android 宿主在调用开始前解析 v2；null 仅保留旧 JVM 测试/工具宿主的兼容语义。 */
    suspend fun prepareCall(conversationId: String, use: app.solochat.core.model.ModelUse): PreparedAgentCall? = null

    /** 每次调用都要读最新值：用户在设置页改完立刻生效，不用重启。 */
    fun config(): ChatConfig

    /**
     * 用户连发节奏的学习样本（相邻两条用户消息的间隔毫秒）。
     *
     * 由存储层统计，不在这里现算 —— 现算要遍历全部历史，而防抖判定在
     * "用户刚发完一条"的热路径上，不能做重活（参考 QQ-agent 的经验：
     * 读盘平均 0.4ms、写盘平均 10ms，串在请求路径上就会明显卡）。
     */
    fun rhythmSamples(): List<Long>

    /** 用户侧的防抖窗口（已学到的自适应值）。 */
    fun debounceWindowMs(): Long
}
