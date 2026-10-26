package app.solochat.ui.persona

import app.solochat.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.key
import app.solochat.ui.PersonaAvatar
import app.solochat.ui.personaLabel
import app.solochat.core.model.AvatarRef
import app.solochat.data.AvatarImages
import androidx.compose.foundation.layout.size
import app.solochat.ui.ToolTopBar
import app.solochat.ui.stopTextFlingPropagation
import app.solochat.ui.SettingsFormGroup
import app.solochat.ui.InfoButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.solochat.SoloChatApp
import app.solochat.core.model.Persona
import app.solochat.core.persona.PersonaDocument
import app.solochat.core.persona.PersonaDraft
import app.solochat.core.persona.applyTo
import app.solochat.core.persona.toDraft
import app.solochat.data.persona.PersonaImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [Persona] 被存下来时长什么样：11 个字段按固定顺序摊平成一个 `List<String>`。
 *
 * 统一转成 String 再存，而不是把 `String` / `Boolean` / `Long` 混在一个 `List<Any>` 里：
 * 混合类型的 List 进 Bundle 要走 `Serializable` 那条路，读回来是 `Any`，
 * 只能靠 `as String` 这样的强转，顺序错一格就是 `ClassCastException` 崩在恢复路径上 ——
 * 那是最难查的一类崩溃（只在"进程被回收后重建"时出现）。统一成 String 之后，
 * 读取侧全是 `String`，只有数字/布尔在最后一步显式转换，没有隐式类型假设。
 */
private fun personaToSaved(persona: Persona): List<String> = listOf(
    persona.id,
    persona.name,
    persona.tagline,
    persona.identity,
    persona.voice,
    persona.sceneVoice,
    persona.mannerisms,
    persona.surroundings,
    persona.depiction,
    persona.boundaries,
    persona.secretNotes,
    persona.avatar,
    persona.builtin.toString(),
    persona.createdAt.toString(),
    persona.updatedAt.toString()
)

/**
 * [personaToSaved] 摊平后的字段个数。见 [personaFromSaved] 里为什么要拿它做长度校验。
 *
 * ⚠️ 加字段时**必须同时改这里**：长度对不上就整体放弃（宁可丢草稿，也不要把
 * `depiction` 读成 `boundaries`）—— 那才是这套校验存在的意义。
 */
private const val PERSONA_SAVED_FIELD_COUNT = 15

/**
 * [personaToSaved] 的逆操作。字段对不上、或者数字/布尔读不出来，就返回 `null`。
 *
 * 为什么要判长度、为什么读不出来时返回 `null` 而不是抛异常：
 * `onSaveInstanceState` 存下来的东西**可能来自旧版本**。用户在后台被回收、
 * 这期间应用升级、再切回来时，系统会拿旧 Bundle 走新代码 —— 一旦这一版 `Persona`
 * 多/少了一个字段，或者字段顺序变了，直接下标就是 `IndexOutOfBoundsException`，
 * `toBooleanStrict()` 撞上 `"0"` 之类的串则是 `IllegalArgumentException`。
 * 这两种都会崩在**恢复路径**上，表现成"从后台切回来一打开就崩"，
 * 而且只在真机上偶发，是最难查的一类崩溃。
 *
 * 返回 `null` 等于告诉 `rememberSaveable`"这份存下来的值读不了"，
 * 它会退回用 `init` 的值：丢了草稿，但 App 还能用 —— 这是刻意选的取舍。
 */
private fun personaFromSaved(fields: List<String>): Persona? {
    if (fields.size != PERSONA_SAVED_FIELD_COUNT) return null
    // 只认 `Boolean.toString()` 写出来的那两个字面量，其它一律当"读不了"。
    // 不用 `toBooleanStrict()`：它对认不出的串是抛异常，而这里要的是"安静地放弃"。
    val builtin = when (fields[12]) {
        "true" -> true
        "false" -> false
        else -> return null
    }
    return Persona(
        id = fields[0],
        name = fields[1],
        tagline = fields[2],
        identity = fields[3],
        voice = fields[4],
        sceneVoice = fields[5],
        mannerisms = fields[6],
        surroundings = fields[7],
        depiction = fields[8],
        boundaries = fields[9],
        secretNotes = fields[10],
        avatar = fields[11],
        builtin = builtin,
        createdAt = fields[13].toLongOrNull() ?: return null,
        updatedAt = fields[14].toLongOrNull() ?: return null
    )
}

