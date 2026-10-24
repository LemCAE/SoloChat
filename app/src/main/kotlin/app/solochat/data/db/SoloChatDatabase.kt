package app.solochat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 数据库。
 *
 * 版本策略：**只增不改**。每次加字段/表就升版本并写迁移，不要 recreate ——
 * 这个库里是唯一的聊天记录，清库等于数据丢失。schema 导出到 `app/schemas/`，
 * 迁移测试可以拿历史 schema 直接跑。**迁移本身写在 `Migrations.kt`**，
 * 那里也写了为什么这个项目验不了迁移（没有 instrumentation 测试）。
 *
 * 刻意**没有**用 `fallbackToDestructiveMigration()`：那等于把"用户数据丢光"
 * 变成一个静默的默认行为，绝对不能接受。
 */
@Database(
    entities = [
        ConversationEntity::class,
        EventEntity::class,
        MessageEntity::class,
        PersonaEntity::class,
        MemoryEntity::class,
        SummaryEntity::class,
        UsageEntity::class,
        CallRecordEntity::class,
        ContextRevisionEntity::class,
        ContextSegmentEntity::class,
        ClientCommandEntity::class,
        BackupImportEntity::class,
        BackupOriginEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class SoloChatDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun events(): EventDao
    abstract fun messages(): MessageDao
    abstract fun personas(): PersonaDao
    abstract fun memories(): MemoryDao
    abstract fun summaries(): SummaryDao
    abstract fun usage(): UsageDao
    abstract fun calls(): CallRecordDao
    abstract fun contexts(): ContextRevisionDao
    abstract fun backup(): BackupDao
}
