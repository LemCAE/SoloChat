package app.solochat.ui.settings

import app.solochat.R
import app.solochat.data.ModelRegistryDocument
import app.solochat.data.RegistryModel
import app.solochat.data.RegistrySelection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.solochat.core.model.ModelReference
import app.solochat.ui.SettingsGroup

@Composable
internal fun ModelUseSettingsScreen(
    draft: P2ModelSettingsDraft,
    onDraftChange: (P2ModelSettingsDraft) -> Unit
) {
    val context = LocalContext.current
    var pickerTarget by remember { mutableStateOf<P2SelectionTarget?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroup(context.getString(R.string.p2a_global_default)) {
            ModelSelectionRow(draft, P2SelectionTarget.GLOBAL) { pickerTarget = P2SelectionTarget.GLOBAL }
        }
        SettingsGroup(context.getString(R.string.p2a_chat_use)) {
            ModelSelectionRow(draft, P2SelectionTarget.CHAT) { pickerTarget = P2SelectionTarget.CHAT }
        }
        SettingsGroup(context.getString(R.string.p2a_compression_use)) {
            ModelSelectionRow(draft, P2SelectionTarget.COMPRESSION) { pickerTarget = P2SelectionTarget.COMPRESSION }
        }
        SettingsGroup(context.getString(R.string.p2a_title_use)) {
            ModelSelectionRow(draft, P2SelectionTarget.TITLE) { pickerTarget = P2SelectionTarget.TITLE }
        }
        SettingsGroup(context.getString(R.string.p2a_conversation_example)) {
            ListItem(
                headlineContent = { Text(context.getString(R.string.p2a_conversation_example_name)) },
                supportingContent = {
                    val (resolved, source) = resolveConversationPreview(draft)
                    Column {
                        Text(selectionSummary(draft, resolved))
                        Text(source, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { pickerTarget = P2SelectionTarget.CONVERSATION }
            )
        }
    }

    pickerTarget?.let { target ->
        ModelSelectionDialog(
            document = draft.document,
            target = target,
            current = draft.selection(target),
            onDismiss = { pickerTarget = null },
            onSelect = { selection ->
                onDraftChange(draft.withSelection(target, selection))
                pickerTarget = null
            }
        )
    }
}

@Composable
private fun ModelSelectionRow(draft: P2ModelSettingsDraft, target: P2SelectionTarget, onClick: () -> Unit) {
    val context = LocalContext.current
    val current = draft.selection(target)
    val reference = when {
        current.state == "EXPLICIT" -> draft.resolve(current)
        target != P2SelectionTarget.GLOBAL && current.state == "INHERIT" -> draft.resolve(draft.document.globalDefault)
        else -> null
    }
    val source = when {
        current.state == "NEEDS_CONFIRMATION" -> context.getString(R.string.p2a_selection_needs_confirmation)
        current.state == "INHERIT" -> context.getString(R.string.p2a_source_inherited)
        else -> null
    }
    ListItem(
        headlineContent = { Text(selectionSummary(draft, reference)) },
        supportingContent = {
            Column {
                source?.let { Text(it) }
                reference?.let { Text(modelSource(draft, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ModelSelectionDialog(
    document: ModelRegistryDocument,
    target: P2SelectionTarget,
    current: RegistrySelection,
    onDismiss: () -> Unit,
    onSelect: (RegistrySelection) -> Unit
) {
    val context = LocalContext.current
    var query by remember(target) { mutableStateOf("") }
    val includeInherit = target != P2SelectionTarget.GLOBAL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.p2a_choose_model_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(context.getString(R.string.p2a_picker_search_label)) },
                    placeholder = { Text(context.getString(R.string.p2a_search_model)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (includeInherit) {
                        SelectionOption(
                            title = context.getString(R.string.p2a_inherit_global),
                            subtitle = selectionSummary(document, document.globalDefault),
                            selected = current.state == "INHERIT",
                            onClick = { onSelect(RegistrySelection.inherit()) }
                        )
                    }
                    document.models.asSequence()
                        .filter { model ->
                            val provider = document.providers.firstOrNull { it.id == model.providerId }
                            val needle = query.trim().lowercase()
                            needle.isEmpty() || model.displayName.lowercase().contains(needle) ||
                                model.requestModelId.lowercase().contains(needle) ||
                                provider?.name?.lowercase()?.contains(needle) == true
                        }
                        .forEach { model ->
                            val provider = document.providers.firstOrNull { it.id == model.providerId }
                            val selected = current.state == "EXPLICIT" && current.providerId == model.providerId && current.modelId == model.id
                            SelectionOption(
                                title = model.displayName,
                                subtitle = listOfNotNull(provider?.name, model.requestModelId).joinToString(" · "),
                                selected = selected,
                                enabled = model.enabled && provider?.enabled == true,
                                onClick = { onSelect(RegistrySelection.explicit(ModelReference(model.providerId, model.id))) }
                            )
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.p2a_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.p2a_cancel)) } }
    )
}

@Composable
private fun SelectionOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun selectionSummary(draft: P2ModelSettingsDraft, reference: ModelReference?): String =
    selectionSummary(draft.document, reference?.let { RegistrySelection.explicit(it) } ?: RegistrySelection.needsConfirmation(null, "无有效模型"))

@Composable
private fun selectionSummary(document: ModelRegistryDocument, selection: RegistrySelection): String {
    val context = LocalContext.current
    if (selection.state == "NEEDS_CONFIRMATION") return context.getString(R.string.p2a_selection_needs_confirmation)
    if (selection.state != "EXPLICIT" || selection.providerId == null || selection.modelId == null) {
        return context.getString(R.string.p2a_no_model_selected)
    }
    val model = document.models.firstOrNull { it.id == selection.modelId && it.providerId == selection.providerId }
        ?: return context.getString(R.string.p2a_selection_needs_confirmation)
    return model.displayName
}

private fun selectionSummary(document: ModelRegistryDocument, reference: ModelReference?): String {
    if (reference == null) return "尚未选择模型"
    val model = document.models.firstOrNull { it.id == reference.modelId && it.providerId == reference.providerId }
        ?: return "需要确认模型选择"
    return model.displayName
}

private fun modelSource(draft: P2ModelSettingsDraft, reference: ModelReference): String {
    val model: RegistryModel = draft.document.models.firstOrNull { it.id == reference.modelId && it.providerId == reference.providerId }
        ?: return ""
    val provider = draft.document.providers.firstOrNull { it.id == model.providerId } ?: return model.requestModelId
    return "${provider.name} · ${model.requestModelId}"
}

private fun resolveConversationPreview(draft: P2ModelSettingsDraft): Pair<ModelReference?, String> {
    val conversation = draft.conversationSelection
    if (conversation.state == "NEEDS_CONFIRMATION") return null to "需要确认会话模型"
    if (conversation.state == "EXPLICIT") return draft.resolve(conversation) to "会话指定"
    val chat = draft.document.chatSelection
    if (chat.state == "NEEDS_CONFIRMATION") return null to "聊天用途需要确认"
    if (chat.state == "EXPLICIT") return draft.resolve(chat) to "聊天用途"
    if (draft.document.globalDefault.state == "EXPLICIT") return draft.resolve(draft.document.globalDefault) to "全局默认"
    return null to "全局默认需要确认"
}

