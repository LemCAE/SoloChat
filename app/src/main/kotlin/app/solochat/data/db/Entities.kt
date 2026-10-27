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
    val proactiveCooldownUntil: Long,
    val modelOverride: String? = null,
    val activeContextRevisionId: String? = null,
    val compressionMethod: String = "ORIGINAL"
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

/** 逐调用来源、价格、请求快照保存在版本化 JSON，索引字段独立存储。 */
@Entity(tableName = "call_record", indices = [Index("startedAt"), Index("conversationId"), Index(value = ["usageId"], unique = true)])
data class CallRecordEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val conversationId: String,
    val model: String,
    val kind: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val usageId: Long?,
    val detailJson: String
)

/** 阶段 3 的版本基础；仅会话指针决定启用，迁移不产生或启用摘要。 */
@Entity(tableName = "context_revision", indices = [Index("conversationId")])
data class ContextRevisionEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val personaId: String,
    val parentRevisionId: String?,
    val method: String,
    val sourceSnapshotJson: String,
    val state: String,
    val createdAt: Long,
    val manuallyEdited: Boolean
)

@Entity(tableName = "context_segment", indices = [Index(value = ["revisionId", "ordinal"], unique = true)])
data class ContextSegmentEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val ordinal: Int,
    val fromMessageId: Long,
    val toMessageId: Long,
    val sourceChecksum: String,
    val text: String,
    val model: String
)

/** 网页阶段的幂等命令基础；阶段 2 没有业务入口。 */
@Entity(tableName = "client_command", indices = [Index("conversationId")])
data class ClientCommandEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val payloadChecksum: String,
    val status: String,
    val resultJson: String,
    val createdAt: Long
)

/** 记录恢复来源，重复选择同一备份时可在确认前提示。 */
@Entity(tableName = "backup_import", indices = [Index("sourceBackupId")])
data class BackupImportEntity(@PrimaryKey val id: String, val sourceBackupId: String,
    val sourceChecksum: String, val sourceCreatedAt: Long, val importedAt: Long)

/** 副本账目与暂停状态和业务行一起提交；暂停只用于 conversation 类型。 */
@Entity(tableName = "backup_origin", primaryKeys = ["kind", "recordId"], indices = [Index("importId")])
data class BackupOriginEntity(val kind: String, val recordId: String, val importId: String, val paused: Boolean)

/** 新模型选择与旧 modelOverride 分离；NEEDS_CONFIRMATION 不等于继承。 */
@Entity(tableName = "conversation_model_binding")
data class ConversationModelBindingEntity(
    @PrimaryKey val conversationId: String,
    val selectionState: String,
    val providerId: String?,
    val modelId: String?,
    val pendingReason: String?,
    val migrationSource: String,
    val upgradePlanId: String?
)

/** call_record 一对一的非秘密调用配置快照；历史调用没有该行即表示未记录。 */
@Entity(tableName = "call_config_snapshot")
data class CallConfigSnapshotEntity(
    @PrimaryKey val callId: String,
    val version: Int,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelDisplayName: String,
    val requestModelId: String,
    val use: String,
    val selectionSource: String,
    val thinkingMode: String,
    val thinkingEffort: String?,
    val thinkingSource: String,
    /** 仅保存非秘密思考控制参数的 JSON，不含请求正文、Key 或连接对象。 */
    val actualThinkingParametersJson: String
)

/** P3-A 媒体原件目录；fileKey 仅为 app 私有媒体目录内的相对键。 */
@Entity(tableName = "media_asset", indices = [Index(value = ["sha256", "byteSize"], unique = true)])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val sha256: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val fileKey: String,
    val createdAt: Long
)

@Entity(tableName = "message_attachment", primaryKeys = ["messageId", "ordinal"], indices = [Index("mediaId")])
data class MessageAttachmentEntity(val messageId: Long, val ordinal: Int, val mediaId: String)

@Entity(tableName = "input_submission", indices = [Index("conversationId")])
data class InputSubmissionEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val payloadChecksum: String,
    val messageIdsJson: String,
    val createdAt: Long
)

@Entity(tableName = "media_draft")
data class MediaDraftEntity(@PrimaryKey val conversationId: String, val draftJson: String, val updatedAt: Long)

@Entity(tableName = "call_media_input")
data class CallMediaInputEntity(@PrimaryKey val callId: String, val inputJson: String, val recordedAt: Long)

/** 全局用户收藏关系；同一媒体只能有一条有效关系。 */
@Entity(tableName = "user_sticker", indices = [Index(value = ["mediaId"], unique = true), Index("sortOrder")])
data class UserStickerEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val createdAt: Long,
    val sortOrder: Long
)

/** 人物私有收藏与墓碑。列表以 JSON 保存，保持 core 纯 JVM 且不引入 converter。 */
@Entity(tableName = "persona_sticker", indices = [
    Index(value = ["personaId", "sha256", "byteSize"], unique = true),
    Index(value = ["personaId", "status"]), Index("mediaId")
])
data class PersonaStickerEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val sha256: String,
    val byteSize: Long,
    val mediaId: String?,
    val status: String,
    val collectionEpoch: Long,
    val name: String,
    val description: String,
    val tagsJson: String,
    val usageContextsJson: String,
    val nameLocked: Boolean,
    val descriptionLocked: Boolean,
    val tagsLocked: Boolean,
    val usageContextsLocked: Boolean,
    val nameVersion: Long,
    val descriptionVersion: Long,
    val tagsVersion: Long,
    val usageContextsVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val useCount: Long,
    val lastUsedAt: Long?
)

/** 表情用途只由此显式关系判断；普通历史图片不会被反推成表情。 */
@Entity(tableName = "message_sticker", primaryKeys = ["messageId", "ordinal"], indices = [Index("personaId"), Index("submissionId")])
data class MessageStickerEntity(
    val messageId: Long,
    val ordinal: Int,
    val personaId: String,
    val sha256: String,
    val byteSize: Long,
    val collectionEpoch: Long,
    val sourceUserStickerId: String?,
    val submissionId: String
)

/** AI 表情事件的不可变发送时快照；播放/出口逻辑由后续阶段接通。 */
@Entity(tableName = "event_sticker", indices = [Index("personaId"), Index("assistantMessageId", unique = true)])
data class EventStickerEntity(
    @PrimaryKey val eventId: Long,
    val stickerId: String,
    val personaId: String,
    val collectionEpoch: Long,
    val mediaId: String,
    val sha256: String,
    val byteSize: Long,
    val nameSnapshot: String,
    val descriptionSnapshot: String,
    val assistantMessageId: Long?
)

/** 同一完整模型响应只允许一次人物收藏副作用提交。 */
@Entity(tableName = "sticker_turn_result")
data class StickerTurnResultEntity(
    @PrimaryKey val callId: String,
    val responseChecksum: String,
    val resultJson: String,
    val committedAt: Long
)

/** 类型化媒体来源供人物收藏图接入使用，不伪造 messageId。 */
@Entity(tableName = "typed_call_media_input")
data class TypedCallMediaInputEntity(@PrimaryKey val callId: String, val sourceJson: String, val recordedAt: Long)

/** 本轮 P4 合成测试对象的授权边界；API 不凭名称或调用方自称判断测试范围。 */
@Entity(tableName = "p4_test_subject", indices = [Index(value = ["personaId"], unique = true), Index(value = ["conversationId"], unique = true)])
data class P4TestSubjectEntity(
    @PrimaryKey val requestId: String,
    val personaId: String,
    val conversationId: String,
    val userStickerId: String,
    val mediaId: String,
    val createdAt: Long
)
