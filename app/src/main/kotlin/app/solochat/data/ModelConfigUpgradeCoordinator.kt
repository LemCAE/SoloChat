package app.solochat.data

import androidx.room.withTransaction
import app.solochat.data.db.ConversationModelBindingEntity
import app.solochat.data.db.SoloChatDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 仅供 A-10 启动门禁显式调用。当前会话的 modelOverride 写入不持有配置锁，
 * 因此禁止在会话 UI 已可操作时运行此协调器。
 */
internal class ModelConfigUpgradeCoordinator internal constructor(
    private val settings: SettingsStore,
    private val room: ModelConfigUpgradeRoom,
    private val configMutex: Mutex,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val newPlanId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val checkpoint: suspend (ModelConfigUpgradeCheckpoint) -> Unit = {}
) {
    constructor(
        settings: SettingsStore,
        db: SoloChatDatabase,
        configMutex: Mutex
    ) : this(settings, RoomModelConfigUpgradeRoom(db), configMutex)

    /** DataStore 文档在所有 Room 关联成功后才与 READY 同步发布。 */
    suspend fun coordinate(): ModelConfigPreparationRecord = configMutex.withLock { coordinateLocked() }

    /** 调用方已持有应用配置锁时使用，避免嵌套 Mutex。 */
    internal suspend fun coordinateLocked(): ModelConfigPreparationRecord {
        val conversations = room.legacyConversationOverrides()
        val record = settings.freezeModelConfigUpgrade(conversations, newId, newPlanId)
        val planId = requireNotNull(record.planId) { "配置升级计划缺少 ID" }
        val plan = requireNotNull(record.frozenPlan) { "配置升级计划未冻结" }

        if (record.phase == "READY") {
            val current = checkNotNull(settings.loadModelRegistryV2()) { "READY 配置文档缺失" }
            current.validate()
            return record
        }

        val bindings = plan.conversations.map { item ->
            ConversationModelBindingEntity(
                conversationId = item.conversationId,
                selectionState = item.selection.state,
                providerId = item.selection.providerId,
                modelId = item.selection.modelId,
                pendingReason = item.selection.reason,
                migrationSource = item.migrationSource,
                upgradePlanId = planId
            )
        }

        when (record.phase) {
            "FROZEN" -> {
                checkpoint(ModelConfigUpgradeCheckpoint.AFTER_FROZEN)
                requireCurrentSource(plan)
                checkpoint(ModelConfigUpgradeCheckpoint.BEFORE_ROOM_TRANSACTION)
                requireCurrentSource(plan)
                room.appendBindings(bindings) {
                    checkpoint(ModelConfigUpgradeCheckpoint.BEFORE_ROOM_COMMIT)
                }
                checkpoint(ModelConfigUpgradeCheckpoint.AFTER_ROOM_TRANSACTION)
                requireCurrentSource(plan)
                settings.markModelConfigRoomAppended(planId)
                verifyBindings(bindings)
            }
            "ROOM_APPENDED" -> {
                requireCurrentSource(plan)
                verifyBindings(bindings)
            }
            else -> error("不能继续配置升级阶段：${record.phase}")
        }

        requireCurrentSource(plan)
        settings.publishModelConfigReady(planId, plan.candidate) {
            checkpoint(ModelConfigUpgradeCheckpoint.BEFORE_READY_COMMIT)
        }
        val ready = checkNotNull(settings.loadModelConfigUpgradePreparation()) { "READY 记录丢失" }
        check(ready.phase == "READY" && ready.planId == planId) { "配置升级 READY 发布不完整" }
        check(settings.loadModelRegistryV2() == plan.candidate) { "READY 阶段与 v2 配置不一致" }
        return ready
    }

    private suspend fun verifyBindings(expected: List<ConversationModelBindingEntity>) {
        val actual = room.bindings(expected.map { it.conversationId }).associateBy { it.conversationId }
        check(expected.all { actual[it.conversationId] == it } && actual.size == expected.size) {
            "配置升级 Room 关联与冻结计划不一致"
        }
    }

    private suspend fun requireCurrentSource(plan: ModelConfigUpgradePlan) {
        plan.requireCurrent(ModelConfigUpgradeSource(settings.snapshot(), room.legacyConversationOverrides()))
    }
}

internal enum class ModelConfigUpgradeCheckpoint {
    AFTER_FROZEN,
    BEFORE_ROOM_TRANSACTION,
    BEFORE_ROOM_COMMIT,
    AFTER_ROOM_TRANSACTION,
    BEFORE_READY_COMMIT
}

/** 协调器只需此局部测试缝；生产实现仍由现有 Room 和 A-3 DAO 承担。 */
internal interface ModelConfigUpgradeRoom {
    suspend fun legacyConversationOverrides(): List<LegacyConversationModelOverride>
    suspend fun appendBindings(rows: List<ConversationModelBindingEntity>, beforeCommit: suspend () -> Unit)
    suspend fun bindings(conversationIds: List<String>): List<ConversationModelBindingEntity>
}

private class RoomModelConfigUpgradeRoom(private val db: SoloChatDatabase) : ModelConfigUpgradeRoom {
    override suspend fun legacyConversationOverrides(): List<LegacyConversationModelOverride> =
        db.conversations().all().map { LegacyConversationModelOverride(it.id, it.modelOverride) }

    override suspend fun appendBindings(
        rows: List<ConversationModelBindingEntity>,
        beforeCommit: suspend () -> Unit
    ) = db.withTransaction {
        rows.forEach { db.modelConfigs().putConversationBindingIfAbsentOrSame(it) }
        beforeCommit()
    }

    override suspend fun bindings(conversationIds: List<String>): List<ConversationModelBindingEntity> =
        conversationIds.mapNotNull { db.modelConfigs().conversationBinding(it) }
}
