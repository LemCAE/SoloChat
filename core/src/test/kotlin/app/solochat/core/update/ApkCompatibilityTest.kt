package app.solochat.core.update

import org.junit.Assert.*
import org.junit.Test

class ApkCompatibilityTest {
    private val installed = ApkIdentity("app.solochat", 9, "0.1.0-local", setOf("release"))
    @Test fun `同签名同包名允许更高版本码和同版本覆盖`() {
        assertTrue(checkApkCompatibility(installed, installed.copy(versionCode = 10), 35).allowed)
        val same = checkApkCompatibility(installed, installed, 35)
        assertTrue(same.allowed)
        assertTrue(same.message.contains("同版本"))
    }
    @Test fun `较新标签不能绕过包内版本码降级`() {
        assertFalse(checkApkCompatibility(installed, installed.copy(versionName = "99.0.0", versionCode = 8), 35).allowed)
    }
    @Test fun `独立debug安装说明资料边界且不声称覆盖迁移`() {
        val result = checkApkCompatibility(installed.copy(packageName = "app.solochat.debug"), installed, 35)
        assertFalse(result.allowed)
        assertTrue(result.message.contains("副本"))
    }
    @Test fun `包名不符未签名解析失败和系统版本不足都拒绝`() {
        for (candidate in listOf(null, installed.copy(packageName = "other.app"),
            installed.copy(signers = emptySet()), installed.copy(minSdk = 36))) {
            assertFalse(checkApkCompatibility(installed, candidate, 35).allowed)
        }
        assertFalse(checkApkCompatibility(installed.copy(signers = emptySet()), installed, 35).allowed)
    }
    @Test fun `多签名要求集合一致且不推断证书轮换兼容`() {
        val multi = installed.copy(signers = setOf("a", "b"))
        assertTrue(checkApkCompatibility(multi, multi.copy(signers = setOf("b", "a")), 35).allowed)
        assertFalse(checkApkCompatibility(multi, multi.copy(signers = setOf("a")), 35).allowed)
        assertFalse(checkApkCompatibility(installed, installed.copy(signers = setOf("new")), 35).allowed)
    }
}
