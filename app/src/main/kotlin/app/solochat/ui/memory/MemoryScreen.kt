package app.solochat.ui.memory

import app.solochat.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.solochat.ui.personaLabel
import app.solochat.ui.ToolTopBar
import app.solochat.SoloChatApp
import app.solochat.core.model.Memory
import app.solochat.core.model.Persona
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 记忆页。
 *
 * 这里要回答用户最关心的一个问题：**"它到底记住我什么了"**。
 *
 * ## 记忆是**按人设分开**的
 *
 * 不同人设是不同的人，所以每条记忆都带一个归属（空 = **通用**，哪张人设都能看到）。
 * 这一点必须在界面上看得见 —— 记忆串味（"小默"知道只有跟"阿岩"聊过的事）是这个
 * App 里最容易破坏沉浸感的错，而用户在界面上一眼看出归属，才能自己纠正它。
 * 所以顶部有筛选，每条卡片上都写着归属。
 *
 * ## 为什么筛在界面上做、而不是再查一次库
 *
 * 存储层那条 `listMemories(personaId)` 的语义是**"这张人设能看到的"**
 * （它自己的 + 通用的）—— 那是给**注入上下文**用的。管理页要的是"精确按归属看"，
 * 语义不同，不该共用；而私人规模的记忆量（几十条）在内存里筛完全够。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScopeCompat()

    var memories by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }

    // 用户在输入的东西一律 rememberSaveable：旋屏、切深色模式、被系统回收后重建，
    // 写了一半的记忆不该消失（`adding` 尤其明显 —— 丢了它输入框会直接关掉）。
    // `editing` 例外：它是领域对象，要做 Saver 才存得下，收益不值那一步。
    var adding by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Memory?>(null) }

    /** 正在编辑/新增的那条的归属。`""` = 通用。 */
    var draftOwner by rememberSaveable { mutableStateOf("") }

    /** 筛选：`null` = 全部，`""` = 通用，其余 = 某张人设。 */
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    suspend fun reload() {
        personas = app.store.listPersonas()
        // 读"全部"（null），筛选在界面上做 —— 见类注释
        memories = app.store.listMemories(null)
    }
    LaunchedEffect(Unit) { reload() }

    /** 这条记忆归谁。人设被删掉之后也要说清楚，不能显示成"通用"。 */
    fun ownerLabel(personaId: String): String = when {
        personaId.isBlank() -> stringsContext.getString(R.string.ui_memoryscreen_61a2a249)
        else -> personas.firstOrNull { it.id == personaId }
            ?.let { personaLabel(it.avatar, it.name, false, stringsContext.getString(R.string.ui_common_persona_default)) }
            ?: stringsContext.getString(R.string.ui_memoryscreen_757ceb52)
    }

    val shown = memories.filter { m ->
        filter == null || m.personaId == filter
    }

    Scaffold(
        topBar = { ToolTopBar(title = stringsContext.getString(R.string.ui_memoryscreen_8e1f4724), onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                adding = true
                editing = null
                draft = ""
                // 默认"通用"：用户手动加的往往是想让**所有人设**都知道的事
                draftOwner = ""
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringsContext.getString(R.string.ui_memoryscreen_a515a557))
            }
        }
    ) { padding ->
        // ⚠️ `imePadding()` 在这一页是必需的，不是可选的美化。
        //
        // `MainActivity` 调了 `enableEdgeToEdge()`，它内部会把
        // `decorFitsSystemWindows` 设成 false —— 也就是**窗口不再为键盘让位**，
        // IME 变成一份必须由我们自己消费的 inset。而 `Scaffold` 默认的
        // `contentWindowInsets` 不含 IME 那一份。
        //
        // 少了它，"保存 / 取消"和"这条给谁看"那排 chips 会被键盘压在下面，
        // 表现就是"输入框填完了但点不到保存"。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            // 筛选。人设可能有好几张，所以这排要能横滑。
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text(stringsContext.getString(R.string.ui_memoryscreen_5c336268, memories.size)) }
                )
                FilterChip(
                    selected = filter == "",
                    onClick = { filter = "" },
                    label = { Text(stringsContext.getString(R.string.ui_memoryscreen_0b270fb5, memories.count { it.personaId.isBlank() })) }
                )
                personas.forEach { p ->
                    FilterChip(
                        selected = filter == p.id,
                        onClick = { filter = p.id },
                        label = {
                            Text("${personaLabel(p.avatar, p.name, false, stringsContext.getString(R.string.ui_common_persona_default))} (${memories.count { it.personaId == p.id }})")
                        }
                    )
                }
            }


            Text(
                text = stringsContext.getString(R.string.ui_memoryscreen_22181419),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )

            if (adding || editing != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = editing?.content ?: draft,
                        onValueChange = { value ->
                            val current = editing
                            if (current != null) editing = current.copy(content = value)
                            else draft = value
                        },
                        label = { Text(stringsContext.getString(R.string.ui_memoryscreen_a74ffe35)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = stringsContext.getString(R.string.ui_memoryscreen_9f82c0d0),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = draftOwner.isBlank(),
                            onClick = { draftOwner = "" },
                            label = { Text(stringsContext.getString(R.string.ui_memoryscreen_61a2a249)) }
                        )
                        personas.forEach { p ->
                            FilterChip(
                                selected = draftOwner == p.id,
                                onClick = { draftOwner = p.id },
                                label = { Text(personaLabel(p.avatar, p.name, false, stringsContext.getString(R.string.ui_common_persona_default))) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = (editing?.content ?: draft).isNotBlank(), onClick = {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val target = editing
                                if (target != null) {
                                    app.store.upsertMemory(
                                        target.copy(
                                            content = target.content.trim(),
                                            personaId = draftOwner
                                        )
                                    )
                                } else if (draft.isNotBlank()) {
                                    app.store.upsertMemory(
                                        Memory(
                                            id = UUID.randomUUID().toString(),
                                            content = draft.trim(),
                                            personaId = draftOwner,
                                            createdAt = now,
                                            lastUsedAt = now,
                                            // 手动新增默认固定，供每轮记忆选择优先使用
                                            pinned = true
                                        )
                                    )
                                }
                                adding = false
                                editing = null
                                draft = ""
                                reload()
                            }
                        }) { Text(stringsContext.getString(R.string.ui_memoryscreen_b2ef2293)) }
                        TextButton(onClick = {
                            adding = false
                            editing = null
                            draft = ""
                        }) { Text(stringsContext.getString(R.string.ui_memoryscreen_65a6ca9f)) }
                    }
                }
            }


            if (shown.isEmpty() && !adding) {
                Text(
                    text = if (memories.isEmpty()) {
                        stringsContext.getString(R.string.ui_memoryscreen_ca95cb06)
                    } else {
                        stringsContext.getString(R.string.ui_memoryscreen_4f2b6492)
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // `weight(1f)` 而不是 `fillMaxSize()`：列表要**正好拿走剩下的高度**。
            // 用 fillMaxSize 的话，上面的说明/输入区一旦变高（键盘弹出来就是一例），
            // 列表仍会按整屏高度去要空间，把上面的固定内容挤掉。
            //
            // contentPadding 的 88dp 是给 FAB 留的：Scaffold 不会替列表挡 FAB，
            // 不留这一段最后一张卡片的按钮会被 FAB 盖住。
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(shown, key = { it.id }) { memory ->
                    MemoryListCard(memory, ownerLabel(memory.personaId), onPin = {
                        scope.launch { app.store.upsertMemory(memory.copy(pinned = !memory.pinned)); reload() }
                    }, onEdit = {
                        editing = memory; adding = false; draftOwner = memory.personaId
                    }, onDelete = {
                        scope.launch { app.store.deleteMemory(memory.id); reload() }
                    })
                }
            }
        }
    }
}

