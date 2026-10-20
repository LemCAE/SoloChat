package app.solochat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.solochat.SoloChatApp
import app.solochat.core.agent.SendMode
import app.solochat.core.agent.effectiveConfig
import app.solochat.core.agent.ContextPlanner
import app.solochat.ui.usage.ConversationUsageScreen
import app.solochat.core.model.ChatMode
import app.solochat.core.model.EventKind
import app.solochat.core.model.UserUtterance
import app.solochat.play.ChatItem
import app.solochat.play.SessionController
import app.solochat.play.TurnPhase
import app.solochat.ui.ChatBubble
import app.solochat.ui.CompactTopBar
import app.solochat.ui.theme.SoloChatTheme
import app.solochat.ui.personaLabel
import kotlinx.coroutines.delay

/**
 * 聊天主界面。
 *
 * 三块结构拼起来：标题栏（人设 / 模式 / 进行中状态 / 会话设置）、消息流、输入区。
 *
 * 输入区刻意**保持常驻可用**：即使对方正在播放，用户也必须能立刻插话 ——
 * 这是 [SessionController] 里抢占逻辑存在的前提，界面上不能把它挡住。
 *
 * 输入区上方**不再有任何提示行**：进行中状态只在标题栏转圈（原因见标题栏注释）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPersonas: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp

    // Controller 与 composable 同生命周期：一个会话一个实例。
    // remember(conversationId) 很关键 —— 换会话时必须拿到新的 controller，
    // 否则会把上一个会话的待处理消息带过来。
    val controller = remember(conversationId) {
        SessionController(
            host = app.turnHost,
            scope = app.appScope,
            store = app.store,
            sink = app.store
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    var showConversationSettings by rememberSaveable(conversationId) { mutableStateOf(false) }
    var settingsModel by remember { mutableStateOf("") }
    var settingsModelOverride by remember { mutableStateOf<String?>(null) }
    var settingsModelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showUsage by rememberSaveable(conversationId) { mutableStateOf(false) }
    var showCompression by rememberSaveable(conversationId) { mutableStateOf(false) }
    var contextStatus by remember { mutableStateOf("保留原始聊天记录") }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(showConversationSettings, showCompression) {
        if (showConversationSettings) {
            val snapshot = app.settings.snapshot()
            val conversation = app.store.getConversation(conversationId)
            settingsModelOverride = conversation?.modelOverride
            settingsModel = conversation?.let { effectiveConfig(it, app.runtimeConfig).config.model } ?: snapshot.model
            settingsModelOptions = snapshot.modelList
            contextStatus = ContextPlanner(app.deps).prepare(conversationId).summaryDisabledReason
        } else { showUsage = false; showCompression = false }
    }

    LaunchedEffect(conversationId) {
        if (conversationId.isNotBlank()) controller.open(conversationId)
    }

    // 离开时收尾：旧的 controller 如果还在跑回合，会继续往同一个会话写库与播出，
    // 而界面只看着新的那个 —— 表现为"消息莫名其妙多出来一条"。
    //
    // 顺手把它登记到 App 上，供"点标题栏打一份屏幕快照"用：排查"逐条累积还是逐条覆盖"
    // 只能靠它（这台机器上 screenrecord 被 SELinux 挡住、uiautomator 又太慢）。
    androidx.compose.runtime.DisposableEffect(controller) {
        app.activeController = controller
        onDispose {
            if (app.activeController === controller) app.activeController = null
            controller.close()
        }
    }

    // 逐字出现的进度。
    //
    // 关键点：**进度只属于当前这条活气泡**，用 `live.key` 当 `LaunchedEffect` 的键。
    // 键一变，进度立刻归零 —— 所以不会出现"新一条先以全文出现一帧、然后动画从头重播"。
    // （引擎那边不再放一份进度副本：见 ChatItem.Live 的注释。）
    //
    // 时长取"整条固定在一小段时间内走完"（短消息 2 字/帧 ≈ 130ms，长消息 3 字/帧），
    // 而且至少两帧：这样"在往外蹦"这个观感对任何长度都稳定存在。
    // 打字节奏本身已经在引擎那边做过了（打字指示 + 按字数排的间隔），
    // 屏幕上这一步只需要"看起来是敲出来的"，拖太久反而让等待变长。
    var typewriterChars by remember { mutableStateOf(0) }
    val liveKey = state.live?.key
    val liveText = state.live?.text.orEmpty()
    LaunchedEffect(liveKey) {
        typewriterChars = 0
        val total = liveText.length
        if (total == 0) return@LaunchedEffect
        val charsPerTick = if (total > 16) 3 else 2
        val ticks = maxOf(2, (total + charsPerTick - 1) / charsPerTick)
        repeat(ticks) { tick ->
            delay(65)
            typewriterChars = minOf(total, (tick + 1) * charsPerTick)
        }
    }

    val listState = rememberLazyListState()

    /**
     * 滚到底。
     *
     * 用 `scrollToItem` 而不是 `animateScrollToItem`：键盘弹起时可视区在缩小，
     * 动画滚动会在半路被新的布局打断，观感是"滑到一半卡住"。这里要的是
     * "立刻贴底"，不需要动画。
     */
    suspend fun scrollToBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last)
    }

    // 新内容进来时自动滚到底。
    //
    // ⚠️ 键里必须包含 `typewriterChars`（**已经显示出来的字数**），不能只跟 `liveText.length`。
    // 后者是整条正文的长度，在一条气泡的整个生命周期里是**常量** —— 所以只跟它的话，
    // 逐字动画期间列表根本不跟随，而活气泡的高度正是在那几十帧里一点点长出来的。
    // 原来的注释写着"包括待处理气泡与活气泡增长"，但代码跟的是 length，
    // 那句话其实没有被实现：长消息在接近底部时，末尾几个字会被顶出视口。
    //
    // 代价说清楚：这里是**无条件**贴底，所以在对方正在打字时用户往上翻历史，
    // 会被拉回来。之所以接受它：逐字动画本身只有 130ms~2s，而"看着消息长出来"
    // 是这个应用的核心观感。真要改成"用户上翻时不打扰"，需要引入
    // `!listState.canScrollForward` 之类的判据，但那对"最后一条高过整屏"的情况会失效。
    LaunchedEffect(state.items.size, state.pendingItems.size, liveKey, typewriterChars) {
        scrollToBottom()
    }

    // ⚠️ **键盘弹起时也要贴底。**
    //
    // 这是用户明确提的一条："希望唤起输入框、输入法键盘弹出的时候聊天可以直接翻到最底下"。
    // 不加这段的话，键盘一弹，可视区矮了一半，而滚动位置还停在原来那个偏移上 ——
    // 于是最新那几条被压在键盘底下，看起来像"卡在中间"。
    //
    // 监听 `WindowInsets.ime` 而不是输入框的焦点：用户可能是点输入框（焦点 + 键盘），
    // 也可能是键盘自己弹出来（比如切回来时系统恢复键盘），后者没有焦点变化事件。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            // 等一帧：`imePadding` 改变布局需要一次 recomposition + layout，
            // 立刻滚会用旧的视口高度算，滚不到真正的底。
            withFrameNanos { }
            scrollToBottom()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // 设置覆盖时隐藏底层聊天的无障碍节点，输入草稿仍留在组合中。
            modifier = if (showConversationSettings) Modifier.clearAndSetSemantics { } else Modifier,
            topBar = {
                ChatHeader(state, onBack,
                    onSettings = { keyboard?.hide(); showConversationSettings = true },
                    titleModifier = if (app.debugDumpEnabled) {
                        Modifier.clickable { app.activeController?.dumpScreenState() }
                    } else Modifier)
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // ⚠️ **`consumeWindowInsets(padding)` 不能省，而且必须在 `imePadding()` 之前。**
                    //
                    // `Scaffold` 给的 `padding` 底部**已经含了导航栏那一段**；而 `imePadding()`
                    // 读到的 `WindowInsets.ime` **也包含导航栏**。两段叠加 = 键盘弹出时底部被算两遍，
                    // 输入框被顶到半空（用户的原话是"输入框做低一点，现在的太高了"）。
                    //
                    // 关键在于 `Modifier.padding()` 只是画内边距，它**不会**声明"这些 inset 我用掉了"；
                    // 只有 `consumeWindowInsets(padding)` 会把它们标记为已消费，后面的
                    // `imePadding()` 于是只补差额。这是 edge-to-edge 里 Scaffold + IME 的标准写法。
                    //
                    // 另外**不要**再叠 `navigationBarsPadding()`：那是上一版的错法 ——
                    // 它压根没有"已消费"的概念，只是又加了一遍。
                    .consumeWindowInsets(padding)
                    .imePadding()
            ) {
                // ---- 消息流 ----
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        // ⚠️ 水平留白走 `contentPadding` 而不是 `Modifier.padding`。
                        //
                        // 打在 LazyColumn 的 modifier 上会把**列表视口整体缩进去**，
                        // 内容于是永远滚不到系统栏后面 —— 那就等于白做了 edge-to-edge。
                        // 放进 contentPadding 里，视口仍是全宽、内容仍能滚到系统栏之下，
                        // 而首尾条目又会自动留出让开系统栏的余地。
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.items.size, key = { index -> itemKey(state.items[index], index) }) { index ->
                            ChatBubble(state.items[index])
                        }
                        // 已发出但还没进模型的消息：必须立刻显示，否则"点了发送没反应"。
                        //
                        // key 前缀 `p-` 是刻意的：待处理条目的 id 是负数占位，和落盘条目
                        // 共用一套 key 空间虽然不会撞，但分成两段更容易看懂。
                        // 待处理条目现在也可能是动作/环境（见 `UserUtterance`），
                        // 所以 key 交给同一个 [itemKey]，不再写死 `ChatItem.Mine`。
                        items(
                            state.pendingItems.size,
                            key = { index -> "p-" + itemKey(state.pendingItems[index], index) }
                        ) { index ->
                            ChatBubble(state.pendingItems[index])
                        }
                        state.live?.let { live ->
                            item(key = "live") {
                                // 逐字渐显：进度由上面的 tick 驱动。
                                // 注意这里**不做**"一次全显示"的短路 —— 那正是这个效果的意义所在。
                                ChatBubble(live.copy(text = live.text.take(typewriterChars)))
                            }
                        }
                    }
                }

                // ⚠️ 这里**刻意没有**"等待/进行中"那一行。
                //
                // 它曾经是 `HintRow`（`pendingHint` 的正文 + 转圈），挂在输入框正上方。
                // 撤掉的理由见标题栏 `actions` 里的注释：那一行每轮都要闪两次，
                // 而它承载的信息大部分属于排查而非用户。进行中状态现在只在标题栏转圈。
                //
                // 注意 `ChatUiState.pendingHint` **仍然存在且继续被赋值** —— 日志、
                // 以及将来任何需要用它的地方都还读得到，这里只是不再把它画出来。

                state.error?.let { error ->
                    ErrorCard(
                        message = error.message,
                        advice = error.advice(),
                        onDismiss = { controller.clearError() },
                        onOpenSettings = onOpenSettings,
                        onOpenLogs = onOpenLogs
                    )
                }

                // ---- 模式切换确认 ----
                state.modeProposal?.let { proposal ->
                    ModeProposalCard(
                        mode = proposal,
                        reason = state.modeReason,
                        onConfirm = { controller.applyMode(proposal) },
                        onDismiss = { controller.dismissModeProposal() }
                    )
                }

                // ---- 输入区 ----
                ChatComposer(
                    sceneMode = state.mode == ChatMode.SCENE,
                    enabled = true,
                    manualMode = state.sendMode == SendMode.MANUAL,
                    pendingSendCount = state.pendingSendCount,
                    onSend = { pieces ->
                        // 组合发送 = **同一批连发 N 条**，每条各自带类型。
                        //
                        // 刻意**不**拼成"一条含多段"的消息：那要求 `message.text` 能表达
                        // 多段各带类型的片段，等于把解析规则往嵌套方向推 ——
                        // 而 `UserUtterance` 的注释里写了，"规则开始需要嵌套/转义"
                        // 正是该迁到"加一列"的信号。连发不需要任何新概念，
                        // 模型侧看到的也就是三行各带标记的话，和它自己一轮分条同形。
                        //
                        // 交给控制器的**一次性**入口（而不是在这里 forEach）：手动模式下
                        // 这几格是同一批，要一起落盘、一起等推送。
                        controller.send(pieces)
                        typewriterChars = 0
                    },
                    onPush = { controller.push() },
                    onSystem = { controller.sendSystem(it) },
                    onCompress = { keyboard?.hide(); showConversationSettings = true; showCompression = true }
                )
            }
        }
        if (showConversationSettings) {
            BackHandler {
                if (showCompression) showCompression = false else
                if (showUsage) showUsage = false else showConversationSettings = false
            }
            if (showCompression) CompressionScreen(conversationId, state.turnPhase != TurnPhase.IDLE,
                isIdle = { controller.state.value.turnPhase == TurnPhase.IDLE }, onBack = { showCompression = false }) else
            if (showUsage) ConversationUsageScreen(conversationId,
                state.turnPhase != TurnPhase.IDLE, onBack = { showUsage = false }) else
            ConversationSettingsScreen(state, settingsModel,
                onBack = { showConversationSettings = false },
                onOpenPersonas = { showConversationSettings = false; onOpenPersonas() },
                onRename = { title ->
                    app.deps.conversations.withConversation(conversationId) {
                    check(controller.state.value.turnPhase == TurnPhase.IDLE) { "请等待本轮结束" }
                    checkNotNull(app.store.getConversation(conversationId)) { "对话已不存在" }
                    app.store.renameConversation(conversationId, title, app.deps.clock.now())
                    }
                    controller.reload()
                },
                onDelete = {
                    app.deps.conversations.withConversation(conversationId) {
                    check(controller.state.value.turnPhase == TurnPhase.IDLE) { "请等待本轮结束" }
                    app.store.deleteConversation(conversationId)
                    }
                    controller.close()
                    onBack()
                }, modelOverride = settingsModelOverride, modelOptions = settingsModelOptions,
                onModelChange = { model ->
                    app.deps.conversations.withConversation(conversationId) {
                        check(controller.state.value.turnPhase == TurnPhase.IDLE) { "请等待本轮结束" }
                        app.store.changeModel(conversationId, model, app.deps.clock.now())
                        settingsModelOverride = model
                        settingsModel = effectiveConfig(
                            checkNotNull(app.store.getConversation(conversationId)), app.runtimeConfig).config.model
                    }
                }, onOpenUsage = { showUsage = true }, contextStatus = contextStatus,
                onOpenCompression = { showCompression = true })
        }
    }
}

