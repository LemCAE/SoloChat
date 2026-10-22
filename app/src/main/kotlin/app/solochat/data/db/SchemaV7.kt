package app.solochat.data.db

/** v6→v7 只追加恢复来源和隔离表，不更改旧业务表。 */
val SCHEMA_V7_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS backup_import (id TEXT NOT NULL PRIMARY KEY, sourceBackupId TEXT NOT NULL, sourceChecksum TEXT NOT NULL, sourceCreatedAt INTEGER NOT NULL, importedAt INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_backup_import_sourceBackupId ON backup_import (sourceBackupId)",
    "CREATE TABLE IF NOT EXISTS backup_origin (kind TEXT NOT NULL, recordId TEXT NOT NULL, importId TEXT NOT NULL, paused INTEGER NOT NULL, PRIMARY KEY(kind, recordId))",
    "CREATE INDEX IF NOT EXISTS index_backup_origin_importId ON backup_origin (importId)"
)
