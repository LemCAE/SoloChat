package app.solochat.ui.settings

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
import app.solochat.ui.ToolTopBar
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch

@Serializable
internal data class AdvancedPriceRules(val schedule: PriceSchedule? = null, val tiers: List<PriceTier> = emptyList())

/** 复杂规则也只返回模型编辑草稿，不越过父价格页写设置。 */
@Composable
internal fun AdvancedPriceEditor(original: ModelPriceEntry, rules: AdvancedPriceRules,
    peakEnabled: Boolean, onDismiss: () -> Unit, onApply: (AdvancedPriceRules) -> Unit) {
    var customSchedule by rememberSaveable { mutableStateOf(rules.schedule != null) }
    var ranges by rememberSaveable { mutableStateOf(rules.schedule?.ranges?.joinToString("\n") {
        "${formatPriceMinute(it.startMinute)}-${formatPriceMinute(it.endMinute)}" }.orEmpty()) }
    var weekdays by rememberSaveable { mutableStateOf(rules.schedule?.weekdays?.joinToString(",") ?: "1,2,3,4,5") }
    var zone by rememberSaveable { mutableStateOf(rules.schedule?.timeZone ?: original.window.timeZone) }
    var useCalendar by rememberSaveable { mutableStateOf(rules.schedule?.calendar != null) }
    var from by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.validFrom.orEmpty()) }
    var through by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.validThrough.orEmpty()) }
    var excluded by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.excludedDates?.joinToString("\n").orEmpty()) }
    var included by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.includedDates?.joinToString("\n").orEmpty()) }
    var calendarSource by rememberSaveable { mutableStateOf(rules.schedule?.calendar?.source ?: "用户自填") }
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
        Scaffold(topBar = { ToolTopBar("时段、日历与阶梯", onBack = { leave() }, actions = {
            TextButton(onClick = {
                runCatching {
                    val schedule = if (!customSchedule) null else PriceSchedule(
                        ranges.lineSequence().filter { it.isNotBlank() }.map { row ->
                            val parts = row.trim().split('-')
                            require(parts.size == 2) { "每行时段请填写 HH:mm-HH:mm" }
                            PriceTimeRange(requireNotNull(parsePriceMinute(parts[0])) { "开始时间无效" },
                                requireNotNull(parsePriceMinute(parts[1])) { "结束时间无效" })
                        }.toList(), tokens(weekdays).map { requireNotNull(it.toIntOrNull()) { "星期需填 1～7" } }, zone.trim(),
                        if (!useCalendar) null else PriceCalendar(from.trim(), through.trim(), tokens(excluded), tokens(included), calendarSource.trim(), calendarConfirmed))
                    fun number(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                        ?: throw IllegalArgumentException("单价需为不小于 0 的有效数字")
                    val tiers = if (!tiered) emptyList() else fields.chunked(8).map { row ->
                        val limit = if (row[0].isBlank()) null else requireNotNull(row[0].trim().toIntOrNull()) { "阶梯上限需填整数" }
                        PriceTier(limit, PriceRates(number(row[1]), number(row[2]), row[3].takeIf { it.isNotBlank() }?.let(::number)),
                            if (row[4] != "true") null else PriceRates(number(row[5]), number(row[6]), row[7].takeIf { it.isNotBlank() }?.let(::number)))
                    }
                    require(!tiered || tiers.isNotEmpty()) { "请至少添加一个阶梯" }
                    schedule?.let(::validateSchedule)
                    validateFrozenTiers(tiers.map { FrozenPriceTier(it.upToInputTokens, it.standard) })
                    tiers.forEach { tier ->
                        tier.peak?.let { validateFrozenTiers(listOf(FrozenPriceTier(null, it))) }
                        require(!peakEnabled || tier.peak != null) { "开启峰谷后每档均需填写峰价" }
                    }
                    // 这里只校验规则表单；模型 ID、主单价及旧单时段由父模型页完成时统一校验。
                    onApply(AdvancedPriceRules(schedule, tiers))
                }.onFailure { error = it.message ?: "规则填写有误"; scope.launch { scroll.animateScrollTo(0) } }
            }) { Text("完成") }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("完成后回到模型草稿；最终仍需保存价格页。")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                SettingsFormGroup("多时段与星期") {
                    SwitchRow("使用多时段 / 星期规则", "关闭后使用主编辑页的每日单时段。", customSchedule, { customSchedule = it })
                    if (customSchedule) {
                        OutlinedTextField(ranges, { ranges = it }, label = { Text("峰时段，每行 HH:mm-HH:mm") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(weekdays, { weekdays = it }, label = { Text("星期：1=周一，7=周日，逗号分隔") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(zone, { zone = it }, label = { Text("时区") }, modifier = Modifier.fillMaxWidth())
                        Text("含开始、不含结束；跨午夜的凌晨部分归开始日期。峰谷开关仍在主编辑页，配置不会自动启用。")
                        SwitchRow("使用有界日历", "有效期外的待判定时段费用未知；不自动联网更新。", useCalendar, { useCalendar = it })
                        if (useCalendar) {
                            OutlinedTextField(from, { from = it; calendarConfirmed = false }, label = { Text("日历开始 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(through, { through = it; calendarConfirmed = false }, label = { Text("日历结束 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(excluded, { excluded = it; calendarConfirmed = false }, label = { Text("排除日期，每行 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                            OutlinedTextField(included, { included = it; calendarConfirmed = false }, label = { Text("额外适用日期，每行 yyyy-MM-dd（可空）") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                            SwitchRow("已核对本组渠道的日期口径", "确认后才按此日历自动估价；修改日历需重新确认。", calendarConfirmed, { calendarConfirmed = it })
                            OutlinedTextField(calendarSource, { calendarSource = it; calendarConfirmed = false }, label = { Text("日历来源 / 核对说明") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                SettingsFormGroup("输入长度阶梯") {
                    SwitchRow("使用阶梯单价", "按接口返回的输入总量选整档，不是逐段累加；缺返回量则费用未知。", tiered, { tiered = it })
                    if (tiered) {
                        Text("上限包含本数，必须递增；最后一档上限可留空表示不限。标准价在主开关关闭或谷时使用。缓存价留空随输入，0 表示免费。")
                        fields.chunked(8).forEachIndexed { index, row ->
                            Text("第 ${index + 1} 档", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(row[0], { set(index * 8, it) }, label = { Text("输入 token 上限，最后一档可空") }, modifier = Modifier.fillMaxWidth())
                            listOf("标准输入", "标准输出", "标准缓存（可空）").forEachIndexed { offset, label ->
                                OutlinedTextField(row[offset + 1], { set(index * 8 + offset + 1, it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                            }
                            SwitchRow("此档保留峰价", "启用峰谷时每档均须有峰价。", row[4] == "true", { set(index * 8 + 4, it.toString()) })
                            if (row[4] == "true") listOf("峰时输入", "峰时输出", "峰时缓存（可空）").forEachIndexed { offset, label ->
                                OutlinedTextField(row[offset + 5], { set(index * 8 + offset + 5, it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = { fields = fields.filterIndexed { i, _ -> i / 8 != index } }) { Text("删除第 ${index + 1} 档") }
                            HorizontalDivider()
                        }
                        TextButton(onClick = { fields = fields + listOf("", "", "", "", "false", "", "", "") }) { Text("添加阶梯") }
                    }
                }
            }
        }
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("放弃规则编辑？") },
            text = { Text("仅放弃本次规则修改，模型和父价格页草稿保留。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
    }
}
