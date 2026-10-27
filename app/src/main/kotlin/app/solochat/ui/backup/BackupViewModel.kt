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
import app.solochat.data.backup.BackupRestoreJournal
import app.solochat.data.backup.BackupRestoreRunner
import app.solochat.data.backup.BackupRestoreBootstrapGate
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Conversation
import app.solochat.core.model.Persona
import app.solochat.data.RegistrySelection
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope

data class BackupExportState(
    val busy: Boolean = false,
    val manifest: BackupManifest? = null,
    val status: String = "",
    val failed: Boolean = false,
    val restorePreview: BackupRestorePreview? = null,
    val pausedCopies: List<Conversation> = emptyList(),
    val repairPersonas: List<Persona> = emptyList(),
    val repairModels: List<Pair<RegistrySelection, String>> = emptyList(),
    val mediaCount: Int = 0,
    val mediaBytes: Long = 0,
    val archiveBytes: Long = 0,
    val pendingRestoreOperation: Boolean = false,
    val restoreCommittedNeedsCleanup: Boolean = false
)

data class BackupRestorePreview(val manifest: BackupManifest, val previousImports: Int,
    val disabledSummaries: Int, val commonMemories: Int, val pendingMessages: Int,
    val mediaCount: Int, val mediaBytes: Long, val archiveBytes: Long)

