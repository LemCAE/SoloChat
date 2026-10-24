package app.solochat.ui.settings

import app.solochat.R
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
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
    fun provider(entry: ModelPriceEntry): String = entry.provider.ifBlank { stringsContext.getString(R.string.ui_pricetableeditor_eb6a3474) }
    val uriHandler = LocalUriHandler.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    fun replace(updated: PriceTable) = onChange(catalog.copy(tables = catalog.tables.map { if (it.id == table.id) updated else it }))

    Text(stringsContext.getString(R.string.ui_pricetableeditor_891a0e09, table.name, table.entries.size), style = MaterialTheme.typography.titleMedium)
    Text(stringsContext.getString(R.string.ui_pricetableeditor_0dbce54a),
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { managing = true }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_18ae07c3)) }
    if (managing) Dialog(onDismissRequest = { managing = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(stringsContext.getString(R.string.ui_pricetableeditor_a52da246), onBack = { managing = false }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringsContext.getString(R.string.ui_pricetableeditor_862bf892), style = MaterialTheme.typography.titleMedium)
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
                            Icon(Icons.Default.MoreVert, contentDescription = stringsContext.getString(R.string.ui_pricetableeditor_1d5b25ed))
                        }
                        DropdownMenu(expanded = manageMenu, onDismissRequest = { manageMenu = false }) {
                            listOf(stringsContext.getString(R.string.ui_pricetableeditor_4bb9ae89), stringsContext.getString(R.string.ui_pricetableeditor_799054cd), stringsContext.getString(R.string.ui_pricetableeditor_b59c7514)).forEach { action ->
                                DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_pricetableeditor_dee1e1df, action)) },
                                    enabled = action != stringsContext.getString(R.string.ui_pricetableeditor_b59c7514) || catalog.tables.size > 1,
                                    onClick = {
                                        management = action
                                        name = when (action) {
                                            stringsContext.getString(R.string.ui_pricetableeditor_4bb9ae89) -> stringsContext.getString(R.string.ui_pricetableeditor_cc63e4d2, table.name)
                                            stringsContext.getString(R.string.ui_pricetableeditor_799054cd) -> table.name
                                            else -> ""
                                        }
                                        error = ""; manageMenu = false
                                    })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { management = stringsContext.getString(R.string.ui_pricetableeditor_a111b6b0); name = ""; error = "" }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_819fa0ec)) }

                }
                PriceTableTransferControls(table, onImport = { onChange(PriceTableTransfer.append(catalog, it)) })
                TextButton(onClick = { reference = true }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_3c657416)) }
                if (table.id == "bundled") Text(stringsContext.getString(R.string.ui_pricetableeditor_2601445d),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringsContext.getString(R.string.ui_pricetableeditor_e3388dd8),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val matched = PricingRules.matchedModelId(table.entries.map { it.model }.toSet(), currentModel)
    val currentEntry = table.entries.firstOrNull { it.model == matched }
    SettingsGroup(stringsContext.getString(R.string.ui_pricetableeditor_c8b13e36)) {
        Text(currentModel.ifBlank { stringsContext.getString(R.string.ui_pricetableeditor_70382e2d) }, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        val matchStatus = when {
            !useTable -> stringsContext.getString(R.string.ui_pricetableeditor_b9c228fc)
            currentEntry == null -> stringsContext.getString(R.string.ui_pricetableeditor_53d255d8)
            matched != currentModel.trim().lowercase() -> stringsContext.getString(R.string.ui_pricetableeditor_8238d304, matched)
            else -> null
        }
        if (matchStatus != null) Text(matchStatus, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (currentEntry != null) PriceEntryRow(currentEntry, provider(currentEntry)) { editing = currentEntry.model }
        else TextButton(enabled = currentModel.isNotBlank(), onClick = {
            newModelSeed = currentModel.trim().lowercase(); editing = ""
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringsContext.getString(R.string.ui_pricetableeditor_da497e41)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { newModelSeed = ""; editing = "" }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_d64c8d08)) }
        OutlinedButton(onClick = { choosingModel = true; choiceQuery = "" }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_36c82d0e)) }
    }
    OutlinedTextField(search, { search = it }, label = { Text(stringsContext.getString(R.string.ui_pricetableeditor_77c69373)) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    val filtered = table.entries.filter { it.model.contains(search.trim(), true) }.sortedBy { it.model }
    if (filtered.isEmpty()) Text(stringsContext.getString(R.string.ui_pricetableeditor_f4f5f66e))
    filtered.take(visibleCount).forEach { entry ->
        PriceEntryRow(entry, provider(entry)) { editing = entry.model }
    }
    if (filtered.size > visibleCount) TextButton(onClick = { visibleCount += 30 }) {
        Text(stringsContext.getString(R.string.ui_pricetableeditor_bdd5f474, visibleCount, filtered.size))
    }
    if (choosingModel) Dialog(onDismissRequest = { choosingModel = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(stringsContext.getString(R.string.ui_pricetableeditor_088691d0), onBack = { choosingModel = false }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                contentPadding = PaddingValues(16.dp)) {
                item {
                    Text(stringsContext.getString(R.string.ui_pricetableeditor_945d0812))
                    OutlinedTextField(choiceQuery, { choiceQuery = it }, label = { Text(stringsContext.getString(R.string.ui_pricetableeditor_9a8a6f33)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val choices = (modelChoices + currentModel).map { it.trim().lowercase() }
                    .filter { it.isNotBlank() && it.contains(choiceQuery.trim(), true) }.distinct().sorted()
                if (choices.isEmpty()) item { Text(stringsContext.getString(R.string.ui_pricetableeditor_0a20d928)) }
                items(choices, key = { it }) { id ->
                    ListItem(modifier = Modifier.clickable {
                        newModelSeed = id
                        editing = id.takeIf { key -> table.entries.any { it.model == key } } ?: ""
                        choosingModel = false
                    }, headlineContent = { Text(id) }, supportingContent = {
                        Text(if (table.entries.any { it.model == id }) stringsContext.getString(R.string.ui_pricetableeditor_811de9b5) else stringsContext.getString(R.string.ui_pricetableeditor_da637057))
                    })
                }
            }
        }
    }

    if (reference) Dialog(onDismissRequest = { reference = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { ToolTopBar(stringsContext.getString(R.string.ui_pricetableeditor_61f91a55), onBack = { reference = false }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringsContext.getString(R.string.ui_pricetableeditor_81430dee)) }
                ReferencePrices.sheets.forEach { sheet ->
                    item(key = sheet.name) {
                        Text(sheet.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringsContext.getString(R.string.ui_pricetableeditor_3e3c0f88, sheet.checkedAt), style = MaterialTheme.typography.bodySmall)
                        Text(sheet.details)
                        sheet.sources.forEachIndexed { index, url ->
                            TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_4b91eaf8, index + 1)) }
                        }
                        sheet.copyableTable?.let { source ->
                            Button(onClick = {
                                val copied = source.copy(id = UUID.randomUUID().toString(), name = stringsContext.getString(R.string.ui_pricetableeditor_35be7701, source.name))
                                onChange(catalog.copy(selectedTableId = copied.id, tables = catalog.tables + copied))
                                reference = false
                            }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_ee51dab7)) }
                        }
                        if (sheet.copyableTable == null) Text(stringsContext.getString(R.string.ui_pricetableeditor_efccf9b5))
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }

    if (management.isNotEmpty()) AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = { management = "" },
        title = { Text(stringsContext.getString(R.string.ui_pricetableeditor_dee1e1df, management)) },
        text = { Column {
            if (management == stringsContext.getString(R.string.ui_pricetableeditor_b59c7514)) Text(stringsContext.getString(R.string.ui_pricetableeditor_899277fa, table.name))
            else OutlinedTextField(name, onValueChange = { name = it }, label = { Text(stringsContext.getString(R.string.ui_pricetableeditor_019b5747)) }, singleLine = true)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            if (management != stringsContext.getString(R.string.ui_pricetableeditor_b59c7514) && name.isBlank()) { error = stringsContext.getString(R.string.ui_pricetableeditor_9433d10e); return@TextButton }
            when (management) {
                stringsContext.getString(R.string.ui_pricetableeditor_b59c7514) -> {
                    val remaining = catalog.tables.filterNot { it.id == table.id }
                    onChange(catalog.copy(selectedTableId = remaining.first().id, tables = remaining))
                }
                stringsContext.getString(R.string.ui_pricetableeditor_799054cd) -> replace(table.copy(name = name.trim()))
                else -> {
                    val added = PriceTable(UUID.randomUUID().toString(), name.trim(), if (management == stringsContext.getString(R.string.ui_pricetableeditor_4bb9ae89)) table.entries else emptyList())
                    onChange(catalog.copy(selectedTableId = added.id, tables = catalog.tables + added))
                }
            }
            management = ""
        }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_acd89ecc)) } }, dismissButton = { TextButton(onClick = { management = "" }) { Text(stringsContext.getString(R.string.ui_pricetableeditor_65a6ca9f)) } })

    editing?.let { key ->
        val entry = table.entries.firstOrNull { it.model == key } ?: ModelPriceEntry(newModelSeed)
        val original = entry.copy(provider = provider(entry).takeUnless { it == stringsContext.getString(R.string.ui_pricetableeditor_eb6a3474) }.orEmpty())
        ModelPriceEditorScreen(original, tableName = table.name, isNew = key.isEmpty(), onDismiss = { editing = null }, onApply = { entry ->
            require(table.entries.none { it.model == entry.model && it.model != key }) { stringsContext.getString(R.string.ui_pricetableeditor_8d4891e0) }
            replace(table.copy(entries = table.entries.filterNot { it.model == key } + entry))
            editing = null
        }, onDelete = { replace(table.copy(entries = table.entries.filterNot { it.model == key })); editing = null })
    }
}

@Composable
private fun PriceEntryRow(entry: ModelPriceEntry, provider: String, onClick: (() -> Unit)? = null) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    ListItem(modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.model, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("$provider · " + (if (entry.tiers.isEmpty()) stringsContext.getString(R.string.ui_pricetableeditor_b4689403, entry.standard.input, entry.standard.output) else stringsContext.getString(R.string.ui_pricetableeditor_2cb600d1, entry.tiers.size)) + "\n" +
                (if (entry.tiers.isEmpty()) stringsContext.getString(R.string.ui_pricetableeditor_88e131ab, entry.standard.cached?.toString() ?: stringsContext.getString(R.string.ui_price_cache_follows)) else stringsContext.getString(R.string.ui_pricetableeditor_2a674765)) + stringsContext.getString(R.string.ui_pricetableeditor_1ecc43f3, if (entry.peakEnabled) stringsContext.getString(R.string.ui_price_peak_enabled) else stringsContext.getString(R.string.ui_price_peak_disabled)),
                style = MaterialTheme.typography.bodySmall)
        }, trailingContent = { if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) })
}
