package app.solochat.ui.usage

import app.solochat.R
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
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
        catch (_: Exception) { error = stringsContext.getString(R.string.ui_statisticsscreen_811a7c7c) }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { if (!working) clearDialog = false },
        title = { Text(stringsContext.getString(R.string.ui_statisticsscreen_95ebc6b5)) }, text = { Text(stringsContext.getString(R.string.ui_statisticsscreen_42c15310)) },
        confirmButton = { TextButton(enabled = !working, onClick = {
            if (working) return@TextButton
            working = true
            scope.launch {
                try { app.store.clearUsage(); clearDialog = false; refresh++ }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = stringsContext.getString(R.string.ui_statisticsscreen_3465dcbd) }
                finally { working = false }
            }
        }) { Text(stringsContext.getString(R.string.ui_statisticsscreen_ee075581), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !working, onClick = { clearDialog = false }) { Text(stringsContext.getString(R.string.ui_statisticsscreen_65a6ca9f)) } })
    Scaffold(topBar = { ToolTopBar(title = if (conversationId == null) stringsContext.getString(R.string.ui_statisticsscreen_ec0a7354) else stringsContext.getString(R.string.ui_statisticsscreen_fee519eb),
        onBack = onBack, backEnabled = !working,
        actions = {
            TextButton(enabled = !working, onClick = { refresh++ }) { Text(stringsContext.getString(R.string.ui_statisticsscreen_ac3919f9)) }
            if (conversationId == null) Box {
                IconButton(enabled = !working, onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringsContext.getString(R.string.ui_statisticsscreen_efbb4354)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_statisticsscreen_92632371)) }, onClick = { more = false; clearDialog = true })
                }
            }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (conversationId != null) CurrentContext(plan, busy)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(stringsContext.getString(R.string.ui_statisticsscreen_05b0621f), stringsContext.getString(R.string.ui_statisticsscreen_1ee46205), stringsContext.getString(R.string.ui_statisticsscreen_7b684050), stringsContext.getString(R.string.ui_statisticsscreen_44b9e3d9)).forEachIndexed { index, label ->
                    FilterChip(selected = range == index, onClick = { range = index }, label = { Text(label) })
                }
            }
            selected?.let { snapshot ->
                StatisticsOverview(if (conversationId != null) stringsContext.getString(R.string.ui_statisticsscreen_c18de79f) else listOf(stringsContext.getString(R.string.ui_statisticsscreen_42b306fe), stringsContext.getString(R.string.ui_statisticsscreen_55d0ccdb), stringsContext.getString(R.string.ui_statisticsscreen_204483d9), stringsContext.getString(R.string.ui_statisticsscreen_bced3bb8))[range], snapshot)
                OutlinedButton(onClick = { records = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringsContext.getString(R.string.ui_statisticsscreen_c2367efa, snapshot.totals.calls))
                }
                UsageByModel(snapshot)
                if (snapshot.importedCalls.isNotEmpty() || snapshot.importedLegacy.isNotEmpty()) {
                    val history = StatisticsSnapshot(snapshot.importedCalls, snapshot.importedLegacy)
                    StatisticsOverview(stringsContext.getString(R.string.ui_statisticsscreen_ff776256), history)
                    TextButton(onClick = { records = 2 }) { Text(stringsContext.getString(R.string.ui_statisticsscreen_3b34ed81, history.totals.calls)) }
                    Text(stringsContext.getString(R.string.ui_statisticsscreen_6f2a2006), style = MaterialTheme.typography.bodySmall)
                }
            } ?: Text(if (error.isEmpty()) stringsContext.getString(R.string.ui_statisticsscreen_70314c7c) else stringsContext.getString(R.string.ui_statisticsscreen_d0819688))
        }
    }
    if (records != 0) selected?.let { snapshot ->
        CallRecordsPage(if (records == 1) snapshot else StatisticsSnapshot(snapshot.importedCalls, snapshot.importedLegacy),
            onBack = { records = 0 })
    }
}
