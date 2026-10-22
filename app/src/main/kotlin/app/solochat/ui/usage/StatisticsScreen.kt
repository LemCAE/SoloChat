package app.solochat.ui.usage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    var all by remember { mutableStateOf<StatisticsSnapshot?>(null) }
    var today by remember { mutableStateOf<StatisticsSnapshot?>(null) }
    var month by remember { mutableStateOf<StatisticsSnapshot?>(null) }
    var plan by remember { mutableStateOf<ContextPlan?>(null) }
    var error by remember { mutableStateOf("") }
    var clearDialog by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    suspend fun reload() {
        error = ""
        try {
            all = app.store.statistics(conversationId)
            if (conversationId != null) plan = ContextPlanner(app.deps).prepare(conversationId) else {
                val start = Calendar.getInstance().apply { timeInMillis = app.deps.clock.now(); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                today = app.store.statistics(from = start.timeInMillis, to = end)
                start.set(Calendar.DAY_OF_MONTH, 1)
                month = app.store.statistics(from = start.timeInMillis, to = end)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "统计读取失败，请刷新重试" }
    }
    LaunchedEffect(conversationId, busy) { reload() }
    if (clearDialog) AlertDialog(onDismissRequest = { if (!working) clearDialog = false },
        title = { Text("清空全部用量记录？") }, text = { Text("将删除旧版用量和新调用记录，包括冻结单价；聊天记录会保留。") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            if (working) return@TextButton
            working = true
            scope.launch {
                try { app.store.clearUsage(); clearDialog = false; reload() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "清空失败，请重试" }
                finally { working = false }
            }
        }) { Text("确认清空", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !working, onClick = { clearDialog = false }) { Text("取消") } })
    Scaffold(topBar = { ToolTopBar(title = if (conversationId == null) "用量统计" else "会话统计",
        onBack = onBack, backEnabled = !working,
        actions = {
            TextButton(enabled = !working, onClick = { scope.launch { reload() } }) { Text("刷新") }
            if (conversationId == null) TextButton(enabled = !working, onClick = { clearDialog = true }) { Text("清空") }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (conversationId != null) CurrentContext(plan, busy) else {
                today?.let { StatisticsOverview("今天", it) }
                month?.let { StatisticsOverview("本月", it) }
                today?.let { TodayByModel(it) }
            }
            all?.let {
                StatisticsOverview(if (conversationId == null) "本机累计" else "会话本机累计", it)
                RecentCalls(it)
                if (it.importedCalls.isNotEmpty() || it.importedLegacy.isNotEmpty()) {
                    Text("导入历史单独展示，不加入本机累计；重复导入的副本不代表再次消费。",
                        style = MaterialTheme.typography.bodySmall)
                    val history = StatisticsSnapshot(it.importedCalls, it.importedLegacy)
                    StatisticsOverview("导入历史", history)
                    RecentCalls(history)
                }
            }
                ?: Text("正在读取…")
        }
    }
}
