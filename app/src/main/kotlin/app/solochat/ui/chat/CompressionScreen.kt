package app.solochat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.solochat.ui.stopTextFlingPropagation
import app.solochat.SoloChatApp
import app.solochat.core.agent.*
import app.solochat.core.model.*
import app.solochat.ui.CompactTopBar
import app.solochat.ui.usage.costLabel
import app.solochat.ui.usage.tokenLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 选择→生成→编辑/预览→应用。整个页面仅新增摘要版本，聊天时间轴不折叠。 */
@Composable
fun CompressionScreen(conversationId: String, busy: Boolean, isIdle: () -> Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SoloChatApp
    val scope = rememberCoroutineScope()
    val coordinator = app.compression
    val service = coordinator.service
    val generation by coordinator.state(conversationId).collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<CompressionOptions?>(null) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var method by remember { mutableStateOf(CompressionMethod.ORIGINAL) }
    var budgetText by remember { mutableStateOf("8000") }
    var targetText by remember { mutableStateOf("600") }
    var preview by remember { mutableStateOf<CompressionPreview?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var after by remember { mutableStateOf<Int?>(null) }
    var before by remember { mutableStateOf<Int?>(null) }
    var viewing by remember { mutableStateOf<ContextRevision?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val draft = generation.draft
    var texts by remember(draft?.id) { mutableStateOf(draft?.segments?.map { it.text }.orEmpty()) }
    BackHandler(enabled = working) { }

    fun action(block: suspend () -> Unit) {
        if (working) return
        working = true; error = ""; feedback = ""
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "操作失败，请重试" }
            finally { working = false }
        }
    }
    suspend fun reload() {
        val fresh = service.options(conversationId)
        options = fresh
        if (fresh.cuts.none { it.messageId == selected }) selected = fresh.cuts.lastOrNull()?.messageId
    }
    LaunchedEffect(conversationId, busy, generation.generating) {
        try { reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "读取上下文失败" }
    }
    LaunchedEffect(draft?.id) {
        if (draft != null) {
            try {
                before = ContextPlanner(app.deps).prepare(conversationId).estimatedTokens
                after = service.afterTokens(draft)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { after = null; error = e.message ?: "草稿预览失败" }
        } else { before = null; after = null }
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text("恢复原始上下文？") },
        text = { Text("下一轮重新读取原文；摘要版本和聊天记录均保留。") },
        confirmButton = { TextButton(enabled = !working && !busy && !generation.generating, onClick = {
            confirmRestore = false
            action {
                val id = checkNotNull(app.store.getConversation(conversationId)).activeContextRevisionId
                service.restore(conversationId, id, isIdle)
                preview = null; reload(); feedback = "已恢复原始上下文"
            }
        }) { Text("恢复原文") } }, dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } })

    Scaffold(topBar = { CompactTopBar(title = { Text("上下文压缩") },
        navigationIcon = { IconButton(enabled = !working, onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        actions = { TextButton(enabled = !working, onClick = { action { preview = null; reload() } }) { Text("刷新") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("只压缩模型读到的早期历史；聊天窗口和全部原消息保持原样。", style = MaterialTheme.typography.bodyMedium)
            options?.let { data ->
                Text(data.status, color = MaterialTheme.colorScheme.primary)
                if (data.active != null) {
                    TextButton(enabled = !busy && !working && !generation.generating, onClick = { confirmRestore = true }) { Text("恢复原文") }
                }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (generation.error.isNotEmpty()) Text(generation.error, color = MaterialTheme.colorScheme.error)
            if (feedback.isNotEmpty()) Text(feedback, color = MaterialTheme.colorScheme.primary)
            if (busy) Text("本轮结束后可生成、应用或恢复；已在生成的摘要可以继续完成。", style = MaterialTheme.typography.bodySmall)
            if (generation.generating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("已完成 ${generation.completed} 次调用，最多 ${generation.maximum} 次；完成后得到草稿。")
                Text("可返回聊天继续交流。取消不会切换摘要版本。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { coordinator.cancel(conversationId) }) { Text("取消生成") }
            }
            val editable = !working && !generation.generating
            Text("1 · 选择早期范围", style = MaterialTheme.typography.titleMedium)
            val cuts = options?.cuts.orEmpty()
            if (cuts.isEmpty()) Text("还没有安全切点。至少保留最近一段完整交流及最新待回应输入；未出口或无法确认的回复不能压缩。")
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cuts.forEach { cut ->
                    TextButton(enabled = editable, onClick = { selected = cut.messageId; preview = null }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${if (selected == cut.messageId) "✓ " else ""}压缩前 ${cut.count} 条 · 保留 ${cut.retained} 条")
                            Text("截至 ${time(cut.at)} · ${cut.preview}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("重复压缩方式", style = MaterialTheme.typography.titleSmall)
            CompressionMethod.entries.forEach { value ->
                TextButton(enabled = editable, onClick = { method = value; preview = null }) {
                    Text("${if (method == value) "✓ " else ""}${methodLabel(value)}")
                }
            }
            Text(when (method) {
                CompressionMethod.ORIGINAL -> "从原文重新整理；不会自动继承旧摘要中的手工修改。"
                CompressionMethod.INCREMENTAL -> "旧摘要（含手工修改）与新增原文一起整理，可能累积信息遗失；须选择更晚的切点。"
                CompressionMethod.SEGMENTED -> "旧段原样保留，只生成相邻新增部分；总长度会逐渐增加，须选择更晚的切点。"
            }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(budgetText, onValueChange = { budgetText = it; preview = null }, enabled = editable,
                    label = { Text("输入预算（估算 token）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(targetText, onValueChange = { targetText = it; preview = null }, enabled = editable,
                    label = { Text("摘要目标字数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("预算为本地估算，不是模型窗口上限；太长时按完整交流分块。", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = editable && selected != null, onClick = { action {
                preview = service.preview(conversationId, checkNotNull(selected), method,
                    budgetText.toIntOrNull() ?: error("请输入有效输入预算"), targetText.toIntOrNull() ?: error("请输入有效目标字数"))
            } }) { Text("预览生成计划") }
            preview?.let { plan ->
                Text("2 · 确认生成", style = MaterialTheme.typography.titleMedium)
                Text("模型：${plan.config.model}\n覆盖 ${plan.source.messages.size} 条 · 保留 ${plan.retainedCount} 条\n" +
                    "材料输入约 ${plan.selectedTokens} tokens；预计 ${plan.minimumCalls}～${plan.maximumCalls} 次调用。")
                Text(expectedCost(plan), style = MaterialTheme.typography.bodySmall)
                Text("估算采用预览时单价；实际每次摘要调用按开始时间选取峰谷价格，并保存当时单价。", style = MaterialTheme.typography.bodySmall)
                Text("接口输出限制：${if (plan.config.maxTokens > 0) "${plan.config.maxTokens} tokens" else "接口默认"}；目标字数为正文约束，推理也可能占用输出额度。",
                    style = MaterialTheme.typography.bodySmall)
                Text("点击才会请求模型，摘要生成后还需应用。中断已产生的用量仍会记录。", style = MaterialTheme.typography.bodySmall)
                Button(enabled = editable && !busy, onClick = { coordinator.generate(plan) }) { Text("生成摘要草稿") }
            }
            draft?.let { revision ->
                Text("3 · 编辑与应用", style = MaterialTheme.typography.titleMedium)
                Text("覆盖 ${revision.source.messages.size} 条 · ${revision.segments.size} 段 · ${methodLabel(revision.method)}", style = MaterialTheme.typography.bodySmall)
                revision.segments.forEachIndexed { index, segment ->
                    OutlinedTextField(texts.getOrElse(index) { segment.text }, onValueChange = { changed ->
                        texts = texts.toMutableList().also { it[index] = changed }; after = null
                    }, enabled = editable, label = { Text("摘要 ${index + 1}") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).stopTextFlingPropagation())
                }
                Text("当前上下文约 ${before ?: "待计算"} → 应用后约 ${after ?: "待重新计算"} tokens（均为本地估算）。")
                if (before != null && after != null && after!! >= before!!) Text("摘要没有缩短上下文；可调整文字，或仍由你决定是否应用。", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = editable, onClick = { action {
                    val edited = service.editedDraft(revision, texts)
                    coordinator.updateDraft(conversationId, edited)
                    before = ContextPlanner(app.deps).prepare(conversationId).estimatedTokens
                    after = service.afterTokens(edited)
                } }) { Text("保存编辑并更新预览") }
                Button(enabled = editable && !busy && after != null && texts.all { it.isNotBlank() }, onClick = { action {
                    val edited = service.editedDraft(revision, texts)
                    service.apply(edited, isIdle)
                    coordinator.updateDraft(conversationId, null)
                    preview = null; reload(); feedback = "摘要已应用，下一轮生效"
                } }) { Text("应用摘要") }
                Text("退出后草稿会保留；未应用时模型仍读原有上下文。", style = MaterialTheme.typography.bodySmall)
            }
            options?.drafts?.filter { it.id != draft?.id }?.take(5)?.forEach { saved ->
                TextButton(enabled = editable, onClick = { coordinator.updateDraft(conversationId, saved) }) {
                    Text("加载草稿 · ${time(saved.createdAt)} · ${saved.source.messages.size} 条")
                }
            }
            if (options?.versions?.isNotEmpty() == true) {
                Text("已保存的摘要版本", style = MaterialTheme.typography.titleMedium)
                options!!.versions.forEach { version ->
                    TextButton(onClick = { viewing = if (viewing?.id == version.id) null else version }) {
                        Text("${if (version.id == options!!.active?.id) "当前 · " else ""}${time(version.createdAt)} · ${version.source.messages.size} 条 · ${methodLabel(version.method)}")
                    }
                }
            }
            viewing?.let { version ->
                Text("${if (version.manuallyEdited) "含手工编辑 · " else ""}已保存摘要（只读）", style = MaterialTheme.typography.titleSmall)
                version.segments.forEach { Text(it.text) }
            }
            draft?.let { revision ->
                val calls by produceState<List<CallRecord>>(emptyList(), revision.id) {
                    value = app.store.calls(conversationId).filter { it.operationId == revision.id }
                }
                if (calls.isNotEmpty()) {
                    Text("本次摘要实际调用 ${calls.size} 次", style = MaterialTheme.typography.titleSmall)
                    calls.forEach { Text("${it.model} · ${costLabel(recordedCost(it))}\n输入：${tokenLabel(it.input)} · 输出：${tokenLabel(it.output)}",
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun time(at: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
private fun methodLabel(method: CompressionMethod) = when (method) {
    CompressionMethod.ORIGINAL -> "原文重新摘要"
    CompressionMethod.INCREMENTAL -> "旧摘要 + 新增原文"
    CompressionMethod.SEGMENTED -> "保留多段摘要"
}
private fun expectedCost(plan: CompressionPreview): String {
    val input = plan.selectedTokens + (plan.maximumCalls - plan.chunks.size) * plan.budget
    val output = Estimate.roughTokensOf("字".repeat(plan.targetChars)) * plan.maximumCalls
    val hypothetical = CallRecord("preview", conversationId = plan.source.conversationId, model = plan.config.model,
        startedAt = 0, price = plan.price, inputCharacters = 0,
        input = TokenAmount(input, TokenSource.ESTIMATED), output = TokenAmount(output, TokenSource.ESTIMATED))
    return "按目标长度与合并预算粗估：${costLabel(recordedCost(hypothetical))}；实际长度与调用次数可能不同，最终按返回用量记账。"
}
