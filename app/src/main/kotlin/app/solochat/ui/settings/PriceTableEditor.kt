package app.solochat.ui.settings

import androidx.compose.foundation.clickable
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
@Composable
fun PriceTableEditor(catalog: PriceCatalog, onChange: (PriceCatalog) -> Unit) {
    val table = catalog.tables.first { it.id == catalog.selectedTableId }
    var menu by remember { mutableStateOf(false) }
    var manageMenu by remember { mutableStateOf(false) }
    var management by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(0) }
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
                        onChange(catalog.copy(selectedTableId = item.id)); menu = false; page = 0; search = ""
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
    OutlinedTextField(search, onValueChange = { search = it; page = 0 }, label = { Text("查找模型") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { editing = "" }) { Text("添加模型与自定义价格") }
    val filtered = table.entries.filter { it.model.contains(search.trim(), ignoreCase = true) }.sortedBy { it.model }
    val currentPage = page.coerceIn(0, ((filtered.size - 1).coerceAtLeast(0) / 15))
    SettingsGroup("模型价格 · ${table.entries.size} 项") {
        filtered.drop(currentPage * 15).take(15).forEach { entry ->
            ListItem(modifier = Modifier.clickable { editing = entry.model },
                headlineContent = { Text(entry.model, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("输入 ${entry.standard.input} · 输出 ${entry.standard.output}\n" +
                        "缓存 ${entry.standard.cached?.toString() ?: "随输入价"} · ${if (entry.peakEnabled) "峰谷已开" else "峰谷关闭"}",
                        style = MaterialTheme.typography.bodySmall)
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
        }
        if (filtered.isEmpty()) Text(
            if (table.entries.isEmpty()) "这张表还没有模型，点击上方添加自定义价格。" else "没有匹配的模型，试试其他关键词。",
            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
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
        ModelPriceEditorScreen(original, tableName = table.name, isNew = key.isEmpty(), onDismiss = { editing = null }, onApply = { entry ->
            require(table.entries.none { it.model == entry.model && it.model != key }) { "该模型已存在，请编辑已有条目" }
            replace(table.copy(entries = table.entries.filterNot { it.model == key } + entry))
            editing = null
        }, onDelete = { replace(table.copy(entries = table.entries.filterNot { it.model == key })); editing = null })
    }
}
