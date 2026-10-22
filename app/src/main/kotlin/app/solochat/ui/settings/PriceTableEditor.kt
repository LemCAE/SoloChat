package app.solochat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.solochat.ui.ToolTopBar
import app.solochat.data.pricing.PricingRules
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.style.TextOverflow
import app.solochat.ui.SettingsGroup
import app.solochat.core.model.*
import java.util.UUID

/** 所有操作只更新父页面草稿；持久化由价格页统一“保存”。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceTableEditor(catalog: PriceCatalog, currentModel: String, useTable: Boolean,
    bundledProviders: Map<String, String>, onChange: (PriceCatalog) -> Unit) {
    val table = catalog.tables.first { it.id == catalog.selectedTableId }
    var menu by remember { mutableStateOf(false) }
    var manageMenu by remember { mutableStateOf(false) }
    var management by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var providerFilter by rememberSaveable { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }
    var providerSearch by rememberSaveable { mutableStateOf("") }
    var newModelSeed by rememberSaveable { mutableStateOf("") }
    fun provider(entry: ModelPriceEntry): String = entry.provider.ifBlank {
        if (entry.source.startsWith("随包快照：")) bundledProviders[entry.model].orEmpty() else ""
    }.ifBlank { "未分类" }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    fun replace(updated: PriceTable) = onChange(catalog.copy(tables = catalog.tables.map { if (it.id == table.id) updated else it }))

    Text("价格表", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(table.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                catalog.tables.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = {
                        onChange(catalog.copy(selectedTableId = item.id)); menu = false; search = ""; providerFilter = ""
                    })
                }
            }
        }
        Box {
            IconButton(onClick = { manageMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "管理价格表")
            }
            DropdownMenu(expanded = manageMenu, onDismissRequest = { manageMenu = false }) {
                listOf("新增", "复制", "重命名", "删除").forEach { action ->
                    DropdownMenuItem(text = { Text("${action}价格表") },
                        enabled = action != "删除" || catalog.tables.size > 1,
                        onClick = {
                            management = action
                            name = when (action) {
                                "复制" -> "${table.name}副本"
                                "重命名" -> table.name
                                else -> ""
                            }
                            error = ""; manageMenu = false
                        })
                }
            }
        }
    }
    Text("金额单位：元 / 百万 token。快照可能过时，可按实际渠道修改；保存后仅影响后续调用。",
        style = MaterialTheme.typography.bodySmall)
    val matched = PricingRules.matchedModelId(table.entries.map { it.model }.toSet(), currentModel)
    val currentEntry = table.entries.firstOrNull { it.model == matched }
    SettingsGroup("当前模型 · 全局选择") {
        Text(currentModel.ifBlank { "尚未选择模型" }, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        Text(when {
            !useTable -> "价格表未启用，当前使用下方全局自填价。"
            currentEntry == null -> "此表未匹配，当前使用下方全局自填价；未配置时费用未知。"
            matched != currentModel.trim().lowercase() -> "按快照后缀匹配 $matched。"
            else -> "匹配所选价格表。"
        }, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (currentEntry != null) PriceEntryRow(currentEntry, provider(currentEntry)) { editing = currentEntry.model }
        else TextButton(enabled = currentModel.isNotBlank(), onClick = {
            newModelSeed = currentModel.trim().lowercase(); editing = ""
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("为当前模型配置价格与峰谷") }
    }
    OutlinedButton(onClick = { browsing = true }, modifier = Modifier.fillMaxWidth()) {
        Text("全部模型价格 · ${table.entries.size} 项", Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    Text("会话单独选择的模型可在全部模型中查找；表内编辑先写入本页草稿，保存后才生效。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (browsing) Dialog(onDismissRequest = { browsing = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(title = "全部模型价格", onBack = { browsing = false }) }) { padding ->
            val filtered = table.entries.filter {
                it.model.contains(search.trim(), ignoreCase = true) &&
                    (providerFilter.isBlank() || provider(it) == providerFilter)
            }.sortedBy { it.model }
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("${table.name} · 编辑完成后，仍需在价格页保存。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(search, { search = it }, label = { Text("查找模型") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { providerSearch = ""; providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (providerFilter.isBlank()) "全部归属 / 服务商" else providerFilter,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { newModelSeed = ""; editing = "" }) { Text("添加模型与自定义价格") }
                }
                if (filtered.isEmpty()) item { Text("没有匹配的模型，可调整筛选或添加条目。") }
                filtered.groupBy(::provider).toSortedMap().forEach { (group, entries) ->
                    item(key = "group:$group") { Text("$group · ${entries.size}", Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(entries, key = { "model:${it.model}" }) { entry ->
                        PriceEntryRow(entry, group) { editing = entry.model }
                    }
                }
            }
        }
    }

    if (providerMenu) ModalBottomSheet(onDismissRequest = { providerMenu = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型归属 / 服务商", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(providerSearch, { providerSearch = it }, label = { Text("搜索归属") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val providers = table.entries.map(::provider).distinct().sorted().filter { it.contains(providerSearch.trim(), true) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(listOf("") + providers, key = { it }) { name ->
                    ListItem(modifier = Modifier.clickable { providerFilter = name; providerMenu = false },
                        leadingContent = { RadioButton(selected = providerFilter == name, onClick = null) },
                        headlineContent = { Text(name.ifBlank { "全部归属 / 服务商" }) })
                }
            }
        }
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
            management = ""; providerFilter = ""
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { management = "" }) { Text("取消") } })

    editing?.let { key ->
        val entry = table.entries.firstOrNull { it.model == key } ?: ModelPriceEntry(newModelSeed)
        val original = entry.copy(provider = provider(entry).takeUnless { it == "未分类" }.orEmpty())
        ModelPriceEditorScreen(original, tableName = table.name, isNew = key.isEmpty(), onDismiss = { editing = null }, onApply = { entry ->
            require(table.entries.none { it.model == entry.model && it.model != key }) { "该模型已存在，请编辑已有条目" }
            replace(table.copy(entries = table.entries.filterNot { it.model == key } + entry))
            editing = null
        }, onDelete = { replace(table.copy(entries = table.entries.filterNot { it.model == key })); editing = null })
    }
}

@Composable
private fun PriceEntryRow(entry: ModelPriceEntry, provider: String, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.model, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("$provider · 输入 ${entry.standard.input} · 输出 ${entry.standard.output}\n" +
                "缓存 ${entry.standard.cached?.toString() ?: "随输入价"} · ${if (entry.peakEnabled) "峰谷已开" else "峰谷关闭"}",
                style = MaterialTheme.typography.bodySmall)
        }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
}
