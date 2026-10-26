package app.solochat.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.solochat.SoloChatApp
import app.solochat.core.model.*
import app.solochat.data.ModelRegistryDocument
import app.solochat.data.pricing.*
import app.solochat.ui.SettingsFormGroup
import app.solochat.ui.ToolTopBar
import kotlinx.coroutines.launch
import java.util.UUID

/** Provider 报价页；所有模型编辑先进入本页草稿，唯一生效入口是父页“保存报价”。 */
@Composable
internal fun ProviderPricingSettingsScreen(
    initial: ModelRegistryDocument,
    providerId: String,
    onDismiss: () -> Unit,
    onPersisted: (ModelRegistryDocument) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf(initial) }
    var providerIdState by rememberSaveable { mutableStateOf(providerId) }
    val provider = document.providers.firstOrNull { it.id == providerIdState }
    var pricing by remember { mutableStateOf(initial.providers.firstOrNull { it.id == providerId }?.pricing ?: ProviderPricing()) }
    var draftCache by remember { mutableStateOf(emptyMap<String, ProviderPricing>()) }
    var dirtyProviders by remember { mutableStateOf(emptySet<String>()) }
    var migration by remember { mutableStateOf<ProviderPricingMigrationPlan?>(null) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    var referenceItem by remember { mutableStateOf<ReferencePriceEntry?>(null) }
    var showReferences by remember { mutableStateOf(false) }
    var referenceChannelConfirmed by remember { mutableStateOf(false) }
    var referenceConditionsConfirmed by remember { mutableStateOf(false) }
    var confirmReference by remember { mutableStateOf(false) }
    var importDocument by remember { mutableStateOf<PriceExchangeDocument?>(null) }
    var importSelections by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var importUnassignedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var overwriteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedUnassigned by remember { mutableStateOf<String?>(null) }
    var selectingUnassignedModel by remember { mutableStateOf<String?>(null) }
    var unassignedTargets by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmUnassignedLink by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }
    var unpricedExpanded by rememberSaveable { mutableStateOf(false) }
    var unassignedExpanded by rememberSaveable { mutableStateOf(false) }

    val createExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && provider != null) runCatching {
            val active = pricing.bindings.map { (modelId, bound) ->
                PriceExchangeEntry(UUID.randomUUID().toString(), bound.requestModelId,
                    document.models.firstOrNull { it.id == modelId }?.displayName, bound.rule,
                bound.origin.toExchange(), bound.conditions.toExchange())
            }
            val orphaned = pricing.unassigned.map { item ->
                PriceExchangeEntry(item.id, item.requestModelId, rule = item.rule,
                    origin = item.origin.toExchange(), conditions = item.conditions.toExchange(), unassignedReason = item.reason.name)
            }
            val legacyCatalog = pricing.legacyArchive?.legacy?.priceCatalog
            val bundledCatalog = pricing.legacyArchive?.bundledSnapshot
            val archive = pricing.legacyArchive?.let { legacyArchive -> PriceExchangeArchive(
                "旧配置存档（只读）", catalogPresent = legacyCatalog != null,
                selectedTableFileId = legacyCatalog?.let { catalog -> catalog.tables.indexOfFirst { it.id == catalog.selectedTableId }.takeIf { it >= 0 }?.let { "legacy-table-$it" } },
                tables = legacyCatalog?.let { catalog -> catalog.tables.mapIndexed { index, table ->
                    PriceExchangeTable("legacy-table-$index", table.name, table.id == catalog.selectedTableId, table.entries, "LEGACY")
                } }.orEmpty() + bundledCatalog?.let { catalog -> catalog.tables.mapIndexed { index, table ->
                    PriceExchangeTable("bundled-table-$index", table.name, table.id == catalog.selectedTableId, table.entries, "BUNDLED")
                } }.orEmpty(),
                legacyPricing = legacyArchive.legacy.let { legacy -> ExchangeLegacyPricing(
                    legacy.useOfficialPrice, legacy.priceInput, legacy.priceOutput, legacy.priceCached,
                    legacy.priceConfigured, legacy.cachedPriceConfigured) },
                bundledCatalogPresent = bundledCatalog != null,
                bundledSelectedTableFileId = bundledCatalog?.let { catalog -> catalog.tables.indexOfFirst { it.id == catalog.selectedTableId }.takeIf { it >= 0 }?.let { "bundled-table-$it" } },
                source = "原 Provider 价格配置；本文件只读，不自动成为目标生效价"
            ) }
            val text = PriceExchangeTransfer.encode(PriceExchangeDocument(name = provider.name, entries = active,
                unassigned = orphaned, legacyArchive = archive, archives = pricing.importedArchives))
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error("无法写入所选文件")
        }.onFailure { error = it.message ?: "导出失败" }
    }
    val openImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { loadExchangeDocument(context, uri) }
            .onSuccess { importDocument = it; importSelections = emptyMap(); importUnassignedIds = emptySet(); overwriteIds = emptySet(); confirmImport = true }
            .onFailure { error = it.message ?: "导入失败" }
    }

    fun leave() { if (busy) return else if (dirty) confirmLeave = true else onDismiss() }
    fun saveDraft() {
        val target = provider ?: return
        if (busy) return
        val submittedRevision = document.revision
        val submittedPricing = pricing
        busy = true; error = ""
        scope.launch {
            runCatching {
                app.saveModelPricing(target.id, submittedRevision, upserts = submittedPricing.bindings,
                    removes = (target.pricing?.bindings.orEmpty().keys - submittedPricing.bindings.keys), unassigned = submittedPricing.unassigned,
                    importedArchives = submittedPricing.importedArchives)
            }.onSuccess {
                document = it; dirty = false; dirtyProviders = dirtyProviders - target.id
                draftCache = draftCache + (target.id to submittedPricing); onPersisted(it)
            }
                .onFailure { error = it.message ?: "保存失败" }
            busy = false
        }
    }

    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            ToolTopBar("模型报价", onBack = { leave() }, actions = {
                if (provider?.pricing != null) IconButton(onClick = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) modelSearch = ""
                }) {
                    Icon(if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchExpanded) "关闭模型搜索" else "搜索模型")
                }
                TextButton(enabled = !busy && dirty, onClick = { saveDraft() }) { Text("保存报价") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp,
                    bottom = 72.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (document.providers.size > 1) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(provider?.name ?: "选择提供商", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded, { expanded = false }) { document.providers.forEach { item ->
                            DropdownMenuItem(enabled = !busy && !dirty, text = { Text(item.name) }, onClick = {
                                draftCache = draftCache + (providerIdState to pricing)
                                if (dirty) dirtyProviders = dirtyProviders + providerIdState
                                providerIdState = item.id
                                pricing = draftCache[item.id] ?: item.pricing ?: ProviderPricing()
                                dirty = item.id in dirtyProviders
                                modelSearch = ""; searchExpanded = false; unpricedExpanded = false
                                expanded = false
                            })
                        } }
                    }
                    if (dirty) Text("保存报价或离开后再切换提供商。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (searchExpanded && provider?.pricing != null) OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索模型") },
                    placeholder = { Text("名称或请求 ID") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (modelSearch.isNotBlank()) IconButton(onClick = { modelSearch = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                        }
                    }
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (provider == null) Text("提供商已移除，请返回重新载入。", color = MaterialTheme.colorScheme.error)
                else if (provider.pricing == null) {
                    val plan = remember(document, provider.id) { ProviderPricingMigrationPlan.create(document, provider.id, app.pricing.initialCatalog) }
                    Text("旧价格整理预览", style = MaterialTheme.typography.titleMedium)
                    Text("${plan.bindings.size} 个可绑定 · ${plan.unknown.size} 个未知 · ${plan.unassigned.size} 个未关联 · 旧表与兜底完整存档")
                    plan.unknown.forEach { Text("未知 · ${it.requestModelId}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("整理仅生成新报价与只读存档，不删除或改写旧价格。确认后立即保存此提供商报价。", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch { runCatching { app.commitPricingMigration(plan) }
                            .onSuccess {
                                document = it; pricing = it.providers.first { p -> p.id == provider.id }.pricing ?: ProviderPricing()
                                draftCache = draftCache + (provider.id to pricing); dirtyProviders = dirtyProviders - provider.id
                                onPersisted(it)
                            }
                            .onFailure { error = it.message ?: "整理失败" }; busy = false }
                    }) { Text("确认整理") }
                } else {
                    Text("${provider.name} · CNY / 百万 token", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val query = modelSearch.trim()
                    val matchingModels = document.models.filter { model ->
                        model.providerId == provider.id && (query.isBlank() ||
                            model.displayName.contains(query, ignoreCase = true) ||
                            model.requestModelId.contains(query, ignoreCase = true))
                    }
                    val pricedModels = matchingModels.filter { it.id in pricing.bindings }
                    val unpricedModels = matchingModels.filterNot { it.id in pricing.bindings }
                    val showUnpriced = unpricedExpanded || query.isNotBlank()
                    if (matchingModels.isEmpty()) Text(if (query.isBlank()) "当前提供商没有模型" else "没有匹配的模型",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsFormGroup("已设置 · ${pricedModels.size}") {
                        if (pricedModels.isEmpty()) Text("没有匹配的已设置报价", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        pricedModels.forEach { model ->
                            val bound = pricing.bindings.getValue(model.id)
                            ListItem(modifier = Modifier.clickable(enabled = !busy) { editingModel = model.id },
                                headlineContent = { Text(model.displayName) },
                                supportingContent = { Text("${bound.rule.standard.input} / ${bound.rule.standard.output} · ${model.requestModelId}",
                                    maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Text("编辑", color = MaterialTheme.colorScheme.primary) })
                        }
                    }
                    SettingsFormGroup("未设置 · ${unpricedModels.size}") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("未设置报价的模型", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(enabled = query.isBlank() && unpricedModels.isNotEmpty(), onClick = { unpricedExpanded = !unpricedExpanded }) {
                                Text(if (query.isNotBlank()) "搜索结果" else if (unpricedExpanded) "收起" else "展开")
                            }
                        }
                        if (showUnpriced) {
                            if (unpricedModels.isEmpty()) Text("没有匹配的未设置报价", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            unpricedModels.forEach { model ->
                                ListItem(modifier = Modifier.clickable(enabled = !busy) { editingModel = model.id },
                                    headlineContent = { Text(model.displayName) },
                                    supportingContent = { Text("未知价格 · ${model.requestModelId}", maxLines = 2,
                                        overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { Text("未设置", color = MaterialTheme.colorScheme.onSurfaceVariant) })
                            }
                        }
                    }
                    TextButton(enabled = !busy, onClick = { openImport.launch(arrayOf("*/*")) }) { Text("导入报价") }
                    TextButton(enabled = !busy, onClick = { createExport.launch("${provider.name}-prices-v3.json") }) { Text("导出 v3") }
                    if (pricing.unassigned.isNotEmpty()) {
                        SettingsFormGroup("未关联报价 · ${pricing.unassigned.size}") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("旧请求 ID 或来源未匹配的报价", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { unassignedExpanded = !unassignedExpanded }) {
                                    Text(if (unassignedExpanded) "收起" else "展开")
                                }
                            }
                            if (unassignedExpanded) pricing.unassigned.forEach { item ->
                                var expanded by remember(item.id) { mutableStateOf(false) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${item.requestModelId} · ${item.reason}", Modifier.weight(1f))
                                    TextButton(enabled = !busy && item.rule != null, onClick = { expanded = !expanded }) {
                                        Text(if (expanded) "收起" else "查看")
                                    }
                                }
                                if (expanded) {
                                    item.rule?.let { RulePreview(it) }
                                    Text("来源：${item.origin.channel ?: item.origin.kind} · ${item.origin.checkedAt.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                    Text("条件：${item.conditions.thinkingMode ?: "不限思考模式"} · ${if (item.conditions.connectionFingerprint == null) "未绑定连接" else "绑定了连接指纹"}", style = MaterialTheme.typography.bodySmall)
                                    val targetId = unassignedTargets[item.id]
                                    OutlinedButton(enabled = !busy, onClick = { selectingUnassignedModel = item.id }) {
                                        Text(targetId?.let { id -> document.models.firstOrNull { it.id == id }?.displayName } ?: "选择模型")
                                    }
                                    TextButton(enabled = !busy && targetId != null, onClick = { selectedUnassigned = item.id; confirmUnassignedLink = true }) {
                                        Text(if (targetId != null && targetId in pricing.bindings) "预览并替换" else "关联到模型")
                                    }
                                }
                            }
                        }
                    }
                    pricing.legacyArchive?.let { archive ->
                        var show by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { show = !show }) { Text("${if (show) "收起" else "查看"}旧价存档") }
                        if (show) SettingsFormGroup("旧配置只读存档") {
                            Text("旧价格目录：${archive.legacy.priceCatalog?.tables?.size ?: 0} 张")
                            Text("旧价已启用：${archive.legacy.useOfficialPrice} · 自填已配置：${archive.legacy.priceConfigured} · 输入/输出/缓存 ${archive.legacy.priceInput}/${archive.legacy.priceOutput}/${archive.legacy.priceCached}")
                            archive.legacy.priceCatalog?.tables?.forEach { table ->
                                Text("${table.name}${if (table.id == archive.legacy.priceCatalog.selectedTableId) " · 原选中" else ""} · ${table.entries.size} 项")
                                table.entries.forEach { RulePreview(it) }
                            }
                        }
                    }
                    pricing.importedArchives.forEach { archive ->
                        var showArchive by rememberSaveable(archive.name) { mutableStateOf(false) }
                        TextButton(enabled = !busy, onClick = { showArchive = !showArchive }) { Text("${if (showArchive) "收起" else "查看"}${archive.name} · 只读") }
                        if (showArchive) SettingsFormGroup("导入存档 · 不参与计价") {
                            Text("${archive.tables.size} 张表 · ${archive.tables.sumOf { it.entries.size }} 条规则")
                            archive.tables.forEach { table ->
                                Text("${table.name} · ${table.entries.size} 条")
                                table.entries.forEach { RulePreview(it) }
                            }
                        }
                    }
                    TextButton(enabled = !busy, onClick = { showReferences = true }) {
                        Text("参考库")
                    }
                }
            }
        }
    }

    selectingUnassignedModel?.let { itemId ->
        val item = pricing.unassigned.firstOrNull { it.id == itemId }
        if (item != null) {
            val candidates = document.models.filter { model ->
                model.providerId == providerIdState &&
                    (item.conditions.thinkingMode == null || model.thinkingMode == item.conditions.thinkingMode)
            }.sortedBy { it.requestModelId != item.requestModelId }
            AlertDialog(onDismissRequest = { selectingUnassignedModel = null },
                title = { Text("选择目标模型") },
                text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("可选择此提供商下符合报价条件的模型。请求 ID 不同也可以手动关联，确认前会显示完整映射。",
                        style = MaterialTheme.typography.bodySmall)
                    if (candidates.isEmpty()) {
                        Text("没有符合当前提供商和思考条件的模型。")
                    } else candidates.forEach { target ->
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            unassignedTargets = unassignedTargets + (item.id to target.id)
                            selectingUnassignedModel = null
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(target.displayName, modifier = Modifier.fillMaxWidth())
                                Text(target.requestModelId, modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { selectingUnassignedModel = null }) { Text("取消") } })
        }
    }

    editingModel?.let { modelId ->
        val model = document.models.first { it.id == modelId }
        val existing = pricing.bindings[modelId]
        val entry = existing?.rule ?: ModelPriceEntry(model.requestModelId.trim().lowercase(), PriceRates(0.0, 0.0))
        ModelPriceEditorScreen(entry, provider?.name.orEmpty(), existing == null, onDismiss = { editingModel = null },
            onApply = { updated ->
                pricing = pricing.copy(bindings = pricing.bindings + (modelId to BoundModelPrice(model.requestModelId, updated,
                    existing?.origin ?: PriceOrigin(PriceOriginKind.MANUAL), existing?.conditions ?: PriceConditions())))
                dirty = true; editingModel = null
            }, onDelete = { pricing = pricing.copy(bindings = pricing.bindings - modelId); dirty = true; editingModel = null },
            identityLocked = true)
    }

    referenceItem?.let { item ->
        if (item.adoptable && provider != null) {
            val target = document.models.filter { it.providerId == provider.id && it.requestModelId == item.requestModelId }
            val sheet = ReferencePrices.sheets.first { item in it.entries }
            val channelMatched = referenceChannelMatches(sheet.channelId, provider.baseUrl)
            val thinkingMatched = target.singleOrNull()?.thinkingMode == (item.thinkingMode ?: target.singleOrNull()?.thinkingMode)
            val calendarNeedsConfirmation = item.rule?.schedule?.calendar?.confirmed == false
            AlertDialog(onDismissRequest = { referenceItem = null }, title = { Text("参考报价 · ${item.requestModelId}") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${sheet.channelId} · ${sheet.region} · 核对 ${sheet.checkedAt}")
                    Text(item.conditions)
                    Text("来源：${item.rule?.source}")
                    TextButton(onClick = { runCatching { uriHandler.openUri(sheet.sources.first()) } }) { Text("打开官方来源") }
                    Text("当前：${target.firstOrNull()?.let { pricing.bindings[it.id]?.rule?.let { r -> "${r.standard.input}/${r.standard.output}" } } ?: "未设置"}")
                    Text("参考：${item.rule?.standard?.input}/${item.rule?.standard?.output}")
                    if (calendarNeedsConfirmation) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(referenceConditionsConfirmed, { referenceConditionsConfirmed = it })
                        Text("我已核对参考中的日期与渠道计费条件")
                    }
                    if (!channelMatched) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(referenceChannelConfirmed, { referenceChannelConfirmed = it })
                        Text("我已核对所选提供商与此渠道的价格适用范围")
                    }
                } },
                confirmButton = { TextButton(enabled = item.adoptable && target.size == 1 && thinkingMatched &&
                    (channelMatched || referenceChannelConfirmed) && (!calendarNeedsConfirmation || referenceConditionsConfirmed),
                    onClick = { confirmReference = true }) { Text("预览整条替换") } },
                dismissButton = { TextButton(onClick = { referenceItem = null }) { Text("关闭") } })
            if (confirmReference) AlertDialog(onDismissRequest = { confirmReference = false }, title = { Text("替换草稿报价？") },
                text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前报价", style = MaterialTheme.typography.titleSmall)
                    target.singleOrNull()?.let { current -> pricing.bindings[current.id]?.rule?.let { RulePreview(it) } } ?: Text("未设置")
                    Text("参考报价 · 整条替换", style = MaterialTheme.typography.titleSmall)
                    RulePreview(adoptReferenceRule(item, referenceConditionsConfirmed))
                    Text("仅进入本页草稿，保存报价后才生效。", style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(onClick = {
                    val model = target.single()
                    val adoptedRule = adoptReferenceRule(item, referenceConditionsConfirmed)
                    pricing = pricing.copy(bindings = pricing.bindings + (model.id to BoundModelPrice(model.requestModelId, adoptedRule,
                        PriceOrigin(PriceOriginKind.REFERENCE, sourceId = item.id, channel = sheet.channelId, checkedAt = sheet.checkedAt,
                            url = sheet.sources.first()), PriceConditions(item.thinkingMode,
                            ProviderPricingResolver.connectionFingerprint(provider)))))
                    dirty = true; confirmReference = false; referenceItem = null
                }) { Text("替换草稿") } }, dismissButton = { TextButton(onClick = { confirmReference = false }) { Text("取消") } })
        } else AlertDialog(onDismissRequest = { referenceItem = null }, title = { Text("仅供核对") },
            text = { Text(item.reasonReadOnly ?: "适用条件与规则不能确认，不能作为参考价采用。") },
            confirmButton = { TextButton(onClick = { referenceItem = null }) { Text("关闭") } })
    }

    if (showReferences) AlertDialog(onDismissRequest = { showReferences = false }, title = { Text("参考报价") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            ReferencePrices.sheets.forEach { sheet ->
                Text("${sheet.name} · ${sheet.checkedAt}", style = MaterialTheme.typography.titleSmall)
                sheet.entries.forEach { item ->
                    TextButton(enabled = !busy, onClick = { referenceItem = item; referenceChannelConfirmed = false; referenceConditionsConfirmed = false; showReferences = false }) {
                        Text("${item.requestModelId} · ${if (item.adoptable) "可预览采用" else "仅供核对"}")
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showReferences = false }) { Text("关闭") } })

    importDocument?.let { doc ->
        val models = document.models.filter { it.providerId == providerIdState }
        val plan = remember(doc, models, importSelections, importUnassignedIds, overwriteIds, pricing.bindings) {
            PriceExchangeImportPlan.create(doc, models, importSelections, overwriteIds, pricing.bindings.keys, importUnassignedIds)
        }
        if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("导入预览 · ${doc.name}") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                plan.rows.forEach { row ->
                    var expanded by remember(row.fileEntryId) { mutableStateOf(false) }
                    Text("${row.requestModelId} → ${row.selectedModelId ?: "未关联"}${if (row.defaultSkip) " · 已有报价默认跳过" else ""}")
                    if (row.candidates.size > 1 || row.reason?.contains("大小写") == true) {
                        TextButton(enabled = !busy, onClick = { expanded = true }) { Text("选择目标模型") }
                        DropdownMenu(expanded, { expanded = false }) { (row.candidates.ifEmpty { models.map { it.id } }).forEach { candidate ->
                            DropdownMenuItem(enabled = !busy, text = { Text(models.first { it.id == candidate }.displayName) }, onClick = { importSelections = importSelections + (row.fileEntryId to candidate); importUnassignedIds = importUnassignedIds - row.fileEntryId; expanded = false })
                        } }
                    }
                    row.reason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    val id = row.selectedModelId
                    if (row.candidates.isNotEmpty() && id != null && !row.defaultSkip && !row.keepUnassigned) TextButton(enabled = !busy, onClick = {
                        importUnassignedIds = importUnassignedIds + row.fileEntryId
                    }) { Text("保留未关联") }
                    if (row.keepUnassigned) TextButton(enabled = !busy, onClick = {
                        importUnassignedIds = importUnassignedIds - row.fileEntryId
                    }) { Text("恢复模型映射") }
                    if (id != null && id in pricing.bindings) TextButton(enabled = !busy, onClick = {
                        overwriteIds = if (id in overwriteIds) overwriteIds - id else overwriteIds + id
                    }) { Text(if (id in overwriteIds) "确认覆盖" else "保留已有报价") }
                    doc.entries.firstOrNull { it.fileEntryId == row.fileEntryId }?.rule?.let { r ->
                        Text("导入完整规则：${r.standard.input}/${r.standard.output} · ${r.tiers.size} 档 · ${r.schedule?.ranges?.size ?: 0} 个时段", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (doc.unassigned.isNotEmpty()) Text("文件内 ${doc.unassigned.size} 条未关联报价将只读保留。")
                (doc.archives + listOfNotNull(doc.legacyArchive)).forEach { archive ->
                    Text("存档：${archive.name} · ${archive.tables.size} 张旧表 · 不参与计价。")
                }
                plan.conflicts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = plan.ready, onClick = {
                pricing = plan.applyTo(doc, pricing, models)
                dirty = true; confirmImport = false; importDocument = null
            }) { Text("导入到草稿") } }, dismissButton = { TextButton(enabled = !busy, onClick = { confirmImport = false; importDocument = null }) { Text("取消") } })
    }
    if (confirmUnassignedLink) {
        val orphan = pricing.unassigned.firstOrNull { it.id == selectedUnassigned }
        val targetId = orphan?.let { unassignedTargets[it.id] }
        val targetModel = document.models.firstOrNull { it.id == targetId && it.providerId == providerIdState }
        val previous = targetModel?.let { pricing.bindings[it.id] }
        AlertDialog(onDismissRequest = { confirmUnassignedLink = false }, title = { Text("关联未关联报价？") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                orphan?.rule?.let { RulePreview(it) }
                Text("请求 ID：${orphan?.requestModelId.orEmpty()} → ${targetModel?.displayName.orEmpty()}（${targetModel?.requestModelId.orEmpty()}）")
                Text("来源与适用条件将一并保留；此操作只修改本页草稿。")
                if (previous != null) {
                    Text("目标已有报价，将整条替换：", style = MaterialTheme.typography.titleSmall)
                    RulePreview(previous.rule)
                }
            } },
            confirmButton = { TextButton(enabled = orphan?.rule != null && targetModel != null && !busy, onClick = {
                val selected = requireNotNull(orphan)
                val model = requireNotNull(targetModel)
                val rule = requireNotNull(selected.rule)
                pricing = pricing.copy(
                    bindings = pricing.bindings + (model.id to BoundModelPrice(model.requestModelId,
                        rule.copy(model = model.requestModelId.trim().lowercase()), selected.origin, selected.conditions)),
                    unassigned = pricing.unassigned.filterNot { it.id == selected.id })
                dirty = true
                selectedUnassigned = null; confirmUnassignedLink = false
            }) { Text(if (previous != null) "确认替换草稿" else "关联到草稿") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmUnassignedLink = false }) { Text("取消") } })
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("放弃未保存报价？") },
        text = { Text("本页草稿尚未保存。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
}

private fun loadExchangeDocument(context: android.content.Context, uri: Uri): PriceExchangeDocument {
    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total <= PriceExchangeTransfer.MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, PriceExchangeTransfer.MAX_BYTES + 1 - total))
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count); total += count
                    }
                    val bytes = output.toByteArray()
        require(total <= PriceExchangeTransfer.MAX_BYTES) { "价格文件超过 2 MiB" }
        String(bytes, Charsets.UTF_8)
    } ?: error("无法读取所选文件")
    return if ("\"formatVersion\"\\s*:\\s*3".toRegex().containsMatchIn(text)) PriceExchangeTransfer.decode(text) else {
        val old = PriceTableTransfer.decode(text)
        PriceExchangeDocument(name = old.name, entries = old.entries.mapIndexed { i, rule ->
            PriceExchangeEntry("legacy-file-$i", rule.model, rule.model, rule,
                ExchangePriceOrigin("IMPORT", note = "由旧 v${old.formatVersion} 文件导入；原格式未包含精确请求 ID"))
        })
    }
}

