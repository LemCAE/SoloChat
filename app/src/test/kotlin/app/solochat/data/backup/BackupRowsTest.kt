package app.solochat.data.backup

import app.solochat.core.backup.BackupCodec
import app.solochat.core.backup.BackupTargetIds
import app.solochat.core.backup.planBackupImport
import app.solochat.core.model.*
import app.solochat.data.db.*
import app.solochat.data.toEntity
import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class BackupRowsTest {
    @Test fun `实际调用快照对象形状可导出恢复并重映射调用 ID`() {
        val actual = CallConfigSnapshot(
            version = 1, providerId = "provider", providerName = "测试接口", modelId = "model",
            modelDisplayName = "模型", requestModelId = "request-name", use = ModelUse.CHAT,
            selectionSource = SelectionSource.GLOBAL_DEFAULT,
            thinking = ModelThinking(ThinkingMode.ON, app.solochat.core.llm.ThinkingEffort.MEDIUM),
            thinkingSource = ThinkingSource.MODEL_PREFERENCE,
            actualThinkingParameters = listOf(ThinkingParameterSnapshot("reasoning_effort", "medium"))
        ).toEntity("call")
        val actualRows = rows().copy(callConfigSnapshots = listOf(actual))
        val output = ByteArrayOutputStream()
        BackupCodec.write(actualRows.toBackupData(), output, 124)
        val archive = BackupCodec.read(output.toByteArray().inputStream())
        val plan = planBackupImport(archive, BackupTargetIds(1000, 2000, 3000))
        val restored = plan.data.toBackupRows().toBackupData().callConfigSnapshots.single()

        assertEquals(plan.data.calls.single().record.id, restored.callId)
        assertEquals("provider", restored.providerId)
        assertEquals("{\"reasoning_effort\":\"medium\"}", restored.actualThinkingParametersJson)
    }

    @Test fun `已写入的旧思考参数数组仍可导出`() {
        val legacyRows = rows().copy(callConfigSnapshots = rows().callConfigSnapshots.map {
            it.copy(actualThinkingParametersJson = "[{\"name\":\"reasoning_effort\",\"value\":\"medium\"}]")
        })
        assertEquals(1, legacyRows.toBackupData().callConfigSnapshots.size)
    }

    @Test fun `实际恢复实体编码可重新导出且保留映射后的正文摘要与账目关联`() {
        val output = ByteArrayOutputStream()
        BackupCodec.write(rows().toBackupData(), output, 123)
        val archive = BackupCodec.read(output.toByteArray().inputStream())
        val plan = planBackupImport(archive, BackupTargetIds(1000, 2000, 3000))
        assertEquals(plan.data, plan.data.toBackupRows().toBackupData())
        assertEquals(405, plan.data.messages.size)
        assertEquals(1001L, plan.data.messages.first().id)
        assertEquals(3001L, plan.data.calls.single().usageId)
        assertEquals("NEEDS_CONFIRMATION", plan.data.conversationModelBindings.single().selectionState)
        assertEquals(plan.data.calls.single().record.id, plan.data.callConfigSnapshots.single().callId)
    }
    private fun rows(): BackupRows {
        val conversation = Conversation("archived", archived = true)
        val messages = (1L..405L).map {
            Message(it, conversation.id, MessageRole.USER, "第 $it 条", sentToModel = it != 405L)
        }
        val event = Event(1, conversation.id, EventKind.SAY, text = "未播出", delivery = DeliveryState.PENDING)
        val source = CompressionSource(conversation.id, "", "", null, ChatMode.CHAT,
            messages.take(2).map(::fingerprintOf), emptyList())
        val call = CallRecord("call", conversationId = "deleted", model = "m", startedAt = -1,
            price = ModelPrice(2.0, 3.0), inputCharacters = 12)
        return BackupRows(listOf(conversation.toEntity()), messages.map { it.toEntity() }, listOf(event.toEntity()),
            emptyList(), listOf(Memory("memory", "保留的记忆", sourceConversationId = "deleted").toEntity()),
            listOf(SceneSummary("summary", conversation.id, 1, 1, "旧摘要").toEntity()),
            listOf(ContextRevisionEntity("revision", conversation.id, "", null, "ORIGINAL",
                Json.encodeToString(source), "DRAFT", 12, true)),
            listOf(ContextSegmentEntity("segment", "revision", 0, 1, 2, "stale", "草稿正文", "m")),
            listOf(UsageEntity(1, "deleted", "m", 10, 2, 0, 0, 0, false, -1),
                UsageEntity(2, "deleted", "old-m", 9, 0, 0, 0, 0, true, Long.MAX_VALUE)),
            listOf(CallRecordEntity(call.id, call.operationId, call.conversationId, call.model, call.kind.name,
                "INTERRUPTED", call.startedAt, null, 1, Json.encodeToString(call))),
            listOf(ConversationModelBindingEntity("archived", "EXPLICIT", "source-provider", "source-model",
                null, "legacy-source", "old-plan")),
            listOf(CallConfigSnapshotEntity("call", 1, "source-provider", "源接口", "source-model", "源模型",
                "Original-Case", "CHAT", "CONVERSATION", "AUTO", null, "MODEL", "{}")))
    }

    @Test fun `导出保留超过四百条历史与未推送状态以及归档和删除来源账目`() {
        val original = rows()
        val data = original.toBackupData()
        val output = ByteArrayOutputStream()
        BackupCodec.write(data, output, 123)
        val restored = BackupCodec.read(output.toByteArray().inputStream()).data
        assertEquals(data, restored)
        assertEquals(405, restored.messages.size)
        assertEquals("第 1 条", restored.messages.first().text)
        assertFalse(restored.messages.last().sentToModel)
        assertTrue(restored.conversations.single().archived)
        assertEquals(DeliveryState.PENDING, restored.events.single().delivery)
        assertEquals("deleted", restored.memories.single().sourceConversationId)
        assertEquals(listOf(1L, 2L), restored.usages.map { it.id })
        assertEquals(Long.MAX_VALUE, restored.usages.last().at)
        val call = restored.calls.single()
        assertEquals(1L, call.usageId)
        assertEquals(CallStatus.INTERRUPTED, call.record.status)
        assertTrue(call.record.outputIncomplete)
        assertEquals(ModelPrice(2.0, 3.0), call.record.price)
        assertEquals("segment", restored.revisions.single().segments.single().id)
        assertEquals("DRAFT", restored.revisions.single().state)
        assertTrue(restored.revisions.single().manuallyEdited)
        // 映射不能为了导出而修改原行，旧 JSON 仍保持 STARTED。
        assertEquals(CallStatus.STARTED, Json.decodeFromString<CallRecord>(original.calls.single().detailJson).status)
    }

    @Test fun `调用索引与正文身份不一致时拒绝导出而非丢失账目`() {
        val rows = rows()
        try {
            rows.copy(calls = rows.calls.map { it.copy(model = "wrong") }).toBackupData()
            fail("应拒绝不一致调用")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `无所属版本的分段拒绝导出而非静默忽略`() {
        val rows = rows()
        try {
            rows.copy(revisions = emptyList()).toBackupData()
            fail("应拒绝悬空分段")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `v3 Room行快照闭包包含附件提交与已悬空的历史调用来源`() {
        val bytes = byteArrayOf(4, 5, 6)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val input = CallMediaInput("call", listOf(MediaContextReference(999, 0, "removed-asset", sha,
            "png-2048-v1", sha, "image/png", 1, 3, MediaReferenceSource.RECENT_HISTORY)), 1)
        val source = rows().copy(
            mediaAssets = listOf(MediaAssetEntity("asset", sha, "image/png", 3, 1, 3, 1, "objects/$sha", 1)),
            messageAttachments = listOf(MessageAttachmentEntity(1, 0, "asset")),
            submissions = listOf(InputSubmissionEntity("submission", "archived", "a".repeat(64), "[1]", 1)),
            callMediaInputs = listOf(CallMediaInputEntity("call", Json.encodeToString(input), 1))
        )
        val data = source.toBackupData()
        assertEquals(1, data.media.assets.size)
        assertEquals(1, data.media.attachments.size)
        assertEquals(1, data.media.submissions.size)
        assertEquals(1, data.media.callInputs.size)

        val manifest = app.solochat.core.backup.BackupManifest(version = 3, id = "v3",
            createdAt = 1, dataChecksum = app.solochat.core.model.checksumOf(Json.encodeToString(data)), counts = data.countsV3())
        val archive = app.solochat.core.backup.BackupArchive(manifest, data)
        val plan = planBackupImport(archive, BackupTargetIds(1000, 2000, 3000))
        val restored = plan.data
        assertEquals(restored.messages.first().id, restored.media.attachments.single().messageId)
        assertEquals(restored.conversations.single().id, restored.media.submissions.single().conversationId)
        val callInput = Json.decodeFromString<CallMediaInput>(restored.media.callInputs.single().inputJson)
        assertEquals(restored.calls.single().record.id, callInput.callId)
        assertEquals(-999L, callInput.images.single().messageId)
        assertTrue("已悬空的调用来源不得指向本机同名资源", callInput.images.single().mediaId.startsWith("missing:"))
    }
}