/** 未确认暂存由 ViewModel 管理；用户确认后先写私有续接日志，进程重建仍可继续同一恢复操作。 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SoloChatApp
    private val mutableState = MutableStateFlow(BackupExportState())
    val state = mutableState.asStateFlow()
    private var staged: File? = null
    private var restoreArchive: BackupArchive? = null
    private var restoreMediaFiles: Map<String, File> = emptyMap()
    private var restoreMediaDirectory: File? = null
    private var restoreImportId: String? = null
    private var stagedRestoreZip: File? = null
    private val restoreJournal = BackupRestoreJournal(File(app.filesDir, "backup-restore/pending"))
    private val bootstrapGate = BackupRestoreBootstrapGate()

    init {
        mutableState.value = state.value.copy(busy = true, status = "正在检查待续接的恢复操作")
        resumePendingRestore(bootstrapGate.begin())
    }

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
        if (!pageActionAllowed()) return
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
        if (!bootstrapGate.canStartPageAction()) {
            mutableState.value = state.value.copy(failed = true, status = "正在检查待续接的恢复操作，请稍后再选文件")
            return
        }
        if (restoreImportId != null || state.value.pendingRestoreOperation) {
            mutableState.value = state.value.copy(failed = true, status = "已有确认的恢复操作待续接；请先继续或取消该操作")
            return
        }
        clearRestoreStaging(deleteZip = true)
        restoreArchive = null
        mutableState.value = state.value.copy(busy = true, failed = false,
            restorePreview = null, status = app.getString(R.string.ui_backup_status_reading))
        viewModelScope.launch {
            var zip: File? = null
            try {
                zip = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "backup-import").apply {
                        check((isDirectory || mkdirs()) && isDirectory) { "无法准备备份暂存目录" }
                    }
                    val file = File.createTempFile("restore-", ".zip", directory)
                    try {
                        val input = app.contentResolver.openInputStream(uri) ?: error(app.getString(R.string.ui_backup_error_open))
                        input.use { source -> FileOutputStream(file).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 1024L * 1024 * 1024) { "备份压缩文件超过 1 GiB" }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } }
                        file
                    } catch (error: Throwable) { file.delete(); throw error }
                }
                val parsed = withContext(Dispatchers.IO) { parseRestoreZip(requireNotNull(zip)) }
                restoreArchive = parsed.archive
                restoreMediaFiles = parsed.mediaFiles
                restoreMediaDirectory = parsed.mediaDirectory
                stagedRestoreZip = zip
                restoreImportId = java.util.UUID.randomUUID().toString()
                mutableState.value = state.value.copy(busy = false, restorePreview = parsed.preview,
                    status = app.getString(R.string.ui_backup_status_validated))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = app.getString(R.string.ui_backup_error_read, e.message ?: app.getString(R.string.ui_backup_error_unavailable)))
            } finally {
                if (restoreArchive == null) withContext(NonCancellable + Dispatchers.IO) { zip?.delete() }
            }
        }
    }

    fun cancelRestore() {
        if (!pageActionAllowed()) return
        if (state.value.restoreCommittedNeedsCleanup) {
            retryRestoreCleanup()
            return
        }
        mutableState.value = state.value.copy(busy = true, status = "正在取消恢复操作")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { restoreJournal.clear() }
                restoreArchive = null
                clearRestoreStaging(deleteZip = true)
                restoreImportId = null
                mutableState.value = state.value.copy(busy = false, restorePreview = null, pendingRestoreOperation = false,
                    status = app.getString(R.string.ui_backup_status_canceled), failed = false)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = "恢复操作取消失败：${error.message ?: "请重试"}")
            }
        }
    }

    fun retryRestoreCleanup() {
        if (!pageActionAllowed() || !state.value.restoreCommittedNeedsCleanup) return
        mutableState.value = state.value.copy(busy = true, failed = false, status = "正在核对恢复结果并清理续接文件")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val record = restoreJournal.load() ?: error("恢复续接记录已不存在")
                    val committed = RoomBackupImporter(app.db).committedResult(record.importId, record.backupId, record.dataChecksum)
                    check(committed != null) { "Room 尚无已提交结果，请回到恢复预览继续恢复" }
                    withContext(NonCancellable) { restoreJournal.clear() }
                }
                restoreArchive = null
                restoreImportId = null
                clearRestoreStaging(deleteZip = true)
                mutableState.value = BackupExportState(status = app.getString(R.string.ui_backup_status_already_restored))
                refreshPausedCopies()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    pendingRestoreOperation = true, restoreCommittedNeedsCleanup = true,
                    status = "恢复结果已提交，但续接文件尚未清理：${error.message ?: "请重试"}")
            }
        }
    }

    fun restore() {
        if (!pageActionAllowed()) return
        val archive = restoreArchive ?: return
        val mediaFiles = restoreMediaFiles
        val importId = restoreImportId ?: return
        mutableState.value = state.value.copy(busy = true, failed = false, status = app.getString(R.string.ui_backup_status_restoring))
        viewModelScope.launch {
            try {
                val attempt = withContext(Dispatchers.IO) {
                    BackupRestoreRunner(restoreJournal).run(stagedRestoreZip, importId, archive.manifest) {
                        mutableState.value = state.value.copy(pendingRestoreOperation = true)
                        RoomBackupImporter(app.db).restore(archive, mediaFiles, app.media, app.clock.now(), importId)
                    }
                }
                if (attempt.cleanupError != null) {
                    mutableState.value = state.value.copy(busy = false, failed = true,
                        pendingRestoreOperation = true, restoreCommittedNeedsCleanup = true,
                        status = "恢复已提交，但续接文件清理失败；请重试完成确认")
                    return@launch
                }
                restoreArchive = null
                clearRestoreStaging(deleteZip = true)
                restoreImportId = null
                val status = if (attempt.result.alreadyCommitted) app.getString(R.string.ui_backup_status_already_restored)
                    else app.getString(R.string.ui_backup_status_restored, attempt.result.conversationCount)
                mutableState.value = BackupExportState(status = status)
                refreshPausedCopies()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false, failed = true,
                    status = app.getString(R.string.ui_backup_error_restore, e.message ?: app.getString(R.string.ui_backup_retry)))
            }
        }
    }

    fun prepare() {
        if (!pageActionAllowed()) return
        if (restoreImportId != null) {
            mutableState.value = state.value.copy(failed = true, status = "请先继续或取消待续接的恢复操作")
            return
        }
        restoreArchive = null
        clearRestoreStaging(deleteZip = true)
        mutableState.value = state.value.copy(busy = true, failed = false, restorePreview = null,
            status = app.getString(R.string.ui_backup_status_preparing))
        viewModelScope.launch {
            var candidate: File? = null
            try {
                val manifest = withContext(Dispatchers.IO) {
                    // 暂存放在 App 私有缓存，不把含聊天正文的中间文件放到公共目录。
                    val directory = File(app.cacheDir, "backup-export").apply { mkdirs() }
                    val file = File.createTempFile("solochat-", ".zip", directory)
                    candidate = file
                    val snapshot = RoomBackupReader(app.db).readWithMedia(app.media)
                    val manifest = file.outputStream().buffered().use {
                        BackupCodec.writeV3(snapshot.data, it, app.clock.now(), snapshot.mediaSources)
                    }
                    manifest to snapshot
                }
                staged?.delete()
                staged = candidate
                mutableState.value = BackupExportState(manifest = manifest.first,
                    mediaCount = manifest.second.data.media.attachments.size, mediaBytes = manifest.second.mediaBytes,
                    archiveBytes = candidate?.length() ?: 0,
                    status = app.getString(R.string.ui_backup_status_prepared))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(busy = false,
                    status = app.getString(R.string.ui_backup_error_prepare, e.message ?: app.getString(R.string.ui_backup_retry)), failed = true)
            } finally {
                if (staged != candidate) candidate?.delete()
            }
        }
    }

    fun save(uri: Uri?) {
        if (uri == null || !pageActionAllowed()) return
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
        bootstrapGate.invalidate()
        staged?.delete()
        clearRestoreStaging(deleteZip = true)
    }

    private data class ParsedRestore(val archive: BackupArchive, val preview: BackupRestorePreview,
        val mediaFiles: Map<String, File>, val mediaDirectory: File)

    /** 在 IO 调度器调用；读完所有媒体后再建预览，损坏图片不会进入确认/写库路径。 */
    private suspend fun parseRestoreZip(zipFile: File): ParsedRestore {
        val directory = File(app.cacheDir, "backup-import/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val mediaFiles = linkedMapOf<String, File>()
            val archive = zipFile.inputStream().buffered().use { source ->
                BackupCodec.read(source) { descriptor, mediaInput ->
                    val target = File(directory, "${descriptor.sha256}.part")
                    target.outputStream().buffered().use { output -> mediaInput.copyTo(output, 64 * 1024) }
                    require(mediaFiles.put(descriptor.entryKey, target) == null) { "备份媒体条目重复" }
                }
            }
            val plan = planBackupImport(archive, BackupTargetIds(0, 0, 0))
            archive.data.media.assets.distinctBy { it.entryKey }.forEach { descriptor ->
                app.media.validateBackupStaged(descriptor, mediaFiles.getValue(descriptor.entryKey))
            }
            val preview = BackupRestorePreview(archive.manifest,
                app.db.backup().previousImports(archive.manifest.id), plan.disabledRevisions.size,
                archive.data.memories.count { it.personaId.isBlank() },
                archive.data.messages.count { it.role == MessageRole.USER && !it.sentToModel },
                archive.data.media.attachments.size,
                archive.data.media.assets.distinctBy { it.entryKey }.sumOf { it.byteSize }, archive.compressedBytes)
            return ParsedRestore(archive, preview, mediaFiles, directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun resumePendingRestore(token: Long) {
        viewModelScope.launch {
            try {
                val journalExists = withContext(Dispatchers.IO) { restoreJournal.exists() }
                if (!bootstrapGate.isCurrent(token)) return@launch
                if (journalExists) {
                    mutableState.value = state.value.copy(pendingRestoreOperation = true)
                }
                val pending = withContext(Dispatchers.IO) { restoreJournal.load() }
                if (!bootstrapGate.isCurrent(token)) return@launch
                if (pending == null) {
                    if (bootstrapGate.finish(token)) {
                        mutableState.value = if (journalExists) state.value.copy(busy = false, failed = true,
                            pendingRestoreOperation = true, status = "恢复续接记录不完整；可取消此操作后重新选择备份")
                        else state.value.copy(busy = false, status = "", failed = false)
                    }
                    return@launch
                }
                restoreImportId = pending.importId
                val committed = withContext(Dispatchers.IO) {
                    RoomBackupImporter(app.db).committedResult(pending.importId, pending.backupId, pending.dataChecksum)
                }
                if (!bootstrapGate.isCurrent(token)) return@launch
                if (committed != null) {
                    try {
                        withContext(Dispatchers.IO) { restoreJournal.clear() }
                        restoreImportId = null
                        if (bootstrapGate.finish(token)) {
                            mutableState.value = BackupExportState(status = app.getString(R.string.ui_backup_status_already_restored))
                            refreshPausedCopies()
                        }
                    } catch (cleanupError: Exception) {
                        if (bootstrapGate.finish(token)) mutableState.value = state.value.copy(busy = false,
                            failed = true, pendingRestoreOperation = true, restoreCommittedNeedsCleanup = true,
                            status = "恢复已提交，但续接文件清理失败；请重试完成确认")
                    }
                    return@launch
                }
                val zip = withContext(Dispatchers.IO) { restoreJournal.sourceZip(pending) }
                val parsed = withContext(Dispatchers.IO) { parseRestoreZip(zip) }
                if (!bootstrapGate.isCurrent(token)) {
                    parsed.mediaDirectory.deleteRecursively()
                    return@launch
                }
                if (parsed.archive.manifest.id != pending.backupId || parsed.archive.manifest.dataChecksum != pending.dataChecksum) {
                    parsed.mediaDirectory.deleteRecursively()
                    error("续接归档与已确认恢复来源不一致")
                }
                restoreArchive = parsed.archive
                restoreMediaFiles = parsed.mediaFiles
                restoreMediaDirectory = parsed.mediaDirectory
                stagedRestoreZip = null // 续接源归档属于持久 journal，不能随 ViewModel 清理。
                if (bootstrapGate.finish(token)) mutableState.value = state.value.copy(busy = false,
                    restorePreview = parsed.preview, pendingRestoreOperation = true,
                    status = "检测到尚未完成的恢复操作；可继续同一副本恢复")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (bootstrapGate.finish(token)) mutableState.value = state.value.copy(busy = false, failed = true,
                    status = "恢复续接检查失败：${error.message ?: "请重试或取消该操作"}")
            }
        }
    }

    private fun clearRestoreStaging(deleteZip: Boolean) {
        val dirs = (restoreMediaFiles.values.mapNotNull { it.parentFile } + listOfNotNull(restoreMediaDirectory)).distinct()
        restoreMediaFiles = emptyMap()
        restoreMediaDirectory = null
        if (deleteZip) {
            stagedRestoreZip?.delete()
            stagedRestoreZip = null
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { dirs.forEach { it.deleteRecursively() } }
    }

    private fun pageActionAllowed(): Boolean = bootstrapGate.canStartPageAction() && !state.value.busy
}
