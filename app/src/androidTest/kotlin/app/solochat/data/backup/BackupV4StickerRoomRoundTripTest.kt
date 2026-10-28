package app.solochat.data.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.backup.BackupCodec
import app.solochat.core.model.Conversation
import app.solochat.core.model.InputSubmission
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Persona
import app.solochat.core.model.StickerSubmissionIntent
import app.solochat.data.MediaLibrary
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.test.IsolatedMediaContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 合成 Room 中的双库和历史表情经 ZIP v4 恢复、再次导出后关系仍完整。 */
@RunWith(AndroidJUnit4::class)
class BackupV4StickerRoomRoundTripTest {
    @Test fun roomStickerLibrariesAndHistoryRestoreAndExportAgain() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceContext = IsolatedMediaContext(base, "p4-v4-source")
        val targetContext = IsolatedMediaContext(base, "p4-v4-target")
        val sourceDb = Room.inMemoryDatabaseBuilder(sourceContext, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        val targetDb = Room.inMemoryDatabaseBuilder(targetContext, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        val staged = linkedMapOf<String, File>()
        try {
            val sourceStore = RoomStore(sourceDb)
            val sourceLibrary = MediaLibrary(sourceContext, sourceStore)
            sourceStore.upsertPersona(Persona("source-persona", "TEST-P4-备份人物"))
            sourceStore.upsertConversation(Conversation("source-conversation", "TEST-P4-备份会话", personaId = "source-persona"))
            val asset = sourceLibrary.importSyntheticTestImage(10)
            val userSticker = sourceStore.addUserSticker("user-sticker-p4-backup", asset.id, 11)
            val personaSticker = sourceStore.addPersonaSticker("persona-sticker-p4-backup", "source-persona", asset.id, 12)
            sourceStore.editPersonaSticker(personaSticker.copy(name = "固定名称", description = "固定描述", updatedAt = 13),
                setOf("name", "description"))
            val intent = StickerSubmissionIntent(userSticker.id, "source-persona")
            val submission = InputSubmission("p4-history-sticker", "source-conversation", emptyList(), listOf(asset.id), 13,
                stickerIntent = intent)
            val historySticker = Message(conversationId = "source-conversation", role = MessageRole.USER,
                text = "", turnId = 13, createdAt = 13, sentToModel = false)
            val appended = sourceStore.appendStickerSubmission(submission, emptyList(), historySticker, intent)
            assertTrue(appended.inserted)

            val exported = RoomBackupReader(sourceDb).readWithMedia(sourceLibrary)
            val firstZip = ByteArrayOutputStream()
            BackupCodec.writeV4(exported.data, firstZip, 20, exported.mediaSources, "p4-room-v4-source")
            val archive = BackupCodec.read(ByteArrayInputStream(firstZip.toByteArray())) { descriptor, input ->
                val file = File.createTempFile("${descriptor.sha256.take(8)}-", ".part", targetContext.cacheDir)
                FileOutputStream(file).use(input::copyTo)
                staged[descriptor.entryKey] = file
            }
            assertEquals(4, archive.manifest.version)
            assertEquals(1, archive.data.stickers.userStickers.size)
            assertEquals(1, archive.data.stickers.personaStickers.size)
            assertEquals(1, archive.data.stickers.messageStickers.size)

            val targetStore = RoomStore(targetDb)
            val targetLibrary = MediaLibrary(targetContext, targetStore)
            targetDb.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_p4_v4_sticker BEFORE INSERT ON persona_sticker
                BEGIN SELECT RAISE(ABORT, 'synthetic v4 restore failure'); END
            """.trimIndent())
            assertTrue(runCatching {
                RoomBackupImporter(targetDb).restore(archive, staged, targetLibrary, 21, "p4-room-v4-import")
            }.isFailure)
            assertTrue(targetStore.listConversations().isEmpty())
            assertTrue(targetStore.userStickers().isEmpty())
            assertTrue(targetDb.media().allAssets().isEmpty())
            assertTrue(File(targetContext.filesDir, "media/objects").listFiles().orEmpty().none { it.isFile })
            targetDb.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_p4_v4_sticker")

            val restored = RoomBackupImporter(targetDb).restore(archive, staged, targetLibrary, 22, "p4-room-v4-import")
            assertFalse(restored.alreadyCommitted)
            val restoredConversation = targetStore.listConversations().single()
            val restoredPersona = targetStore.listPersonas().single()
            assertEquals("TEST-P4-备份人物", restoredPersona.name)
            assertEquals(restoredPersona.id, restoredConversation.personaId)
            val restoredUsers = targetStore.userStickers()
            val restoredPersonaStickers = targetStore.personaStickers(restoredPersona.id)
            assertEquals(1, restoredUsers.size)
            assertEquals(1, restoredPersonaStickers.size)
            assertEquals(restoredUsers.single().mediaId, restoredPersonaStickers.single().mediaId)
            assertEquals("固定名称", restoredPersonaStickers.single().name)
            assertEquals("固定描述", restoredPersonaStickers.single().description)
            assertTrue(restoredPersonaStickers.single().nameLocked)
            assertTrue(restoredPersonaStickers.single().descriptionLocked)
            assertEquals(1, restoredPersonaStickers.single().nameVersion)
            assertEquals(1, restoredPersonaStickers.single().descriptionVersion)
            assertNotNull(targetStore.mediaAsset(restoredUsers.single().mediaId))
            val restoredMessages = targetStore.recentMessages(restoredConversation.id, -1)
            assertEquals(listOf(""), restoredMessages.map { it.text })
            val historyStickers = targetStore.messageStickers(restoredMessages.map { it.id })
            assertEquals(1, historyStickers.size)
            assertEquals(restoredPersona.id, historyStickers.single().personaId)
            assertEquals(restoredUsers.single().id, historyStickers.single().sourceUserStickerId)
            assertEquals(restoredUsers.single().mediaId,
                targetStore.mediaAssetsForMessages(restoredMessages.map { it.id }).values.flatten().single().second.id)

            val restoredSnapshot = RoomBackupReader(targetDb).readWithMedia(targetLibrary)
            val secondZip = ByteArrayOutputStream()
            BackupCodec.writeV4(restoredSnapshot.data, secondZip, 23, restoredSnapshot.mediaSources, "p4-room-v4-re-export")
            val secondArchive = BackupCodec.read(ByteArrayInputStream(secondZip.toByteArray())) { _, input -> input.readBytes() }
            assertEquals(1, secondArchive.data.stickers.userStickers.size)
            assertEquals(1, secondArchive.data.stickers.personaStickers.size)
            assertEquals("固定名称", secondArchive.data.stickers.personaStickers.single().name)
            assertEquals("固定描述", secondArchive.data.stickers.personaStickers.single().description)
            assertTrue(secondArchive.data.stickers.personaStickers.single().nameLocked)
            assertTrue(secondArchive.data.stickers.personaStickers.single().descriptionLocked)
            assertEquals(1, secondArchive.data.stickers.personaStickers.single().nameVersion)
            assertEquals(1, secondArchive.data.stickers.personaStickers.single().descriptionVersion)
            assertEquals(1, secondArchive.data.stickers.messageStickers.size)
            assertEquals(restoredSnapshot.data.stickers.messageStickers.single().personaId,
                secondArchive.data.stickers.messageStickers.single().personaId)
            assertEquals(1, secondArchive.data.media.assets.size)
        } finally {
            sourceDb.close()
            targetDb.close()
            staged.values.forEach(File::delete)
            sourceContext.clean()
            targetContext.clean()
        }
    }
}
