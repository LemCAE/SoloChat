package app.solochat.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.solochat.SoloChatApp
import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupManifest
import app.solochat.data.backup.RoomBackupReader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupExportState(
    val busy: Boolean = false,
    val manifest: BackupManifest? = null,
    val status: String = "",
    val failed: Boolean = false
)

/** 暂存文件属于当前页面的 ViewModel；旋屏/选文件期间保留，离页后清理。 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SoloChatApp
    private val mutableState = MutableStateFlow(BackupExportState())
    val state = mutableState.asStateFlow()
    private var staged: File? = null

    fun prepare() {
        if (state.value.busy) return
        mutableState.value = BackupExportState(busy = true, status = "正在整理备份…")
        viewModelScope.launch {
            var candidate: File? = null
            try {
                staged?.delete()
                staged = null
                val manifest = withContext(Dispatchers.IO) {
                    // 暂存放在 App 私有缓存，不把含聊天正文的中间文件放到公共目录。
                    val directory = File(app.cacheDir, "backup-export").apply { mkdirs() }
                    val file = File.createTempFile("solochat-", ".zip", directory)
                    candidate = file
                    val data = RoomBackupReader(app.db).read()
                    file.outputStream().buffered().use { BackupCodec.write(data, it, app.clock.now()) }
                }
                staged = candidate
                mutableState.value = BackupExportState(manifest = manifest,
                    status = "备份已整理，请选择保存位置。整理之后的新消息不包含在本文件中。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = BackupExportState(status = "整理失败：${e.message ?: "请稍后重试"}", failed = true)
            } finally {
                if (staged != candidate) candidate?.delete()
            }
        }
    }

    fun save(uri: Uri?) {
        if (uri == null || state.value.busy) return
        val file = staged
        if (file == null || !file.exists()) {
            mutableState.value = BackupExportState(status = "备份暂存已失效，请重新整理后保存。", failed = true)
            return
        }
        mutableState.value = state.value.copy(busy = true, status = "正在保存…", failed = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                    output.buffered().use { target -> file.inputStream().buffered().use { it.copyTo(target) } }
                }
                mutableState.value = state.value.copy(busy = false, status = "备份已保存。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = "保存失败，请换一个位置重试；目标位置可能留下不完整文件。")
            }
        }
    }

    override fun onCleared() {
        staged?.delete()
    }
}
