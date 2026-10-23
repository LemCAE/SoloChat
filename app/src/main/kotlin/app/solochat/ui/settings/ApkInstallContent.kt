package app.solochat.ui.settings

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
        message = if (installer.canInstall()) "已允许安装来源，请再次点击安装。" else "尚未允许安装来源，可稍后再试。"
    }
    val systemInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        message = "已返回应用；是否完成安装请以系统结果与当前安装版本为准。"
    }
    LaunchedEffect(file, revision) {
        inspection = null
        inspection = withContext(Dispatchers.IO) { installer.inspect(file, size) }
    }
    Text("下载完成", style = MaterialTheme.typography.titleSmall)
    inspection?.let { check ->
        check.candidate?.let { apk -> Text("安装包：${apk.versionName} · 版本号 ${apk.versionCode}") }
        Text(check.compatibility.message,
            color = if (check.compatibility.allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        if (check.compatibility.allowed) {
            Button(enabled = !busy, onClick = { confirm = true }) { Text(if (busy) "正在准备…" else "安装") }
        }
    } ?: Text("正在检查包名、版本号与签名…")
    TextButton(onClick = onBackup) { Text("先备份资料") }
    if (message.isNotEmpty()) Text(message)
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("交给系统确认安装") },
        text = { Column {
            Text("建议先导出备份。同包名、同签名覆盖安装通常保留资料，最终结果由系统确认；不要卸载或清数据。")
            TextButton(onClick = { confirm = false; onBackup() }) { Text("打开本地备份") }
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
                catch (e: Exception) { message = (e as? java.io.IOException)?.message ?: "无法打开系统安装或授权页面，请重试。" }
                finally { busy = false }
            }
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
    )
}
