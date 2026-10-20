package app.solochat.data.db

/** v5→v6 只追加；SQL 独立列出，离线验证与实际迁移使用同一份。已交付后不修改。 */
val SCHEMA_V6_SQL = listOf(
    "ALTER TABLE conversation ADD COLUMN modelOverride TEXT",
    "ALTER TABLE conversation ADD COLUMN activeContextRevisionId TEXT",
    "ALTER TABLE conversation ADD COLUMN compressionMethod TEXT NOT NULL DEFAULT 'ORIGINAL'",
    "CREATE TABLE IF NOT EXISTS call_record (id TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, conversationId TEXT NOT NULL, model TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, usageId INTEGER, detailJson TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_call_record_startedAt ON call_record (startedAt)",
    "CREATE INDEX IF NOT EXISTS index_call_record_conversationId ON call_record (conversationId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_call_record_usageId ON call_record (usageId)",
    "CREATE TABLE IF NOT EXISTS context_revision (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, personaId TEXT NOT NULL, parentRevisionId TEXT, method TEXT NOT NULL, sourceSnapshotJson TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, manuallyEdited INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_context_revision_conversationId ON context_revision (conversationId)",
    "CREATE TABLE IF NOT EXISTS context_segment (id TEXT NOT NULL PRIMARY KEY, revisionId TEXT NOT NULL, ordinal INTEGER NOT NULL, fromMessageId INTEGER NOT NULL, toMessageId INTEGER NOT NULL, sourceChecksum TEXT NOT NULL, text TEXT NOT NULL, model TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_context_segment_revisionId_ordinal ON context_segment (revisionId, ordinal)",
    "CREATE TABLE IF NOT EXISTS client_command (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, payloadChecksum TEXT NOT NULL, status TEXT NOT NULL, resultJson TEXT NOT NULL, createdAt INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_client_command_conversationId ON client_command (conversationId)"
)
