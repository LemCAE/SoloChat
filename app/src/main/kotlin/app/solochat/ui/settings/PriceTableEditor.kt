package app.solochat.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.solochat.ui.ToolTopBar
import app.solochat.data.pricing.PricingRules
import app.solochat.data.pricing.ReferencePrices
import androidx.compose.ui.platform.LocalUriHandler
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
    modelChoices: List<String>, onChange: (PriceCatalog) -> Unit) {
    val table = catalog.tables.first { it.id == catalog.selectedTableId }
    var menu by remember { mutableStateOf(false) }
    var manageMenu by remember { mutableStateOf(false) }
    var management by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var managing by rememberSaveable { mutableStateOf(false) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var choiceQuery by rememberSaveable { mutableStateOf("") }
    var visibleCount by rememberSaveable(search, catalog.selectedTableId) { mutableStateOf(30) }
    var newModelSeed by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf(false) }
    fun provider(entry: ModelPriceEntry): String = entry.provider.ifBlank { "未分类" }
    val uriHandler = LocalUriHandler.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    fun replace(updated: PriceTable) = onChange(catalog.copy(tables = catalog.tables.map { if (it.id == table.id) updated else it }))

    Text("${table.name} · ${table.entries.size} 项模型价格", style = MaterialTheme.typography.titleMedium)
    Text("元 / 百万 token · 编辑先进入本页草稿，保存后只影响后续调用。",
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { managing = true }) { Text("价格表管理 · 切换 / 导入 / 导出") }
    if (managing) Dialog(onDismissRequest = { managing = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar("价格表管理", onBack = { managing = false }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    onChange(catalog.copy(selectedTableId = item.id)); menu = false; search = ""
                                })
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { manageMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "管理价格表")
                        }
                        DropdownMenu(expanded = manageMenu, onDismissRequest = { manageMenu = false }) {
                            listOf("复制", "重命名", "删除").forEach { action ->
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { management = "新增"; name = ""; error = "" }) { Text("新建价格表") }

                }
                PriceTableTransferControls(table, onImport = { onChange(PriceTableTransfer.append(catalog, it)) })
                TextButton(onClick = { reference = true }) { Text("查看官方参考资料（非实时）") }
                if (table.id == "bundled") Text("此表由旧版保留在本组，可继续编辑或删除；新组不会自动引用。随包资料已作为独立参考快照。",
                    style = MaterialTheme.typography.bodySmall)
                Text("每张表只属于当前接口组。金额单位：元 / 百万 token；保存后仅影响后续调用。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val matched = PricingRules.matchedModelId(table.entries.map { it.model }.toSet(), currentModel)
    val currentEntry = table.entries.firstOrNull { it.model == matched }
    SettingsGroup("当前模型 · 本组默认") {
        Text(currentModel.ifBlank { "尚未选择模型" }, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        Text(when {
            !useTable -> "价格表未启用，当前使用本组可选兜底价。"
            currentEntry == null -> "此表未匹配，当前使用本组可选兜底价；未配置时费用未知。"
            matched != currentModel.trim().lowercase() -> "按快照后缀匹配 $matched。"
            else -> "匹配所选价格表。"
        }, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (currentEntry != null) PriceEntryRow(currentEntry, provider(currentEntry)) { editing = currentEntry.model }
        else TextButton(enabled = currentModel.isNotBlank(), onClick = {
            newModelSeed = currentModel.trim().lowercase(); editing = ""
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("编辑当前模型价格") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { newModelSeed = ""; editing = "" }) { Text("手动添加价格") }
        OutlinedButton(onClick = { choosingModel = true; choiceQuery = "" }) { Text("从本组目录选择") }
    }
    OutlinedTextField(search, { search = it }, label = { Text("查找模型价格") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    val filtered = table.entries.filter { it.model.contains(search.trim(), true) }.sortedBy { it.model }
    if (filtered.isEmpty()) Text("暂无匹配价格，可手动添加或从本组模型目录选择。")
    filtered.take(visibleCount).forEach { entry ->
        PriceEntryRow(entry, provider(entry)) { editing = entry.model }
    }
    if (filtered.size > visibleCount) TextButton(onClick = { visibleCount += 30 }) {
        Text("显示更多（已显示 $visibleCount / ${filtered.size}）")
    }
    if (choosingModel) Dialog(onDismissRequest = { choosingModel = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar("从本组模型目录选择", onBack = { choosingModel = false }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                contentPadding = PaddingValues(16.dp)) {
                item {
                    Text("目录只来自当前组；选择后编辑本表价格，不改变默认模型。")
                    OutlinedTextField(choiceQuery, { choiceQuery = it }, label = { Text("查找模型") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val choices = (modelChoices + currentModel).map { it.trim().lowercase() }
                    .filter { it.isNotBlank() && it.contains(choiceQuery.trim(), true) }.distinct().sorted()
                if (choices.isEmpty()) item { Text("本组暂无匹配目录项，请在接口页获取目录或手动添加。") }
                items(choices, key = { it }) { id ->
                    ListItem(modifier = Modifier.clickable {
                        newModelSeed = id
                        editing = id.takeIf { key -> table.entries.any { it.model == key } } ?: ""
                        choosingModel = false
                    }, headlineContent = { Text(id) }, supportingContent = {
                        Text(if (table.entries.any { it.model == id }) "编辑已有价格" else "新增此模型价格")
                    })
                }
            }
        }
    }

    if (reference) Dialog(onDismissRequest = { reference = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar("参考快照 · 只读", onBack = { reference = false }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("参考资料独立于本组实价；不会按模型名或地址自动套价，不会替换旧表。") }
                ReferencePrices.sheets.forEach { sheet ->
                    item(key = sheet.name) {
                        Text(sheet.name, style = MaterialTheme.typography.titleMedium)
                        Text("核对日期：${sheet.checkedAt} · 非实时报价", style = MaterialTheme.typography.bodySmall)
                        Text(sheet.details)
                        sheet.sources.forEachIndexed { index, url ->
                            TextButton(onClick = { uriHandler.openUri(url) }) { Text("官方来源 ${index + 1}") }
                        }
                        sheet.copyableTable?.let { source ->
                            Button(onClick = {
                                val copied = source.copy(id = UUID.randomUUID().toString(), name = "${source.name}副本（请核价）")
                                onChange(catalog.copy(selectedTableId = copied.id, tables = catalog.tables + copied))
                                reference = false
                            }) { Text("复制上述可用条目到本组草稿") }
                        }
                        if (sheet.copyableTable == null) Text("当前仅供查阅，不能自动用于计费。")
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    }
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
            management = ""
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
private fun PriceEntryRow(entry: ModelPriceEntry, provider: String, onClick: (() -> Unit)? = null) {
    ListItem(modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.model, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("$provider · " + (if (entry.tiers.isEmpty()) "输入 ${entry.standard.input} · 输出 ${entry.standard.output}" else "输入长度阶梯 ${entry.tiers.size} 档") + "\n" +
                (if (entry.tiers.isEmpty()) "缓存 ${entry.standard.cached?.toString() ?: "随输入价"}" else "缓存随各档配置") + " · ${if (entry.peakEnabled) "峰谷已开" else "峰谷关闭"}",
                style = MaterialTheme.typography.bodySmall)
        }, trailingContent = { if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
}
