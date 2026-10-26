package app.solochat.data.pricing

import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.model.*
import app.solochat.data.*
import app.solochat.ui.settings.P2ModelSettingsDraft
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 合成旧文档从只读计划进入正式准备器与实际 R4 规则解析。 */
class ProviderPricingIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `真实临时DataStore计划CAS重开后进入正式调用准备与R4`() = runBlocking {
        val file = File(temporary.newFolder(), "pricing.preferences_pb")
        fun store(scope: CoroutineScope) = SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = store(firstScope)
        first.setBaseUrl("https://synthetic.example/v1")
        first.setModel("model")
        val activeTable = PriceTable("active", "legacy synthetic", listOf(
            ModelPriceEntry("model", PriceRates(1.0, 2.0, null),
                peak = PriceRates(3.0, 4.0, 0.0), peakEnabled = true, window = PeakWindow(0, 60),
                schedule = PriceSchedule(listOf(PriceTimeRange(0, 60)), calendar = PriceCalendar(
                    "2026-01-01", "2026-12-31", source = "synthetic-confirmed", confirmed = true)),
                tiers = listOf(
                    PriceTier(100, PriceRates(1.0, 2.0, null), PriceRates(3.0, 4.0, 0.0)),
                    PriceTier(null, PriceRates(5.0, 6.0, null), PriceRates(7.0, 8.0, 0.0))
                )
            ), ModelPriceEntry("legacy-orphan", PriceRates(11.0, 12.0))))
        val inactiveTable = PriceTable("inactive", "legacy inactive synthetic", listOf(
            ModelPriceEntry("inactive-model", PriceRates(13.0, 14.0))))
        val catalog = PriceCatalog("active", listOf(activeTable, inactiveTable))
        first.setUseOfficialPrice(true)
        first.setModelList(listOf("model"))
        first.saveSection(SettingsSection.PRICE, first.snapshot().copy(priceCatalog = catalog,
            priceInput = 8.0, priceOutput = 9.0, priceCached = 0.0,
            priceConfigured = true, cachedPriceConfigured = true))
        first.migrateApiProfiles { }
        val frozen = first.freezeModelConfigUpgrade(emptyList(), newId = { "migration-model" }, newPlanId = { "pricing-ready" })
        val readyCandidate = requireNotNull(frozen.frozenPlan).candidate
        first.markModelConfigRoomAppended(requireNotNull(frozen.planId))
        first.publishModelConfigReady(requireNotNull(frozen.planId), readyCandidate)

