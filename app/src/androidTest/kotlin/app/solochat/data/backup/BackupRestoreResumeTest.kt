package app.solochat.data.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupData
import app.solochat.data.MediaLibrary
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.test.IsolatedMediaContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreResumeTest {
    @Test fun committedImportIdIsReportedAfterImporterRecreation() = runBlocking {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "resume")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).build()
        try {
            val output = ByteArrayOutputStream()
            val manifest = BackupCodec.writeV3(BackupData(), output, 10, emptyMap(), "resume-backup")
            val archive = BackupCodec.read(output.toByteArray().inputStream())
            val library = MediaLibrary(context, RoomStore(database))
            val first = RoomBackupImporter(database).restore(archive, emptyMap(), library, 20, "confirmed-operation")
            assertFalse(first.alreadyCommitted)

            val recreatedImporter = RoomBackupImporter(database)
            val recovered = recreatedImporter.committedResult("confirmed-operation", manifest.id, manifest.dataChecksum)
            assertTrue(recovered?.alreadyCommitted == true)
            assertTrue(recreatedImporter.restore(archive, emptyMap(), library, 21, "confirmed-operation").alreadyCommitted)
        } finally {
            database.close()
            context.clean()
        }
    }
}
