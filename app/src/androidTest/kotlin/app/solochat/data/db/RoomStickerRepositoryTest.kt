package app.solochat.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.model.Persona
import app.solochat.core.model.Conversation
import app.solochat.core.model.EventKind
import app.solochat.core.model.InputSubmission
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.StickerSubmissionIntent
import app.solochat.core.model.UserUtterance
import app.solochat.data.db.MediaAssetEntity
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.test.IsolatedMediaContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 隔离 Room 验证收藏去重、人物隔离、手动锁冲突与移出后重收。 */
@RunWith(AndroidJUnit4::class)
class RoomStickerRepositoryTest {
    @Test fun removingQueuedStickerKeepsLeadingTextAndAReplayTombstone() = runBlocking {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "sticker-removal")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomStore(database)
            store.upsertPersona(Persona("persona", "TEST-P4"))
            store.upsertConversation(Conversation("conversation", title = "TEST-P4", personaId = "persona", createdAt = 1, updatedAt = 1))
            database.media().insertAsset(MediaAssetEntity("asset", "a".repeat(64), "image/png", 12, 2, 2, 0,
                "objects/${"a".repeat(64)}", 1))
            val sticker = store.addUserSticker("user-sticker-one", "asset", 2)
            val intent = StickerSubmissionIntent(sticker.id, "persona")
            val submission = InputSubmission("sticker-op", "conversation", emptyList(), listOf("asset"), 3,
                stickerIntent = intent)
            val leading = Message(conversationId = "conversation", role = MessageRole.USER,
                text = UserUtterance.encode(EventKind.SAY, "先写的文字"), turnId = 2, createdAt = 2, sentToModel = false)
            val stickerMessage = Message(conversationId = "conversation", role = MessageRole.USER,
                text = "", turnId = 3, createdAt = 3, sentToModel = false)
            val appended = store.appendStickerSubmission(submission, listOf(leading), stickerMessage, intent)

