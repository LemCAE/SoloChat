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

/** 导出入口：先展示范围，整理成功后才提供系统文件保存；尚未开放副本恢复。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SoloChatApp
    val vm: BackupViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::save)
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
            Text("恢复功能正在接入，当前仅支持导出。后续恢复将创建独立副本。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
