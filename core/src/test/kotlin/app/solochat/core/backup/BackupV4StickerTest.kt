package app.solochat.core.backup

import app.solochat.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class BackupV4StickerTest {
    @Test fun `P4复核 - 人物移出收藏后仍可备份过去调用的图片来源`() {
        val asset = reviewAsset()
        val removed = PersonaSticker("persona-sticker-source", "persona", asset.sha256, asset.byteSize,
            mediaId = null, status = PersonaStickerStatus.REMOVED, collectionEpoch = 1,
            createdAt = 1, updatedAt = 3)
        val source = BackupCallMediaSource(BackupCallMediaSource.Kind.PERSONA_STICKER,
            personaStickerId = removed.id, collectionEpoch = 0, mediaId = asset.id, sha256 = asset.sha256,
            byteSize = asset.byteSize, variant = "png-2048-v1", variantSha256 = "b".repeat(64), mimeType = "image/png", width = 2, height = 2)
        val data = BackupData(
            conversations = listOf(Conversation("conversation", title = "TEST-P4", personaId = "persona")),
            personas = listOf(Persona("persona", "TEST-P4")),
            calls = listOf(reviewCall()),
            media = BackupMediaData(assets = listOf(asset), typedCallInputs = listOf(
                BackupTypedCallMediaInput("call", listOf(source), 2))),
            stickers = BackupStickerData(userStickers = listOf(UserSticker("user-sticker-source", asset.id, 1, 0)),
                personaStickers = listOf(removed))
        )
        // 原件仍由我的收藏保存；历史调用使用当时有效的代次，不要求今天仍是人物有效收藏。
        validateBackup(data)
    }

    @Test fun `P4复核 - 类型化图片来源不能引用另一会话的用户附件`() {
        val asset = reviewAsset()
        val source = BackupCallMediaSource(BackupCallMediaSource.Kind.MESSAGE, messageId = 7, ordinal = 0,
            mediaId = asset.id, sha256 = asset.sha256, variant = "png-2048-v1",
            byteSize = asset.byteSize, variantSha256 = "b".repeat(64), mimeType = "image/png", width = 2, height = 2)
        val data = BackupData(
            conversations = listOf(Conversation("conversation", title = "TEST-P4-A", personaId = "persona"),
                Conversation("other", title = "TEST-P4-B", personaId = "persona")),
            personas = listOf(Persona("persona", "TEST-P4")),
            messages = listOf(Message(7, "other", MessageRole.USER, "", sentToModel = true)),
            calls = listOf(reviewCall()),
            media = BackupMediaData(assets = listOf(asset), attachments = listOf(MessageAttachment(7, 0, asset.id)),
                typedCallInputs = listOf(BackupTypedCallMediaInput("call", listOf(source), 2)))
        )
        assertTrue("调用属于 conversation，不能把 other 的图片当作自己的输入", runCatching { validateBackup(data) }.isFailure)
    }

    @Test fun `P4二审 - 恢复副本改绑人物保留稳定隔离的历史表情目标`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val asset = BackupMediaAsset("asset", sha, "image/png", bytes.size.toLong(), 2, 2, 0,
            "media/$sha-${bytes.size}.bin")
        val historicalTarget = "backup:origin-import:missing:old-persona"
        val replacement = Persona("replacement-persona", "新人物")
        val message = Message(7, "conversation", MessageRole.USER, "表情原文", createdAt = 1, sentToModel = true)
        val attachment = MessageAttachment(7, 0, asset.id)
        val sticker = MessageSticker(7, 0, historicalTarget, asset.sha256, asset.byteSize,
            0, "user-sticker-source", "submission")
        val fingerprint = fingerprintWithStickers(message, listOf(attachment to asset.asModel()), listOf(sticker))
        val source = CompressionSource("conversation", replacement.id, personaChecksum(listOf(replacement), replacement.id),
            null, ChatMode.CHAT, listOf(fingerprint), emptyList())
        val revision = ContextRevision("revision", "conversation", replacement.id, null, CompressionMethod.ORIGINAL,
            source, listOf(ContextSegment("segment", 7, 7, checksumOf(fingerprint.checksum), "已整理摘要", "model")),
            2, state = "COMPLETE")
        val data = BackupData(
            conversations = listOf(Conversation("conversation", title = "TEST-P4", personaId = replacement.id,
                activeContextRevisionId = revision.id)),
            personas = listOf(replacement), messages = listOf(message), revisions = listOf(revision),
            media = BackupMediaData(assets = listOf(asset), attachments = listOf(attachment)),
            stickers = BackupStickerData(
                userStickers = listOf(UserSticker("user-sticker-source", asset.id, 1, 0)),
                messageStickers = listOf(sticker)
            )
        )

        validateBackup(data)
        val firstZip = ByteArrayOutputStream()
        BackupCodec.writeV4(data, firstZip, 2, mapOf(asset.entryKey to { bytes.inputStream() }), "restored-copy")
        val firstArchive = BackupCodec.read(ByteArrayInputStream(firstZip.toByteArray())) { _, input -> input.readBytes() }
        val firstImport = planBackupImport(firstArchive, BackupTargetIds(10, 10, 0), operationImportId = "first-import")
        assertTrue(firstImport.disabledRevisions.isEmpty())
        assertEquals(historicalTarget, firstImport.data.stickers.messageStickers.single().personaId)
        val firstSourceFingerprint = firstImport.data.revisions.single().source.messages.single()
        val mappedFingerprint = fingerprintWithStickers(firstImport.data.messages.single(),
            listOf(firstImport.data.media.attachments.single() to firstImport.data.media.assets.single().asModel()),
            firstImport.data.stickers.messageStickers)
        assertEquals(firstSourceFingerprint, mappedFingerprint)
        assertNotEquals(data.conversations.single().personaId, data.stickers.messageStickers.single().personaId)

        val secondZip = ByteArrayOutputStream()
        val importedAsset = firstImport.data.media.assets.single()
        BackupCodec.writeV4(firstImport.data, secondZip, 3,
            mapOf(importedAsset.entryKey to { bytes.inputStream() }), "restored-copy-again")
        val secondArchive = BackupCodec.read(ByteArrayInputStream(secondZip.toByteArray())) { _, input -> input.readBytes() }
        val secondImport = planBackupImport(secondArchive, BackupTargetIds(20, 20, 0), operationImportId = "second-import")
        assertTrue("摘要指纹在第二次恢复时仍有效", secondImport.disabledRevisions.isEmpty())
        assertEquals(historicalTarget, secondImport.data.stickers.messageStickers.single().personaId)
    }

    @Test fun `P4二审 - 两个有效人物间的表情目标错配仍被拒绝`() {
        val asset = reviewAsset()
        val data = BackupData(
            conversations = listOf(Conversation("conversation", title = "TEST-P4", personaId = "current-persona")),
            personas = listOf(Persona("current-persona", "当前人物"), Persona("other-persona", "其他人物")),
            messages = listOf(Message(7, "conversation", MessageRole.USER, "", sentToModel = true)),
            media = BackupMediaData(assets = listOf(asset), attachments = listOf(MessageAttachment(7, 0, asset.id))),
            stickers = BackupStickerData(
                userStickers = listOf(UserSticker("user-sticker-source", asset.id, 1, 0)),
                messageStickers = listOf(MessageSticker(7, 0, "other-persona", asset.sha256, asset.byteSize,
                    0, "user-sticker-source", "submission"))
            )
        )
        assertTrue(runCatching { validateBackup(data) }.isFailure)
        assertTrue("无效的 backup 前缀不是历史隔离凭证",
            runCatching { validateBackup(data.copy(stickers = data.stickers.copy(messageStickers =
                data.stickers.messageStickers.map { it.copy(personaId = "backup:unverified") }))) }.isFailure)
    }

    private fun reviewAsset() = BackupMediaAsset("asset", "a".repeat(64), "image/png", 4, 2, 2, 0,
        "media/${"a".repeat(64)}-4.bin")

    private fun reviewCall() = BackupCall(CallRecord("call", conversationId = "conversation", model = "test",
        status = CallStatus.SUCCESS, startedAt = 1, finishedAt = 2, price = ModelPrice(0.0, 0.0), inputCharacters = 0))

    private fun BackupMediaAsset.asModel() = MediaAsset(id, sha256, mimeType, byteSize, width, height,
        orientation, "objects/$sha256", createdAt = 1)

    @Test fun v4RoundTripCarriesUnsentUserStickerAndImportRemapsItsIdentity() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val asset = BackupMediaAsset("asset", sha, "image/png", bytes.size.toLong(), 2, 2, 0,
            "media/$sha-${bytes.size}.bin")
        val data = BackupData(
            conversations = listOf(Conversation("conversation", title = "TEST-P4", personaId = "persona")),
            messages = listOf(Message(3, "conversation", MessageRole.USER, "", turnId = 5, sentToModel = false)),
            personas = listOf(Persona("persona", "TEST-P4")),
            media = BackupMediaData(assets = listOf(asset), attachments = listOf(MessageAttachment(3, 0, "asset"))),
            stickers = BackupStickerData(
                userStickers = listOf(UserSticker("user-sticker-source", "asset", 4, 0)),
                messageStickers = listOf(MessageSticker(3, 0, "persona", sha, bytes.size.toLong(), 0,
                    "user-sticker-source", "submission"))
            )
        )
        val zip = ByteArrayOutputStream()
        BackupCodec.writeV4(data, zip, 9, mapOf(asset.entryKey to { bytes.inputStream() }), "v4-test")
        val restoredBytes = mutableListOf<ByteArray>()
        val archive = BackupCodec.read(ByteArrayInputStream(zip.toByteArray())) { _, input -> restoredBytes += input.readBytes() }
        assertEquals(4, archive.manifest.version)
        assertEquals(data, archive.data)
        assertEquals(bytes.toList(), restoredBytes.single().toList())
        assertEquals(data.countsV4(), archive.manifest.counts)

        val plan = planBackupImport(archive, BackupTargetIds(10, 10, 0), operationImportId = "import-operation")
        assertNotEquals("user-sticker-source", plan.data.stickers.userStickers.single().id)
        assertEquals(plan.data.stickers.userStickers.single().id,
            plan.data.stickers.messageStickers.single().sourceUserStickerId)
        assertEquals(plan.data.media.assets.single().id, plan.data.stickers.userStickers.single().mediaId)
        assertEquals(false, plan.data.messages.single().sentToModel)
    }

    @Test fun v4RoundTripIncludesUserLibraryAssetsWithoutAnyMessages() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val asset = BackupMediaAsset("library-only", sha, "image/png", bytes.size.toLong(), 2, 2, 0,
            "media/$sha-${bytes.size}.bin")
        val data = BackupData(stickers = BackupStickerData(userStickers = listOf(UserSticker("user-sticker-only", asset.id, 1, 0))),
            media = BackupMediaData(assets = listOf(asset)))
        val zip = ByteArrayOutputStream()
        BackupCodec.writeV4(data, zip, 1, mapOf(asset.entryKey to { bytes.inputStream() }), "library-only")
        val captured = mutableListOf<ByteArray>()
        val archive = BackupCodec.read(ByteArrayInputStream(zip.toByteArray())) { _, input -> captured += input.readBytes() }

        assertEquals(data, archive.data)
        assertEquals(bytes.toList(), captured.single().toList())
        val imported = planBackupImport(archive, BackupTargetIds(0, 0, 0))
        assertNotEquals("user-sticker-only", imported.data.stickers.userStickers.single().id)
        validateBackup(imported.data)
    }

    @Test fun deletedTypedCallSourcesStayIsolatedAfterImportAndAnotherBackupRoundTrip() {
        val missingMessage = BackupCallMediaSource(BackupCallMediaSource.Kind.MESSAGE, messageId = -7, ordinal = 0,
            mediaId = "missing:old-call:message", sha256 = "a".repeat(64), byteSize = 20, variant = "png-2048-v1",
            variantSha256 = "b".repeat(64), mimeType = "image/png", width = 2, height = 2)
        val missingSticker = BackupCallMediaSource(BackupCallMediaSource.Kind.PERSONA_STICKER,
            personaStickerId = "backup:old-import:missing:persona-sticker", collectionEpoch = 3,
            mediaId = "missing:old-call:sticker", sha256 = "c".repeat(64), byteSize = 30, variant = "png-2048-v1",
            variantSha256 = "d".repeat(64), mimeType = "image/png", width = 2, height = 2)
        val data = BackupData(conversations = listOf(Conversation("conversation", title = "TEST-P4", personaId = "persona")),
            personas = listOf(Persona("persona", "TEST-P4")), calls = listOf(reviewCall()),
            media = BackupMediaData(typedCallInputs = listOf(BackupTypedCallMediaInput("call", listOf(missingMessage, missingSticker), 2))))
        val firstZip = ByteArrayOutputStream()
        BackupCodec.writeV4(data, firstZip, 1, emptyMap(), "missing-sources")
        val first = BackupCodec.read(ByteArrayInputStream(firstZip.toByteArray()))
        val imported = planBackupImport(first, BackupTargetIds(10, 10, 0))
        val copiedSources = imported.data.media.typedCallInputs.single().sources
        assertEquals(-7L, copiedSources.first { it.kind == BackupCallMediaSource.Kind.MESSAGE }.messageId)
        assertTrue(copiedSources.all { it.mediaId.startsWith("missing:") })
        assertNotEquals("backup:old-import:missing:persona-sticker", copiedSources.first { it.kind == BackupCallMediaSource.Kind.PERSONA_STICKER }.personaStickerId)
        validateBackup(imported.data)

        val secondZip = ByteArrayOutputStream()
        BackupCodec.writeV4(imported.data, secondZip, 2, emptyMap(), "re-export")
        val second = BackupCodec.read(ByteArrayInputStream(secondZip.toByteArray()))
        validateBackup(second.data)
        assertEquals(copiedSources, second.data.media.typedCallInputs.single().sources)
    }
}