/**
 * [PersonaEditor] 里那份编辑草稿（`draft`）的 Saver。
 *
 * 为什么必须自己写：`draft` 是**整张人物卡的编辑内容**，是领域对象。
 * `rememberSaveable` 自带的 autoSaver 只认 Bundle 装得下的类型，装不下这个 data class；
 * 而它是这一页最不该丢的状态 —— 名字 / 头像 / 八段人设全是手敲或从模型那边粘过来的，
 * 丢了无法复原。见 [PersonaEditor] 里对旧写法的说明。
 */
private val PersonaSaver: Saver<Persona, Any> = listSaver<Persona, String>(
    save = { personaToSaved(it) },
    restore = { personaFromSaved(it) }
)

/**
 * [PersonaSaver] 的可空版本，专门给 `PersonaScreen` 的 `editing` 用
 * （`null` = 当前没在编辑，页面渲染卡片列表）。
 *
 * 为什么要单独一个：`Saver<Persona, Any>` **不能**当 `Saver<Persona?, Any>` 用 ——
 * `Saver<Original, Saveable>` 的两个类型参数都没有声明型变，`Persona` 和 `Persona?`
 * 在类型系统里就是两个不同的 `Original`。
 *
 * `save` 返回空 List 表示"没有东西要存"，`restore` 见到空 List 就还回 `null`，
 * 与 `rememberSaveable` 的约定一致（恢复不出值就退回 `init`，而 `init` 也是 `null`）。
 */
private val NullablePersonaSaver: Saver<Persona?, Any> = listSaver<Persona?, String>(
    save = { if (it == null) emptyList() else personaToSaved(it) },
    restore = { if (it.isEmpty()) null else personaFromSaved(it) }
)

/** 系统“创建文件”选择器，优先打开手机的 Download 文件夹。 */
private class CreatePersonaDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, input)
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

private fun personaJsonFileName(name: String): String {
    val safeName = name.trim().map { char ->
        if (char in "\\/:*?\"<>|" || char.code < 0x20) '_' else char
    }.joinToString("").trimEnd('.', ' ')
    val baseName = safeName.ifBlank { "人物卡" }
    return if (baseName.endsWith(".json", ignoreCase = true)) baseName else "$baseName.json"
}

