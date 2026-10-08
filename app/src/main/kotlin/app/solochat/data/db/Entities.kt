package app.solochat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 表结构。
 *
 * 几条刻意的设计：
 *
 * 1. **没有用 TypeConverter 处理枚举**，而是统一存字符串（`ChatMode.wire` / `name`）。
 *    原因是：跨版本读旧数据时，未知的枚举值会让 converter 抛异常（整个库打不开），
 *    而字符串 + 解析时兜底只影响单条记录。对一个"数据不能丢"的聊天应用，这个差异很重要。
 *
 * 2. **事件（Event）与消息（Message）分两张表**。事件是"屏幕上发生的事"（含未播出的），
 *    消息是"进模型上下文的话"。两者生命周期不同（事件会被取消，消息不会），
 *    混在一张表里迟早要靠一个 isVisible 之类的位来救火。
 *
 * 3. 索引只建真正会被查的：会话内的 id 升序、事件按 delivery 过滤。
 */

@Entity(
    tableName = "conversation",
    indices = [Index("updatedAt")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** "chat" | "scene" */
    val mode: String,
    val modeSince: Long,
    val modeSinceEventId: Long,
    val personaId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val proactiveCooldownUntil: Long
)

@Entity(
    tableName = "event",
    indices = [
        Index(value = ["conversationId", "id"]),
        Index(value = ["conversationId", "delivery"])
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** say / act / scene / wait / read */
    val kind: String,
    val speaker: String,
    val text: String,
    val turnId: Long,
    /** "chat" | "scene" */
    val mode: String,
    val gapHintMs: Int?,
    val typingHintMs: Int?,
    val waitReason: String,
    /** PENDING / PLAYING / DELIVERED / CANCELED / INTERRUPTED */
    val delivery: String,
    val gapMs: Int,
    val typingMs: Int,
    val createdAt: Long,
    val deliveredAt: Long?
)

@Entity(
    tableName = "message",
    indices = [Index(value = ["conversationId", "id"]), Index(value = ["conversationId", "createdAt"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** user / assistant / system */
    val role: String,
    val text: String,
    val turnId: Long,
    val createdAt: Long,
    val interruptedAt: String?,
    val sentToModel: Boolean
)

@Entity(tableName = "persona")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tagline: String,
    val identity: String,
    val voice: String,

    /**
     * 面对面专用的四段素材/笔法。
     *
     * ⚠️ `sceneVoice` / `mannerisms` / `surroundings` 是 **v3 → v4 迁移**加的，
     * `depiction` 是 **v4 → v5** 加的（见 `Migrations.kt`）。
     * 老卡上它们是空串，语义是"这个人没写这一段" —— 提示词里对应的那一段**整个不出现**，
     * 而不是留一个空标题（留空标题会让模型自己编一段补上，那比没有更糟）。
     */
    val sceneVoice: String,
    val mannerisms: String,
    val surroundings: String,

    /** 怎么描写动作与景物：这个人被描写出来是什么质感。 */
    val depiction: String,

    val boundaries: String,
    val secretNotes: String,
    val avatar: String,
    val builtin: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    /** 换行分隔的标签，避免再开一张关联表 */
    val tags: String,

    /**
     * 属于哪张人物卡。**空串 = 通用**（哪张人设都能看到）。
     *
     * ⚠️ 这一列是 v1 → v2 迁移加的（见 `SoloChatDatabase.MIGRATION_1_2`）。
     * 刻意**不写** `@ColumnInfo(defaultValue = ...)`：Room 的 schema 校验只在
     * "实体声明了默认值"时才比对默认值，而迁移里的 `ALTER TABLE ... DEFAULT ''`
     * 只是为了老行能填上 —— 声明成实体默认值反而会让校验更严、更容易踩坑。
     */
    val personaId: String = "",
    val createdAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    val sourceConversationId: String,
    val pinned: Boolean
)

@Entity(tableName = "scene_summary", indices = [Index("conversationId")])
data class SummaryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val fromEventId: Long,
    val toEventId: Long,
    val text: String,
    val model: String,
    val createdAt: Long
)

/**
 * 用量记账。**一行一次调用**，不做每日聚合表 ——
 * 聚合在 SQL 里现算就行（私人用量级下几条查询毫无压力），
 * 而多一张聚合表就多一处"聚合和明细对不上"的可能。
 */
@Entity(tableName = "usage", indices = [Index("at"), Index("model")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val reasoningTokens: Int,
    val imageCount: Int,
    /** true = 本地估的 token，UI 要标"估算" */
    @ColumnInfo(name = "estimated") val estimated: Boolean,
    val at: Long
)
