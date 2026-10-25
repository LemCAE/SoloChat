package app.solochat.ui.usage

import app.solochat.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import app.solochat.ui.ToolTopBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.solochat.core.agent.ContextPlan
import app.solochat.core.model.*
import java.util.Locale
import app.solochat.data.StatisticsSnapshot

private fun money(value: Double) = String.format(Locale.CHINA, "%.8f", value).trimEnd('0').trimEnd('.')
@Composable
fun costLabel(cost: RecordedCost): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val low = cost.low ?: return context.getString(R.string.ui_statistics_cost_unknown)
    val high = cost.high ?: return context.getString(R.string.ui_statistics_cost_unknown)
    return if (low != high) context.getString(R.string.ui_statistics_estimate_range, money(low), money(high))
    else context.getString(R.string.ui_statistics_estimate, money(low))
}
@Composable
fun tokenLabel(amount: TokenAmount): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return when (amount.source) {
        TokenSource.RETURNED -> context.getString(R.string.ui_statistics_returned, amount.value)
        TokenSource.ESTIMATED -> context.getString(R.string.ui_statistics_estimated, amount.value)
        TokenSource.LEGACY -> context.getString(R.string.ui_statistics_legacy, amount.value ?: context.getString(R.string.ui_statistics_unknown))
        TokenSource.UNKNOWN -> context.getString(R.string.ui_statistics_unknown)
    }
}

