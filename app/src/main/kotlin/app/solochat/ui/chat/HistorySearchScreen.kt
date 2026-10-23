package app.solochat.ui.chat

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.solochat.ui.CompactTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistorySearchScreen(search: HistorySearchState, onBack: () -> Unit, global: Boolean = false) {
    val state by search.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val date = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    BackHandler(onBack = onBack)
    LaunchedEffect(state.query) { list.scrollToItem(0) }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            CompactTopBar(title = { Text(if (global) "全局历史搜索" else "搜索聊天记录") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (global) "返回会话列表" else "返回聊天") }
            }, actions = {
                TextButton(onClick = search::refresh, enabled = state.query.isNotBlank() && !state.loading) { Text("刷新") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                OutlinedTextField(value = state.query, onValueChange = search::setQuery,
                    label = { Text(if (global) "在全部会话中搜索" else "在当前会话中搜索") }, singleLine = true,
                    trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { search.setQuery("") }) { Text("清空") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                Text("按原文匹配，区分大小写 · 最近记录在前", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LazyColumn(state = list, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.hits.isNotEmpty()) item { Text("已显示 ${state.hits.size} 条", style = MaterialTheme.typography.labelMedium) }
                    items(state.hits, key = { "${it.conversationId}:${it.key.source}:${it.key.id}" }) { hit ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (global) Text(
                                    "${hit.personaName.ifBlank { "待确认人物" }} · ${hit.conversationTitle.ifBlank { "未命名对话" }}" +
                                        if (hit.archived) " · 已归档" else "",
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
                                TextButton(onClick = search::retry) { Text("重试") }
                            }
                            state.query.isBlank() -> Text(if (global) "输入关键词，搜索全部人物与会话的聊天记录（含归档）" else "输入关键词，搜索此会话的全部聊天记录")
                            state.hits.isEmpty() -> Text("没有找到匹配的聊天记录")
                            state.hasMore -> TextButton(onClick = search::loadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多结果") }
                            else -> Text("已显示全部结果", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
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
