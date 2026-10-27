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

    private fun reviewAsset() = BackupMediaAsset("asset", "a".repeat(64), "image/png", 4, 2, 2, 0,
        "media/${"a".repeat(64)}-4.bin")

    private fun reviewCall() = BackupCall(CallRecord("call", conversationId = "conversation", model = "test",
        status = CallStatus.SUCCESS, startedAt = 1, finishedAt = 2, price = ModelPrice(0.0, 0.0), inputCharacters = 0))

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
