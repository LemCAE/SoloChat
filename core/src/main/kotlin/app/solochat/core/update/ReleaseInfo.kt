package app.solochat.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 只保存公开发布信息，不接触模型接口与密钥。 */
@Serializable data class ReleaseAsset(val name: String = "", val state: String = "", val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "")
@Serializable data class ReleaseInfo(
    @SerialName("tag_name") val tag: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val pageUrl: String = "",
    val assets: List<ReleaseAsset> = emptyList()
) {
    val apkReady: Boolean get() = assets.any { it.name == "app-release.apk" && it.state == "uploaded" && it.size > 0 &&
        it.downloadUrl.startsWith("https://github.com/LemCAE/SoloChat/releases/download/") }
}

/** SemVer 的数值段不按字典排序；构建元数据不影响版本优先级。 */
class AppVersion private constructor(val numbers: List<String>, val pre: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        fun numeric(a: String, b: String): Int = a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
        for (i in numbers.indices) numeric(numbers[i], other.numbers[i]).takeIf { it != 0 }?.let { return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            pre.isEmpty() -> 1
            else -> -1
        }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]; val b = other.pre[i]
            val an = a.all(Char::isDigit); val bn = b.all(Char::isDigit)
            val result = when {
                an && bn -> numeric(a, b)
                an != bn -> if (an) -1 else 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        private val syntax = Regex("""^[vV]?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""")
        fun parse(text: String): AppVersion? {
            val match = syntax.matchEntire(text.trim()) ?: return null
            val pre = match.groupValues[4].takeIf(String::isNotBlank)?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return AppVersion((1..3).map { match.groupValues[it] }, pre)
        }
    }
}

enum class ReleaseStatus { NO_RELEASE, NEWER_AVAILABLE, SAME_VERSION, LOCAL_NEWER, UNCOMPARABLE }
data class ReleaseCheck(val release: ReleaseInfo?, val status: ReleaseStatus)

/** 渠道由用户显式选择；本机 debug 后缀不改变渠道，草稿永远不展示。 */
fun checkReleases(releases: List<ReleaseInfo>, current: String, includePrerelease: Boolean): ReleaseCheck {
    val candidates = releases.filter { !it.draft && (includePrerelease || !it.prerelease) }
    if (candidates.isEmpty()) return ReleaseCheck(null, ReleaseStatus.NO_RELEASE)
    val selected = candidates.filter { AppVersion.parse(it.tag) != null }.maxWithOrNull(
        compareBy<ReleaseInfo> { AppVersion.parse(it.tag)!! }.thenBy { it.publishedAt.orEmpty() })
        ?: candidates.maxBy { it.publishedAt.orEmpty() }
    val local = AppVersion.parse(current)
    val remote = AppVersion.parse(selected.tag)
    val status = if (local == null || remote == null) ReleaseStatus.UNCOMPARABLE else when {
        remote > local -> ReleaseStatus.NEWER_AVAILABLE
        remote < local -> ReleaseStatus.LOCAL_NEWER
        else -> ReleaseStatus.SAME_VERSION
    }
    return ReleaseCheck(selected, status)
}
