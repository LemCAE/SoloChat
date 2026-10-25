package app.solochat.ui.conversations

import app.solochat.R
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import app.solochat.ui.theme.SoloChatTheme
import app.solochat.SoloChatApp
import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.Persona
import app.solochat.core.model.TimelineKey
import app.solochat.core.model.TimelineSource
import androidx.room.InvalidationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import app.solochat.ui.PersonaAvatar
import app.solochat.ui.personaLabel
import app.solochat.ui.CompactTopBar
import app.solochat.ui.chat.HistorySearchState
import app.solochat.ui.chat.HistorySearchScreen
import kotlinx.coroutines.launch
import java.util.UUID

/** NavHost 仍存活期间缓存两级列表快照，避免从聊天页返回时先闪出空首页。 */
class ConversationListSnapshot {
    val conversations = mutableStateListOf<Conversation>()
    val personas = mutableStateOf<List<Persona>>(emptyList())
    val previews = mutableStateOf<Map<String, ConversationPreview>>(emptyMap())
    val initialSnapshotLoaded = mutableStateOf(false)
}

/** 先选人物，再看该人物的对话。仅改变展示层级，不回写会话原始归属。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    snapshot: ConversationListSnapshot,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScopeCompat()
    val keyboard = LocalSoftwareKeyboardController.current
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val historySearch = remember { HistorySearchState(scope, search = { query, before -> app.store.searchHistory(null, query, before) }, searchFailedText = stringsContext.getString(R.string.ui_history_search_failed)) }

    val conversations = snapshot.conversations
    var personas by snapshot.personas
    var previews by snapshot.previews
    var initialSnapshotLoaded by snapshot.initialSnapshotLoaded
    val refreshGeneration = remember(app) { RefreshGeneration() }
    val homeListState = rememberLazyListState()
    val personaListState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }
    var showHomePersonaPicker by rememberSaveable { mutableStateOf(false) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPersonaName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPersonaAvatar by rememberSaveable { mutableStateOf("") }
    val groups = buildPersonaGroups(personas, conversations, previews)
    val selected = groups.firstOrNull { it.key == selectedGroup }
    val restoringSelection = selectedGroup != null && !initialSnapshotLoaded
    val showingPersonaPage = selected != null || restoringSelection

    fun clearGroupSelection() {
        selectedGroup = null
        selectedPersonaName = null
        selectedPersonaAvatar = ""
    }

    BackHandler(enabled = showingPersonaPage) { clearGroupSelection() }

    LaunchedEffect(app.db, refreshGeneration) {
        callbackFlow {
            val observer = object : InvalidationTracker.Observer("persona", "conversation", "message", "event") {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            app.db.invalidationTracker.addObserver(observer)
            trySend(Unit)
            awaitClose { app.db.invalidationTracker.removeObserver(observer) }
        }.conflate().flowOn(Dispatchers.IO).collectLatest {
            val generation = refreshGeneration.begin()
            delay(75)
            val (loadedPersonas, loadedConversations, loadedPreviews) = withContext(Dispatchers.IO) {
                val loadedPersonas = app.store.listPersonas()
                val loadedConversations = app.store.listConversations().filterNot { it.archived }
                val loadedPreviews = buildMap {
                    loadedConversations.forEach { conversation ->
                        val latest = app.store.timelinePage(conversation.id, before = null, limit = 1)
                            .entries.lastOrNull()
                        if (latest != null) put(conversation.id, ConversationPreview.from(latest))
                    }
                }
                Triple(loadedPersonas, loadedConversations, loadedPreviews)
            }
            if (refreshGeneration.isCurrent(generation)) {
                val loadedGroups = buildPersonaGroups(
                    loadedPersonas, loadedConversations, loadedPreviews
                )
                val availableGroupKeys = loadedGroups.mapTo(mutableSetOf()) { it.key }
                if (selectedGroup != null && selectedGroup !in availableGroupKeys) {
                    clearGroupSelection()
                } else if (selectedGroup != null) {
                    loadedGroups.firstOrNull { it.key == selectedGroup }?.persona?.let { persona ->
                        selectedPersonaName = persona.name
                        selectedPersonaAvatar = persona.avatar
                    }
                }
                personas = loadedPersonas
                previews = loadedPreviews
                conversations.clear()
                conversations.addAll(loadedConversations)
                initialSnapshotLoaded = true
            }
        }
    }

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
                    title = stringsContext.getString(R.string.ui_conversationlistscreen_fb03968b),
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
                        val title = selected?.persona?.name ?: selectedPersonaName
                            ?: if (showingPersonaPage) stringsContext.getString(R.string.ui_conversationlistscreen_eaf02942)
                            else stringsContext.getString(R.string.app_name)
                        if (showingPersonaPage) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PersonaAvatar(selected?.persona?.avatar ?: selectedPersonaAvatar, title, Modifier.size(32.dp))
                                Text(title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(title,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    navigationIcon = if (showingPersonaPage) ({
                        IconButton(onClick = { clearGroupSelection() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringsContext.getString(R.string.ui_conversationlistscreen_4d46be1e))
                        }
                    }) else null,
                    actions = {
                        IconButton(onClick = { showSearch = true; historySearch.refresh() }) {
                            Icon(Icons.Filled.Search, contentDescription = stringsContext.getString(R.string.ui_conversationlistscreen_28277a82))
                        }
                        if (!showingPersonaPage) {
                            IconButton(onClick = { showHomePersonaPicker = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringsContext.getString(R.string.ui_conversationlistscreen_bb85b855))
                            }
                        } else {
                            IconButton(enabled = selected?.persona != null, onClick = {
                                selected?.persona?.let { createConversation(it.id) }
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = stringsContext.getString(R.string.ui_conversationlistscreen_bb85b855))
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringsContext.getString(R.string.ui_conversationlistscreen_efbb4354))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                // 注意 onClick 必须写成具名参数：命名参数后面跟尾随 lambda
                                // 会被解析成另一个参数（这里编译不过就是这个原因）。
                                DropdownMenuItem(
                                    text = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_99d28d4a)) },
                                    onClick = { menuOpen = false; onOpenSettings() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_a30da8f8)) },
                                    onClick = { menuOpen = false; onOpenPersonas() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_8e1f4724)) },
                                    onClick = { menuOpen = false; onOpenMemory() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_3316736c)) },
                                    onClick = { menuOpen = false; onOpenUsage() }
                                )
                                // 日志放在最后，并且用一条分隔线把它和上面四项分开：
                                // 它是排查用的，不是日常功能（见类注释）。混在一起会让人
                                // 以为它和"设置/人物卡/记忆/用量"是同一类东西，
                                // 而它其实是出问题时才去的地方。
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_bc0753cf)) },
                                    onClick = { menuOpen = false; onOpenLogs() }
                                )
                            }
                        }
                    }
                )
            },
        ) { padding ->
            if (!initialSnapshotLoaded) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else if (groups.isEmpty()) {
                EmptyHint(modifier = Modifier.fillMaxSize().padding(padding), onConfigure = onOpenSettings)
            } else {
                LazyColumn(
                    state = if (selected == null) homeListState else personaListState,
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + 12.dp)
                ) {
                    if (!showingPersonaPage) {
                        items(groups, key = { it.key }) { group ->
                            val latest = group.conversations.mapNotNull { previews[it.id] }.maxByOrNull { it.activeAt }
                            PersonaGroupRow(group, latest, onClick = {
                                selectedGroup = group.key
                                selectedPersonaName = group.persona?.name
                                selectedPersonaAvatar = group.persona?.avatar.orEmpty()
                            })
                        }
                    } else if (selected != null) {
                        if (selected.conversations.isEmpty()) item {
                            Text(stringsContext.getString(R.string.ui_conversationlistscreen_a7e057dd), Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(selected.conversations, key = { it.id }) { conversation ->
                            Column(Modifier.fillMaxWidth()) {
                                ConversationRow(conversation = conversation,
                                    preview = previews[conversation.id],
                                    onClick = { onOpenChat(conversation.id) },
                                    onRename = { newTitle -> scope.launch {
                                        app.deps.conversations.withConversation(conversation.id) {
                                            app.store.renameConversation(conversation.id, newTitle, app.deps.clock.now())
                                        }
                                    } },
                                    onTogglePinned = { scope.launch {
                                        app.deps.conversations.withConversation(conversation.id) {
                                            app.store.setConversationPinned(conversation.id, !conversation.pinned)
                                        }
                                    } },
                                    onDelete = { scope.launch { app.deleteConversation(conversation.id) } })
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
            title = stringsContext.getString(R.string.ui_conversations_choose_person),
            onPick = { persona ->
                if (persona == null) return@PersonaPickerDialog
                pickTarget = null
                createConversation(persona.id)
            },
            onOpenPersonas = { pickTarget = null; onOpenPersonas() },
            onDismiss = { pickTarget = null }
        )
    }

    if (showHomePersonaPicker) {
        NewConversationPersonaSheet(
            personas = personas,
            onPick = { persona ->
                showHomePersonaPicker = false
                createConversation(persona.id)
            },
            onOpenPersonas = { showHomePersonaPicker = false; onOpenPersonas() },
            onDismiss = { showHomePersonaPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationPersonaSheet(
    personas: List<Persona>,
    onPick: (Persona) -> Unit,
    onOpenPersonas: () -> Unit,
    onDismiss: () -> Unit
) {
    val stringsContext = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringsContext.getString(R.string.ui_conversations_choose_person),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringsContext.getString(R.string.ui_conversations_close))
                }
            }
            if (personas.isEmpty()) {
                TextButton(onClick = onOpenPersonas, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringsContext.getString(R.string.ui_conversationlistscreen_6789afda))
                }
            } else {
                personas.forEach { persona ->
                    Surface(
                        onClick = { onPick(persona) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 28.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PersonaAvatar(persona.avatar, persona.name, Modifier.size(44.dp), cornerRadius = 12.dp)
                            Text(
                                persona.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaGroupRow(group: PersonaGroup, preview: ConversationPreview?, onClick: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val name = group.persona?.let {
        personaLabel(it.avatar, it.name, false, stringsContext.getString(R.string.ui_common_persona_default))
    } ?: stringsContext.getString(R.string.ui_conversationlistscreen_eaf02942)
    Column(Modifier.fillMaxWidth()) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonaAvatar(group.persona?.avatar.orEmpty(), name, Modifier.size(44.dp), cornerRadius = 13.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (preview != null && preview.activeAt > 0) {
                            Text(formatRelative(preview.activeAt), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (preview?.pendingPush == true) Text(stringsContext.getString(R.string.ui_conversations_pending_push),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                        Text(preview?.text ?: stringsContext.getString(R.string.ui_conversations_no_message),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 76.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 当前只处理新会话的人物选择；已有会话不在列表菜单里更换人物归属。 */