/** LazyColumn 的 key：落盘条目用 id，活气泡用固定 key。 */
private fun itemKey(item: ChatItem, index: Int): Any = when (item) {
    is ChatItem.Mine -> "m-${item.id}"
    is ChatItem.MyAction -> "ma-${item.id}"
    is ChatItem.Theirs -> "t-${item.id}"
    is ChatItem.Action -> "a-${item.id}"
    is ChatItem.Narration -> "n-${item.id}"
    is ChatItem.Divider -> "d-${item.id}"
    is ChatItem.Live -> "live-$index"
}

/**
 * 错误卡片。
 *
 * 刻意把 [advice] 与原始 message 分开显示：用户需要的是"我该改什么"，
 * 而 message 是排查用的。混在一起会让人以为要去改代码。
 */
@Composable
private fun ErrorCard(
    message: String,
    advice: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = advice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings) { Text("去设置") }
                // 出错时最需要的其实是"到底发出去什么、回来什么"，所以日志入口就放在这里
                TextButton(onClick = onOpenLogs) { Text("看日志") }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

/**
 * 模式切换确认卡片。
 *
 * 默认策略是"需确认"（见 docs/01-decisions.md D7）：自动切换判错时体验断裂很明显，
 * 而确认一次的成本极低 —— 这个动作本身也很像真人（"要不要不聊微信了？"）。
 *
 * 关键细节：**卡片不阻塞输入**。用户直接发一条消息就视为拒绝，
 * 否则卡片会把输入区挡住，那比判错还糟。
 */
