package app.solochat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import app.solochat.core.model.*
import java.util.UUID

/** 所有操作只更新父页面草稿；持久化由价格页统一“保存”。 */
@Composable
fun PriceTableEditor(catalog: PriceCatalog, onChange: (PriceCatalog) -> Unit) {
    val table = catalog.tables.first { it.id == catalog.selectedTableId }
    var menu by remember { mutableStateOf(false) }
    var management by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(0) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    fun replace(updated: PriceTable) = onChange(catalog.copy(tables = catalog.tables.map { if (it.id == table.id) updated else it }))

    Text("价格表", style = MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(onClick = { menu = true }) { Text("当前：${table.name}") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            catalog.tables.forEach { item ->
                DropdownMenuItem(text = { Text(item.name) }, onClick = {
                    onChange(catalog.copy(selectedTableId = item.id)); menu = false; page = 0; search = ""
                })
            }
        }
    }
    Row {
        TextButton(onClick = { management = "新增"; name = ""; error = "" }) { Text("新增表") }
        TextButton(onClick = { management = "复制"; name = "${table.name}副本"; error = "" }) { Text("复制") }
        TextButton(onClick = { management = "重命名"; name = table.name; error = "" }) { Text("改名") }
        TextButton(enabled = catalog.tables.size > 1, onClick = { management = "删除"; error = "" }) { Text("删除") }
    }
    Text("金额单位：元 / 百万 token。快照可能过时，可按实际渠道修改；保存后仅影响后续调用。",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(search, onValueChange = { search = it; page = 0 }, label = { Text("查找模型") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { editing = "" }) { Text("添加模型与自定义价格") }
    val filtered = table.entries.filter { it.model.contains(search.trim(), ignoreCase = true) }.sortedBy { it.model }
    val currentPage = page.coerceIn(0, ((filtered.size - 1).coerceAtLeast(0) / 15))
    filtered.drop(currentPage * 15).take(15).forEach { entry ->
        ListItem(modifier = Modifier.clickable { editing = entry.model },
            headlineContent = { Text(entry.model) },
            supportingContent = { Text("输入 ${entry.standard.input} · 输出 ${entry.standard.output} · 缓存 ${entry.standard.cached?.toString() ?: "随输入价"}\n" +
                if (entry.peakEnabled) "峰谷已开 · ${formatPriceMinute(entry.window.startMinute!!)}～${formatPriceMinute(entry.window.endMinute!!)} ${entry.window.timeZone}"
                else "峰谷关闭") },
            trailingContent = { Text("编辑") })
    }
    if (filtered.isEmpty()) Text("没有匹配模型，可添加自定义条目。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = currentPage > 0, onClick = { page = currentPage - 1 }) { Text("上一页") }
        Text("${if (filtered.isEmpty()) 0 else currentPage * 15 + 1}～${minOf((currentPage + 1) * 15, filtered.size)} / ${filtered.size}",
            modifier = Modifier.padding(top = 12.dp))
        TextButton(enabled = (currentPage + 1) * 15 < filtered.size, onClick = { page = currentPage + 1 }) { Text("下一页") }
    }

    if (management.isNotEmpty()) AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = { management = "" },
        title = { Text("${management}价格表") },
        text = { Column {
            if (management == "删除") Text("从本页草稿删除“${table.name}”？已有调用的价格快照会保留。保存本页后生效。")
            else OutlinedTextField(name, onValueChange = { name = it }, label = { Text("表名") }, singleLine = true)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            if (management != "删除" && name.isBlank()) { error = "表名不能为空"; return@TextButton }
            when (management) {
                "删除" -> {
                    val remaining = catalog.tables.filterNot { it.id == table.id }
                    onChange(catalog.copy(selectedTableId = remaining.first().id, tables = remaining))
                }
                "重命名" -> replace(table.copy(name = name.trim()))
                else -> {
                    val added = PriceTable(UUID.randomUUID().toString(), name.trim(), if (management == "复制") table.entries else emptyList())
                    onChange(catalog.copy(selectedTableId = added.id, tables = catalog.tables + added))
                }
            }
            management = ""; page = 0
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { management = "" }) { Text("取消") } })

    editing?.let { key ->
        val original = table.entries.firstOrNull { it.model == key } ?: ModelPriceEntry("")
        ModelPriceDialog(original, isNew = key.isEmpty(), onDismiss = { editing = null }, onApply = { entry ->
            require(table.entries.none { it.model == entry.model && it.model != key }) { "该模型已存在，请编辑已有条目" }
            replace(table.copy(entries = table.entries.filterNot { it.model == key } + entry))
            editing = null
        }, onDelete = { replace(table.copy(entries = table.entries.filterNot { it.model == key })); editing = null })
    }
}

@Composable
private fun ModelPriceDialog(original: ModelPriceEntry, isNew: Boolean, onDismiss: () -> Unit,
    onApply: (ModelPriceEntry) -> Unit, onDelete: () -> Unit) {
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

    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除模型价格") },
        text = { Text("删除“${original.model}”的价格条目？未匹配时将使用全局自填价；未配置自填价则费用未知。") },
        confirmButton = { TextButton(onClick = onDelete) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    else AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = onDismiss, title = { Text(if (isNew) "添加模型价格" else "编辑模型价格") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(model, { model = it }, label = { Text("模型名（精确匹配）") }, singleLine = true)
                Text("标准 / 谷价（元 / 百万 token）")
                RateFields(input, { input = it }, output, { output = it }, cacheSet, { cacheSet = it }, cache, { cache = it })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("启用峰谷计价", Modifier.weight(1f))
                    Switch(enabled, { enabled = it; if (it) hasPeak = true })
                }
                if (hasPeak) {
                    Text("峰价（元 / 百万 token）")
                    RateFields(peakInput, { peakInput = it }, peakOutput, { peakOutput = it }, peakCacheSet,
                        { peakCacheSet = it }, peakCache, { peakCache = it })
                    OutlinedTextField(start, { start = it }, label = { Text("峰时开始 HH:mm") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("峰时结束 HH:mm") }, singleLine = true)
                    OutlinedTextField(zone, { zone = it }, label = { Text("时区，例如 Asia/Shanghai") }, singleLine = true)
                    Text("每天生效，含开始、不含结束；开始晚于结束表示跨午夜。其余时间按谷价。按每次请求开始时间冻结价格。",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(note, { note = it }, label = { Text("说明 / 来源备注") })
                Text("原来源：${original.source}", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!isNew) TextButton(onClick = { deleting = true }) { Text("删除此模型价格") }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching {
                fun number(text: String) = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?: throw IllegalArgumentException("单价需为不小于 0 的有效数字")
                require(start.isBlank() || parsePriceMinute(start) != null) { "峰时开始请填写 HH:mm" }
                require(end.isBlank() || parsePriceMinute(end) != null) { "峰时结束请填写 HH:mm" }
                val entry = original.copy(model = model.trim().lowercase(),
                    standard = PriceRates(number(input), number(output), if (cacheSet) number(cache) else null),
                    peak = if (hasPeak) PriceRates(number(peakInput), number(peakOutput), if (peakCacheSet) number(peakCache) else null) else null,
                    peakEnabled = enabled, window = PeakWindow(parsePriceMinute(start), parsePriceMinute(end), zone.trim()),
                    note = note, source = if (original.source.endsWith("（用户已编辑）")) original.source else "${original.source}（用户已编辑）")
                PriceCatalogFormat.validateEntry(entry)
                onApply(entry)
            }.onFailure { error = it.message ?: "价格填写有误" }
        }) { Text("应用到本页草稿") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RateFields(input: String, onInput: (String) -> Unit, output: String, onOutput: (String) -> Unit,
    cacheSet: Boolean, onCacheSet: (Boolean) -> Unit, cache: String, onCache: (String) -> Unit) {
    OutlinedTextField(input, onInput, label = { Text("输入") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    OutlinedTextField(output, onOutput, label = { Text("输出") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("单独设置缓存价", Modifier.weight(1f)); Switch(cacheSet, onCacheSet)
    }
    if (cacheSet) OutlinedTextField(cache, onCache, label = { Text("缓存命中（0 表示免费）") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    else Text("缓存命中按输入价计算。", style = MaterialTheme.typography.bodySmall)
}
