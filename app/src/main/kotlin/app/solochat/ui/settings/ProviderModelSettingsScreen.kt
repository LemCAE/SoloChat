package app.solochat.ui.settings

import app.solochat.R
import app.solochat.core.agent.ThinkingRequestAdaptation
import app.solochat.core.agent.ThinkingRequestAdapter
import app.solochat.core.agent.ThinkingFailure
import app.solochat.core.llm.ThinkingEffort
import app.solochat.core.model.CapabilitySource
import app.solochat.core.model.ModelUse
import app.solochat.core.model.ThinkingControl
import app.solochat.core.model.ThinkingMode
import app.solochat.core.model.ThinkingParameterDialect
import app.solochat.data.ModelRegistryDocument
import app.solochat.data.RegistryModel
import app.solochat.data.RegistryProvider
import app.solochat.ui.SettingsGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ProviderModelSettingsScreen(
    draft: P2ModelSettingsDraft,
    onDraftChange: (P2ModelSettingsDraft) -> Unit,
    onOpenPrice: (String) -> Unit,
    keyStatus: (String) -> String = { "" },
    onSaveKey: (String, String?, Boolean) -> Unit = { _, _, _ -> },
    onTestConnection: suspend (RegistryProvider, String?) -> String = { _, _ -> "" },
    onFetchModels: suspend (RegistryProvider) -> List<String> = { emptyList() }
) {
    val context = LocalContext.current
    var selectedTab by remember(draft.selectedProviderId) { mutableStateOf(0) }
    var editingProvider by remember { mutableStateOf(false) }
    var addingProvider by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<RegistryModel?>(null) }
    var editingKey by remember { mutableStateOf(false) }
    var providerToRemove by remember { mutableStateOf<RegistryProvider?>(null) }
    var removeError by remember { mutableStateOf("") }
    var connectionBusy by remember { mutableStateOf(false) }
    var catalogBusy by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf("") }
    var catalogResult by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var addingModel by remember { mutableStateOf(false) }
    val provider = draft.document.providers.firstOrNull { it.id == draft.selectedProviderId }
    var modelSearch by rememberSaveable(provider?.id) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroup(context.getString(R.string.p2a_provider_list)) {
            draft.document.providers.forEach { item ->
                val count = draft.document.models.count { it.providerId == item.id }
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = {
                        Text(context.resources.getQuantityString(R.plurals.p2a_model_count, count, count))
                    },
                    leadingContent = {
                        RadioButton(
                            selected = item.id == draft.selectedProviderId,
                            onClick = { onDraftChange(draft.copy(selectedProviderId = item.id)) }
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { providerToRemove = item; removeError = "" }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除提供商")
                        }
                    },
                    modifier = Modifier.clickable { onDraftChange(draft.copy(selectedProviderId = item.id)) }
                )
            }
            TextButton(onClick = { addingProvider = true }, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(context.getString(R.string.p2a_add_provider))
            }
        }

        if (provider != null) {
            SettingsGroup(provider.name) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(context.getString(R.string.p2a_provider_connection)) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(context.getString(R.string.p2a_provider_model_tab)) })
                }
                if (selectedTab == 0) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReadOnlyPreviewField(context.getString(R.string.p2a_provider_name), provider.name)
                        ReadOnlyPreviewField(context.getString(R.string.p2a_endpoint), provider.baseUrl)
                        ReadOnlyPreviewField("API Key", keyStatus(provider.id).ifBlank { "未设置" })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(context.getString(R.string.p2a_enabled), modifier = Modifier.weight(1f))
                            Switch(checked = provider.enabled, onCheckedChange = { onDraftChange(draft.withProvider(provider.copy(enabled = it))) })
                        }
                        TextButton(onClick = { editingProvider = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Text(context.getString(R.string.p2a_edit_provider))
                        }
                        TextButton(onClick = { editingKey = true }) { Text("设置密钥") }
                        Button(enabled = !connectionBusy && provider.enabled, onClick = {
                            connectionBusy = true; connectionResult = ""
                            scope.launch {
                                connectionResult = try {
                                    onTestConnection(provider, draft.document.models.firstOrNull { it.providerId == provider.id && it.enabled }?.requestModelId)
                                } catch (error: Exception) { error.message ?: "连接测试失败" }
                                connectionBusy = false
                            }
                        }) { Text(if (connectionBusy) "正在测试" else "测试连接") }
                        if (connectionResult.isNotBlank()) Text(connectionResult, style = MaterialTheme.typography.bodySmall,
                            color = if (connectionResult.contains("成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onOpenPrice(provider.id) }) {
                            Text(context.getString(R.string.p2a_price_entry))
                        }
                        Text(context.getString(R.string.p2a_price_active_only),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        OutlinedTextField(
                            value = modelSearch,
                            onValueChange = { modelSearch = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            singleLine = true,
                            label = { Text("搜索模型") },
                            placeholder = { Text("名称或请求 ID") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (modelSearch.isNotBlank()) {
                                    IconButton(onClick = { modelSearch = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                                    }
                                }
                            }
                        )
                        TextButton(enabled = !catalogBusy && provider.enabled, onClick = {
                            catalogBusy = true; catalogResult = ""
                            scope.launch {
                                try {
                                    val fetched = onFetchModels(provider).distinct()
                                    var updated = draft
                                    val existing = updated.document.models.filter { it.providerId == provider.id }
                                        .mapTo(mutableSetOf()) { it.requestModelId }
                                    val added = fetched.filterNot { it in existing }
                                    added.forEach { requestId ->
                                        updated = updated.addModel(provider.id, requestId, requestId)
                                    }
                                    onDraftChange(updated)
                                    catalogResult = "已加入 ${added.size} 个模型"
                                } catch (error: Exception) { catalogResult = error.message ?: "获取模型失败" }
                                catalogBusy = false
                            }
                        }) { Text(if (catalogBusy) "正在获取" else "获取模型列表") }
                        if (catalogResult.isNotBlank()) Text(catalogResult, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            textAlign = TextAlign.Start,
                            color = if (catalogResult.startsWith("已加入")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        val query = modelSearch.trim()
                        val visibleModels = draft.document.models.filter { model ->
                            model.providerId == provider.id && (query.isBlank() ||
                                model.displayName.contains(query, ignoreCase = true) ||
                                model.requestModelId.contains(query, ignoreCase = true))
                        }
                        if (visibleModels.isEmpty()) {
                            Text(if (query.isBlank()) "尚未添加模型" else "没有匹配的模型",
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        visibleModels.forEach { model ->
                            ListItem(
                                headlineContent = { Text(model.displayName) },
                                supportingContent = {
                                    Column {
                                        Text(model.requestModelId, style = MaterialTheme.typography.bodySmall)
                                        Text(thinkingSummary(provider, model), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                trailingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                modifier = Modifier.clickable { editingModel = model }
                            )
                        }
                        TextButton(onClick = { addingModel = true }, modifier = Modifier.padding(start = 16.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(context.getString(R.string.p2a_add_model))
                        }
                    }
                }
            }
        }
    }

    providerToRemove?.let { removing ->
        AlertDialog(onDismissRequest = { providerToRemove = null },
            title = { Text("移除提供商") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将从模型配置中移除“${removing.name}”及其模型。已保存的凭据槽位会保留。请先把全局/用途选择和相关会话改到其他模型。")
                if (removeError.isNotBlank()) Text(removeError, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                runCatching { draft.removeProvider(removing.id) }
                    .onSuccess { onDraftChange(it); providerToRemove = null }
                    .onFailure { removeError = it.message ?: "无法移除提供商" }
            }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { providerToRemove = null }) { Text("取消") } })
    }

    if (editingProvider && provider != null) EditProviderDialog(provider, onDismiss = { editingProvider = false }) {
        onDraftChange(draft.withProvider(it)); editingProvider = false
    }
    if (editingKey && provider != null) EditProviderKeyDialog(provider.name,
        onDismiss = { editingKey = false }, onSave = { value, clear ->
            onSaveKey(provider.id, value, clear); editingKey = false
        })
    if (addingProvider) AddProviderDialog(onDismiss = { addingProvider = false }) { name, baseUrl ->
        onDraftChange(draft.addProvider(name, baseUrl)); addingProvider = false; selectedTab = 0
    }
    if (addingModel && provider != null) EditModelDialog(
        document = draft.document,
        provider = provider,
        model = null,
        onDismiss = { addingModel = false },
        onSave = { name, requestId, enabled, mode, effort, control, efforts, dialect, manual ->
            val updated = draft.addModel(provider.id, name, requestId)
            val created = updated.document.models.last().copy(
                enabled = enabled,
                thinkingMode = mode.name,
                thinkingEffort = effort?.name,
                thinkingControl = control.name,
                supportedEfforts = efforts.map { it.name },
                capabilitySource = if (manual) CapabilitySource.USER_CONFIRMED.name else CapabilitySource.UNKNOWN.name,
                thinkingDialectOverride = dialect?.name
            )
            onDraftChange(updated.withModel(created))
            addingModel = false
        }
    )
    editingModel?.let { model ->
        if (provider != null) EditModelDialog(
            document = draft.document,
            provider = provider,
            model = model,
            onDismiss = { editingModel = null },
            onSave = { name, requestId, enabled, mode, effort, control, efforts, dialect, manual ->
                onDraftChange(draft.withModel(model.copy(
                    displayName = name,
                    requestModelId = requestId,
                    enabled = enabled,
                    thinkingMode = mode.name,
                    thinkingEffort = effort?.name,
                    thinkingControl = control.name,
                    supportedEfforts = efforts.map { it.name },
                    capabilitySource = if (manual) CapabilitySource.USER_CONFIRMED.name else CapabilitySource.UNKNOWN.name,
                    thinkingDialectOverride = dialect?.name
                )))
                editingModel = null
            }
        )
    }
}

@Composable
private fun EditProviderKeyDialog(providerName: String, onDismiss: () -> Unit,
    onSave: (String?, Boolean) -> Unit) {
    var value by remember(providerName) { mutableStateOf("") }
    var clear by remember(providerName) { mutableStateOf(false) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("$providerName · API Key") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value, { value = it; clear = false }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                label = { Text("新密钥") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { clear = true; value = "" }) { Text(if (clear) "将清除密钥" else "清除已保存密钥") }
            Text(context.getString(R.string.p2a_credential_slot_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(enabled = clear || value.isNotBlank(), onClick = { onSave(value.takeIf { it.isNotBlank() }, clear) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.p2a_cancel)) } })
}

@Composable
private fun ReadOnlyPreviewField(label: String, value: String) {
    OutlinedTextField(value = value, onValueChange = {}, readOnly = true, singleLine = true,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun EditProviderDialog(provider: RegistryProvider, onDismiss: () -> Unit, onSave: (RegistryProvider) -> Unit) {
    val context = LocalContext.current
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.p2a_edit_provider)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(context.getString(R.string.p2a_provider_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(context.getString(R.string.p2a_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(context.getString(R.string.p2a_credential_slot_note), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(provider.copy(name = name.trim(), baseUrl = baseUrl.trim())) }) { Text(context.getString(R.string.p2a_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.p2a_cancel)) } }
    )
}

@Composable
private fun AddProviderDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var templateMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.p2a_add_provider)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(context.getString(R.string.p2a_provider_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(context.getString(R.string.p2a_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { templateMenu = true }) { Text(context.getString(R.string.p2a_endpoint_template)) }
                DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                    listOf(
                        context.getString(R.string.api_template_deepseek) to "https://api.deepseek.com",
                        context.getString(R.string.api_template_dashscope) to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        context.getString(R.string.api_template_custom) to ""
                    ).forEach { (label, url) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                        name = if (name.isBlank()) label else name
                        baseUrl = url
                        templateMenu = false
                    }) }
                }
            }
            Text(context.getString(R.string.p2a_credential_slot_note), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name, baseUrl) }) { Text(context.getString(R.string.p2a_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.p2a_cancel)) } }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EditModelDialog(
    document: ModelRegistryDocument,
    provider: RegistryProvider,
    model: RegistryModel?,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, ThinkingMode, ThinkingEffort?, ThinkingControl, Set<ThinkingEffort>, ThinkingParameterDialect?, Boolean) -> Unit
) {
    val context = LocalContext.current
    var name by remember(model?.id) { mutableStateOf(model?.displayName.orEmpty()) }
    var requestId by remember(model?.id) { mutableStateOf(model?.requestModelId.orEmpty()) }
    var enabled by remember(model?.id) { mutableStateOf(model?.enabled ?: true) }
    var mode by remember(model?.id) { mutableStateOf(runCatching { ThinkingMode.valueOf(model?.thinkingMode ?: "AUTO") }.getOrDefault(ThinkingMode.AUTO)) }
    var effort by remember(model?.id) { mutableStateOf(model?.thinkingEffort?.let { runCatching { ThinkingEffort.valueOf(it) }.getOrNull() }) }
    var control by remember(model?.id) { mutableStateOf(model?.thinkingControl?.let { runCatching { ThinkingControl.valueOf(it) }.getOrNull() } ?: ThinkingControl.UNKNOWN) }
    var efforts by remember(model?.id) { mutableStateOf(model?.supportedEfforts.orEmpty().mapNotNull { runCatching { ThinkingEffort.valueOf(it) }.getOrNull() }.toSet()) }
    var dialect by remember(model?.id) { mutableStateOf(model?.thinkingDialectOverride?.let { runCatching { ThinkingParameterDialect.valueOf(it) }.getOrNull() }) }
    var manual by remember(model?.id) { mutableStateOf(model?.capabilitySource == CapabilitySource.USER_CONFIRMED.name) }
    var error by remember(model?.id) { mutableStateOf("") }
    var helpMessage by remember(model?.id) { mutableStateOf<String?>(null) }
    val dialectProbe = RegistryModel(
        id = model?.id ?: "preview-model",
        providerId = provider.id,
        displayName = name,
        requestModelId = requestId,
        thinkingMode = ThinkingMode.AUTO.name,
        thinkingControl = control.name,
        supportedEfforts = efforts.map { it.name },
        capabilitySource = if (manual) CapabilitySource.USER_CONFIRMED.name else CapabilitySource.UNKNOWN.name,
        thinkingDialectOverride = dialect?.name
    )
    val automaticDialect = (ThinkingRequestAdapter.adapt(provider.toCore(), dialectProbe.toCore(), ModelUse.CHAT)
        as? ThinkingRequestAdaptation.Ready)?.dialect
    val effectiveDialect = dialect ?: automaticDialect
    val dashscopeStrengthConflict = mode == ThinkingMode.ON && effort != null &&
        effectiveDialect == ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(if (model == null) R.string.p2a_add_model else R.string.p2a_edit_model)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(context.getString(R.string.p2a_model_display_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(requestId, { requestId = it }, label = { Text(context.getString(R.string.p2a_request_model_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.p2a_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                ThinkingInfoHeading(context.getString(R.string.p2a_thinking_mode),
                    context.getString(R.string.p2a_thinking_mode_help)) { helpMessage = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ThinkingMode.AUTO, ThinkingMode.ON, ThinkingMode.OFF).forEach { choice ->
                        FilterChip(selected = mode == choice, onClick = {
                            mode = choice
                            if (choice != ThinkingMode.ON) effort = null
                        }, label = { Text(context.getString(when (choice) {
                            ThinkingMode.AUTO -> R.string.p2a_thinking_auto
                            ThinkingMode.ON -> R.string.p2a_thinking_on
                            ThinkingMode.OFF -> R.string.p2a_thinking_off
                        })) })
                    }
                }
                if (mode == ThinkingMode.ON) {
                    ThinkingInfoHeading(context.getString(R.string.p2a_thinking_effort),
                        context.getString(R.string.p2a_thinking_effort_help)) { helpMessage = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = effort == null, onClick = { effort = null }, label = { Text("不指定") })
                        listOf(ThinkingEffort.LOW, ThinkingEffort.MEDIUM, ThinkingEffort.HIGH, ThinkingEffort.XHIGH, ThinkingEffort.MAX).forEach { value ->
                            FilterChip(selected = effort == value,
                                enabled = effectiveDialect != ThinkingParameterDialect.DASHSCOPE_ENABLE_THINKING,
                                onClick = { effort = if (effort == value) null else value },
                                label = { Text(value.name.lowercase()) })
                        }
                    }
                    if (dashscopeStrengthConflict) Text(context.getString(R.string.p2a_dashscope_effort_unsupported),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(context.getString(R.string.p2a_capability_source))
                        Text(context.getString(if (manual) R.string.p2a_user_confirmed else R.string.p2a_auto_detect),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { helpMessage = context.getString(R.string.p2a_capability_source_help) }) {
                        Icon(Icons.Filled.Info, contentDescription = "查看能力来源说明")
                    }
                    Switch(checked = manual, onCheckedChange = { manual = it })
                }
                if (manual) {
                    ChoiceDropdown(
                        label = context.getString(R.string.p2a_thinking_control),
                        selected = capabilityLabel(context, control),
                        options = ThinkingControl.entries.map { it to capabilityLabel(context, it) },
                        onSelect = { control = it },
                        onInfo = { helpMessage = context.getString(R.string.p2a_thinking_control_help) }
                    )
                    if (control == ThinkingControl.ENABLE_AND_DISABLE || control == ThinkingControl.ENABLE_ONLY) {
                        Text(context.getString(R.string.p2a_effort_capability_note), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(ThinkingEffort.LOW, ThinkingEffort.MEDIUM, ThinkingEffort.HIGH, ThinkingEffort.XHIGH, ThinkingEffort.MAX).forEach { value ->
                                FilterChip(selected = value in efforts, onClick = {
                                    efforts = if (value in efforts) efforts - value else efforts + value
                                }, label = { Text(value.name.lowercase()) })
                            }
                        }
                    }
                }
                ChoiceDropdown(
                    label = context.getString(R.string.p2a_dialect),
                    selected = dialect?.name ?: context.getString(R.string.p2a_auto_detect),
                    options = listOf<Pair<ThinkingParameterDialect?, String>>(null to context.getString(R.string.p2a_auto_detect)) +
                        ThinkingParameterDialect.entries.map { it to it.name },
                    onSelect = { dialect = it },
                    onInfo = { helpMessage = context.getString(R.string.p2a_parameter_dialect_help) }
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && requestId.isNotBlank() && !dashscopeStrengthConflict, onClick = {
                val candidate = RegistryModel(
                    id = model?.id ?: "preview-new-model",
                    providerId = provider.id,
                    displayName = name.trim(),
                    requestModelId = requestId.trim(),
                    enabled = enabled,
                    thinkingMode = mode.name,
                    thinkingEffort = effort?.name,
                    thinkingControl = control.name,
                    supportedEfforts = efforts.map { it.name },
                    capabilitySource = if (manual) CapabilitySource.USER_CONFIRMED.name else CapabilitySource.UNKNOWN.name,
                    thinkingDialectOverride = dialect?.name
                )
                val adaptationResult = runCatching {
                    ThinkingRequestAdapter.adapt(provider.toCore(), candidate.toCore(), ModelUse.CHAT)
                }
                if (adaptationResult.isFailure) {
                    error = adaptationResult.exceptionOrNull()?.message
                        ?: context.getString(R.string.p2a_invalid_thinking_setting)
                    return@TextButton
                }
                val adaptation = adaptationResult.getOrThrow()
                if (adaptation is ThinkingRequestAdaptation.Unsupported) {
                    error = context.getString(when (adaptation.failure) {
                        ThinkingFailure.CAPABILITY_UNKNOWN -> R.string.p2a_thinking_capability_unknown
                        ThinkingFailure.CANNOT_ENABLE -> R.string.p2a_thinking_enable_unsupported
                        ThinkingFailure.CANNOT_DISABLE -> R.string.p2a_thinking_disable_unsupported
                        ThinkingFailure.EFFORT_UNSUPPORTED -> R.string.p2a_thinking_effort_unsupported
                    })
                } else {
                    onSave(name.trim(), requestId.trim(), enabled, mode, effort, control,
                        if (manual) efforts else emptySet(), dialect, manual)
                }
            }) { Text(context.getString(R.string.p2a_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.p2a_cancel)) } }
    )
    helpMessage?.let { message ->
        AlertDialog(onDismissRequest = { helpMessage = null },
            title = { Text("思考设置说明") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { helpMessage = null }) { Text(context.getString(R.string.p2a_done)) } })
    }
}

@Composable
private fun <T> ChoiceDropdown(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    onInfo: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(selected, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { expanded = true }) { Text(context.getString(R.string.p2a_choose)) }
        if (onInfo != null) IconButton(onClick = onInfo) {
            Icon(Icons.Filled.Info, contentDescription = "查看${label}说明")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}

@Composable
private fun ThinkingInfoHeading(title: String, message: String, onInfo: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { onInfo(message) }) {
            Icon(Icons.Filled.Info, contentDescription = "查看${title}说明")
        }
    }
}

@Composable
private fun capabilityLabel(context: android.content.Context, control: ThinkingControl): String = context.getString(when (control) {
    ThinkingControl.ENABLE_AND_DISABLE -> R.string.p2a_capability_toggle
    ThinkingControl.ENABLE_ONLY -> R.string.p2a_capability_enable_only
    ThinkingControl.UNSUPPORTED -> R.string.p2a_capability_unsupported
    ThinkingControl.UNKNOWN -> R.string.p2a_capability_unknown
})

@Composable
private fun thinkingSummary(provider: RegistryProvider, model: RegistryModel): String {
    val context = LocalContext.current
    val autoCandidate = model.copy(thinkingMode = ThinkingMode.AUTO.name, thinkingEffort = null)
    val detected = ThinkingRequestAdapter.adapt(provider.toCore(), autoCandidate.toCore(), ModelUse.CHAT)
        as? ThinkingRequestAdaptation.Ready
    val control = detected?.capability?.control ?: ThinkingControl.UNKNOWN
    val capability = capabilityLabel(context, control)
    return when {
        detected?.capability?.source == CapabilitySource.USER_CONFIRMED ->
            context.getString(R.string.p2a_model_capability_manual, capability)
        detected?.capability?.source == CapabilitySource.PROVIDER_MODEL_DECLARATION ->
            context.getString(R.string.p2a_model_capability_detected, capability)
        else -> context.getString(R.string.p2a_model_capability_unknown)
    }
}

