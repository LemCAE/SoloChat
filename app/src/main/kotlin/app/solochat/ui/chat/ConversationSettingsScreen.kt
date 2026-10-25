package app.solochat.ui.chat

import app.solochat.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.solochat.core.model.ChatMode
import app.solochat.core.model.ModelReference
import app.solochat.data.RegistrySelection
import app.solochat.play.ChatUiState
import app.solochat.play.TurnPhase
import app.solochat.ui.CompactTopBar
import app.solochat.ui.theme.SoloChatTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** 在聊天上层展示，订阅原控制器；查看设置不会关闭回合或丢失输入草稿。 */
data class ConversationModelChoice(val providerId: String, val modelId: String,
    val providerName: String, val displayName: String, val requestModelId: String)

@Composable
fun ConversationSettingsScreen(
    state: ChatUiState,
    model: String,
    onBack: () -> Unit,
    onRename: suspend (String) -> Unit,
    onDelete: suspend () -> Unit,
    modelSelection: RegistrySelection = RegistrySelection.inherit(),
    modelChoices: List<ConversationModelChoice> = emptyList(),
    onModelBindingChange: suspend (RegistrySelection) -> Unit = {},
    onOpenUsage: () -> Unit = {},
    contextStatus: String? = null,
    onOpenCompression: () -> Unit = {}
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var editingName by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editingModel by rememberSaveable { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }
    var modelDraft by remember(modelSelection) { mutableStateOf(modelSelection) }
    val canManage = state.turnPhase == TurnPhase.IDLE && !working
    BackHandler(enabled = working) { }
    fun perform(action: suspend () -> Unit, finished: () -> Unit) {
        if (working || state.turnPhase != TurnPhase.IDLE) return
        working = true
        error = ""
        scope.launch {
            try { action(); finished() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = stringsContext.getString(R.string.ui_conversationsettingsscreen_08672daf) }
            finally { working = false }
        }
    }
    if (editingName) {
        AlertDialog(
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            onDismissRequest = { if (!working) editingName = false },
            title = { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_8a1bfdc1)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it; error = "" },
                    enabled = !working, singleLine = true, label = { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_105efc40)) },
                    modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = canManage && nameDraft.isNotBlank(),
                onClick = { perform({ onRename(nameDraft.trim()) }, { editingName = false }) }) {
                Text(if (working) stringsContext.getString(R.string.ui_conversationsettingsscreen_ab212277) else stringsContext.getString(R.string.ui_conversationsettingsscreen_b2ef2293))
            } },
            dismissButton = { TextButton(enabled = !working, onClick = { editingName = false }) { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_65a6ca9f)) } }
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmingDelete = false },
            title = { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_9a0f780a)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_1ed70970, state.title.ifBlank { stringsContext.getString(R.string.ui_chat_mode_label) }))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = canManage,
                onClick = { perform(onDelete, { confirmingDelete = false }) }) {
                Text(if (working) stringsContext.getString(R.string.ui_conversationsettingsscreen_3a568ef8) else stringsContext.getString(R.string.ui_conversationsettingsscreen_473342ec), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(enabled = !working, onClick = { confirmingDelete = false }) { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_65a6ca9f)) } }
        )
    }
    if (editingModel) AlertDialog(
        // 对话框自行消费键盘 inset，正文滚动，操作按钮留在键盘上方。
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = { if (!working) editingModel = false },
        title = { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_e8158ed9)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = modelSearch,
                onValueChange = { modelSearch = it },
                enabled = !working,
                singleLine = true,
                label = { Text(stringsContext.getString(R.string.p2a_picker_search_label)) },
                placeholder = { Text(stringsContext.getString(R.string.p2a_search_model)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().clickable(enabled = !working) { modelDraft = RegistrySelection.inherit() }) {
                RadioButton(selected = modelDraft.state == "INHERIT", enabled = !working,
                    onClick = { modelDraft = RegistrySelection.inherit() })
                Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_b359d76a), modifier = Modifier.padding(top = 12.dp))
            }
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                modelChoices.filter { option ->
                    val needle = modelSearch.trim().lowercase()
                    needle.isEmpty() || option.providerName.lowercase().contains(needle) ||
                        option.displayName.lowercase().contains(needle) || option.requestModelId.lowercase().contains(needle)
                }.forEach { option ->
                    val selected = modelDraft.providerId == option.providerId && modelDraft.modelId == option.modelId
                    Row(Modifier.fillMaxWidth().clickable(enabled = !working) {
                        modelDraft = RegistrySelection.explicit(ModelReference(option.providerId, option.modelId))
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, enabled = !working, onClick = {
                            modelDraft = RegistrySelection.explicit(ModelReference(option.providerId, option.modelId))
                        })
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text("${option.providerName} · ${option.displayName}")
                            Text(option.requestModelId, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (modelSelection.state == "NEEDS_CONFIRMATION") Text(modelSelection.reason.orEmpty(),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_ca145f3b), style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = canManage && modelDraft.state in setOf("INHERIT", "EXPLICIT"), onClick = {
            perform({ onModelBindingChange(modelDraft) }, { editingModel = false })
        }) { Text(if (working) stringsContext.getString(R.string.ui_conversationsettingsscreen_ab212277) else stringsContext.getString(R.string.ui_conversationsettingsscreen_b2ef2293)) } },
        dismissButton = { TextButton(enabled = !working, onClick = { editingModel = false }) { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_65a6ca9f)) } })
    Scaffold(topBar = {
        CompactTopBar(
            title = { Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_ace70d1f), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !working) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringsContext.getString(R.string.ui_conversationsettingsscreen_fda2983d))
            } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(state.title.ifBlank { stringsContext.getString(R.string.ui_conversationsettingsscreen_6a43e963) } + " · " + if (state.mode == ChatMode.SCENE) stringsContext.getString(R.string.ui_conversationsettingsscreen_5bf828cd) else stringsContext.getString(R.string.ui_conversationsettingsscreen_52a23b7f),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                SettingsRow(stringsContext.getString(R.string.ui_conversationsettingsscreen_316aad47), state.title, canManage) { nameDraft = state.title; error = ""; editingName = true }
                SettingsRow(stringsContext.getString(R.string.ui_conversationsettingsscreen_35677e07),
                    if (modelSelection.state == "NEEDS_CONFIRMATION") "待确认 · ${modelSelection.reason.orEmpty()}" else model.ifBlank { stringsContext.getString(R.string.ui_conversationsettingsscreen_3ef1b8d6) }, canManage) {
                    modelDraft = modelSelection; modelSearch = ""; error = ""; editingModel = true
                }
                SettingsRow(stringsContext.getString(R.string.ui_conversationsettingsscreen_c0321c35), contextStatus ?: stringsContext.getString(R.string.ui_chatscreen_fab6a8ec), !working, onOpenCompression)
                SettingsRow(stringsContext.getString(R.string.ui_conversationsettingsscreen_ec0a7354), stringsContext.getString(R.string.ui_conversationsettingsscreen_6d5299af), !working, onOpenUsage)
            }
            if (!canManage) Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_73b29772), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = canManage, onClick = { error = ""; confirmingDelete = true }) {
                Text(stringsContext.getString(R.string.ui_conversationsettingsscreen_937c57c8), color = MaterialTheme.colorScheme.error)
            }

        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "会话设置·明", showBackground = true)
@Preview(name = "会话设置·大字体", showBackground = true, fontScale = 2f)
@Composable
private fun ConversationSettingsLightPreview() = ConversationSettingsPreview(false)

@Preview(name = "会话设置·暗", showBackground = true)
@Composable
private fun ConversationSettingsDarkPreview() = ConversationSettingsPreview(true)

@Composable
private fun ConversationSettingsPreview(dark: Boolean) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    SoloChatTheme(darkTheme = dark) {
        ConversationSettingsScreen(ChatUiState(title = stringsContext.getString(R.string.ui_conversationsettingsscreen_4d173817), personaName = stringsContext.getString(R.string.ui_conversationsettingsscreen_90aa16e5)),
            "deepseek-chat", {}, {}, {})
    }
}
