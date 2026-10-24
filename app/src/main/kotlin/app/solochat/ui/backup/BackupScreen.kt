package app.solochat.ui.backup

import app.solochat.R
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.solochat.SoloChatApp
import app.solochat.ui.SettingsFormGroup
import app.solochat.ui.ToolTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 先校验和预览，再明确确认恢复；导出和副本恢复复用同一版本化 ZIP。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val app = LocalContext.current.applicationContext as SoloChatApp
    val vm: BackupViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::save)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::readRestore)
    var confirmRestore by remember { mutableStateOf(false) }
    if (confirmRestore && state.restorePreview != null) {
        AlertDialog(onDismissRequest = { if (!state.busy) confirmRestore = false },
            title = { Text(stringsContext.getString(R.string.ui_backupscreen_48143c1e)) },
            text = { Text(stringsContext.getString(R.string.ui_backupscreen_0b09bafe)) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = {
                confirmRestore = false
                vm.restore()
            }) { Text(stringsContext.getString(R.string.ui_backupscreen_d5645dc9)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { confirmRestore = false }) { Text(stringsContext.getString(R.string.ui_backupscreen_c1c2a6c5)) } })
    }
    BackHandler(enabled = state.busy) { }
    Scaffold(topBar = {
        ToolTopBar(title = stringsContext.getString(R.string.ui_backupscreen_aa1025cc), onBack = onBack, backEnabled = !state.busy)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.status.isNotBlank()) Text(state.status,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsFormGroup(stringsContext.getString(R.string.ui_backupscreen_dcf6661d)) {
                Text(stringsContext.getString(R.string.ui_backupscreen_55ba8090), style = MaterialTheme.typography.bodyMedium)
                state.manifest?.let { manifest ->
                    val at = Instant.ofEpochMilli(manifest.createdAt).atZone(ZoneId.systemDefault())
                    Text(stringsContext.getString(R.string.ui_backupscreen_26a4c414, at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BackupCounts(manifest.counts, manifest.id)
                    Button(onClick = { save.launch("SoloChat-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip") },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_backupscreen_7456275e)) }
                }
                if (state.manifest == null) Button(onClick = vm::prepare, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_backupscreen_04535fbe)) }
                else TextButton(onClick = vm::prepare, enabled = !state.busy) { Text(stringsContext.getString(R.string.ui_backupscreen_dbdbcbbf)) }
            }
            SettingsFormGroup(stringsContext.getString(R.string.ui_backupscreen_578a902e)) {
                Text(stringsContext.getString(R.string.ui_backupscreen_16440d67), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_backupscreen_bb258e2c)) }
                state.restorePreview?.let { preview ->
                    HorizontalDivider()
                    Text(stringsContext.getString(R.string.ui_backupscreen_e751aaed), style = MaterialTheme.typography.titleMedium)
                    Text(stringsContext.getString(R.string.ui_backupscreen_5a1810ef, Instant.ofEpochMilli(preview.manifest.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),
                        style = MaterialTheme.typography.bodySmall)
                    BackupCounts(preview.manifest.counts, preview.manifest.id)
                    Text(stringsContext.getString(R.string.ui_backupscreen_88becf83, preview.pendingMessages, preview.disabledSummaries))
                    if (preview.commonMemories > 0) Text(stringsContext.getString(R.string.ui_backupscreen_969d9fc5, preview.commonMemories))
                    if (preview.previousImports > 0) Text(stringsContext.getString(R.string.ui_backupscreen_421da7ef, preview.previousImports),
                        color = MaterialTheme.colorScheme.error)
                    Text(stringsContext.getString(R.string.ui_backupscreen_a93bf305),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { confirmRestore = true }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(stringsContext.getString(R.string.ui_backupscreen_743c5482)) }
                    TextButton(onClick = vm::cancelRestore, enabled = !state.busy) { Text(stringsContext.getString(R.string.ui_backupscreen_4e99f759)) }
                }
            }
            SettingsFormGroup(stringsContext.getString(R.string.ui_backupscreen_07ec0f8e)) {
                Text(stringsContext.getString(R.string.ui_backupscreen_e8f35f16))
                Text(stringsContext.getString(R.string.ui_backupscreen_69b03d47),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringsContext.getString(R.string.ui_backupscreen_77ef869e), style = MaterialTheme.typography.bodyMedium)
                Text(stringsContext.getString(R.string.ui_backupscreen_bc71b67f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 核心资料先展示，协议明细按需展开；恢复风险提示不放入折叠区。 */
@Composable
private fun BackupCounts(counts: Map<String, Int>, backupId: String) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable(backupId) { mutableStateOf(false) }
    Text(stringsContext.getString(R.string.ui_backupscreen_2e5549bc, counts["conversations"] ?: 0, counts["messages"] ?: 0))
    Text(stringsContext.getString(R.string.ui_backupscreen_dbc471b0, counts["personas"] ?: 0, counts["memories"] ?: 0))
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringsContext.getString(R.string.ui_backupscreen_d13b4fcc) else stringsContext.getString(R.string.ui_backupscreen_c9c6f0e0)) }
    if (expanded) {
        val names = linkedMapOf("events" to stringsContext.getString(R.string.ui_backupscreen_a7b41862), "summaries" to stringsContext.getString(R.string.ui_backupscreen_501bdd80), "revisions" to stringsContext.getString(R.string.ui_backupscreen_b63eb53b),
            "segments" to stringsContext.getString(R.string.ui_backupscreen_126db525), "usages" to stringsContext.getString(R.string.ui_backupscreen_711e6421), "calls" to stringsContext.getString(R.string.ui_backupscreen_551826e7))
        names.forEach { (key, label) -> Text("$label：${counts[key] ?: 0}", style = MaterialTheme.typography.bodySmall) }
    }
}
