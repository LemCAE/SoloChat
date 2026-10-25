package app.solochat.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solochat.core.model.CallConfigSnapshot
import app.solochat.core.model.CallKind
import app.solochat.core.model.CallStatus
import app.solochat.core.model.ModelPrice
import app.solochat.core.model.ModelThinking
import app.solochat.core.model.ModelUse
import app.solochat.core.model.SelectionSource
import app.solochat.core.model.ThinkingMode
import app.solochat.core.model.ThinkingSource
import app.solochat.core.model.ChatMode
import app.solochat.core.backup.BackupCall
import app.solochat.core.backup.BackupCallConfigSnapshot
import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupConversationModelBinding
import app.solochat.core.backup.BackupData
import app.solochat.data.backup.RoomBackupImporter
import app.solochat.data.backup.RoomBackupReader
import app.solochat.core.model.Conversation
import app.solochat.core.model.CallRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 在设备上的 Room runtime 打开 v8 schema 副本；不连接应用正式数据库。 */
@RunWith(AndroidJUnit4::class)
class RoomV8ToV9MigrationTest {
    @Test fun roomBackupRestoreSnapshotRepairAndPauseAreIsolatedAndAtomic() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "backup-v2-restore-isolated.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, SoloChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val source = BackupData(
            conversations = listOf(Conversation("source-conversation", title = "隔离备份", personaId = "removed-persona")),
            calls = listOf(BackupCall(CallRecord("source-call", "source-op", "source-conversation", "request-model",
                CallKind.CHAT, CallStatus.SUCCESS, 20L, price = ModelPrice(1.0, 2.0), inputCharacters = 8))),
            conversationModelBindings = listOf(BackupConversationModelBinding("source-conversation", "EXPLICIT",
                "source-provider", "source-model", null, "USER")),
            callConfigSnapshots = listOf(BackupCallConfigSnapshot("source-call", 1, "source-provider", "旧接口",
                "source-model", "旧模型", "request-model", "CHAT", "GLOBAL_DEFAULT", "ON", "HIGH",
                "MODEL_PREFERENCE", "{\"reasoning_effort\":\"high\"}"))
        )
        val output = ByteArrayOutputStream()
        BackupCodec.write(source, output, 21L, id = "isolated-backup")
        val archive = BackupCodec.read(ByteArrayInputStream(output.toByteArray()))
        try {
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_import_snapshot BEFORE INSERT ON call_config_snapshot
                BEGIN SELECT RAISE(ABORT, 'injected import snapshot failure'); END
            """.trimIndent())
            assertTrue(runCatching { RoomBackupImporter(database).restore(archive, 30L) }.isFailure)
            assertTrue(database.conversations().all().isEmpty())
            assertTrue(database.backup().callConfigSnapshots().isEmpty())

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_import_snapshot")
            val plan = RoomBackupImporter(database).restore(archive, 31L)
            val conversationId = plan.conversationIds.getValue("source-conversation")
            val importedConversation = checkNotNull(database.conversations().byId(conversationId))
            assertTrue(importedConversation.personaId.startsWith("backup:${plan.importId}:missing:"))
            assertTrue(database.backup().paused(conversationId))
            val pendingModel = checkNotNull(database.modelConfigs().conversationBinding(conversationId))
            assertEquals("NEEDS_CONFIRMATION", pendingModel.selectionState)
            val importedCall = plan.data.calls.single().record
            val callSnapshot = checkNotNull(database.modelConfigs().callConfigSnapshot(importedCall.id))
            assertEquals("旧接口", callSnapshot.providerName)
            assertEquals("{\"reasoning_effort\":\"high\"}", callSnapshot.actualThinkingParametersJson)

            val roundTrip = RoomBackupReader(database).read()
            val restoredArchiveBytes = ByteArrayOutputStream()
            BackupCodec.write(roundTrip, restoredArchiveBytes, 32L)
            val readBack = BackupCodec.read(ByteArrayInputStream(restoredArchiveBytes.toByteArray()))
            assertEquals("旧接口", readBack.data.callConfigSnapshots.single().providerName)
            assertEquals("request-model", readBack.data.calls.single().record.model)

            database.personas().upsert(PersonaEntity(id = "local-target", name = "本机人物", tagline = "", identity = "",
                voice = "", sceneVoice = "", mannerisms = "", surroundings = "", depiction = "", boundaries = "",
                secretNotes = "", avatar = "", builtin = false, createdAt = 40L, updatedAt = 40L))
            val store = RoomStore(database)
            store.repairPausedBackupPersona(conversationId, "local-target", 41L)
            database.modelConfigs().saveUserConversationBinding(pendingModel.copy(
                selectionState = "EXPLICIT", providerId = "local-provider", modelId = "local-model",
                pendingReason = null, migrationSource = "USER", upgradePlanId = null))
            assertTrue(database.backup().paused(conversationId))
            database.backup().resume(conversationId)
            assertEquals(false, database.backup().paused(conversationId))
            assertEquals("local-target", database.conversations().byId(conversationId)?.personaId)
            assertEquals("EXPLICIT", database.modelConfigs().conversationBinding(conversationId)?.selectionState)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun roomOnlyAllowsPersonaRepairForPausedBackupWithMissingPersona() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "paused-persona-repair-isolated.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, SoloChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val store = RoomStore(database)
        try {
            val now = 10L
            fun persona(id: String, name: String) = PersonaEntity(id = id, name = name, tagline = "", identity = "",
                voice = "", sceneVoice = "", mannerisms = "", surroundings = "", depiction = "", boundaries = "",
                secretNotes = "", avatar = "", builtin = false, createdAt = now, updatedAt = now)
            database.personas().upsert(persona("source", "原人物"))
            database.personas().upsert(persona("target", "目标人物"))
            database.personas().upsert(persona("another-target", "另一个目标人物"))
            database.conversations().upsert(ConversationEntity("ordinary", "普通会话", ChatMode.CHAT.wire,
                now, 0, "source", now, now, false, false, 0))
            assertTrue(runCatching { store.repairPausedBackupPersona("ordinary", "target", 11L) }.isFailure)
            assertEquals("source", database.conversations().byId("ordinary")?.personaId)

            database.conversations().upsert(ConversationEntity("copy", "恢复副本", ChatMode.CHAT.wire,
                now, 0, "missing-persona", now, now, false, false, 0))
            database.backup().addOrigins(listOf(BackupOriginEntity("conversation", "copy", "isolated-import", true)))
            store.repairPausedBackupPersona("copy", "target", 12L)
            assertEquals("target", database.conversations().byId("copy")?.personaId)
            assertTrue(database.backup().paused("copy"))

            database.conversations().upsert(ConversationEntity("valid-copy", "有效归属副本", ChatMode.CHAT.wire,
                now, 0, "target", now, now, false, false, 0))
            database.backup().addOrigins(listOf(BackupOriginEntity("conversation", "valid-copy", "isolated-import", true)))
            assertTrue(runCatching { store.repairPausedBackupPersona("valid-copy", "another-target", 13L) }.isFailure)
            assertTrue(database.backup().paused("valid-copy"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun roomOpensV8AndRetainsSeededLegacyRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v8-v9-isolated-test.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()
        context.deleteDatabase(name)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schemaText = instrumentation.context.assets.open("solo-chat-v8-schema.json").bufferedReader().use { it.readText() }
        val entities = JSONObject(schemaText).getJSONObject("database").getJSONArray("entities")
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        val preserved = linkedMapOf<String, List<List<String?>>>()
        try {
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (item in 0 until indices.length()) {
                    val sql = indices.getJSONObject(item).getString("createSql")
                    legacy.execSQL(sql.replace("\${TABLE_NAME}", table))
                }
            }
            seed(legacy)
            entities.let { schema -> (0 until schema.length()).map { schema.getJSONObject(it).getString("tableName") } }.forEach {
                preserved[it] = rows(legacy, it)
            }
            legacy.version = 8
        } finally {
            legacy.close()
        }

        val migrated = Room.databaseBuilder(context, SoloChatDatabase::class.java, name)
            .addMigrations(MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals("ok", db.query("PRAGMA integrity_check").use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
            preserved.forEach { (table, expected) -> assertEquals("$table rows changed", expected, rows(db, table)) }
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='conversation_model_binding'")
                .use { it.moveToFirst() })
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='call_config_snapshot'")
                .use { it.moveToFirst() })
            assertEquals(9, db.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun roomCallAndConfigurationSnapshotCommitOrRollbackTogether() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "call-snapshot-atomic-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, SoloChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val store = RoomStore(database)
        val call = CallRecord("atomic-call", "atomic-op", "isolated", "model-request", CallKind.CHAT,
            CallStatus.STARTED, 20L, price = ModelPrice(), inputCharacters = 0)
        val snapshot = CallConfigSnapshot(1, "provider-id", "测试提供商", "model-id", "测试模型", "model-request",
            ModelUse.CHAT, SelectionSource.GLOBAL_DEFAULT, ModelThinking(ThinkingMode.AUTO), ThinkingSource.MODEL_PREFERENCE)
        try {
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_call_snapshot BEFORE INSERT ON call_config_snapshot
                BEGIN SELECT RAISE(ABORT, 'injected snapshot failure'); END
            """.trimIndent())
            assertTrue(runCatching { store.recordCallStartedWithConfig(call, snapshot) }.isFailure)
            assertEquals(null, database.calls().byId(call.id))
            assertEquals(null, database.modelConfigs().callConfigSnapshot(call.id))

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_call_snapshot")
            store.recordCallStartedWithConfig(call, snapshot)
            assertEquals("model-request", database.calls().byId(call.id)?.model)
            assertEquals("provider-id", database.modelConfigs().callConfigSnapshot(call.id)?.providerId)
            assertEquals("provider-id", store.statistics("isolated", 0, 100).callConfigSnapshots[call.id]?.providerId)
            assertTrue(runCatching {
                store.recordCallStartedWithConfig(call.copy(operationId = "conflicting-op"), snapshot)
            }.isFailure)
            assertEquals("atomic-op", database.calls().byId(call.id)?.operationId)
            assertEquals("provider-id", database.modelConfigs().callConfigSnapshot(call.id)?.providerId)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun seed(db: SQLiteDatabase) {
        db.insertOrThrow("persona", null, ContentValues().apply {
            put("id", "migration-persona"); put("name", "测试人物"); put("tagline", "tag"); put("identity", "identity")
            put("voice", "voice"); put("sceneVoice", "scene"); put("mannerisms", "manner"); put("surroundings", "place")
            put("depiction", "depiction"); put("boundaries", "boundary"); put("secretNotes", "private note")
            put("avatar", ""); put("builtin", 0); put("createdAt", 10L); put("updatedAt", 11L)
        })
        db.insertOrThrow("memory", null, ContentValues().apply {
            put("id", "migration-memory"); put("content", "保留的人物记忆"); put("tags", "tag1")
            put("personaId", "migration-persona"); put("createdAt", 9L); put("lastUsedAt", 10L)
            put("useCount", 2); put("sourceConversationId", "migration-conversation"); put("pinned", 1)
        })
        db.insertOrThrow("conversation", null, ContentValues().apply {
            put("id", "migration-conversation"); put("title", "迁移保护行"); put("mode", "chat")
            put("modeSince", 12L); put("modeSinceEventId", 0L); put("personaId", "migration-persona")
            put("createdAt", 10L); put("updatedAt", 12L); put("pinned", 0); put("archived", 0)
            put("proactiveCooldownUntil", 0L); put("modelOverride", "Legacy-Model"); put("compressionMethod", "ORIGINAL")
        })
        db.insertOrThrow("message", null, ContentValues().apply {
            put("conversationId", "migration-conversation"); put("role", "user"); put("text", "历史消息原文")
            put("turnId", 3L); put("createdAt", 12L); put("sentToModel", 1)
        })
        db.insertOrThrow("event", null, ContentValues().apply {
            put("conversationId", "migration-conversation"); put("kind", "say"); put("speaker", "对方")
            put("text", "历史回复"); put("turnId", 3L); put("mode", "chat"); put("waitReason", "")
            put("delivery", "DELIVERED"); put("gapMs", 0); put("typingMs", 0); put("createdAt", 13L)
        })
        db.insertOrThrow("scene_summary", null, ContentValues().apply {
            put("id", "migration-summary"); put("conversationId", "migration-conversation")
            put("fromEventId", 1L); put("toEventId", 1L); put("text", "旧摘要")
            put("model", "Legacy-Model"); put("createdAt", 13L)
        })
        db.insertOrThrow("context_revision", null, ContentValues().apply {
            put("id", "migration-revision"); put("conversationId", "migration-conversation")
            put("personaId", "migration-persona"); putNull("parentRevisionId")
            put("method", "SEGMENTED"); put("sourceSnapshotJson", "{\"protected\":true}")
            put("state", "COMPLETE"); put("createdAt", 13L); put("manuallyEdited", 0)
        })
        db.insertOrThrow("context_segment", null, ContentValues().apply {
            put("id", "migration-segment"); put("revisionId", "migration-revision"); put("ordinal", 0)
            put("fromMessageId", 1L); put("toMessageId", 1L); put("sourceChecksum", "checksum")
            put("text", "压缩历史"); put("model", "Legacy-Model")
        })
        db.insertOrThrow("usage", null, ContentValues().apply {
            put("conversationId", "migration-conversation"); put("model", "Legacy-Model")
            put("promptTokens", 17); put("completionTokens", 9); put("cachedTokens", 0)
            put("reasoningTokens", 0); put("imageCount", 0); put("estimated", 0); put("at", 14L)
        })
        db.insertOrThrow("call_record", null, ContentValues().apply {
            put("id", "migration-call"); put("operationId", "migration-op"); put("conversationId", "migration-conversation")
            put("model", "Legacy-Model"); put("kind", "CHAT"); put("status", "SUCCESS"); put("startedAt", 14L)
            put("detailJson", "migration-protected-call-json")
        })
        db.insertOrThrow("client_command", null, ContentValues().apply {
            put("id", "migration-command"); put("conversationId", "migration-conversation")
            put("payloadChecksum", "command-checksum"); put("status", "DONE")
            put("resultJson", "{\"ok\":true}"); put("createdAt", 14L)
        })
        db.insertOrThrow("backup_import", null, ContentValues().apply {
            put("id", "migration-import"); put("sourceBackupId", "source-backup")
            put("sourceChecksum", "backup-checksum"); put("sourceCreatedAt", 8L); put("importedAt", 9L)
        })
        db.insertOrThrow("backup_origin", null, ContentValues().apply {
            put("kind", "conversation"); put("recordId", "migration-conversation")
            put("importId", "migration-import"); put("paused", 0)
        })
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
