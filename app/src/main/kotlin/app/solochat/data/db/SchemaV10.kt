package app.solochat.data.db

/** v9→v10 只追加 P3 媒体、附件、提交映射、草稿与调用媒体清单表。 */
internal val SCHEMA_V10_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `media_asset` (`id` TEXT NOT NULL, `sha256` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `orientation` INTEGER NOT NULL, `fileKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_asset_sha256_byteSize` ON `media_asset` (`sha256`, `byteSize`)",
    "CREATE TABLE IF NOT EXISTS `message_attachment` (`messageId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `mediaId` TEXT NOT NULL, PRIMARY KEY(`messageId`, `ordinal`))",
    "CREATE INDEX IF NOT EXISTS `index_message_attachment_mediaId` ON `message_attachment` (`mediaId`)",
    "CREATE TABLE IF NOT EXISTS `input_submission` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `payloadChecksum` TEXT NOT NULL, `messageIdsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_input_submission_conversationId` ON `input_submission` (`conversationId`)",
    "CREATE TABLE IF NOT EXISTS `media_draft` (`conversationId` TEXT NOT NULL, `draftJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))",
    "CREATE TABLE IF NOT EXISTS `call_media_input` (`callId` TEXT NOT NULL, `inputJson` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, PRIMARY KEY(`callId`))"
)
