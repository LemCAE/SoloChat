package app.solochat.core.backup

import app.solochat.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

class BackupTest {
    private val backupJson = Json { encodeDefaults = true }
    private fun data(): BackupData {
        val persona = Persona("default", "测试人物", identity = "人物设定", builtin = true)
        val conversation = Conversation("c", title = "测试会话", personaId = "default", modeSinceEventId = 7,
            activeContextRevisionId = "revision")
        val messages = listOf(
            Message(3, "c", MessageRole.USER, "不喝咖啡", turnId = 99, sentToModel = true),
            Message(8, "c", MessageRole.ASSISTANT, "记住了", turnId = 99),
            Message(15, "c", MessageRole.USER, "尚未推送", turnId = 100)
        )
        val events = listOf(Event(7, "c", EventKind.SAY, text = "记住了", turnId = 99,
            delivery = DeliveryState.DELIVERED), Event(19, "c", EventKind.SAY, text = "未播出", delivery = DeliveryState.PENDING))
        val source = CompressionSource("c", "default", personaChecksum(listOf(persona), "default"),
            null, ChatMode.CHAT, messages.take(2).map(::fingerprintOf), events.take(1).map(::fingerprintOf))
        val revision = ContextRevision("revision", "c", "default", null, CompressionMethod.ORIGINAL, source,
            listOf(ContextSegment("segment", 3, 8, checksumOf(source.messages.joinToString("|") { it.checksum }),
                "不喝咖啡，已答复记住", "test-model")), 200, state = "COMPLETE")
        val usage = Usage(100, 20, 0)
        val call = CallRecord("call", conversationId = "c", model = "test-model", status = CallStatus.SUCCESS,
            startedAt = 123, finishedAt = 125, price = ModelPrice(1.0, 2.0), inputCharacters = 42,
            input = TokenAmount(100, TokenSource.RETURNED), output = TokenAmount(20, TokenSource.RETURNED),
            contextMessageIds = listOf(3, 8))
        return BackupData(listOf(conversation), messages, events, listOf(persona),
            listOf(Memory("memory", "喜欢绿茶", "default", sourceConversationId = "c")),
            listOf(SceneSummary("summary", "c", 7, 7, "旧摘要")), listOf(revision),
            listOf(BackupUsage(11, "c", "test-model", usage, 123)), listOf(BackupCall(call, 11)))
    }

    private fun archive(data: BackupData = data()): BackupArchive {
        val output = ByteArrayOutputStream()
        BackupCodec.write(data, output, 123, "backup-original")
        return BackupCodec.read(output.toByteArray().inputStream())
    }

    private fun archiveV1(data: BackupData = data()): BackupArchive {
        val encoded = backupJson.encodeToJsonElement(BackupData.serializer(), data).jsonObject -
            setOf("conversationModelBindings", "callConfigSnapshots")
        val body = backupJson.encodeToString(JsonObject(encoded))
        val manifest = BackupManifest(version = 1, id = "backup-v1", createdAt = 123,
            dataChecksum = checksumOf(body), counts = data.counts())
        return BackupCodec.read(zip("manifest.json" to Json.encodeToString(manifest), "data.json" to body).inputStream())
    }

    private fun v2Data(): BackupData {
        val original = data()
        val call = original.calls.single().record
        return original.copy(
            conversationModelBindings = listOf(BackupConversationModelBinding(
                conversationId = "c", selectionState = "EXPLICIT", providerId = "source-provider",
                modelId = "source-model", migrationSource = "source-explicit"
            )),
            callConfigSnapshots = listOf(BackupCallConfigSnapshot(
                callId = call.id, version = 1, providerId = "source-provider", providerName = "源接口",
                modelId = "source-model", modelDisplayName = "源模型", requestModelId = "Original-Case",
                use = "CHAT", selectionSource = "CONVERSATION", thinkingMode = "AUTO",
                thinkingSource = "MODEL", actualThinkingParametersJson = "{}"
            ))
        )
    }

    private fun plan(archive: BackupArchive = archive()): BackupImportPlan {
        var next = 0
        return planBackupImport(archive, BackupTargetIds(100, 200, 300)) { "copy-${++next}" }
    }

