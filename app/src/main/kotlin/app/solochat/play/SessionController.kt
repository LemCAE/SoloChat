package app.solochat.play

import app.solochat.core.agent.AgentEvent
import app.solochat.core.agent.AgentTurn
import app.solochat.core.agent.AgentTurnRunner
import app.solochat.core.agent.SendMode
import app.solochat.core.llm.LlmError
import app.solochat.core.llm.LlmErrorKind
import app.solochat.core.log.Log
import app.solochat.core.model.ManualModeCommand
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Memory
import app.solochat.core.model.MediaDraft
import app.solochat.core.model.MediaContextReference
import app.solochat.core.model.MediaReferenceSource
import app.solochat.core.model.VisionControl
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Usage
import app.solochat.core.model.UserUtterance
import app.solochat.core.model.InputSubmission
import app.solochat.core.model.SubmissionText
import app.solochat.core.model.StickerSubmissionIntent
import app.solochat.core.model.TimelineEntry
import app.solochat.core.model.TimelinePosition
import app.solochat.core.model.resolvePersona
import app.solochat.core.model.checksumOf
import app.solochat.data.CapturedMediaSubmission
import app.solochat.core.playback.PlaybackOutcome
import app.solochat.core.playback.PlaybackPlan
import app.solochat.core.playback.Player
import app.solochat.core.protocol.DebounceTier
import app.solochat.core.protocol.IncomingMessage
import app.solochat.core.protocol.Pacing
import app.solochat.core.ports.EventSink
import app.solochat.core.ports.StoragePort
import app.solochat.core.prompt.PromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val RECENT_HISTORY_IMAGE_WINDOW = 4
private data class PlannedMediaItem(
    val messageId: Long,
    val attachment: app.solochat.core.model.MessageAttachment,
    val asset: app.solochat.core.model.MediaAsset,
    val source: MediaReferenceSource
)

/**
 * 一轮的三个阶段。标题栏右上角那个指示器画什么，由它决定。
 *
 * 为什么要有这个枚举、而不是让界面去猜 [ChatUiState.pendingHint] 的文案：
 * **拿文案当状态用，改一个字就崩** —— 第一版写的是
 * `spinner = state.pendingHint.contains("想")`，那是把提示语当布尔标志使。
 */
enum class TurnPhase {
    /** 什么都没在跑。 */
    IDLE,

    /** 你的话还攒着没交出去（在防抖窗口里）—— 界面上是三个点依次亮。 */
    BATCHING,

    /** 已经交给模型、等它回 —— 界面上是转圈。 */
    THINKING,

    /**
     * 模型已经回了，正在**一条一条发出来**（播出中）—— 界面上是三个点在起伏。
     *
     * 这一段原来没有任何指示：气泡之间可能隔好几秒（尤其是面对面），
     * 屏幕上什么都不动，看起来像"发完了"或者"卡住了"。
     */
    SENDING
}

/**
 * 一个会话在界面上的全部状态。
 */
data class ChatUiState(
    val conversationId: String = "",
    /** 首次读库完成；调试接口与界面均不得绕过副本暂停状态。 */
    val ready: Boolean = false,
    val title: String = "",
    val mode: ChatMode = ChatMode.CHAT,
    val items: List<ChatItem> = emptyList(),
    val hasEarlierHistory: Boolean = false,
    val loadingHistory: Boolean = false,
    val historyError: String = "",
    val latestHistoryRequest: Long = 0,

    /**
     * 这个会话在用的那张人物卡。
     *
     * 放在状态里而不是让界面自己去查库：标题栏要在**第一帧**就显示对，而界面的
     * 第一帧早于任何一次查询完成 —— 那会先闪一下"没有名字"，再变成名字。
     */
    val personaName: String = "",
    val personaAvatar: String = "",

    /** 精确匹配后的有效人物 id；缺失时为空，阻止发送并要求用户选择。 */
    val personaId: String = "",

    /** 保留旧调用处参数兼容；不再隐式回退人物，正常状态恒为 false。 */
    val personaIsFallback: Boolean = false,

    /** 正在演出中的条目（还没成为历史）。 */
    val live: ChatItem.Live? = null,

    /**
     * 已发出但**还没进模型**的消息。
     *
     * 它们必须立刻显示（否则用户点了发送看不到东西）。
     *
     * **只在自动（防抖）模式下有内容**：那时它们还没落盘 —— 落盘发生在回合真正开始的
     * 那一刻（见 [SessionController.flushTurn]）。这个取舍是刻意的：如果在按下发送时
     * 就写库、而回合里又写一遍，就会出现"用户消息显示两遍"（第一版就是这个 bug）。
     *
     * 手动（两段）模式下这个列表**恒为空**：代发消息直接落库（`sentToModel = false`），
     * 由 [pendingSendCount] 表达"有代发"。为什么非要落库不可 —— 手动模式的窗口是
     * "随便多久"，切会话/离开页面/进程被杀都会丢，而界面上明明已经显示过了。
     */
    val pendingItems: List<ChatItem> = emptyList(),

    /** 界面上要显示的"进行中"提示，例如"在等你打完"。 */
    val pendingHint: String = "",

    /** 发送方式（手动两段 / 自动防抖）。决定发送键那三态里有没有"蓝"。 */
    val sendMode: SendMode = SendMode.MANUAL,

    /**
     * 有几条"代发"（按了发送、还没推给模型）。
     *
     * **判定来源是数据库**（`Message.sentToModel == false` 的用户消息），不是内存状态 ——
     * 所以切会话、离开页面、进程被杀之后它还会回来。
     */
    val pendingSendCount: Int = 0,
    /** 数据库中全部未推送用户消息关联的图片数，独立于已加载的历史页。 */
    val pendingImageCount: Int = 0,
    /** 导入副本持久暂停；打开会话和配置变化不得隐式解除。 */
    val backupPaused: Boolean = false,

    /**
     * 现在处于哪个阶段 —— 标题栏右上角那个指示器画什么由它决定。
     *
     * 区分的意义：用户要能看出**"它在等我"**和**"它在想"**是两件事。
     * 同一个动画表示两件事，就等于没有信息。
     */
    val turnPhase: TurnPhase = TurnPhase.IDLE,

    /** 模式切换提议：非空时界面弹确认卡片。 */
    val modeProposal: ChatMode? = null,
    val modeReason: String = "",

    /** 上一次失败。 */
    val error: LlmError? = null,
    val mediaRetryAvailable: Boolean = false,

    /** 这一轮花掉的钱（展示用）。 */
    val lastCost: Double = 0.0,
    val lastRough: Boolean = true,
    val lastUsage: Usage? = null
)

/**
 * 会话编排器 —— 连接"用户输入 / 模型 / 播放器 / 存储"的那一层。
 *
 * 它要解决的四件难事：
 *
 * 1. **防抖**：用户连发时不能对半句话抢答。
 * 2. **抢占**：正在播出时用户插话，必须立刻停住未播的部分。
 * 3. **状态一致**：界面看到的历史必须和落盘一致 —— 正在播的放 [ChatUiState.live]，
 *    待处理的放 [ChatUiState.pendingItems]，落盘的才进 [ChatUiState.items]。
 * 4. **一批消息只能被处理一次**。这一点最不直观但最要紧：防抖任务会被反复取消重启，
 *    如果"取批 + 清空 + 落盘"不是一个原子动作，同一批消息就会被投给模型两遍、
 *    也在库里写两遍（第一版就是这个 bug，用户消息在界面上成对出现）。
 *    [flushTurn] 现在把这三件事一起放在 [gate] 里做。
 */
