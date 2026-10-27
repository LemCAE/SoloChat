package app.solochat.data.backup

import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupData
import java.io.ByteArrayOutputStream
import java.io.File
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
}
