package app.solochat.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import app.solochat.ui.personaLabel
import app.solochat.ui.CompactTopBar
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 会话列表。
 *
 * 第一版刻意做得很朴素：一个列表 + 新建按钮 + 菜单（改名 / 删除）。
 * 不做的：搜索、分组、未读角标 —— 私人使用只有一个会话列表，
 * 加这些只会让"打开就能聊"变慢。
 *
 * ## 人设是"每个对话一个"
 *
 * 点 + 之后**先选人设再建对话**。这和"全局一个人设"的区别不是自由度，而是
 * **一份历史对应一个人**：同一个 App 里可以既有小默也有阿岩，各自记得自己的事，
 * 而每段对话的提示词前缀始终是稳定的（前缀一变，KV 缓存就全丢）。
 *
 * 列表每行因此都标出"这个对话在用谁"，并提供「换人设」——
 * 不提供的话，选错一次就只能删掉整个对话重来。
 */
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

    val conversations = remember { mutableStateListOf<Conversation>() }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }

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

    Scaffold(
        topBar = {
            CompactTopBar(
                title = {
                    Text("SoloChat", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                },
                actions = {
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
            FloatingActionButton(onClick = { pickTarget = PickTarget.New }) {
                Icon(Icons.Filled.Add, contentDescription = "新建对话")
            }
        }
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyHint(
                modifier = Modifier.fillMaxSize().padding(padding),
                onConfigure = onOpenSettings
            )
        } else {
            // ⚠️ Scaffold 给的 inset 走 `contentPadding`，不要打成 `Modifier.padding`。
            //
            // 打在 modifier 上会把**列表视口本身**缩进系统栏以内 —— 内容永远滚不到
            // 状态栏/导航栏后面，等于白做了 edge-to-edge。放进 contentPadding 里，
            // 视口仍是整屏，首尾条目自己让开系统栏。
            //
            // 底部额外 +88dp 是**给 FAB 留的**：Scaffold 不会替列表挡 FAB，
            // 不留这一段，最后一个会话行的菜单按钮会被 FAB 压住点不到。
            LazyColumn(
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp
                )
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        persona = resolvePersona(personas, conversation.personaId),
                        onClick = { onOpenChat(conversation.id) },
                        onRename = { newTitle ->
                            scope.launch {
                                app.deps.conversations.withConversation(conversation.id) {
                                    app.store.renameConversation(conversation.id, newTitle, app.deps.clock.now())
                                }
                                reload()
                            }
                        },
                        onChangePersona = { pickTarget = PickTarget.Change(conversation) },
                        onDelete = {
                            scope.launch {
                                app.store.deleteConversation(conversation.id)
                                reload()
                            }
                        }
                    )
                    // `outlineVariant` 是主题里**专门给分隔线**的槽位（HorizontalDivider 的默认色）。
                    // 原先写的是 `outline.copy(alpha = 0.3f)` —— 那是绕过槽位自己调透明度，
                    // 明暗两套下要各自碰运气；outlineVariant 已经把两套都调好了。
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
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
                pickTarget = null
                when (target) {
                    is PickTarget.New -> createConversation(persona?.id.orEmpty())
                    is PickTarget.Change -> scope.launch {
                        // 同一会话入口串行，只更新归属与摘要指针，保留标题、模式和模型覆盖。
                        app.deps.conversations.withConversation(target.conversation.id) {
                            app.store.changePersona(target.conversation.id, persona?.id.orEmpty(), app.deps.clock.now())
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
    var renaming by remember { mutableStateOf(false) }

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
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (personaText.isNotBlank()) {
                            append(personaText)
                            append(" · ")
                        }
                        append(if (conversation.mode == ChatMode.SCENE) "面对面" else "发消息")
                        append(" · ")
                        append(formatRelative(conversation.updatedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                        onClick = { expanded = false; onDelete() }
                    )
                }
            }
        }
    }

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
                    Text(
                        "还没有人物卡。没有卡也能聊，但对方就没有固定的人设，" +
                            "会按出厂设定说话。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { onPick(null) }) { Text("先这样开一个") }
                    TextButton(onClick = onOpenPersonas) { Text("去人物卡页建一张") }
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
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${persona.avatar} ${persona.name}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (persona.builtin) {
                                    Text(
                                        "出厂",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (persona.tagline.isNotBlank()) {
                                Text(
                                    persona.tagline,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
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
