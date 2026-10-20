package app.solochat.play

import app.solochat.core.agent.CompressionPreview
import app.solochat.core.agent.CompressionService
import app.solochat.core.model.ContextRevision
import app.solochat.core.llm.LlmException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CompressionUiState(val generating: Boolean = false, val completed: Int = 0,
    val maximum: Int = 0, val draft: ContextRevision? = null, val error: String = "")

/** 生成属于应用而非页面；返回聊天不会取消摘要，完成后草稿保存在 Room。 */
class CompressionCoordinator(val service: CompressionService, private val scope: CoroutineScope) {
    private val states = mutableMapOf<String, MutableStateFlow<CompressionUiState>>()
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Any()
    fun state(id: String): StateFlow<CompressionUiState> = synchronized(lock) {
        states.getOrPut(id) { MutableStateFlow(CompressionUiState()) }
    }
    fun generate(preview: CompressionPreview) = synchronized(lock) {
        val id = preview.source.conversationId
        val state = states.getOrPut(id) { MutableStateFlow(CompressionUiState()) }
        if (state.value.generating) return@synchronized
        state.value = CompressionUiState(generating = true, maximum = preview.maximumCalls)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val draft = service.generate(preview) { completed, maximum ->
                    state.value = state.value.copy(completed = completed, maximum = maximum)
                }
                state.value = state.value.copy(draft = draft)
            } catch (cancelled: CancellationException) {
                state.value = state.value.copy(error = "生成已取消；已发生调用仍会记账，启用摘要保持原样")
                throw cancelled
            } catch (e: Exception) {
                state.value = state.value.copy(error = if (e is LlmException) "摘要请求失败：${e.error.message}" else e.message ?: "摘要生成失败")
            } finally {
                synchronized(lock) { jobs.remove(id); state.value = state.value.copy(generating = false) }
            }
        }
        jobs[id] = job
        job.start()
    }
    fun cancel(id: String) = synchronized(lock) { jobs[id]?.cancel() }
    fun updateDraft(id: String, draft: ContextRevision?) = synchronized(lock) {
        val state = states.getOrPut(id) { MutableStateFlow(CompressionUiState()) }
        state.value = state.value.copy(draft = draft)
    }
}
