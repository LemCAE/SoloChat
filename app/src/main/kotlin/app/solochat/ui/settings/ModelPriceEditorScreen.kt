package app.solochat.ui.settings

import app.solochat.R
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
import app.solochat.ui.InfoButton
import app.solochat.ui.ToolTopBar
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 全屏编辑覆盖在价格页之上，保持价格页滚动位置和其他未保存草稿。完成也不直接写设置。 */
@Composable
internal fun ModelPriceEditorScreen(original: ModelPriceEntry, tableName: String, isNew: Boolean,
    onDismiss: () -> Unit, onApply: (ModelPriceEntry) -> Unit, onDelete: () -> Unit, identityLocked: Boolean = false) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var provider by rememberSaveable(original.model) { mutableStateOf(original.provider) }
    var model by rememberSaveable(original.model) { mutableStateOf(original.model) }
    val firstTier = original.tiers.firstOrNull()
    val firstTierRates = firstTier?.standard ?: original.standard
    var input by rememberSaveable(original.model) { mutableStateOf(if (isNew) "" else firstTierRates.input.toString()) }
    var output by rememberSaveable(original.model) { mutableStateOf(if (isNew) "" else firstTierRates.output.toString()) }
    var cacheSet by rememberSaveable(original.model) { mutableStateOf(firstTierRates.cached != null) }
    var cache by rememberSaveable(original.model) { mutableStateOf(firstTierRates.cached?.toString().orEmpty()) }
    var enabled by rememberSaveable(original.model) { mutableStateOf(original.peakEnabled) }
    var hasPeak by rememberSaveable(original.model) { mutableStateOf(original.peak != null || firstTier?.peak != null) }
    val peak = firstTier?.peak ?: original.peak ?: original.standard
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
    var showPeak by rememberSaveable(original.model) { mutableStateOf(false) }
    var confirmLeave by rememberSaveable(original.model) { mutableStateOf(false) }
    // 比较原始表单值，未填完的数字或时段也受到离页提醒保护。
    val fields = listOf(model, provider, input, output, cacheSet.toString(), cache, enabled.toString(), hasPeak.toString(),
        peakInput, peakOutput, peakCacheSet.toString(), peakCache, start, end, zone, note)
    val initialFields = remember(original) {
        listOf(original.model, original.provider, if (isNew) "" else firstTierRates.input.toString(), if (isNew) "" else firstTierRates.output.toString(),
            (firstTierRates.cached != null).toString(), firstTierRates.cached?.toString().orEmpty(),
            original.peakEnabled.toString(), (original.peak != null).toString(),
            peak.input.toString(), peak.output.toString(), (peak.cached != null).toString(), (peak.cached ?: 0.0).toString(),
            original.window.startMinute?.let(::formatPriceMinute).orEmpty(),
            original.window.endMinute?.let(::formatPriceMinute).orEmpty(), original.window.timeZone, original.note)
    }
    fun leave() { if (fields != initialFields || rulesJson != initialRulesJson) confirmLeave = true else onDismiss() }
    fun rate(inputText: String, outputText: String, cacheEnabled: Boolean, cacheText: String): PriceRates? {
        val inputValue = inputText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val outputValue = outputText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val cacheValue = if (!cacheEnabled) null else cacheText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        return PriceRates(inputValue, outputValue, cacheValue)
    }
    fun editAdvancedRules() {
        if (rules.tiers.isNotEmpty()) {
            val standard = rate(input, output, cacheSet, cache)
            val peakRates = if (hasPeak) rate(peakInput, peakOutput, peakCacheSet, peakCache) else null
            if (standard == null || (hasPeak && peakRates == null)) {
                error = "请先填写有效的第 1 档价格，再编辑高级规则。"
                return
            }
            rulesJson = Json.encodeToString(withTopTierDraft(rules, standard, peakRates))
        }
        editingRules = true
    }

    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(),
            topBar = { ToolTopBar(if (isNew) stringsContext.getString(R.string.ui_modelpriceeditorscreen_dcf148c0) else stringsContext.getString(R.string.ui_modelpriceeditorscreen_1c06819a), onBack = { leave() },
                actions = { TextButton(onClick = {
                    runCatching {
                        fun number(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                            ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_modelpriceeditorscreen_8cbe3571))
                        require(rules.schedule != null || start.isBlank() || parsePriceMinute(start) != null) { stringsContext.getString(R.string.ui_modelpriceeditorscreen_cdeba8b4) }
                        require(rules.schedule != null || end.isBlank() || parsePriceMinute(end) != null) { stringsContext.getString(R.string.ui_modelpriceeditorscreen_b33e4c70) }
                        val editedStandard = PriceRates(number(input), number(output), if (cacheSet) number(cache) else null)
                        val editedPeak = if (hasPeak) PriceRates(number(peakInput), number(peakOutput), if (peakCacheSet) number(peakCache) else null) else null
                        val editedTiers = rules.tiers.mapIndexed { index, tier -> if (index == 0)
                            tier.copy(standard = editedStandard, peak = editedPeak) else tier }
                        val entry = original.copy(model = model.trim().lowercase(), provider = provider.trim(),
                            standard = editedTiers.firstOrNull()?.standard ?: editedStandard,
                            peak = editedTiers.firstOrNull()?.peak ?: editedPeak,
                            peakEnabled = enabled, window = PeakWindow(parsePriceMinute(start), parsePriceMinute(end), zone.trim()),
                            schedule = rules.schedule, tiers = editedTiers,
                            note = note, source = if (original.source.endsWith(stringsContext.getString(R.string.ui_modelpriceeditorscreen_27a3f757))) original.source else stringsContext.getString(R.string.ui_modelpriceeditorscreen_fd0fd6f2, original.source))
                        PriceCatalogFormat.validateEntry(entry)
                        onApply(entry)
                    }.onFailure {
                        error = it.message ?: stringsContext.getString(R.string.ui_modelpriceeditorscreen_a34e6217)
                        if (!enabled && hasPeak) showRetainedPeak = true
                        scope.launch { scroll.animateScrollTo(0) }
                    }
                }) { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_06be9697)) } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_8d2ed4c4, tableName),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (identityLocked) {
                    Text("${original.provider} · ${original.model}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else SettingsFormGroup(stringsContext.getString(R.string.ui_modelpriceeditorscreen_35677e07)) {
                    OutlinedTextField(model, { model = it }, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_def49b50)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(provider, { provider = it }, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_1c72b122)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                SettingsFormGroup(if (enabled) stringsContext.getString(R.string.ui_modelpriceeditorscreen_2c16922b) else stringsContext.getString(R.string.ui_modelpriceeditorscreen_0e954b04)) {
                    if (rules.tiers.isNotEmpty()) Text("第 1 档实际价格 · 共 ${rules.tiers.size} 档", style = MaterialTheme.typography.bodySmall)
                    RateFields(input, { input = it }, output, { output = it }, cacheSet, { cacheSet = it }, cache, { cache = it })
                }
                SettingsFormGroup(stringsContext.getString(R.string.ui_modelpriceeditorscreen_9f4996c4)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_31807da7), Modifier.weight(1f))
                        Switch(enabled, { enabled = it; if (it) hasPeak = true })
                    }
                    if (!enabled) {
                        Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_c64d0fac),
                            style = MaterialTheme.typography.bodySmall)
                        if (hasPeak) TextButton(onClick = { showRetainedPeak = !showRetainedPeak }) {
                            Text(if (showRetainedPeak) stringsContext.getString(R.string.ui_modelpriceeditorscreen_74deb7f4) else stringsContext.getString(R.string.ui_modelpriceeditorscreen_13abbe0e))
                        }
                    }
                    TextButton(onClick = { showPeak = !showPeak }) { Text("${if (showPeak) "收起" else "展开"}峰价${if (hasPeak) " · 已设置" else " · 未设置"}") }
                    if (showPeak && enabled) SwitchRow(stringsContext.getString(R.string.ui_modelpriceeditorscreen_793ea7be), "保留并编辑峰时价格", hasPeak, { hasPeak = it })
                    TextButton(onClick = { editAdvancedRules() }) { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_3d12e0f4)) }
                    if (rules.schedule != null) Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_01f6ae24, rules.schedule.ranges.size, rules.schedule.weekdays.joinToString()) +
                        (rules.schedule.calendar?.let { stringsContext.getString(R.string.ui_modelpriceeditorscreen_f699bede, it.validFrom, it.validThrough) } ?: stringsContext.getString(R.string.ui_modelpriceeditorscreen_09d5222a)))
                    if (enabled || showRetainedPeak) {
                        if (showPeak || showRetainedPeak) {
                        Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_793ea7be), style = MaterialTheme.typography.titleSmall)
                        RateFields(peakInput, { peakInput = it }, peakOutput, { peakOutput = it }, peakCacheSet,
                            { peakCacheSet = it }, peakCache, { peakCache = it })
                        }
                        if (rules.schedule == null) {
                        OutlinedTextField(start, { start = it }, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_cdc1efb2)) },
                            trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_modelpriceeditorscreen_9f4996c4), stringsContext.getString(R.string.ui_modelpriceeditorscreen_ac28f5db)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(end, { end = it }, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_9f169c92)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(zone, { zone = it }, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_90ba8426)) },
                            trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_modelpriceeditorscreen_90ba8426), stringsContext.getString(R.string.ui_modelpriceeditorscreen_ac28f5db)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_ac28f5db),
                            style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_1c4f087b),
                        style = MaterialTheme.typography.bodySmall)
                }
                SettingsFormGroup(stringsContext.getString(R.string.ui_modelpriceeditorscreen_93e470e4)) {
                    OutlinedTextField(note, { note = it }, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_fd97e3aa)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_e2721f9e, original.source), style = MaterialTheme.typography.bodySmall)
                }
                if (!isNew) TextButton(onClick = { deleting = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_be81d0d9))
                }
            }
        }
        if (editingRules) AdvancedPriceEditor(original, rules, enabled, onDismiss = { editingRules = false },
            onApply = { updated ->
                rulesJson = Json.encodeToString(updated)
                updated.tiers.firstOrNull()?.let { first ->
                    input = first.standard.input.toString()
                    output = first.standard.output.toString()
                    cacheSet = first.standard.cached != null
                    cache = first.standard.cached?.toString().orEmpty()
                    hasPeak = first.peak != null
                    first.peak?.let { rates ->
                        peakInput = rates.input.toString(); peakOutput = rates.output.toString()
                        peakCacheSet = rates.cached != null; peakCache = rates.cached?.toString().orEmpty()
                    }
                }
                editingRules = false
            })
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
            title = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_c802d343)) }, text = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_3b2a5be9)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_90264fb7)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_106b68e5)) } })
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_ce489139)) },
            text = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_03f0d624, original.model)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_b59c7514)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_65a6ca9f)) } })
    }
}

@Composable
private fun RateFields(input: String, onInput: (String) -> Unit, output: String, onOutput: (String) -> Unit,
    cacheSet: Boolean, onCacheSet: (Boolean) -> Unit, cache: String, onCache: (String) -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    OutlinedTextField(input, onInput, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_cd539cc2)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    OutlinedTextField(output, onOutput, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_8abca7a8)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_cb5241e0), Modifier.weight(1f)); Switch(cacheSet, onCacheSet)
    }
    if (cacheSet) OutlinedTextField(cache, onCache, label = { Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_64c0d01c)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    else Text(stringsContext.getString(R.string.ui_modelpriceeditorscreen_a6ec64bb), style = MaterialTheme.typography.bodySmall)
}