@Composable
private fun RulePreview(rule: ModelPriceEntry) {
    @Composable fun rates(label: String, value: PriceRates?) {
        if (value != null) Text("$label · 输入 ${value.input} / 输出 ${value.output} / 缓存 ${value.cached?.toString() ?: "随输入"}",
            style = MaterialTheme.typography.bodySmall)
    }
    rates("标准", rule.standard)
    Text("峰谷：${if (rule.peakEnabled) "已启用" else "关闭（峰价保留）"} · 窗口 ${rule.window.startMinute ?: "未设"}～${rule.window.endMinute ?: "未设"} · ${rule.window.timeZone}",
        style = MaterialTheme.typography.bodySmall)
    rates("峰价", rule.peak)
    rule.schedule?.let { schedule ->
        Text("时段：${schedule.ranges.joinToString { "${it.startMinute}–${it.endMinute}" }} · 星期 ${schedule.weekdays.joinToString()} · ${schedule.timeZone}",
            style = MaterialTheme.typography.bodySmall)
        schedule.calendar?.let { calendar ->
            Text("日历：${calendar.validFrom}～${calendar.validThrough} · 排除 ${calendar.excludedDates.size} · 包含 ${calendar.includedDates.size} · 已确认 ${calendar.confirmed}",
                style = MaterialTheme.typography.bodySmall)
            Text("日历来源：${calendar.source}", style = MaterialTheme.typography.bodySmall)
        }
    } ?: Text("时段：未设置", style = MaterialTheme.typography.bodySmall)
    rule.tiers.forEachIndexed { index, tier ->
        Text("第 ${index + 1} 档 · ≤ ${tier.upToInputTokens ?: "末档"} token", style = MaterialTheme.typography.bodySmall)
        rates("标准", tier.standard); rates("峰价", tier.peak)
    }
    Text("来源：${rule.source} · 渠道：${rule.provider}", style = MaterialTheme.typography.bodySmall)
    Text(rule.note, style = MaterialTheme.typography.bodySmall)
}

private fun PriceOrigin.toExchange() = ExchangePriceOrigin(kind.name, sourceId, channel, checkedAt, url, note)
private fun PriceConditions.toExchange() = ExchangePriceConditions(thinkingMode, connectionFingerprint)
