package app.solochat.ui.usage

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
fun costLabel(cost: RecordedCost): String {
    val low = cost.low ?: return "费用未知"
    val high = cost.high ?: return "费用未知"
    return if (low != high) "估算费用 ¥${money(low)}～${money(high)}" else "估算费用 ¥${money(low)}"
}
fun tokenLabel(amount: TokenAmount): String = when (amount.source) {
    TokenSource.RETURNED -> "${amount.value}（接口返回）"
    TokenSource.ESTIMATED -> "${amount.value}（本地估算）"
    TokenSource.LEGACY -> "${amount.value ?: "未知"}（旧版记录）"
    TokenSource.UNKNOWN -> "未知"
}

/** 复用现有统计口径，按调用次数显示模型分布，不以当前价格重算金额。 */
@Composable
fun UsageByModel(snapshot: StatisticsSnapshot) {
    val models = (snapshot.calls.map { it.model } + snapshot.legacy.map { it.model }).distinct()
        .map { model -> model to StatisticsSnapshot(snapshot.calls.filter { it.model == model }, snapshot.legacy.filter { it.model == model }) }
        .sortedByDescending { it.second.totals.calls }
    if (models.isEmpty()) return
    Text("按模型 · 调用次数", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { (model, group) ->
                val totals = group.totals
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(model.ifBlank { "未记录模型" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("${totals.calls} 次", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(progress = { totals.calls.toFloat() / snapshot.totals.calls.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth())
                    Text(if (group.calls.none { recordedCost(it).low != null }) "费用未知"
                        else costLabel(RecordedCost(totals.costLow, totals.costHigh)), style = MaterialTheme.typography.bodySmall)
                    if (totals.missingCost > 0) Text("另有 ${totals.missingCost} 次费用未知", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 借鉴指标网格组织；未知、估算和导入来源仍按 SoloChat 的冻结账目口径展示。 */
@Composable
fun StatisticsOverview(title: String, snapshot: StatisticsSnapshot) {
    val totals = snapshot.totals
    val cached = snapshot.calls.filter { it.cached.source == TokenSource.RETURNED }.mapNotNull { it.cached.value }
    val cacheMeasured = snapshot.calls.filter {
        it.input.source == TokenSource.RETURNED && it.cached.source == TokenSource.RETURNED
    }
    val measuredInput = cacheMeasured.sumOf { (it.input.value ?: 0).toLong() }
    val hitRate = if (measuredInput == 0L) "未知" else String.format(Locale.CHINA, "%.1f%%",
        cacheMeasured.sumOf { (it.cached.value ?: 0).toLong() } * 100.0 / measuredInput)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (snapshot.calls.none { recordedCost(it).low != null }) "暂无可估算费用"
                else costLabel(RecordedCost(totals.costLow, totals.costHigh)), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricValue("已记录输入", totals.input.toString(), Modifier.weight(1f))
                MetricValue("已记录输出", totals.output.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricValue("调用次数", totals.calls.toString(), Modifier.weight(1f))
                MetricValue("新调用已知缓存", if (cached.isEmpty()) "未知" else cached.sumOf { it.toLong() }.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricValue("缓存命中率", hitRate, Modifier.weight(1f))
                MetricValue("命中率样本", "${cacheMeasured.size} 次", Modifier.weight(1f))
            }
            Text("命中率仅按接口同时返回输入与缓存的调用计算。token 数含本地估算与旧版记录。缓存为 ${cached.size}/${snapshot.calls.size} 次新调用的已知值，不含旧版缓存，不重复加到输入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (totals.missingInput > 0 || totals.missingOutput > 0) Text("缺输入 ${totals.missingInput} 次 · 缺输出 ${totals.missingOutput} 次",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("费用仅含可估算部分，另有 ${totals.missingCost} 次未知（含旧版 ${totals.legacyCalls} 次）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val summary = snapshot.calls.count { it.kind == CallKind.SUMMARY }
            val incomplete = snapshot.calls.count { it.outputIncomplete || it.status == CallStatus.STARTED }
            Text("聊天 ${snapshot.calls.count { it.kind == CallKind.CHAT }} · 压缩 $summary · 未完成或输出不完整 $incomplete",
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前上下文", style = MaterialTheme.typography.titleMedium)
            if (plan == null) Text("正在读取…") else {
                Text("约 ${plan.estimatedTokens} tokens（本地估算）")
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起上下文详情" else "查看上下文详情") }
                if (expanded) {
                Text("有效模型：${plan.effective.config.model.ifBlank { "尚未配置" }}")
                Text("${plan.history.size} 条模型可见记录 · 稳定前缀 ${plan.prompt.stablePrefixChars} 字符")
                Text("按当前状态模拟下一次普通请求；不含尚未推送的输入，不会发起模型调用。",
                    style = MaterialTheme.typography.bodySmall)
                if (busy) Text("回合进行中，当前预览可能尚未包含正在播出的内容。", style = MaterialTheme.typography.bodySmall)
                if (plan.historyLimited) Text("已设置有限历史窗口；这里只统计最终装配的内容。", color = MaterialTheme.colorScheme.error)
                Text(plan.summaryDisabledReason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 明细独立分页并按需组成列表项，不在统计首页铺开。 */
@Composable
fun CallRecordsPage(snapshot: StatisticsSnapshot, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<CallRecord?>(null) }
    Dialog(onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(title = "调用记录", onBack = onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text("缓存包含在输入内，推理包含在输出内。费用按调用时冻结价格估算。",
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
                                if (call.kind == CallKind.CHAT) "聊天" else "压缩", style = MaterialTheme.typography.bodySmall)
                            Text("输入 ${call.input.value ?: "未知"} · 输出 ${call.output.value ?: "未知"} · 缓存 ${call.cached.value ?: "未知"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(costLabel(recordedCost(call)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (snapshot.legacy.isNotEmpty()) item {
                    Text("旧版记录 · 缺少调用时价格，费用未知", style = MaterialTheme.typography.titleSmall)
                }
                items(snapshot.legacy) { row ->
                    Text("${row.model} · 输入 ${row.promptTokens} / 输出 ${row.completionTokens} · " +
                        if (row.estimated) "旧版本地估算" else "旧版记录", style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.calls.isEmpty() && snapshot.legacy.isEmpty()) item { Text("此范围暂无调用") }
            }
        }
        detail?.let { call -> CallDetail(call) { detail = null } }
    }
}

private fun callStatus(call: CallRecord) = when (call.status) {
    CallStatus.STARTED -> "进行中"
    CallStatus.SUCCESS -> "成功"
    CallStatus.SILENT -> "静默"
    CallStatus.FAILED -> "失败"
    CallStatus.CANCELED -> "取消"
    CallStatus.INTERRUPTED -> "中断"
}

@Composable
private fun CallDetail(call: CallRecord, onDismiss: () -> Unit) {
    var raw by rememberSaveable(call.id) { mutableStateOf(false) }
    val cost = recordedCost(call)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("调用明细") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${call.model} · ${callStatus(call)}")
            Text("输入：${tokenLabel(call.input)}\n输出：${tokenLabel(call.output)}\n缓存：${tokenLabel(call.cached)}\n推理：${tokenLabel(call.reasoning)}")
            Text(costLabel(cost))
            Text(cost.note)
            val price = call.price.forInput(call.input)
            if (price.known) {
                val cachedRate = if (price.cachedPriceKnown) price.cachedPerM else price.inputPerM
                Text("调用时单价（${price.currency} / 百万 token）：输入 ${price.inputPerM}、输出 ${price.outputPerM}、缓存 $cachedRate；${price.source}")
                val input = call.input.value
                val cached = call.cached.value
                val output = call.output.value
                if (input != null && cached != null && output != null) {
                    Text("按记录拆分（${price.currency}）：\n未命中输入 ${input - cached} × ${price.inputPerM} / 百万 = ${money((input - cached) * price.inputPerM / 1_000_000)}\n" +
                        "缓存 $cached × $cachedRate / 百万 = ${money(cached * cachedRate / 1_000_000)}\n" +
                        "输出 $output × ${price.outputPerM} / 百万 = ${money(output * price.outputPerM / 1_000_000)}")
                } else Text("缺少用量字段，无法完整拆分费用。")
            } else Text("调用时未配置可用价格。")
            if (call.outputIncomplete) Text("输出不完整，仅按收到的部分估算。", color = MaterialTheme.colorScheme.error)
            if (call.usageFrames.isNotEmpty()) {
                TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起接口用量" else "查看接口原始用量") }
                if (raw) call.usageFrames.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else Text("此记录未保存接口原始用量。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
