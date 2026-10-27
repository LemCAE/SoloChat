package app.solochat.core.backup

import app.solochat.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class BackupV4StickerTest {
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
}
