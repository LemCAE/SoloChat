package app.solochat.ui.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import app.solochat.ui.theme.SoloChatTheme
import app.solochat.SoloChatApp
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.Persona
import app.solochat.core.model.resolvePersona
import app.solochat.ui.PersonaAvatar
import app.solochat.core.model.AvatarRef
import app.solochat.ui.personaLabel
import app.solochat.ui.CompactTopBar
import app.solochat.ui.chat.HistorySearchState
import app.solochat.ui.chat.HistorySearchScreen
import kotlinx.coroutines.launch
import java.util.UUID

/** 先选人物，再看该人物的对话。仅改变展示层级，不回写会话原始归属。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScopeCompat()
    val keyboard = LocalSoftwareKeyboardController.current
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val historySearch = remember { HistorySearchState(scope) { query, before -> app.store.searchHistory(null, query, before) } }

    val conversations = remember { mutableStateListOf<Conversation>() }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = (personas.map { persona ->
        PersonaGroup("persona:${persona.id}", persona,
            conversations.filter { it.personaId == persona.id })
    }.filter { it.conversations.isNotEmpty() } + listOfNotNull(conversations.filter { conversation -> personas.none { it.id == conversation.personaId } }
        .takeIf { it.isNotEmpty() }?.let { PersonaGroup("unassigned", null, it) }))
        .sortedByDescending { it.conversations.maxOfOrNull { conversation -> conversation.updatedAt } ?: 0L }
    val selected = groups.firstOrNull { it.key == selectedGroup }
    BackHandler(enabled = selectedGroup != null) { selectedGroup = null }

    suspend fun reload() {
        personas = app.store.listPersonas()
        val list = app.store.listConversations()
        conversations.clear()
        conversations.addAll(list.filterNot { it.archived })
    }

    LaunchedEffect(Unit) { reload() }

    /**
     * 新建会话：**先落盘再进去**，避免"进去之后没有会话 id"的空档。
     *
     * 人设必须在这里定下来 —— 它是 `conversation.personaId`，落库之后
     * 提示词装配与列表显示都以它为准。
     */
    fun createConversation(personaId: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            app.store.upsertConversation(
                Conversation(
                    id = id,
                    title = "新的对话",
                    mode = ChatMode.CHAT,
                    modeSince = now,
                    personaId = personaId,
                    createdAt = now,
                    updatedAt = now
                )
            )
            onOpenChat(id)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (showSearch) Modifier.clearAndSetSemantics { } else Modifier,
            topBar = {
                CompactTopBar(
                    title = {
                        Text(selected?.persona?.name ?: if (selected != null) "待确认人物" else "SoloChat",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    navigationIcon = if (selectedGroup != null) ({
                        IconButton(onClick = { selectedGroup = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回人物列表")
                        }
                    }) else null,
                    actions = {
                        IconButton(onClick = { showSearch = true; historySearch.refresh() }) {
                            Icon(Icons.Filled.Search, contentDescription = "全局历史搜索")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                // 注意 onClick 必须写成具名参数：命名参数后面跟尾随 lambda
                                // 会被解析成另一个参数（这里编译不过就是这个原因）。
                                DropdownMenuItem(
                                    text = { Text("设置") },
                                    onClick = { menuOpen = false; onOpenSettings() }
                                )
                                DropdownMenuItem(
                                    text = { Text("人物卡") },
                                    onClick = { menuOpen = false; onOpenPersonas() }
                                )
                                DropdownMenuItem(
                                    text = { Text("记忆") },
                                    onClick = { menuOpen = false; onOpenMemory() }
                                )
                                DropdownMenuItem(
                                    text = { Text("用量") },
                                    onClick = { menuOpen = false; onOpenUsage() }
                                )
                                // 日志放在最后，并且用一条分隔线把它和上面四项分开：
                                // 它是排查用的，不是日常功能（见类注释）。混在一起会让人
                                // 以为它和"设置/人物卡/记忆/用量"是同一类东西，
                                // 而它其实是出问题时才去的地方。
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("日志") },
                                    onClick = { menuOpen = false; onOpenLogs() }
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    val persona = selected?.persona
                    if (persona != null) createConversation(persona.id) else pickTarget = PickTarget.New
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建对话")
                }
            }
        ) { padding ->
            if (groups.isEmpty()) {
                EmptyHint(modifier = Modifier.fillMaxSize().padding(padding), onConfigure = onOpenSettings)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 6.dp,
                        bottom = padding.calculateBottomPadding() + 88.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (selected == null) {
                        item { Text("人物 · ${groups.size}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(groups, key = { it.key }) { group ->
                            PersonaGroupRow(group, onClick = { selectedGroup = group.key })
                        }
                    } else {
                        item { Text("${selected.conversations.size} 段对话" + if (selected.persona != null) " · 新建会沿用当前人物" else " · 未选择人物，请通过会话菜单选择",
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (selected.conversations.isEmpty()) item {
                            Text("还没有对话，点击右下角 + 开始聊天。", Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(selected.conversations, key = { it.id }) { conversation ->
                            Column(Modifier.padding(horizontal = 12.dp)) {
                                ConversationRow(conversation = conversation,
                                    persona = resolvePersona(personas, conversation.personaId),
                                    onClick = { onOpenChat(conversation.id) },
                                    onRename = { newTitle -> scope.launch {
                                        app.deps.conversations.withConversation(conversation.id) {
                                            app.store.renameConversation(conversation.id, newTitle, app.deps.clock.now())
                                        }
                                        reload()
                                    } },
                                    onChangePersona = { pickTarget = PickTarget.Change(conversation) },
                                    onDelete = { scope.launch { app.deleteConversation(conversation.id); reload() } })
                            }
                        }
                    }
                }
            }
        }

        if (showSearch) HistorySearchScreen(historySearch, onBack = { keyboard?.hide(); showSearch = false }, global = true)
    }

    pickTarget?.let { target ->
        PersonaPickerDialog(
            personas = personas,
            title = if (target is PickTarget.New) "这个对话跟谁聊？" else "换成哪一张人物卡？",
            note = if (target is PickTarget.New) {
                "人设是每个对话各自记的：换一张卡就是换一个人，历史仍然留在同一个对话里。"
            } else {
                // 改的是"下一轮开始用谁"，已经进过上下文的内容不会变
                "从下一轮开始生效。之前已经聊过的内容不会重写。"
            },
            onPick = { persona ->
                if (persona == null) return@PersonaPickerDialog
                pickTarget = null
                when (target) {
                    is PickTarget.New -> createConversation(persona.id)
                    is PickTarget.Change -> scope.launch {
                        // 同一会话入口串行，只更新归属与摘要指针，保留标题、模式和模型覆盖。
                        app.deps.conversations.withConversation(target.conversation.id) {
                            app.store.changePersona(target.conversation.id, persona.id, app.deps.clock.now())
                        }
                        reload()
                    }
                }
            },
            onOpenPersonas = { pickTarget = null; onOpenPersonas() },
            onDismiss = { pickTarget = null }
        )
    }
}

private data class PersonaGroup(val key: String, val persona: Persona?, val conversations: List<Conversation>)

@Composable
private fun PersonaGroupRow(group: PersonaGroup, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            group.persona?.takeIf { AvatarRef.isImage(it.avatar) }?.let {
                PersonaAvatar(it.avatar, it.name, Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(group.persona?.let { personaLabel(it.avatar, it.name, false) } ?: "待确认人物",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildString {
                    append("${group.conversations.size} 段对话")
                    if (group.persona == null) append(" · 请为对话选择人物卡")
                    else group.persona.tagline.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 点 + 是"给新对话挑人设"，点行菜单里的「换人设」是改已有的那个。 */
private sealed class PickTarget {
    data object New : PickTarget()
    data class Change(val conversation: Conversation) : PickTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    persona: Persona?,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onChangePersona: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    // 会话自己没指定人设（空着或指向已删掉的卡）时用的是兜底那张，要标出来，
    // 否则用户会以为"这个对话用的是我当初选的那个人"
    val fallback = persona != null && persona.id != conversation.personaId
    val personaText = persona?.let { personaLabel(it.avatar, it.name, fallback) }.orEmpty()

    // 用 `Surface(onClick = …)` 而不是 `Modifier.clickable`：
    // 前者会带上 `Role.Button` 语义，读屏把它念成一个按钮；
    // 后者只是一块可点区域，读屏会把标题、副标题、右边那个"会话操作"图标
    // 混成一个节点念出来（"标题 副标题 会话操作"），听起来完全不像一行会话。
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (persona != null && AvatarRef.isImage(persona.avatar)) {
                PersonaAvatar(persona.avatar, persona.name, Modifier.size(32.dp).padding(end = 4.dp))
            }
            Column(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(conversation.title, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatRelative(conversation.updatedAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(personaText.ifBlank { "未设置人物卡" } + " · " + if (conversation.mode == ChatMode.SCENE) "面对面" else "发消息",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "会话操作")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { expanded = false; renaming = true }
                    )
                    DropdownMenuItem(
                        text = { Text("换人设") },
                        onClick = { expanded = false; onChangePersona() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { expanded = false; deleting = true }
                    )
                }
            }
        }
    }

    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("删除这段对话？") },
        text = { Text("“${conversation.title}”的聊天记录和上下文摘要将删除，无法撤销。人物卡、已保存记忆和历史用量保留。") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text("删除对话") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })

    if (renaming) {
        RenameDialog(
            initial = conversation.title,
            onConfirm = { renaming = false; onRename(it) },
            onDismiss = { renaming = false }
        )
    }
}

/**
 * 人设选择。
 *
 * 点一下就选中并**立即生效**（没有"确定"按钮）：这两个入口本来就是"我要开一段新的"
 * 或"我要换成那个"，多一步确认只是多一次点击。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaPickerDialog(
    personas: List<Persona>,
    title: String,
    note: String,
    onPick: (Persona?) -> Unit,
    onOpenPersonas: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (personas.isEmpty()) {
                    Text("先创建或导入一张人物卡，再开始对话。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenPersonas) { Text("创建人物卡") }
                }
                personas.forEach { persona ->
                    // 同 `ConversationRow`：用 Card 自己的 onClick，而不是叠 `Modifier.clickable`。
                    // 差别不只是语义 —— `clickable` 的涟漪画在 Card 的圆角**之外**，
                    // 点下去会看到方形的波纹溢出圆角卡片。
                    Card(
                        onClick = { onPick(persona) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PersonaAvatar(persona.avatar, persona.name)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(persona.name, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (persona.tagline.isNotBlank()) Text(persona.tagline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (persona.builtin) Text("出厂", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("算了") }
        }
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (text.isNotBlank()) onConfirm(text.trim())
            }) { Text("好") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("算了") }
        }
    )
}

/**
 * 空列表提示。
 *
 * 这个界面承担着"第一次打开该干什么"的引导职责 —— 所以不是一句"暂无数据"，
 * 而是直接给一个去设置页的动作。体检卡式的引导比文案有用。
 */
@Composable
private fun EmptyHint(modifier: Modifier = Modifier, onConfigure: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有对话", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "右下角的 + 开一个新对话（会让你先选一张人物卡）。\n" +
                "开始之前，先去设置里填接口地址和 Key。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        androidx.compose.material3.TextButton(onClick = onConfigure) {
            Text("去设置")
        }
    }
}

/** 相对时间。刻意不做 i18n —— 单用户中文应用，够用即可。 */
private fun formatRelative(at: Long): String {
    if (at <= 0) return "—"
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(at)
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

@Preview(name = "主页·明", showBackground = true)
@Preview(name = "主页·大字体", showBackground = true, fontScale = 2f)
@Composable
private fun HomeLightPreview() = HomeLayoutPreview(false)

@Preview(name = "主页·暗", showBackground = true)
@Composable
private fun HomeDarkPreview() = HomeLayoutPreview(true)

@Composable
private fun HomeLayoutPreview(dark: Boolean) {
    SoloChatTheme(darkTheme = dark) {
        Scaffold(topBar = {
            CompactTopBar(title = { Text("SoloChat", style = MaterialTheme.typography.titleMedium) },
                actions = { IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, "更多") } })
        }, floatingActionButton = {
            FloatingActionButton(onClick = {}) { Icon(Icons.Filled.Add, "新建对话") }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                listOf("一个安静的下午", "周末见面").forEachIndexed { index, title ->
                    ConversationRow(Conversation(index.toString(), title), null, {}, {}, {}, {})
                }
            }
        }
    }
}
