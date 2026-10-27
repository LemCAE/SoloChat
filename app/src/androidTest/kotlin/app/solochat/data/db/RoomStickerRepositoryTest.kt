package app.solochat.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.model.Persona
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
            assertNotEquals(first.id, otherPersona.id)
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
}
