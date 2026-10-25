package app.solochat.data.db

/** v8→v9 只追加新配置表，不触碰任何旧表或历史行。 */
internal val SCHEMA_V9_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `conversation_model_binding` (`conversationId` TEXT NOT NULL, `selectionState` TEXT NOT NULL, `providerId` TEXT, `modelId` TEXT, `pendingReason` TEXT, `migrationSource` TEXT NOT NULL, `upgradePlanId` TEXT, PRIMARY KEY(`conversationId`))",
    "CREATE TABLE IF NOT EXISTS `call_config_snapshot` (`callId` TEXT NOT NULL, `version` INTEGER NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `modelId` TEXT NOT NULL, `modelDisplayName` TEXT NOT NULL, `requestModelId` TEXT NOT NULL, `use` TEXT NOT NULL, `selectionSource` TEXT NOT NULL, `thinkingMode` TEXT NOT NULL, `thinkingEffort` TEXT, `thinkingSource` TEXT NOT NULL, `actualThinkingParametersJson` TEXT NOT NULL, PRIMARY KEY(`callId`))"
)
