package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerContractTest {
    @Test fun optionalStickerIntentLeavesP3SubmissionChecksumUnchanged() {
        val old = InputSubmission("submission", "conversation", listOf(SubmissionText("say", "hi")), listOf("media"), 1L)
        assertEquals(old.payloadChecksum, old.copy(stickerIntent = null).payloadChecksum)
        val stickerA = old.copy(text = emptyList(), stickerIntent = StickerSubmissionIntent("user-sticker-a", "persona-a"))
        val stickerB = stickerA.copy(stickerIntent = StickerSubmissionIntent("user-sticker-a", "persona-b"))
        assertNotEquals(stickerA.payloadChecksum, stickerB.payloadChecksum)
    }

    @Test fun stickerIdentityAndRemovalEpochAreSeparatePerPersona() {
        val first = personaSticker("persona-sticker-one", "persona-one")
        val other = personaSticker("persona-sticker-two", "persona-two")
        assertNotEquals(first.id, other.id)
        assertNotEquals(first.personaId, other.personaId)
        assertEquals(0L, first.collectionEpoch)
        val removed = first.copy(status = PersonaStickerStatus.REMOVED, mediaId = null, collectionEpoch = 1)
        assertEquals(1L, removed.collectionEpoch)
        assertEquals("a".repeat(64), removed.sha256)
    }

    @Test fun callScopeRejectsCrossPersonaSourcesAndInactiveCandidates() {
        val messageSticker = MessageSticker(7, 0, "persona-a", "a".repeat(64), 10, 0,
            "user-sticker-a", "submission-a")
        val valid = StickerCallScope("call", "conversation", "persona-a", listOf(7), listOf(messageSticker),
            listOf(personaSticker("persona-sticker-a", "persona-a")), 1)
        assertTrue(valid.collectibleSources.single().messageId in valid.inputMessageIds)
        assertTrue(runCatching {
            valid.copy(personaId = "persona-b")
        }.isFailure)
        assertTrue(runCatching {
            valid.copy(candidates = listOf(personaSticker("persona-sticker-removed", "persona-a")
                .copy(status = PersonaStickerStatus.REMOVED, mediaId = null)))
        }.isFailure)
    }

    @Test fun typedMediaSourceUsesStickerIdentityWithoutFakeMessageId() {
        val source = BackupCallMediaSource(BackupCallMediaSource.Kind.PERSONA_STICKER,
            personaStickerId = "persona-sticker-a", collectionEpoch = 3, mediaId = "asset",
            sha256 = "a".repeat(64), variant = "png-2048-v1", variantSha256 = "b".repeat(64),
            mimeType = "image/png", width = 20, height = 30)
        assertEquals(null, source.messageId)
        assertEquals(3L, source.collectionEpoch)
        assertTrue(runCatching { source.copy(personaStickerId = null) }.isFailure)
    }

    private fun personaSticker(id: String, personaId: String) = PersonaSticker(
        id = id, personaId = personaId, sha256 = "a".repeat(64), byteSize = 10,
        mediaId = "asset-$personaId", createdAt = 1, updatedAt = 1
    )
}
