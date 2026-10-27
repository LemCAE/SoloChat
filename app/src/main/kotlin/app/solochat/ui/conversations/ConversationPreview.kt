package app.solochat.ui.conversations

import app.solochat.core.model.Conversation
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Persona
import app.solochat.core.model.TimelineKey
import app.solochat.core.model.TimelineEntry
import app.solochat.core.model.historySearchHit

/** 列表使用的最近可见记录；不复制正文或建立独立的可见性规则。 */
data class ConversationPreview(
    val conversationId: String,
    val key: TimelineKey,
    val activeAt: Long,
    val text: String,
    val pendingPush: Boolean
) {
    companion object {
        fun from(entry: TimelineEntry, imageCount: Int = 0, stickerCount: Int = 0): ConversationPreview {
            val hit = entry.historySearchHit()
            val pendingPush = (entry as? TimelineEntry.MessageRow)
                ?.message
                ?.let { it.role == MessageRole.USER && !it.sentToModel }
                ?: false
            val plain = hit.text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
            val prefix = when { stickerCount > 0 -> "[表情]"; imageCount > 0 -> "[图片]"; else -> "" }
            val text = if (prefix.isNotEmpty()) (prefix + if (plain.isBlank()) "" else " $plain") else plain
            return ConversationPreview(hit.conversationId, hit.key, hit.position.at, text, pendingPush)
        }
    }
}

data class PersonaGroup(val key: String, val persona: Persona?, val conversations: List<Conversation>)

fun buildPersonaGroups(
    personas: List<Persona>,
    conversations: List<Conversation>,
    previews: Map<String, ConversationPreview>
): List<PersonaGroup> {
    val groups = personas.map { persona ->
        val owned = conversations.filter { it.personaId == persona.id && !it.archived }
        PersonaGroup("persona:${persona.id}", persona, sortConversationsByActivity(owned, previews))
    }
    val personaIds = personas.mapTo(mutableSetOf()) { it.id }
    val unassigned = conversations.filter { !it.archived && it.personaId !in personaIds }
    return (groups + listOfNotNull(unassigned.takeIf { it.isNotEmpty() }
        ?.let { PersonaGroup("unassigned", null, sortConversationsByActivity(it, previews)) }))
        .sortedWith { left, right -> comparePersonaActivity(left.key, left.conversations,
            right.key, right.conversations, previews,
            left.persona?.createdAt ?: left.conversations.maxOfOrNull { it.createdAt } ?: 0L,
            right.persona?.createdAt ?: right.conversations.maxOfOrNull { it.createdAt } ?: 0L) }
}

/** 防止较早启动的异步读取把较新的列表状态盖回去。 */
class RefreshGeneration {
    private var current = 0L

    fun begin(): Long = ++current
    fun isCurrent(generation: Long): Boolean = generation == current
}

fun sortConversationsByActivity(
    conversations: List<Conversation>,
    previews: Map<String, ConversationPreview>
): List<Conversation> = conversations.sortedWith { left, right ->
    compareConversationsByActivity(left, right, previews)
}

private fun compareConversationsByActivity(
    left: Conversation,
    right: Conversation,
    previews: Map<String, ConversationPreview>
): Int {
    if (left.pinned != right.pinned) return if (left.pinned) -1 else 1
    val leftPreview = previews[left.id]
    val rightPreview = previews[right.id]
    if ((leftPreview == null) != (rightPreview == null)) return if (leftPreview != null) -1 else 1
    if (leftPreview != null && rightPreview != null) {
        val byActivity = rightPreview.activeAt.compareTo(leftPreview.activeAt)
        if (byActivity != 0) return byActivity
    } else {
        val byCreation = right.createdAt.compareTo(left.createdAt)
        if (byCreation != 0) return byCreation
    }
    return left.id.compareTo(right.id)
}

/** 人物行按其未归档会话的最近可见记录排序；全空时按最近创建的会话排序。 */
fun comparePersonaActivity(
    leftKey: String,
    leftConversations: List<Conversation>,
    rightKey: String,
    rightConversations: List<Conversation>,
    previews: Map<String, ConversationPreview>,
    leftCreatedAt: Long? = null,
    rightCreatedAt: Long? = null
): Int {
    val leftAt = leftConversations.asSequence().filterNot { it.archived }
        .mapNotNull { previews[it.id]?.activeAt }.maxOrNull()
    val rightAt = rightConversations.asSequence().filterNot { it.archived }
        .mapNotNull { previews[it.id]?.activeAt }.maxOrNull()
    if ((leftAt == null) != (rightAt == null)) return if (leftAt != null) -1 else 1
    if (leftAt != null && rightAt != null) {
        val byActivity = rightAt.compareTo(leftAt)
        if (byActivity != 0) return byActivity
    } else {
        val leftCreated = leftCreatedAt ?: leftConversations.filterNot { it.archived }.maxOfOrNull { it.createdAt } ?: 0L
        val rightCreated = rightCreatedAt ?: rightConversations.filterNot { it.archived }.maxOfOrNull { it.createdAt } ?: 0L
        val byCreation = rightCreated.compareTo(leftCreated)
        if (byCreation != 0) return byCreation
    }
    return leftKey.compareTo(rightKey)
}