        val plan = ProviderPricingMigrationPlan.create(readyCandidate,
            readyCandidate.providers.single().id, bundledSnapshot = null)
        val migrated = ProviderPricingPublisher.migrate(readyCandidate, plan, bundledSnapshot = null)
        first.saveReadyModelRegistryV2(migrated, readyCandidate.revision)
        assertTrue(runCatching { first.saveReadyModelRegistryV2(migrated.copy(revision = 2), 0) }.isFailure)
        firstScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = store(reopenedScope)
            val beforeDraft = requireNotNull(reopened.loadModelRegistryV2())
            assertEquals(migrated, beforeDraft)
            val migratedPricing = beforeDraft.providers.single().pricing!!
            assertEquals(readyCandidate.providers.single().legacyPricing, migratedPricing.legacyArchive?.legacy)
            assertEquals(listOf("legacy-orphan"), migratedPricing.unassigned.map { it.requestModelId })
            assertEquals(2, migratedPricing.legacyArchive?.legacy?.priceCatalog?.tables?.size)
            assertEquals("inactive", migratedPricing.legacyArchive?.legacy?.priceCatalog?.tables?.last()?.id)
            assertEquals(8.0, migratedPricing.legacyArchive?.legacy?.priceInput ?: -1.0, 0.0)
            assertEquals(0.0, migratedPricing.legacyArchive?.legacy?.priceCached ?: -1.0, 0.0)
            val staleDraft = P2ModelSettingsDraft.fromDocument(beforeDraft).withModel(
                beforeDraft.models.single().copy(displayName = "stale draft"))
            val draft = P2ModelSettingsDraft.fromDocument(beforeDraft).withModel(
                beforeDraft.models.single().copy(displayName = "saved model edit"))
            val provider = beforeDraft.providers.single()
            val originalBinding = provider.pricing!!.bindings.getValue(beforeDraft.models.single().id)
            val importedArchive = PriceExchangeArchive("synthetic legacy archive", catalogPresent = true,
                tables = listOf(PriceExchangeTable("legacy-table", "inactive", false,
                    listOf(ModelPriceEntry("retained", PriceRates(9.0, 10.0))))))
            val bundledArchive = PriceExchangeArchive("synthetic bundled archive", catalogPresent = true,
                selectedTableFileId = "bundle-table", tables = listOf(PriceExchangeTable("bundle-table", "bundle", true,
                    listOf(ModelPriceEntry("bundle-retained", PriceRates(15.0, 16.0))))))
            val exchange = PriceExchangeDocument(name = "synthetic import", entries = listOf(PriceExchangeEntry(
                "file-entry", beforeDraft.models.single().requestModelId,
                rule = originalBinding.rule.copy(note = "saved price edit"),
                origin = ExchangePriceOrigin("IMPORT", sourceId = "file-entry"))),
                unassigned = listOf(PriceExchangeEntry("file-orphan", "removed-request-id", rule = null,
                    origin = ExchangePriceOrigin("REFERENCE", channel = "test-channel"),
                    conditions = ExchangePriceConditions("OFF", "c".repeat(64)), unassignedReason = "MODEL_REMOVED")),
                legacyArchive = importedArchive, archives = listOf(bundledArchive))
            val exchanged = PriceExchangeTransfer.decode(PriceExchangeTransfer.encode(exchange))
            assertEquals(exchange, exchanged)
            val conflictingExchange = exchanged.copy(entries = listOf(
                exchanged.entries.single().copy(fileEntryId = "collision-a"),
                exchanged.entries.single().copy(fileEntryId = "collision-b")))
            val conflictPlan = PriceExchangeImportPlan.create(conflictingExchange, beforeDraft.models,
                overwriteModelIds = provider.pricing!!.bindings.keys,
                existingPricingModelIds = provider.pricing!!.bindings.keys)
            assertFalse(conflictPlan.ready)
            assertTrue(runCatching { conflictPlan.applyTo(conflictingExchange, provider.pricing!!, beforeDraft.models) }.isFailure)
            assertEquals(beforeDraft, reopened.loadModelRegistryV2())
            val skipPlan = PriceExchangeImportPlan.create(exchanged, beforeDraft.models,
                existingPricingModelIds = provider.pricing!!.bindings.keys)
            assertTrue(skipPlan.rows.single().defaultSkip)
            assertEquals(originalBinding, skipPlan.applyTo(exchanged, provider.pricing!!, beforeDraft.models)
                .bindings.getValue(beforeDraft.models.single().id))
            val importPlan = PriceExchangeImportPlan.create(exchanged, beforeDraft.models,
                overwriteModelIds = setOf(beforeDraft.models.single().id), existingPricingModelIds = setOf(beforeDraft.models.single().id))
            val importedPricing = importPlan.applyTo(exchanged, provider.pricing!!, beforeDraft.models)
            val changedBinding = importedPricing.bindings.getValue(beforeDraft.models.single().id)
            assertEquals(listOf(importedArchive, bundledArchive), importedPricing.importedArchives)
            assertEquals(setOf("legacy-orphan", "removed-request-id"),
                importedPricing.unassigned.map { it.requestModelId }.toSet())
            assertEquals(PriceConditions("OFF", "c".repeat(64)),
                importedPricing.unassigned.single { it.requestModelId == "removed-request-id" }.conditions)
            val priceSaved = ProviderPricingPublisher.edit(beforeDraft, provider.id, beforeDraft.revision,
                upserts = mapOf(beforeDraft.models.single().id to changedBinding), unassigned = importedPricing.unassigned,
                importedArchives = importedPricing.importedArchives)
            reopened.saveReadyModelRegistryV2(priceSaved, beforeDraft.revision)
            val controlledDraft = draft.withPersistedProviderPricing(provider.id,
                priceSaved.providers.single().pricing!!, beforeDraft.revision, priceSaved.revision)
            var keyWrites = 0
            val afterModelSave = ProviderCredentialPublisher(
                writeKey = { _, value -> assertEquals("synthetic-new-key", value); keyWrites++ },
                saveDocument = { reopened.saveReadyModelRegistryV2(it, it.revision - 1) },
                newSlotId = { "synthetic-new-slot" }
            ).save(priceSaved, controlledDraft.document, mapOf(provider.id to "synthetic-new-key"))
            assertEquals(1, keyWrites)
            assertEquals("saved model edit", afterModelSave.models.single().displayName)
            assertEquals("saved price edit", afterModelSave.providers.single().pricing!!.bindings.values.single().rule.note)
            val staleWrites = mutableListOf<String>()
            assertTrue(runCatching { ProviderCredentialPublisher(
                writeKey = { _, value -> staleWrites += value }, saveDocument = {}, newSlotId = { "stale-slot" }
            ).save(afterModelSave, staleDraft.document, mapOf(provider.id to "stale-key")) }.isFailure)
            assertTrue(staleWrites.isEmpty())

