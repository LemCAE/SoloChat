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
import app.solochat.SoloChatApp
import app.solochat.core.model.HistorySearchHit
import app.solochat.play.ChatProjector
import app.solochat.ui.ChatBubble
import app.solochat.ui.CompactTopBar

@Composable
fun HistoryContextScreen(hit: HistorySearchHit, onBack: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val app = LocalContext.current.applicationContext as SoloChatApp
    val scope = rememberCoroutineScope()
    val context = remember(hit.conversationId, hit.key) { HistoryContextState(scope, app.store, hit, stringsContext.getString(R.string.ui_history_load_failed)) }
    val state by context.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var olderAnchor by remember(context) { mutableStateOf<Pair<String, Int>?>(null) }
    val projectedItems = remember(state.entries) { ChatProjector.projectVisible(state.entries).items }
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
                            projectedItems.getOrNull(index)?.let { ChatBubble(it) }
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
            }
        }
    }
}

/** LazyColumn 的标识必须可存入 Android Bundle，领域来源键本身保持纯 Kotlin。 */
private fun contextItemKey(key: app.solochat.core.model.TimelineKey): String = "${key.source.name}:${key.id}"
