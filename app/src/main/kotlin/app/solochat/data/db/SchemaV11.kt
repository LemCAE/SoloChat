package app.solochat.data.db

/** v10→v11 只追加收藏、消息/事件语义及人物响应幂等表，不改写旧行。 */
internal val SCHEMA_V11_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `user_sticker` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_sticker_mediaId` ON `user_sticker` (`mediaId`)",
    "CREATE INDEX IF NOT EXISTS `index_user_sticker_sortOrder` ON `user_sticker` (`sortOrder`)",
    "CREATE TABLE IF NOT EXISTS `persona_sticker` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sha256` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `mediaId` TEXT, `status` TEXT NOT NULL, `collectionEpoch` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, `usageContextsJson` TEXT NOT NULL, `nameLocked` INTEGER NOT NULL, `descriptionLocked` INTEGER NOT NULL, `tagsLocked` INTEGER NOT NULL, `usageContextsLocked` INTEGER NOT NULL, `nameVersion` INTEGER NOT NULL, `descriptionVersion` INTEGER NOT NULL, `tagsVersion` INTEGER NOT NULL, `usageContextsVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, `lastUsedAt` INTEGER, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_persona_sticker_personaId_sha256_byteSize` ON `persona_sticker` (`personaId`, `sha256`, `byteSize`)",
    "CREATE INDEX IF NOT EXISTS `index_persona_sticker_personaId_status` ON `persona_sticker` (`personaId`, `status`)",
    "CREATE INDEX IF NOT EXISTS `index_persona_sticker_mediaId` ON `persona_sticker` (`mediaId`)",
    "CREATE TABLE IF NOT EXISTS `message_sticker` (`messageId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `personaId` TEXT NOT NULL, `sha256` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `collectionEpoch` INTEGER NOT NULL, `sourceUserStickerId` TEXT, `submissionId` TEXT NOT NULL, PRIMARY KEY(`messageId`, `ordinal`))",
    "CREATE INDEX IF NOT EXISTS `index_message_sticker_personaId` ON `message_sticker` (`personaId`)",
    "CREATE INDEX IF NOT EXISTS `index_message_sticker_submissionId` ON `message_sticker` (`submissionId`)",
    "CREATE TABLE IF NOT EXISTS `event_sticker` (`eventId` INTEGER NOT NULL, `stickerId` TEXT NOT NULL, `personaId` TEXT NOT NULL, `collectionEpoch` INTEGER NOT NULL, `mediaId` TEXT NOT NULL, `sha256` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `nameSnapshot` TEXT NOT NULL, `descriptionSnapshot` TEXT NOT NULL, `assistantMessageId` INTEGER, PRIMARY KEY(`eventId`))",
    "CREATE INDEX IF NOT EXISTS `index_event_sticker_personaId` ON `event_sticker` (`personaId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_sticker_assistantMessageId` ON `event_sticker` (`assistantMessageId`)",
    "CREATE TABLE IF NOT EXISTS `sticker_turn_result` (`callId` TEXT NOT NULL, `responseChecksum` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `committedAt` INTEGER NOT NULL, PRIMARY KEY(`callId`))",
    "CREATE TABLE IF NOT EXISTS `typed_call_media_input` (`callId` TEXT NOT NULL, `sourceJson` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, PRIMARY KEY(`callId`))",
    "CREATE TABLE IF NOT EXISTS `p4_test_subject` (`requestId` TEXT NOT NULL, `personaId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `userStickerId` TEXT NOT NULL, `mediaId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`requestId`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_p4_test_subject_personaId` ON `p4_test_subject` (`personaId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_p4_test_subject_conversationId` ON `p4_test_subject` (`conversationId`)"
)