            val document = requireNotNull(reopened.loadModelRegistryV2())
            assertEquals(afterModelSave, document)
            val persistedProvider = document.providers.single()
            assertEquals(listOf(importedArchive, bundledArchive), persistedProvider.pricing!!.importedArchives)
            assertEquals(setOf("legacy-orphan", "removed-request-id"),
                persistedProvider.pricing!!.unassigned.map { it.requestModelId }.toSet())
            val model = document.models.single()
            val prepared = ModelAwareCallPreparation.prepare(document, ModelUse.CHAT,
                mapOf(persistedProvider.id to CredentialAvailability.AVAILABLE), mapOf(persistedProvider.keyId to "synthetic-key"),
                ChatConfig(), java.time.Instant.parse("2026-10-24T16:30:00Z").toEpochMilli(),
                object : PriceResolver { override fun resolve(model: String, config: ChatConfig) = error("已整理后不能访问旧解析器") })
                as ModelCallPreparation.Ready
            assertEquals(model.requestModelId, prepared.call.requestModelId)
            assertEquals(3.0, prepared.call.price.inputPerM, 0.0)
            val priceForUsage = prepared.call.price.forInput(TokenAmount(80, TokenSource.RETURNED))
            val recorded = CallRecord("synthetic-call", conversationId = "synthetic", model = model.requestModelId,
                startedAt = prepared.call.priceAt, input = TokenAmount(80, TokenSource.RETURNED),
                output = TokenAmount(20, TokenSource.RETURNED), cached = TokenAmount(10, TokenSource.RETURNED),
                price = priceForUsage, inputCharacters = 1)
            assertEquals((70 * 3.0 + 10 * 0.0 + 20 * 4.0) / 1_000_000.0,
                recordedCost(recorded).low!!, 1e-12)

