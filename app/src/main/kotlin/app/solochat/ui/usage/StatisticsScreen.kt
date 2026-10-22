package app.solochat.ui.usage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import app.solochat.SoloChatApp
import app.solochat.data.StatisticsSnapshot
import app.solochat.core.agent.ContextPlanner
import app.solochat.core.agent.ContextPlan
import app.solochat.ui.ToolTopBar
import kotlinx.coroutines.launch
import java.util.Calendar

/** 全局与会话详情共用明细、聚合与费用算法；查看不会消费 API。 */
@Composable
fun UsageScreen(onBack: () -> Unit) = StatisticsScreen(null, false, onBack)

@Composable
fun ConversationUsageScreen(conversationId: String, busy: Boolean, onBack: () -> Unit) =
    StatisticsScreen(conversationId, busy, onBack)

@Composable
private fun StatisticsScreen(conversationId: String?, busy: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SoloChatApp
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<StatisticsSnapshot?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var records by rememberSaveable { mutableStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<ContextPlan?>(null) }
    var error by remember { mutableStateOf("") }
    var clearDialog by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    // 只读取当前范围；切换范围会取消旧读取，避免较慢的结果覆盖新选择。
    LaunchedEffect(conversationId, busy, range, refresh) {
        selected = null
        error = ""
        try {
            val start = Calendar.getInstance().apply {
                timeInMillis = app.deps.clock.now()
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            when (range) {
                1 -> start.add(Calendar.DAY_OF_MONTH, -6)
                2 -> start.set(Calendar.DAY_OF_MONTH, 1)
            }
            selected = if (conversationId != null || range == 3) app.store.statistics(conversationId)
                else app.store.statistics(from = start.timeInMillis, to = end)
            if (conversationId != null) plan = ContextPlanner(app.deps).prepare(conversationId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "统计读取失败，请刷新重试" }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { if (!working) clearDialog = false },
        title = { Text("清空全部用量记录？") }, text = { Text("将删除旧版用量和新调用记录，包括冻结单价；聊天记录会保留。") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            if (working) return@TextButton
            working = true
            scope.launch {
                try { app.store.clearUsage(); clearDialog = false; refresh++ }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "清空失败，请重试" }
                finally { working = false }
            }
        }) { Text("确认清空", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !working, onClick = { clearDialog = false }) { Text("取消") } })
    Scaffold(topBar = { ToolTopBar(title = if (conversationId == null) "用量统计" else "会话统计",
        onBack = onBack, backEnabled = !working,
        actions = {
            TextButton(enabled = !working, onClick = { refresh++ }) { Text("刷新") }
            if (conversationId == null) Box {
                IconButton(enabled = !working, onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("清空用量记录") }, onClick = { more = false; clearDialog = true })
                }
            }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (conversationId != null) CurrentContext(plan, busy)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("今天", "近7天", "本月", "累计").forEachIndexed { index, label ->
                    FilterChip(selected = range == index, onClick = { range = index }, label = { Text(label) })
                }
            }
            selected?.let { snapshot ->
                StatisticsOverview(if (conversationId != null) "会话本机累计" else listOf("本机今天", "本机近7天", "本机本月", "本机累计")[range], snapshot)
                OutlinedButton(onClick = { records = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text("最近调用与明细 · ${snapshot.totals.calls} 次")
                }
                UsageByModel(snapshot)
                if (snapshot.importedCalls.isNotEmpty() || snapshot.importedLegacy.isNotEmpty()) {
                    val history = StatisticsSnapshot(snapshot.importedCalls, snapshot.importedLegacy)
                    StatisticsOverview("导入历史 · 当前范围", history)
                    TextButton(onClick = { records = 2 }) { Text("查看导入调用 · ${history.totals.calls} 次") }
                    Text("导入历史单独展示，不加入本机消费。", style = MaterialTheme.typography.bodySmall)
                }
            } ?: Text(if (error.isEmpty()) "正在读取…" else "暂无统计")
        }
    }
    if (records != 0) selected?.let { snapshot ->
        CallRecordsPage(if (records == 1) snapshot else StatisticsSnapshot(snapshot.importedCalls, snapshot.importedLegacy),
            onBack = { records = 0 })
    }
}
