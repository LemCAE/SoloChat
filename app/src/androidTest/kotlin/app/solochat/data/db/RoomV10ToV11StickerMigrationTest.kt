package app.solochat.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.test.IsolatedMediaContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 以隔离合成 v10 库验证迁移只加表、不改旧业务行。 */
@RunWith(AndroidJUnit4::class)
class RoomV10ToV11StickerMigrationTest {
    @Test fun migrationAddsStickerTablesAndPreservesEveryV10Row() {
        val context = IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext, "sticker-migration")
        val name = "p4-v10-v11-isolated.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val schema = JSONObject(testContext.assets.open("solo-chat-v10-schema.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        val preserved = linkedMapOf<String, List<List<String?>>>()
        try {
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (item in 0 until indices.length()) {
                    legacy.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            legacy.insertOrThrow("persona", null, ContentValues().apply {
                put("id", "p4-test-persona"); put("name", "TEST-P4-migration"); put("tagline", "")
                put("identity", "synthetic"); put("voice", ""); put("sceneVoice", ""); put("mannerisms", "")
                put("surroundings", ""); put("depiction", ""); put("boundaries", ""); put("secretNotes", "")
                put("avatar", ""); put("builtin", 0); put("createdAt", 1L); put("updatedAt", 1L)
            })
            legacy.insertOrThrow("conversation", null, ContentValues().apply {
                put("id", "p4-test-conversation"); put("title", "TEST-P4-migration"); put("mode", "chat")
                put("modeSince", 1L); put("modeSinceEventId", 0L); put("personaId", "p4-test-persona")
                put("createdAt", 1L); put("updatedAt", 1L); put("pinned", 0); put("archived", 0)
                put("proactiveCooldownUntil", 0L); put("compressionMethod", "ORIGINAL")
            })
            legacy.insertOrThrow("message", null, ContentValues().apply {
                put("conversationId", "p4-test-conversation"); put("role", "user"); put("text", "旧消息原文")
                put("turnId", 2L); put("createdAt", 2L); put("sentToModel", 1)
            })
            val oldTables = (0 until schema.length()).map { schema.getJSONObject(it).getString("tableName") }
            oldTables.forEach { preserved[it] = rows(legacy, it) }
            legacy.version = 10
        } finally { legacy.close() }

        val migrated = Room.databaseBuilder(context, SoloChatDatabase::class.java, name)
            .addMigrations(MIGRATION_10_11).allowMainThreadQueries().build()
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals(11, db.version)
            assertEquals("ok", db.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
            preserved.forEach { (table, expected) -> assertEquals("$table rows changed", expected, rows(db, table)) }
            listOf("user_sticker", "persona_sticker", "message_sticker", "event_sticker",
                "sticker_turn_result", "typed_call_media_input", "p4_test_subject").forEach { table ->
                assertTrue(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { it.moveToFirst() })
            }
        } finally {
            migrated.close()
            context.clean()
        }
    }

    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM `$table` ORDER BY 1", null).use(::cursorRows)
    private fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM `$table` ORDER BY 1").use(::cursorRows)
    private fun cursorRows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
            if (cursor.isNull(index)) null else cursor.getString(index)
        })
    }
}