class SessionController(
    private val host: TurnHost,
    private val scope: CoroutineScope,
    /** 读历史（装配上下文、投影界面）。 */
    private val store: StoragePort,
    /** 写播出状态（已播 / 说了一半 / 取消）。 */
    private val sink: EventSink,
    private val runner: AgentTurnRunner = AgentTurnRunner(host.deps)
) {

    private companion object {
        /** 日志标签。界面上按它分组，能只看"会话编排"这一路。 */
        const val TAG = "编排"

        /**
         * 敏感信息兜底。提示词里已经写了"绝不记密码/证件号/银行卡"，
         * 但这是**用户数据的最后一道防线**，值得在代码里再拦一次 ——
         * 模型不听话的代价是不可逆的（记忆会被反复注入上下文）。
         */
        val SENSITIVE_PATTERN = Regex(
            "(密码|口令|验证码|银行卡|信用卡|身份证|护照号|CVV|pin\\s*码)" +
                "|\\b\\d{16,19}\\b" +          // 卡号
                "|\\b\\d{17}[\\dXx]\\b"        // 身份证
        )
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val timelineGate = Mutex()
    private var timelineConversation = ""
    private var timelineRows: List<TimelineEntry> = emptyList()
    private var hasEarlierHistory = false
    private var historyPageJob: Job? = null

    /**
     * 时间来源。
     *
     * ⚠️ **一律走它，不要用 `System.currentTimeMillis()`**。
     *
     * 不只是为了测试方便（虚拟时钟让"交付时间递增"这类断言变成确定性的）：
     * 交付时间决定气泡在时间轴上的位置，它必须和防抖、回合标记用的是**同一个时钟**。
     * 两边各拿各的系统时间，早晚会出现"事件比它所属的回合还早"这种矛盾。
     */
    private val clock get() = host.deps.clock

    /** 已出口助手历史的检查、实际写入与 id 认领必须跨挂起点串行完成。 */
    private val historyWriteGate = Mutex()
    /** 手动代发整批落盘、push 认领及模式切换按提交顺序执行。 */
    private val submissionGate = Mutex()
    private var playbackSettlement: Job? = null

    /**
     * 认领待处理消息的锁。
     *
     * 用 JVM 的 `synchronized`（非挂起）而不是 [Mutex]，因为这里要做的只是
     * "取 list + 换一个新的空 list + 置空待处理计数" —— **没有任何挂起点**。
     * 而 `Mutex.lock` 自身会挂起，挂起的续体会被丢回调度器，于是：
     *
     * - 在测试里，虚拟时间推不动它（`runTest` 的调度器被绕过，用例随机失败）
     * - 在应用里，认领与后续 `turnJob?.cancel()` 之间会插进一个调度点，
     *   给"同一批被认领两次"留出窗口
     *
     * 一句话：**能不挂起就不要挂起。** 需要跨挂起点持锁时才用 [Mutex]（见 [gate]）。
     */
    private val claimLock = Any()

    /** 本会话里"用户已经发出、还没交给模型"的消息。 */
    private var pending: MutableList<IncomingMessage> = mutableListOf()
    @Volatile private var retryMediaMessageIds: List<Long>? = null
    private val retryMediaInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 这一批消息里第一条到达的时刻，用于防抖的硬上限。 */
    private var pendingSince: Long = 0

    /** 防抖任务：每来一条新消息就重启它（等于把窗口往后推）。 */
    private var debounceJob: Job? = null

    /**
     * 当前正在跑的回合。
     */
    private var turnJob: Job? = null

    /** 正在播出的那场演出的元数据。 */
    private var playing: Playing? = null

    /**
     * 演出期间被"让路"掉的那些 [reload]，收尾时要补做一次。
     *
     * 不记账的后果很实在：回合结束时的那次 `reload()` 与演出收尾是**并发**的，
     * 谁先谁后不确定。它先跑就会被丢掉，而演出收尾写完 `live = null` 也不会再刷 ——
     * 库里已有的回复永远画不出来。
     */
    private var reloadRequestedWhilePlaying = false

    private var typewriterJob: Job? = null

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private data class Playing(
        /** 本场演出属于哪个会话 —— 演出跑一半切走时，不能再往新会话的界面上写状态。 */
        val conversationId: String,
        /** 本轮的 turnId。写 assistant 历史时要用它做幂等判断。 */
        val turnId: Long,
        val events: List<Event>,
        /** 已完整播出的条数。 */
        var playedCount: Int = 0,
        /** 正在播的那条的 index（还没完成）。 */
        var playingIndex: Int? = null,
        var outcome: PlaybackOutcome? = null,
        /**
         * 每条气泡**出现**的时刻（eventId → 时间）。
         *
         * 存在的理由是"被打断的那条要留在它出现的位置上"：它的交付时间必须是出现
         * 的那一刻，而不是被打断的那一刻。详见 [interruptPlayback]。
         */
        val startedAt: MutableMap<Long, Long> = mutableMapOf(),

        /**
         * 已经写进 assistant 历史的事件 id。见 [appendSpokenAssistantLine]。
         *
         * assistant 历史现在**一个气泡一条消息**，而写它有两条路径：正常播完、
         * 以及被插话打断。两条路径可能对同一条事件都走到（`markDelivered` 是挂起调用，
         * 中断可以插在它之后），重复写会让模型看到自己说了两遍。
         * 演出不会跨进程存活，所以内存集合就够。
         *
         * 用 `synchronizedSet`：两条路径跑在同一个 IO 调度器上的不同协程里。
         */
        val writtenIds: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())
    )

    /** 用户手动切模式时置位，避免模型提议与手动操作打架。 */
    private var suppressModeProposal = false

    private var openingJob: Job? = null

    suspend fun awaitReady() {
        openingJob?.join()
        check(_state.value.ready && !closed.get()) { "会话尚未打开或已关闭" }
    }

    fun open(conversationId: String) {
        if (closed.get()) return
        // 首次读取暂停状态之前不接受输入，避免刚打开副本时抢先发送。
        historyPageJob?.cancel()
        _state.value = _state.value.copy(backupPaused = true, ready = false, loadingHistory = false, historyError = "")
        // 换会话时必须把上一个会话的待处理消息清掉，否则会给新会话发旧内容
        if (_state.value.conversationId != conversationId) {
            pending = mutableListOf()
            pendingSince = 0
            debounceJob?.cancel()
        }
        openingJob = scope.launch {
            _state.value = _state.value.copy(
                conversationId = conversationId,
                error = null,
                turnPhase = TurnPhase.IDLE
            )
            // 先把"整段 JSON 被当成一条消息"的历史脏行还原掉，**再**刷新时间轴 ——
            // 界面第一次画出来就已经是修好的样子（放在 reload 后面会闪一下 JSON 气泡）。
            // 幂等：修完就没有可匹配的行，所以每次打开都跑一遍也不花钱（按会话 id 走索引）。
            repairDegradedTurns(conversationId)
            reloadNow(resetHistory = true)
            // 自动模式下不该存在"代发"（那是手动模式留下的）。切回自动就等于
            // "按下的都发出去" —— 不补这一步，那些消息会永远躺在库里没人推，
            // 而自动模式又不画"蓝"，用户没有任何提示。
            if (sendMode() == SendMode.AUTO && turnJob?.isActive != true) {
                pushUnpushed(conversationId)
            }
        }
    }

    /** 重新显示页面时只刷新资料，不重置回合；恢复原先切回自动模式推送代发的行为。 */
    suspend fun onPageOpened() {
        awaitReady()
        reload()
        if (sendMode() == SendMode.AUTO && turnJob?.isActive != true && playing == null) {
            pushUnpushed(_state.value.conversationId)
        }
    }

    /**
     * 把历史脏行还原好。见 [app.solochat.core.ports.StoragePort.repairDegradedTurns]。
     *
     * **失败只记日志**：这是"把旧数据好看一点"，绝不能因为它让会话打不开 ——
     * 所以这里吞掉异常，而不是让它冒到 [open] 的协程里。
     */
    private suspend fun repairDegradedTurns(conversationId: String) {
        runCatching { store.repairDegradedTurns(conversationId) }
            .onSuccess {
                if (it > 0) Log.info(TAG, "时间轴修复：$it 轮（整段 JSON 的那几条已还原）")
            }
            .onFailure { Log.error(TAG, "时间轴修复失败（不影响正常使用）：${it.message}") }
    }

    /** 应用明确移除会话时调用。普通导航离开只解除订阅，不关闭实例。 */
    fun close() {
        historyPageJob?.cancel()
        closed.set(true)
        debounceJob?.cancel()
        turnJob?.cancel()
        typewriterJob?.cancel()
        playing = null
    }

    // ------------------------------------------------------------------
    // 用户输入
    // ------------------------------------------------------------------

    /**
     * 用户按了发送。
     *
     * 这里做两件事：① 把消息放进待处理列表并**立刻显示**（点了发送必须马上看到气泡）
     * ② 重启防抖定时器。
     *
     * ⚠️ **自动模式下不在这里落盘**。落盘发生在 [flushTurn] —— 也就是回合真正要开始的
     * 那一刻，并且与"取批 + 清空"一起放在锁内。
     *
     * 为什么这样安排（第一版的教训）：如果这里写一遍、回合里再写一遍，用户消息就会
     * 成对出现在界面上。而"按下发送时落盘"换来的抗崩溃能力其实很有限 ——
     * 防抖窗口只有几秒，真正会丢的只是"在这几秒里进程被杀"这一种情况。
     * 用"显示两遍"换这个，不值得。
     *
     * ⚠️ **手动模式下前提反过来**：那时窗口是"随便多久"，切会话、离开页面、进程被杀
     * 都会丢，而界面上明明已经显示过 —— 所以手动模式**必须**立刻落盘（见 [push]）。
     * 两种模式的落盘时机因此不同，这是刻意的。
     */
    fun send(
        text: String,
        isMedia: Boolean = false,
        /**
         * 这一句是什么：说话 / 动作 / 环境。面对面模式里由输入框上那个开关决定。
         * 见 [UserUtterance]：落盘时才会编码成 `*…*` / `（…）`。
         */
        kind: EventKind = EventKind.SAY
    ) {
        val body = text.trim()
        if (body.isEmpty() || closed.get() || _state.value.backupPaused || (_state.value.ready && _state.value.personaId.isBlank())) return
        clearMediaRetry()
        val now = clock.now()

        if (sendMode() == SendMode.MANUAL) {
            // 手动模式：只落进时间线，**不动模型**，也不打断正在播的演出。
            // 打断留到 [push] —— 语义是"我说出来了"才打断（见那里的注释）。
            scope.launch { submissionGate.withLock { appendUnpushed(kind, body, isMedia, now) } }
            Log.fields(
                TAG,
                pairs = arrayOf(
                    "阶段" to "用户发送（代发）",
                    "类型" to kind.wire,
                    "文本" to body
                )
            )
            return
        }

        // 插话优先于一切：先把正在播的停住，别让用户等演出做完
        if (playing != null) interruptPlayback("你说新话了")

        if (pending.isEmpty()) pendingSince = now
        pending += IncomingMessage(text = body, isMedia = isMedia, at = now, kind = kind)

        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "用户发送",
                "类型" to kind.wire,
                "文本" to body,
                "本轮已累积" to "${pending.size} 条",
                "正在播出" to (playing != null)
            )
        )

        // 同步更新界面：待处理消息直接作为气泡显示。
        // 不走 scope.launch —— 那会让"点了发送没反应"这种观感出现。
        //
        // `turnPhase = BATCHING`：你的话攒着还没交出去，右上角画三个点。
        _state.value = _state.value.copy(
            pendingItems = pendingAsItems(),
            turnPhase = TurnPhase.BATCHING
        )
        scheduleDebounce()
    }

    /**
     * 按下发送键（或界面上任何"发送"入口）的**统一入口**。
     *
     * 一次按键可以同时送好几格（面对面模式的说/做/景），它们是**同一批**：
     * 自动模式下逐条进待处理队列（每条都重启一次防抖，正是"等你说完"），
     * 手动模式下一次性全部落盘（不然后面那几条会各触发一次读库）。
     */
    fun send(pieces: List<Pair<EventKind, String>>) {
        if (closed.get() || _state.value.backupPaused || (_state.value.ready && _state.value.personaId.isBlank())) return
        val usable = pieces.filter { it.second.isNotBlank() }
        if (usable.isEmpty()) return
        clearMediaRetry()
        if (sendMode() == SendMode.MANUAL) {
            val now = clock.now()
            scope.launch {
                submissionGate.withLock {
                    usable.forEach { (kind, body) -> appendUnpushed(kind, body.trim(), false, now) }
                }
            }
            return
        }
        usable.forEach { (kind, body) -> send(body, kind = kind) }
    }

    /** UI 与等价调试入口共用命令解释，控制命令不落成用户气泡。 */
    fun submitInput(pieces: List<Pair<EventKind, String>>) {
        val one = pieces.singleOrNull()?.takeIf { it.first == EventKind.SAY }?.second?.trim()
        if (one != null) {
            ManualModeCommand.parse(one)?.let { applyMode(it); return }
            if (one == "/compress" || (one.startsWith("/compress") && one.getOrNull(9)?.isWhitespace() == true)) {
                throw IllegalArgumentException("压缩命令需要在界面中选择范围")
            }
            if (one == "/system" || (one.startsWith("/system") && one.getOrNull(7)?.isWhitespace() == true)) {
                sendSystem(one.removePrefix("/system").trim()); return
            }
        }
        send(pieces)
    }

    /** A 阶段保存图片输入并明确拒绝模型请求；返回值只在 Room 事务确认后为 true。 */
    suspend fun submitMedia(draft: MediaDraft): Boolean = submitMedia(CapturedMediaSubmission(draft, draft.text))

    suspend fun submitMedia(snapshot: CapturedMediaSubmission): Boolean = submissionGate.withLock {
        val draft = snapshot.draft
        if (closed.get() || _state.value.backupPaused || (_state.value.ready && _state.value.personaId.isBlank())) return@withLock false
        require(draft.mediaIds.isNotEmpty() && draft.mediaIds.size <= 4) { "一次最多附加 4 张图片" }
        if (_state.value.conversationId.isNotBlank()) require(draft.conversationId == _state.value.conversationId) { "图片草稿不属于当前会话" }
        try {
            if (sendMode() == SendMode.AUTO) persistPendingBeforeMedia(draft.conversationId)
            // 点击瞬间的完整草稿先持久化。后续附件、消息、幂等映射和同版本草稿清理在一事务内提交。
            store.saveMediaDraft(draft)
            val now = clock.now()
            val supplied = snapshot.text.map { item ->
                val kind = UserUtterance.kinds.firstOrNull { it.wire == item.kind } ?: error("草稿消息类型无效")
                kind to item.text.trim()
            }.filter { it.second.isNotBlank() }
            val batch = if (supplied.any { it.first == EventKind.SAY }) supplied else listOf(EventKind.SAY to "") + supplied
            val messages = batch.map { (kind, text) -> Message(
                conversationId = draft.conversationId, role = MessageRole.USER,
                text = UserUtterance.encode(kind, text), turnId = now, createdAt = now, sentToModel = false
            ) }
            val submission = InputSubmission(
                id = draft.submissionId, conversationId = draft.conversationId,
                text = supplied.map { SubmissionText(it.first.wire, it.second) }, mediaIds = draft.mediaIds,
                createdAt = now, draftVersion = draft.version
            )
            store.appendSubmission(submission, messages)
            reloadNow()
            clearMediaRetry()
            if (sendMode() == SendMode.AUTO) {
                if (pending.isNotEmpty()) scheduleDebounce() else scheduleMediaAutoPush(draft.conversationId)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = LlmError.Failed(
                LlmErrorKind.BAD_REQUEST, error.message ?: "图片没有保存，请保留草稿后重试"
            ))
            false
        }
    }

    /** 图片提交先前已经显示的 AUTO 文字，确保数据库顺序与用户动作顺序一致。 */
    private suspend fun persistPendingBeforeMedia(conversationId: String, consume: Boolean = true): List<IncomingMessage> {
        val captured = synchronized(claimLock) { pending.toList() }
        if (captured.isEmpty()) return captured
        captured.forEach { input ->
            val submissionId = autoSubmissionId(conversationId, input.id)
            val submission = InputSubmission(submissionId, conversationId,
                listOf(SubmissionText(input.kind.wire, input.text)), emptyList(), input.at)
            val message = Message(conversationId = conversationId, role = MessageRole.USER,
                text = UserUtterance.encode(input.kind, input.text), turnId = input.at, createdAt = input.at, sentToModel = false)
            store.appendSubmission(submission, listOf(message))
            if (consume) consumePendingPrefix(listOf(input))
        }
        if (consume) reloadNow()
        return captured
    }

    private fun consumePendingPrefix(consumed: List<IncomingMessage>) = synchronized(claimLock) {
        if (consumed.isNotEmpty() && pending.size >= consumed.size && pending.take(consumed.size) == consumed) {
            pending = pending.drop(consumed.size).toMutableList()
            if (pending.isEmpty()) pendingSince = 0
            _state.value = _state.value.copy(pendingItems = pendingAsItems())
        }
    }

    /** P4 专用测试文字入口：提交 ID 落盘幂等，仍通过同一控制器批次推送。 */
    suspend fun submitTestInput(pieces: List<Pair<EventKind, String>>, submissionId: String,
        capturedAt: Long = clock.now()): Boolean = submissionGate.withLock {
        if (closed.get() || _state.value.backupPaused || !_state.value.ready || _state.value.personaId.isBlank()) return@withLock false
        require(pieces.size in 1..3 && pieces.all { it.second.isNotBlank() } && submissionId.isNotBlank())
        require(pieces.all { it.first == EventKind.SAY || _state.value.mode == ChatMode.SCENE }) { "动作与场景输入仅在面对面模式可用" }
        val conversationId = _state.value.conversationId
        val text = pieces.map { (kind, body) -> SubmissionText(kind.wire, body.trim()) }
        val messages = pieces.map { (kind, body) -> Message(conversationId = conversationId,
            role = MessageRole.USER, text = UserUtterance.encode(kind, body.trim()), turnId = capturedAt,
            createdAt = capturedAt, sentToModel = false) }
        try {
            store.appendSubmission(InputSubmission(submissionId, conversationId, text, emptyList(), capturedAt), messages)
            reloadNow(); clearMediaRetry()
            if (sendMode() == SendMode.AUTO) {
                if (pending.isNotEmpty()) scheduleDebounce() else scheduleMediaAutoPush(conversationId)
            }
            true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            _state.value = _state.value.copy(error = LlmError.Failed(LlmErrorKind.BAD_REQUEST, error.message ?: "测试输入没有保存"))
            false
        }
    }

    /** P4 专用测试图片入口：保留会话现有草稿，仍通过同一控制器批次推送。 */
    suspend fun submitTestImage(mediaId: String, submissionId: String, capturedAt: Long = clock.now()): Boolean = submissionGate.withLock {
        if (closed.get() || _state.value.backupPaused || !_state.value.ready || _state.value.personaId.isBlank()) return@withLock false
        require(mediaId.isNotBlank() && submissionId.isNotBlank())
        val conversationId = _state.value.conversationId
        try {
            val submission = InputSubmission(submissionId, conversationId, emptyList(), listOf(mediaId), capturedAt, draftVersion = -1)
            val message = Message(conversationId = conversationId, role = MessageRole.USER,
                text = UserUtterance.encode(EventKind.SAY, ""), turnId = capturedAt, createdAt = capturedAt, sentToModel = false)
            store.appendSubmission(submission, listOf(message))
            reloadNow()
            clearMediaRetry()
            if (sendMode() == SendMode.AUTO) {
                if (pending.isNotEmpty()) scheduleDebounce() else scheduleMediaAutoPush(conversationId)
            }
            true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            _state.value = _state.value.copy(error = LlmError.Failed(LlmErrorKind.BAD_REQUEST, error.message ?: "测试图片没有保存"))
            false
        }
    }

    /** 点击已收藏表情立即追加独立消息；AUTO 复用同一批防抖/视觉预检，不改写草稿。 */
    suspend fun enqueueSticker(
        conversationId: String,
        personaId: String,
        userStickerId: String,
        mediaId: String,
        capturedAt: Long = clock.now(),
        submissionId: String = "sticker-${UUID.randomUUID()}"
    ): Boolean = submissionGate.withLock {
        if (closed.get() || _state.value.backupPaused || !_state.value.ready ||
            _state.value.conversationId != conversationId || _state.value.personaId != personaId) return@withLock false
        val now = capturedAt
        val auto = sendMode() == SendMode.AUTO
        val leading = if (auto) persistPendingBeforeMedia(conversationId, consume = false) else emptyList()
        require(submissionId.isNotBlank()) { "表情提交 ID 不能为空" }
        val intent = StickerSubmissionIntent(userStickerId, personaId)
        val submission = InputSubmission(submissionId, conversationId, emptyList(), listOf(mediaId), now,
            draftVersion = -1, stickerIntent = intent)
        val message = Message(conversationId = conversationId, role = MessageRole.USER, text = "",
            turnId = now, createdAt = now, sentToModel = false)
        try {
            val write = store.appendStickerSubmission(submission, emptyList(), message, intent)
            if (write.inserted) consumePendingPrefix(leading)
            clearMediaRetry()
            reloadNow()
            if (auto) {
                if (synchronized(claimLock) { pending.isNotEmpty() }) scheduleDebounce()
                else scheduleMediaAutoPush(conversationId)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = LlmError.Failed(
                LlmErrorKind.BAD_REQUEST, error.message ?: "表情没有入队，请重试"
            ))
            false
        }
    }

    suspend fun removeUnpushedStickerMessage(messageId: Long): Boolean = submissionGate.withLock {
        if (closed.get() || _state.value.backupPaused) return@withLock false
        val removed = store.removeUnpushedStickerMessage(messageId)
        if (removed) reloadNow()
        removed
    }

    /**
     * 手动模式：把一条消息落进时间线，**不推给模型**。
     *
     * 落盘用的是既有约定（[UserUtterance.encode] 编码类型、`sentToModel = false`
     * 表示"还没被任何一轮取走"）。它立刻就能被 [reloadNow] 读回来 —— 所以界面
     * 不需要一份"待处理"的内存副本，也就不会出现"同一条画两遍"。
     */
    private suspend fun appendUnpushed(kind: EventKind, body: String, isMedia: Boolean, at: Long) {
        val id = _state.value.conversationId.takeIf { it.isNotBlank() } ?: ensureConversation()
        if (store.isBackupPaused(id)) return
        _state.value = _state.value.copy(conversationId = id)
        store.appendMessage(
            Message(
                conversationId = id,
                role = MessageRole.USER,
                text = UserUtterance.encode(kind, body),
                turnId = at,
                createdAt = at,
                sentToModel = false
            )
        )
        // isMedia 目前没有落库的地方（图片功能没做），只在自动模式的队列里带着它。
        reloadNow()
    }

    /**
     * 手动模式下的第二次按下：**把代发的消息推给模型**。
     *
     * 三件事，顺序有讲究：
     *
     * 1. **打断**排在最前 —— 与自动模式里 `send()` 的动作一致：推送等于"我说出来了"，
     *    所以正在播的该停。刻意**不**在第一次按下时打断：那时用户还没决定要说。
     * 2. 先取、再标记、**最后**起轮。标记必须在起轮之前落库，否则模型这一轮读到的
     *    上下文里还看不见这几条（[AgentTurnRunner] 读的是 `modelVisibleMessages`）。
     * 3. `startTurn` 的约定正是"调用方负责落盘" —— 这里不落盘，因为它们已经在库里了，
     *    再写一遍就是"用户消息成对出现"。
     */
    fun push() {
        if (closed.get()) return
        if (sendMode() != SendMode.MANUAL) return
        val id = _state.value.conversationId
        if (id.isBlank()) return
        scope.launch { pushUnpushed(id) }
    }

    /**
     * 把 [conversationId] 里所有代发消息推给模型（标记 + 起一轮）。
     *
     * 调用方负责打断与模式判断。一条都没有时只刷新界面 —— 那会把"有代发"那一态收掉。
     *
     * 三件事的顺序有讲究：
     *
     * 1. 先取、再标记、**最后**起轮。标记必须在起轮之前落库，否则模型这一轮读到的
     *    上下文里还看不见这几条（[AgentTurnRunner] 读的是 `modelVisibleMessages`）。
     * 2. `startTurn` 的约定正是"调用方负责落盘" —— 这里不落盘，因为它们已经在库里了，
     *    再写一遍就是"用户消息成对出现"那个老 bug。
     * 3. 批次里的时间戳取**当下**而不是原按下时刻 —— 回合的时间戳决定会话的 `updatedAt`
     *    与对方那几条事件的 `createdAt`，用"十分钟前"会把它们排到时间轴前头。
     *    用户消息自己的 `createdAt` 仍是按下那一刻（那是它的真实时间）。
     */
    suspend fun pushForTest(onClaimBatch: suspend (List<Long>) -> Unit): Boolean =
        pushUnpushed(_state.value.conversationId, onClaimBatch)

    private suspend fun pushUnpushed(
        conversationId: String,
        onClaimBatch: (suspend (List<Long>) -> Unit)? = null
    ): Boolean = submissionGate.withLock {
        if (store.isBackupPaused(conversationId)) return@withLock false
        val selected = store.getConversation(conversationId)
        if (resolvePersona(store.listPersonas(), selected?.personaId) == null) return@withLock false
        val rows = store.unpushedUserMessages(conversationId)
        if (rows.isEmpty()) {
            onClaimBatch?.invoke(emptyList())
            reloadNow()
            return@withLock true
        }
        val media = store.mediaAssetsForMessages(rows.map { it.id })
        val prepared = try { host.prepareChatCall(conversationId) } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = LlmError.Failed(
                app.solochat.core.llm.LlmErrorKind.NOT_CONFIGURED,
                e.message ?: "模型配置暂不可用，请修复配置后重试"),
                mediaRetryAvailable = media.values.any { it.isNotEmpty() })
            return@withLock false
        }
        val hasNewImages = media.values.any { it.isNotEmpty() }
        val preparedWithMedia = if (prepared != null) {
            try { prepareMediaRequest(prepared, media, conversationId, rows.map { it.id }) } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = LlmError.Failed(LlmErrorKind.BAD_REQUEST,
                    e.message ?: "图片预检失败；图片和同批文字仍保留在代发状态。"), mediaRetryAvailable = true)
                return@withLock false
            }
        } else {
            if (hasNewImages) {
                _state.value = _state.value.copy(error = LlmError.Failed(LlmErrorKind.NOT_CONFIGURED,
                    "当前宿主未完成模型预检；图片和同批文字仍保留在代发状态。"), mediaRetryAvailable = true)
                return@withLock false
            }
            null
        }
        onClaimBatch?.invoke(rows.map { it.id })
        if (playing != null) interruptPlayback("你说出来了")
        store.markUserMessagesPushed(rows.map { it.id })
        // ⚠️ 标记完**立刻**把"有代发"那一态收掉 —— 这是发送键变回灰的唯一时机。
        //
        // 不刷新的后果（真机上就是这样）：图标会一直蓝到**这一轮结束**为止，用户于是
        // 以为"第一次按只是记下来、得再按一次才算发出去" —— 而第二次按下去其实什么都没做
        // （代发已经是 0 条），只是走了取代发为空那条分支、顺手刷新了状态。
        // "推送成功"必须是它变灰的触发点，不能是"回合结束"或"用户再按一次"。
        _state.value = _state.value.copy(pendingSendCount = 0, pendingImageCount = 0)
        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "推送代发",
                "条数" to rows.size,
                "内容" to rows.joinToString(" / ") { UserUtterance.displayText(it.text) }
            )
        )
        // 重建批次：`startTurn` 只拿它算日志文本与回合时间戳。
        val now = clock.now()
        val batch = rows.map { row ->
            val (kind, body) = UserUtterance.decode(row.text)
            IncomingMessage(text = body, isMedia = false, at = now, kind = kind)
        }
        startTurn(conversationId, batch, preparedCall = preparedWithMedia)
        true
    }

    private fun scheduleMediaAutoPush(conversationId: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            val waitMs = host.deps.debounceWindowMs().coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs)
            if (sendMode() == SendMode.AUTO && _state.value.conversationId == conversationId) pushUnpushed(conversationId)
        }
    }

    private suspend fun prepareMediaRequest(
        prepared: app.solochat.core.agent.PreparedAgentCall,
        media: Map<Long, List<Pair<app.solochat.core.model.MessageAttachment, app.solochat.core.model.MediaAsset>>>,
        conversationId: String,
        currentMessageIds: List<Long>
    ): app.solochat.core.agent.PreparedAgentCall = withContext(Dispatchers.IO) {
        val newItems = media.toSortedMap().flatMap { (messageId, attachments) -> attachments.sortedBy { it.first.ordinal }.map { (attachment, asset) ->
            Triple(messageId, attachment, asset)
        } }
        require(newItems.size <= 4) { "本次图片批次最多包含 4 张；图片和文字仍保留。" }
        if (newItems.isNotEmpty()) when (prepared.visionCapability.control) {
            VisionControl.SUPPORTED -> Unit
            VisionControl.UNKNOWN -> error("所选模型的图片能力未知。请在模型设置中确认该型号支持图片后再推送；输入仍保留。")
            VisionControl.UNSUPPORTED -> error("所选模型不支持图片输入；输入仍保留。")
        }
        val content = host.deps.mediaContent
        if (newItems.isNotEmpty()) requireNotNull(content) { "当前宿主未接入图片文件读取；输入仍保留。" }
        val contextPlan = app.solochat.core.agent.ContextPlanner(host.deps).prepare(conversationId)
        val prior = contextPlan.mediaByMessage.toSortedMap().flatMap { (messageId, attachments) -> attachments.sortedBy { it.first.ordinal }.map { (attachment, asset) ->
            Triple(messageId, attachment, asset)
        } }.filterNot { it.first in media.keys }
        val recentWindow = prior.takeLast(RECENT_HISTORY_IMAGE_WINDOW)
        val historyCanAttach = prepared.visionCapability.control == VisionControl.SUPPORTED && content != null
        val chosenHistory = if (historyCanAttach) recentWindow.takeLast((4 - newItems.size).coerceAtLeast(0)) else emptyList()
        var omittedHistory = prior.size - chosenHistory.size
        val items = newItems.map { PlannedMediaItem(it.first, it.second, it.third, MediaReferenceSource.NEW) } +
            chosenHistory.asReversed().map { PlannedMediaItem(it.first, it.second, it.third, MediaReferenceSource.RECENT_HISTORY) }
        var totalBytes = 0L
        var totalPixels = 0L
        val images = mutableListOf<app.solochat.core.llm.ApiImage>()
        val references = mutableListOf<MediaContextReference>()
        items.forEach { (messageId, attachment, asset, source) ->
            try {
                val variant = requireNotNull(content).prepareSendVariant(asset.id)
                val nextBytes = totalBytes + variant.byteSize
                val nextPixels = totalPixels + variant.width.toLong() * variant.height
                if (variant.byteSize > 8L * 1024 * 1024 || nextBytes > 8L * 1024 * 1024 || nextPixels > 16_000_000L) {
                    if (source == MediaReferenceSource.NEW) error("本轮新图片超过 8 MiB 或 1600 万像素预算；输入仍保留。")
                    omittedHistory++
                    return@forEach
                }
                val bytes = content.openSendVariant(variant).use { it.readBytes() }
                require(bytes.size.toLong() == variant.byteSize) { "图片发送版本长度变化；输入仍保留。" }
                val dataUrl = "data:${variant.mimeType};base64," + java.util.Base64.getEncoder().encodeToString(bytes)
                totalBytes = nextBytes
                totalPixels = nextPixels
                images += app.solochat.core.llm.ApiImage(variant.mimeType, dataUrl)
                references += MediaContextReference(messageId, attachment.ordinal, asset.id, asset.sha256,
                    variant.variant, variant.sha256, variant.mimeType, variant.width, variant.height, source)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (source == MediaReferenceSource.NEW) throw error
                omittedHistory++
            }
        }
        prepared.withMedia(images, references, omittedHistory = omittedHistory, currentMessageIds = currentMessageIds)
    }

    private fun clearMediaRetry() {
        retryMediaMessageIds = null
        if (_state.value.mediaRetryAvailable) _state.value = _state.value.copy(mediaRetryAvailable = false)
    }

    /** 只重放已落盘的媒体消息，不追加消息；每次都会生成新 callId 并重新预检。 */
    fun retryMedia() {
        if (!_state.value.mediaRetryAvailable || closed.get() || pending.isNotEmpty() ||
            !retryMediaInFlight.compareAndSet(false, true)) return
        val retryIds = retryMediaMessageIds?.toList()
        val conversationId = _state.value.conversationId
        scope.launch {
            try {
                if (conversationId.isBlank() || _state.value.conversationId != conversationId) return@launch
                if (retryIds == null) {
                    _state.value = _state.value.copy(mediaRetryAvailable = false)
                    pushUnpushed(conversationId)
                    return@launch
                }
                submissionGate.withLock {
                    if (pending.isNotEmpty() || _state.value.conversationId != conversationId || retryMediaMessageIds != retryIds) return@withLock
                    val all = store.recentMessages(conversationId, -1)
                    val rows = all.filter { it.role == MessageRole.USER && it.id in retryIds }
                    if (rows.size != retryIds.distinct().size || all.any { it.role == MessageRole.USER && it.id > retryIds.max() }) {
                        clearMediaRetry()
                        return@withLock
                    }
                    val media = store.mediaAssetsForMessages(retryIds)
                    try {
                        val prepared = requireNotNull(host.prepareChatCall(conversationId)) { "当前宿主未完成模型预检。" }
                        val preparedWithMedia = prepareMediaRequest(prepared, media, conversationId, retryIds)
                        if (retryMediaMessageIds != retryIds || pending.isNotEmpty()) return@withLock
                        retryMediaMessageIds = null
                        _state.value = _state.value.copy(mediaRetryAvailable = false)
                        val now = clock.now()
                        val batch = rows.sortedBy { it.id }.map { row ->
                            val (kind, body) = UserUtterance.decode(row.text)
                            IncomingMessage(text = body, isMedia = false, at = now, kind = kind)
                        }
                        startTurn(conversationId, batch, preparedCall = preparedWithMedia)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (retryMediaMessageIds == retryIds) {
                            _state.value = _state.value.copy(error = LlmError.Failed(LlmErrorKind.BAD_REQUEST,
                                error.message ?: "图片重试预检失败；原消息仍保留。"), mediaRetryAvailable = true)
                        }
                    }
                }
            } finally {
                retryMediaInFlight.set(false)
            }
        }
    }

    private val resumingBackup = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 仅由用户确认后的按钮调用；解除后自动模式可推送旧代发，手动模式仍须按推送。 */
    fun resumeImportedConversation() {
        if (!resumingBackup.compareAndSet(false, true)) return
        scope.launch {
            try {
                val id = _state.value.conversationId
                if (id.isBlank() || !store.isBackupPaused(id)) return@launch
                host.validateBackupResume(id)
                store.resumeBackup(id)
                reloadNow()
                if (sendMode() == SendMode.AUTO) pushUnpushed(id)
            } finally { resumingBackup.set(false) }
        }
    }

    /** 当前生效的发送方式（每次现读配置，设置页改完下一轮就生效）。 */
    private fun sendMode(): SendMode = host.config().sendMode

    /**
     * 把待处理消息投影成界面条目（它们还没有数据库 id，所以用负数占位）。
     *
     * 动作/环境走的是和落盘后同一套条目类型（[ChatItem.MyAction] / [ChatItem.Narration]），
     * 否则"发出去的瞬间"和"落盘之后"会换一副样子，看起来像闪了一下。
     */
    private fun pendingAsItems(): List<ChatItem> =
        pending.mapIndexed { index, message ->
            val id = -(index + 1).toLong()
            when (message.kind) {
                EventKind.ACT -> ChatItem.MyAction(id = id, text = message.text, sameGroup = index > 0)
                EventKind.SCENE -> ChatItem.Narration(id = id, text = message.text, source = app.solochat.core.model.TimelineSource.MESSAGE)
                else -> ChatItem.Mine(
                    id = id,
                    text = message.text,
                    at = message.at,
                    sameGroup = index > 0
                )
            }
        }

    /**
     * 防抖：每次重启定时器，等窗口过去再决定。
     *
     * 注意这里是"**重启**"而不是"等一次就发" —— 用户连发时，
     * 每一条都会把开工时间往后推，这就是"等你说完"的实现。
     * 具体等多久由 [app.solochat.core.protocol.Debounce] 按规则给。
     */
    private fun scheduleDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            val now = clock.now()
            val decision = runner.planWait(_state.value.conversationId, pending, now)
            Log.fields(
                TAG,
                pairs = arrayOf(
                    "阶段" to "防抖判定",
                    "档位" to decision.tier.name,
                    "等待毫秒" to decision.waitMs,
                    "理由" to decision.reason,
                    "待处理" to "${pending.size} 条"
                )
            )
            _state.value = _state.value.copy(pendingHint = decision.reason)

            if (decision.waitMs > 0) {
                // ⚠️ 先记下"我等的这一批长什么样"。判据必须是**批次本身**，
                // 不能是"重新判定之后还需不需要等" —— 见下面。
                val batchMark = pending.lastOrNull()?.at
                if (decision.tier != DebounceTier.NEW_TURN) delay(decision.waitMs)
                // 等完再看一眼：这期间有没有新消息进来？有就让给下一轮。
                //
                // ⚠️ **不能用 `recheck.waitMs > 0` 当判据**（原来的写法就是那样，是个真 bug）：
                // 对"一批里有 ≥2 条、最后一条又没句末标点"的批次，重新判定**仍然**是
                // CHUNKING 档、仍然 `waitMs > 0` —— 于是这里会一直让给"下一轮"，
                // 而下一轮根本不存在（没有新的 `send()` 就不会再起一个防抖任务）。
                // 结果是这一批永远不被认领、模型永远不被调用：用户看到的就是
                // **"我把话发出去了，它一直不回"**。
                //
                // 这个 bug 是被"一次发多条"逼出来的 —— 一次发 2~3 条（用户在三格里
                // 分别写了话、动作、环境，一起发）恰好就是一个 ≥2 条的批次。
                //
                // 正确的判据是**批次变没变**：变了说明有新消息，那个新任务会重新计时；
                // 没变就说明我等的这批已经稳定了，该开工。
                if (pending.lastOrNull()?.at != batchMark) {
                    Log.debug(TAG, "防抖：等待期间又有新消息，让下一轮处理")
                    return@launch
                }
            }

            _state.value = _state.value.copy(pendingHint = "")
            flushTurn()
        }
    }

    /**
     * 原子地"认领"待处理消息：**取批 + 清空 + 落盘**一次做完，一批只会被认领一次。
     *
     * 这是第一版那个"用户消息显示两遍"bug 的修复点。原来的写法里，
     * "取批"和"清空"是两个独立语句，而真正的锁在之后才拿：
     *
     * ```
     * val batch = pending.toList()      // ← 取批
     * if (batch.isEmpty()) return
     * pending = mutableListOf()         // ← 清空
     * ...
     * busy.withLock { ... runner.run(turn, batch) }   // ← 锁在之后才拿
     * ```
     *
     * 防抖任务会被反复取消重启，于是两条路径能各自读到同一批 `pending`：
     * 一条已走进 flushTurn、另一条还在等锁。两边都落盘、都投给模型 ——
     * 界面上用户消息成对出现，而模型的回复只有一份。
     *
     * 现在"取批 + 清空"在 [claimLock] 里一次完成；**落盘放在之后**，
     * 因为写库是挂起操作，不该在同步块里做。落盘时用的已经是本次认领到的
     * 不可变 batch，所以重复认领在结构上就不可能了。
     *
     * `internal` 而不是 private：这是本类最要紧的不变式，
     * 需要能被测试并发调用验证（见 SessionControllerTest）。
     */
    internal fun claimPending(): ClaimedTurn = synchronized(claimLock) {
        if (pending.isEmpty()) {
            Log.debug(TAG, "认领：空批（说明已被另一次认领取走，或本来就没有待处理）")
            return@synchronized ClaimedTurn(null, emptyList())
        }
        val batch = pending.toList()
        pending = mutableListOf()
        pendingSince = 0
        // ⚠️ **这里刻意不清 pendingItems**。
        //
        // 落盘是挂起操作，发生在认领之后（见 flushTurn）。如果在这里就把界面上的
        // 待处理气泡清掉，就会出现一段"界面空白期"：
        //   认领 → （清空）→ 写库 → 请求模型 → ... → reload
        // 真机日志实测这段空白有一秒多，正是用户看到的
        // "模型回答之前，我发出去的气泡不会显示"。
        //
        // 所以待处理气泡一直留到"对方第一条话开始播出"为止（见 clearPendingBubbles 的调用点）。
        // 落盘之后它会被 reload() 读成正式条目，两边内容一样、时间戳也一样的概率很小，
        // 但即使短暂同时存在也不会闪 —— 因为正式条目在列表前半、待处理在后半，
        // 而我们在对方开始说话的那一刻就把后半段清掉了。
        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "认领批次",
                "条数" to batch.size,
                "内容" to batch.joinToString(" / ") { it.text }
            )
        )
        ClaimedTurn(conversationId = _state.value.conversationId, batch = batch)
    }

    /** 写库失败或暂停状态阻止起轮时，将刚认领的原文放回批次前方。 */
    private fun restorePending(batch: List<IncomingMessage>) = synchronized(claimLock) {
        if (batch.isEmpty()) return@synchronized
        pending = (batch + pending).toMutableList()
        pendingSince = pending.first().at
        _state.value = _state.value.copy(pendingItems = pendingAsItems(), turnPhase = TurnPhase.BATCHING)
    }

    /**
     * 把界面上的待处理气泡收掉，换成正式条目。
     *
     * 传进来的 [projected] 必须是 [runPlayback] 一开始读的那一份，**不能在这里重读**：
     * 重读是把"历史刷新"和"活气泡交接"拆成两次写入的根源，中间那一帧里同一条内容
     * 会同时存在于 `items` 与 `live`，屏幕上就是同一条消息画了两次
     * （真机截图抓到过，因为只存在一两帧，dump 根本抓不住）。
     *
     * 现在整块只有**这一次**状态写入，所以不存在"两边都有"的中间状态。
     */
    private fun handoffToProjection(
        projected: ChatProjector.Projection,
        conversation: Conversation? = null
    ) {
        if (projected.items.isEmpty() && projected.eventIds.isEmpty()) return

        val liveEventId = _state.value.live?.eventId
        // 活气泡指向的事件一旦成为正式条目就必须让位，否则同一内容会出现两次
        val alreadyProjected = liveEventId != null && liveEventId in projected.eventIds

        _state.value = _state.value.copy(
            pendingItems = emptyList(),
            title = conversation?.title ?: _state.value.title,
            mode = conversation?.mode ?: _state.value.mode,
            items = projected.items,
            live = if (alreadyProjected) null else _state.value.live
        )
    }

    /**
     * 把认领到的一批消息落盘（只调用一次）。
     *
     * ⚠️ **编码在这一刻做**：`Message.text` 里存的是带标记的形式（`*动作*` / `（环境）`），
     * 与助手侧 `eventsToAssistantText` 的约定一致。待处理期间存的是原始正文，
     * 因为防抖要看的是用户真正打的那几个字（见 `IncomingMessage.kind`）。
     */
    private suspend fun persist(conversationId: String, batch: List<IncomingMessage>, sentToModel: Boolean = true): List<Long> {
        return batch.flatMap { message ->
            val row = Message(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    text = UserUtterance.encode(message.kind, message.text),
                    turnId = message.at,
                    createdAt = message.at,
                    // ⚠️ **自动模式下这一句是必须的**：这里就是"交给模型的那一刻"
                    // （`persist` 只在 `flushTurn` 里被调，紧接着就 `startTurn`），
                    // 而模型上下文读的是 `modelVisibleMessages`（未推送的不算）。
                    // 写成默认的 `false` 会让自动模式下用户说的话**永远进不了上下文**
                    // —— 而且不报错、界面上也看不出来（气泡照旧显示）。
                    sentToModel = sentToModel
                )
            val submission = InputSubmission(autoSubmissionId(conversationId, message.id), conversationId,
                listOf(SubmissionText(message.kind.wire, message.text)), emptyList(), message.at)
            store.appendSubmission(submission, listOf(row))
        }
    }

    private fun autoSubmissionId(conversationId: String, messageId: String): String {
        val identity = "$conversationId\u0000$messageId"
        return "auto-${checksumOf(identity)}"
    }

    /**
     * 把**一条**已经出口的事件写进 assistant 历史（一个气泡一条消息）。
     *
     * ## 为什么是一条一条写，而不是整场演完写一次
     *
     * 原来在演出收尾处统一写一次（`persistSpokenAssistantMessage`）。那留了三个窗口：
     *
     * 1. **竞态**：用户插话时 `send()` 先 `interruptPlayback()`（它 `scope.launch` 去写历史），
     *    紧接着 `scheduleDebounce()` 若判成"新话题"档（`waitMs = 0`）会**立刻**
     *    `flushTurn → startTurn → buildPrompt` 读消息表。两条协程在同一个 IO 调度器上，
     *    顺序不保证 —— 于是**下一轮可能读不到上一轮刚出口的话**。
     * 2. **进程被杀**：已经出口的气泡在事件表里是 `DELIVERED`（界面上也画得出来），
     *    但消息表里没有 → 模型永远看不到自己说过什么。这正是 docs 记过的
     *    "界面看起来对、模型看不到"那类故障。
     * 3. **时间戳**：整轮共用一个写入时刻，而每条气泡其实是**各自**在某一刻出口的。
     *
     * 现在改成"播完这条就落盘"，三个窗口一起关掉。
     *
     * ## 为什么这件事必须由播出方做
     *
     * 只有播出方知道哪几句真的说出去了。被取消的绝不能进 —— 否则模型以为自己说过
     * 从没出口的话。至于最初那个"写助手历史的代码从来没执行过"的 bug，原因见
     * [AgentTurnRunner] 里的注释：那里拿的全是 `PENDING` 事件。
     *
     * @param event 刚出口的那条，**必须带"出口后"的 delivery**。调用方手里的
     *   `play.events` 是开播前的快照（全是 `PENDING`），直接交给
     *   [PromptBuilder.eventsToAssistantText] 会被它整个滤掉、静默写出空内容 ——
     *   这个坑踩过两次，第二次连报错都没有。
     */
    private suspend fun appendSpokenAssistantLine(play: Playing, event: Event) {
        val text = PromptBuilder.eventsToAssistantText(listOf(event))
        // wait / read / remember 没有正文，历史里不留痕
        if (text.isBlank()) return
        // 落定写入不能被插话取消；仅在实际写入成功后认领幂等标记。
        // 正常播出与打断补写共享锁，后者要等前者完成，避免跳过尚未写成的记录。
        withContext(NonCancellable) {
            historyWriteGate.withLock {
                if (event.id in play.writtenIds) return@withLock
                store.appendMessage(
                    Message(conversationId = play.conversationId, role = MessageRole.ASSISTANT,
                        text = text, turnId = play.turnId, createdAt = clock.now(), sentToModel = false)
                )
                play.writtenIds.add(event.id)
            }
        }
        // 这里刻意**不逐条打日志**：一轮 9 条就是 9 行噪声，而"有没有漏写"在演出收尾
        // 一行里对总数就够了（见 runPlayback 末尾的"已写历史"）。
    }

    /** 把认领到的一批消息交给模型，并把过程中的事件映到界面状态。 */
    private suspend fun flushTurn() {
        if (_state.value.pendingItems.isEmpty()) return
        var prepared = try { host.prepareChatCall(_state.value.conversationId.takeIf { it.isNotBlank() }) }
        catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(pendingHint = e.message ?: "模型配置暂不可用，请修复配置后重试",
                turnPhase = TurnPhase.IDLE, error = LlmError.Failed(
                    app.solochat.core.llm.LlmErrorKind.NOT_CONFIGURED,
                    e.message ?: "模型配置暂不可用，请修复配置后重试"))
            return
        }
        var claimed: ClaimedTurn? = null
        var conversationId = ""
        var waitingOnUnpushed = false
        var turnMessageIds = emptyList<Long>()
        try {
            submissionGate.withLock {
                // Sticker、图片提交与 AUTO 批次认领共用同一边界；不能在锁外先取 pending。
                val current = claimPending()
                if (current.batch.isEmpty()) return@withLock
                claimed = current
                // conversationId 为空说明会话还没建（用户直接从一个空会话发消息）
                conversationId = current.conversationId?.takeIf { it.isNotBlank() } ?: ensureConversation()
                if (store.isBackupPaused(conversationId)) {
                    restorePending(current.batch)
                    claimed = null
                    return@withLock
                }
                // 新建会话时重新绑定，确保演出会话 ID 先于 startTurn 生效。
                _state.value = _state.value.copy(conversationId = conversationId)
                val priorUnpushed = store.unpushedUserMessages(conversationId)
                waitingOnUnpushed = priorUnpushed.isNotEmpty()
                // 落盘在认领之后、请求之前，并与媒体提交串行，避免图文漏出本轮合并边界。
                turnMessageIds = persist(conversationId, current.batch, sentToModel = !waitingOnUnpushed)
                if (!waitingOnUnpushed && prepared != null) {
                    prepared = try { prepareMediaRequest(prepared, emptyMap(), conversationId, turnMessageIds) }
                        catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            _state.value = _state.value.copy(error = LlmError.Failed(LlmErrorKind.BAD_REQUEST,
                                error.message ?: "近期历史图片未附入本轮；原文字仍会发送。"))
                            prepared.withMedia(emptyList(), emptyList(), currentMessageIds = turnMessageIds)
                        }
                }
            }
        } catch (error: CancellationException) {
            claimed?.let { restorePending(it.batch) }
            throw error
        } catch (error: Exception) {
            claimed?.let { restorePending(it.batch) }
            _state.value = _state.value.copy(pendingHint = "消息暂未保存，请稍后重试",
                turnPhase = TurnPhase.BATCHING, error = LlmError.Failed(LlmErrorKind.BAD_REQUEST,
                    error.message ?: "消息暂未保存，请稍后重试"))
            return
        }

        val turn = claimed ?: return
        val batch = turn.batch

        if (waitingOnUnpushed) {
            // 已持久化的未推送消息与这段 AUTO 连发输入交给同一入口统一预检/认领。
            pushUnpushed(conversationId)
            return
        }

        startTurn(conversationId, batch, preparedCall = prepared)
    }

    /**
     * 起一轮。**调用方负责落盘**，这里只管"把消息送出去、把回来的事件映进状态"。
     *
     * @param batch 这一轮要回应的用户消息。**可以是空的** —— 见 [cue]。
     * @param cue 引擎插的"台上提示"：此刻发生了什么、所以你现在该干嘛。
     *
     * 为什么要允许空批次：模式刚切换时得让对方主动开个场，而那时**没有任何用户消息**。
     * 原来的做法是伪造一条 `"[系统] 你们见面了…"` 塞进待处理队列 —— 它会以
     * `MessageRole.USER` 落库、以"我"的气泡显示，于是模型以为对方说了那么一句，
     * 用户看到一条自己发的系统通知。现在改成走 [cue]：只进提示词的变动区，
     * 不落成任何一方的消息（详见 [AgentTurn.cue] 的注释）。
     */
    private fun startTurn(conversationId: String, batch: List<IncomingMessage>, cue: String = "",
        preparedCall: app.solochat.core.agent.PreparedAgentCall? = null) {
        val previous = turnJob
        previous?.cancel()
        val settlement = playbackSettlement
        turnJob = scope.launch {
            // 先等旧轮取消及已出口历史落定，再装配下一轮上下文。
            previous?.join()
            settlement?.join()
            if (store.isBackupPaused(conversationId)) return@launch
            val turn = AgentTurn(
                conversationId = conversationId,
                userText = runner.userTextOf(batch),
                // 空批次时没有"用户消息时刻"可用，取当下 —— 事件的时间戳与
                // 会话的 updatedAt 都靠它，取 0 会让这一轮的事件排到时间轴最前面。
                at = batch.lastOrNull()?.at ?: clock.now(),
                cue = cue
            )
            var failed = false
            val resolvedCall = preparedCall ?: try { host.prepareChatCall(conversationId) }
                catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = LlmError.Failed(
                        app.solochat.core.llm.LlmErrorKind.NOT_CONFIGURED,
                        e.message ?: "模型配置暂不可用，请修复配置后重试"), turnPhase = TurnPhase.IDLE)
                    return@launch
                }
            runner.run(turn, batch, resolvedCall).collect { event ->
                when (event) {
                    is AgentEvent.Thinking ->
                        _state.value = _state.value.copy(
                            pendingHint = "正在想…",
                            // 交出去了，从"等你"变成"在想"：右上角换成转圈
                            turnPhase = TurnPhase.THINKING
                        )

                    is AgentEvent.Waiting ->
                        _state.value = _state.value.copy(pendingHint = event.reason)

                    is AgentEvent.ScriptReady -> {
                        _state.value = _state.value.copy(
                            pendingHint = "",
                            turnPhase = TurnPhase.IDLE
                        )
                        playEvents(
                            conversationId = conversationId,
                            turnId = event.turnId,
                            events = event.events,
                            modeProposal = event.modeProposal,
                            modeReason = event.modeReason,
                            usage = null
                        )
                    }

                    is AgentEvent.Silent -> {
                        _state.value = _state.value.copy(
                            pendingHint = "",
                            turnPhase = TurnPhase.IDLE
                        )
                        reload()
                    }

                    is AgentEvent.Failed -> {
                        failed = true
                        val mediaIds = preparedCall?.mediaReferences.orEmpty()
                        val turnIds = preparedCall?.turnMessageIds.orEmpty()
                        if (mediaIds.isNotEmpty() && turnIds.isNotEmpty() && pending.isEmpty() && event.error.kind != LlmErrorKind.CANCELED) {
                            val hasLaterInput = host.deps.storage.recentMessages(conversationId, -1).any {
                                it.role == MessageRole.USER && it.id > turnIds.max()
                            }
                            if (!hasLaterInput) retryMediaMessageIds = turnIds
                        }
                        _state.value = _state.value.copy(
                            pendingHint = "",
                            turnPhase = TurnPhase.IDLE,
                            error = event.error,
                            mediaRetryAvailable = retryMediaMessageIds != null
                        )
                        reload()
                    }

                    is AgentEvent.UsageRecorded -> {
                        _state.value = _state.value.copy(
                            lastCost = event.cost,
                            lastRough = event.rough,
                            lastUsage = event.usage
                        )
                    }
                }
            }
            // 这一轮结束才重算手速统计：它是数据库聚合，不该挤在回合里做
            if (!failed) host.refreshRhythm()
            reload()
        }
    }

    /** [claimPending] 的原子认领结果。`conversationId` 为 null 表示这批没轮到这一次。 */
    internal data class ClaimedTurn(
        val conversationId: String?,
        val batch: List<IncomingMessage>
    )

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    private fun playEvents(
        /** 这场演出属于哪个会话。**必须显式传**，不能读 `_state` —— 见下面的教训。 */
        conversationId: String,
        /** 本轮的 turnId（模型侧一条 assistant 消息对应一个轮次）。 */
        turnId: Long,
        events: List<Event>,
        modeProposal: ChatMode?,
        modeReason: String,
        usage: Usage?
    ) {
        val visible = events.filter { it.kind.isVisible && it.displayText != null }

        // 顺手把 `remember` 落成记忆 —— 它不播出、不进上下文，只是"记下来"
        scope.launch { persistRemembered(events) }

        if (visible.isEmpty()) {
            // 模型选择不说话（wait）：界面只清掉提示，不产生任何气泡
            reload()
            return
        }

        val plan = Player.plan(
            events = visible,
            pacing = currentPacing(),
            readDelayMs = readDelayFor()
        )

        // 对方开始说话了 —— 这时才把待处理气泡换成正式条目。
        // 放在这里而不是认领时，是为了不出现"消息已发出但界面空着"的一段（见 claimPending）。
        //
        // ⚠️ `conversationId` 是**参数**而不是 `_state.value.conversationId`：
        // 全新的会话是 `flushTurn` 里才建的，建完只更新了 state，而 state 的写入
        // 可能还没被看见 —— 读 state 会读到空字符串，于是整场演出被"会话不一致"
        // 的保护判定作废，界面上一条都播不出来。这种"参数 vs 全局状态"的错位
        // 极难从日志上看出来，所以干脆不让它有机会发生。
        val play = Playing(
            conversationId = conversationId,
            turnId = turnId,
            events = visible,
            outcome = plan.outcome
        )
        playing = play

        // 把播放计划记下来。这是排查"一次冒出来还是逐条冒出来"的依据：
        // 每条状态与它的等待时长都在这里，逐条 log 出实际发生的时间点。
        //
        // 只记**种类 + 时长**，不记事件 id 与正文 —— 正文在下面逐条"状态=播出"里已经有，
        // 两边都写会让一轮日志多出上千字符（真机上量过）。
        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "开始播出",
                "可见事件" to visible.size,
                "状态步数" to plan.states.size,
                "计划" to plan.states.joinToString(" ") { step ->
                    val name = when (step.state) {
                        is Player.State.Reading -> "阅读"
                        is Player.State.Typing -> "打字"
                        is Player.State.Gap -> "间隔"
                        is Player.State.Delivering -> "播出"
                        is Player.State.Done -> "结束"
                        Player.State.Idle -> "空闲"
                    }
                    "$name+${step.waitMs}"
                }
            )
        )

        // 模式提议：用户手动切换后的那一轮不要再提议，否则界面会来回弹卡片
        val proposal = if (suppressModeProposal) null else modeProposal
        suppressModeProposal = false
        _state.value = _state.value.copy(
            modeProposal = proposal?.takeIf { host.config().allowModeSwitch },
            modeReason = if (proposal != null) modeReason else "",
            // 从"在想"进入"正在一条一条发出来" —— 右上角换第三个动画
            turnPhase = TurnPhase.SENDING
        )

        typewriterJob?.cancel()
        typewriterJob = scope.launch { runPlayback(plan) }
    }

    /**
     * 把状态序列真正跑一遍。
     *
     * 每一步都检查"我是否还是当前那场演出" —— 用户插话会把 [playing] 清空/替换，
     * 这时必须立刻退出，而不是把剩下的气泡继续放出来（那正是"明明打断了还在刷屏"的来源）。
     *
     * ## 播出期间界面上只能有一个真相
     *
     * 这一版把播出期间的状态收成一条硬规则：
     *
     * - **已出口的**（`delivery.spoken`）才进 `items`，由 [ChatProjector] 保证；
     * - **正在播的那一条**只以 [ChatUiState.live] 存在，**同一时刻最多一条**；
     * - "打字中"（Reading / Typing / Gap）**不占活气泡** —— 那段时间屏幕上应当只有
     *   上一条，外加一条"正在输入…"提示。以前的写法会在打字阶段就把整条正文塞进
     *   活气泡，因为逐字进度要等 `LaunchedEffect` 重启才归零，屏幕上就是
     *   "整条一次性冒出来、紧接着从头重播动画"。
     *
     * 最后一条落定之后先 [reload] 再清 `live`：反过来会留出一段"库里已经有了、
     * 但界面上两边都没有"的空白，观感就是气泡放完一起消失。
     */
    private suspend fun runPlayback(plan: PlaybackPlan) {
        val play = playing ?: return

        // 会话已经换人了：这场演出属于上一个会话，一条都不该再往界面上写
        // （否则新会话会莫名其妙多出几条气泡，而且还会被错当成新会话的历史）。
        if (_state.value.conversationId != play.conversationId) {
            Log.warn(TAG, "演出作废：会话已切到 ${_state.value.conversationId}，本场属于 ${play.conversationId}")
            playing = null
            endPlaybackPhase()
            return
        }
        // **整场演出只读一次库**，读到的那一份直接用于交接。
        //
        // 此刻用户消息已经落盘（见 flushTurn 的 persist），而助手事件还是 PENDING、
        // 被 ChatProjector 的 `delivery.spoken` 门禁挡在历史之外 —— 所以这一份投影
        // 恰好就是"已出口的全部内容"，正是整场演出期间该显示的历史。
        // 读两次（这里一次、以后哪里再一次）就是那条瞬时重复的来源。
        val conversation = store.getConversation(play.conversationId)

        // **交接必须在设 live 之前**：把待处理气泡换成正式条目，并让已经落定的活气泡让位。
        timelineGate.withLock {
            refreshTimelineLocked(play.conversationId)
            if (_state.value.conversationId == play.conversationId) {
                val projected = ChatProjector.projectVisible(timelineRows)
                val projectedWithMedia = projected.copy(items = projectChatItems(timelineRows, projected.items))
                handoffToProjection(projectedWithMedia, conversation)
                _state.value = _state.value.copy(hasEarlierHistory = hasEarlierHistory)
            }
        }
        // 交接会清掉可能已经过期的 live，这里重新确认自己仍是当前那场演出
        if (playing !== play) {
            cancelUnplayed(play)
            return
        }

        for (step in plan.states) {
            if (playing !== play) {
                Log.warn(TAG, "演出被作废（用户在演出中途插话），已停止后续播出")
                cancelUnplayed(play)
                return
            }
            when (val s = step.state) {
                is Player.State.Reading -> {
                    Log.debug(TAG, "  阅读 ${s.eventId}")
                    _state.value = _state.value.copy(live = null)
                }

                is Player.State.Typing -> {
                    Log.debug(TAG, "  打字 ${s.eventId}")
                    _state.value = _state.value.copy(live = null)
                }

                is Player.State.Gap ->
                    Log.debug(TAG, "  间隔 ${s.eventId} ${step.waitMs}ms")

                is Player.State.Delivering -> {
                    // Delivering 只带 PlaybackStatus（没有事件 id），所以按正文内容定位。
                    // 正文可能重复（两条都是"嗯"），indexOfFirst 会取到第一条 —— 这没关
                    // 系，因为活气泡只用于渲染，真正决定"哪条算说过"的是引擎的计数。
                    val index = play.events.indexOfFirst { e -> e.displayText == s.status.bubble }
                    if (index >= 0) {
                        val event = play.events[index]
                        play.playingIndex = index
                        // **气泡出现的这一刻**就是它的交付时间。记在这里（而不是等它播完）
                        // 是为了让"被打断的那条"留在它出现的位置上 —— 见 interruptPlayback。
                        play.startedAt[event.id] = clock.now()
                        Log.debug(
                            TAG,
                            "  播出 ${index + 1}/${play.events.size} ${event.id}「${s.status.bubble?.take(20)}」"
                        )
                        _state.value = _state.value.copy(live = liveFor(play, event.id))
                    } else {
                        Log.warn(TAG, "  状态=播出 但按正文找不到对应事件：「${s.status.bubble}」")
                    }
                }

                else -> Unit
            }

            if (step.waitMs > 0) delay(step.waitMs)

            // 这条"播出"动作在此刻完成。逐字出现的进度交给界面自己按时间推进，
            // 引擎只负责"什么时候开始出现"和"什么时候算说完"。
            if (step.state is Player.State.Delivering) {
                val index = play.playingIndex ?: play.playedCount
                // ⚠️ **在这一刻就落定**，而不是等整场演出结束再统一标。
                // 统一标会让这一轮所有事件共用一个 deliveredAt（真机日志里实测过：
                // 两条回复的时间戳完全相同），于是它们在同一帧一起冒出来。
                // 逐条标之后，时间戳与"确实一条一条发出来"这件事才对得上。
                val event = play.events.getOrNull(index)
                if (event != null && event.delivery == DeliveryState.PENDING) {
                    // 用"出现的那一刻"，与上面记进 startedAt 的是同一个值 ——
                    // 这样正常播完的和被打断的，时间戳口径完全一致。
                    sink.markDelivered(event.id, play.startedAt[event.id] ?: clock.now())
                }
                // ⚠️ **播完这一条就写进助手历史**，不等整场演完。
                //
                // 原来在演出收尾处统一写一次，那留了三个窗口（竞态 / 进程被杀 /
                // 整轮共用一个时间戳），详见 [appendSpokenAssistantLine] 的注释。
                //
                // 传 `copy(delivery = DELIVERED)`：手里的 `play.events` 是开播前的快照
                // （`delivery` 还是 `PENDING`），而 `eventsToAssistantText` 只收已出口的
                // —— 直接传原件会被它整个滤掉、静默不写。状态刚刚在上一行落定，
                // 所以这里补一个"出口后"的副本是准确的，不是猜。
                if (event != null) {
                    appendSpokenAssistantLine(play, event.copy(delivery = DeliveryState.DELIVERED))
                }
                play.playingIndex = null
                play.playedCount += 1

                // ⚠️ **每播完一条就把它落进时间轴，并且立刻撤掉活气泡。**
                //
                // 这就是用户报的那个"逐条覆盖"：
                //
                // 原来整场演出期间从不刷新 `items`（`reload` 会被"演出进行中"挡掉），
                // 于是已经播完的那几条在界面上**没有位置** —— 屏幕上只有当前那条活气泡。
                // 新的一条一出来就把上一条顶掉，看起来像"只有一条，逐条覆盖"；
                // 直到整场演完、收尾那次 reload 才把两条一起放出来。
                //
                // 现在的时序对上了真实聊天的观感：
                //   播出第 1 条 → 落定 → 进历史（留在屏幕上）→ 间隔 → 播出第 2 条 → …
                //
                // 撤活气泡必须在同一步里做完：留着它就会和刚进 `items` 的那条重复。
                // 先清 `live` 再刷新，中间最多是一帧的空档（活气泡刚撤、items 还没到），
                // 之后那条会以"正式条目"的身份回来。
                _state.value = _state.value.copy(live = null)
                reloadNow()
            }
        }
        if (playing !== play) return

        // 全场播完：先把正式条目读回来，再撤掉活气泡 —— 顺序反了会有一段空白
        // （live 已清、items 还没到），观感是"放完就一起消失了"。
        //
        // ⚠️ 这里必须**先把 `playing` 清掉**再刷新：`reloadNow()` 内部会读
        // `_state`，而"让路"判断看的是 `playing`。顺序反了就会自己把自己挡掉。
        val deferred = reloadRequestedWhilePlaying
        playing = null
        reloadRequestedWhilePlaying = false
        endPlaybackPhase()
        if (deferred) Log.debug(TAG, "补做演出期间被让路的 reload")

        reloadNow()
        // 一轮的收尾就这一行：播出条数 + 写进助手历史的条数。
        // "已写历史"是那处老 bug 的哨兵（原来一次都没写过），逐条打日志太吵，
        // 所以只在这里对总数；对不上才单独 WARN。
        val expectedWrites = play.events.count { it.displayText != null }
        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "演出结束",
                "已播出" to "${play.playedCount} 条",
                "已写历史" to "${play.writtenIds.size} 条"
            )
        )
        if (play.writtenIds.size != expectedWrites) {
            Log.warn(TAG, "写助手历史的条数对不上：应有 $expectedWrites，实际 ${play.writtenIds.size}")
        }
    }

    /**
     * 这场演出被作废时，把**还没轮到播的**事件标成已取消。
     *
     * 为什么必须有这一步（真机 + 回归测试都抓到过）：用户插话会走 [interruptPlayback]，
     * 它已经按引擎的计数把该取消的取消了；但那条路径是**异步**的，而且演出协程自己
     * 会在下一个检查点直接 return —— 于是两件事会打架：
     *
     * - 演出协程先退出：剩下的事件**永远停在 `PENDING`**，既没播出去、也没被取消。
     *   它们在库里是"悬空"的，重启收尾才会被扫成 CANCELED，
     *   而在此之前任何一次 `unfinishedEvents` 都会看到它们。
     * - 更糟的是"被打断的那条"边界：演出协程退出时 `playingIndex` 可能还没清，
     *   光看计数会算错范围。
     *
     * 所以这里用一个保守的判据：**除了已经出口的（说过/说了一半），全部取消**。
     * 与 [interruptPlayback] 的结果正好一致，重复标同一个状态是幂等的。
     */
    private suspend fun cancelUnplayed(play: Playing) {
        endPlaybackPhase()
        // 读一次就够：这场演出涉及的事件都在这份快照里
        val latest = store.eventsOf(play.conversationId).associateBy { it.id }
        var count = 0
        for (e in play.events) {
            val current = latest[e.id] ?: continue
            if (current.delivery.spoken || current.delivery == DeliveryState.CANCELED) continue
            sink.setDelivery(e.id, DeliveryState.CANCELED)
            count += 1
        }
        if (count > 0) {
            Log.fields(
                TAG,
                pairs = arrayOf(
                    "阶段" to "作废收尾",
                    "已取消" to "$count 条（绝不能停在 PENDING，否则它们算是悬空事件）"
                )
            )
        }
    }

    /**
     * 演出结束/作废时统一收尾：右上角那个指示器回到"什么都没在跑"。
     *
     * ⚠️ 只在自己确实是 [TurnPhase.SENDING] 时才动它。用户插话时 `send()` 已经把
     * 阶段改成了 [TurnPhase.BATCHING]，而演出协程随之作废、也会走到这里 ——
     * 无条件写 IDLE 会把"你的话攒着"那个状态抹掉（两条协程的先后顺序不确定，
     * 所以两头都必须能自洽）。
     */
    private fun endPlaybackPhase() {
        if (_state.value.turnPhase == TurnPhase.SENDING) {
            _state.value = _state.value.copy(turnPhase = TurnPhase.IDLE)
        }
    }

    /** 立刻停住未播出的部分。用户插话时调用。 */
    private fun interruptPlayback(reason: String) {
        val play = playing ?: return
        val outcome = play.outcome ?: return
        val cut = Player.interrupt(
            outcome = outcome,
            playedCount = play.playedCount,
            playingIndex = play.playingIndex
        )
        playing = null
        typewriterJob?.cancel()

        val previousSettlement = playbackSettlement
        playbackSettlement = scope.launch {
            previousSettlement?.join()
            // ⚠️ 交付时间取**各自出现的那一刻**，不是"打断发生的这一刻"。
            //
            // 原来这里全都盖 `now`，代价是真机上看到的怪事：一条 16:34:27 就已经
            // 显示给用户的气泡，被打断后时间戳变成 16:34:31 —— 界面上它**往下跳**了，
            // 跑到用户刚刚发的那条消息下面。用户会以为顺序乱了。
            // 现在统一用出现时刻，正常播完与被打断两条路径的口径也一致。
            for (e in cut.delivered) {
                sink.markDelivered(e.id, play.startedAt[e.id] ?: clock.now(), 0, 0)
                // 顺手补写历史：`markDelivered` 是挂起调用，中断可能正好插在
                // "已落定、还没写历史"那一瞬间。writtenIds 会把重复的挡掉。
                appendSpokenAssistantLine(play, e.copy(delivery = DeliveryState.DELIVERED))
            }
            // 正在播的那条：它整条其实已经显示出来了（`INTERRUPTED` 也算出口，
            // 投影会把完整正文画出来），但那一轮被截断在这儿 —— 标成 interrupted，
            // 下一轮上下文会带上"你刚才被打断了"。
            cut.interrupted?.let {
                val at = play.startedAt[it.id] ?: clock.now()
                sink.markInterrupted(it.id, at)
                appendSpokenAssistantLine(play, it.copy(delivery = DeliveryState.INTERRUPTED))
            }
            // 没播的：标记为取消，**绝不进上下文**，否则模型以为自己说过
            for (e in cut.canceled) sink.setDelivery(e.id, DeliveryState.CANCELED)

            Log.fields(
                TAG,
                pairs = arrayOf(
                    "阶段" to "插话打断",
                    "原因" to reason,
                    "已播出" to "${cut.delivered.size} 条",
                    "被打断" to (cut.interrupted?.displayText?.take(20) ?: "无"),
                    "已取消" to "${cut.canceled.size} 条"
                )
            )

            // ⚠️ **先落定、再刷新**。
            //
            // 顺序反了会出现一帧"库里还没落定、活气泡已经撤掉"的空白：那条明明已经
            // 显示出来的气泡会先消失一下再回来。第一次写反了，被回归测试逮住。
            //
            // 助手历史**不在这里补写**：每条出口时就各自写过了（见
            // [appendSpokenAssistantLine]），上面两个分支只是把可能漏掉的补上。
            _state.value = _state.value.copy(live = null, pendingHint = reason)
            reloadNow()
        }
    }

    /**
     * 生成"正在演出"的占位条目。
     *
     * ⚠️ **必须带上两个东西，缺一个就会出现真机上那种观感问题**：
     *
     * - `eventId`：交接时判断"这条是不是已经落进历史了"只能靠 id。拿正文比会误判 ——
     *   同一个「嗯」出现两次是合法的，靠正文就会把新的一条误当成旧的。
     * - `at`：活气泡上也要有时间戳，否则气泡在"落定"那一刻会突然多出一个时间戳
     *   把整行高度顶一下，看起来像闪了一下。
     *
     * 逐字进度**刻意不放进来**：它由界面按 `live.key` 自己归零（见 ChatScreen）。
     * 引擎再放一份副本只会让人分不清哪一份在起作用 —— 之前那个 `visibleChars`
     * 就是这个下场：写是写了，从来没人读。
     */
    private fun liveFor(play: Playing, eventId: Long): ChatItem.Live {
        val event = play.events.firstOrNull { it.id == eventId }
        return ChatItem.Live(
            key = "live-$eventId",
            eventId = eventId,
            kind = event?.kind ?: EventKind.SAY,
            text = event?.displayText.orEmpty(),
            speaker = event?.speaker.orEmpty(),
            at = clock.now()
        )
    }

    // ------------------------------------------------------------------
    // 模式切换
    // ------------------------------------------------------------------

    /** 用户写入的系统事实，不冒充用户发言；保存后供下一轮使用，不额外触发模型。 */
    fun sendSystem(text: String) {
        val body = text.trim()
        if (body.isEmpty() || closed.get()) return
        val id = _state.value.conversationId
        if (id.isBlank()) return
        ManualModeCommand.systemBody(body)?.let { applyMode(it); return }
        scope.launch {
            if (store.isBackupPaused(id)) return@launch
            val now = clock.now()
            store.appendMessage(Message(
                conversationId = id, role = MessageRole.SYSTEM,
                text = "【系统消息】$body", turnId = now, createdAt = now
            ))
            reload()
        }
    }

    /**
     * 应用模式切换。
     *
     * 四件事必须一起做，少一件都会留下不一致：
     * ① 改会话上的 mode
     * ② 把切换点作为 **SYSTEM** 消息写进历史（模型才能看到"我们见面了"）
     * ③ 界面上打一条分隔行（②那条消息投影出来的）
     * ④ 通过 [startTurn] 的 `cue` 让对方主动开个场 —— **不产生任何用户消息**
     */
    fun applyMode(mode: ChatMode) {
        if (closed.get()) return
        scope.launch { applyModeAndWait(mode) }
    }

    suspend fun applyModeAndWait(mode: ChatMode) {
        if (closed.get()) return
        submissionGate.withLock {
                val id = _state.value.conversationId
                if (store.isBackupPaused(id)) return@withLock
                val conversation = store.getConversation(id) ?: return@withLock
                if (resolvePersona(store.listPersonas(), conversation.personaId) == null) return@withLock
                if (conversation.mode == mode) {
                    _state.value = _state.value.copy(modeProposal = null, modeReason = "")
                    return@withLock
                }
                // 模式变化先收尾旧播出/请求，避免旧轮在新模式下继续回写。
                if (playing != null) interruptPlayback("切换模式")
                turnJob?.cancel()
                turnJob?.join()
                playbackSettlement?.join()
                val now = clock.now()
                store.changeMode(id, mode, now)
                // 系统消息：以"事实告知"的形式进上下文，不伪装成任何一方说的话。
                //
                // ⚠️ 这是"切换发生了"这件事**唯一**进历史的痕迹，也是界面上那条居中小字。
                // 它必须留在 SYSTEM 里 —— 一旦改成用户消息，模型就会以为对方说了这么一句。
                store.appendMessage(
                    Message(
                        conversationId = id,
                        role = MessageRole.SYSTEM,
                        text = ChatProjector.dividerFor(mode),
                        turnId = now,
                        createdAt = now
                    )
                )
                _state.value = _state.value.copy(
                    mode = mode,
                    modeProposal = null,
                    modeReason = ""
                )
                // 切换后自动让对方开个场。
                //
                // ⚠️ **不能再往 `pending` 里塞一条 `"[系统] 你们见面了…"`**（那正是原来的写法）。
                // 待处理消息一律以 `MessageRole.USER` 落库、以"我"的气泡显示，于是
                // 模型看到的是「对方：[系统] 你们见面了，开始面对面。」——它会以为对方说了话；
                // 用户看到的是一条自己发的系统通知。**两个身份都错了。**
                //
                // 现在：切换的事实由上面那条 SYSTEM 消息进历史，"这时候该干嘛"走 `cue`
                // 只进提示词变动区。批次是空的 —— 这一轮没有人在说话，是引擎在叫她开口。
                // 压制它自己再提议一次，避免来回抖。
                suppressModeProposal = true
                reload()
                startTurn(
                    conversationId = id,
                    batch = emptyList(),
                    cue = when (mode) {
                        ChatMode.SCENE ->
                            "你们刚刚从「隔着手机发消息」变成了「面对面」—— 对方现在就在你面前。" +
                                "你先开个场：一两个事件就够（一个动作，或一句短话），然后停下等他回应。"
                        ChatMode.CHAT ->
                            "你们刚刚从「面对面」变回了「隔着手机发消息」—— 对方掏出了手机。" +
                                "可以接一句短的，像刚分开那样。"
                    }
                )
        }
    }

    /** 用户拒绝模式切换。 */
    fun dismissModeProposal() {
        _state.value = _state.value.copy(modeProposal = null, modeReason = "")
    }

    // ------------------------------------------------------------------
    // 记忆
    // ------------------------------------------------------------------

    /**
     * 把 `remember` 事件写进记忆本。
     *
     * 落盘时顺手去重：同一句话（去空白后完全相同）不重复记。
     * 模型很容易在相邻几轮里重复记同一条，不去重的话记忆页会很快变成垃圾场。
     *
     * ⚠️ 记忆是**按人设分开存**的（`Memory.personaId`）：不同人设是不同的人，
     * 共用一份记忆会让"小默"知道只有跟"阿岩"聊过的事。这里用的必须是
     * **解析之后生效的**人设 id（[ChatUiState.personaId]），否则会和注入时的
     * 查询条件错位 —— 那表现为"记了但从来不出现"。
     */
    private suspend fun persistRemembered(events: List<Event>) {
        val remembered = events.filter { it.kind == EventKind.REMEMBER && it.text.isNotBlank() }
        if (remembered.isEmpty()) return
        // 去重只在**同一张人设**范围内做：不同人设完全可以各记一条同样的内容
        val personaId = _state.value.personaId
        val existing = store.listMemories(personaId).map { it.content.trim() }.toSet()
        val now = clock.now()
        for (e in remembered) {
            val content = e.text.trim()
            if (content in existing) continue
            // 敏感信息兜底：模型偶尔会记下不该记的东西，这里做最后一道拦截
            if (SENSITIVE_PATTERN.containsMatchIn(content)) continue
            store.upsertMemory(
                Memory(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    personaId = personaId,
                    tags = emptyList(),
                    createdAt = now,
                    lastUsedAt = now,
                    useCount = 0,
                    sourceConversationId = _state.value.conversationId
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 从库里重读整个时间轴。
     *
     * ⚠️ **演出期间必须让路**（[force] 为 false 时直接返回）。
     *
     * 为什么：演出期间"屏幕上有什么"是由 `items + pendingItems + live` 三者拼出来的，
     * 而 `reload` 只改 `items` —— 它一旦和演出交错，就会出现"某条内容既在 items 里、
     * 又是当前的活气泡"，屏幕上同一条画两次。用户报的正是这个现象。
     *
     * ⚠️ **让路必须记账，不能直接丢掉**（[reloadRequestedWhilePlaying]）。
     *
     * 这条是补上面那条的漏洞，代价曾经很实在：回合结束时的 `reload()` 会和演出自己的
     * 收尾**并发**，谁先谁后不确定。如果它先跑，`playing` 还没清空，这次刷新就被丢掉；
     * 而演出的收尾此刻正在写 `live = null`，写完也不会再刷一次 ——
     * 结果是**库里已经有的两条回复永远画不出来**（我写的回归测试就是这么超时的）。
     * 现在记一笔账，演出收尾时补做。
     */
    fun reload(force: Boolean = false) {
        if (!force && playing != null) {
            Log.debug(TAG, "reload 让路（演出中，收尾时补做）")
            reloadRequestedWhilePlaying = true
            return
        }
        scope.launch { reloadNow() }
    }

    /** 真正读库并更新 `items`。集中一处，避免"让路"逻辑被绕过。 */
    private suspend fun reloadNow(resetHistory: Boolean = false) {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        val conversation = store.getConversation(id)
        val paused = store.isBackupPaused(id)
        // 人设走 resolvePersona：界面显示的和模型用的是同一个回退规则
        val persona = resolvePersona(store.listPersonas(), conversation?.personaId)
        timelineGate.withLock {
        refreshTimelineLocked(id, resetHistory)
        if (_state.value.conversationId != id) return@withLock
        val pendingCount = store.unpushedUserCount(id)
        val pendingImageCount = store.unpushedMediaCount(id)
        _state.value = _state.value.copy(
            ready = true,
            title = conversation?.title ?: "",
            mode = conversation?.mode ?: ChatMode.CHAT,
            items = projectChatItems(timelineRows, ChatProjector.projectVisible(timelineRows).items),
            hasEarlierHistory = hasEarlierHistory,
            personaName = persona?.name.orEmpty(),
            personaAvatar = persona?.avatar.orEmpty(),
            personaId = persona?.id.orEmpty(),
            personaIsFallback = persona != null && persona.id != conversation?.personaId,
            sendMode = sendMode(),
            // 代发数量独立查询，不能随当前只加载了哪几页变化。
            backupPaused = paused,
            pendingSendCount = pendingCount,
            pendingImageCount = pendingImageCount
        )
        }
    }

    /** 持 timelineGate；最新页与已读区间之间有空隙时按页接齐，不静默跳过中间内容。 */
    private suspend fun refreshTimelineLocked(id: String, reset: Boolean = false) {
        var page = store.timelinePage(id)
        if (reset || timelineConversation != id || timelineRows.isEmpty() || page.entries.isEmpty()) {
            timelineConversation = id
            timelineRows = page.entries
            hasEarlierHistory = page.hasEarlier
            return
        }
        val fresh = page.entries.toMutableList()
        val previousLast = timelineRows.last().position
        while (page.hasEarlier && page.entries.isNotEmpty() && fresh.first().position > previousLast) {
            page = store.timelinePage(id, page.entries.first().position)
            if (page.entries.isEmpty()) break
            fresh.addAll(0, page.entries)
        }
        if (fresh.first().position <= timelineRows.first().position) hasEarlierHistory = page.hasEarlier
        timelineRows = (timelineRows + fresh).associateBy { it.key }.values.sortedBy { it.position }
    }

    fun loadEarlierHistory() = requestHistoryPage(latest = false)
    fun returnToLatestHistory() = requestHistoryPage(latest = true)

    private fun requestHistoryPage(latest: Boolean) {
        if (closed.get() || historyPageJob?.isActive == true || !_state.value.ready) return
        if (!latest && !_state.value.hasEarlierHistory) return
        val id = _state.value.conversationId
        _state.value = _state.value.copy(loadingHistory = true, historyError = "")
        historyPageJob = scope.launch {
            try {
                timelineGate.withLock {
                    if (_state.value.conversationId != id) return@withLock
                    if (latest) refreshTimelineLocked(id, reset = true)
                    else {
                        val page = store.timelinePage(id, timelineRows.firstOrNull()?.position)
                        timelineRows = (page.entries + timelineRows).distinctBy { it.key }.sortedBy { it.position }
                        hasEarlierHistory = page.hasEarlier
                    }
                    if (_state.value.conversationId != id) return@withLock
                    val projected = ChatProjector.projectVisible(timelineRows)
                    _state.value = _state.value.copy(items = projectChatItems(timelineRows, projected.items), hasEarlierHistory = hasEarlierHistory,
                        live = _state.value.live?.takeUnless { it.eventId in projected.eventIds },
                        latestHistoryRequest = _state.value.latestHistoryRequest + if (latest) 1 else 0)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (_state.value.conversationId == id) _state.value = _state.value.copy(historyError = "历史加载失败，请重试")
            } finally {
                if (_state.value.conversationId == id) _state.value = _state.value.copy(loadingHistory = false)
            }
        }
    }

    private suspend fun projectChatItems(rows: List<TimelineEntry>, base: List<ChatItem>): List<ChatItem> {
        val messageIds = rows.filterIsInstance<TimelineEntry.MessageRow>().map { it.message.id }
        val attached = store.mediaAssetsForMessages(messageIds)
        val stickers = store.messageStickers(messageIds).groupBy { it.messageId to it.ordinal }
        return base.map { item ->
            if (item is ChatItem.Mine) item.copy(
                attachments = attached[item.id].orEmpty().sortedBy { it.first.ordinal }.map { it.second },
                isSticker = stickers.containsKey(item.id to 0)
            )
            else item
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * 把"此刻屏幕上有什么"打进日志。
     *
     * ## 为什么需要它
     *
     * 排查"气泡是逐条累积还是逐条覆盖"时，常规手段全都够不着：
     *
     * - `uiautomator dump` 一次要 2.5 秒，只能看到秒级的粗粒度，而那一幕在秒级两帧之间；
     * - 这台机器上 `screenrecord` **完全不可用** —— 写 `/sdcard` 与 `/data/local/tmp`
     *   都被拒（SELinux 不让 shell 域往那儿写），`exec-out` 流式输出也拿不到数据。
     *
     * 而状态本身是**同步写**的，抓它比抓屏幕准得多。所以由用户在现象发生的**那一刻**
     * 点一下（设置里那个开关打开时，点标题栏即可），把三者原样记下来：
     *
     * - `items`：已成历史的（这些必须**留着**）
     * - `live`：正在播的那一条
     * - `pending`：还没交给模型的
     *
     * 判据很直接：**正在播第 2 条时，`items` 里应当已经有第 1 条**。
     * 如果 `items` 是空的、只有 `live`，那就是"逐条覆盖"。
     */
    fun dumpScreenState() {
        val s = _state.value
        Log.fields(
            TAG,
            pairs = arrayOf(
                "阶段" to "屏幕快照",
                "历史(items)" to "${s.items.size} 条：${s.items.joinToString(" / ") { it.textOf().take(14) }}",
                "正在播(live)" to (s.live?.text?.take(20) ?: "无"),
                "待处理(pending)" to "${s.pendingItems.size} 条",
                "提示" to s.pendingHint.ifBlank { "无" }
            )
        )
    }

    /** 给日志用的短文本（不引入 UI 依赖，只取正文）。 */
    private fun ChatItem.textOf(): String = when (this) {
        is ChatItem.Mine -> text
        is ChatItem.Theirs -> text
        is ChatItem.Action -> text
        is ChatItem.MyAction -> text
        is ChatItem.Narration -> text
        is ChatItem.Divider -> text
        is ChatItem.Live -> text
    }

    // ------------------------------------------------------------------

    /**
     * 当前的节奏参数。
     *
     * 每次都从宿主的**设置快照**构造而不是缓存：用户在设置页把"每字 700ms"改成 300ms
     * 之后，下一条消息就该按新值走，不该等重启。
     *
     * 读的是 `host.pacingConfig()`（一个 @Volatile 快照）而不是挂起读 DataStore ——
     * 这个方法在播放路径上被调用，不能挂起。
     */
    private fun currentPacing(): Pacing = Pacing(
        config = host.pacingConfig(),
        random = kotlin.random.Random.Default
    )

    /**
     * "看完消息"的延迟：用户最后一条消息越长，对方"看得越久"。
     *
     * ⚠️ 面对面**不适用**这条 —— 那里没有"拿起手机读一条消息"这个动作，
     * 话是当场听见的。所以场景模式只取一个很短的反射时间（见 `Pacing.sceneReadDelay`）。
     */
    private fun readDelayFor(): Long {
        if (_state.value.mode == ChatMode.SCENE) return currentPacing().sceneReadDelay()
        val userChars = pending.lastOrNull()?.text?.length
            ?: _state.value.items.filterIsInstance<ChatItem.Mine>().lastOrNull()?.text?.length
            ?: 0
        return currentPacing().readDelay(userChars).ms
    }

    private suspend fun ensureConversation(): String {
        val existing = _state.value.conversationId
        if (existing.isNotBlank()) return existing
        val id = UUID.randomUUID().toString()
        val now = clock.now()
        store.upsertConversation(
            Conversation(
                id = id,
                title = "新对话",
                mode = ChatMode.CHAT,
                modeSince = now,
                createdAt = now,
                updatedAt = now
            )
        )
        _state.value = _state.value.copy(conversationId = id)
        return id
    }
}

