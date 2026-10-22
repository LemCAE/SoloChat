package app.solochat.core.backup

import app.solochat.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        assertEquals(original.counts(), restored.manifest.counts)
        assertEquals(BACKUP_EXCLUDED, restored.manifest.excluded)
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

    @Test fun `默认归属会话导入后固定到备份中的人物卡副本`() {
        val original = data()
        val c = original.conversations.single().copy(personaId = "")
        val r = original.revisions.single()
        val source = r.source.copy(declaredPersonaId = "", personaChecksum = personaChecksum(original.personas, ""))
        val copied = plan(archive(original.copy(conversations = listOf(c), revisions = listOf(r.copy(source = source))))).data
        assertEquals(copied.personas.single().id, copied.conversations.single().personaId)
        assertEquals(copied.personas.single().id, copied.revisions.single().source.declaredPersonaId)
        assertNotNull(copied.conversations.single().activeContextRevisionId)
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
    }

    @Test fun `无人物卡的旧包使用独立兜底副本而不借用本机人物卡`() {
        val original = BackupData(conversations = listOf(Conversation("empty-persona")))
        val copied = plan(archive(original)).data
        assertEquals("对方", copied.personas.single().name)
        assertEquals(copied.personas.single().id, copied.conversations.single().personaId)
        assertFalse(copied.personas.single().builtin)
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
        reject { BackupCodec.read(packed(a.manifest.copy(version = 2))) }
        reject { BackupCodec.read(packed(a.manifest, body + " ")) }
        reject { BackupCodec.read(packed(a.manifest.copy(counts = emptyMap()))) }
        reject { BackupCodec.read(zip("../data.json" to body).inputStream()) }
        reject { BackupCodec.read(zip("data.json" to body).inputStream()) }
        reject { BackupCodec.read(zip("manifest.json" to " ".repeat(65537), "data.json" to body).inputStream()) }
    }
}