/**
 * 人物卡管理。
 *
 * 表单照着 [Persona] 的**八段**来分（它是谁 / 怎么说话 / 面对面时怎么说话 / 习惯与小动作 /
 * 它身边的样子 / 怎么描写动作与景物 / 关系与边界 / 只有你知道的事），
 * 因为它们在提示词里就是八段 —— 让用户看着这几块调，效果和预期一致。
 * 后四段是**面对面模式的素材与笔法**，提示词里只在面对面模式注入（见 docs/03-persona-format.md）。
 *
 * ⚠️ 界面文案里**不用**第三人称性别代词（不写"她/他"）：一张人物卡可能属于任何人，
 * 而这一页是给所有卡用的。需要主语时就用「它」、或者干脆省略（"待的地方"）。
 *
 * "里设定"刻意单独放在最后并标注"不会直接说出来"：它是给模型的上下文，
 * 不是给对方看的资料，混在一起用户会以为写在这儿对方就能看到。
 *
 * ## 为什么要有「导入」
 *
 * 手写八段人设是件麻烦事，真实用法是**让模型写**。所以这里给了一条完整的路：
 * 「格式说明」里复制模板 → 丢给模型 → 把结果存成文件 → 回来「导入文档」。
 * 解析用 `core/persona/PersonaDocument`：**正式格式是 JSON**（键名与字段一一对应），
 * Markdown 也仍读得进来（别名标题、`键：值` 表单、代码块包裹、多张卡），
 * 但**读错了会明确告诉你哪里没读懂**（见导入预览里的提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScopeCompat()
    val clipboard = LocalClipboardManager.current

    // 卡片列表是领域数据，来自 store，重建后由 `LaunchedEffect` 重新查一遍，不需要 Saver。
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }

    // ⚠️ `editing` 必须跨重建存活，否则 [PersonaEditor] 里那份草稿的保存**根本没有意义**：
    // 重建后 `editing` 回 `null`，这一页会直接渲染成卡片列表，编辑器压根不再进 composition，
    // `PersonaEditor` 自己那份 `rememberSaveable` 的恢复也就无从发生 ——
    // 用户看到的现象仍然是"屏幕转了一下，我刚写的八段人设没了"。
    //
    // 它是领域对象，所以要自己写 Saver（见 [NullablePersonaSaver]）。
    var editing by rememberSaveable(stateSaver = NullablePersonaSaver) {
        mutableStateOf<Persona?>(null)
    }

    // 下面三个是纯 UI 状态，顺带一起保住（都是 Bundle 装得下的类型）：
    // - `menuOpen` / `templateOpen`：用户正在看「格式说明」时屏幕一转，
    //   对话框不该自己关掉 —— 那份模板是照着抄格式用的，关掉就得再翻一次菜单。
    // - `hint`：它承载的是**上一步操作的结果**（"已复制模板…" / "导入失败：…"），
    //   重建后没了，用户根本不知道刚才那下到底成没成。
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var templateOpen by rememberSaveable { mutableStateOf(false) }
    var hint by rememberSaveable { mutableStateOf("") }
    var exportHint by rememberSaveable { mutableStateOf("") }
    var exportJson by rememberSaveable { mutableStateOf("") }
    var exportName by rememberSaveable { mutableStateOf("") }

    // `preview` 刻意留在 `remember`：它是"刚解析出来的导入结果"，
    // 里面装的是整批待确认的 [Persona] 和解析警告，要做 Saver 得先给
    // `ImportPreview` 也写一个，而它的生命周期本来就只到"用户点确认/算了"为止
    //（真正落库的是 `PersonaImporter` 的解析结果，用户重新选一次文件即可）。
    var preview by remember { mutableStateOf<ImportPreview?>(null) }

    suspend fun reload() {
        personas = app.store.listPersonas()
    }

    LaunchedEffect(Unit) { reload() }

    val exportDocument = rememberLauncherForActivityResult(CreatePersonaDocument()) { uri ->
        if (uri == null) {
            exportJson = ""
            exportName = ""
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                        "无法打开所选文件"
                    }.bufferedWriter(Charsets.UTF_8).use { it.write(exportJson) }
                }
                stringsContext.getString(R.string.ui_personascreen_678bfcfe, exportName)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                stringsContext.getString(
                    R.string.ui_personascreen_export_error,
                    error.message ?: error::class.java.simpleName
                )
            }
            hint = message
            exportHint = message
            exportJson = ""
            exportName = ""
        }
    }

    fun export(draft: PersonaDraft) {
        exportJson = PersonaDocument.toJson(draft)
        exportHint = ""
        val fileName = personaJsonFileName(draft.name)
        exportName = fileName
        exportDocument.launch(fileName)
    }

    // ---- 选文件 → 解析 → 预览 ----
    //
    // mime 类型给得很宽：`.md` 在不同文件管理器里可能是 text/markdown、
    // text/plain、甚至 application/octet-stream，卡死过滤条件会让用户"根本选不到那个文件"。
    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // 读盘与查文件名都放到 IO：这是主线程路径，几百 KB 的读会掉帧
                val (raw, docName) = withContext(Dispatchers.IO) {
                    PersonaImporter.read(context, uri) to PersonaImporter.displayName(context, uri)
                }
                val result = PersonaImporter.parse(raw, docName.ifBlank { null })
                if (result.isEmpty) {
                    hint = stringsContext.getString(R.string.ui_personascreen_614ec4ce) + result.warnings.firstOrNull().orEmpty()
                    return@launch
                }
                val imported = PersonaImporter.toPersonas(result, System.currentTimeMillis())
                val conflicts = PersonaImporter.namesConflict(imported, app.store.listPersonas())
                preview = ImportPreview(imported, result.warnings, conflicts)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                hint = stringsContext.getString(R.string.ui_personascreen_c2906f27, e.message ?: e::class.java.simpleName)
            }
        }
    }

    val current = editing
    if (current != null) {
        PersonaEditor(
            persona = current,
            onCancel = { editing = null },
            exportHint = exportHint,
            onExport = { export(it.toDraft()) },
            onSave = { persona ->
                scope.launch {
                    app.store.upsertPersona(persona.copy(updatedAt = System.currentTimeMillis()))
                    editing = null
                    reload()
                }
            }
        )
        return
    }

    Scaffold(
        topBar = {
            ToolTopBar(title = stringsContext.getString(R.string.ui_personascreen_a30da8f8), onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringsContext.getString(R.string.ui_personascreen_efbb4354))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringsContext.getString(R.string.ui_personascreen_91234d83)) },
                                onClick = {
                                    menuOpen = false
                                    hint = ""
                                    // 正式格式是 .json，但**不把过滤卡死**：`.json` 在不同
                                    // 文件管理器里可能被报成 text/plain 甚至 octet-stream，
                                    // 卡死会让用户"根本选不到那个文件"。所以选择器放开，
                                    // 靠解析给出的提示来纠错。（这条没有真机验证过。）
                                    pickDocument.launch(
                                        arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringsContext.getString(R.string.ui_personascreen_22fd6a0e)) },
                                onClick = { menuOpen = false; templateOpen = true }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val now = System.currentTimeMillis()
                editing = Persona(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    createdAt = now,
                    updatedAt = now
                )
            }) { Icon(Icons.Filled.Add, contentDescription = stringsContext.getString(R.string.ui_personascreen_00c782fe)) }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 声明 Scaffold 给的那份 inset（状态栏 / 导航栏）已经让过位，
                // 免得下面的 `imePadding()` 把底部那一段又算一遍。
                // 这是本项目所有页面的统一写法：padding(padding).consumeWindowInsets(padding)。
                .consumeWindowInsets(padding)
                // 这一屏自己没有输入框（键盘在这里不会弹），所以这一条现在恒等于 0。
                // 仍然写上：一是和编辑那一屏、以及项目里其它页面保持同一份 inset 契约，
                // 二是顺序（必须在 `verticalScroll()` 之前）只有写对了才有意义，
                // 等以后真在这里加搜索框之类的输入时，不用再回来踩一遍"顺序反了不生效"。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringsContext.getString(R.string.ui_personascreen_ba203188),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (personas.isEmpty()) {
                Text(stringsContext.getString(R.string.ui_personascreen_780a33f9))
            }

            personas.forEach { persona ->
                key(persona.id) {
                    PersonaListCard(persona = persona, onEdit = { editing = persona }, onCopy = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            app.store.upsertPersona(persona.copy(id = UUID.randomUUID().toString(),
                                name = persona.name + stringsContext.getString(R.string.ui_personascreen_9a141fa3), builtin = false, createdAt = now, updatedAt = now))
                            reload()
                        }
                    }, onExport = { export(persona.toDraft()) }, onDelete = {
                        scope.launch { app.store.deletePersona(persona.id); reload() }
                    })
                }
            }
        }
    }

    preview?.let { shown ->
        ImportPreviewDialog(
            preview = shown,
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        shown.personas.forEach { app.store.upsertPersona(it) }
                    }
                    hint = buildString {
                        append(stringsContext.getString(R.string.ui_personascreen_0475beed, shown.personas.size))
                        if (shown.conflicts.isNotEmpty()) {
                            append(stringsContext.getString(R.string.ui_personascreen_eb9b4ebf, shown.conflicts.joinToString("、")))
                        }
                    }
                    preview = null
                    reload()
                }
            },
            onDismiss = { preview = null }
        )
    }

    if (templateOpen) {
        TemplateDialog(
            onCopy = {
                clipboard.setText(AnnotatedString(PersonaDocument.template()))
                hint = stringsContext.getString(R.string.ui_personascreen_cd2a21c9)
            },
            onDismiss = { templateOpen = false }
        )
    }
}

/** 编辑常驻，复制/导出/删除放入菜单；出厂卡仍不可删除。 */
@Composable
private fun PersonaListCard(persona: Persona, onEdit: () -> Unit, onCopy: () -> Unit,
    onExport: () -> Unit, onDelete: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonaAvatar(persona.avatar, persona.name, Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(persona.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (persona.tagline.isNotBlank()) Text(persona.tagline, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (persona.builtin) Text(stringsContext.getString(R.string.ui_personascreen_cabe4bbf), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringsContext.getString(R.string.ui_personascreen_8d687618)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_personascreen_2817cf26)) }, onClick = { menu = false; onCopy() })
                        DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_personascreen_5bd89806)) }, onClick = { menu = false; onExport() })
                        if (!persona.builtin) DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_personascreen_84b5ce6a)) },
                            onClick = { menu = false; deleting = true })
                    }
                }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringsContext.getString(R.string.ui_personascreen_a9e1da47)) },
        text = { Text(stringsContext.getString(R.string.ui_personascreen_fb5f0a60, persona.name)) },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text(stringsContext.getString(R.string.ui_personascreen_b59c7514)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringsContext.getString(R.string.ui_personascreen_65a6ca9f)) } })
}

