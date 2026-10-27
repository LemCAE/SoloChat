package app.solochat.data.backup

import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BackupRestoreJournalTest {
    @Test fun `确认后的恢复操作在新journal实例中保留同一来源身份和续接文件`() {
        val root = File("build/tmp/restore-journal-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val zip = ByteArrayOutputStream()
        val manifest = BackupCodec.writeV3(BackupData(), zip, 10, emptyMap(), "backup")
        val source = File(root, "source.zip").apply { writeBytes(zip.toByteArray()) }
        val directory = File(root, "pending-operation")
        try {
            val first = BackupRestoreJournal(directory)
            val confirmed = first.persist(source, "confirmed-import-id", manifest)

            val recreated = BackupRestoreJournal(directory)
            assertEquals(confirmed, recreated.load())
            assertEquals(source.readBytes().toList(), recreated.sourceZip(confirmed).readBytes().toList())
            val reread = recreated.sourceZip(confirmed).inputStream().use { BackupCodec.read(it).manifest.id }
            assertEquals(manifest.id, reread)
            assertEquals(confirmed, recreated.persist(null, confirmed.importId, manifest))
            assertTrue(runCatching {
                recreated.persist(null, "different-operation", manifest.copy(id = "another-backup"))
            }.isFailure)

            recreated.clear()
            assertEquals(null, BackupRestoreJournal(directory).load())
            val deliberateReimport = recreated.persist(source, "new-import-id", manifest)
            assertEquals("new-import-id", deliberateReimport.importId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `Room已提交但journal清理失败时重试只确认原操作并可在ZIP缺失时识别提交`() {
        val root = File("build/tmp/restore-runner-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val zip = ByteArrayOutputStream()
        val manifest = BackupCodec.writeV3(BackupData(), zip, 10, emptyMap(), "runner-backup")
        val source = File(root, "source.zip").apply { writeBytes(zip.toByteArray()) }
        val directory = File(root, "pending-operation")
        var committed = false
        var writes = 0
        var cleanupAttempts = 0
        val journal = BackupRestoreJournal(directory)
        val runner = BackupRestoreRunner(journal) {
            if (cleanupAttempts++ == 0) throw IOException("测试清理失败")
            journal.clear()
        }
        try {
            val first = kotlinx.coroutines.runBlocking {
                runner.run(source, "same-import", manifest) {
                    writes++
                    committed = true
                    BackupRestoreResult("same-import", 2, alreadyCommitted = false)
                }
            }
            assertTrue(first.cleanupError is IOException)
            val record = requireNotNull(BackupRestoreJournal(directory).load())
            BackupRestoreJournal(directory).sourceZip(record).delete()

            val recreatedRunner = BackupRestoreRunner(BackupRestoreJournal(directory)) {
                if (cleanupAttempts++ == 0) throw IOException("测试清理失败")
                BackupRestoreJournal(directory).clear()
            }
            val second = kotlinx.coroutines.runBlocking {
                recreatedRunner.run(null, record.importId, manifest) {
                    assertTrue("重建后先依据 Room 提交记录识别原操作", committed)
                    BackupRestoreResult("same-import", 2, alreadyCommitted = true)
                }
            }
            assertEquals(null, second.cleanupError)
            assertTrue(second.result.alreadyCommitted)
            assertEquals(1, writes)
            assertEquals(null, BackupRestoreJournal(directory).load())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `恢复启动检查期间页面操作被挡住且失效回调不能重新发布旧结果`() {
        val gate = BackupRestoreBootstrapGate()
        val bootstrap = gate.begin()
        assertTrue(!gate.canStartPageAction())
        assertTrue(gate.isCurrent(bootstrap))
        assertTrue(gate.finish(bootstrap))
        assertTrue(gate.canStartPageAction())

        val lateBootstrap = gate.begin()
        gate.invalidate() // 对应页面销毁/启动工作取消；之后完成的旧回调不能更新页面状态。
        assertTrue(!gate.isCurrent(lateBootstrap))
        assertTrue(!gate.finish(lateBootstrap))
        assertTrue(!gate.canStartPageAction())
    }
}
