package app.solochat.play

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

/** 应用内每个会话只有一个运行实例。页面只订阅，退出页面不结束生成或播出。 */
class SessionRegistry(
    private val scope: CoroutineScope,
    private val factory: (CoroutineScope) -> SessionController
) {
    private data class Entry(val controller: SessionController, val job: Job)
    private val entries = mutableMapOf<String, Entry>()
    private val removing = mutableSetOf<String>()

    @Synchronized
    fun get(id: String): SessionController {
        require(id.isNotBlank()) { "会话 id 不能为空" }
        check(id !in removing) { "对话正在删除" }
        return entries.getOrPut(id) {
            val job = SupervisorJob(scope.coroutineContext[Job])
            val controller = factory(CoroutineScope(scope.coroutineContext + job))
            Entry(controller, job).also { controller.open(id) }
        }.controller
    }

    /** 仅在明确删除会话时关闭；等已开始的落定写入结束，再删除数据库记录。 */
    suspend fun remove(id: String, delete: suspend () -> Unit = {}) {
        val entry = synchronized(this) {
            check(removing.add(id)) { "对话正在删除" }
            entries.remove(id)
        }
        try {
            entry?.controller?.close()
            entry?.job?.cancelAndJoin()
            delete()
        } finally {
            synchronized(this) { removing.remove(id) }
        }
    }
}
