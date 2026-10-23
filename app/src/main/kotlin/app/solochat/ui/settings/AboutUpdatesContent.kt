package app.solochat.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.solochat.BuildConfig
import app.solochat.core.update.*
import app.solochat.data.update.GitHubReleaseClient
import app.solochat.data.update.AppApkDownload
import app.solochat.data.update.DownloadPhase
import app.solochat.data.update.DownloadTarget
import app.solochat.ui.SettingsFormGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class UpdateUiState(val checking: Boolean = false, val includePrerelease: Boolean = false,
    val result: ReleaseCheck? = null, val error: String = "", val checkedAt: String = "")

/** 检查仅由按键启动，重建页面共享同一任务，不后台轮询。 */
class UpdateViewModel(private val fetch: suspend () -> List<ReleaseInfo> = GitHubReleaseClient()::releases) : ViewModel() {
    private val mutable = MutableStateFlow(UpdateUiState())
    internal val state = mutable.asStateFlow()
    fun channel(include: Boolean) {
        if (!mutable.value.checking) mutable.value = UpdateUiState(includePrerelease = include)
    }
    fun check() {
        if (mutable.value.checking) return
        val include = mutable.value.includePrerelease
        mutable.value = mutable.value.copy(checking = true, result = null, error = "", checkedAt = "")
        viewModelScope.launch {
            try {
                val result = checkReleases(fetch(), BuildConfig.VERSION_NAME, include)
                mutable.value = mutable.value.copy(result = result,
                    checkedAt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: java.io.IOException) { mutable.value = mutable.value.copy(error = e.message ?: "连接失败，请重试") }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "无法读取 GitHub 发布信息，请重试或打开版本页") }
            finally { mutable.value = mutable.value.copy(checking = false) }
        }
    }
}

@Composable
internal fun AboutUpdatesContent(onBackup: () -> Unit, model: UpdateViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val downloads = remember(context.applicationContext) { AppApkDownload.get(context) }
    val download by downloads.state.collectAsState()
    val uri = LocalUriHandler.current
    var openError by remember { mutableStateOf("") }
    fun open(url: String) { openError = if (runCatching { uri.openUri(url) }.isFailure) "无法打开浏览器，请检查浏览器是否可用" else "" }
    SettingsFormGroup("当前安装") {
        Text("SoloChat ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text("版本号 ${BuildConfig.VERSION_CODE} · ${if (BuildConfig.DEBUG) "调试版" else "发布版"}")
        Text("包名：${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
    }
    SettingsFormGroup("GitHub 更新") {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("包含预发布版本", Modifier.weight(1f))
            Switch(checked = state.includePrerelease, enabled = !state.checking, onCheckedChange = model::channel)
        }
        Button(enabled = !state.checking, onClick = model::check) { Text(if (state.checking) "正在检查…" else "检查更新") }
        if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
        state.result?.let { result ->
            Text(when (result.status) {
                ReleaseStatus.NO_RELEASE -> if (state.includePrerelease) "尚无可用的公开发布" else "尚无正式发布，可选择包含预发布后再检查"
                ReleaseStatus.NEWER_AVAILABLE -> "发现更新版本"
                ReleaseStatus.SAME_VERSION -> "当前版本与所选渠道版本相同"
                ReleaseStatus.LOCAL_NEWER -> "当前版本高于所选渠道版本"
                ReleaseStatus.UNCOMPARABLE -> "版本标签无法比较，请查看发布说明"
            }, style = MaterialTheme.typography.titleSmall)
            result.release?.let { release ->
                Text("${release.tag} · ${if (release.prerelease) "预发布" else "正式版"}")
                if (release.publishedAt != null) {
                    val published = runCatching { java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(release.publishedAt)) }
                        .getOrDefault(release.publishedAt)
                    Text("发布时间：$published", style = MaterialTheme.typography.bodySmall)
                }
                Text(if (release.apkReady) "安装包已就绪" else "版本已发布，安装包尚未就绪，可稍后重新检查",
                    style = MaterialTheme.typography.bodySmall)
                release.apkAsset?.let { asset ->
                    val target = DownloadTarget(release.tag, asset)
                    val alreadyDownloaded = download.target == target && download.phase == DownloadPhase.COMPLETE && download.file?.isFile == true
                    Button(enabled = download.phase != DownloadPhase.DOWNLOADING && !alreadyDownloaded,
                        onClick = { downloads.start(release) }) {
                        Text(if (alreadyDownloaded) "此版本已下载" else "下载此版本 APK")
                    }
                }
                var expanded by remember(release.tag) { mutableStateOf(false) }
                if (release.body.isNotBlank()) {
                    Text(release.body, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起更新说明" else "展开更新说明") }
                }
                val page = release.pageUrl.takeIf { it.startsWith("https://github.com/LemCAE/SoloChat/releases/tag/") }
                    ?: "https://github.com/LemCAE/SoloChat/releases"
                TextButton(onClick = { open(page) }) { Text("打开此版本发布页") }
            }
            Text("检查时间：${state.checkedAt}", style = MaterialTheme.typography.bodySmall)
        }
        download.target?.let { target ->
            HorizontalDivider()
            Text("下载：${target.tag} · ${target.asset.name}", style = MaterialTheme.typography.titleSmall)
            when (download.phase) {
                DownloadPhase.DOWNLOADING -> {
                    val fraction = (download.received.toDouble() / target.asset.size).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}% · ${download.received / 1024} / ${target.asset.size / 1024} KB")
                    TextButton(onClick = downloads::cancel) { Text("取消下载") }
                }
                DownloadPhase.CANCELED, DownloadPhase.FAILED -> {
                    Text(if (download.phase == DownloadPhase.CANCELED) "下载已取消" else download.error,
                        color = if (download.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = downloads::retry) { Text("重新下载此版本") }
                }
                DownloadPhase.COMPLETE -> {
                    if (download.file?.isFile == true) ApkInstallContent(download, onBackup)
                    else {
                        Text("下载文件已被系统清理，请重新下载。")
                        TextButton(onClick = downloads::retry) { Text("重新下载此版本") }
                    }
                }
                DownloadPhase.IDLE -> Unit
            }
        }
        TextButton(onClick = { open("https://github.com/LemCAE/SoloChat/releases") }) { Text("打开 GitHub 版本列表") }
        if (openError.isNotEmpty()) Text(openError, color = MaterialTheme.colorScheme.error)
        Text("仅在点击后下载，离开此页仍继续；应用进程结束后需重新下载，不自动续传。覆盖升级还取决于安装包版本号、包名和签名，请先备份资料。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
