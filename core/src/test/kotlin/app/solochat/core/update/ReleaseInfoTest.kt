package app.solochat.core.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseInfoTest {
    @Test fun `版本按数字与预发布顺序比较而不是字符串`() {
        fun v(s: String) = AppVersion.parse(s)!!
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("v1.0.0-rc.10") > v("1.0.0-rc.9"))
        assertTrue(v("1.0.0") > v("1.0.0-rc.10"))
        assertEquals(0, v("1.0.0+build.7").compareTo(v("1.0.0+other")))
        assertNull(AppVersion.parse("nightly"))
        assertNull(AppVersion.parse("1.0.0-rc.01"))
    }
    @Test fun `稳定渠道排除草稿与预发布且本机debug不改变渠道`() {
        val releases = listOf(ReleaseInfo("v1.0.0"), ReleaseInfo("v2.0.0-rc.1", prerelease = true),
            ReleaseInfo("v3.0.0", draft = true))
        assertEquals("v1.0.0", checkReleases(releases, "1.0.0-debug", false).release!!.tag)
        assertEquals("v2.0.0-rc.1", checkReleases(releases, "1.0.0", true).release!!.tag)
        assertEquals(ReleaseStatus.NO_RELEASE, checkReleases(releases.drop(1), "1.0.0", false).status)
    }
    @Test fun `回补旧版的发布时间不能盖过语义更高版本`() {
        val r = checkReleases(listOf(ReleaseInfo("v1.1.0", publishedAt = "2026-01-01"),
            ReleaseInfo("v1.0.1", publishedAt = "2026-02-01")), "1.0.0", false)
        assertEquals("v1.1.0", r.release!!.tag)
        assertEquals(ReleaseStatus.NEWER_AVAILABLE, r.status)
        assertFalse(r.release!!.apkReady)
    }
    @Test fun `未知标签与本机版本不能误报最新且相同较新分别说明`() {
        assertEquals(ReleaseStatus.UNCOMPARABLE, checkReleases(listOf(ReleaseInfo("nightly")), "1.0.0", true).status)
        assertEquals(ReleaseStatus.UNCOMPARABLE, checkReleases(listOf(ReleaseInfo("1.0.0")), "local", true).status)
        assertEquals(ReleaseStatus.SAME_VERSION, checkReleases(listOf(ReleaseInfo("1.0.0")), "1.0.0", false).status)
        assertEquals(ReleaseStatus.LOCAL_NEWER, checkReleases(listOf(ReleaseInfo("1.0.0")), "1.1.0", false).status)
    }
    @Test fun `只有已上传且有内容的本仓库APK才就绪`() {
        val asset = ReleaseAsset("app-release.apk", "uploaded", 12, "https://github.com/LemCAE/SoloChat/releases/download/v1/app-release.apk")
        assertTrue(ReleaseInfo("v1", assets = listOf(asset)).apkReady)
        assertFalse(ReleaseInfo("v1", assets = listOf(asset.copy(state = "starter"))).apkReady)
        assertFalse(ReleaseInfo("v1", assets = listOf(asset.copy(size = 0))).apkReady)
        assertFalse(ReleaseInfo("v1", assets = listOf(asset.copy(name = "sources.zip"))).apkReady)
    }
}
