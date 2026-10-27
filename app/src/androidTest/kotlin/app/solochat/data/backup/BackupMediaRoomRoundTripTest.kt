package app.solochat.data.backup

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.backup.BackupCodec
import app.solochat.core.model.CallConfigSnapshot
import app.solochat.core.model.CallKind
import app.solochat.core.model.CallMediaInput
import app.solochat.core.model.CallRecord
import app.solochat.core.model.CallStatus
import app.solochat.core.model.Conversation
import app.solochat.core.model.MediaAsset
import app.solochat.core.model.MediaContextReference
import app.solochat.core.model.MediaReferenceSource
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.ModelPrice
import app.solochat.core.model.ModelThinking
import app.solochat.core.model.ModelUse
import app.solochat.core.model.Persona
import app.solochat.core.model.SelectionSource
import app.solochat.core.model.ThinkingMode
import app.solochat.core.model.ThinkingSource
import app.solochat.core.model.TokenAmount
import app.solochat.core.model.TokenSource
import app.solochat.core.model.Usage
import app.solochat.data.MediaDraftSessions
import app.solochat.data.MediaLibrary
import app.solochat.data.db.MessageAttachmentEntity
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.test.IsolatedMediaContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupMediaRoomRoundTripTest {
    @Test fun syntheticJpegPngAndWebpRestoreIntoSeparateRoomAndMediaRoot() = runBlocking {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceContext = IsolatedMediaContext(instrumentationContext, "v3-source")
        val targetContext = IsolatedMediaContext(instrumentationContext, "v3-target")
        val sourceDb = Room.inMemoryDatabaseBuilder(sourceContext, SoloChatDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(targetContext, SoloChatDatabase::class.java).build()
        val staged = linkedMapOf<String, File>()
        try {
            val sourceStore = RoomStore(sourceDb)
            val sourceLibrary = MediaLibrary(sourceContext, sourceStore)
            sourceStore.upsertPersona(Persona("synthetic-persona", "合成人物"))
            sourceStore.upsertConversation(Conversation("source-conversation", "合成媒体备份", personaId = "synthetic-persona"))
            sourceStore.upsertMemory(Memory("synthetic-memory", "只用于隔离测试", "synthetic-persona",
                createdAt = 12, sourceConversationId = "source-conversation"))
            val messageId = sourceStore.appendMessage(Message(conversationId = "source-conversation",
                role = MessageRole.USER, text = "看一下这三张图", createdAt = 11, sentToModel = true))

            val assets = listOf(
                sourceAsset(sourceContext, "jpeg", Bitmap.CompressFormat.JPEG, "image/jpeg"),
                sourceAsset(sourceContext, "png", Bitmap.CompressFormat.PNG, "image/png"),
                sourceAsset(sourceContext, "webp", Bitmap.CompressFormat.WEBP_LOSSY, "image/webp")
            )
            assets.forEach { sourceStore.publishMediaAsset(it) }
            sourceDb.media().insertAttachments(assets.mapIndexed { index, asset ->
                MessageAttachmentEntity(messageId, index, asset.id)
            })
            val sendVariant = sourceLibrary.prepareSendVariant(assets.first().id)
            val startedCall = CallRecord("synthetic-call", "synthetic-operation", "source-conversation", "synthetic-model",
                CallKind.CHAT, CallStatus.STARTED, 15, price = ModelPrice(), inputCharacters = 12)
            val callSnapshot = CallConfigSnapshot(1, "synthetic-provider", "合成提供商", "synthetic-model",
                "合成模型", "synthetic-model", ModelUse.CHAT, SelectionSource.GLOBAL_DEFAULT,
                ModelThinking(ThinkingMode.AUTO), ThinkingSource.MODEL_PREFERENCE)
            val callImage = MediaContextReference(messageId, 0, assets.first().id, assets.first().sha256,
                sendVariant.variant, sendVariant.sha256, sendVariant.mimeType, sendVariant.width, sendVariant.height,
                MediaReferenceSource.NEW)
            sourceStore.recordCallStartedWithConfigAndMedia(startedCall, callSnapshot,
                CallMediaInput(startedCall.id, listOf(callImage), 16))
            sourceStore.recordCall(startedCall.copy(status = CallStatus.SUCCESS, finishedAt = 17,
                input = TokenAmount(9, TokenSource.RETURNED), output = TokenAmount(3, TokenSource.RETURNED)),
                Usage(promptTokens = 9, completionTokens = 3, imageCount = 1))

            val exported = RoomBackupReader(sourceDb).readWithMedia(sourceLibrary)
            val zipped = ByteArrayOutputStream()
            BackupCodec.writeV3(exported.data, zipped, 20, exported.mediaSources, "synthetic-media-v3")
            val archive = BackupCodec.read(zipped.toByteArray().inputStream()) { descriptor, input ->
                val file = File.createTempFile("restore-${descriptor.sha256.take(8)}-", ".part", targetContext.cacheDir)
                FileOutputStream(file).use(input::copyTo)
                staged[descriptor.entryKey] = file
            }
            assertEquals(3, archive.data.media.assets.size)

            // Source media is removed before restore: this catches accidental path sharing.
            sourceDb.close()
            sourceContext.clean()

            val targetStore = RoomStore(targetDb)
            val targetLibrary = MediaLibrary(targetContext, targetStore)
            val importer = RoomBackupImporter(targetDb)
            targetDb.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_synthetic_import BEFORE INSERT ON conversation
                BEGIN SELECT RAISE(ABORT, 'synthetic media restore failure'); END
            """.trimIndent())
            assertTrue(runCatching { importer.restore(archive, staged, targetLibrary, 29, "synthetic-restore") }.isFailure)
            assertTrue(targetDb.conversations().all().isEmpty())
            assertTrue(targetDb.media().allAssets().isEmpty())
            assertTrue(File(targetContext.filesDir, "media/objects").listFiles().orEmpty().none { it.isFile })
            targetDb.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_synthetic_import")

            val first = importer.restore(archive, staged, targetLibrary, 30, "synthetic-restore")
            assertFalse(first.alreadyCommitted)
            assertEquals(1, first.conversationCount)

            val restoredConversation = targetStore.listConversations().single()
            assertTrue(restoredConversation.id != "source-conversation")
            val restoredMessages = targetStore.recentMessages(restoredConversation.id, -1)
            assertEquals(1, restoredMessages.size)
            assertEquals("看一下这三张图", restoredMessages.single().text)
            val restoredPersona = targetStore.listPersonas().single()
            assertEquals("合成人物", restoredPersona.name)
            val restoredMemory = targetStore.listMemories(restoredPersona.id).single()
            assertEquals(restoredConversation.id, restoredMemory.sourceConversationId)
            val restoredMedia = targetStore.mediaAssetsForMessages(restoredMessages.map { it.id })[restoredMessages.single().id].orEmpty()
            assertEquals(listOf("image/jpeg", "image/png", "image/webp"), restoredMedia.map { it.second.mimeType })
            restoredMedia.forEach { (_, asset) ->
                assertNotEquals(assets.firstOrNull { it.sha256 == asset.sha256 }?.id, asset.id)
                val decoded = targetLibrary.decodeDisplay(asset)
                try {
                    assertEquals(12, decoded.width)
                    assertEquals(8, decoded.height)
                } finally { decoded.recycle() }
                assertTrue(targetLibrary.file(asset).isFile)
            }
            val restoredCall = targetStore.calls(restoredConversation.id, 0, 100).single()
            val callInput = requireNotNull(targetStore.callMediaInput(restoredCall.id))
            assertEquals(restoredMessages.single().id, callInput.images.single().messageId)
            assertEquals(restoredMedia.first().second.id, callInput.images.single().mediaId)
            assertEquals(1, targetDb.backup().usages().single().imageCount)
            val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                MediaDraftSessions(targetStore, draftScope).addReference(restoredConversation.id,
                    restoredMedia.first().second.id, 32)
                assertEquals(listOf(restoredMedia.first().second.id),
                    targetStore.mediaDraft(restoredConversation.id)?.mediaIds)
            } finally { draftScope.cancel() }

            // The committed operation can resume after importer recreation without staged files.
            val resumed = RoomBackupImporter(targetDb).restore(archive, emptyMap(), targetLibrary, 31, "synthetic-restore")
            assertTrue(resumed.alreadyCommitted)
            assertEquals(1, targetStore.listConversations().size)

            val repeated = importer.restore(archive, staged, targetLibrary, 33, "synthetic-restore-again")
            assertFalse(repeated.alreadyCommitted)
            assertEquals(2, targetStore.listConversations().size)
            assertEquals(3, targetDb.media().allAssets().size)
        } finally {
            sourceDb.close()
            targetDb.close()
            staged.values.forEach(File::delete)
            sourceContext.clean()
            targetContext.clean()
        }
    }

    private fun sourceAsset(context: IsolatedMediaContext, label: String, format: Bitmap.CompressFormat,
        mime: String): MediaAsset {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        val bytes = ByteArrayOutputStream().also { check(bitmap.compress(format, 88, it)) }.toByteArray()
        bitmap.recycle()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = File(context.filesDir, "media/objects/$digest")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return MediaAsset("source-$label-${UUID.randomUUID()}", digest, mime, bytes.size.toLong(), 12, 8, 0,
            "objects/$digest", 10)
    }
}
