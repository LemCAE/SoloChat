package app.solochat.ui.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.solochat.SoloChatApp
import app.solochat.ui.ToolTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 先校验和预览，再明确确认恢复；导出和副本恢复复用同一版本化 ZIP。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SoloChatApp
    val vm: BackupViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::save)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::readRestore)
    var confirmRestore by remember { mutableStateOf(false) }
    if (confirmRestore && state.restorePreview != null) {
        AlertDialog(onDismissRequest = { if (!state.busy) confirmRestore = false },
            title = { Text("导入为独立副本？") },
            text = { Text("不会覆盖已有会话或人物卡。通用记忆会加入当前应用的通用记忆；导入会话先暂停发送，旧账单独保留，不计入本机累计。") },
            confirmButton = { TextButton(enabled = !state.busy, onClick = {
                confirmRestore = false
                vm.restore()
            }) { Text("确认导入") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { confirmRestore = false }) { Text("返回预览") } })
    }
    BackHandler(enabled = state.busy) { }
    Scaffold(topBar = {
        ToolTopBar(title = "本地备份", onBack = onBack, backEnabled = !state.busy)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("把聊天资料保存在自己选择的位置", style = MaterialTheme.typography.titleMedium)
                    Text("包含全部会话与历史、人物卡、记忆、摘要版本和用量记录。归档会话也会包含。")
                    Text("不包含 API Key、接口设置、价格表和其他偏好、日志及头像图片文件。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("备份含聊天正文，文件未加密，请选择可信的保存位置。",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("从备份文件恢复…") }
            state.restorePreview?.let { preview ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("恢复预览", style = MaterialTheme.typography.titleMedium)
                        Text("来源时间：${Instant.ofEpochMilli(preview.manifest.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                        val counts = preview.manifest.counts
                        Text("会话 ${counts["conversations"]} · 消息 ${counts["messages"]} · 事件 ${counts["events"]}")
                        Text("人物卡 ${counts["personas"]} · 记忆 ${counts["memories"]} · 调用 ${counts["calls"]}")
                        Text("旧摘要 ${counts["summaries"]} · 摘要版本 ${counts["revisions"]} · 分段 ${counts["segments"]} · 用量 ${counts["usages"]}")
                        Text("未推送旧消息 ${preview.pendingMessages} 条，导入后不会自动发送；失效摘要 ${preview.disabledSummaries} 份会保留但停用。")
                        if (preview.commonMemories > 0) Text("包含 ${preview.commonMemories} 条通用记忆，导入后所有人物可使用。")
                        if (preview.previousImports > 0) Text("此备份此前已导入 ${preview.previousImports} 次；再次确认会新建另一份副本。",
                            color = MaterialTheme.colorScheme.error)
                        Text("接口、Key、价格表与其他设置不在文件中；本地头像图片也不包含。")
                        Button(onClick = { confirmRestore = true }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text("导入为副本…") }
                        TextButton(onClick = vm::cancelRestore, enabled = !state.busy) { Text("取消，不导入") }
                    }
                }
            }
            Button(onClick = vm::prepare, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.manifest == null) "整理备份" else "重新整理备份")
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.status.isNotBlank()) Text(state.status,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            state.manifest?.let { manifest ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val at = Instant.ofEpochMilli(manifest.createdAt).atZone(ZoneId.systemDefault())
                        Text("本次备份", style = MaterialTheme.typography.titleMedium)
                        Text(at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                        val names = linkedMapOf("conversations" to "会话", "messages" to "消息", "events" to "事件",
                            "personas" to "人物卡", "memories" to "记忆", "summaries" to "旧摘要",
                            "revisions" to "摘要版本", "segments" to "摘要分段", "usages" to "用量记录", "calls" to "调用记录")
                        names.forEach { (key, label) -> Text("$label：${manifest.counts[key] ?: 0}") }
                        Button(onClick = { save.launch("SoloChat-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip") },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存备份文件") }
                    }
                }
            }
            Text("调试包与发布包的数据独立，可用此文件导出与副本恢复迁移聊天资料；两边不会自动同步。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
