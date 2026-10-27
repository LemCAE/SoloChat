package app.solochat.data.backup

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.backup.BackupMediaAsset
import app.solochat.data.MediaLibrary
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.test.IsolatedMediaContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupMediaDecodeValidationTest {
    @Test fun previewValidationRejectsPngHeaderWithoutDecodablePixels() {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "decode")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).build()
        val library = MediaLibrary(context, RoomStore(database))
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val valid = staged(context.cacheDir, encoded)
        val damaged = staged(context.cacheDir, encoded.copyOfRange(0, 33)) // PNG signature 与 IHDR 有效，但没有图像数据。
        try {
            library.validateBackupStaged(descriptor(encoded, 3, 2), valid)
            assertTrue(runCatching { library.validateBackupStaged(descriptor(damaged.readBytes(), 3, 2), damaged) }.isFailure)
        } finally {
            valid.delete()
            damaged.delete()
            database.close()
            context.clean()
        }
    }

    private fun staged(directory: File, bytes: ByteArray) = File.createTempFile("backup-image-", ".part", directory).also {
        it.writeBytes(bytes)
    }

    private fun descriptor(bytes: ByteArray, width: Int, height: Int): BackupMediaAsset {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return BackupMediaAsset("asset", sha, "image/png", bytes.size.toLong(), width, height, 0, "media/$sha-${bytes.size}.bin")
    }
}
