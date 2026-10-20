package app.solochat.data

import app.solochat.core.model.CallRecord
import app.solochat.core.model.Usage
import app.solochat.core.model.summarizeCalls
import app.solochat.data.db.UsageEntity

/** 事务内读取新调用与未关联的旧记录；界面共用同一份聚合口径。 */
data class StatisticsSnapshot(val calls: List<CallRecord>, val legacy: List<UsageEntity>) {
    val totals get() = summarizeCalls(calls, legacy.map { Usage(it.promptTokens, it.completionTokens,
        it.cachedTokens, it.reasoningTokens, it.imageCount, it.estimated) })
}
