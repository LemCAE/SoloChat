package app.solochat.ui.chat

import app.solochat.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.InvalidationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import app.solochat.SoloChatApp
import app.solochat.core.model.HistorySearchHit
import app.solochat.core.model.MediaAsset
import app.solochat.core.model.TimelineEntry
import app.solochat.play.ChatProjector
import app.solochat.play.ChatItem
import app.solochat.ui.ChatBubble
import app.solochat.ui.CompactTopBar
import app.solochat.ui.MediaViewerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun HistoryContextScreen(hit: HistorySearchHit, onBack: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val app = LocalContext.current.applicationContext as SoloChatApp
    val scope = rememberCoroutineScope()
    val context = remember(hit.conversationId, hit.key) { HistoryContextState(scope, app.store, hit, stringsContext.getString(R.string.ui_history_load_failed)) }
    val state by context.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var attachmentMap by remember { mutableStateOf<Map<Long, List<MediaAsset>>>(emptyMap()) }
    var stickerMessageIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var viewer by remember { mutableStateOf<MediaAsset?>(null) }
    var mediaActionStatus by remember { mutableStateOf("") }
    val mediaScope = rememberCoroutineScope()
    val saveMedia = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val asset = viewer
        if (uri != null && asset != null) mediaScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val output = app.contentResolver.openOutputStream(uri) ?: error(stringsContext.getString(R.string.media_save_failed))
                FileInputStream(app.media.file(asset)).use { input -> output.use { input.copyTo(it) } }
            } }.onSuccess { mediaActionStatus = stringsContext.getString(R.string.media_saved) }.onFailure { mediaActionStatus = it.message ?: stringsContext.getString(R.string.media_save_failed) }
        }
    }
    var olderAnchor by remember(context) { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(state.entries) {
        val ids = state.entries.filterIsInstance<TimelineEntry.MessageRow>().map { it.message.id }
        val loaded = app.store.mediaAssetsForMessages(ids)
        attachmentMap = loaded.mapValues { (_, rows) -> rows.sortedBy { it.first.ordinal }.map { it.second } }
        stickerMessageIds = app.store.messageStickers(ids).mapTo(mutableSetOf()) { it.messageId }
    }
    val projectedItems = remember(state.entries, attachmentMap) {
        ChatProjector.projectVisible(state.entries).items.map { item ->
            if (item is ChatItem.Mine) item.copy(attachments = attachmentMap[item.id].orEmpty(), isSticker = item.id in stickerMessageIds) else item
        }
    }
    BackHandler(onBack = onBack)
    DisposableEffect(context) { context.locate(); onDispose { context.close() } }
    LaunchedEffect(context) {
        callbackFlow {
            val observer = object : InvalidationTracker.Observer("event", "message", "conversation") {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            app.db.invalidationTracker.addObserver(observer)
            awaitClose { app.db.invalidationTracker.removeObserver(observer) }
        }.conflate().flowOn(Dispatchers.IO).collect { context.onDatabaseChanged() }
    }
    LaunchedEffect(context, state.scrollRequest) {
        if (state.scrollRequest > 0) {
            olderAnchor = null
            val index = state.entries.indexOfFirst { it.key == state.scrollTarget }
            if (index >= 0) { withFrameNanos { }; list.scrollToItem(index + 1) }
        }
    }
    LaunchedEffect(state.loading, state.entries.firstOrNull()?.key) {
        val anchor = olderAnchor
        if (!state.loading && anchor != null) {
            val index = state.entries.indexOfFirst { contextItemKey(it.key) == anchor.first }
            if (index >= 0) { withFrameNanos { }; list.scrollToItem(index + 1, anchor.second) }
            olderAnchor = null
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            CompactTopBar(title = { Column {
                Text(if (state.latest) stringsContext.getString(R.string.ui_historycontextscreen_af0b1022) else stringsContext.getString(R.string.ui_historycontextscreen_8cea8498))
                Text(hit.conversationTitle.ifBlank { stringsContext.getString(R.string.ui_historycontextscreen_6af330d1) }, style = MaterialTheme.typography.labelSmall)
            } }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringsContext.getString(R.string.ui_historycontextscreen_a74e67c8)) }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (state.missing) Text(stringsContext.getString(R.string.ui_historycontextscreen_0056e6d9),
                    Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                if (state.error.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp)) {
                    Text(state.error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = context::retry) { Text(stringsContext.getString(R.string.ui_historycontextscreen_298edb9a)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = context::locate, enabled = !state.loading) { Text(stringsContext.getString(R.string.ui_historycontextscreen_d54f6a40)) }
                    TextButton(onClick = context::latest, enabled = !state.loading) {
                        Text(if (state.hasNew) stringsContext.getString(R.string.ui_historycontextscreen_bfbff775) else stringsContext.getString(R.string.ui_historycontextscreen_e5afc769))
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(state = list, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    item(key = "context-head") {
                        if (state.hasEarlier) TextButton(onClick = {
                            olderAnchor = list.layoutInfo.visibleItemsInfo.firstOrNull {
                                row -> state.entries.any { contextItemKey(it.key) == row.key }
                            }?.let { it.key.toString() to -it.offset }
                            context.earlier()
                        }, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_historycontextscreen_8bdfad97)) }
                        else if (state.entries.isNotEmpty()) Text(stringsContext.getString(R.string.ui_historycontextscreen_d8cf7092), style = MaterialTheme.typography.bodySmall)
                    }
                    items(state.entries.indices.toList(), key = { contextItemKey(state.entries[it].key) }) { index ->
                        val entry = state.entries[index]
                        val highlighted = !state.latest && !state.missing && entry.key == hit.key
                        Column(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(12.dp)).padding(vertical = 6.dp) else Modifier) {
                            if (highlighted) Text(stringsContext.getString(R.string.ui_historycontextscreen_9e02fd16), Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            projectedItems.getOrNull(index)?.let { ChatBubble(it, onAttachmentClick = { viewer = it }) }
                        }
                    }
                    item(key = "context-tail") {
                        if (state.hasLater) TextButton(onClick = context::later, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_historycontextscreen_38d8bf8f)) }
                        else if (state.entries.isNotEmpty()) Text(stringsContext.getString(R.string.ui_historycontextscreen_6ca29125), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringsContext.getString(R.string.ui_historycontextscreen_3fcb1f6b), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mediaActionStatus.isNotBlank()) Text(mediaActionStatus, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    viewer?.let { asset -> MediaViewerDialog(asset, onDismiss = { viewer = null }, onSave = {
        val ext = when (asset.mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        saveMedia.launch("solochat-${asset.sha256.take(12)}.$ext")
    }, onQuote = {
        mediaScope.launch {
            runCatching { app.mediaDrafts.addReference(hit.conversationId, asset.id, app.deps.clock.now()) }.onSuccess {
                mediaActionStatus = stringsContext.getString(R.string.media_quoted_to_conversation, hit.conversationTitle)
                viewer = null
            }.onFailure { mediaActionStatus = it.message ?: stringsContext.getString(R.string.media_quote_failed) }
        }
    }, onCollectUser = {
        mediaScope.launch {
            runCatching {
                app.store.addUserSticker("user-sticker-${java.util.UUID.randomUUID()}", asset.id, app.deps.clock.now())
            }.onSuccess { mediaActionStatus = "已收藏到我的表情" }
                .onFailure { mediaActionStatus = it.message ?: "收藏失败" }
        }
    }) }
}

/** LazyColumn 的标识必须可存入 Android Bundle，领域来源键本身保持纯 Kotlin。 */
private fun contextItemKey(key: app.solochat.core.model.TimelineKey): String = "${key.source.name}:${key.id}"
