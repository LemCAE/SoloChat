package app.solochat.core.agent

import app.solochat.core.model.*
import app.solochat.core.prompt.PromptBuilder

data class CompressionCut(val messageId: Long, val count: Int, val retained: Int, val at: Long, val preview: String)

object CompressionHistory {
    /** 仅在已出口的完整助手调用组之后切。用户与助手 turnId 不相等，不拼成同轮问答。 */
    fun groupEnds(history: List<Message>, events: List<Event>): List<Int> {
        return history.indices.filter { index ->
            val message = history[index]
            if (message.role != MessageRole.ASSISTANT || message.turnId <= 0) return@filter false
            if (history.drop(index + 1).any { it.role == MessageRole.ASSISTANT && it.turnId == message.turnId }) return@filter false
            val groupEvents = events.filter { it.turnId == message.turnId && it.kind in
                setOf(EventKind.SAY, EventKind.ACT, EventKind.SCENE) }
            groupEvents.isNotEmpty() && groupEvents.any { it.usableAsContext } &&
                groupEvents.none { it.delivery == DeliveryState.PENDING || it.delivery == DeliveryState.PLAYING } &&
                history.filter { it.role == MessageRole.ASSISTANT && it.turnId == message.turnId }
                    .joinToString("\n") { PromptBuilder.assistantContextText(it.text.trim()) } ==
                    PromptBuilder.eventsToAssistantText(groupEvents)
        }
    }

    /** 保留最新完整交流及其后的待回应输入/系统事实；不确定的回复组不提供切点。 */
    fun cuts(history: List<Message>, events: List<Event>): List<CompressionCut> {
        val lastAssistant = history.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistant < 0) return emptyList()
        val lastTurn = history[lastAssistant].turnId
        val firstOfLast = history.indexOfFirst { it.role == MessageRole.ASSISTANT && it.turnId == lastTurn }
        val priorAssistant = (firstOfLast - 1 downTo 0).firstOrNull { history[it].role == MessageRole.ASSISTANT } ?: -1
        val keepFrom = priorAssistant + 1
        val ends = groupEnds(history, events)
        val confirmedTurns = ends.map { history[it].turnId }.toSet()
        val firstUncertain = history.indexOfFirst { it.role == MessageRole.ASSISTANT && it.turnId !in confirmedTurns }
            .let { if (it < 0) history.size else it }
        return ends.filter { it < keepFrom && it < firstUncertain }.map { index ->
            val m = history[index]
            CompressionCut(m.id, index + 1, history.size - index - 1, m.createdAt, m.text.take(70))
        }
    }
}