@Composable
private fun ModeProposalCard(
    mode: ChatMode,
    reason: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when (mode) {
                    ChatMode.SCENE -> "看起来你们要见面了"
                    ChatMode.CHAT -> "看起来又回到发消息了"
                },
                style = MaterialTheme.typography.titleSmall
            )
            if (reason.isNotBlank()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "切换后，同一个对话里会换成面对面的样子；直接发消息就当作不切。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(
                        when (mode) {
                            ChatMode.SCENE -> "好，进去"
                            ChatMode.CHAT -> "好，回来"
                        }
                    )
                }
                TextButton(onClick = onDismiss) { Text("不用") }
            }
        }
    }
}

/**
 * 输入区。
 *
 * ## 为什么不用 material3 的 `TextField`
 *
 * 它有一个**压不下去的内置最小高度**：容器自带的上下内边距（各 16dp）加一行字
 * （24dp）就是 56dp。真机实测过：写 `heightIn(min = 44.dp)` 完全没用 ——
 * 量出来仍然是 196px，而屏幕密度是 560dpi，196px 正好 = 56dp。
 * 56dp 的输入框在这个界面里显得笨重，所以换成 `BasicTextField` + 自己画的圆角底：
 * 高度、内边距、占位符全由我们定，顺带也不需要那个不生效的下限了。
 *
 * 现在一行时 40dp（= 一行字 24dp + 上下各 8dp），多行跟着长到 132dp。
 *
 * ## 为什么它看起来这么"抠"
 *
 * 之前输入框离屏幕底边有一段明显空白（真机实测：输入框在 y 2543~2739，屏幕高 2760，
 * 而下面还压着导航栏）。原因是**底部内边距被叠了两层**：`Scaffold` 给的 `padding`
 * 已经含导航栏，再叠一个 `navigationBarsPadding()` 就多出来一段；键盘弹出时
 * `imePadding()` **本身就包含导航栏那一段**，于是被算两遍，输入框被顶到半空。
 * 现在只留 `imePadding()`（见上面的 Column），并把这个 Row 的竖直内边距收到 4dp。
 */
