package app.solochat.ui.chat

import app.solochat.R
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.InvalidationTracker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
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
import app.solochat.ui.PersonaAvatar
import app.solochat.core.model.ManualModeCommand
import app.solochat.data.RegistrySelection
import app.solochat.core.model.ModelSelection
import app.solochat.core.model.AvatarRef
import app.solochat.core.model.MediaAsset
import app.solochat.core.model.SubmissionText
import app.solochat.core.model.UserSticker
import app.solochat.core.model.PersonaSticker
import app.solochat.core.model.MAX_SUBMISSION_IMAGES
import java.util.UUID
import app.solochat.ui.MediaThumbnail
import app.solochat.ui.MediaViewerDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
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
 * 进行中状态集中在标题栏；输入区只展示输入类型与手动模式的待推送提示。
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val mediaDraftState by remember(conversationId) { app.mediaDrafts.observe(conversationId) }.collectAsStateWithLifecycle()
    val mediaDraftAssets = mediaDraftState.assets
    val mediaDraftText = mediaDraftState.draft?.text.orEmpty()
    val missingMediaIds = mediaDraftState.missingMediaIds
    val mediaDraftLoaded = mediaDraftState.loaded
    var composerGeneration by rememberSaveable(conversationId) { mutableStateOf(0) }
    var mediaImportStatus by remember(conversationId) { mutableStateOf("") }
    var mediaImportStatusIsError by remember(conversationId) { mutableStateOf(false) }
    var mediaStatusRevision by remember(conversationId) { mutableIntStateOf(0) }
    var viewerAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var userStickerItems by remember(conversationId) { mutableStateOf<List<Pair<UserSticker, MediaAsset?>>>(emptyList()) }
    var personaStickerItems by remember(conversationId) { mutableStateOf<List<Pair<PersonaSticker, MediaAsset?>>>(emptyList()) }
    var showingPersonaStickers by remember(conversationId) { mutableStateOf(false) }
    var pendingStickerPickTarget by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    val mediaScope = rememberCoroutineScope()
    val userMediaSubmissionGate = remember(conversationId) { Mutex() }
    fun showMediaStatus(message: String, isError: Boolean = false, autoDismissMillis: Long? = null) {
        val revision = ++mediaStatusRevision
        mediaImportStatus = message
        mediaImportStatusIsError = isError
        if (autoDismissMillis != null) mediaScope.launch {
            delay(autoDismissMillis)
            if (mediaStatusRevision == revision) {
                mediaImportStatus = ""
                mediaImportStatusIsError = false
            }
        }
    }
    fun clearMediaStatus() {
        mediaStatusRevision++
        mediaImportStatus = ""
        mediaImportStatusIsError = false
    }
    val importSelectedImages: (List<Uri>) -> Unit = { uris ->
        if (uris.isEmpty()) app.appScope.launch { app.mediaDrafts.cancelImport(conversationId) }
        else app.appScope.launch {
            val remaining = 4 - mediaDraftAssets.size
            if (uris.size > remaining) {
                app.mediaDrafts.cancelImport(conversationId)
                withContext(Dispatchers.Main.immediate) { showMediaStatus(stringsContext.getString(R.string.media_limit_feedback), isError = true) }
            } else {
                withContext(Dispatchers.Main.immediate) { showMediaStatus(stringsContext.getString(R.string.media_importing)) }
                try {
                    val imported = uris.map { uri -> withContext(Dispatchers.IO) { app.media.import(uri, app.deps.clock.now()) } }
                    app.mediaDrafts.finishImport(conversationId, imported, app.deps.clock.now())
                    withContext(Dispatchers.Main.immediate) { clearMediaStatus() }
                } catch (error: CancellationException) {
                    app.mediaDrafts.cancelImport(conversationId)
                    throw error
                } catch (error: Exception) {
                    app.mediaDrafts.cancelImport(conversationId)
                    withContext(Dispatchers.Main.immediate) {
                        showMediaStatus(error.message ?: stringsContext.getString(R.string.media_draft_save_failed), isError = true)
                    }
                }
            }
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4), importSelectedImages)
    val pickImageFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), importSelectedImages)
    val pickSticker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pendingStickerPickTarget
        pendingStickerPickTarget = null
        if (uri != null && target == null) {
            showMediaStatus("收藏目标已丢失，图片未保存；请重新打开表情库选择。", isError = true)
        } else if (uri != null && target != null) app.appScope.launch {
            try {
                val asset = withContext(Dispatchers.IO) { app.media.import(uri, app.deps.clock.now()) }
                if (target.startsWith("persona:")) {
                    val personaId = target.removePrefix("persona:").takeIf(String::isNotBlank)
                        ?: error("请先为会话选择人物")
                    app.store.addPersonaSticker("persona-sticker-${UUID.randomUUID()}", personaId, asset.id, app.deps.clock.now())
                    personaStickerItems = app.store.personaStickers(personaId).map { sticker -> sticker to sticker.mediaId?.let { app.store.mediaAsset(it) } }
                } else app.store.addUserSticker("user-sticker-${UUID.randomUUID()}", asset.id, app.deps.clock.now())
                val refreshed = app.store.userStickers().mapNotNull { sticker ->
                    app.store.mediaAsset(sticker.mediaId)?.let { sticker to it }
                }
                withContext(Dispatchers.Main.immediate) {
                    userStickerItems = refreshed
                    showMediaStatus(stringsContext.getString(R.string.p4_sticker_added), autoDismissMillis = 1800)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    showMediaStatus(error.message ?: "表情添加失败", isError = true)
                }
            }
        }
    }
    val saveMedia = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val asset = viewerAsset
        if (uri != null && asset != null) mediaScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error(stringsContext.getString(R.string.media_save_failed))
                    FileInputStream(app.media.file(asset)).use { input -> output.use { input.copyTo(it) } }
                }
                showMediaStatus(stringsContext.getString(R.string.media_saved), autoDismissMillis = 2200)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMediaStatus(error.message ?: stringsContext.getString(R.string.media_save_failed), isError = true)
            }
        }
    }
    LaunchedEffect(conversationId) { app.mediaDrafts.load(conversationId) }

    // 页面和接口按会话复用应用级实例；返回首页、切换人物不会取消正在运行的回合。
    val controller = remember(conversationId) { app.sessions.get(conversationId) }
    val state by controller.state.collectAsStateWithLifecycle()
    val pendingImageCount = state.pendingImageCount
    var confirmBackupResume by remember { mutableStateOf(false) }
    if (confirmBackupResume) {
        AlertDialog(onDismissRequest = { confirmBackupResume = false }, title = { Text(stringsContext.getString(R.string.ui_chatscreen_b85565fa)) },
            text = { Text(stringsContext.getString(R.string.ui_chatscreen_e7589a82, state.pendingSendCount)) },
            confirmButton = { TextButton(onClick = {
                confirmBackupResume = false
                controller.resumeImportedConversation()
            }) { Text(stringsContext.getString(R.string.ui_chatscreen_508fa9f0)) } },
            dismissButton = { TextButton(onClick = { confirmBackupResume = false }) { Text(stringsContext.getString(R.string.ui_chatscreen_8ef75c4e)) } })
    }
    var showConversationSettings by rememberSaveable(conversationId) { mutableStateOf(false) }
    var showSearch by rememberSaveable(conversationId) { mutableStateOf(false) }
    val searchScope = rememberCoroutineScope()
    val historySearch = remember(conversationId) {
        HistorySearchState(searchScope, search = { query, before -> app.store.searchHistory(conversationId, query, before) }, searchFailedText = stringsContext.getString(R.string.ui_history_search_failed))
    }
    var settingsModel by remember { mutableStateOf("") }
    var settingsModelSelection by remember { mutableStateOf(RegistrySelection.inherit()) }
    var settingsModelChoices by remember { mutableStateOf<List<ConversationModelChoice>>(emptyList()) }
    var showUsage by rememberSaveable(conversationId) { mutableStateOf(false) }
    var showCompression by rememberSaveable(conversationId) { mutableStateOf(false) }
    var contextStatus by remember { mutableStateOf(stringsContext.getString(R.string.ui_chatscreen_fab6a8ec)) }
    val keyboard = LocalSoftwareKeyboardController.current
    var attachmentPanelOpen by rememberSaveable(conversationId) { mutableStateOf(false) }
    var stickerPanelOpen by rememberSaveable(conversationId) { mutableStateOf(false) }
    LaunchedEffect(stickerPanelOpen) { if (!stickerPanelOpen) showingPersonaStickers = false }
    LaunchedEffect(app.db, conversationId, state.personaId, stickerPanelOpen) {
        if (stickerPanelOpen) callbackFlow {
            val observer = object : InvalidationTracker.Observer("user_sticker", "persona_sticker", "media_asset") {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            app.db.invalidationTracker.addObserver(observer)
            trySend(Unit)
            awaitClose { app.db.invalidationTracker.removeObserver(observer) }
        }.conflate().flowOn(Dispatchers.IO).collect {
            val (users, personas) = withContext(Dispatchers.IO) {
                val users = app.store.userStickers().map { sticker -> sticker to app.store.mediaAsset(sticker.mediaId) }
                val personas = if (state.personaId.isBlank()) emptyList() else app.store.personaStickers(state.personaId).map { sticker ->
                    sticker to sticker.mediaId?.let { app.store.mediaAsset(it) }
                }
                users to personas
            }
            userStickerItems = users
            personaStickerItems = personas
        }
    }
    var panelKeyboardSwitchRequested by remember(conversationId) { mutableStateOf(false) }
    var lastImeContentHeightPx by remember(conversationId) { mutableIntStateOf(0) }
    var previousImeContentHeightPx by remember(conversationId) { mutableIntStateOf(0) }
    LaunchedEffect(showConversationSettings, showCompression) {
        if (showConversationSettings) {
            app.awaitModelConfigurationReady()
            val document = checkNotNull(app.settings.loadModelRegistryV2())
            val conversation = app.store.getConversation(conversationId)
            val binding = app.store.conversationModelBinding(conversationId)
            settingsModelSelection = binding?.let { row -> RegistrySelection(row.selectionState, row.providerId,
                row.modelId, row.pendingReason) } ?: RegistrySelection.inherit()
            val modelRef = when (binding?.selectionState) {
                "EXPLICIT" -> binding.providerId to binding.modelId
                "NEEDS_CONFIRMATION" -> null
                else -> {
                    val use = document.chatSelection
                    when (use.state) {
                        "EXPLICIT" -> use.providerId to use.modelId
                        else -> document.globalDefault.providerId to document.globalDefault.modelId
                    }
                }
            }
            val selected = document.models.firstOrNull { it.providerId == modelRef?.first && it.id == modelRef.second }
            settingsModel = selected?.requestModelId ?: if (binding?.selectionState == "NEEDS_CONFIRMATION") binding.pendingReason.orEmpty() else ""
            settingsModelChoices = document.models.filter { it.enabled }.mapNotNull { model ->
                val provider = document.providers.firstOrNull { it.id == model.providerId && it.enabled } ?: return@mapNotNull null
                ConversationModelChoice(provider.id, model.id, provider.name, model.displayName, model.requestModelId)
            }
            contextStatus = ContextPlanner(app.deps).prepare(conversationId).summaryDisabledReason
        } else { showUsage = false; showCompression = false }
    }

    LaunchedEffect(conversationId) {
        controller.onPageOpened()
    }

    // 当前页面引用仅供标题调试，退出时不关闭应用级会话。
    androidx.compose.runtime.DisposableEffect(controller) {
        app.activeController = controller
        onDispose {
            if (app.activeController === controller) app.activeController = null
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
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followLatest by rememberSaveable(conversationId) { mutableStateOf(true) }
    var newHistory by remember(conversationId) { mutableStateOf(false) }
    var olderAnchor by remember(conversationId) { mutableStateOf<Pair<Any, Int>?>(null) }

    // 只有手指拖动才改变跟随状态，加载前页/程序贴底不会被误判成用户上翻。
    LaunchedEffect(listState, conversationId) {
        var userScrolling = false
        snapshotFlow { Triple(dragging, listState.isScrollInProgress, listState.canScrollForward) }
            .collect { (drag, scrolling, canForward) ->
                if (drag) { userScrolling = true; followLatest = false; olderAnchor = null }
                if (userScrolling && !scrolling) {
                    followLatest = !canForward
                    if (followLatest) newHistory = false
                    userScrolling = false
                }
            }
    }

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

    // 在最新位置仍逐字跟随；上翻后只提示新内容，稳定条目 key 负责前页插入时的锚点。
    LaunchedEffect(state.items.size, state.pendingItems.size, liveKey, typewriterChars, followLatest) {
        if (followLatest) { withFrameNanos { }; scrollToBottom() }
    }
    val tailKey = state.items.lastOrNull()?.let { itemKey(it, 0) }
    LaunchedEffect(state.loadingHistory, state.items.firstOrNull()) {
        val anchor = olderAnchor
        if (!state.loadingHistory && anchor != null) {
            val index = state.items.indexOfFirst { itemKey(it, 0) == anchor.first }
            if (index >= 0) {
                withFrameNanos { }
                listState.scrollToItem(index + 1, anchor.second)
            }
            olderAnchor = null
        }
    }
    LaunchedEffect(tailKey, liveKey, state.pendingItems.size) {
        if (!followLatest) newHistory = true
    }
    LaunchedEffect(state.latestHistoryRequest) {
        if (state.latestHistoryRequest > 0) {
            followLatest = true
            newHistory = false
            withFrameNanos { }
            scrollToBottom()
        }
    }

    // 在最新位置时键盘弹起也贴底；上翻阅读历史时保持位置。
    //
    // 这是用户明确提的一条："希望唤起输入框、输入法键盘弹出的时候聊天可以直接翻到最底下"。
    // 不加这段的话，键盘一弹，可视区矮了一半，而滚动位置还停在原来那个偏移上 ——
    // 于是最新那几条被压在键盘底下，看起来像"卡在中间"。
    //
    // 监听 `WindowInsets.ime` 而不是输入框的焦点：用户可能是点输入框（焦点 + 键盘），
    // 也可能是键盘自己弹出来（比如切回来时系统恢复键盘），后者没有焦点变化事件。
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && followLatest) {
            // 等一帧：`imePadding` 改变布局需要一次 recomposition + layout，
            // 立刻滚会用旧的视口高度算，滚不到真正的底。
            withFrameNanos { }
            scrollToBottom()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // 设置覆盖时隐藏底层聊天的无障碍节点，输入草稿仍留在组合中。
            modifier = if (showConversationSettings || showSearch) Modifier.clearAndSetSemantics { } else Modifier,
            topBar = {
                ChatHeader(state, onBack,
                onSearch = { attachmentPanelOpen = false; stickerPanelOpen = false; keyboard?.hide(); showSearch = true; historySearch.refresh() },
                    onSettings = { attachmentPanelOpen = false; stickerPanelOpen = false; keyboard?.hide(); showConversationSettings = true },
                    titleModifier = if (app.debugDumpEnabled) {
                        Modifier.clickable { app.activeController?.dumpScreenState() }
                    } else Modifier)
            }
        ) { padding ->
            val scaffoldBottomPaddingPx = with(density) { padding.calculateBottomPadding().roundToPx() }
            val imeContentHeightPx = (imeBottomPx - scaffoldBottomPaddingPx).coerceAtLeast(0)
            val defaultPanelHeightPx = with(density) { 280.dp.roundToPx() }
            val panelTargetHeightPx = lastImeContentHeightPx.takeIf { it > 0 } ?: defaultPanelHeightPx
            // imePadding 消耗 Scaffold 底边后只增加 IME 与系统栏的差值；面板补齐这一高度，输入栏在切换时留在原位。
            val inputPanelOpen = attachmentPanelOpen || stickerPanelOpen
            val attachmentPanelHeightPx = if (inputPanelOpen) {
                (panelTargetHeightPx - imeContentHeightPx).coerceIn(0, panelTargetHeightPx)
            } else 0
            val attachmentPanelHeight = with(density) { attachmentPanelHeightPx.toDp() }

            LaunchedEffect(imeContentHeightPx, inputPanelOpen) {
                if (!inputPanelOpen && imeContentHeightPx > 0) lastImeContentHeightPx = imeContentHeightPx
            }
            LaunchedEffect(imeContentHeightPx) {
                val previous = previousImeContentHeightPx
                if (inputPanelOpen && imeContentHeightPx > previous + with(density) { 8.dp.roundToPx() }) {
                    panelKeyboardSwitchRequested = true
                }
                previousImeContentHeightPx = imeContentHeightPx
            }
            LaunchedEffect(imeContentHeightPx, panelTargetHeightPx, panelKeyboardSwitchRequested, inputPanelOpen) {
                if (inputPanelOpen && panelKeyboardSwitchRequested &&
                    imeContentHeightPx >= panelTargetHeightPx - with(density) { 8.dp.roundToPx() }) {
                    attachmentPanelOpen = false
                    stickerPanelOpen = false
                    panelKeyboardSwitchRequested = false
                }
            }
            BackHandler(enabled = (attachmentPanelOpen || stickerPanelOpen) && imeContentHeightPx == 0 && !showSearch && !showConversationSettings) {
                attachmentPanelOpen = false
                stickerPanelOpen = false
                panelKeyboardSwitchRequested = false
            }
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
                        item(key = "history-head") {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (state.historyError.isNotBlank()) Text(state.historyError, color = MaterialTheme.colorScheme.error)
                                if (state.hasEarlierHistory || state.loadingHistory) {
                                    TextButton(enabled = !state.loadingHistory, onClick = {
                                        followLatest = false
                                        // 顶部按钮自身不是正文锚点，否则插入前页后仍停在按钮处而跳走。
                                        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { row ->
                                            state.items.any { itemKey(it, 0) == row.key }
                                        }
                                        olderAnchor = visible?.let { it.key to -it.offset }
                                        controller.loadEarlierHistory()
                                    }) { Text(if (state.loadingHistory) stringsContext.getString(R.string.ui_chatscreen_c8f76dc5) else stringsContext.getString(R.string.ui_chatscreen_8bdfad97)) }
                                } else if (state.ready && state.items.isNotEmpty()) {
                                    Text(stringsContext.getString(R.string.ui_chatscreen_d8cf7092), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        items(state.items.size, key = { index -> itemKey(state.items[index], index) }) { index ->
                            val item = state.items[index]
                            ChatBubble(item, onAttachmentClick = { viewerAsset = it })
                            if (pendingImageCount > MAX_SUBMISSION_IMAGES &&
                                item is ChatItem.Mine && item.isSticker && !item.sentToModel) {
                                TextButton(onClick = { mediaScope.launch {
                                    runCatching { controller.removeUnpushedStickerMessage(item.id) }
                                        .onFailure { showMediaStatus(it.message ?: "移除待发表情失败", isError = true) }
                                } }) { Text("移除这条待发表情以调整待发批次") }
                            }
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
                            ChatBubble(state.pendingItems[index], onAttachmentClick = { viewerAsset = it })
                        }
                        state.live?.let { live ->
                            item(key = "live") {
                                // 逐字渐显：进度由上面的 tick 驱动。
                                // 注意这里**不做**"一次全显示"的短路 —— 那正是这个效果的意义所在。
                                ChatBubble(live.copy(text = live.text.take(typewriterChars)))
                            }
                        }
                    }
                    if (!followLatest) {
                        TextButton(enabled = !state.loadingHistory,
                            modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
                            onClick = { controller.returnToLatestHistory() }) {
                            Text(if (newHistory) stringsContext.getString(R.string.ui_chatscreen_bfbff775) else stringsContext.getString(R.string.ui_chatscreen_e5afc769))
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
                        onOpenLogs = onOpenLogs,
                        onRetry = if (state.mediaRetryAvailable) ({ controller.retryMedia() }) else null
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
                if (state.backupPaused && state.title.isNotBlank()) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringsContext.getString(R.string.ui_chatscreen_9d755bde), style = MaterialTheme.typography.titleSmall)
                            Text(stringsContext.getString(R.string.ui_chatscreen_aa7bc1b8), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { confirmBackupResume = true }) { Text(stringsContext.getString(R.string.ui_chatscreen_54ada122)) }
                        }
                    }
                }
                if (state.ready && state.personaId.isBlank()) {
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringsContext.getString(R.string.ui_chatscreen_ee71025e))
                    }
                }
                val draftStatus = mediaImportStatus.ifBlank { mediaDraftState.saveError.orEmpty() }
                if (draftStatus.isNotBlank()) Text(draftStatus, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mediaImportStatusIsError || !mediaDraftState.saveError.isNullOrBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                key(conversationId, mediaDraftLoaded, composerGeneration) { ChatComposer(
                    mediaAssets = mediaDraftAssets,
                    missingImageCount = missingMediaIds.size,
                    onClearMissingImages = { mediaScope.launch { app.mediaDrafts.clearMissing(conversationId, app.deps.clock.now()) } },
                    mediaReady = missingMediaIds.isEmpty(),
                    initialText = mediaDraftText,
                    onPickImages = { if (app.mediaDrafts.beginImport(conversationId))
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onToggleAttachmentPanel = {
                        stickerPanelOpen = false
                        if (attachmentPanelOpen) {
                            panelKeyboardSwitchRequested = true
                        } else {
                            if (imeContentHeightPx > 0) lastImeContentHeightPx = imeContentHeightPx
                            previousImeContentHeightPx = imeContentHeightPx
                            panelKeyboardSwitchRequested = false
                            attachmentPanelOpen = true
                            keyboard?.hide()
                        }
                    },
                    onRemoveImage = { asset ->
                        mediaScope.launch { app.mediaDrafts.removeMedia(conversationId, asset.id, app.deps.clock.now()) }
                    },
                    onTextDraftChanged = { text -> if (mediaDraftLoaded) app.mediaDrafts.updateText(conversationId, text, app.deps.clock.now()) },
                    onSubmitMedia = { pieces, mediaIds, completeText ->
                        val captured = try {
                            app.mediaDrafts.captureSubmission(conversationId, mediaDraftState.draft?.version ?: -1,
                                completeText, pieces.map { SubmissionText(it.first.wire, it.second) }, mediaIds, app.deps.clock.now())
                        } catch (error: Exception) {
                            showMediaStatus(error.message ?: stringsContext.getString(R.string.media_draft_save_failed), isError = true)
                            null
                        }
                        if (captured != null) app.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            userMediaSubmissionGate.withLock {
                                var succeeded = false
                                var tracking = false
                                try {
                                    tracking = app.mediaDrafts.persistSubmissionSnapshot(captured, app.deps.clock.now())
                                    if (tracking) succeeded = controller.submitMedia(captured)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    withContext(Dispatchers.Main.immediate) {
                                        showMediaStatus(error.message ?: stringsContext.getString(R.string.media_draft_save_failed), isError = true)
                                    }
                                } finally {
                                    withContext(NonCancellable) {
                                        if (tracking) app.mediaDrafts.finishSubmission(conversationId, captured.draft.submissionId, succeeded, app.deps.clock.now())
                                    }
                                }
                                if (succeeded) withContext(Dispatchers.Main.immediate) {
                                    composerGeneration++
                                    followLatest = true
                                    newHistory = false
                                }
                            }
                        }
                    },
                    sceneMode = state.mode == ChatMode.SCENE,
                    attachmentPanelOpen = attachmentPanelOpen,
                    stickerPanelOpen = stickerPanelOpen,
                    onToggleStickerPanel = {
                        attachmentPanelOpen = false
                        if (stickerPanelOpen) {
                            stickerPanelOpen = false
                            keyboard?.show()
                        } else {
                            if (imeContentHeightPx > 0) lastImeContentHeightPx = imeContentHeightPx
                            previousImeContentHeightPx = imeContentHeightPx
                            panelKeyboardSwitchRequested = false
                            stickerPanelOpen = true
                            keyboard?.hide()
                        }
                    },
                    enabled = state.ready && !state.backupPaused && state.personaId.isNotBlank() && mediaDraftLoaded && !mediaDraftState.importing,
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
                        app.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            userMediaSubmissionGate.withLock {
                                withContext(Dispatchers.Main.immediate) {
                                    controller.submitInput(pieces)
                                    followLatest = true
                                    newHistory = false
                                    typewriterChars = 0
                                }
                            }
                        }
                    },
                    onPush = { followLatest = true; newHistory = false; controller.push() },
                    onSystem = { followLatest = true; newHistory = false; controller.sendSystem(it) },
                    onCompress = { keyboard?.hide(); showConversationSettings = true; showCompression = true }
                ) }
                if ((attachmentPanelOpen || stickerPanelOpen) && attachmentPanelHeight > 0.dp) {
                    if (stickerPanelOpen) UserStickerPanel(
                        height = attachmentPanelHeight,
                        stickers = userStickerItems,
                        personaStickers = personaStickerItems,
                        showingPersona = showingPersonaStickers,
                        onAdd = {
                            pendingStickerPickTarget = if (showingPersonaStickers) "persona:${state.personaId}" else "user"
                            pickSticker.launch("image/*")
                        },
                        onSelectPersona = { showingPersonaStickers = true },
                        onSelectUser = { showingPersonaStickers = false },
                        onRemove = { sticker -> mediaScope.launch {
                            runCatching { app.store.removeUserSticker(sticker.id) }.onSuccess {
                                userStickerItems = app.store.userStickers().map { row -> row to app.store.mediaAsset(row.mediaId) }
                            }.onFailure { showMediaStatus(it.message ?: "表情删除失败", isError = true) }
                        } },
                        onRemovePersona = { sticker -> mediaScope.launch {
                            runCatching { app.store.removePersonaSticker(sticker.id, app.deps.clock.now()) }
                                .onSuccess { personaStickerItems = app.store.personaStickers(state.personaId).map { it to it.mediaId?.let { mediaId -> app.store.mediaAsset(mediaId) } } }
                                .onFailure { showMediaStatus(it.message ?: "人物表情移出失败", isError = true) }
                        } },
                        onEditPersona = { sticker, locks, complete -> mediaScope.launch {
                            runCatching { app.store.editPersonaSticker(sticker, locks) }
                                .onSuccess {
                                    personaStickerItems = app.store.personaStickers(sticker.personaId).map { it to it.mediaId?.let { mediaId -> app.store.mediaAsset(mediaId) } }
                                    withContext(Dispatchers.Main.immediate) { complete(true) }
                                }
                                .onFailure {
                                    withContext(Dispatchers.Main.immediate) {
                                        showMediaStatus(it.message ?: "人物表情修改失败", isError = true)
                                        complete(false)
                                    }
                                }
                        } },
                        onUnlockPersona = { sticker, field, complete -> mediaScope.launch {
                            runCatching { app.store.unlockPersonaStickerField(sticker.id, field) }
                                .onSuccess {
                                    val updated = app.store.personaStickers(sticker.personaId).firstOrNull { it.id == sticker.id }
                                    personaStickerItems = app.store.personaStickers(sticker.personaId).map { it to it.mediaId?.let { mediaId -> app.store.mediaAsset(mediaId) } }
                                    withContext(Dispatchers.Main.immediate) { complete(updated) }
                                }
                                .onFailure {
                                    withContext(Dispatchers.Main.immediate) {
                                        showMediaStatus(it.message ?: "字段解锁失败", isError = true)
                                        complete(null)
                                    }
                                }
                        } },
                        onStickerClick = { sticker, asset ->
                            if (!state.ready || state.backupPaused || state.personaId.isBlank()) {
                                showMediaStatus(stringsContext.getString(R.string.p4_sticker_enqueue_failed), isError = true)
                            } else {
                                val targetConversation = conversationId
                                val targetPersona = state.personaId
                                val capturedAt = app.deps.clock.now()
                                val submissionId = "sticker-${UUID.randomUUID()}"
                                app.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    userMediaSubmissionGate.withLock {
                                        val accepted = try {
                                            withContext(Dispatchers.IO) { check(app.media.file(asset).isFile) { "表情原图已丢失" } }
                                            controller.enqueueSticker(targetConversation, targetPersona, sticker.id, asset.id,
                                                capturedAt = capturedAt, submissionId = submissionId)
                                        } catch (error: CancellationException) { throw error }
                                        catch (error: Exception) {
                                            withContext(Dispatchers.Main.immediate) {
                                                showMediaStatus(error.message ?: stringsContext.getString(R.string.p4_sticker_enqueue_failed), isError = true)
                                            }
                                            false
                                        }
                                        if (accepted) withContext(Dispatchers.Main.immediate) {
                                            followLatest = true
                                            newHistory = false
                                        }
                                    }
                                }
                            }
                        }
                    ) else AttachmentPanel(height = attachmentPanelHeight,
                        onPickPhotos = {
                            attachmentPanelOpen = false
                            panelKeyboardSwitchRequested = false
                            if (app.mediaDrafts.beginImport(conversationId)) {
                                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        },
                        onPickImageFiles = {
                            attachmentPanelOpen = false
                            panelKeyboardSwitchRequested = false
                            if (app.mediaDrafts.beginImport(conversationId)) pickImageFiles.launch(arrayOf("image/*"))
                        })
                }
            }
        }
        if (showSearch) HistorySearchScreen(historySearch, onBack = { keyboard?.hide(); showSearch = false })
        viewerAsset?.let { asset ->
            MediaViewerDialog(asset, onDismiss = { viewerAsset = null },
                onSave = {
                    val ext = when (asset.mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                    saveMedia.launch("solochat-${asset.sha256.take(12)}.$ext")
                },
                onQuote = {
                    if (mediaDraftAssets.size >= 4) showMediaStatus(stringsContext.getString(R.string.media_quote_limit), isError = true)
                    else app.appScope.launch {
                        try {
                            app.mediaDrafts.addReference(conversationId, asset.id, app.deps.clock.now())
                            withContext(Dispatchers.Main.immediate) {
                                val screenStillActive = mediaScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true
                                if (screenStillActive && viewerAsset?.id == asset.id) {
                                    showMediaStatus(stringsContext.getString(R.string.media_quoted_to_draft), autoDismissMillis = 2200)
                                    viewerAsset = null
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                val screenStillActive = mediaScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true
                                if (screenStillActive && viewerAsset?.id == asset.id) {
                                    showMediaStatus(error.message ?: stringsContext.getString(R.string.media_quote_failed), isError = true)
                                }
                            }
                        }
                    }
                })
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
                onRename = { title ->
                    app.deps.conversations.withConversation(conversationId) {
                    check(controller.state.value.turnPhase == TurnPhase.IDLE) { stringsContext.getString(R.string.ui_chatscreen_0cfdca2f) }
                    checkNotNull(app.store.getConversation(conversationId)) { stringsContext.getString(R.string.ui_chatscreen_53842620) }
                    app.store.renameConversation(conversationId, title, app.deps.clock.now())
                    }
                    controller.reload()
                },
                onDelete = {
                    check(controller.state.value.turnPhase == TurnPhase.IDLE) { stringsContext.getString(R.string.ui_chatscreen_0cfdca2f) }
                    app.deleteConversation(conversationId)
                    onBack()
                }, modelSelection = settingsModelSelection, modelChoices = settingsModelChoices,
                onModelBindingChange = { selection ->
                    check(controller.state.value.turnPhase == TurnPhase.IDLE) { stringsContext.getString(R.string.ui_chatscreen_0cfdca2f) }
                    app.saveConversationModelBinding(conversationId, selection)
                    settingsModelSelection = selection
                    val selected = settingsModelChoices.firstOrNull { it.providerId == selection.providerId && it.modelId == selection.modelId }
                    if (selected != null) settingsModel = selected.requestModelId
                    else if (selection.state == "INHERIT") settingsModel = app.runtimeConfig.model
                    controller.reload()
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
    is ChatItem.Narration -> "n-${item.source.name}-${item.id}"
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
    onOpenLogs: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
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
                if (onRetry != null) TextButton(onClick = onRetry) { Text("重试图片消息") }
                TextButton(onClick = onOpenSettings) { Text(stringsContext.getString(R.string.ui_chatscreen_341c1503)) }
                // 出错时最需要的其实是"到底发出去什么、回来什么"，所以日志入口就放在这里
                TextButton(onClick = onOpenLogs) { Text(stringsContext.getString(R.string.ui_chatscreen_9bfa7dd0)) }
                TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_chatscreen_b26ab9b3)) }
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when (mode) {
                    ChatMode.SCENE -> stringsContext.getString(R.string.ui_chatscreen_63d55768)
                    ChatMode.CHAT -> stringsContext.getString(R.string.ui_chatscreen_e767e765)
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
                text = stringsContext.getString(R.string.ui_chatscreen_723a653c),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) {
                    Text(
                        when (mode) {
                            ChatMode.SCENE -> stringsContext.getString(R.string.ui_chatscreen_c9914ac0)
                            ChatMode.CHAT -> stringsContext.getString(R.string.ui_chatscreen_73cba4dd)
                        }
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_chatscreen_acc38142)) }
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
    attachmentPanelOpen: Boolean = false,
    stickerPanelOpen: Boolean = false,
    onToggleStickerPanel: () -> Unit = {},
    enabled: Boolean,
    /**
     * 手动（两段）模式开关。见 `SendMode`。
     *
     * 它只影响发送键那一态的含义：手动模式下"字空但有代发"时，键变成**蓝色**，
     * 按下去是"推给模型"而不是"发一条新的"。
     */
    manualMode: Boolean = false,
    mediaAssets: List<MediaAsset> = emptyList(),
    missingImageCount: Int = 0,
    onClearMissingImages: () -> Unit = {},
    mediaReady: Boolean = true,
    initialText: List<SubmissionText> = emptyList(),
    onPickImages: () -> Unit = {},
    onToggleAttachmentPanel: () -> Unit = {},
    onRemoveImage: (MediaAsset) -> Unit = {},
    onTextDraftChanged: (List<SubmissionText>) -> Unit = {},
    onSubmitMedia: (List<Pair<EventKind, String>>, List<String>, List<SubmissionText>) -> Unit = { _, _, _ -> },
    /** 有几条代发（数据库里的判定，见 `ChatUiState.pendingSendCount`）。 */
    pendingSendCount: Int = 0,
    onSend: (List<Pair<EventKind, String>>) -> Unit,
    /** 手动模式下的第二次按下：把代发的消息推给模型。 */
    onPush: () -> Unit = {},
    onSystem: (String) -> Unit = {},
    onCompress: () -> Unit = {}
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // 三格草稿：面对面里"说 / 做 / 景"是三条**并行**的输入线 —— 你完全可能
    // 先说一句、再做两个动作、最后描一下环境，然后一起发出去。所以切来切去时
    // 各自的内容都留着，不是清空重来。
    //
    // 用三个独立的 `rememberSaveable` 而不是一个 `List`：`autoSaver` 对
    // `List` 的具体实现类有要求，三个字符串是最不容易在真机上炸的写法。
    var sayDraft by rememberSaveable { mutableStateOf(initialText.firstOrNull { it.kind == "say" }?.text.orEmpty()) }
    var actDraft by rememberSaveable { mutableStateOf(initialText.firstOrNull { it.kind == "act" }?.text.orEmpty()) }
    var sceneDraft by rememberSaveable { mutableStateOf(initialText.firstOrNull { it.kind == "scene" }?.text.orEmpty()) }
    LaunchedEffect(sayDraft, actDraft, sceneDraft) {
        onTextDraftChanged(listOf(
            sayDraft.takeIf(String::isNotBlank)?.let { SubmissionText("say", it) },
            actDraft.takeIf(String::isNotBlank)?.let { SubmissionText("act", it) },
            sceneDraft.takeIf(String::isNotBlank)?.let { SubmissionText("scene", it) }
        ).filterNotNull())
    }

    // 用序号而不是枚举：`rememberSaveable` 存枚举要自己写 Saver，不值当
    var kindIndex by rememberSaveable { mutableStateOf(0) }
    // 发消息模式下开关是藏起来的，一切按“说”处理。
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
     * ⚠️ 聊天模式只发stringsContext.getString(R.string.ui_chat_input_say)：那时候开关藏起来了，把看不见的格子发出去
     * 等于背着你说话。做/景的草稿**不清掉**，切回面对面还在。
     */
    val sendable = drafts.indices.filter { drafts[it].isNotBlank() && (sceneMode || it == 0) }

    val colors = MaterialTheme.colorScheme

    val modeCommand = if (activeKind == 0 && mediaAssets.isEmpty()) ManualModeCommand.parse(text) else null
    val systemCommand = activeKind == 0 && mediaAssets.isEmpty() && SlashCommands.isSystem(text)
    val compressCommand = activeKind == 0 && mediaAssets.isEmpty() && SlashCommands.isCompress(text)
    Column {
        if (mediaAssets.isNotEmpty() || missingImageCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            mediaAssets.forEach { asset ->
                Box {
                    MediaThumbnail(asset, Modifier.size(56.dp))
                    TextButton(onClick = { onRemoveImage(asset) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                        contentPadding = PaddingValues(0.dp)) { Text("×") }
                }
            }
            if (missingImageCount > 0) {
                Text(stringsContext.getString(R.string.media_missing_count, missingImageCount), style = MaterialTheme.typography.labelSmall, color = colors.error)
                TextButton(onClick = onClearMissingImages) { Text(stringsContext.getString(R.string.media_remove)) }
            }
            TextButton(enabled = enabled && mediaAssets.size < 4, onClick = onPickImages) { Text(stringsContext.getString(R.string.media_add_more)) }
        }
        if (activeKind == 0 && (SlashCommands.suggestSystem(text) || SlashCommands.suggestCompress(text) || SlashCommands.suggestMode(text))) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)
            ) {
                if (SlashCommands.suggestMode(text)) Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { sayDraft = "/scene" }) { Text(stringsContext.getString(R.string.ui_chatscreen_290aabbe)) }
                    TextButton(onClick = { sayDraft = "/chat" }) { Text(stringsContext.getString(R.string.ui_chatscreen_2b8587cd)) }
                }
                if (SlashCommands.suggestSystem(text)) TextButton(onClick = { sayDraft = "/system " }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringsContext.getString(R.string.ui_chatscreen_c0b14d85), color = colors.primary)
                        Text(stringsContext.getString(R.string.ui_chatscreen_ca8047c0), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                }
                if (SlashCommands.suggestCompress(text)) TextButton(onClick = {
                    sayDraft = SlashCommands.removeCompressSuggestion(text)
                    onCompress()
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringsContext.getString(R.string.ui_chatscreen_b04af749), color = colors.primary)
                        Text(stringsContext.getString(R.string.ui_chatscreen_d122158a), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (systemCommand || modeCommand != null) {
            Text(if (modeCommand != null) stringsContext.getString(R.string.ui_chatscreen_5fce7521) else stringsContext.getString(R.string.ui_chatscreen_a920f4f3), modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
        if (manualMode && enabled && pendingSendCount > 0) Text(
            if (pendingSendCount > 0) {
                if (sendable.isEmpty()) stringsContext.getString(R.string.ui_chatscreen_96beb185, pendingSendCount)
                else stringsContext.getString(R.string.ui_chatscreen_409a7ea0, pendingSendCount)
            } else stringsContext.getString(R.string.ui_chatscreen_606dbb89),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                if (sceneMode) {
                    // 恢复输入框内的小切换，三格草稿独立保留；底色标记选中，点表示另有草稿。
                    UserUtterance.kinds.forEachIndexed { index, inputKind ->
                        Text(kindLabel(inputKind) + if (drafts[index].isNotBlank()) "·" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (activeKind == index) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (activeKind == index) colors.primaryContainer else colors.surfaceVariant)
                                .selectable(selected = activeKind == index, enabled = enabled, role = Role.Tab,
                                    onClick = { kindIndex = index })
                                .padding(horizontal = 7.dp, vertical = 10.dp))
                    }
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 占位符和输入框叠在同一格里：占位符垫在下面，输入框透明底。
                    // 文案随类型变 —— “做”的时候还提示“说点什么”会让人不知道怎么写。
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(inputFocusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        maxLines = 5
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(
                modifier = Modifier.size(40.dp).semantics {
                    contentDescription = stringsContext.getString(R.string.p4_sticker_button)
                },
                enabled = enabled,
                onClick = onToggleStickerPanel
            ) {
                val iconTint = if (stickerPanelOpen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(22.dp)) {
                    val strokeWidth = 1.6.dp.toPx()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(iconTint, size.minDimension / 2f - strokeWidth / 2f, center,
                        style = Stroke(width = strokeWidth))
                    val eyeOffset = 3.2.dp.toPx()
                    val eyeY = center.y - 2.7.dp.toPx()
                    val eyeRadius = 0.8.dp.toPx()
                    drawCircle(iconTint, eyeRadius, Offset(center.x - eyeOffset, eyeY))
                    drawCircle(iconTint, eyeRadius, Offset(center.x + eyeOffset, eyeY))
                    val smile = Path().apply {
                        moveTo(center.x - 4.4.dp.toPx(), center.y + 1.4.dp.toPx())
                        cubicTo(center.x - 2.4.dp.toPx(), center.y + 4.4.dp.toPx(),
                            center.x + 2.4.dp.toPx(), center.y + 4.4.dp.toPx(),
                            center.x + 4.4.dp.toPx(), center.y + 1.4.dp.toPx())
                    }
                    drawPath(smile, iconTint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
                }
            }
            Box {
                IconButton(
                    modifier = Modifier.size(40.dp).semantics {
                        contentDescription = stringsContext.getString(R.string.media_add_description)
                    },
                    onClick = {
                        if (attachmentPanelOpen) {
                            onToggleAttachmentPanel()
                            inputFocusRequester.requestFocus()
                            keyboardController?.show()
                        } else onToggleAttachmentPanel()
                    },
                    enabled = enabled && mediaAssets.size < 4
                ) {
                    val iconTint = LocalContentColor.current
                    Canvas(Modifier.size(22.dp)) {
                        val strokeWidth = 1.7.dp.toPx()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            color = iconTint,
                            radius = size.minDimension / 2f - strokeWidth / 2f,
                            center = center,
                            style = Stroke(width = strokeWidth)
                        )
                        val arm = 4.2.dp.toPx()
                        drawLine(iconTint, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y),
                            strokeWidth, cap = StrokeCap.Round)
                        drawLine(iconTint, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm),
                            strokeWidth, cap = StrokeCap.Round)
                    }
                }
            }
            // 三个按钮仍各保留 40dp 点击区，只收掉它们之间的额外空隙。
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
            val hasText = mediaAssets.isNotEmpty() || missingImageCount > 0 || if (modeCommand != null || compressCommand) true else if (systemCommand) SlashCommands.systemBody(text).isNotBlank() else sendable.isNotEmpty()
            val canPush = manualMode && !systemCommand && !hasText && pendingSendCount > 0
            IconButton(
            modifier = Modifier.size(40.dp),
            // 可点性看的是 [sendable]，**不是当前这一格** ——
            // 在“做”里写了一半，再切到空的“景”，发送键不该变灰。
            onClick = {
                if (compressCommand) {
                    sayDraft = SlashCommands.compressBody(text)
                    onCompress()
                    return@IconButton
                }
                if (modeCommand != null) {
                    onSend(listOf(EventKind.SAY to text))
                    sayDraft = ""
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
                if (mediaAssets.isNotEmpty()) {
                    val completeText = drafts.indices.mapNotNull { index ->
                        drafts[index].takeIf(String::isNotBlank)?.let { SubmissionText(UserUtterance.kinds[index].wire, it) }
                    }
                    onSubmitMedia(pieces, mediaAssets.map { it.id }, completeText)
                } else if (pieces.isNotEmpty()) {
                    onSend(pieces)
                    // 发完一起清空：三格是同一次发言的三个部分
                    sayDraft = ""
                    actDraft = ""
                    sceneDraft = ""
                }
            },
            enabled = enabled && mediaReady && (hasText || canPush)
            ) {
                Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = if (compressCommand) stringsContext.getString(R.string.ui_chatscreen_556973cb) else if (canPush) {
                    stringsContext.getString(R.string.ui_chatscreen_5fa16826, pendingSendCount)
                } else {
                    stringsContext.getString(R.string.ui_chatscreen_5eb79878)
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

/** 面对面输入分段名称；草稿仍按原事件类型独立保存。 */
@Composable
private fun kindLabel(kind: EventKind): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return when (kind) {
        EventKind.ACT -> context.getString(R.string.ui_chat_input_act)
        EventKind.SCENE -> context.getString(R.string.ui_chat_input_scene)
        else -> context.getString(R.string.ui_chat_input_say)
    }
}

/** 占位文字：让用户知道当前这一态该写什么。 */
@Composable
private fun kindPlaceholder(kind: EventKind): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return when (kind) {
        EventKind.ACT -> context.getString(R.string.ui_chat_input_placeholder_act)
        EventKind.SCENE -> context.getString(R.string.ui_chat_input_placeholder_scene)
        else -> context.getString(R.string.ui_chat_input_placeholder_say)
    }
}


@Composable
private fun ChatHeader(
    state: app.solochat.play.ChatUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    titleModifier: Modifier = Modifier
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val details = buildList {
        if (state.mode == ChatMode.SCENE) add(stringsContext.getString(R.string.ui_chatscreen_5bf828cd))
        if (state.title.isNotBlank() && state.title != state.personaName && state.title != stringsContext.getString(R.string.ui_chatscreen_fb03968b)) add(state.title)
        if (state.personaIsFallback) add(stringsContext.getString(R.string.ui_chatscreen_1d229ecc))
    }.joinToString(" · ")
    CompactTopBar(
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (AvatarRef.isImage(state.personaAvatar)) PersonaAvatar(state.personaAvatar, state.personaName,
                    Modifier.padding(end = 6.dp).size(24.dp))
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
                        text = personaLabel(state.personaAvatar, state.personaName, false, stringsContext.getString(R.string.ui_common_persona_default)).ifBlank { state.title.ifBlank { stringsContext.getString(R.string.ui_chatscreen_6a43e963) } },
                        // 标题区随大字体增高，不再用固定高度裁掉第二行。
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (details.isNotBlank()) Text(
                        text = details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringsContext.getString(R.string.ui_chatscreen_072b258d))
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
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringsContext.getString(R.string.ui_chatscreen_deb020ec))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringsContext.getString(R.string.ui_chatscreen_ace70d1f))
            }
        }
    )
}

@Composable
private fun UserStickerPanel(
    height: Dp,
    stickers: List<Pair<UserSticker, MediaAsset?>>,
    personaStickers: List<Pair<PersonaSticker, MediaAsset?>>,
    showingPersona: Boolean,
    onAdd: () -> Unit,
    onSelectPersona: () -> Unit,
    onSelectUser: () -> Unit,
    onRemove: (UserSticker) -> Unit,
    onRemovePersona: (PersonaSticker) -> Unit,
    onEditPersona: (PersonaSticker, Set<String>, (Boolean) -> Unit) -> Unit,
    onUnlockPersona: (PersonaSticker, String, (PersonaSticker?) -> Unit) -> Unit,
    onStickerClick: (UserSticker, MediaAsset) -> Unit
) {
    val stringsContext = LocalContext.current
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PersonaSticker?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var editContexts by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showingPersona) "人物表情" else stringsContext.getString(R.string.p4_sticker_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onSelectUser) { Text("我的") }
            TextButton(onClick = onSelectPersona) { Text("人物") }
            TextButton(onClick = { managing = !managing }) { Text(if (managing) "完成" else "管理") }
            TextButton(onClick = onAdd) { Text(stringsContext.getString(R.string.p4_sticker_add)) }
        }
        val empty = if (showingPersona) personaStickers.isEmpty() else stickers.isEmpty()
        if (empty) {
            Text(stringsContext.getString(R.string.p4_sticker_empty), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showingPersona) items(personaStickers.filter { it.first.status == app.solochat.core.model.PersonaStickerStatus.ACTIVE }, key = { it.first.id }) { (sticker, asset) ->
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).clickable {
                        if (!managing) { editing = sticker; editName = sticker.name; editDescription = sticker.description; editTags = sticker.tags.joinToString(", "); editContexts = sticker.usageContexts.joinToString(", ") }
                    }) {
                        if (asset != null) MediaThumbnail(asset, Modifier.fillMaxSize()) else Text("图片缺失", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                        if (managing) TextButton(onClick = { onRemovePersona(sticker) }, Modifier.align(Alignment.TopEnd)) { Text("×") }
                    }
                } else items(stickers, key = { it.first.id }) { (sticker, asset) ->
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).clickable { if (!managing && asset != null) onStickerClick(sticker, asset) }) {
                        if (asset != null) MediaThumbnail(asset, Modifier.fillMaxSize()) else Text("图片缺失", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        if (managing) TextButton(onClick = { onRemove(sticker) }, Modifier.align(Alignment.TopEnd)) { Text("×") }
                    }
                }
            }
        }
    }
    editing?.let { sticker ->
        val updated = sticker.copy(name = editName, description = editDescription,
            tags = editTags.split(',').map(String::trim).filter(String::isNotBlank),
            usageContexts = editContexts.split(',').map(String::trim).filter(String::isNotBlank), updatedAt = System.currentTimeMillis())
        val changed = buildSet {
            if (updated.name != sticker.name) add("name")
            if (updated.description != sticker.description) add("description")
            if (updated.tags != sticker.tags) add("tags")
            if (updated.usageContexts != sticker.usageContexts) add("usageContexts")
        }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("编辑人物表情") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(editName, { editName = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(editDescription, { editDescription = it }, label = { Text("描述") })
                OutlinedTextField(editTags, { editTags = it }, label = { Text("标签，用逗号分隔") })
                OutlinedTextField(editContexts, { editContexts = it }, label = { Text("使用场景，用逗号分隔") })
                listOf("name" to sticker.nameLocked, "description" to sticker.descriptionLocked, "tags" to sticker.tagsLocked, "usageContexts" to sticker.usageContextsLocked).filter { it.second }.forEach { (field, _) ->
                    val label = when (field) { "name" -> "名称"; "description" -> "描述"; "tags" -> "标签"; else -> "使用场景" }
                    TextButton(onClick = { onUnlockPersona(sticker, field) { updated -> if (updated != null) editing = updated } }) { Text("解锁$label") }
                }
            } }, confirmButton = { TextButton(onClick = { onEditPersona(updated, changed) { saved -> if (saved) editing = null } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun UserStickerPanelPreview() {
    SoloChatTheme {
        UserStickerPanel(260.dp, emptyList(), emptyList(), false, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, { _, _ -> })
    }
}

@Composable
private fun AttachmentPanel(height: Dp, onPickPhotos: () -> Unit, onPickImageFiles: () -> Unit) {
    val stringsContext = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AttachmentPanelAction(Icons.Outlined.Image, stringsContext.getString(R.string.media_pick_photos), onPickPhotos)
            AttachmentPanelAction(Icons.Outlined.FolderOpen,
                stringsContext.getString(R.string.media_pick_image_files), onPickImageFiles)
        }
    }
}

@Composable
private fun AttachmentPanelAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(92.dp).height(88.dp),
        contentPadding = PaddingValues(4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    SoloChatTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ChatHeader(app.solochat.play.ChatUiState(title = stringsContext.getString(R.string.ui_chatscreen_4d173817), personaName = stringsContext.getString(R.string.ui_chatscreen_90aa16e5)), {}, {}, {})
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                ChatBubble(ChatItem.Mine(1, stringsContext.getString(R.string.ui_chatscreen_ae14bdce), 0, false))
                ChatBubble(ChatItem.Theirs(2, stringsContext.getString(R.string.ui_chatscreen_b1f399eb), 0, stringsContext.getString(R.string.ui_chatscreen_90aa16e5), false))
            }
            ChatComposer(sceneMode = false, enabled = true, onSend = {})
        }
    }
}