/** 正文和归属常驻，其余操作收进菜单，避免按钮挤压内容。 */
@Composable
private fun MemoryListCard(memory: Memory, owner: String, onPin: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(owner + if (memory.pinned) stringsContext.getString(R.string.ui_memoryscreen_6ffc0a41) else "", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringsContext.getString(R.string.ui_memoryscreen_dfa8177f)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_memoryscreen_efdcf07f)) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(if (memory.pinned) stringsContext.getString(R.string.ui_memoryscreen_bebf13db) else stringsContext.getString(R.string.ui_memoryscreen_aff1670b)) },
                            onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text(stringsContext.getString(R.string.ui_memoryscreen_a0f331a5)) }, onClick = { menu = false; deleting = true })
                    }
                }
            }
            Text(memory.content, style = MaterialTheme.typography.bodyLarge)
            Text(stringsContext.getString(R.string.ui_memoryscreen_d5e87624, memory.useCount), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringsContext.getString(R.string.ui_memoryscreen_fc9c9d50)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringsContext.getString(R.string.ui_memoryscreen_2dc5145d, owner))
            Text(memory.content, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text(stringsContext.getString(R.string.ui_memoryscreen_2985cd24))
        } },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text(stringsContext.getString(R.string.ui_memoryscreen_b59c7514)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringsContext.getString(R.string.ui_memoryscreen_65a6ca9f)) } })
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