/** 复用现有统计口径，按调用次数显示模型分布，不以当前价格重算金额。 */
@Composable
fun UsageByModel(snapshot: StatisticsSnapshot) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val models = (snapshot.calls.map { it.model } + snapshot.legacy.map { it.model }).distinct()
        .map { model -> model to StatisticsSnapshot(snapshot.calls.filter { it.model == model }, snapshot.legacy.filter { it.model == model }) }
        .sortedByDescending { it.second.totals.calls }
    if (models.isEmpty()) return
    Text(stringsContext.getString(R.string.ui_statisticscontent_69232344), style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { (model, group) ->
                val totals = group.totals
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(model.ifBlank { stringsContext.getString(R.string.ui_statisticscontent_72caa7cb) }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(stringsContext.getString(R.string.ui_statisticscontent_6937fff9, totals.calls), style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(progress = { totals.calls.toFloat() / snapshot.totals.calls.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth())
                    Text(if (group.calls.none { recordedCost(it).low != null }) stringsContext.getString(R.string.ui_statisticscontent_c9a256ad)
                        else costLabel(RecordedCost(totals.costLow, totals.costHigh)), style = MaterialTheme.typography.bodySmall)
                    if (totals.missingCost > 0) Text(stringsContext.getString(R.string.ui_statisticscontent_4139ba0f, totals.missingCost), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 借鉴指标网格组织；未知、估算和导入来源仍按 SoloChat 的冻结账目口径展示。 */
@Composable
fun StatisticsOverview(title: String, snapshot: StatisticsSnapshot) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val totals = snapshot.totals
    val cached = snapshot.calls.filter { it.cached.source == TokenSource.RETURNED }.mapNotNull { it.cached.value }
    val cacheMeasured = snapshot.calls.filter {
        it.input.source == TokenSource.RETURNED && it.cached.source == TokenSource.RETURNED
    }
    val measuredInput = cacheMeasured.sumOf { (it.input.value ?: 0).toLong() }
    val hitRate = if (measuredInput == 0L) stringsContext.getString(R.string.ui_statisticscontent_53ef29f6) else String.format(Locale.CHINA, "%.1f%%",
        cacheMeasured.sumOf { (it.cached.value ?: 0).toLong() } * 100.0 / measuredInput)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (snapshot.calls.none { recordedCost(it).low != null }) stringsContext.getString(R.string.ui_statisticscontent_66bbfdf8)
                else costLabel(RecordedCost(totals.costLow, totals.costHigh)), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricValue(stringsContext.getString(R.string.ui_statisticscontent_ccec3478), totals.input.toString(), Modifier.weight(1f))
                MetricValue(stringsContext.getString(R.string.ui_statisticscontent_95e8b419), totals.output.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricValue(stringsContext.getString(R.string.ui_statisticscontent_61e8c679), totals.calls.toString(), Modifier.weight(1f))
                MetricValue(stringsContext.getString(R.string.ui_statisticscontent_e0fac2df), if (cached.isEmpty()) stringsContext.getString(R.string.ui_statisticscontent_53ef29f6) else cached.sumOf { it.toLong() }.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricValue(stringsContext.getString(R.string.ui_statisticscontent_920c78b4), hitRate, Modifier.weight(1f))
                MetricValue(stringsContext.getString(R.string.ui_statisticscontent_439b4741), stringsContext.getString(R.string.ui_statisticscontent_6937fff9, cacheMeasured.size), Modifier.weight(1f))
            }
            Text(stringsContext.getString(R.string.ui_statisticscontent_08300f5c, cached.size, snapshot.calls.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (totals.missingInput > 0 || totals.missingOutput > 0) Text(stringsContext.getString(R.string.ui_statisticscontent_94a6d725, totals.missingInput, totals.missingOutput),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringsContext.getString(R.string.ui_statisticscontent_3b721ef7, totals.missingCost, totals.legacyCalls),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val summary = snapshot.calls.count { it.kind == CallKind.SUMMARY }
            val incomplete = snapshot.calls.count { it.outputIncomplete || it.status == CallStatus.STARTED }
            Text(stringsContext.getString(R.string.ui_statisticscontent_e0e392f7, snapshot.calls.count { it.kind == CallKind.CHAT }, summary, incomplete),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetricValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun CurrentContext(plan: ContextPlan?, busy: Boolean) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringsContext.getString(R.string.ui_statisticscontent_87cf4a7e), style = MaterialTheme.typography.titleMedium)
            if (plan == null) Text(stringsContext.getString(R.string.ui_statisticscontent_70314c7c)) else {
                Text(stringsContext.getString(R.string.ui_statisticscontent_f15f3c4d, plan.estimatedTokens))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringsContext.getString(R.string.ui_statisticscontent_562f1bfb) else stringsContext.getString(R.string.ui_statisticscontent_c8daa506)) }
                if (expanded) {
                Text(stringsContext.getString(R.string.ui_statisticscontent_13be13f2, plan.effective.config.model.ifBlank { stringsContext.getString(R.string.ui_statistics_model_unconfigured) }))
                Text(stringsContext.getString(R.string.ui_statisticscontent_4f061b55, plan.history.size, plan.prompt.stablePrefixChars))
                Text(stringsContext.getString(R.string.ui_statisticscontent_6a21b521),
                    style = MaterialTheme.typography.bodySmall)
                if (busy) Text(stringsContext.getString(R.string.ui_statisticscontent_a1453ee4), style = MaterialTheme.typography.bodySmall)
                if (plan.historyLimited) Text(stringsContext.getString(R.string.ui_statisticscontent_6bfde9ef), color = MaterialTheme.colorScheme.error)
                Text(plan.summaryDisabledReason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 明细独立分页并按需组成列表项，不在统计首页铺开。 */
@Composable
fun CallRecordsPage(snapshot: StatisticsSnapshot, onBack: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var detail by remember { mutableStateOf<CallRecord?>(null) }
    Dialog(onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(title = stringsContext.getString(R.string.ui_statisticscontent_551826e7), onBack = onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text(stringsContext.getString(R.string.ui_statisticscontent_074f887d),
                    style = MaterialTheme.typography.bodySmall) }
                items(snapshot.calls.sortedByDescending { it.startedAt }, key = { it.id }) { call ->
                    Card(onClick = { detail = call }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row {
                                Text(call.model, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(callStatus(call), style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(java.util.Date(call.startedAt))} · " +
                                if (call.kind == CallKind.CHAT) stringsContext.getString(R.string.ui_statisticscontent_6a43e963) else stringsContext.getString(R.string.ui_statisticscontent_b4fab8f4), style = MaterialTheme.typography.bodySmall)
                            Text(stringsContext.getString(R.string.ui_statisticscontent_5837fa79, call.input.value ?: stringsContext.getString(R.string.ui_statistics_unknown), call.output.value ?: stringsContext.getString(R.string.ui_statistics_unknown), call.cached.value ?: stringsContext.getString(R.string.ui_statistics_unknown)),
                                style = MaterialTheme.typography.bodySmall)
                            Text(costLabel(recordedCost(call)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (snapshot.legacy.isNotEmpty()) item {
                    Text(stringsContext.getString(R.string.ui_statisticscontent_23cd6a72), style = MaterialTheme.typography.titleSmall)
                }
                items(snapshot.legacy) { row ->
                    Text(stringsContext.getString(R.string.ui_statisticscontent_114e0ae0, row.model, row.promptTokens, row.completionTokens) +
                        if (row.estimated) stringsContext.getString(R.string.ui_statisticscontent_1b1ed35d) else stringsContext.getString(R.string.ui_statisticscontent_eccb4004), style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.calls.isEmpty() && snapshot.legacy.isEmpty()) item { Text(stringsContext.getString(R.string.ui_statisticscontent_979a9ca6)) }
            }
        }
        detail?.let { call -> CallDetail(call, snapshot.callConfigSnapshots[call.id]) { detail = null } }
    }
}

@Composable
private fun callStatus(call: CallRecord): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return when (call.status) {
    CallStatus.STARTED -> context.getString(R.string.ui_statistics_status_running)
    CallStatus.SUCCESS -> context.getString(R.string.ui_statistics_status_success)
    CallStatus.SILENT -> context.getString(R.string.ui_statistics_status_silent)
    CallStatus.FAILED -> context.getString(R.string.ui_statistics_status_failed)
    CallStatus.CANCELED -> context.getString(R.string.ui_statistics_status_canceled)
    CallStatus.INTERRUPTED -> context.getString(R.string.ui_statistics_status_interrupted)
    }
}

@Composable
private fun CallDetail(call: CallRecord, configSnapshot: app.solochat.data.db.CallConfigSnapshotEntity?, onDismiss: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var raw by rememberSaveable(call.id) { mutableStateOf(false) }
    val cost = recordedCost(call)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringsContext.getString(R.string.ui_statisticscontent_6fcd00d6)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${call.model} · ${callStatus(call)}")
            if (configSnapshot == null) {
                Text("调用配置：未记录", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("提供商：${configSnapshot.providerName} · ${configSnapshot.modelDisplayName}",
                    style = MaterialTheme.typography.bodySmall)
                Text("用途：${snapshotUseLabel(configSnapshot.use)} · 思考：${thinkingModeLabel(configSnapshot.thinkingMode)}" +
                    (configSnapshot.thinkingEffort?.let { " · 强度：$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                Text("思考来源：${thinkingSourceLabel(configSnapshot.thinkingSource)} · 选择来源：${selectionSourceLabel(configSnapshot.selectionSource)}",
                    style = MaterialTheme.typography.bodySmall)
                val parameters = configSnapshot.actualThinkingParametersJson
                    .takeUnless { it == "{}" || it == "[]" }.orEmpty().ifBlank { "无" }
                Text("实际参数：$parameters", style = MaterialTheme.typography.bodySmall)
            }
            Text(stringsContext.getString(R.string.ui_statisticscontent_aa6f9326, tokenLabel(call.input), tokenLabel(call.output), tokenLabel(call.cached), tokenLabel(call.reasoning)))
            Text(costLabel(cost))
            Text(cost.note)
            val price = call.price.forInput(call.input)
            if (price.known) {
                val cachedRate = if (price.cachedPriceKnown) price.cachedPerM else price.inputPerM
                Text(stringsContext.getString(R.string.ui_statisticscontent_95b8d386, price.currency, price.inputPerM, price.outputPerM, cachedRate, price.source))
                val input = call.input.value
                val cached = call.cached.value
                val output = call.output.value
                if (input != null && cached != null && output != null) {
                    Text(stringsContext.getString(R.string.ui_statisticscontent_e5d1b9d7, price.currency, input - cached, price.inputPerM, money((input - cached) * price.inputPerM / 1_000_000)) +
                        stringsContext.getString(R.string.ui_statisticscontent_d1b46f6e, cached, cachedRate, money(cached * cachedRate / 1_000_000)) +
                        stringsContext.getString(R.string.ui_statisticscontent_44c47628, output, price.outputPerM, money(output * price.outputPerM / 1_000_000)))
                } else Text(stringsContext.getString(R.string.ui_statisticscontent_13a1fa5f))
            } else Text(stringsContext.getString(R.string.ui_statisticscontent_bc0b7bf5))
            if (call.outputIncomplete) Text(stringsContext.getString(R.string.ui_statisticscontent_b2a1eafc), color = MaterialTheme.colorScheme.error)
            if (call.usageFrames.isNotEmpty()) {
                TextButton(onClick = { raw = !raw }) { Text(if (raw) stringsContext.getString(R.string.ui_statisticscontent_98fecf85) else stringsContext.getString(R.string.ui_statisticscontent_8426a422)) }
                if (raw) call.usageFrames.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else Text(stringsContext.getString(R.string.ui_statisticscontent_a2a07f14), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_statisticscontent_251f8761)) } })
}

private fun snapshotUseLabel(value: String) = when (value) {
    "CHAT" -> "聊天"
    "COMPRESSION" -> "压缩"
    "TITLE" -> "标题"
    else -> value
}

private fun thinkingModeLabel(value: String) = when (value) {
    "AUTO" -> "自动"
    "ON" -> "开启"
    "OFF" -> "关闭"
    else -> value
}

private fun thinkingSourceLabel(value: String) = when (value) {
    "MODEL_PREFERENCE" -> "模型设置"
    "COMPRESSION_DEFAULT" -> "压缩默认"
    else -> value
}

private fun selectionSourceLabel(value: String) = when (value) {
    "CONVERSATION" -> "会话指定"
    "CHAT_PREFERENCE" -> "聊天用途"
    "COMPRESSION_PREFERENCE" -> "压缩用途"
    "TITLE_PREFERENCE" -> "标题用途"
    "GLOBAL_DEFAULT" -> "全局默认"
    else -> value
}
