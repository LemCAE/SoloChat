package app.solochat.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.solochat.SoloChatApp
import app.solochat.R
import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupManifest
import app.solochat.core.backup.BackupArchive
import app.solochat.core.backup.BackupTargetIds
import app.solochat.core.backup.planBackupImport
import app.solochat.data.backup.RoomBackupReader
import app.solochat.data.backup.RoomBackupImporter
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Conversation
import app.solochat.core.model.Persona
import app.solochat.data.RegistrySelection
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
    val restorePreview: BackupRestorePreview? = null,
    val pausedCopies: List<Conversation> = emptyList(),
    val repairPersonas: List<Persona> = emptyList(),
    val repairModels: List<Pair<RegistrySelection, String>> = emptyList()
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

    fun refreshPausedCopies() {
        viewModelScope.launch {
            runCatching {
                Triple(app.pausedBackupConversations(), app.availablePersonas(), app.availableModelSelections())
            }.onSuccess { (copies, personas, selections) ->
                val labels = selections.associateWith { selection ->
                    if (selection.state == "INHERIT") "使用本机默认模型" else {
                        val doc = app.settings.loadModelRegistryV2()
                        val model = doc?.models?.firstOrNull { it.providerId == selection.providerId && it.id == selection.modelId }
                        val provider = doc?.providers?.firstOrNull { it.id == selection.providerId }
                        "${provider?.name ?: selection.providerId} · ${model?.displayName ?: selection.modelId}"
                    }
                }
                mutableState.value = state.value.copy(pausedCopies = copies, repairPersonas = personas,
                    repairModels = selections.map { it to labels.getValue(it) })
            }.onFailure { error ->
                mutableState.value = state.value.copy(failed = true, status = "读取恢复副本失败：${error.message ?: "请重试"}")
            }
        }
    }

    fun repairCopy(conversationId: String, personaId: String, selection: RegistrySelection) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, failed = false, status = "正在检查恢复副本")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { app.repairAndResumeBackupConversation(conversationId, personaId, selection) }
                mutableState.value = state.value.copy(busy = false, status = "恢复副本已修复并解除暂停")
                refreshPausedCopies()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = "副本仍保持暂停：${e.message ?: "请检查人物、模型和密钥后重试"}")
                refreshPausedCopies()
            }
        }
    }

    fun readRestore(uri: Uri?) {
        if (uri == null || state.value.busy) return
        restoreArchive = null
        mutableState.value = state.value.copy(busy = true, failed = false,
            restorePreview = null, status = app.getString(R.string.ui_backup_status_reading))
        viewModelScope.launch {
            try {
                val (archive, preview) = withContext(Dispatchers.IO) {
                    val archive = (app.contentResolver.openInputStream(uri) ?: error(app.getString(R.string.ui_backup_error_open)))
                        .use(BackupCodec::read)
                    val plan = planBackupImport(archive, BackupTargetIds(0, 0, 0))
                    archive to BackupRestorePreview(archive.manifest,
                        app.db.backup().previousImports(archive.manifest.id), plan.disabledRevisions.size,
                        archive.data.memories.count { it.personaId.isBlank() },
                        archive.data.messages.count { it.role == MessageRole.USER && !it.sentToModel })
                }
                restoreArchive = archive
                mutableState.value = state.value.copy(busy = false, restorePreview = preview,
                    status = app.getString(R.string.ui_backup_status_validated))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = app.getString(R.string.ui_backup_error_read, e.message ?: app.getString(R.string.ui_backup_error_unavailable)))
            }
        }
    }

    fun cancelRestore() {
        if (state.value.busy) return
        restoreArchive = null
        mutableState.value = state.value.copy(restorePreview = null, status = app.getString(R.string.ui_backup_status_canceled), failed = false)
    }

    fun restore() {
        if (state.value.busy) return
        val archive = restoreArchive ?: return
        mutableState.value = state.value.copy(busy = true, failed = false, status = app.getString(R.string.ui_backup_status_restoring))
        viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { RoomBackupImporter(app.db).restore(archive, app.clock.now()) }
                restoreArchive = null
                staged?.delete()
                staged = null
                mutableState.value = BackupExportState(status = app.getString(R.string.ui_backup_status_restored, plan.data.conversations.size))
                refreshPausedCopies()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = app.getString(R.string.ui_backup_error_restore, e.message ?: app.getString(R.string.ui_backup_retry)))
            }
        }
    }

    fun prepare() {
        if (state.value.busy) return
        restoreArchive = null
        mutableState.value = BackupExportState(busy = true, status = app.getString(R.string.ui_backup_status_preparing))
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
                    status = app.getString(R.string.ui_backup_status_prepared))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = BackupExportState(status = app.getString(R.string.ui_backup_error_prepare, e.message ?: app.getString(R.string.ui_backup_retry)), failed = true)
            } finally {
                if (staged != candidate) candidate?.delete()
            }
        }
    }

    fun save(uri: Uri?) {
        if (uri == null || state.value.busy) return
        val file = staged
        if (file == null || !file.exists()) {
            mutableState.value = BackupExportState(status = app.getString(R.string.ui_backup_status_expired), failed = true)
            return
        }
        mutableState.value = state.value.copy(busy = true, status = app.getString(R.string.ui_backup_status_saving), failed = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt") ?: error(app.getString(R.string.ui_backup_error_target))
                    output.buffered().use { target -> file.inputStream().buffered().use { it.copyTo(target) } }
                }
                mutableState.value = state.value.copy(busy = false, status = app.getString(R.string.ui_backup_status_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = app.getString(R.string.ui_backup_error_save))
            }
        }
    }

    override fun onCleared() {
        staged?.delete()
    }
}