            val historicalCost = recordedCost(recorded)
            val historicalRecord = recorded
            val currentDocument = requireNotNull(reopened.loadModelRegistryV2())
            val currentBound = currentDocument.providers.single().pricing!!.bindings.getValue(model.id)
            val futureRule = currentBound.rule.copy(standard = PriceRates(30.0, 40.0, 0.0),
                peak = null, peakEnabled = false, schedule = null, tiers = emptyList())
            val futureDocument = ProviderPricingPublisher.edit(currentDocument, persistedProvider.id,
                currentDocument.revision, upserts = mapOf(model.id to currentBound.copy(rule = futureRule)))
            reopened.saveReadyModelRegistryV2(futureDocument, currentDocument.revision)
            assertEquals(historicalRecord, recorded)
            assertEquals(historicalCost, recordedCost(recorded))
            assertEquals(3.0, prepared.call.price.inputPerM, 0.0)
            val futurePrepared = ModelAwareCallPreparation.prepare(requireNotNull(reopened.loadModelRegistryV2()),
                ModelUse.CHAT, mapOf(persistedProvider.id to CredentialAvailability.AVAILABLE),
                mapOf(persistedProvider.keyId to "synthetic-key"), ChatConfig(), prepared.call.priceAt,
                object : PriceResolver { override fun resolve(model: String, config: ChatConfig) = error("新绑定不得访问旧解析器") })
                as ModelCallPreparation.Ready
            assertEquals(30.0, futurePrepared.call.price.inputPerM, 0.0)
        } finally { reopenedScope.cancel() }
    }

    @Test fun `计划到新绑定到正式准备R4阶梯与recordedCost`() {
        val rule = ModelPriceEntry("model", PriceRates(1.0, 2.0, null),
            peak = PriceRates(3.0, 4.0, 0.0), peakEnabled = true,
            window = PeakWindow(0, 60),
            schedule = PriceSchedule(listOf(PriceTimeRange(0, 60)), calendar = PriceCalendar(
                "2026-01-01", "2026-12-31", source = "synthetic-confirmed", confirmed = true)),
            tiers = listOf(PriceTier(100, PriceRates(1.0, 2.0, null), PriceRates(3.0, 4.0, 0.0)),
                PriceTier(null, PriceRates(5.0, 6.0, null), PriceRates(7.0, 8.0, 0.0))))
        val catalog = PriceCatalog("active", listOf(PriceTable("active", "old table", listOf(rule))))
        val document = ModelRegistryDocument(
            providers = listOf(RegistryProvider("provider", "P", "https://p.example/v1", "slot",
                legacyPricing = LegacyProviderPricing(priceCatalog = catalog))),
            models = listOf(RegistryModel("model-id", "provider", "M", "Model")),
            globalDefault = RegistrySelection.explicit(ModelReference("provider", "model-id")))
        val bundled = PriceCatalog("bundle", listOf(PriceTable("bundle", "empty")))
        val plan = ProviderPricingMigrationPlan.create(document, "provider", bundled)
        val migrated = ProviderPricingPublisher.migrate(document, plan, bundled)
        val reopened = ModelRegistryFormat.decode(ModelRegistryFormat.encode(migrated))
        val resolver = object : PriceResolver {
            override fun resolve(model: String, config: ChatConfig) = error("new绑定解析不得走旧价格解析器")
        }
        val prepared = ModelAwareCallPreparation.prepare(reopened, ModelUse.CHAT,
            mapOf("provider" to CredentialAvailability.AVAILABLE), mapOf("slot" to "synthetic-key"),
            ChatConfig(), java.time.Instant.parse("2026-10-24T16:30:00Z").toEpochMilli(), resolver)
            as ModelCallPreparation.Ready
        assertEquals("Model", prepared.call.requestModelId)
        assertEquals(3.0, prepared.call.price.inputPerM, 0.0)
        assertTrue(prepared.call.price.frozenTiers.isNotEmpty())
        val tierPrice = prepared.call.price.forInput(TokenAmount(80, TokenSource.RETURNED))
        val record = CallRecord("call", conversationId = "synthetic", model = "Model", startedAt = prepared.call.priceAt,
            input = TokenAmount(80, TokenSource.RETURNED), output = TokenAmount(20, TokenSource.RETURNED),
            cached = TokenAmount(10, TokenSource.RETURNED), price = tierPrice, inputCharacters = 1)
        val cost = recordedCost(record)
        assertNotNull(cost.low)
        assertEquals(((70 * 3.0 + 10 * 0.0 + 20 * 4.0) / 1_000_000.0), cost.low!!, 1e-12)
        assertEquals(plan.archive.legacy, migrated.providers.single().pricing!!.legacyArchive!!.legacy)
    }

    @Test fun `整理后无绑定明确未知且不回退旧规则`() {
        val doc = ModelRegistryDocument(
            providers = listOf(RegistryProvider("provider", "P", "https://p.example/v1", "slot",
                legacyPricing = LegacyProviderPricing(priceInput = 8.0, priceOutput = 9.0, priceConfigured = true),
                pricing = ProviderPricing())),
            models = listOf(RegistryModel("model-id", "provider", "M", "Model", legacyPriceEligible = true)),
            globalDefault = RegistrySelection.explicit(ModelReference("provider", "model-id")))
        val prepared = ModelAwareCallPreparation.prepare(doc, ModelUse.CHAT,
            mapOf("provider" to CredentialAvailability.AVAILABLE), mapOf("slot" to "synthetic-key"),
            ChatConfig(), 100L, object : PriceResolver { override fun resolve(model: String, config: ChatConfig) = ModelPrice(9.0, 9.0, known = true) })
            as ModelCallPreparation.Ready
        assertFalse(prepared.call.price.known)
        assertEquals("未配置模型报价", prepared.call.price.source)
    }
}
