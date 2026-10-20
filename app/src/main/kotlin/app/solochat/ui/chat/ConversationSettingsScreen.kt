package app.solochat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import app.solochat.play.ChatUiState
import app.solochat.play.TurnPhase
import app.solochat.ui.CompactTopBar
import app.solochat.ui.personaLabel
import app.solochat.ui.theme.SoloChatTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** 在聊天上层展示，订阅原控制器；查看设置不会关闭回合或丢失输入草稿。 */
@Composable
fun ConversationSettingsScreen(
    state: ChatUiState,
    model: String,
    onBack: () -> Unit,
    onOpenPersonas: () -> Unit,
    onRename: suspend (String) -> Unit,
    onDelete: suspend () -> Unit,
    modelOverride: String? = null,
    modelOptions: List<String> = emptyList(),
    onModelChange: suspend (String?) -> Unit = {},
    onOpenUsage: () -> Unit = {},
    contextStatus: String = "保留原始聊天记录",
    onOpenCompression: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var editingName by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editingModel by rememberSaveable { mutableStateOf(false) }
    var inheritModel by rememberSaveable { mutableStateOf(true) }
    var modelDraft by rememberSaveable { mutableStateOf("") }
    val canManage = state.turnPhase == TurnPhase.IDLE && !working
    BackHandler(enabled = working) { }
    fun perform(action: suspend () -> Unit, finished: () -> Unit) {
        if (working || state.turnPhase != TurnPhase.IDLE) return
        working = true
        error = ""
        scope.launch {
            try { action(); finished() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "操作失败，请重试" }
            finally { working = false }
        }
    }
    if (editingName) {
        AlertDialog(
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            onDismissRequest = { if (!working) editingName = false },
            title = { Text("修改对话名") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it; error = "" },
                    enabled = !working, singleLine = true, label = { Text("对话名称") },
                    modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = canManage && nameDraft.isNotBlank(),
                onClick = { perform({ onRename(nameDraft.trim()) }, { editingName = false }) }) {
                Text(if (working) "保存中…" else "保存")
            } },
            dismissButton = { TextButton(enabled = !working, onClick = { editingName = false }) { Text("取消") } }
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmingDelete = false },
            title = { Text("删除这个对话？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将删除“${state.title.ifBlank { "聊天" }}”及其聊天记录，无法恢复。人物卡、已保存记忆和历史用量会保留。")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = canManage,
                onClick = { perform(onDelete, { confirmingDelete = false }) }) {
                Text(if (working) "删除中…" else "确认删除", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(enabled = !working, onClick = { confirmingDelete = false }) { Text("取消") } }
        )
    }
    if (editingModel) AlertDialog(
        // 对话框自行消费键盘 inset，正文滚动，操作按钮留在键盘上方。
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = { if (!working) editingModel = false },
        title = { Text("会话模型") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !working) { inheritModel = true }) {
                RadioButton(selected = inheritModel, enabled = !working, onClick = { inheritModel = true })
                Text("继承全局模型", modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = !working) { inheritModel = false }) {
                RadioButton(selected = !inheritModel, enabled = !working, onClick = { inheritModel = false })
                Text("指定模型", modifier = Modifier.padding(top = 12.dp))
            }
            if (!inheritModel) {
                OutlinedTextField(value = modelDraft, onValueChange = { modelDraft = it; error = "" },
                    enabled = !working, singleLine = true, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    modelOptions.distinct().forEach { option ->
                        TextButton(enabled = !working, onClick = { modelDraft = option }) { Text(option) }
                    }
                }
            }
            Text("共用全局接口与密钥；下一次请求生效，不改变其他会话。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = canManage && (inheritModel || modelDraft.isNotBlank()), onClick = {
            perform({ onModelChange(if (inheritModel) null else modelDraft.trim()) }, { editingModel = false })
        }) { Text(if (working) "保存中…" else "保存") } },
        dismissButton = { TextButton(enabled = !working, onClick = { editingModel = false }) { Text("取消") } })
    Scaffold(topBar = {
        CompactTopBar(
            title = { Text("会话设置", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !working) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回聊天")
            } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(state.title.ifBlank { "聊天" }, style = MaterialTheme.typography.titleLarge)
            Text(if (state.mode == ChatMode.SCENE) "面对面" else "发消息",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsSection("对话") {
                TextButton(enabled = canManage, onClick = {
                    nameDraft = state.title; error = ""; editingName = true
                }) { Text("修改对话名") }
                TextButton(enabled = canManage, onClick = { error = ""; confirmingDelete = true }) {
                    Text("删除对话", color = if (canManage) MaterialTheme.colorScheme.error else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                if (state.turnPhase != TurnPhase.IDLE) {
                    Text("本轮结束后可修改或删除", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SettingsSection("人物卡") {
                Text(personaLabel(state.personaAvatar, state.personaName, state.personaIsFallback)
                    .ifBlank { "尚未配置人物卡" }, style = MaterialTheme.typography.bodyLarge)
                Text("编辑人物卡会影响使用这张卡的其他会话。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenPersonas, enabled = canManage) {
                    Text(if (state.turnPhase == TurnPhase.IDLE) "管理人物卡" else "本轮结束后可编辑")
                }
            }
            SettingsSection("模型") {
                Text(model.ifBlank { "尚未配置模型" }, style = MaterialTheme.typography.bodyLarge)
                Text(if (modelOverride == null) "继承全局模型" else "本会话指定模型",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = canManage, onClick = {
                    inheritModel = modelOverride == null; modelDraft = modelOverride ?: model
                    error = ""; editingModel = true
                }) { Text(if (state.turnPhase == TurnPhase.IDLE) "选择模型" else "本轮结束后可修改模型") }
            }
            SettingsSection("上下文") {
                Text(contextStatus, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenCompression, enabled = !working) { Text("压缩上下文") }
                Text("选择早期范围、预览摘要后应用；聊天记录保持原样。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSection("用量统计") {
                TextButton(onClick = onOpenUsage, enabled = !working) { Text("查看统计详情") }
                Text("上下文估算、最近调用、会话累计与调用时单价。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
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
    SoloChatTheme(darkTheme = dark) {
        ConversationSettingsScreen(ChatUiState(title = "一个安静的下午", personaName = "小默"),
            "deepseek-chat", {}, {}, {}, {})
    }
}
