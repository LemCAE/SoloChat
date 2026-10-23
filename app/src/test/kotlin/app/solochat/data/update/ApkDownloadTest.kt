package app.solochat.data.update

import app.solochat.core.update.ReleaseAsset
import app.solochat.core.update.ReleaseInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ApkDownloadTest {
    private val asset = ReleaseAsset("app-release.apk", "uploaded", 4,
        "https://github.com/LemCAE/SoloChat/releases/download/v1.0.0/app-release.apk")
    private val release = ReleaseInfo("v1.0.0", assets = listOf(asset))

    @Test fun `重复点击与换版本不会替换进行中的固定下载`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val controller = ApkDownloadController(backgroundScope) { _, progress ->
            calls++; progress(2); gate.await(); File("unused")
        }
        controller.start(release); controller.start(release)
        controller.start(release.copy(tag = "v2.0.0"))
        runCurrent()
        assertEquals(1, calls)
        assertEquals("v1.0.0", controller.state.value.target!!.tag)
        assertEquals(2L, controller.state.value.received)
        controller.cancel(); runCurrent()
        assertEquals(DownloadPhase.CANCELED, controller.state.value.phase)
    }

    @Test fun `失败可重试原asset且完成后重复点击不再联网`() = runTest {
        val file = File.createTempFile("update-test", ".apk")
        try {
            var calls = 0
            val controller = ApkDownloadController(backgroundScope) { selected, _ ->
                assertEquals(asset, selected)
                if (++calls == 1) throw IOException("连接中断")
                file
            }
            controller.start(release); runCurrent()
            assertEquals(DownloadPhase.FAILED, controller.state.value.phase)
            assertEquals("连接中断", controller.state.value.error)
            controller.retry(); runCurrent()
            assertEquals(DownloadPhase.COMPLETE, controller.state.value.phase)
            controller.start(release); runCurrent()
            assertEquals(2, calls)
            assertEquals(file, controller.state.value.file)
        } finally { file.delete() }
    }

    @Test fun `取消后的旧进度不会污染重试任务且缺包不启动`() = runTest {
        var oldProgress: ((Long) -> Unit)? = null
        val controller = ApkDownloadController(backgroundScope) { _, progress ->
            oldProgress = oldProgress ?: progress
            awaitCancellation()
        }
        controller.start(release.copy(assets = emptyList()))
        assertEquals(DownloadPhase.IDLE, controller.state.value.phase)
        controller.start(release); runCurrent()
        controller.cancel(); runCurrent()
        controller.retry(); runCurrent()
        oldProgress!!(4); runCurrent()
        assertEquals(0L, controller.state.value.received)
        assertEquals(DownloadPhase.DOWNLOADING, controller.state.value.phase)
    }

    @Test fun `开始后立即取消再重试不会卡在下载中或被旧任务覆盖`() = runTest {
        var calls = 0
        val controller = ApkDownloadController(backgroundScope) { _, _ ->
            calls++
            awaitCancellation()
        }
        controller.start(release)
        controller.cancel()
        assertEquals(DownloadPhase.CANCELED, controller.state.value.phase)
        controller.retry(); runCurrent()
        assertEquals(1, calls)
        assertEquals(DownloadPhase.DOWNLOADING, controller.state.value.phase)
    }

    @Test fun `完整响应逐字写入而截断与超长响应均失败`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        copyApk(ByteArrayInputStream(bytes), output, 4, { true }, {})
        assertArrayEquals(bytes, output.toByteArray())
        for (expected in listOf(3L, 5L)) {
            try {
                copyApk(ByteArrayInputStream(bytes), ByteArrayOutputStream(), expected, { true }, {})
                fail("不完整内容不应成功")
            } catch (_: IOException) { }
        }
    }

    @Test fun `取消传输不继续写入剩余数据`() {
        val output = ByteArrayOutputStream()
        try {
            copyApk(ByteArrayInputStream(ByteArray(16)), output, 16, { false }, {})
            fail("已取消的传输不应成功")
        } catch (_: CancellationException) { }
        assertEquals(0, output.size())
    }
}
