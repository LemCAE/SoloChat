package app.solochat.core.model

/** 向较新方向的一页；统一按时间轴位置升序返回。 */
data class TimelineFollowingPage(val entries: List<TimelineEntry>, val hasLater: Boolean)

/** 目标前后文与实时播出窗口分开持有，不能把两者之间的空档逐页补齐。 */
data class TimelineContext(
    val target: TimelineKey,
    val entries: List<TimelineEntry>,
    val hasEarlier: Boolean,
    val hasLater: Boolean
)

fun timelineFollowingPage(entries: List<TimelineEntry>, after: TimelinePosition, limit: Int): TimelineFollowingPage {
    require(limit in 1..200)
    val eligible = entries.filter { it.position > after }
    return TimelineFollowingPage(eligible.take(limit), eligible.size > limit)
}
