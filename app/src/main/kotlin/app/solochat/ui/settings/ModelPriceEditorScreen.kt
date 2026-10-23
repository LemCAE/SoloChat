package app.solochat.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.solochat.core.model.*
import app.solochat.ui.SettingsFormGroup
import app.solochat.ui.ToolTopBar
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 全屏编辑覆盖在价格页之上，保持价格页滚动位置和其他未保存草稿。完成也不直接写设置。 */
@Composable
internal fun ModelPriceEditorScreen(original: ModelPriceEntry, tableName: String, isNew: Boolean,
    onDismiss: () -> Unit, onApply: (ModelPriceEntry) -> Unit, onDelete: () -> Unit) {
    var provider by rememberSaveable(original.model) { mutableStateOf(original.provider) }
    var model by rememberSaveable(original.model) { mutableStateOf(original.model) }
    var input by rememberSaveable(original.model) { mutableStateOf(original.standard.input.toString()) }
    var output by rememberSaveable(original.model) { mutableStateOf(original.standard.output.toString()) }
    var cacheSet by rememberSaveable(original.model) { mutableStateOf(original.standard.cached != null) }
    var cache by rememberSaveable(original.model) { mutableStateOf((original.standard.cached ?: 0.0).toString()) }
    var enabled by rememberSaveable(original.model) { mutableStateOf(original.peakEnabled) }
    var hasPeak by rememberSaveable(original.model) { mutableStateOf(original.peak != null) }
    val peak = original.peak ?: original.standard
    var peakInput by rememberSaveable(original.model) { mutableStateOf(peak.input.toString()) }
    var peakOutput by rememberSaveable(original.model) { mutableStateOf(peak.output.toString()) }
    var peakCacheSet by rememberSaveable(original.model) { mutableStateOf(peak.cached != null) }
    var peakCache by rememberSaveable(original.model) { mutableStateOf((peak.cached ?: 0.0).toString()) }
    var start by rememberSaveable(original.model) { mutableStateOf(original.window.startMinute?.let(::formatPriceMinute).orEmpty()) }
    var end by rememberSaveable(original.model) { mutableStateOf(original.window.endMinute?.let(::formatPriceMinute).orEmpty()) }
    var zone by rememberSaveable(original.model) { mutableStateOf(original.window.timeZone) }
    var note by rememberSaveable(original.model) { mutableStateOf(original.note) }
    var error by rememberSaveable(original.model) { mutableStateOf("") }
    var deleting by rememberSaveable(original.model) { mutableStateOf(false) }

    var rulesJson by rememberSaveable(original.model) { mutableStateOf(Json.encodeToString(AdvancedPriceRules(original.schedule, original.tiers))) }
    val rules = remember(rulesJson) { Json.decodeFromString<AdvancedPriceRules>(rulesJson) }
    val initialRulesJson = remember(original) { Json.encodeToString(AdvancedPriceRules(original.schedule, original.tiers)) }
    var editingRules by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var showRetainedPeak by rememberSaveable(original.model) { mutableStateOf(false) }
    var confirmLeave by rememberSaveable(original.model) { mutableStateOf(false) }
    // 比较原始表单值，未填完的数字或时段也受到离页提醒保护。
    val fields = listOf(model, provider, input, output, cacheSet.toString(), cache, enabled.toString(), hasPeak.toString(),
        peakInput, peakOutput, peakCacheSet.toString(), peakCache, start, end, zone, note)
    val initialFields = remember(original) {
        listOf(original.model, original.provider, original.standard.input.toString(), original.standard.output.toString(),
            (original.standard.cached != null).toString(), (original.standard.cached ?: 0.0).toString(),
            original.peakEnabled.toString(), (original.peak != null).toString(),
            peak.input.toString(), peak.output.toString(), (peak.cached != null).toString(), (peak.cached ?: 0.0).toString(),
            original.window.startMinute?.let(::formatPriceMinute).orEmpty(),
            original.window.endMinute?.let(::formatPriceMinute).orEmpty(), original.window.timeZone, original.note)
    }
    fun leave() { if (fields != initialFields || rulesJson != initialRulesJson) confirmLeave = true else onDismiss() }

    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(),
            topBar = { ToolTopBar(if (isNew) "添加模型价格" else "编辑模型价格", onBack = { leave() },
                actions = { TextButton(onClick = {
                    runCatching {
                        fun number(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                            ?: throw IllegalArgumentException("单价需为不小于 0 的有效数字")
                        require(rules.schedule != null || start.isBlank() || parsePriceMinute(start) != null) { "峰时开始请填写 HH:mm" }
                        require(rules.schedule != null || end.isBlank() || parsePriceMinute(end) != null) { "峰时结束请填写 HH:mm" }
                        val entry = original.copy(model = model.trim().lowercase(), provider = provider.trim(),
                            standard = PriceRates(number(input), number(output), if (cacheSet) number(cache) else null),
                            peak = if (hasPeak) PriceRates(number(peakInput), number(peakOutput), if (peakCacheSet) number(peakCache) else null) else null,
                            peakEnabled = enabled, window = PeakWindow(parsePriceMinute(start), parsePriceMinute(end), zone.trim()),
                            schedule = rules.schedule, tiers = rules.tiers,
                            note = note, source = if (original.source.endsWith("（用户已编辑）")) original.source else "${original.source}（用户已编辑）")
                        PriceCatalogFormat.validateEntry(entry)
                        onApply(entry)
                    }.onFailure {
                        error = it.message ?: "价格填写有误"
                        if (!enabled && hasPeak) showRetainedPeak = true
                        scope.launch { scroll.animateScrollTo(0) }
                    }
                }) { Text("完成") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${tableName} · 完成后回到价格页草稿，保存价格页后生效。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                SettingsFormGroup("模型") {
                    OutlinedTextField(model, { model = it }, label = { Text("模型 ID（精确匹配）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(provider, { provider = it }, label = { Text("模型归属 / 服务商（可自填）") },
                        supportingText = { Text("仅用于列表分组，不决定请求地址或单价。留空表示未分类。") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                SettingsFormGroup(if (enabled) "谷价 · 元 / 百万 token" else "标准价 · 元 / 百万 token") {
                    if (rules.tiers.isEmpty()) RateFields(input, { input = it }, output, { output = it }, cacheSet, { cacheSet = it }, cache, { cache = it })
                    else Text("已配置 ${rules.tiers.size} 档输入长度价格；本组基础单价保留但不参与此模型阶梯计算。")
                }
                SettingsFormGroup("峰谷计价") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("启用峰谷计价", Modifier.weight(1f))
                        Switch(enabled, { enabled = it; if (it) hasPeak = true })
                    }
                    if (!enabled) {
                        Text("全天按标准价计算，已填写的峰价和时段会保留。",
                            style = MaterialTheme.typography.bodySmall)
                        if (hasPeak) TextButton(onClick = { showRetainedPeak = !showRetainedPeak }) {
                            Text(if (showRetainedPeak) "收起保留的峰价与时段" else "查看保留的峰价与时段")
                        }
                    }
                    TextButton(onClick = { editingRules = true }) { Text("编辑多时段、日历与阶梯") }
                    Text(if (rules.schedule == null) "使用每日单时段" else "已配置 ${rules.schedule.ranges.size} 个时段，星期 ${rules.schedule.weekdays.joinToString()}" +
                        (rules.schedule.calendar?.let { "；日历 ${it.validFrom} 至 ${it.validThrough}" } ?: "；无日期例外"))
                    if (enabled || showRetainedPeak) {
                        if (rules.tiers.isEmpty()) {
                        Text("峰价 · 元 / 百万 token", style = MaterialTheme.typography.titleSmall)
                        RateFields(peakInput, { peakInput = it }, peakOutput, { peakOutput = it }, peakCacheSet,
                            { peakCacheSet = it }, peakCache, { peakCache = it })
                        }
                        if (rules.schedule == null) {
                        OutlinedTextField(start, { start = it }, label = { Text("峰时开始 HH:mm") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(end, { end = it }, label = { Text("峰时结束 HH:mm") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(zone, { zone = it }, label = { Text("时区，例如 Asia/Shanghai") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("每天生效，含开始、不含结束；开始晚于结束表示跨午夜，其余时间按谷价。",
                            style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("价格按每次调用开始时间冻结，修改不会重算历史费用。",
                        style = MaterialTheme.typography.bodySmall)
                }
                SettingsFormGroup("备注与来源") {
                    OutlinedTextField(note, { note = it }, label = { Text("说明 / 来源备注") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("原来源：${original.source}", style = MaterialTheme.typography.bodySmall)
                }
                if (!isNew) TextButton(onClick = { deleting = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除此模型价格")
                }
            }
        }
        if (editingRules) AdvancedPriceEditor(original, rules, enabled, onDismiss = { editingRules = false },
            onApply = { rulesJson = Json.encodeToString(it); editingRules = false })
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
            title = { Text("放弃模型编辑？") }, text = { Text("本次模型编辑尚未应用；价格页中其他草稿会保留。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("放弃编辑") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除模型价格") },
            text = { Text("从价格页草稿删除“${original.model}”？保存价格页后生效。未匹配时使用本组兜底价，未配置则费用未知。") },
            confirmButton = { TextButton(onClick = onDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    }
}

@Composable
private fun RateFields(input: String, onInput: (String) -> Unit, output: String, onOutput: (String) -> Unit,
    cacheSet: Boolean, onCacheSet: (Boolean) -> Unit, cache: String, onCache: (String) -> Unit) {
    OutlinedTextField(input, onInput, label = { Text("输入") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    OutlinedTextField(output, onOutput, label = { Text("输出") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("单独设置缓存价", Modifier.weight(1f)); Switch(cacheSet, onCacheSet)
    }
    if (cacheSet) OutlinedTextField(cache, onCache, label = { Text("缓存命中（0 表示免费）") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    else Text("缓存命中按输入价计算。", style = MaterialTheme.typography.bodySmall)
}