@Composable
private fun ChatComposer(
    /** 面对面模式才显示"说 / 做 / 景"开关。发消息模式下它就是普通聊天框。 */
    sceneMode: Boolean,
    enabled: Boolean,
    /**
     * 手动（两段）模式开关。见 `SendMode`。
     *
     * 它只影响发送键那一态的含义：手动模式下"字空但有代发"时，键变成**蓝色**，
     * 按下去是"推给模型"而不是"发一条新的"。
     */
    manualMode: Boolean = false,
    /** 有几条代发（数据库里的判定，见 `ChatUiState.pendingSendCount`）。 */
    pendingSendCount: Int = 0,
    onSend: (List<Pair<EventKind, String>>) -> Unit,
    /** 手动模式下的第二次按下：把代发的消息推给模型。 */
    onPush: () -> Unit = {},
    onSystem: (String) -> Unit = {},
    onCompress: () -> Unit = {}
) {
    // 三格草稿：面对面里"说 / 做 / 景"是三条**并行**的输入线 —— 你完全可能
    // 先说一句、再做两个动作、最后描一下环境，然后一起发出去。所以切来切去时
    // 各自的内容都留着，不是清空重来。
    //
    // 用三个独立的 `rememberSaveable` 而不是一个 `List`：`autoSaver` 对
    // `List` 的具体实现类有要求，三个字符串是最不容易在真机上炸的写法。
    var sayDraft by rememberSaveable { mutableStateOf("") }
    var actDraft by rememberSaveable { mutableStateOf("") }
    var sceneDraft by rememberSaveable { mutableStateOf("") }

    // 用序号而不是枚举：`rememberSaveable` 存枚举要自己写 Saver，不值当
    var kindIndex by rememberSaveable { mutableStateOf(0) }
    // 发消息模式下开关是藏起来的，一切按"说"处理
    val activeKind = if (sceneMode) kindIndex else 0
    val kind = UserUtterance.kinds[activeKind]
    val drafts = listOf(sayDraft, actDraft, sceneDraft)
    val text = drafts[activeKind]
    var editorValue by remember(activeKind) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (editorValue.text != text) editorValue = TextFieldValue(text, TextRange(text.length))
    }

    fun setDraft(index: Int, value: String) {
        when (index) {
            1 -> actDraft = value
            2 -> sceneDraft = value
            else -> sayDraft = value
        }
    }

    /**
     * 这次按发送键**真的会发出去**哪几格。
     *
     * ⚠️ 聊天模式只发"说"：那时候开关藏起来了，把看不见的格子发出去
     * 等于背着你说话。做/景的草稿**不清掉**，切回面对面还在。
     */
    val sendable = drafts.indices.filter { drafts[it].isNotBlank() && (sceneMode || it == 0) }

    val colors = MaterialTheme.colorScheme

    val systemCommand = activeKind == 0 && SlashCommands.isSystem(text)
    val compressCommand = activeKind == 0 && SlashCommands.isCompress(text)
    Column {
        if (activeKind == 0 && (SlashCommands.suggestSystem(text) || SlashCommands.suggestCompress(text))) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)
            ) {
                if (SlashCommands.suggestSystem(text)) TextButton(onClick = { sayDraft = "/system " }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("/system  系统消息", color = colors.primary)
                        Text("写入系统记录，下一轮生效", style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                }
                if (SlashCommands.suggestCompress(text)) TextButton(onClick = {
                    sayDraft = SlashCommands.removeCompressSuggestion(text)
                    onCompress()
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("/compress  压缩上下文", color = colors.primary)
                        Text("选择早期范围，预览后应用", style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (systemCommand) {
            Text("系统消息 · 保存后下一轮生效", modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 这一组内边距不是审美偏好，是被**屏幕圆角**和**气泡对齐**两条约束夹出来的。
            //
            // 真机实测（`dumpsys display`）：这块屏 1256×2760，四角圆角半径 **140px**
            // （560dpi 下 = 40dp），左下角圆心在 (140, 2620)。输入栏自己的圆角是 20dp
            // （70px），它的左下角圆弧中心落在 `(start+70, 2760-bottom-70)`；
            // 整段圆弧可见的条件是它落在屏幕圆角之内：
            //
            //     (start + 70 - 140)² + (2760 - bottom - 70 - 2620)² ≤ (140 - 70)²
            //
            // 第一版是 `start = 8dp` / `bottom = 4dp`：42² + 56² = 4900 = 70²，
            // **正好卡在边界上**，所以左下角被切掉（右下角同理）。当时的修法是把左右顶到
            // 16dp —— 但那是治标：屏幕上看着像"一块胶囊悬浮在角上"。
            //
            // 真正的解法是**把输入栏抬离圆角**：`bottom` 从 4dp 提到 10dp 之后，
            // 圆弧中心离屏幕圆心 44.8px，余量 25px；于是左右可以收回到 **12dp** ——
            // 与消息气泡的 `padding(horizontal = 12.dp)` 完全对齐。
            // 右边只要 10dp：屏幕右下角那边唯一的东西是图标，它本身已经内缩了。
            //
            // 注意截图看不出来这件事：`screencap` 拍的是整个矩形帧缓冲，
            // 圆角是物理显示区域的属性，只在真机眼睛上看得到。
            .padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
        // ⚠️ 用底部对齐，但**两个控件必须等高**（都是 40dp）——
        // 否则底对齐会让它们的中线错开：原来输入框 40dp、IconButton 48dp，
        // 中线差 4dp，肉眼一眼就看得出来。等高之后底对齐 = 中线对齐，
        // 而多行输入变高时按钮仍然贴着底部（这是聊天软件的惯例）。
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 132.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceVariant)
                // ⚠️ 左侧 14dp 而不是原来的 6dp，**两端对称**。
                //
                // 原来左边只留 6dp，是因为面对面模式下左边紧挨着「说 / 做 / 景」那排开关，
                // 6dp 看着刚好。但发消息模式下那排开关是**藏起来**的，于是正文第一个字
                // 离气泡左边缘只有 6dp —— 而气泡是 20dp 圆角，那个位置正好在圆角的弧上。
                // 后果是把光标点到第一个字前面这件事需要瞄得很准（用户的原话：
                // "贴的太紧想移动光标不好点"）。
                //
                // 14dp 的取值：和右边一致，视觉上正文两侧留白对称；且 14 > 20dp 圆角半径
                // 在竖直中线上占掉的横向距离（约 5.9dp），点到第一字前面时不会落在弧上。
                .padding(start = 14.dp, end = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「说 / 做 / 景」——**三格并排**，而不是一个循环切换的按钮。
                //
                // 为什么不是循环：这一版允许三格各存各的草稿、最后一起发，
                // 那就必须能一眼看出"另外两格里有没有还没发的东西"。
                // 循环按钮做不到这件事，只能靠记性。
                //
                // 颜色就是那套信息：当前格用主题色；另外两格里**有草稿的**比空的亮。
                // 发消息模式（隔空聊天）不显示它 —— 那是"说话"一种语气就够的场景，
                // 摆三个开关只会让人犹豫（见 `UserUtterance`）。
                if (sceneMode) {
                    UserUtterance.kinds.forEachIndexed { index, k ->
                        Text(
                            text = kindLabel(k),
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                index == activeKind -> colors.primary
                                drafts[index].isNotBlank() -> colors.onSurfaceVariant
                                else -> colors.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = enabled) { kindIndex = index }
                                .padding(horizontal = 5.dp, vertical = 5.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 占位符和输入框叠在同一格里：占位符垫在下面，输入框透明底。
                    // 文案随类型变 —— "做"的时候还提示"说点什么…"会让人不知道怎么写。
                    if (text.isEmpty()) {
                        Text(
                            text = kindPlaceholder(kind),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = editorValue,
                        onValueChange = { newValue ->
                            editorValue = newValue
                            setDraft(activeKind, newValue.text)
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        maxLines = 5
                    )
                }
            }
        }
        // ⚠️ 40dp 而不是默认的 48dp：要和输入框**等高**，中线才会齐（见上面 Row 的注释）。
        // `Modifier.size(40.dp)` 会先把约束钉成 40dp，`IconButton` 内部的
        // `minimumInteractiveComponentSize()` 因此扩不开 —— 量出来就是 40dp。
        //
        // 发送键有**三态**，含义由「输入框里有没有字」和「有没有代发」共同决定：
        //
        // | 有字 | 有代发 | 样子 | 按下去 |
        // |---|---|---|---|
        // | 是 | — | 亮图标（"白"） | 把这几格送进时间线 |
        // | 否 | 是 | **蓝色实心**（仅手动模式） | 推给模型 |
        // | 否 | 否 | 灰、不可点 | 没事可干 |
        //
        // 判据里"有字"优先：手上有字就一定是"再发一条"，而不是"推送"——
        // 这样同一颗键的行为才是可预测的（发完手上就空了，自然变成蓝色那一态）。
        //
        // ⚠️ **只改图标染色，不加背景色**。这一页（以及整个 App）的按钮语言就是
        // "同一个图标，靠染色/透明度表达状态"：禁用态由 `IconButton` 自己降透明度，
        // 所以三态分别是 亮（有字）/ 蓝（有代发）/ 灰（没事干），形状始终不变。
        // 加一个实心圆底会让它突然变成一个"主要按钮"，和旁边的输入框、上面的标题栏打架。
        val hasText = if (compressCommand) true else if (systemCommand) SlashCommands.systemBody(text).isNotBlank() else sendable.isNotEmpty()
        val canPush = manualMode && !systemCommand && !hasText && pendingSendCount > 0
        IconButton(
            modifier = Modifier.size(40.dp),
            // 可点性看的是 [sendable]，**不是当前这一格** ——
            // 在"做"里写了一半切到空的"景"，发送键不该变灰。
            onClick = {
                if (compressCommand) {
                    sayDraft = SlashCommands.compressBody(text)
                    onCompress()
                    return@IconButton
                }
                if (systemCommand) {
                    onSystem(SlashCommands.systemBody(text))
                    sayDraft = ""
                    return@IconButton
                }
                if (canPush) {
                    onPush()
                    return@IconButton
                }
                val pieces = sendable
                    .map { UserUtterance.kinds[it] to drafts[it].trim() }
                    .filter { it.second.isNotEmpty() }
                if (pieces.isNotEmpty()) {
                    onSend(pieces)
                    // 发完一起清空：三格是同一次发言的三个部分
                    sayDraft = ""
                    actDraft = ""
                    sceneDraft = ""
                }
            },
            enabled = enabled && (hasText || canPush)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = if (compressCommand) "打开上下文压缩" else if (canPush) {
                    "推送 $pendingSendCount 条待发消息给模型"
                } else {
                    "发送"
                },
                // 有字时保持原样（它本来就是最亮的那个），只有"该推送了"这一态换色
                tint = if (canPush) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current
                }
            )
        }
    }
}
}

/**
 * "你的话攒着还没交出去" —— 三个点依次亮起来。
 *
 * 与转圈分工：这是 [TurnPhase.BATCHING]，转圈是 [TurnPhase.THINKING]。
 * 用户要能从这一个动作里看出"我该继续写"还是"我该等"，所以两者不能共用动画。
 *
 * 节奏 300ms 一跳、四拍一轮（`tick % 4`）—— 第四拍三个点全灭，
 * 这样"一轮"和"下一轮"之间有呼吸，不会看着像常亮。
 */
@Composable
private fun BatchingDots() {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            tick = (tick + 1) % 4
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if (tick > index) 0.9f else 0.25f)
                    )
            )
        }
    }
}