/** 导入预览：先让用户看清楚"读到了什么、放弃了什么"，再决定要不要落库。 */
private data class ImportPreview(
    val personas: List<Persona>,
    val warnings: List<String>,
    val conflicts: List<String>
)

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringsContext.getString(R.string.ui_personascreen_8a36f410)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringsContext.getString(R.string.ui_personascreen_2e7d33d6, preview.personas.size),
                    style = MaterialTheme.typography.bodySmall
                )
                preview.personas.forEach { persona ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                personaLabel(persona.avatar, persona.name, false, stringsContext.getString(R.string.ui_common_persona_default)),
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (persona.tagline.isNotBlank()) {
                                Text(persona.tagline, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                persona.toDraft().filledSections()
                                    .joinToString(" / ")
                                    .ifBlank { stringsContext.getString(R.string.ui_personascreen_f5215fe1) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (preview.conflicts.isNotEmpty()) {
                    Text(
                        stringsContext.getString(R.string.ui_personascreen_aac41c73, preview.conflicts.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (preview.warnings.isNotEmpty()) {
                    Text(stringsContext.getString(R.string.ui_personascreen_79fe0aa3), style = MaterialTheme.typography.labelSmall)
                    preview.warnings.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringsContext.getString(R.string.ui_personascreen_88a19fa8, preview.personas.size)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_personascreen_1e7eb0be)) }
        }
    )
}

/**
 * 格式说明。
 *
 * 这里同时承担两件事：告诉用户"文件长什么样"，以及**给出可直接复制的模板**。
 * 后者比前者重要 —— 真实流程是"复制模板 → 让模型填 → 导入"，
 * 用户不需要读懂格式，只需要把模板交出去。
 *
 * ⚠️ 文案里不能写 Markdown（`Text` 不解析，会原样显示星号和反引号）。
 */
@Composable
private fun TemplateDialog(onCopy: () -> Unit, onDismiss: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringsContext.getString(R.string.ui_personascreen_14e3c92c)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        PersonaDocument.template(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(stringsContext.getString(R.string.ui_personascreen_ca353c32)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_personascreen_b26ab9b3)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaEditor(
    persona: Persona,
    onCancel: () -> Unit,
    exportHint: String,
    onExport: (Persona) -> Unit,
    onSave: (Persona) -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    // ⚠️ `rememberSaveable` + 自定义 Saver，不是 `remember(persona.id)`。
    //
    // `draft` 是**整张人物卡的编辑内容**：抬头两行 + 八段人设（它是谁 / 怎么说话 /
    // 面对面时怎么说话 / 习惯与小动作 / 它身边的样子 / 怎么描写动作与景物 /
    // 关系与边界 / 里设定）。旧写法是 `remember(persona.id) { mutableStateOf(persona) }`，
    // 它只在 composition 存活期间有效 —— Activity 被系统回收后重建时，这一份会重新执行
    // `mutableStateOf(persona)`，也就是**退回打开编辑器那一刻的原文**。
    // 而"八段人设"恰恰是用户手敲几分钟、甚至从模型那边粘过来才写出来的东西，
    // 丢了不可能复原，是这一页最贵的一处丢数据。
    //
    // 为什么把 `persona.id` 当 input 一起传：换了一张卡（id 变了）要重新以新卡为草稿，
    // 不能把上一张卡的内容留在编辑器里 —— 那是"改错人"级别的错。
    // 它和保存用的 key 无关（key 由 composition 位置决定），只负责"输入变了就重建"。
    var draft by rememberSaveable(persona.id, stateSaver = PersonaSaver) {
        mutableStateOf(persona)
    }

    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    fun leave() { if (draft != persona) confirmLeave = true else onCancel() }
    BackHandler { leave() }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringsContext.getString(R.string.ui_personascreen_c28ed52a)) },
        text = { Text(stringsContext.getString(R.string.ui_personascreen_ce9fb31a)) },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringsContext.getString(R.string.ui_personascreen_ee436641)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringsContext.getString(R.string.ui_personascreen_106b68e5)) } })

    val context = LocalContext.current
    val scope = rememberCoroutineScopeCompat()

    /**
     * 从文档读到的那张卡，**先不落地**：等用户在预览里点确认才填进 [draft]。
     *
     * 为什么要多这一步确认（而列表页的导入也有预览）：
     * 编辑框里可能正躺着用户手敲了一半的内容，而"导入"是**覆盖**它。
     * 一次误触就毁掉几分钟的输入，且无从恢复 —— 这是这一页最贵的一处丢数据。
     */
    var pending by remember { mutableStateOf<EditorImport?>(null) }
    var importHint by remember { mutableStateOf("") }

    var pickingImage by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !pickingImage) {
            pickingImage = true
            scope.launch {
                try {
                    val ref = withContext(Dispatchers.IO) { AvatarImages.importImage(context, uri) }
                    draft = draft.copy(avatar = ref)
                    importHint = stringsContext.getString(R.string.ui_personascreen_cedd10cd)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { importHint = stringsContext.getString(R.string.ui_personascreen_7a8f925b) }
                finally { pickingImage = false }
            }
        }
    }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (raw, docName) = withContext(Dispatchers.IO) {
                    PersonaImporter.read(context, uri) to PersonaImporter.displayName(context, uri)
                }
                val result = PersonaImporter.parse(raw, docName.ifBlank { null })
                val first = result.personas.firstOrNull()
                if (first == null || !first.hasContent) {
                    // 只有名字不算"读到了东西"（见 PersonaDraft.hasContent）——
                    // 这个分支要**说清是哪一种**，否则用户看着文档里明明有名字会莫名其妙。
                    importHint = when {
                        first == null -> stringsContext.getString(R.string.ui_personascreen_5ec657ba) +
                            result.warnings.firstOrNull().orEmpty()
                        else -> stringsContext.getString(R.string.ui_personascreen_9d9bf09e, first.name)
                    }
                    return@launch
                }
                importHint = ""
                pending = EditorImport(
                    draft = first,
                    docName = docName,
                    warnings = result.warnings,
                    cardCount = result.personas.size
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                importHint = stringsContext.getString(R.string.ui_personascreen_c2906f27, e.message ?: e::class.java.simpleName)
            }
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(title = if (persona.name.isBlank()) stringsContext.getString(R.string.ui_personascreen_00c782fe) else stringsContext.getString(R.string.ui_personascreen_abe322e5), onBack = { leave() },
                actions = {
                    // 「导入」放在编辑页，和列表页那个是**两件事**：
                    // 列表页的导入 = 建新卡（每次发新 id，"只增不改"）；
                    // 这里 = 把文档内容填进**当前这张卡**的编辑框，检查后你自己点保存。
                    TextButton(onClick = {
                        importHint = ""
                        pickDocument.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
                        )
                    }) { Text(stringsContext.getString(R.string.ui_personascreen_48fda5c5)) }
                    TextButton(
                        enabled = !pickingImage && draft.name.isNotBlank(),
                        onClick = { onExport(draft) }
                    ) { Text(stringsContext.getString(R.string.ui_personascreen_export)) }
                    TextButton(
                        enabled = !pickingImage && draft.name.isNotBlank() && draft != persona,
                        onClick = { onSave(draft) }
                    ) { Text(stringsContext.getString(R.string.ui_personascreen_b2ef2293)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // ⚠️ 这三行的**顺序都是必须的**，不能随手挪：
                //
                // ① `consumeWindowInsets(padding)` 必须在 `imePadding()` 之前。
                //    `Scaffold` 给的 padding 底部已经含了导航栏那一段，而 `imePadding()`
                //    读到的 `WindowInsets.ime` **也包含导航栏**。`Modifier.padding()` 只是
                //    画内边距，它不会声明"这些 inset 我用掉了"；只有
                //    `consumeWindowInsets(padding)` 会标记成已消费，后面的 `imePadding()`
                //    于是只补差额。少了它，键盘一弹底部被算两遍，输入框会被顶到半空。
                //
                // ② `imePadding()` 必须在 `verticalScroll()` 之前。
                //    滚动容器是**在给定的测量约束里滚**的，imePadding 打在里面等于
                //    "视口高度已经减掉键盘"，所以下方的输入框能滚出来、光标也能带出来；
                //    打在 `verticalScroll()` 之后，视口高度是键盘弹起之前的全高，
                //    键盘会直接盖在最下面几个输入框上，怎么滚都滚不出来 ——
                //    而这一页恰恰有 11 个输入框，最后那个「只有它知道的事」是最容易中招的。
                //
                // ③ `MainActivity` 调了 `enableEdgeToEdge()`（内部把 `decorFitsSystemWindows`
                //    设成 false），窗口**不会**为键盘让位；`Scaffold` 默认的
                //    `contentWindowInsets` 又是 systemBars、不含 IME。所以键盘那一段 inset
                //    只能由这一页自己消费，没有别人会替它补。
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (exportHint.isNotBlank()) {
                Text(exportHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (importHint.isNotBlank()) {
                Text(
                    importHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            SettingsFormGroup(stringsContext.getString(R.string.ui_personascreen_09e1a4a3)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonaAvatar(draft.avatar, draft.name, Modifier.size(48.dp))
                    TextButton(enabled = !pickingImage, onClick = { pickImage.launch("image/*") }) {
                        Text(if (pickingImage) stringsContext.getString(R.string.ui_personascreen_d0c16ad6) else stringsContext.getString(R.string.ui_personascreen_5ad8e980))
                    }
                    TextButton(enabled = !pickingImage, onClick = { draft = draft.copy(avatar = "🙂") }) { Text(stringsContext.getString(R.string.ui_personascreen_b181d9ef)) }
                }
                if (!AvatarRef.isImage(draft.avatar)) OutlinedTextField(
                    value = draft.avatar,
                    onValueChange = { draft = draft.copy(avatar = it.take(32)) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_0ddc712f)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringsContext.getString(R.string.ui_personascreen_85ec4166),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_cf5067ad)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.tagline,
                    onValueChange = { draft = draft.copy(tagline = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_d0bf0960)) }, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().stopTextFlingPropagation()
                )
                OutlinedTextField(
                    value = draft.identity,
                    onValueChange = { draft = draft.copy(identity = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_5bd296e3)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
            }
            SettingsFormGroup(stringsContext.getString(R.string.ui_personascreen_500a0de0)) {
                OutlinedTextField(
                    value = draft.voice,
                    onValueChange = { draft = draft.copy(voice = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_13c82cb7)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
                // 当面表达独立保存，不与聊天语气或后面的动作素材合并。
                OutlinedTextField(
                    value = draft.sceneVoice,
                    onValueChange = { draft = draft.copy(sceneVoice = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_af9d17f3)) },
                    trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_personascreen_af9d17f3), stringsContext.getString(R.string.ui_personascreen_0ecc3b91)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
            }
            SettingsFormGroup(stringsContext.getString(R.string.ui_personascreen_36a05c1d)) {
                OutlinedTextField(
                    value = draft.mannerisms,
                    onValueChange = { draft = draft.copy(mannerisms = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_712b51d2)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
                OutlinedTextField(
                    value = draft.surroundings,
                    onValueChange = { draft = draft.copy(surroundings = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_2b5701cb)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
                OutlinedTextField(
                    value = draft.depiction,
                    onValueChange = { draft = draft.copy(depiction = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_ff536082)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
            }
            SettingsFormGroup(stringsContext.getString(R.string.ui_personascreen_37061c5b)) {
                OutlinedTextField(
                    value = draft.boundaries,
                    onValueChange = { draft = draft.copy(boundaries = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_d4f2b99a)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
                OutlinedTextField(
                    value = draft.secretNotes,
                    onValueChange = { draft = draft.copy(secretNotes = it) },
                    label = { Text(stringsContext.getString(R.string.ui_personascreen_09f2a1a2)) },
                    trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_personascreen_09f2a1a2), stringsContext.getString(R.string.ui_personascreen_a1043959)) },
                    minLines = 2, maxLines = 6,
                    modifier = rememberVisiblePersonaField()
                )
            }
        }
    }

    pending?.let { import ->
        EditorImportDialog(
            import = import,
            onConfirm = {
                // 只改编辑框，**不落库** —— 用户还要自己看一眼、点保存。
                draft = import.draft.applyTo(draft)
                importHint = stringsContext.getString(R.string.ui_personascreen_36b2085e, import.docName.ifBlank { stringsContext.getString(R.string.ui_persona_doc_default) }) +
                    import.draft.filledSections().joinToString("、") +
                    stringsContext.getString(R.string.ui_personascreen_67a07758)
                pending = null
            },
            onDismiss = { pending = null }
        )
    }
}

/**
 * 编辑页里"从文档读到的东西"。**只是一份待确认的草稿**，不是结果。
 *
 * [cardCount] 要留着：文档里放了多张卡是很常见的（列表页那套是一次全导进来），
 * 而在**编辑**一张卡的语境里只能选一张 —— 必须明确告诉用户"只用了第一张"，
 * 否则他会以为四张都合并进来了。
 */
private data class EditorImport(
    val draft: PersonaDraft,
    val docName: String,
    val warnings: List<String>,
    val cardCount: Int
)

/**
 * 导入前的预览。
 *
 * 它存在的唯一理由是**逐段列出"这次会替换哪几段"** —— `applyTo` 的语义是
 * "文档里空着的段落保留原文"（非破坏性），所以用户必须看得见哪些段落**不会**被替换，
 * 否则"为什么这段还是旧的"就成了一个没有答案的问题。
 */
@Composable
private fun EditorImportDialog(
    import: EditorImport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val filled = import.draft.filledSections()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringsContext.getString(R.string.ui_personascreen_6ee993a9)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringsContext.getString(R.string.ui_personascreen_b07965cf, import.docName.ifBlank { stringsContext.getString(R.string.ui_persona_name_unnamed) }),
                    style = MaterialTheme.typography.bodySmall
                )
                if (import.cardCount > 1) {
                    Text(
                        // 这里不能写 Markdown 的 **粗体**：`Text` 不解析 Markdown，会原样显示星号
                        stringsContext.getString(R.string.ui_personascreen_33a89ca6, import.cardCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    stringsContext.getString(R.string.ui_personascreen_c2b44e06) + (filled.joinToString("、").ifBlank { stringsContext.getString(R.string.ui_personascreen_3cde874a) }),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringsContext.getString(R.string.ui_personascreen_db032c9c),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringsContext.getString(R.string.ui_personascreen_d85720e1, import.draft.name.ifBlank { stringsContext.getString(R.string.ui_persona_name_keep) }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (import.warnings.isNotEmpty()) {
                    Text(
                        stringsContext.getString(R.string.ui_personascreen_0cee8da8) + import.warnings.joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    stringsContext.getString(R.string.ui_personascreen_f64d6d5f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringsContext.getString(R.string.ui_personascreen_5577ae41)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_personascreen_65a6ca9f)) } }
    )
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

/** 文字增长或键盘改变可用高度后，把当前长文本框移入外层滚动视口。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberVisiblePersonaField(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, height, imeBottom) {
        if (focused && imeBottom > 0) {
            // 等本帧的高度和 IME 约束参与布局，避免用旧边界请求滚动。
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return Modifier.fillMaxWidth().stopTextFlingPropagation().bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.isFocused }.onSizeChanged { height = it.height }
}
