package app.solochat.ui.chat

import app.solochat.R
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
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
            catch (e: Exception) { error = e.message ?: stringsContext.getString(R.string.ui_compressionscreen_08672daf) }
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
        catch (e: Exception) { error = e.message ?: stringsContext.getString(R.string.ui_compressionscreen_4ff86126) }
    }
    LaunchedEffect(draft?.id) {
        if (draft != null) {
            try {
                before = ContextPlanner(app.deps).prepare(conversationId).estimatedTokens
                after = service.afterTokens(draft)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { after = null; error = e.message ?: stringsContext.getString(R.string.ui_compressionscreen_14430e3a) }
        } else { before = null; after = null }
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text(stringsContext.getString(R.string.ui_compressionscreen_3bd65e66)) },
        text = { Text(stringsContext.getString(R.string.ui_compressionscreen_cacd1d1b)) },
        confirmButton = { TextButton(enabled = !working && !busy && !generation.generating, onClick = {
            confirmRestore = false
            action {
                val id = checkNotNull(app.store.getConversation(conversationId)).activeContextRevisionId
                service.restore(conversationId, id, isIdle)
                preview = null; reload(); feedback = stringsContext.getString(R.string.ui_compressionscreen_acc543ce)
            }
        }) { Text(stringsContext.getString(R.string.ui_compressionscreen_0b0b0397)) } }, dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringsContext.getString(R.string.ui_compressionscreen_65a6ca9f)) } })

    Scaffold(topBar = { CompactTopBar(title = { Text(stringsContext.getString(R.string.ui_compressionscreen_da8b10a7)) },
        navigationIcon = { IconButton(enabled = !working, onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringsContext.getString(R.string.ui_compressionscreen_072b258d)) } },
        actions = { TextButton(enabled = !working, onClick = { action { preview = null; reload() } }) { Text(stringsContext.getString(R.string.ui_compressionscreen_ac3919f9)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringsContext.getString(R.string.ui_compressionscreen_ffc73640), style = MaterialTheme.typography.bodyMedium)
            options?.let { data ->
                Text(data.status, color = MaterialTheme.colorScheme.primary)
                if (data.active != null) {
                    TextButton(enabled = !busy && !working && !generation.generating, onClick = { confirmRestore = true }) { Text(stringsContext.getString(R.string.ui_compressionscreen_0b0b0397)) }
                }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (generation.error.isNotEmpty()) Text(generation.error, color = MaterialTheme.colorScheme.error)
            if (feedback.isNotEmpty()) Text(feedback, color = MaterialTheme.colorScheme.primary)
            if (busy) Text(stringsContext.getString(R.string.ui_compressionscreen_f0e71f38), style = MaterialTheme.typography.bodySmall)
            if (generation.generating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringsContext.getString(R.string.ui_compressionscreen_b9f25665, generation.completed, generation.maximum))
                Text(stringsContext.getString(R.string.ui_compressionscreen_3782c276), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { coordinator.cancel(conversationId) }) { Text(stringsContext.getString(R.string.ui_compressionscreen_24904375)) }
            }
            val editable = !working && !generation.generating
            Text(stringsContext.getString(R.string.ui_compressionscreen_448f0c56), style = MaterialTheme.typography.titleMedium)
            val cuts = options?.cuts.orEmpty()
            if (cuts.isEmpty()) Text(stringsContext.getString(R.string.ui_compressionscreen_d7a7577d))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cuts.forEach { cut ->
                    TextButton(enabled = editable, onClick = { selected = cut.messageId; preview = null }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(stringsContext.getString(R.string.ui_compressionscreen_fef46eee, if (selected == cut.messageId) "✓ " else "", cut.count, cut.retained))
                            Text(stringsContext.getString(R.string.ui_compressionscreen_53f7c6a7, time(cut.at), cut.preview), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(stringsContext.getString(R.string.ui_compressionscreen_15ec3d01), style = MaterialTheme.typography.titleSmall)
            CompressionMethod.entries.forEach { value ->
                TextButton(enabled = editable, onClick = { method = value; preview = null }) {
                    Text("${if (method == value) "✓ " else ""}${methodLabel(value)}")
                }
            }
            Text(when (method) {
                CompressionMethod.ORIGINAL -> stringsContext.getString(R.string.ui_compressionscreen_8f91f397)
                CompressionMethod.INCREMENTAL -> stringsContext.getString(R.string.ui_compressionscreen_b290808c)
                CompressionMethod.SEGMENTED -> stringsContext.getString(R.string.ui_compressionscreen_a9eca386)
            }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(budgetText, onValueChange = { budgetText = it; preview = null }, enabled = editable,
                    label = { Text(stringsContext.getString(R.string.ui_compressionscreen_0458ab37)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(targetText, onValueChange = { targetText = it; preview = null }, enabled = editable,
                    label = { Text(stringsContext.getString(R.string.ui_compressionscreen_caf6af46)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(stringsContext.getString(R.string.ui_compressionscreen_eda94a20), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = editable && selected != null, onClick = { action {
                preview = service.preview(conversationId, checkNotNull(selected), method,
                    budgetText.toIntOrNull() ?: error(stringsContext.getString(R.string.ui_compressionscreen_87e94dd8)), targetText.toIntOrNull() ?: error(stringsContext.getString(R.string.ui_compressionscreen_fc83d476)))
            } }) { Text(stringsContext.getString(R.string.ui_compressionscreen_b53aa46b)) }
            preview?.let { plan ->
                Text(stringsContext.getString(R.string.ui_compressionscreen_d00f5a22), style = MaterialTheme.typography.titleMedium)
                Text(stringsContext.getString(R.string.ui_compressionscreen_cedb0e67, plan.config.model, plan.source.messages.size, plan.retainedCount) +
                    stringsContext.getString(R.string.ui_compressionscreen_004cb6b5, plan.selectedTokens, plan.minimumCalls, plan.maximumCalls))
                Text(expectedCost(plan), style = MaterialTheme.typography.bodySmall)
                Text(stringsContext.getString(R.string.ui_compressionscreen_20438f81), style = MaterialTheme.typography.bodySmall)
                Text(stringsContext.getString(R.string.ui_compressionscreen_fcd962f4, if (plan.config.maxTokens > 0) stringsContext.getString(R.string.ui_compression_output_limit, plan.config.maxTokens) else stringsContext.getString(R.string.ui_compression_default_api)),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringsContext.getString(R.string.ui_compressionscreen_9ee6fe43), style = MaterialTheme.typography.bodySmall)
                Button(enabled = editable && !busy, onClick = { coordinator.generate(plan) }) { Text(stringsContext.getString(R.string.ui_compressionscreen_0e40c5ec)) }
            }
            draft?.let { revision ->
                Text(stringsContext.getString(R.string.ui_compressionscreen_9ad39aa3), style = MaterialTheme.typography.titleMedium)
                Text(stringsContext.getString(R.string.ui_compressionscreen_fadc09cf, revision.source.messages.size, revision.segments.size, methodLabel(revision.method)), style = MaterialTheme.typography.bodySmall)
                revision.segments.forEachIndexed { index, segment ->
                    OutlinedTextField(texts.getOrElse(index) { segment.text }, onValueChange = { changed ->
                        texts = texts.toMutableList().also { it[index] = changed }; after = null
                    }, enabled = editable, label = { Text(stringsContext.getString(R.string.ui_compressionscreen_8b5ec488, index + 1)) }, minLines = 3,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).stopTextFlingPropagation())
                }
                Text(stringsContext.getString(R.string.ui_compressionscreen_1d8c7468, before ?: stringsContext.getString(R.string.ui_compression_pending), after ?: stringsContext.getString(R.string.ui_compression_recalculate)))
                if (before != null && after != null && after!! >= before!!) Text(stringsContext.getString(R.string.ui_compressionscreen_2bb4542f), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = editable, onClick = { action {
                    val edited = service.editedDraft(revision, texts)
                    coordinator.updateDraft(conversationId, edited)
                    before = ContextPlanner(app.deps).prepare(conversationId).estimatedTokens
                    after = service.afterTokens(edited)
                } }) { Text(stringsContext.getString(R.string.ui_compressionscreen_b9f454e2)) }
                Button(enabled = editable && !busy && after != null && texts.all { it.isNotBlank() }, onClick = { action {
                    val edited = service.editedDraft(revision, texts)
                    service.apply(edited, isIdle)
                    coordinator.updateDraft(conversationId, null)
                    preview = null; reload(); feedback = stringsContext.getString(R.string.ui_compressionscreen_592b8412)
                } }) { Text(stringsContext.getString(R.string.ui_compressionscreen_ea32cb07)) }
                Text(stringsContext.getString(R.string.ui_compressionscreen_9e4b4ad6), style = MaterialTheme.typography.bodySmall)
            }
            options?.drafts?.filter { it.id != draft?.id }?.take(5)?.forEach { saved ->
                TextButton(enabled = editable, onClick = { coordinator.updateDraft(conversationId, saved) }) {
                    Text(stringsContext.getString(R.string.ui_compressionscreen_9acfdc62, time(saved.createdAt), saved.source.messages.size))
                }
            }
            if (options?.versions?.isNotEmpty() == true) {
                Text(stringsContext.getString(R.string.ui_compressionscreen_47e7b08b), style = MaterialTheme.typography.titleMedium)
                options!!.versions.forEach { version ->
                    TextButton(onClick = { viewing = if (viewing?.id == version.id) null else version }) {
                        Text(stringsContext.getString(R.string.ui_compressionscreen_416e5675, if (version.id == options!!.active?.id) stringsContext.getString(R.string.ui_compression_current) else "", time(version.createdAt), version.source.messages.size, methodLabel(version.method)))
                    }
                }
            }
            viewing?.let { version ->
                Text(stringsContext.getString(R.string.ui_compressionscreen_514909ff, if (version.manuallyEdited) stringsContext.getString(R.string.ui_compression_manual) else ""), style = MaterialTheme.typography.titleSmall)
                version.segments.forEach { Text(it.text) }
            }
            draft?.let { revision ->
                val calls by produceState<List<CallRecord>>(emptyList(), revision.id) {
                    value = app.store.calls(conversationId).filter { it.operationId == revision.id }
                }
                if (calls.isNotEmpty()) {
                    Text(stringsContext.getString(R.string.ui_compressionscreen_3f9f31e5, calls.size), style = MaterialTheme.typography.titleSmall)
                    calls.forEach { Text(stringsContext.getString(R.string.ui_compressionscreen_458d383e, it.model, costLabel(recordedCost(it)), tokenLabel(it.input), tokenLabel(it.output)),
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun time(at: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))
@Composable
private fun methodLabel(method: CompressionMethod): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return when (method) {
        CompressionMethod.ORIGINAL -> context.getString(R.string.ui_compression_original)
        CompressionMethod.INCREMENTAL -> context.getString(R.string.ui_compression_incremental)
        CompressionMethod.SEGMENTED -> context.getString(R.string.ui_compression_segmented)
    }
}
@Composable
private fun expectedCost(plan: CompressionPreview): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val input = plan.selectedTokens + (plan.maximumCalls - plan.chunks.size) * plan.budget
    val output = Estimate.roughTokensOf(context.getString(R.string.ui_compression_char_unit).repeat(plan.targetChars)) * plan.maximumCalls
    val hypothetical = CallRecord("preview", conversationId = plan.source.conversationId, model = plan.config.model,
        startedAt = 0, price = plan.price, inputCharacters = 0,
        input = TokenAmount(input, TokenSource.ESTIMATED), output = TokenAmount(output, TokenSource.ESTIMATED))
    return context.getString(R.string.ui_compression_expected_cost, costLabel(recordedCost(hypothetical)))
}
