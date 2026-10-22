package app.solochat.ui.usage

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.solochat.core.agent.ContextPlan
import app.solochat.core.model.*
import java.util.Locale
import app.solochat.data.StatisticsSnapshot

private fun money(value: Double) = String.format(Locale.CHINA, "%.4f", value)
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

/** 保留全局按模型查看的入口，同样只汇总冻结价格与未关联的旧版记录。 */
@Composable
fun TodayByModel(snapshot: StatisticsSnapshot) {
    val models = (snapshot.calls.map { it.model } + snapshot.legacy.map { it.model }).distinct().sorted()
    if (models.isEmpty()) return
    Text("今天按模型", style = MaterialTheme.typography.titleMedium)
    models.forEach { model ->
        val group = StatisticsSnapshot(snapshot.calls.filter { it.model == model }, snapshot.legacy.filter { it.model == model })
        val totals = group.totals
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model, style = MaterialTheme.typography.titleSmall)
                Text("${totals.calls} 次 · 已记录输入 ${totals.input} / 输出 ${totals.output} tokens")
                Text(if (group.calls.none { recordedCost(it).low != null }) "暂无可估算费用"
                    else costLabel(RecordedCost(totals.costLow, totals.costHigh)))
                Text("含本地估算与旧版数值；另有 ${totals.missingCost} 次费用未知。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StatisticsOverview(title: String, snapshot: StatisticsSnapshot) {
    val totals = snapshot.totals
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("${totals.calls} 次调用", style = MaterialTheme.typography.headlineSmall)
            val knownCostCalls = snapshot.calls.count { recordedCost(it).low != null }
            Text(if (knownCostCalls == 0) "暂无可估算费用" else costLabel(RecordedCost(totals.costLow, totals.costHigh)))
            Text("已记录输入 ${totals.input} · 输出 ${totals.output} tokens")
            Text("包含本地估算与旧版数值；缺输入 ${totals.missingInput} 次，缺输出 ${totals.missingOutput} 次。",
                style = MaterialTheme.typography.bodySmall)
            Text("费用为可估算的已知部分；另有 ${totals.missingCost} 次费用未知，其中旧版 ${totals.legacyCalls} 次。",
                style = MaterialTheme.typography.bodySmall)
            val summary = snapshot.calls.count { it.kind == CallKind.SUMMARY }
            val incomplete = snapshot.calls.count { it.outputIncomplete || it.status == CallStatus.STARTED }
            Text("聊天 ${snapshot.calls.count { it.kind == CallKind.CHAT }} 次 · 压缩 $summary 次 · 未完成或输出不完整 $incomplete 次",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CurrentContext(plan: ContextPlan?, busy: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前上下文", style = MaterialTheme.typography.titleMedium)
            if (plan == null) Text("正在读取…") else {
                Text("约 ${plan.estimatedTokens} tokens（本地估算）")
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

@Composable
fun RecentCalls(snapshot: StatisticsSnapshot) {
    Text("最近调用", style = MaterialTheme.typography.titleMedium)
    if (snapshot.calls.isNotEmpty()) Text("缓存包含在输入内，推理包含在输出内，不重复相加。展开调用可查看字段来源与冻结单价。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    snapshot.calls.take(12).forEach { call -> key(call.id) { CallSummary(call) } }
    if (snapshot.legacy.isNotEmpty()) {
        Text("旧版记录 ${snapshot.legacy.size} 次", style = MaterialTheme.typography.titleSmall)
        Text("原 token 数值保留；没有调用时价格快照，历史费用未知，不按当前价格重算。",
            style = MaterialTheme.typography.bodySmall)
        snapshot.legacy.take(8).forEach { row ->
            Text("${row.model} · 输入 ${row.promptTokens} / 输出 ${row.completionTokens} · " +
                if (row.estimated) "旧版本地估算" else "旧版记录", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("本地估算没有精确 tokenizer，费用也不是账单；取消、失败和静默同样保留实际尝试。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 摘要保留异常与未知状态，明细仅在用户展开时展示。 */
@Composable
private fun CallSummary(call: CallRecord) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val cost = recordedCost(call)
    val status = when (call.status) {
        CallStatus.STARTED -> "进行中"
        CallStatus.SUCCESS -> "成功"
        CallStatus.SILENT -> "静默"
        CallStatus.FAILED -> "失败"
        CallStatus.CANCELED -> "取消"
        CallStatus.INTERRUPTED -> "中断"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(call.model, style = MaterialTheme.typography.titleSmall)
            Text("${if (call.kind == CallKind.CHAT) "聊天" else "压缩"} · $status · " +
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(java.util.Date(call.startedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(costLabel(cost), style = MaterialTheme.typography.titleMedium)
            Text("输入：${tokenLabel(call.input)}\n输出：${tokenLabel(call.output)}", style = MaterialTheme.typography.bodyMedium)
            if (call.outputIncomplete) Text("输出不完整，未收到的部分与最终费用无法确定。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起调用明细" else "查看调用明细") }
            if (expanded) {
                HorizontalDivider()
                Text("缓存：${tokenLabel(call.cached)}\n推理：${tokenLabel(call.reasoning)}", style = MaterialTheme.typography.bodyMedium)
                Text(cost.note, style = MaterialTheme.typography.bodySmall)
                Text(if (call.price.known) "调用时单价（${call.price.currency} / 百万 token）：输入 ${call.price.inputPerM}、输出 ${call.price.outputPerM}、缓存 ${if (call.price.cachedPriceKnown) call.price.cachedPerM else call.price.inputPerM}；${call.price.source}"
                    else "调用时未配置可用单价。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
