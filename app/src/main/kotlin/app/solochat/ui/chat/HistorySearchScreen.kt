package app.solochat.ui.chat

import app.solochat.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.solochat.ui.CompactTopBar
import app.solochat.core.model.HistorySearchHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistorySearchScreen(search: HistorySearchState, onBack: () -> Unit, global: Boolean = false) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val state by search.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val date = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var selected by remember { mutableStateOf<HistorySearchHit?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = selected == null, onBack = onBack)
    LaunchedEffect(state.query) { list.scrollToItem(0) }
    Box(Modifier.fillMaxSize()) {
        Surface(if (selected != null) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                CompactTopBar(title = { Text(if (global) stringsContext.getString(R.string.ui_historysearchscreen_28277a82) else stringsContext.getString(R.string.ui_historysearchscreen_deb020ec)) }, navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (global) stringsContext.getString(R.string.ui_historysearchscreen_9cbc2c23) else stringsContext.getString(R.string.ui_historysearchscreen_fda2983d)) }
                }, actions = {
                    TextButton(onClick = search::refresh, enabled = state.query.isNotBlank() && !state.loading) { Text(stringsContext.getString(R.string.ui_historysearchscreen_ac3919f9)) }
                })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    OutlinedTextField(value = state.query, onValueChange = search::setQuery,
                        label = { Text(if (global) stringsContext.getString(R.string.ui_historysearchscreen_b652249c) else stringsContext.getString(R.string.ui_historysearchscreen_61cef872)) }, singleLine = true,
                        trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { search.setQuery("") }) { Text(stringsContext.getString(R.string.ui_historysearchscreen_46d2d3ae)) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                    Text(stringsContext.getString(R.string.ui_historysearchscreen_0baa50de), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LazyColumn(state = list, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.hits.isNotEmpty()) item { Text(stringsContext.getString(R.string.ui_historysearchscreen_326f61cd, state.hits.size), style = MaterialTheme.typography.labelMedium) }
                        items(state.hits, key = { "${it.conversationId}:${it.key.source}:${it.key.id}" }) { hit ->
                            OutlinedCard(onClick = { keyboard?.hide(); selected = hit }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (global) Text(
                                        stringsContext.getString(R.string.ui_historysearchscreen_e37af565, hit.personaName.ifBlank { stringsContext.getString(R.string.ui_history_pending_persona) }, hit.conversationTitle.ifBlank { stringsContext.getString(R.string.ui_history_unnamed_conversation) }) +
                                            if (hit.archived) stringsContext.getString(R.string.ui_historysearchscreen_86466ea4) else "",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("${hit.label} · ${date.format(Date(hit.position.at))}",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(searchExcerpt(hit.text, state.query.trim(), MaterialTheme.colorScheme.secondaryContainer),
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        item {
                            when {
                                state.loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                                state.error.isNotEmpty() -> Column {
                                    Text(state.error, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = search::retry) { Text(stringsContext.getString(R.string.ui_historysearchscreen_298edb9a)) }
                                }
                                state.query.isBlank() -> Text(if (global) stringsContext.getString(R.string.ui_historysearchscreen_1d7e1e42) else stringsContext.getString(R.string.ui_historysearchscreen_b9b0efaa))
                                state.hits.isEmpty() -> Text(stringsContext.getString(R.string.ui_historysearchscreen_a716aa0b))
                                state.hasMore -> TextButton(onClick = search::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_historysearchscreen_0bed2de3)) }
                                else -> Text(stringsContext.getString(R.string.ui_historysearchscreen_5c0e0560), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        selected?.let { hit -> HistoryContextScreen(hit, onBack = { selected = null }) }
    }
}

/** 从首次命中附近截取，避免长消息的命中部分被开头挤出。 */
private fun searchExcerpt(text: String, term: String, highlight: androidx.compose.ui.graphics.Color): AnnotatedString {
    val match = if (term.isEmpty()) 0 else text.indexOf(term).coerceAtLeast(0)
    var start = (match - 40).coerceAtLeast(0)
    if (start > 0 && text[start].isLowSurrogate()) start--
    var end = minOf(text.length, maxOf(start + 240, match + term.length))
    if (end < text.length && text[end].isLowSurrogate()) end++
    return buildAnnotatedString {
        if (start > 0) append("…")
        val offset = length
        val excerpt = text.substring(start, end)
        append(excerpt)
        if (end < text.length) append("…")
        if (term.isNotEmpty()) {
            var index = excerpt.indexOf(term)
            while (index >= 0) {
                addStyle(SpanStyle(background = highlight), offset + index, offset + index + term.length)
                index = excerpt.indexOf(term, index + term.length)
            }
        }
    }
}