private sealed class PickTarget {
    data object New : PickTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    preview: ConversationPreview?,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var actionsOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    // 行点击与右侧管理按钮分开，整块内容仍由 Surface 提供按钮语义。
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                shape = RectangleShape, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (conversation.pinned) Icon(Icons.Filled.PushPin,
                            contentDescription = stringsContext.getString(R.string.ui_conversations_pinned),
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(conversation.title, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (preview != null && preview.activeAt > 0) Text(formatRelative(preview.activeAt),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (preview?.pendingPush == true) Text(stringsContext.getString(R.string.ui_conversations_pending_push),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                        Text(preview?.text ?: stringsContext.getString(R.string.ui_conversations_no_message),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box {
                IconButton(onClick = { actionsOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringsContext.getString(R.string.ui_conversationlistscreen_11c1e708))
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }

    if (actionsOpen) ConversationActionsSheet(
        conversation = conversation,
        onDismiss = { actionsOpen = false },
        onRename = { actionsOpen = false; renaming = true },
        onTogglePinned = { actionsOpen = false; onTogglePinned() },
        onDelete = { actionsOpen = false; deleting = true }
    )

    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_01ff54c2)) },
        text = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_bd4fdc6d, conversation.title)) },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text(stringsContext.getString(R.string.ui_conversationlistscreen_937c57c8)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringsContext.getString(R.string.ui_conversationlistscreen_65a6ca9f)) } })

    if (renaming) {
        RenameDialog(
            initial = conversation.title,
            onConfirm = { renaming = false; onRename(it) },
            onDismiss = { renaming = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationActionsSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    conversation.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringsContext.getString(R.string.ui_conversations_close))
                }
            }
            ConversationActionItem(
                icon = Icons.Filled.Edit,
                label = stringsContext.getString(R.string.ui_conversationlistscreen_799054cd),
                onClick = onRename
            )
            ConversationActionItem(
                icon = Icons.Filled.PushPin,
                label = stringsContext.getString(
                    if (conversation.pinned) R.string.ui_conversations_unpin else R.string.ui_conversations_pin
                ),
                onClick = onTogglePinned
            )
            ConversationActionItem(
                icon = Icons.Filled.Delete,
                label = stringsContext.getString(R.string.ui_conversationlistscreen_937c57c8),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun ConversationActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

/** 新会话的人物选择；点击人物后立即创建，不增加确认步骤。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaPickerDialog(
    personas: List<Persona>,
    title: String,
    onPick: (Persona?) -> Unit,
    onOpenPersonas: () -> Unit,
    onDismiss: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
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
                if (personas.isEmpty()) {
                    TextButton(onClick = onOpenPersonas) { Text(stringsContext.getString(R.string.ui_conversationlistscreen_6789afda)) }
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
                            if (persona.builtin) Text(stringsContext.getString(R.string.ui_conversationlistscreen_624476c9), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_conversationlistscreen_1e7eb0be)) }
        }
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(stringsContext.getString(R.string.ui_conversationlistscreen_799054cd)) },
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
            }) { Text(stringsContext.getString(R.string.ui_conversationlistscreen_b1b9f137)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringsContext.getString(R.string.ui_conversationlistscreen_1e7eb0be)) }
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringsContext.getString(R.string.ui_conversationlistscreen_d535074a), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringsContext.getString(R.string.ui_conversationlistscreen_2b2509ad),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        androidx.compose.material3.TextButton(onClick = onConfigure) {
            Text(stringsContext.getString(R.string.ui_conversationlistscreen_341c1503))
        }
    }
}

/** 相对时间。从资源读取相对时间文案，保持各界面用词一致。 */
@Composable
private fun formatRelative(at: Long): String {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    if (at <= 0) return stringsContext.getString(R.string.ui_conversations_no_time)
    val now = System.currentTimeMillis()
    val zone = java.time.ZoneId.systemDefault()
    val daysSince = java.time.temporal.ChronoUnit.DAYS.between(
        java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate(),
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    )
    val diff = now - at
    return when {
        daysSince >= 2 -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(at)
        diff < 60_000 -> stringsContext.getString(R.string.ui_conversations_relative_now)
        diff < 3_600_000 -> stringsContext.getString(R.string.ui_conversations_relative_minutes, diff / 60_000)
        diff < 86_400_000 -> stringsContext.getString(R.string.ui_conversations_relative_hours, diff / 3_600_000)
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
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val people = listOf(
        Persona("wolf", "陆昭", avatar = "陆"),
        Persona("whale", "小鲸鱼", avatar = "🐳"),
        Persona("new", "林听晚")
    )
    val conversations = listOf(
        Conversation("wolf-session", "周末的河岸散步", personaId = "wolf", createdAt = 1),
        Conversation("whale-session", "回家路上的碎碎念", personaId = "whale", createdAt = 2)
    )
    val previews = mapOf(
        "wolf-session" to ConversationPreview("wolf-session", TimelineKey(TimelineSource.EVENT, 1),
            System.currentTimeMillis() - 60_000, "到家了就说一声，路上别一直看手机。", false),
        "whale-session" to ConversationPreview("whale-session", TimelineKey(TimelineSource.MESSAGE, 2),
            System.currentTimeMillis() - 3_600_000, "我刚看了天气，明天好像会下雨。", true)
    )
    val groups = buildPersonaGroups(people, conversations, previews)
    SoloChatTheme(darkTheme = dark) {
        Scaffold(topBar = {
            CompactTopBar(title = {
                Text(stringsContext.getString(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, actions = {
                IconButton(onClick = {}) { Icon(Icons.Filled.Search, stringsContext.getString(R.string.ui_conversationlistscreen_28277a82)) }
                IconButton(onClick = {}) { Icon(Icons.Filled.Add, stringsContext.getString(R.string.ui_conversations_new_person)) }
                IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, stringsContext.getString(R.string.ui_conversationlistscreen_efbb4354)) }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp)) {
                items(groups, key = { it.key }) { group ->
                    val latest = group.conversations.mapNotNull { previews[it.id] }.maxByOrNull { it.activeAt }
                    PersonaGroupRow(group, latest) {}
                }
            }
        }
    }
}

@Preview(name = "会话列表·明", showBackground = true)
@Composable
private fun ConversationLightPreview() = ConversationLayoutPreview(false)

@Preview(name = "会话列表·暗", showBackground = true)
@Composable
private fun ConversationDarkPreview() = ConversationLayoutPreview(true)

@Composable
private fun ConversationLayoutPreview(dark: Boolean) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val now = System.currentTimeMillis()
    val pinned = Conversation("walk", "周末，沿着河岸慢慢走", pinned = true)
    val recent = ConversationPreview("walk", TimelineKey(TimelineSource.EVENT, 1), now - 3_600_000,
        "下次换我来选路线，今天就先听你的。", false)
    val empty = Conversation("empty", "新对话")
    SoloChatTheme(darkTheme = dark) {
        Scaffold(topBar = {
            CompactTopBar(title = { Text("陆昭", style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringsContext.getString(R.string.ui_conversationlistscreen_4d46be1e)) } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Search, stringsContext.getString(R.string.ui_conversationlistscreen_28277a82)) }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Add, stringsContext.getString(R.string.ui_conversationlistscreen_bb85b855)) }
                    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, stringsContext.getString(R.string.ui_conversationlistscreen_efbb4354)) }
                })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                ConversationRow(pinned, recent, {}, {}, {}, {})
                ConversationRow(empty, null, {}, {}, {}, {})
            }
        }
    }
}
