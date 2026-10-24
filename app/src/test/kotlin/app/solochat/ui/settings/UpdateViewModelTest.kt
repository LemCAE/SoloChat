package app.solochat.ui.settings

import app.solochat.core.update.ReleaseStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @Test fun `重复检查复用进行中任务且失败不会显示已是最新`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val gate = CompletableDeferred<Unit>()
            val model = UpdateViewModel { calls++; gate.await(); throw IOException("GitHub 限流，请稍后重试") }
            model.check("connect failed", "fetch failed"); model.check("connect failed", "fetch failed"); model.channel(true)
            runCurrent()
            assertEquals(1, calls)
            assertTrue(model.state.value.checking)
            assertFalse(model.state.value.includePrerelease)
            gate.complete(Unit); advanceUntilIdle()
            assertFalse(model.state.value.checking)
            assertNull(model.state.value.result)
            assertTrue(model.state.value.error.contains("限流"))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `成功空列表是尚无发布且切换渠道清除旧结果不自动联网`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val model = UpdateViewModel { calls++; emptyList() }
            model.check("connect failed", "fetch failed"); advanceUntilIdle()
            assertEquals(ReleaseStatus.NO_RELEASE, model.state.value.result!!.status)
            assertTrue(model.state.value.error.isEmpty())
            model.channel(true); advanceUntilIdle()
            assertNull(model.state.value.result)
            assertTrue(model.state.value.includePrerelease)
            assertEquals(1, calls)
        } finally { Dispatchers.resetMain() }
    }
}
