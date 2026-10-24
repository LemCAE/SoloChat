package app.solochat.ui.settings

import app.solochat.R
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
    fun check(connectionError: String, fetchError: String) {
        if (mutable.value.checking) return
        val include = mutable.value.includePrerelease
        mutable.value = mutable.value.copy(checking = true, result = null, error = "", checkedAt = "")
        viewModelScope.launch {
            try {
                val result = checkReleases(fetch(), BuildConfig.VERSION_NAME, include)
                mutable.value = mutable.value.copy(result = result,
                    checkedAt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: java.io.IOException) { mutable.value = mutable.value.copy(error = e.message ?: connectionError) }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = fetchError) }
            finally { mutable.value = mutable.value.copy(checking = false) }
        }
    }
}

@Composable
internal fun AboutUpdatesContent(onBackup: () -> Unit, model: UpdateViewModel = viewModel()) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val downloads = remember(context.applicationContext) { AppApkDownload.get(context) }
    val download by downloads.state.collectAsState()
    val uri = LocalUriHandler.current
    var openError by remember { mutableStateOf("") }
    fun open(url: String) { openError = if (runCatching { uri.openUri(url) }.isFailure) stringsContext.getString(R.string.ui_aboutupdatescontent_554bb9f4) else "" }
    SettingsFormGroup(stringsContext.getString(R.string.ui_aboutupdatescontent_4edd5849)) {
        Text(stringsContext.getString(R.string.ui_about_app_version, stringsContext.getString(R.string.app_name), BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
        Text(stringsContext.getString(R.string.ui_aboutupdatescontent_89194029, BuildConfig.VERSION_CODE, if (BuildConfig.DEBUG) stringsContext.getString(R.string.ui_update_debug) else stringsContext.getString(R.string.ui_update_release)))
        Text(stringsContext.getString(R.string.ui_aboutupdatescontent_85485f3d, BuildConfig.APPLICATION_ID), style = MaterialTheme.typography.bodySmall)
    }
    SettingsFormGroup(stringsContext.getString(R.string.ui_aboutupdatescontent_39427385)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringsContext.getString(R.string.ui_aboutupdatescontent_b481f0ef), Modifier.weight(1f))
            Switch(checked = state.includePrerelease, enabled = !state.checking, onCheckedChange = model::channel)
        }
        Button(enabled = !state.checking, onClick = { model.check(stringsContext.getString(R.string.ui_update_connection_error), stringsContext.getString(R.string.ui_update_fetch_error)) }) { Text(if (state.checking) stringsContext.getString(R.string.ui_aboutupdatescontent_b96e960f) else stringsContext.getString(R.string.ui_aboutupdatescontent_2adb5e2e)) }
        if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
        state.result?.let { result ->
            Text(when (result.status) {
                ReleaseStatus.NO_RELEASE -> if (state.includePrerelease) stringsContext.getString(R.string.ui_aboutupdatescontent_40c850af) else stringsContext.getString(R.string.ui_aboutupdatescontent_6c735b65)
                ReleaseStatus.NEWER_AVAILABLE -> stringsContext.getString(R.string.ui_aboutupdatescontent_023a53eb)
                ReleaseStatus.SAME_VERSION -> stringsContext.getString(R.string.ui_aboutupdatescontent_1c3cfa30)
                ReleaseStatus.LOCAL_NEWER -> stringsContext.getString(R.string.ui_aboutupdatescontent_43ef14c1)
                ReleaseStatus.UNCOMPARABLE -> stringsContext.getString(R.string.ui_aboutupdatescontent_85c7f0d0)
            }, style = MaterialTheme.typography.titleSmall)
            result.release?.let { release ->
                Text(stringsContext.getString(R.string.ui_aboutupdatescontent_e37af565, release.tag, if (release.prerelease) stringsContext.getString(R.string.ui_update_prerelease) else stringsContext.getString(R.string.ui_update_stable)))
                if (release.publishedAt != null) {
                    val published = runCatching { java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(release.publishedAt)) }
                        .getOrDefault(release.publishedAt)
                    Text(stringsContext.getString(R.string.ui_aboutupdatescontent_3a3d30c7, published), style = MaterialTheme.typography.bodySmall)
                }
                Text(if (release.apkReady) stringsContext.getString(R.string.ui_aboutupdatescontent_69de8be6) else stringsContext.getString(R.string.ui_aboutupdatescontent_8c8ed746),
                    style = MaterialTheme.typography.bodySmall)
                release.apkAsset?.let { asset ->
                    val target = DownloadTarget(release.tag, asset)
                    val alreadyDownloaded = download.target == target && download.phase == DownloadPhase.COMPLETE && download.file?.isFile == true
                    Button(enabled = download.phase != DownloadPhase.DOWNLOADING && !alreadyDownloaded,
                        onClick = { downloads.start(release) }) {
                        Text(if (alreadyDownloaded) stringsContext.getString(R.string.ui_aboutupdatescontent_b9317afb) else stringsContext.getString(R.string.ui_aboutupdatescontent_6c6209c6))
                    }
                }
                var expanded by remember(release.tag) { mutableStateOf(false) }
                if (release.body.isNotBlank()) {
                    Text(release.body, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringsContext.getString(R.string.ui_aboutupdatescontent_9990284b) else stringsContext.getString(R.string.ui_aboutupdatescontent_3492833f)) }
                }
                val page = release.pageUrl.takeIf { it.startsWith("https://github.com/LemCAE/SoloChat/releases/tag/") }
                    ?: "https://github.com/LemCAE/SoloChat/releases"
                TextButton(onClick = { open(page) }) { Text(stringsContext.getString(R.string.ui_aboutupdatescontent_e49c83b1)) }
            }
            Text(stringsContext.getString(R.string.ui_aboutupdatescontent_102725a4, state.checkedAt), style = MaterialTheme.typography.bodySmall)
        }
        download.target?.let { target ->
            HorizontalDivider()
            Text(stringsContext.getString(R.string.ui_aboutupdatescontent_f77d0c6a, target.tag, target.asset.name), style = MaterialTheme.typography.titleSmall)
            when (download.phase) {
                DownloadPhase.DOWNLOADING -> {
                    val fraction = (download.received.toDouble() / target.asset.size).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringsContext.getString(R.string.ui_update_download_progress, (fraction * 100).toInt(), download.received / 1024, target.asset.size / 1024))
                    TextButton(onClick = downloads::cancel) { Text(stringsContext.getString(R.string.ui_aboutupdatescontent_cb126bce)) }
                }
                DownloadPhase.CANCELED, DownloadPhase.FAILED -> {
                    Text(if (download.phase == DownloadPhase.CANCELED) stringsContext.getString(R.string.ui_aboutupdatescontent_eda92636) else download.error,
                        color = if (download.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = downloads::retry) { Text(stringsContext.getString(R.string.ui_aboutupdatescontent_bd671d5a)) }
                }
                DownloadPhase.COMPLETE -> {
                    if (download.file?.isFile == true) ApkInstallContent(download, onBackup)
                    else {
                        Text(stringsContext.getString(R.string.ui_aboutupdatescontent_c1c91f0b))
                        TextButton(onClick = downloads::retry) { Text(stringsContext.getString(R.string.ui_aboutupdatescontent_bd671d5a)) }
                    }
                }
                DownloadPhase.IDLE -> Unit
            }
        }
        TextButton(onClick = { open("https://github.com/LemCAE/SoloChat/releases") }) { Text(stringsContext.getString(R.string.ui_aboutupdatescontent_8e419859)) }
        if (openError.isNotEmpty()) Text(openError, color = MaterialTheme.colorScheme.error)
        Text(stringsContext.getString(R.string.ui_aboutupdatescontent_39500a58),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