/**
 * "它正在一条一条发出来" —— 三个点上下起伏，像声波。
 *
 * 三个阶段必须**一眼**分得开，否则分了也白分：
 *
 * | 阶段 | 动画 | 读作 |
 * |---|---|---|
 * | [TurnPhase.BATCHING] | 三个点**依次点亮**（从左到右累积） | "你的话还在我手里" |
 * | [TurnPhase.THINKING] | 转圈 | "在想" |
 * | [TurnPhase.SENDING] | 三个点**一起上下起伏**（相位错开） | "正在往外说" |
 *
 * 这里刻意用**竖直方向**的运动：横向扫动会和"依次点亮"看着像同一件事。
 */
@Composable
private fun SendingDots() {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(180)
            tick = (tick + 1) % 8
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        // 留出起伏的余量，别让点被标题栏裁掉
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { index ->
            // 每个点错开相位 —— 一起跳就成了"呼吸"，错开才是"波浪"
            val phase = (tick - index * 2 + 8) % 8
            val liftDp = when (phase) {
                0 -> -3f
                1, 7 -> -1.5f
                else -> 0f
            }
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .graphicsLayer { translationY = liftDp * density }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

/** 输入框左侧那个开关上写的字。 */
private fun kindLabel(kind: EventKind): String = when (kind) {
    EventKind.ACT -> "做"
    EventKind.SCENE -> "景"
    else -> "说"
}

/** 占位文字：让用户知道当前这一态该写什么。 */
private fun kindPlaceholder(kind: EventKind): String = when (kind) {
    EventKind.ACT -> "你在做什么…"
    EventKind.SCENE -> "周围发生了什么…"
    else -> "说点什么…"
}


@Composable
private fun ChatHeader(
    state: app.solochat.play.ChatUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    titleModifier: Modifier = Modifier
) {
    CompactTopBar(
        title = {
            Column(
                // 调试状态下点标题栏 = 把"此刻屏幕上有什么"打进日志。
                // 排查"气泡是逐条累积还是逐条覆盖"只能靠它 ——
                // 这台机器上 screenrecord 被 SELinux 挡住，uiautomator 又太慢。
                //
                // ⚠️ 关闭调试时**整块不挂** `clickable`，而不是 `clickable(enabled = false)`：
                // 后者仍然会留下一个"已禁用"的语义节点与一个 InvisibleToUser 的涟漪层，
                // 读屏经过标题时会念出一句"已禁用"，而这个入口对用户根本不存在。
                modifier = titleModifier
            ) {
                Text(
                    text = state.title.ifBlank { "聊天" },
                    // 标题区随大字体增高，不再用固定高度裁掉第二行。
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        // 人设放最前面：它会因为屏幕窄而被右边截掉，
                        // 而"现在在跟谁说话"是这一行里最要紧的信息。
                        val who = personaLabel(
                            state.personaAvatar,
                            state.personaName,
                            state.personaIsFallback
                        )
                        if (who.isNotBlank()) {
                            append(who)
                            append(" · ")
                        }
                        append(if (state.mode == ChatMode.SCENE) "面对面" else "发消息")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            // 进行中指示固定占位；等待、生成、播出使用各自的动画。
            //
            // - `BATCHING`（你还在打字、话攒着没交出去）→ 三个点依次亮
            // - `THINKING`（已经交给模型了）→ 转圈
            //
            // 为什么必须分开：用户要能看出**"它在等我"**和**"它在想"** ——
            // 这决定了他是该继续写，还是该等。同一个动画表示两件事就等于没信息。
            //
            // ⚠️ 判据是 `turnPhase` 这个**类型**，不是 `pendingHint` 的文案。
            // 第一版写的是 `pendingHint.contains("想")` —— 拿提示语当状态用，
            // 改一个字就崩（那行代码现在已经不存在了）。
            //
            // ⚠️ **外面这层固定宽度的 Box 是必需的，不是在配样式。**
            //
            // 三个阶段指示器的固有宽度本来并不一样：转圈 18dp、两组点阵各 21dp、
            // 而 IDLE 时是 0dp。不固定的话，每一轮开始时 actions 会突然占掉 21dp，
            // 标题的可用宽度跟着变窄 —— 带省略号的标题于是重新排版、抖一下，
            // 看起来像"标题闪了一下"。
            // 固定成能放下最大那个（21dp）再留点余量，动画自己在里面居中。
            //
            // 注意：**不要去改下面两个动画组件内部的尺寸与相位来"配合"这个 Box**。
            // 它们的节奏与位移是专门调过的（BatchingDots 四拍一轮、SendingDots
            // 三点相位错开），动了就不再是"依次点亮 / 上下起伏"那两个读得出来的动作。
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                when (state.turnPhase) {
                    TurnPhase.THINKING -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )

                    // 你的话攒着还没交出去
                    TurnPhase.BATCHING -> BatchingDots()

                    // 模型已经回了，正在一条一条发出来
                    TurnPhase.SENDING -> SendingDots()

                    TurnPhase.IDLE -> Unit
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "会话设置")
            }
        }
    )
}

@Preview(name = "聊天·明", showBackground = true)
@Preview(name = "聊天·大字体", showBackground = true, fontScale = 2f)
@Composable
private fun ChatLightPreview() = ChatLayoutPreview(false)

@Preview(name = "聊天·暗", showBackground = true)
@Composable
private fun ChatDarkPreview() = ChatLayoutPreview(true)

@Composable
private fun ChatLayoutPreview(dark: Boolean) {
    SoloChatTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ChatHeader(app.solochat.play.ChatUiState(title = "一个安静的下午", personaName = "小默"), {}, {})
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                ChatBubble(ChatItem.Mine(1, "今天有一点累。", 0, false))
                ChatBubble(ChatItem.Theirs(2, "那就先坐一会儿。", 0, "小默", false))
            }
            ChatComposer(sceneMode = false, enabled = true, onSend = {})
        }
    }
}