            assertTrue(store.removeUnpushedStickerMessage(appended.messageId))
            assertEquals(listOf("先写的文字"), store.unpushedUserMessages("conversation").map { UserUtterance.decode(it.text).second })
            assertTrue(store.messageStickers(listOf(appended.messageId)).isEmpty())
            assertEquals(1, database.media().allSubmissions().size)
            assertEquals("REMOVED", store.p4TestCommand("sticker-op")?.status)
            val replay = store.appendStickerSubmission(submission, emptyList(), stickerMessage, intent)
            assertFalse(replay.inserted)
            assertEquals(listOf("先写的文字"), store.unpushedUserMessages("conversation").map { UserUtterance.decode(it.text).second })
        } finally {
            database.close()
            context.clean()
        }
    }

    @Test fun durableP4CommandBindsRequestIdToItsOriginalBatch() = runBlocking {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "p4-command-receipt")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomStore(database)
            val command = ClientCommandEntity("p4test-push", "conversation", "c".repeat(64), "CLAIMED", "[11,12]", 5)
            assertTrue(store.claimP4TestCommand(command))
            assertFalse(store.claimP4TestCommand(command.copy(resultJson = "[13]")))
            assertTrue(runCatching { store.claimP4TestCommand(command.copy(payloadChecksum = "d".repeat(64))) }.isFailure)
            store.finishP4TestCommand(command.id, accepted = true, resultJson = "[11,12]")
            assertEquals("DONE", store.p4TestCommand(command.id)?.status)
            assertEquals("[11,12]", store.p4TestCommand(command.id)?.resultJson)
        } finally {
            database.close()
            context.clean()
        }
    }

    @Test fun userReuseAndPersonaFieldEditsKeepTheirTransactionalIdentity() = runBlocking {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "sticker-repository")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomStore(database)
            store.upsertPersona(Persona("persona-a", "TEST-P4-A"))
            store.upsertPersona(Persona("persona-b", "TEST-P4-B"))
            database.media().insertAsset(MediaAssetEntity("asset", "a".repeat(64), "image/png", 12, 2, 2, 0,
                "objects/${"a".repeat(64)}", 1))

            val user = store.addUserSticker("user-sticker-one", "asset", 1)
            assertEquals(user, store.addUserSticker("user-sticker-duplicate", "asset", 2))
            assertEquals(1, store.userStickers().size)

            val first = store.addPersonaSticker("persona-sticker-a", "persona-a", "asset", 3)
            val otherPersona = store.addPersonaSticker("persona-sticker-b", "persona-b", "asset", 3)
            assertEquals("双库可各自引用共享原件", user.mediaId, first.mediaId)
            assertNotEquals(first.id, otherPersona.id)
            assertEquals(listOf("persona-a"), store.personaStickers("persona-a").map { it.personaId })
            assertEquals(listOf("persona-b"), store.personaStickers("persona-b").map { it.personaId })
            val changed = first.copy(name = "手动名称", description = "描述", tags = listOf("标签"),
                usageContexts = listOf("场景"), updatedAt = 4)
            store.editPersonaSticker(changed, setOf("name", "description", "tags", "usageContexts"))
            val saved = store.personaStickers("persona-a").single()
            assertEquals("手动名称", saved.name)
            assertTrue(saved.nameLocked && saved.descriptionLocked && saved.tagsLocked && saved.usageContextsLocked)
            assertEquals(1, saved.nameVersion)
            assertTrue(runCatching { store.editPersonaSticker(first.copy(name = "陈旧覆盖"), setOf("name")) }.isFailure)

            store.unlockPersonaStickerField(saved.id, "name")
            assertFalse(store.personaStickers("persona-a").single().nameLocked)
            store.removePersonaSticker(saved.id, 5)
            val reactivated = store.addPersonaSticker("persona-sticker-new-id", "persona-a", "asset", 6)
            assertEquals(saved.id, reactivated.id)
            assertEquals(1L, reactivated.collectionEpoch)

            store.removeUserSticker(user.id)
            assertTrue(store.userStickers().isEmpty())
            assertTrue(store.mediaAsset("asset") != null)
            assertEquals(1, store.personaStickers("persona-b").size)
        } finally {
            database.close()
            context.clean()
        }
    }

    @Test fun stickerAppendFailureRollsBackMessageAttachmentAndSubmissionTogether() = runBlocking {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "sticker-rollback")
        val database = Room.inMemoryDatabaseBuilder(context, SoloChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomStore(database)
            store.upsertPersona(Persona("persona", "TEST-P4-rollback"))
            store.upsertConversation(Conversation("conversation", title = "TEST-P4-rollback", personaId = "persona"))
            database.media().insertAsset(MediaAssetEntity("asset", "d".repeat(64), "image/png", 12, 2, 2, 0,
                "objects/${"d".repeat(64)}", 1))
            val sticker = store.addUserSticker("user-sticker-rollback", "asset", 2)
            val intent = StickerSubmissionIntent(sticker.id, "persona")
            val submission = InputSubmission("sticker-rollback-op", "conversation", emptyList(), listOf("asset"), 3,
                stickerIntent = intent)
            val message = Message(conversationId = "conversation", role = MessageRole.USER, text = "",
                turnId = 3, createdAt = 3, sentToModel = false)
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_p4_message_sticker BEFORE INSERT ON message_sticker
                BEGIN SELECT RAISE(ABORT, 'synthetic sticker insert failure'); END
            """.trimIndent())

            assertTrue(runCatching { store.appendStickerSubmission(submission, emptyList(), message, intent) }.isFailure)
            assertTrue(store.recentMessages("conversation", -1).isEmpty())
            assertTrue(database.media().allAttachments().isEmpty())
            assertTrue(database.media().allSubmissions().isEmpty())
            assertTrue(database.stickers().allMessageStickers().isEmpty())
            assertEquals("回滚只覆盖失败事务，原件与收藏仍保留", 1, store.userStickers().size)
            assertTrue(store.mediaAsset("asset") != null)
        } finally {
            database.close()
            context.clean()
        }
    }
}
