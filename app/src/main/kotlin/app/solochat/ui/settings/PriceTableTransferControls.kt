package app.solochat.ui.settings

import app.solochat.R
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
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
                    check(exportText.isNotBlank()) { stringsContext.getString(R.string.ui_pricetabletransfercontrols_42301d4e) }
                    checkNotNull(context.contentResolver.openOutputStream(uri)) { stringsContext.getString(R.string.ui_pricetabletransfercontrols_d5a7aa07) }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(exportText) }
                }
                status = stringsContext.getString(R.string.ui_pricetabletransfercontrols_4460e56b)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = stringsContext.getString(R.string.ui_pricetabletransfercontrols_fe23bc31) }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loading = true; preview = null; error = ""
            scope.launch {
                try {
                    preview = withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openInputStream(uri)) { stringsContext.getString(R.string.ui_pricetabletransfercontrols_f8b2243d) }
                            .use(PriceTableTransfer::read)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = if (failure is IllegalArgumentException) failure.message.orEmpty() else stringsContext.getString(R.string.ui_pricetabletransfercontrols_c8337350) }
                finally { loading = false }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importing = true; pasted = ""; preview = null; error = "" }) { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_1a84ac8f)) }
        TextButton(onClick = {
            try {
                exportText = PriceTableTransfer.encode(table)
                error = ""
                export.launch("solochat-prices.json")
            } catch (_: Exception) { error = stringsContext.getString(R.string.ui_pricetabletransfercontrols_8329d491) }
        }) { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_d197c6c5)) }
    }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    if (!importing && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

    if (importing) AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = { if (!loading) importing = false },
        title = { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_3cf96180)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_dc4780c5))
            // 部分系统文件管理器将 .json 标为未知类型；类型不作信任依据，选中后严格校验内容。
            OutlinedButton(enabled = !loading, onClick = { pick.launch(arrayOf("*/*")) }) {
                Text(if (loading) stringsContext.getString(R.string.ui_pricetabletransfercontrols_d0c16ad6) else stringsContext.getString(R.string.ui_pricetabletransfercontrols_9c0e3ee5))
            }
            OutlinedTextField(pasted, onValueChange = { pasted = it; preview = null; error = "" },
                enabled = !loading, label = { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_ce7d34bf)) }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !loading && pasted.isNotBlank(), onClick = {
                runCatching { PriceTableTransfer.decode(pasted) }.onSuccess { preview = it; error = "" }
                    .onFailure { preview = null; error = it.message ?: stringsContext.getString(R.string.ui_pricetabletransfercontrols_57738360) }
            }) { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_721deb4b)) }
            preview?.let { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_cf306a83, it.name, it.entries.size)) }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !loading && preview != null, onClick = {
            preview?.let(onImport)
            importing = false; status = stringsContext.getString(R.string.ui_pricetabletransfercontrols_b7125351)
        }) { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_df38ade2)) } },
        dismissButton = { TextButton(enabled = !loading, onClick = { importing = false }) { Text(stringsContext.getString(R.string.ui_pricetabletransfercontrols_65a6ca9f)) } }
    )
}
