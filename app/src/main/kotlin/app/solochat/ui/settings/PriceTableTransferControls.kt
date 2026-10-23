package app.solochat.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.solochat.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入先预览、确认后只追加父页面草稿；文件不包含组 ID、接口或 Key。 */
@Composable
internal fun PriceTableTransferControls(table: PriceTable, onImport: (PriceTableDocument) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<PriceTableDocument?>(null) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    // 文件选择期间表可能变化，导出只使用用户点击那一刻的表。
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(exportText.isNotBlank()) { "导出草稿已失效，请重新导出" }
                    checkNotNull(context.contentResolver.openOutputStream(uri)) { "无法打开目标文件" }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(exportText) }
                }
                status = "已导出所选价格表"
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "导出失败，请重新选择文件" }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loading = true; preview = null; error = ""
            scope.launch {
                try {
                    preview = withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }
                            .use(PriceTableTransfer::read)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = if (failure is IllegalArgumentException) failure.message.orEmpty() else "读取失败，请重新选择文件" }
                finally { loading = false }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importing = true; pasted = ""; preview = null; error = "" }) { Text("导入价格表") }
        TextButton(onClick = {
            try {
                exportText = PriceTableTransfer.encode(table)
                error = ""
                export.launch("solochat-prices.json")
            } catch (_: Exception) { error = "此表无法导出，请检查条目或拆分大表" }
        }) { Text("导出当前表") }
    }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    if (!importing && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

    if (importing) AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = { if (!loading) importing = false },
        title = { Text("导入到当前接口组") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持 SoloChat 单表 JSON（人民币 / 百万 token，最大 2 MiB）。导入只新增一张表，不覆盖已有表；仍需保存价格页后生效。")
            // 部分系统文件管理器将 .json 标为未知类型；类型不作信任依据，选中后严格校验内容。
            OutlinedButton(enabled = !loading, onClick = { pick.launch(arrayOf("*/*")) }) {
                Text(if (loading) "读取中…" else "选择 JSON 文件")
            }
            OutlinedTextField(pasted, onValueChange = { pasted = it; preview = null; error = "" },
                enabled = !loading, label = { Text("或粘贴价格表 JSON") }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !loading && pasted.isNotBlank(), onClick = {
                runCatching { PriceTableTransfer.decode(pasted) }.onSuccess { preview = it; error = "" }
                    .onFailure { preview = null; error = it.message ?: "价格表格式不正确" }
            }) { Text("检查粘贴内容") }
            preview?.let { Text("将新增：${it.name} · ${it.entries.size} 项模型价格\n加入并选为本组草稿，其他接口组不受影响。") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !loading && preview != null, onClick = {
            preview?.let(onImport)
            importing = false; status = "已加入本组草稿，请保存价格页"
        }) { Text("加入本组草稿") } },
        dismissButton = { TextButton(enabled = !loading, onClick = { importing = false }) { Text("取消") } }
    )
}
