package app.solochat.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyBackupSnapshotTest {
    @Test fun `并发图文提交等待快照事务而不会混进无媒体旧包`() = runTest {
        val mutex = Mutex()
        var attachmentCount = 0
        val rows = mutableListOf("旧消息")
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val snapshot = async {
            readLegacyBackupSnapshot(
                transaction = { block -> mutex.withLock { block() } },
                attachmentCount = { attachmentCount },
                readRows = { reading.complete(Unit); release.await(); rows.toList() }
            )
        }
        reading.await()
        val submit = async { mutex.withLock { rows += "新图片消息"; attachmentCount++ } }
        runCurrent()
        assertFalse("数据库快照未结束前，图文提交必须等待", submit.isCompleted)
        release.complete(Unit)

        assertEquals(listOf("旧消息"), snapshot.await())
        submit.await()
        assertEquals(1, attachmentCount)
        assertTrue(rows.contains("新图片消息"))
    }

    @Test fun `快照事务开始时已有图片附件就拒绝旧格式`() = runTest {
        var rowsRead = false
        val failure = runCatching {
            readLegacyBackupSnapshot(
                transaction = { block -> block() },
                attachmentCount = { 1 },
                readRows = { rowsRead = true; "should not read" }
            )
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("不能包含图片") == true)
        assertFalse(rowsRead)
    }
}
