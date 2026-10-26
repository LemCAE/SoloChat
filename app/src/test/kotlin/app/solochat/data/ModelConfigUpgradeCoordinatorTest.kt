package app.solochat.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.solochat.data.db.ConversationModelBindingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelConfigUpgradeCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun withStore(block: suspend (SettingsStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(temporary.newFolder(), "settings.preferences_pb")
            block(SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })))
        } finally { scope.cancel() }
    }

    private suspend fun configuredStore(store: SettingsStore) {
        store.setModel("default-model")
        store.setModelList(listOf("default-model", "directory-model"))
        store.migrateApiProfiles { }
    }

    private fun coordinator(
        store: SettingsStore,
        room: FakeUpgradeRoom,
        mutex: Mutex = Mutex(),
        checkpoint: suspend (ModelConfigUpgradeCheckpoint) -> Unit = {},
        newId: () -> String = { error("冻结后不得重新生成模型 ID") }
    ) = ModelConfigUpgradeCoordinator(store, room, mutex, newId, { "upgrade-plan" }, checkpoint)

    @Test fun `完成后文档与READY同时发布且重跑不重复写关联`() = withStore { store ->
        configuredStore(store)
        val room = FakeUpgradeRoom(listOf(LegacyConversationModelOverride("c1", "custom-model")))
        val mutex = Mutex()

        var nextModelId = 0
        val ready = coordinator(store, room, mutex, newId = { "model-${++nextModelId}" }).coordinate()
        val second = coordinator(store, room, mutex).coordinate()

        assertEquals("READY", ready.phase)
        assertEquals(ready, second)
        assertEquals(1, room.rows.size)
        assertEquals("EXPLICIT", room.rows.getValue("c1").selectionState)
        assertEquals("upgrade-plan", room.rows.getValue("c1").upgradePlanId)
        assertEquals(ready.frozenPlan!!.candidate, store.loadModelRegistryV2())
    }

    @Test fun `READY后编辑配置并重新打开DataStore协调器仍以当前v2为权威`() = runBlocking {
        val file = File(temporary.newFolder(), "restart-settings.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val room = FakeUpgradeRoom(emptyList())
        try {
            val firstStore = SettingsStore(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
            configuredStore(firstStore)
            coordinator(firstStore, room, newId = modelIds()).coordinate()
            val migrated = checkNotNull(firstStore.loadModelRegistryV2())
            val edited = migrated.copy(revision = migrated.revision + 1,
                providers = migrated.providers.map { it.copy(name = "用户已修改") })
            firstStore.saveReadyModelRegistryV2(edited, migrated.revision)
            firstScope.cancel()

            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopenedStore = SettingsStore(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
                val restarted = coordinator(reopenedStore, room).coordinate()
                assertEquals("READY", restarted.phase)
                assertEquals(edited, reopenedStore.loadModelRegistryV2())
                assertTrue(room.rows.isEmpty())
            } finally { secondScope.cancel() }
        } finally { firstScope.cancel() }
    }

    @Test fun `A2旧准备记录冻结时复用原模型ID映射`() = withStore { store ->
        configuredStore(store)
        val conversations = listOf(LegacyConversationModelOverride("c1", "custom-model"))
        var next = 0
        val prepared = store.prepareModelConfigUpgrade(conversations) { "prepared-${++next}" }
        assertEquals(1, prepared.version)
        val oldA2Json = ModelRegistryFormat.encodePreparation(prepared)
            .replace(",\"version\":1,\"phase\":\"PREPARED\",\"planId\":null,\"frozenPlan\":null", "")
        assertEquals(prepared, ModelRegistryFormat.decodePreparation(oldA2Json))
        val room = FakeUpgradeRoom(conversations)

        val ready = coordinator(store, room, newId = { error("必须复用A2准备记录") }).coordinate()

        assertEquals(prepared.modelIds, ready.modelIds)
        assertEquals(2, ready.version)
        assertEquals("READY", ready.phase)
        assertEquals("prepared-1", ready.frozenPlan!!.candidate.models.first().id)
        assertTrue(runCatching {
            ModelRegistryFormat.decodePreparation(
                ModelRegistryFormat.encodePreparation(prepared).replace("\"version\":1", "\"version\":99")
            )
        }.isFailure)
    }

    @Test fun `Room提交前故障回滚并以相同冻结计划重试`() = withStore { store ->
        configuredStore(store)
        val room = FakeUpgradeRoom(listOf(LegacyConversationModelOverride("c1", null)))
        val mutex = Mutex()
        val first = coordinator(store, room, mutex, checkpoint = {
            if (it == ModelConfigUpgradeCheckpoint.BEFORE_ROOM_COMMIT) error("注入事务提交前故障")
        }, newId = modelIds()).coordinateResult()

        assertTrue(first.isFailure)
        assertTrue(room.rows.isEmpty())
        val frozen = store.loadModelConfigUpgradePreparation()!!
        assertEquals("FROZEN", frozen.phase)
        assertEquals("INHERIT", frozen.frozenPlan!!.conversations.single().selection.state)

        val ready = coordinator(store, room, mutex).coordinate()
        assertEquals(frozen.planId, ready.planId)
        assertEquals("READY", ready.phase)
        assertEquals(1, room.rows.size)
    }

    @Test fun `Room已提交而阶段未记录时重放接受同值且不增加行`() = withStore { store ->
        configuredStore(store)
        val room = FakeUpgradeRoom(listOf(LegacyConversationModelOverride("c1", null)))
        val mutex = Mutex()
        val failure = coordinator(store, room, mutex, checkpoint = {
            if (it == ModelConfigUpgradeCheckpoint.AFTER_ROOM_TRANSACTION) error("模拟Room提交后进程中断")
        }, newId = modelIds()).coordinateResult()

        assertTrue(failure.isFailure)
        assertEquals(1, room.rows.size)
        assertEquals("FROZEN", store.loadModelConfigUpgradePreparation()!!.phase)

        val ready = coordinator(store, room, mutex).coordinate()
        assertEquals("READY", ready.phase)
        assertEquals(1, room.rows.size)
    }

    @Test fun `READY写入内故障不会留下单独v2文档`() = withStore { store ->
        configuredStore(store)
        val room = FakeUpgradeRoom(listOf(LegacyConversationModelOverride("c1", null)))
        val mutex = Mutex()
        val failure = coordinator(store, room, mutex, checkpoint = {
            if (it == ModelConfigUpgradeCheckpoint.BEFORE_READY_COMMIT) error("模拟DataStore提交失败")
        }, newId = modelIds()).coordinateResult()

        assertTrue(failure.isFailure)
        assertEquals("ROOM_APPENDED", store.loadModelConfigUpgradePreparation()!!.phase)
        assertNull(store.loadModelRegistryV2())
        assertEquals("READY", coordinator(store, room, mutex).coordinate().phase)
        assertTrue(store.loadModelRegistryV2() != null)
    }

    @Test fun `冻结后源配置变化阻止Room写入和READY发布`() = withStore { store ->
        configuredStore(store)
        val room = FakeUpgradeRoom(listOf(LegacyConversationModelOverride("c1", null)))
        val failure = coordinator(store, room, checkpoint = {
            if (it == ModelConfigUpgradeCheckpoint.AFTER_FROZEN)
                store.saveSection(SettingsSection.API, store.snapshot().copy(model = "changed-model"))
        }, newId = modelIds()).coordinateResult()

        assertTrue(failure.isFailure)
        assertEquals("FROZEN", store.loadModelConfigUpgradePreparation()!!.phase)
        assertTrue(room.rows.isEmpty())
        assertNull(store.loadModelRegistryV2())
        assertFalse(store.snapshot().model == "default-model")
    }

    private suspend fun ModelConfigUpgradeCoordinator.coordinateResult() = runCatching { coordinate() }

    private fun modelIds(): () -> String {
        var next = 0
        return { "model-${++next}" }
    }

    private class FakeUpgradeRoom(
        private val conversations: List<LegacyConversationModelOverride>
    ) : ModelConfigUpgradeRoom {
        val rows = linkedMapOf<String, ConversationModelBindingEntity>()

        override suspend fun legacyConversationOverrides() = conversations

        override suspend fun appendBindings(
            rows: List<ConversationModelBindingEntity>,
            beforeCommit: suspend () -> Unit
        ) {
            val staged = this.rows.toMutableMap()
            rows.forEach { row ->
                val existing = staged[row.conversationId]
                check(existing == null || existing == row) { "会话模型关联冲突：${row.conversationId}" }
                staged[row.conversationId] = row
            }
            beforeCommit()
            this.rows.clear()
            this.rows.putAll(staged)
        }

        override suspend fun bindings(conversationIds: List<String>) =
            conversationIds.mapNotNull(rows::get)
    }
}
