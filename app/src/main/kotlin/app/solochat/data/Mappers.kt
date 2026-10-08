package app.solochat.data

import app.solochat.core.model.ChatMode
import app.solochat.core.model.Conversation
import app.solochat.core.model.DeliveryState
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Memory
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.Persona
import app.solochat.core.model.SceneSummary
import app.solochat.core.model.Usage
import app.solochat.data.db.ConversationEntity
import app.solochat.data.db.EventEntity
import app.solochat.data.db.MemoryEntity
import app.solochat.data.db.MessageEntity
import app.solochat.data.db.PersonaEntity
import app.solochat.data.db.SummaryEntity
import app.solochat.data.db.UsageEntity

/**
 * 表 ↔ 领域模型 的翻译层。
 *
 * 单独放一个文件而不是散在 Store 里，是因为**枚举解析的兜底逻辑必须集中**：
 * 数据库里出现未知的 mode / kind / delivery 时（降级安装、手工改库、跨版本回滚），
 * 不能让一整个列表崩掉。这里的每个 fromWire 都有 default，而且 default 的选择要说得出理由。
 */

private fun ChatMode.toWire(): String = wire

private fun modeFrom(raw: String): ChatMode = when (raw) {
    "scene" -> ChatMode.SCENE
    else -> ChatMode.CHAT          // 未知一律当聊天：聊天是最保守的形态，不会画出不存在的场景
}

private fun deliveryFrom(raw: String): DeliveryState =
    DeliveryState.entries.firstOrNull { it.name == raw } ?: DeliveryState.INTERRUPTED
// 未知一律当"打断"：宁可认为这半句说过（会进上下文），也不要当成 PENDING 又播一遍

private fun kindFrom(raw: String): EventKind = EventKind.fromWire(raw) ?: EventKind.SAY
// 未知类型当普通消息：至少能把文字显示出来，而不是整条消失

private fun roleFrom(raw: String): MessageRole = when (raw) {
    "assistant" -> MessageRole.ASSISTANT
    "system" -> MessageRole.SYSTEM
    else -> MessageRole.USER
}

fun ConversationEntity.toModel(): Conversation = Conversation(
    id = id,
    title = title,
    mode = modeFrom(mode),
    modeSince = modeSince,
    modeSinceEventId = modeSinceEventId,
    personaId = personaId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    archived = archived,
    proactiveCooldownUntil = proactiveCooldownUntil
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    mode = mode.toWire(),
    modeSince = modeSince,
    modeSinceEventId = modeSinceEventId,
    personaId = personaId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    archived = archived,
    proactiveCooldownUntil = proactiveCooldownUntil
)

fun EventEntity.toModel(): Event = Event(
    id = id,
    conversationId = conversationId,
    kind = kindFrom(kind),
    speaker = speaker,
    text = text,
    turnId = turnId,
    mode = modeFrom(mode),
    gapHintMs = gapHintMs,
    typingHintMs = typingHintMs,
    waitReason = waitReason,
    delivery = deliveryFrom(delivery),
    gapMs = gapMs,
    typingMs = typingMs,
    createdAt = createdAt,
    deliveredAt = deliveredAt
)

fun Event.toEntity(): EventEntity = EventEntity(
    id = id,
    conversationId = conversationId,
    kind = kind.wire,
    speaker = speaker,
    text = text,
    turnId = turnId,
    mode = mode.toWire(),
    gapHintMs = gapHintMs,
    typingHintMs = typingHintMs,
    waitReason = waitReason,
    delivery = delivery.name,
    gapMs = gapMs,
    typingMs = typingMs,
    createdAt = createdAt,
    deliveredAt = deliveredAt
)

fun MessageEntity.toModel(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = roleFrom(role),
    text = text,
    turnId = turnId,
    createdAt = createdAt,
    interruptedAt = interruptedAt,
    sentToModel = sentToModel
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = when (role) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.SYSTEM -> "system"
    },
    text = text,
    turnId = turnId,
    createdAt = createdAt,
    interruptedAt = interruptedAt,
    sentToModel = sentToModel
)

fun PersonaEntity.toModel(): Persona = Persona(
    id = id,
    name = name,
    tagline = tagline,
    identity = identity,
    voice = voice,
    sceneVoice = sceneVoice,
    mannerisms = mannerisms,
    surroundings = surroundings,
    depiction = depiction,
    boundaries = boundaries,
    secretNotes = secretNotes,
    avatar = avatar,
    builtin = builtin,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Persona.toEntity(): PersonaEntity = PersonaEntity(
    id = id,
    name = name,
    tagline = tagline,
    identity = identity,
    voice = voice,
    sceneVoice = sceneVoice,
    mannerisms = mannerisms,
    surroundings = surroundings,
    depiction = depiction,
    boundaries = boundaries,
    secretNotes = secretNotes,
    avatar = avatar,
    builtin = builtin,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun MemoryEntity.toModel(): Memory = Memory(
    id = id,
    content = content,
    personaId = personaId,
    tags = tags.split('\n').filter { it.isNotBlank() },
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    sourceConversationId = sourceConversationId,
    pinned = pinned
)

fun Memory.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    content = content,
    tags = tags.joinToString("\n"),
    personaId = personaId,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    sourceConversationId = sourceConversationId,
    pinned = pinned
)

fun SummaryEntity.toModel(): SceneSummary = SceneSummary(
    id = id,
    conversationId = conversationId,
    fromEventId = fromEventId,
    toEventId = toEventId,
    text = text,
    model = model,
    createdAt = createdAt
)

fun SceneSummary.toEntity(): SummaryEntity = SummaryEntity(
    id = id,
    conversationId = conversationId,
    fromEventId = fromEventId,
    toEventId = toEventId,
    text = text,
    model = model,
    createdAt = createdAt
)

fun UsageEntity.toModel(): Usage = Usage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    cachedTokens = cachedTokens,
    reasoningTokens = reasoningTokens,
    imageCount = imageCount,
    estimated = estimated
)
