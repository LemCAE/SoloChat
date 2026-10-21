package app.solochat.core.backup

import app.solochat.core.model.*
import kotlinx.serialization.Serializable

/** 备份只包含业务数据；接口配置、密钥、日志和网页命令不进入这个结构。 */
@Serializable
data class BackupData(
    val conversations: List<Conversation> = emptyList(),
    val messages: List<Message> = emptyList(),
    val events: List<Event> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val summaries: List<SceneSummary> = emptyList(),
    val revisions: List<ContextRevision> = emptyList(),
    val usages: List<BackupUsage> = emptyList(),
    val calls: List<BackupCall> = emptyList()
) {
    fun counts(): Map<String, Int> = linkedMapOf(
        "conversations" to conversations.size, "messages" to messages.size, "events" to events.size,
        "personas" to personas.size, "memories" to memories.size, "summaries" to summaries.size,
        "revisions" to revisions.size, "segments" to revisions.sumOf { it.segments.size },
        "usages" to usages.size, "calls" to calls.size
    )
}

/** 保留旧 usage 表的身份与时间；不能只备份已关联的新调用。 */
@Serializable
data class BackupUsage(val id: Long, val conversationId: String, val model: String,
    val usage: Usage, val at: Long)

/** CallRecord 已包含 detailJson 的业务字段，usageId 单独保存。 */
@Serializable
data class BackupCall(val record: CallRecord, val usageId: Long? = null)

@Serializable
data class BackupManifest(
    val format: String = "solochat-backup",
    val version: Int = 1,
    val id: String,
    val createdAt: Long,
    val dataChecksum: String,
    val counts: Map<String, Int>,
    val excluded: List<String> = BACKUP_EXCLUDED
)

/** 首版协议明确排除这些项目，避免把运行权限或本机路径当成已恢复的能力。 */
val BACKUP_EXCLUDED = listOf("secrets", "interface-settings", "preferences", "logs", "client-commands", "avatar-files")

data class BackupArchive(val manifest: BackupManifest, val data: BackupData)

/**
 * 校验所有必须存在的关系。已删除会话的记忆/账目来源、已删除人物卡归属是合法历史。
 * 摘要来源指纹是否仍有效由导入规划单独判断，失效版本保留文本而不重新激活。
 */
fun validateBackup(data: BackupData) {
    fun <T, K> unique(rows: List<T>, name: String, key: (T) -> K): Map<K, T> {
        val result = rows.associateBy(key)
        require(result.size == rows.size) { "$name 存在重复 ID" }
        require(result.keys.all { if (it is Long) it > 0 else it is String && it.isNotBlank() }) { "$name ID 无效" }
        return result
    }
    val conversations = unique(data.conversations, "会话") { it.id }
    val messages = unique(data.messages, "消息") { it.id }
    val events = unique(data.events, "事件") { it.id }
    unique(data.personas, "人物卡") { it.id }
    unique(data.memories, "记忆") { it.id }
    unique(data.summaries, "旧摘要") { it.id }
    val revisions = unique(data.revisions, "摘要版本") { it.id }
    unique(data.revisions.flatMap { it.segments }, "摘要分段") { it.id }
    val usages = unique(data.usages, "用量") { it.id }
    unique(data.calls, "调用") { it.record.id }
    data.messages.forEach { require(it.conversationId in conversations) { "消息缺少所属会话" } }
    data.events.forEach { require(it.conversationId in conversations) { "事件缺少所属会话" } }
    fun eventBoundary(id: Long, conversationId: String) {
        require(id == 0L || events[id]?.conversationId == conversationId) { "事件边界缺失或跨会话" }
    }
    data.conversations.forEach { c ->
        require(c.compressionMethod in CompressionMethod.entries.map { it.name }) { "压缩方式未知" }
        eventBoundary(c.modeSinceEventId, c.id)
        c.activeContextRevisionId?.let { require(revisions[it]?.conversationId == c.id) { "启用摘要缺失或跨会话" } }
    }
    data.summaries.forEach {
        require(it.conversationId in conversations) { "旧摘要缺少所属会话" }
        eventBoundary(it.fromEventId, it.conversationId)
        eventBoundary(it.toEventId, it.conversationId)
    }
    data.revisions.forEach { r ->
        require(r.conversationId in conversations && r.source.conversationId == r.conversationId) { "摘要归属无效" }
        require(r.state in setOf("DRAFT", "COMPLETE")) { "摘要状态未知" }
        require(r.parentRevisionId == r.source.parentRevisionId) { "摘要父版本不一致" }
        r.parentRevisionId?.let { require(revisions[it]?.conversationId == r.conversationId) { "摘要父版本缺失或跨会话" } }
        require(r.source.messages.map { it.id }.distinct().size == r.source.messages.size &&
            r.source.events.map { it.id }.distinct().size == r.source.events.size) { "摘要来源重复" }
        r.source.messages.forEach { require(messages[it.id]?.conversationId == r.conversationId) { "摘要来源消息缺失或跨会话" } }
        r.source.events.forEach { require(events[it.id]?.conversationId == r.conversationId) { "摘要来源事件缺失或跨会话" } }
        r.segments.forEach {
            require(it.fromMessageId in r.source.messages.map { source -> source.id } &&
                it.toMessageId in r.source.messages.map { source -> source.id }) { "摘要分段边界缺失" }
        }
    }
    // 用迭代路径而非递归，坏包不能用极深父链耗尽调用栈。
    val done = mutableSetOf<String>()
    revisions.keys.forEach { start ->
        val path = mutableSetOf<String>()
        var id: String? = start
        while (id != null && id !in done) {
            require(path.add(id)) { "摘要父版本形成循环" }
            id = revisions.getValue(id).parentRevisionId
        }
        done += path
    }
    val linkedUsage = mutableSetOf<Long>()
    data.calls.forEach { call ->
        val record = call.record
        require(record.operationId.isNotBlank() && record.conversationId.isNotBlank()) { "调用来源无效" }
        require(listOf(record.price.inputPerM, record.price.outputPerM, record.price.cachedPerM,
            record.price.imagePerCall).all { it.isFinite() && it >= 0.0 }) { "调用价格无效" }
        call.usageId?.let {
            val usage = usages[it]
            require(usage != null && usage.conversationId == record.conversationId && usage.model == record.model) { "调用用量关联缺失或不一致" }
            require(linkedUsage.add(it)) { "同一用量关联了多个调用" }
        }
        record.contextMessageIds.forEach {
            // 删除会话后账目仍在；副本用负数保留旧消息号，避免误指向本机的正数主键。
            require(it != 0L && (it !in messages || messages[it]?.conversationId == record.conversationId)) { "调用上下文跨会话" }
            require(record.conversationId !in conversations || it in messages) { "调用上下文消息缺失" }
        }
    }
    data.usages.forEach {
        require(it.conversationId.isNotBlank()) { "用量来源为空" }
        require(listOf(it.usage.promptTokens, it.usage.completionTokens, it.usage.cachedTokens,
            it.usage.reasoningTokens, it.usage.imageCount).all { n -> n >= 0 }) { "用量不能为负数" }
    }
}
