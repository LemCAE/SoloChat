package app.solochat.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.solochat.SoloChatApp
import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupManifest
import app.solochat.core.backup.BackupArchive
import app.solochat.core.backup.BackupTargetIds
import app.solochat.core.backup.planBackupImport
import app.solochat.data.backup.RoomBackupReader
import app.solochat.data.backup.RoomBackupImporter
import app.solochat.core.model.MessageRole
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
    val failed: Boolean = false,
    val restorePreview: BackupRestorePreview? = null
)

data class BackupRestorePreview(val manifest: BackupManifest, val previousImports: Int,
    val disabledSummaries: Int, val commonMemories: Int, val pendingMessages: Int)

/** 暂存文件属于当前页面的 ViewModel；旋屏/选文件期间保留，离页后清理。 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SoloChatApp
    private val mutableState = MutableStateFlow(BackupExportState())
    val state = mutableState.asStateFlow()
    private var staged: File? = null
    private var restoreArchive: BackupArchive? = null

    fun readRestore(uri: Uri?) {
        if (uri == null || state.value.busy) return
        restoreArchive = null
        mutableState.value = state.value.copy(busy = true, failed = false,
            restorePreview = null, status = "正在读取并校验备份…")
        viewModelScope.launch {
            try {
                val (archive, preview) = withContext(Dispatchers.IO) {
                    val archive = (app.contentResolver.openInputStream(uri) ?: error("无法打开备份文件"))
                        .use(BackupCodec::read)
                    val plan = planBackupImport(archive, BackupTargetIds(0, 0, 0))
                    archive to BackupRestorePreview(archive.manifest,
                        app.db.backup().previousImports(archive.manifest.id), plan.disabledRevisions.size,
                        archive.data.memories.count { it.personaId.isBlank() },
                        archive.data.messages.count { it.role == MessageRole.USER && !it.sentToModel })
                }
                restoreArchive = archive
                mutableState.value = state.value.copy(busy = false, restorePreview = preview,
                    status = "文件校验通过，请核对下方预览后确认导入。")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = "无法读取备份：${e.message ?: "文件不可用"}")
            }
        }
    }

    fun cancelRestore() {
        if (state.value.busy) return
        restoreArchive = null
        mutableState.value = state.value.copy(restorePreview = null, status = "已取消，未导入任何资料。", failed = false)
    }

    fun restore() {
        if (state.value.busy) return
        val archive = restoreArchive ?: return
        mutableState.value = state.value.copy(busy = true, failed = false, status = "正在创建副本…")
        viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { RoomBackupImporter(app.db).restore(archive, app.clock.now()) }
                restoreArchive = null
                staged?.delete()
                staged = null
                mutableState.value = BackupExportState(status = "已导入 ${plan.data.conversations.size} 个会话副本。请返回会话列表查看；接口、Key 和价格设置需另行配置。副本发送已暂停。")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = "导入未完成，写入已回滚：${e.message ?: "请稍后重试"}")
            }
        }
    }

    fun prepare() {
        if (state.value.busy) return
        restoreArchive = null
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
