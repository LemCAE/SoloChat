package app.solochat.data

import app.solochat.core.model.CallRecord
import app.solochat.core.model.Usage
import app.solochat.core.model.summarizeCalls
import app.solochat.data.db.UsageEntity

/** 事务内读取新调用与未关联的旧记录；界面共用同一份聚合口径。 */
data class StatisticsSnapshot(val calls: List<CallRecord>, val legacy: List<UsageEntity>,
    val importedCalls: List<CallRecord> = emptyList(), val importedLegacy: List<UsageEntity> = emptyList()) {
    val totals = summarizeCalls(calls, legacy.map { Usage(it.promptTokens, it.completionTokens,
        it.cachedTokens, it.reasoningTokens, it.imageCount, it.estimated) })
}

/** 导入账目仍可查看，但不与本机新消费相加；按行身份判定，不按整个会话排除后续调用。 */
fun partitionStatistics(calls: List<CallRecord>, legacy: List<UsageEntity>,
    importedCallIds: Set<String>, importedUsageIds: Set<String>): StatisticsSnapshot {
    val (importedCalls, localCalls) = calls.partition { it.id in importedCallIds }
    val (importedLegacy, localLegacy) = legacy.partition { it.id.toString() in importedUsageIds }
    return StatisticsSnapshot(localCalls, localLegacy, importedCalls, importedLegacy)
}
