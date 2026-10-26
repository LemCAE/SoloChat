package app.solochat.ui.settings

import app.solochat.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.solochat.core.model.*
import app.solochat.ui.SettingsFormGroup
import app.solochat.ui.InfoButton
import app.solochat.ui.ToolTopBar
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch

@Serializable
internal data class AdvancedPriceRules(val schedule: PriceSchedule? = null, val tiers: List<PriceTier> = emptyList())

internal fun withTopTierDraft(rules: AdvancedPriceRules, standard: PriceRates, peak: PriceRates?): AdvancedPriceRules =
    if (rules.tiers.isEmpty()) rules else rules.copy(tiers = rules.tiers.mapIndexed { index, tier ->
        if (index == 0) tier.copy(standard = standard, peak = peak) else tier
    })

/** 复杂规则也只返回模型编辑草稿，不越过父价格页写设置。 */
@Composable
internal fun AdvancedPriceEditor(original: ModelPriceEntry, rules: AdvancedPriceRules,
    peakEnabled: Boolean, onDismiss: () -> Unit, onApply: (AdvancedPriceRules) -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var customSchedule by rememberSaveable { mutableStateOf(rules.schedule != null) }
    var ranges by rememberSaveable { mutableStateOf(rules.schedule?.ranges?.joinToString("\n") {
        "${formatPriceMinute(it.startMinute)}-${formatPriceMinute(it.endMinute)}" }.orEmpty()) }
    var weekdays by rememberSaveable { mutableStateOf(rules.schedule?.weekdays?.joinToString(",") ?: "1,2,3,4,5") }
    var zone by rememberSaveable { mutableStateOf(rules.schedule?.timeZone ?: original.window.timeZone) }
    var useCalendar by rememberSaveable { mutableStateOf(rules.schedule?.calendar != null) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var showTiers by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.validFrom.orEmpty()) }
    var through by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.validThrough.orEmpty()) }
    var excluded by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.excludedDates?.joinToString("\n").orEmpty()) }
    var included by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.includedDates?.joinToString("\n").orEmpty()) }
    var calendarSource by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.source ?: stringsContext.getString(R.string.ui_advancedpriceeditor_84ca55d1)) }
    var calendarConfirmed by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.confirmed ?: false) }
    var tiered by rememberSaveable { mutableStateOf(rules.tiers.isNotEmpty()) }
    // 每档八个字符串，包含尚未填完的数字；不在输入中途将空值转换为免费价格。
    var fields by rememberSaveable { mutableStateOf(rules.tiers.flatMap {
        listOf(it.upToInputTokens?.toString().orEmpty(), it.standard.input.toString(), it.standard.output.toString(),
            it.standard.cached?.toString().orEmpty(), (it.peak != null).toString(), it.peak?.input?.toString().orEmpty(),
            it.peak?.output?.toString().orEmpty(), it.peak?.cached?.toString().orEmpty()) }) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    val values = listOf(customSchedule, ranges, weekdays, zone, useCalendar, from, through, excluded, included, calendarSource, calendarConfirmed, tiered, fields).toString()
    val initial = remember { values }
    fun leave() { if (values != initial) confirmLeave = true else onDismiss() }
    fun set(index: Int, value: String) { fields = fields.toMutableList().apply { this[index] = value } }
    fun tokens(text: String) = text.split(Regex("[，,\\s]+" )).filter { it.isNotBlank() }

    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(stringsContext.getString(R.string.ui_advancedpriceeditor_a254bc4b), onBack = { leave() }, actions = {
            TextButton(onClick = {
                runCatching {
                    val schedule = if (!customSchedule) null else PriceSchedule(
                        ranges.lineSequence().filter { it.isNotBlank() }.map { row ->
                            val parts = row.trim().split('-')
                            require(parts.size == 2) { stringsContext.getString(R.string.ui_advancedpriceeditor_4a5b457e) }
                            PriceTimeRange(requireNotNull(parsePriceMinute(parts[0])) { stringsContext.getString(R.string.ui_advancedpriceeditor_249d4435) },
                                requireNotNull(parsePriceMinute(parts[1])) { stringsContext.getString(R.string.ui_advancedpriceeditor_59e7cadc) })
                        }.toList(), tokens(weekdays).map { requireNotNull(it.toIntOrNull()) { stringsContext.getString(R.string.ui_advancedpriceeditor_970e6bcc) } }, zone.trim(),
                        if (!useCalendar) null else PriceCalendar(from.trim(), through.trim(), tokens(excluded), tokens(included), calendarSource.trim(), calendarConfirmed))
                    fun number(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                        ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_advancedpriceeditor_8cbe3571))
                    val tiers = if (!tiered) emptyList() else fields.chunked(8).map { row ->
                        val limit = if (row[0].isBlank()) null else requireNotNull(row[0].trim().toIntOrNull()) { stringsContext.getString(R.string.ui_advancedpriceeditor_f5bd8538) }
                        PriceTier(limit, PriceRates(number(row[1]), number(row[2]), row[3].takeIf { it.isNotBlank() }?.let(::number)),
                            if (row[4] != "true") null else PriceRates(number(row[5]), number(row[6]), row[7].takeIf { it.isNotBlank() }?.let(::number)))
                    }
                    require(!tiered || tiers.isNotEmpty()) { stringsContext.getString(R.string.ui_advancedpriceeditor_aa428ab0) }
                    schedule?.let(::validateSchedule)
                    validateFrozenTiers(tiers.map { FrozenPriceTier(it.upToInputTokens, it.standard) })
                    tiers.forEach { tier ->
                        tier.peak?.let { validateFrozenTiers(listOf(FrozenPriceTier(null, it))) }
                        require(!peakEnabled || tier.peak != null) { stringsContext.getString(R.string.ui_advancedpriceeditor_c5004c65) }
                    }
                    // 这里只校验规则表单；模型 ID、主单价及旧单时段由父模型页完成时统一校验。
                    onApply(AdvancedPriceRules(schedule, tiers))
                }.onFailure { error = it.message ?: stringsContext.getString(R.string.ui_advancedpriceeditor_9ffb9bba); scope.launch { scroll.animateScrollTo(0) } }
            }) { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_06be9697)) }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                SettingsFormGroup(stringsContext.getString(R.string.ui_advancedpriceeditor_c789af26)) {
                    SwitchRow(stringsContext.getString(R.string.ui_advancedpriceeditor_f8a32ff2), stringsContext.getString(R.string.ui_advancedpriceeditor_0c1330ff), customSchedule, { customSchedule = it })
                    if (customSchedule) {
                        OutlinedTextField(ranges, { ranges = it }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_87f431c6)) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                            trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_advancedpriceeditor_87f431c6), stringsContext.getString(R.string.ui_advancedpriceeditor_4a5b457e)) })
                        OutlinedTextField(weekdays, { weekdays = it }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_f29d9137)) }, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_advancedpriceeditor_f29d9137), stringsContext.getString(R.string.ui_advancedpriceeditor_970e6bcc)) })
                        OutlinedTextField(zone, { zone = it }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_4c9abdf9)) }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { showCalendar = !showCalendar }) {
                            Text("${if (showCalendar) "收起" else "展开"}日历${if (useCalendar) " · 已设置" else " · 未设置"}")
                        }
                        if (showCalendar) SwitchRow(stringsContext.getString(R.string.ui_advancedpriceeditor_e748e91f), stringsContext.getString(R.string.ui_advancedpriceeditor_7716620c), useCalendar, { useCalendar = it })
                        if (showCalendar && useCalendar) {
                            OutlinedTextField(from, { from = it; calendarConfirmed = false }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_116f5c39)) }, modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_advancedpriceeditor_e748e91f), stringsContext.getString(R.string.ui_advancedpriceeditor_b99dc465)) })
                            OutlinedTextField(through, { through = it; calendarConfirmed = false }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_a31e6eee)) }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(excluded, { excluded = it; calendarConfirmed = false }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_271098f6)) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                            OutlinedTextField(included, { included = it; calendarConfirmed = false }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_eabcb700)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                            SwitchRow(stringsContext.getString(R.string.ui_advancedpriceeditor_2d56d472), stringsContext.getString(R.string.ui_advancedpriceeditor_b353cab5), calendarConfirmed, { calendarConfirmed = it })
                            OutlinedTextField(calendarSource, { calendarSource = it; calendarConfirmed = false }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_b0ac5e8a)) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                SettingsFormGroup(stringsContext.getString(R.string.ui_advancedpriceeditor_757e46cd)) {
                    TextButton(onClick = { showTiers = !showTiers }) {
                        Text("${if (showTiers) "收起" else "展开"}阶梯${if (tiered) " · ${fields.size / 8} 档" else " · 未设置"}")
                    }
                    if (showTiers) SwitchRow(stringsContext.getString(R.string.ui_advancedpriceeditor_61e28d1a), stringsContext.getString(R.string.ui_advancedpriceeditor_53620462), tiered, { tiered = it })
                    if (showTiers && tiered) {
                        fields.chunked(8).forEachIndexed { index, row ->
                            Text(stringsContext.getString(R.string.ui_advancedpriceeditor_0cc08e03, index + 1), style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(row[0], { set(index * 8, it) }, label = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_15dadd06)) }, modifier = Modifier.fillMaxWidth())
                            listOf(stringsContext.getString(R.string.ui_advancedpriceeditor_56043e1c), stringsContext.getString(R.string.ui_advancedpriceeditor_cff5a87c), stringsContext.getString(R.string.ui_advancedpriceeditor_8afb6e20)).forEachIndexed { offset, label ->
                                OutlinedTextField(row[offset + 1], { set(index * 8 + offset + 1, it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                            }
                            SwitchRow(stringsContext.getString(R.string.ui_advancedpriceeditor_1ceee1d1), stringsContext.getString(R.string.ui_advancedpriceeditor_d7dcba02), row[4] == "true", { set(index * 8 + 4, it.toString()) })
                            if (row[4] == "true") listOf(stringsContext.getString(R.string.ui_advancedpriceeditor_1603263f), stringsContext.getString(R.string.ui_advancedpriceeditor_3707ca4b), stringsContext.getString(R.string.ui_advancedpriceeditor_89995d97)).forEachIndexed { offset, label ->
                                OutlinedTextField(row[offset + 5], { set(index * 8 + offset + 5, it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = { fields = fields.filterIndexed { i, _ -> i / 8 != index } }) { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_868892c3, index + 1)) }
                            HorizontalDivider()
                        }
                        TextButton(onClick = { fields = fields + listOf("", "", "", "", "false", "", "", "") }) { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_3153bbf8)) }
                    }
                }
            }
        }
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_0aadafe2)) },
            text = { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_4e006636)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_59f2c9d0)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringsContext.getString(R.string.ui_advancedpriceeditor_106b68e5)) } })
    }
}