    private fun reject(action: () -> Unit) {
        try { action(); fail("应拒绝无效备份") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `中文业务资料经 ZIP 往返保留原记录并明确排除凭据与命令`() {
        val original = data()
        val restored = archive(original)
        assertEquals(original, restored.data)
        assertEquals(original.countsV2(), restored.manifest.counts)
        assertEquals(2, restored.manifest.version)
        assertEquals(BACKUP_EXCLUDED, restored.manifest.excluded)
    }

    @Test fun `v1旧正文和counts可读且新字段不会混入旧协议`() {
        val original = data().copy(conversations = listOf(data().conversations.single().copy(modelOverride = "legacy-Model")))
        val restored = archiveV1(original)
        assertEquals(1, restored.manifest.version)
        assertEquals(original, restored.data)
        assertEquals(original.counts(), restored.manifest.counts)
        val imported = plan(restored).data.conversationModelBindings.single()
        assertEquals("NEEDS_CONFIRMATION", imported.selectionState)
        assertTrue(imported.pendingReason!!.contains("legacy-Model"))
        val v2FieldsInV1 = backupJson.encodeToString(v2Data())
        reject {
            BackupCodec.read(zip(
                "manifest.json" to Json.encodeToString(BackupManifest(version = 1, id = "bad-v1", createdAt = 1,
                    dataChecksum = checksumOf(v2FieldsInV1), counts = data().counts())),
                "data.json" to v2FieldsInV1
            ).inputStream())
        }
    }

    @Test fun `v2模型关联与调用快照校验并在导入时只重映射副本身份`() {
        val original = v2Data()
        val restored = archive(original)
        assertEquals(original, restored.data)
        assertEquals(original.countsV2(), restored.manifest.counts)
        val copied = plan(restored).data
        val binding = copied.conversationModelBindings.single()
        val snapshot = copied.callConfigSnapshots.single()
        assertEquals(copied.conversations.single().id, binding.conversationId)
        assertEquals("NEEDS_CONFIRMATION", binding.selectionState)
        assertEquals("source-provider", binding.providerId)
        assertEquals("source-model", binding.modelId)
        assertEquals("backup:v2:source-explicit", binding.migrationSource)
        assertEquals(copied.calls.single().record.id, snapshot.callId)
        assertEquals("source-provider", snapshot.providerId)
        assertEquals("Original-Case", snapshot.requestModelId)
        assertEquals(original.calls.single().record.price, copied.calls.single().record.price)
        assertEquals(original.calls.single().record.input, copied.calls.single().record.input)
    }

    @Test fun `副本完整重映射摘要调用与用量关联而原资料不变`() {
        val original = archive()
        val before = Json.encodeToString(original.data)
        val result = plan(original)
        val copied = result.data
        assertEquals(before, Json.encodeToString(original.data))
        assertEquals(listOf(101L, 102L, 103L), copied.messages.map { it.id })
        assertEquals(listOf(201L, 202L), copied.events.map { it.id })
        assertEquals(listOf(99L, 99L, 100L), copied.messages.map { it.turnId })
        assertEquals(201L, copied.conversations.single().modeSinceEventId)
        assertFalse(copied.personas.single().builtin)
        assertEquals(copied.personas.single().id, copied.conversations.single().personaId)
        assertEquals(copied.personas.single().id, copied.memories.single().personaId)
        assertEquals(copied.conversations.single().id, copied.memories.single().sourceConversationId)
        assertEquals(301L, copied.calls.single().usageId)
        assertEquals(listOf(101L, 102L), copied.calls.single().record.contextMessageIds)
        assertEquals(original.data.calls.single().record.price, copied.calls.single().record.price)
        assertEquals(original.data.usages.single().usage, copied.usages.single().usage)
        val revision = copied.revisions.single()
        assertEquals(revision.id, copied.conversations.single().activeContextRevisionId)
        validateSegments(revision)
        validateCompressionSource(revision.source, copied.conversations.single(), copied.personas,
            copied.messages.filter { it.role != MessageRole.USER || it.sentToModel }, copied.events)
        assertTrue(result.disabledRevisions.isEmpty())
    }

    @Test fun `导入计划取消未播事件保留代发语义并要求整批会话隔离`() {
        val result = plan()
        assertEquals(DeliveryState.CANCELED, result.data.events.last().delivery)
        assertEquals(DeliveryState.DELIVERED, result.data.events.first().delivery)
        assertFalse(result.data.messages.last().sentToModel)
        assertEquals(result.data.conversations.map { it.id }.toSet(), result.pausedConversationIds)
        assertTrue(result.data.conversationModelBindings.all { it.selectionState == "NEEDS_CONFIRMATION" })
        // 此处只验证写库前计划；不能据此声称 Android 打开会话已不会自动推送。
    }

    @Test fun `原包已失效摘要保留文本且不能因重映射而重新有效`() {
        val original = data()
        val edited = original.copy(messages = original.messages.map {
            if (it.id == 3L) it.copy(text = "原文已改变") else it
        })
        val result = plan(archive(edited))
        assertEquals(original.revisions.single().segments.single().text, result.data.revisions.single().segments.single().text)
        assertNull(result.data.conversations.single().activeContextRevisionId)
        assertEquals(setOf(result.data.revisions.single().id), result.disabledRevisions.keys)
        reject {
            validateCompressionSource(result.data.revisions.single().source, result.data.conversations.single(),
                result.data.personas, result.data.messages, result.data.events)
        }
    }

    @Test fun `删除会话留下的记忆与账目来源不误指向本机会话`() {
        val original = data()
        val call = original.calls.single().record.copy(conversationId = "deleted", status = CallStatus.STARTED,
            contextMessageIds = listOf(777))
        val detached = original.copy(memories = listOf(original.memories.single().copy(
            personaId = "deleted-persona", sourceConversationId = "deleted")),
            calls = listOf(BackupCall(call, 11)), usages = listOf(original.usages.single().copy(conversationId = "deleted")))
        val copied = plan(archive(detached)).data
        assertTrue(copied.memories.single().sourceConversationId.startsWith("backup:"))
        assertEquals(copied.usages.single().conversationId, copied.calls.single().record.conversationId)
        assertEquals(listOf(-777L), copied.calls.single().record.contextMessageIds)
        assertEquals(CallStatus.INTERRUPTED, copied.calls.single().record.status)
        assertTrue(copied.calls.single().record.outputIncomplete)
        assertEquals(copied, archive(copied).data)
    }

    @Test fun `会话人物缺失时不从摘要归属推断且摘要仅保留为历史`() {
        val original = data()
        val c = original.conversations.single().copy(personaId = "")
        val r = original.revisions.single()
        val source = r.source.copy(declaredPersonaId = "", personaChecksum = personaChecksum(original.personas, ""))
        val result = plan(archive(original.copy(conversations = listOf(c), revisions = listOf(r.copy(source = source)))))
        val copied = result.data
        assertTrue(copied.conversations.single().personaId.startsWith("backup:"))
        assertEquals(copied.conversations.single().personaId, copied.revisions.single().source.declaredPersonaId)
        assertNull(copied.conversations.single().activeContextRevisionId)
        assertTrue(result.disabledRevisions.isNotEmpty())
    }

    @Test fun `缺必要引用重复主键父版本循环在规划前拒绝`() {
        val original = data()
        reject { validateBackup(original.copy(messages = original.messages + original.messages.first())) }
        reject { validateBackup(original.copy(messages = original.messages.drop(1))) }
        reject { validateBackup(original.copy(calls = listOf(original.calls.single().copy(usageId = 999)))) }
        val revision = original.revisions.single()
        reject { validateBackup(original.copy(revisions = listOf(revision.copy(parentRevisionId = revision.id,
            source = revision.source.copy(parentRevisionId = revision.id))))) }
        reject { planBackupImport(archive(), BackupTargetIds(Long.MAX_VALUE, 0, 0)) }
        reject { planBackupImport(archive(), BackupTargetIds(0, 0, 0)) { "same-id" } }
        val withV2 = v2Data()
        reject { validateBackup(withV2.copy(conversationModelBindings = listOf(
            withV2.conversationModelBindings.single().copy(conversationId = "missing")
        ))) }
        reject { validateBackup(withV2.copy(callConfigSnapshots = listOf(
            withV2.callConfigSnapshots.single().copy(callId = "missing")
        ))) }
        reject { validateBackup(withV2.copy(callConfigSnapshots = listOf(
            withV2.callConfigSnapshots.single().copy(actualThinkingParametersJson = "not-json")
        ))) }
    }

    @Test fun `无人物卡的旧包不造对方并保留无法解析的待选归属`() {
        val original = BackupData(conversations = listOf(Conversation("empty-persona")))
        val copied = plan(archive(original)).data
        assertTrue(copied.personas.isEmpty())
        assertTrue(copied.conversations.single().personaId.startsWith("backup:"))
        assertNull(resolvePersona(copied.personas, copied.conversations.single().personaId))
    }

    @Test fun `多版本父链与摘要调用操作号一起映射到副本`() {
        val original = data()
        val parent = original.revisions.single()
        val child = parent.copy(id = "child", parentRevisionId = parent.id,
            source = parent.source.copy(parentRevisionId = parent.id),
            segments = parent.segments.map { it.copy(id = "child-segment") })
        val call = original.calls.single().record.copy(id = "summary-call", operationId = child.id, kind = CallKind.SUMMARY)
        val copied = plan(archive(original.copy(
            conversations = listOf(original.conversations.single().copy(activeContextRevisionId = child.id)),
            revisions = listOf(parent, child), calls = original.calls + BackupCall(call)))).data
        val importedChild = copied.revisions.single { it.parentRevisionId != null }
        val importedParent = copied.revisions.single { it.parentRevisionId == null }
        assertEquals(importedParent.id, importedChild.parentRevisionId)
        assertEquals(importedParent.id, importedChild.source.parentRevisionId)
        assertEquals(importedChild.id, copied.conversations.single().activeContextRevisionId)
        assertEquals(importedChild.id, copied.calls.single { it.record.kind == CallKind.SUMMARY }.record.operationId)
        copied.revisions.forEach(::validateSegments)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun `未知版本正文损坏数量不符与非法路径不能读入`() {
        val a = archive()
        val body = backupJson.encodeToString(a.data)
        fun packed(manifest: BackupManifest, text: String = body) = zip(
            "manifest.json" to Json.encodeToString(manifest), "data.json" to text).inputStream()
        reject { BackupCodec.read(packed(a.manifest.copy(version = 3))) }
        reject { BackupCodec.read(packed(a.manifest, body + " ")) }
        reject { BackupCodec.read(packed(a.manifest.copy(counts = emptyMap()))) }
        reject { BackupCodec.read(zip("../data.json" to body).inputStream()) }
        reject { BackupCodec.read(zip("data.json" to body).inputStream()) }
        reject { BackupCodec.read(zip("manifest.json" to " ".repeat(65537), "data.json" to body).inputStream()) }
    }

    @Test fun `v3按流保存去重原件并映射附件提交调用与有效摘要`() {
        val image = byteArrayOf(1, 2, 3, 4, 5)
        val sha = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
        val asset = BackupMediaAsset("asset", sha, "image/png", image.size.toLong(), 2, 2, 1, "media/$sha-${image.size}.bin")
        val original = data()
        val attachment = MessageAttachment(3, 0, "asset")
        val modelAsset = MediaAsset("asset", sha, "image/png", image.size.toLong(), 2, 2, 1, "objects/$sha", 1)
        val firstMessage = original.messages.first { it.id == 3L }
        val fingerprints = listOf(fingerprintWithAttachments(firstMessage, listOf(attachment to modelAsset)),
            fingerprintOf(original.messages.first { it.id == 8L }))
        val source = original.revisions.single().source.copy(messages = fingerprints)
        val revision = original.revisions.single().copy(source = source,
            segments = original.revisions.single().segments.map { it.copy(
                sourceChecksum = checksumOf(fingerprints.joinToString("|") { it.checksum })
            ) })
        val input = CallMediaInput("call", listOf(MediaContextReference(3, 0, "asset", sha,
            "png-2048-v1", sha, "image/png", 2, 2, MediaReferenceSource.NEW)), 123)
        val withMedia = original.copy(revisions = listOf(revision), media = BackupMediaData(
            assets = listOf(asset), attachments = listOf(attachment),
            submissions = listOf(BackupSubmission("submission", "c", "a".repeat(64), listOf(3), 123)),
            callInputs = listOf(BackupCallMediaInput("call", backupJson.encodeToString(input), 123))
        ))
        val output = ByteArrayOutputStream()
        val manifest = BackupCodec.writeV3(withMedia, output, 456, mapOf(asset.entryKey to { image.inputStream() }), "backup-v3")
        assertEquals(3, manifest.version)
        var restoredBytes = byteArrayOf()
        val packed = output.toByteArray()
        val archive = BackupCodec.read(packed.inputStream()) { descriptor, content ->
            assertEquals(asset.entryKey, descriptor.entryKey)
            restoredBytes = content.readBytes()
        }
        assertArrayEquals(image, restoredBytes)
        assertEquals("读取流的压缩包字节数包含 ZIP 中央目录", packed.size.toLong(), archive.compressedBytes)
        assertEquals(withMedia, archive.data)
        val plan = planBackupImport(archive, BackupTargetIds(20, 30, 40))
        assertTrue("含图摘要来源应在原包和新 ID 下继续有效", plan.disabledRevisions.isEmpty())
        val copied = plan.data
        assertEquals(copied.messages.first { it.text == "不喝咖啡" }.id, copied.media.attachments.single().messageId)
        assertNotEquals("asset", copied.media.attachments.single().mediaId)
        assertEquals(copied.media.attachments.single().mediaId, copied.media.assets.single().id)
        assertEquals(copied.conversations.single().id, copied.media.submissions.single().conversationId)
        val copiedCallInput = backupJson.decodeFromString<CallMediaInput>(copied.media.callInputs.single().inputJson)
        assertEquals(copied.calls.single().record.id, copiedCallInput.callId)
        assertEquals(copied.media.attachments.single().messageId, copiedCallInput.images.single().messageId)
        assertEquals(copied.media.attachments.single().mediaId, copiedCallInput.images.single().mediaId)
        val reused = planBackupImport(archive, BackupTargetIds(40, 50, 60),
            mediaIdMapping = mapOf("asset" to "existing-local-asset"))
        assertEquals("existing-local-asset", reused.data.media.attachments.single().mediaId)
        assertTrue(reused.disabledRevisions.isEmpty())
    }

    @Test fun `P3复核 - v3已存在消息的调用图片来源必须对应真实附件`() {
        val sha = "a".repeat(64)
        val asset = BackupMediaAsset("asset", sha, "image/png", 3, 1, 3, 1, "media/$sha-3.bin")
        val input = CallMediaInput("call", listOf(MediaContextReference(3, 9, "asset", "b".repeat(64),
            "png-2048-v1", sha, "image/png", 1, 3, MediaReferenceSource.NEW)), 123)
        val original = data().copy(media = BackupMediaData(assets = listOf(asset),
            attachments = listOf(MessageAttachment(3, 0, "asset")),
            callInputs = listOf(BackupCallMediaInput("call", backupJson.encodeToString(input), 123))))
        reject { validateBackup(original) }
    }

    @Test fun `P3复核 - 媒体消费者读满声明长度也不能跳过条目尾部校验`() {
        val image = byteArrayOf(9, 8, 7)
        val sha = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
        val asset = BackupMediaAsset("asset", sha, "image/png", 3, 1, 3, 1, "media/$sha-3.bin")
        val original = data().copy(media = BackupMediaData(assets = listOf(asset),
            attachments = listOf(MessageAttachment(3, 0, "asset"))))
        val body = backupJson.encodeToString(original)
        val manifest = BackupManifest(version = 3, id = "extra-media-tail", createdAt = 1,
            dataChecksum = checksumOf(body), counts = original.countsV3())
        val packed = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { stream ->
                listOf("manifest.json" to backupJson.encodeToString(manifest).toByteArray(),
                    "data.json" to body.toByteArray(), asset.entryKey to (image + byteArrayOf(6, 5, 4))).forEach { (name, bytes) ->
                    stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
                }
            }
        }.toByteArray()
        reject {
            BackupCodec.read(packed.inputStream()) { _, media ->
                repeat(image.size) { assertTrue(media.read() >= 0) }
            }
        }
    }

    @Test fun `v3拒绝缺失媒体未知条目和展开内容摘要不符`() {
        val image = byteArrayOf(9, 8, 7)
        val sha = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
        val asset = BackupMediaAsset("asset", sha, "image/png", image.size.toLong(), 1, 3, 1, "media/$sha-${image.size}.bin")
        val original = data().copy(media = BackupMediaData(assets = listOf(asset), attachments = listOf(MessageAttachment(3, 0, "asset"))))
        val out = ByteArrayOutputStream()
        val manifest = BackupCodec.writeV3(original, out, 1, mapOf(asset.entryKey to { image.inputStream() }), "v3")
        var readCount = 0
        BackupCodec.read(out.toByteArray().inputStream()) { _, body -> readCount += body.readBytes().size }
        assertEquals(image.size, readCount)
        val dataText = backupJson.encodeToString(original)
        val dataBytes = dataText.toByteArray()
        val badDigestZip = ByteArrayOutputStream().also { zip ->
            ZipOutputStream(zip).use { stream ->
                listOf("manifest.json" to backupJson.encodeToString(manifest).toByteArray(), "data.json" to dataBytes,
                    asset.entryKey to byteArrayOf(0, 0, 0)).forEach { (name, bytes) ->
                    stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
                }
            }
        }.toByteArray()
        reject { BackupCodec.read(badDigestZip.inputStream()) { _, body -> body.copyTo(ByteArrayOutputStream()) } }
        val missingZip = zip("manifest.json" to backupJson.encodeToString(manifest), "data.json" to dataText)
        reject { BackupCodec.read(missingZip.inputStream()) }
        val unknownZip = ByteArrayOutputStream().also { zip ->
            ZipOutputStream(zip).use { stream ->
                listOf("manifest.json" to backupJson.encodeToString(manifest).toByteArray(), "data.json" to dataBytes,
                    "media/../escape.bin" to image).forEach { (name, bytes) ->
                    stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
                }
            }
        }.toByteArray()
        reject { BackupCodec.read(unknownZip.inputStream()) { _, body -> body.copyTo(ByteArrayOutputStream()) } }
    }
}
