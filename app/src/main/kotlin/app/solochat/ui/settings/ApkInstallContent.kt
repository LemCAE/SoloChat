package app.solochat.ui.settings

import app.solochat.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.solochat.data.update.ApkDownloadState
import app.solochat.data.update.ApkInspection
import app.solochat.data.update.ApkInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ApkInstallContent(download: ApkDownloadState, onBackup: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val installer = remember(context) { ApkInstaller(context) }
    val file = download.file ?: return
    val size = download.target?.asset?.size ?: return
    val scope = rememberCoroutineScope()
    var inspection by remember(file) { mutableStateOf<ApkInspection?>(null) }
    var revision by remember(file) { mutableIntStateOf(0) }
    var busy by remember(file) { mutableStateOf(false) }
    var confirm by remember(file) { mutableStateOf(false) }
    var message by remember(file) { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        message = if (installer.canInstall()) stringsContext.getString(R.string.ui_apkinstallcontent_5a2c2d4b) else stringsContext.getString(R.string.ui_apkinstallcontent_398e2ea2)
    }
    val systemInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        message = stringsContext.getString(R.string.ui_apkinstallcontent_7607d91a)
    }
    LaunchedEffect(file, revision) {
        inspection = null
        inspection = withContext(Dispatchers.IO) { installer.inspect(file, size) }
    }
    Text(stringsContext.getString(R.string.ui_apkinstallcontent_cba809f7), style = MaterialTheme.typography.titleSmall)
    inspection?.let { check ->
        check.candidate?.let { apk -> Text(stringsContext.getString(R.string.ui_apkinstallcontent_4a005862, apk.versionName, apk.versionCode)) }
        Text(check.compatibility.message,
            color = if (check.compatibility.allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        if (check.compatibility.allowed) {
            Button(enabled = !busy, onClick = { confirm = true }) { Text(if (busy) stringsContext.getString(R.string.ui_apkinstallcontent_0bc20103) else stringsContext.getString(R.string.ui_apkinstallcontent_a1fb7577)) }
        }
    } ?: Text(stringsContext.getString(R.string.ui_apkinstallcontent_a72ee14a))
    TextButton(onClick = onBackup) { Text(stringsContext.getString(R.string.ui_apkinstallcontent_033d9221)) }
    if (message.isNotEmpty()) Text(message)
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringsContext.getString(R.string.ui_apkinstallcontent_d76f76f1)) },
        text = { Column {
            Text(stringsContext.getString(R.string.ui_apkinstallcontent_4c6156a6))
            TextButton(onClick = { confirm = false; onBackup() }) { Text(stringsContext.getString(R.string.ui_apkinstallcontent_fabfe056)) }
        } },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch {
                busy = true
                try {
                    // 来源授权只打开系统设置；返回后由用户再次点击，不自动启动安装。
                    if (!installer.canInstall()) {
                        permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")))
                    } else {
                        val intent = withContext(Dispatchers.IO) { installer.installIntent(file, size) }
                        systemInstall.launch(intent)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = (e as? java.io.IOException)?.message ?: stringsContext.getString(R.string.ui_apkinstallcontent_1026b411) }
                finally { busy = false }
            }
        }) { Text(stringsContext.getString(R.string.ui_apkinstallcontent_0a2ddf12)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringsContext.getString(R.string.ui_apkinstallcontent_65a6ca9f)) } }
    )
}
