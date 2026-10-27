package app.solochat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDao {
    @Query("SELECT COUNT(*) FROM message_attachment")
    suspend fun attachmentCount(): Int

    @Query("SELECT * FROM media_asset ORDER BY id") suspend fun allAssets(): List<MediaAssetEntity>
    @Query("SELECT DISTINCT media_asset.* FROM media_asset INNER JOIN message_attachment ON message_attachment.mediaId = media_asset.id ORDER BY media_asset.id")
    suspend fun messageAssets(): List<MediaAssetEntity>
    @Query("SELECT * FROM message_attachment ORDER BY messageId, ordinal") suspend fun allAttachments(): List<MessageAttachmentEntity>
    @Query("SELECT * FROM input_submission ORDER BY id") suspend fun allSubmissions(): List<InputSubmissionEntity>
    @Query("SELECT * FROM call_media_input ORDER BY callId") suspend fun allCallInputs(): List<CallMediaInputEntity>
    @Query("SELECT * FROM typed_call_media_input ORDER BY callId") suspend fun allTypedCallInputs(): List<TypedCallMediaInputEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(entity: MediaAssetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssets(entities: List<MediaAssetEntity>)

    @Query("SELECT * FROM media_asset WHERE sha256 = :sha256 AND byteSize = :byteSize LIMIT 1")
    suspend fun assetByDigest(sha256: String, byteSize: Long): MediaAssetEntity?

    @Query("SELECT * FROM media_asset WHERE id = :id")
    suspend fun asset(id: String): MediaAssetEntity?

    @Query("SELECT * FROM media_asset WHERE id IN (:ids)")
    suspend fun assets(ids: List<String>): List<MediaAssetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachments(rows: List<MessageAttachmentEntity>)

    @Query("SELECT * FROM message_attachment WHERE messageId IN (:messageIds) ORDER BY messageId, ordinal")
    suspend fun attachments(messageIds: List<Long>): List<MessageAttachmentEntity>

    @Query("DELETE FROM message_attachment WHERE messageId IN (SELECT id FROM message WHERE conversationId = :conversationId)")
    suspend fun deleteAttachments(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmission(row: InputSubmissionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmissions(rows: List<InputSubmissionEntity>)

    @Query("SELECT * FROM input_submission WHERE id = :id")
    suspend fun submission(id: String): InputSubmissionEntity?

    @Query("DELETE FROM input_submission WHERE conversationId = :conversationId")
    suspend fun deleteSubmissions(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(row: MediaDraftEntity)

    @Query("SELECT * FROM media_draft WHERE conversationId = :conversationId")
    suspend fun draft(conversationId: String): MediaDraftEntity?

    @Query("DELETE FROM media_draft WHERE conversationId = :conversationId")
    suspend fun clearDraft(conversationId: String)


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCallInput(row: CallMediaInputEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCallInputs(rows: List<CallMediaInputEntity>)

    @Query("SELECT * FROM call_media_input WHERE callId = :callId")
    suspend fun callInput(callId: String): CallMediaInputEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTypedCallInput(row: TypedCallMediaInputEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTypedCallInputs(rows: List<TypedCallMediaInputEntity>)
    @Query("SELECT * FROM typed_call_media_input WHERE callId = :callId") suspend fun typedCallInput(callId: String): TypedCallMediaInputEntity?
}

@Dao
interface StickerDao {
    @Query("SELECT * FROM p4_test_subject WHERE requestId = :requestId") suspend fun testSubject(requestId: String): P4TestSubjectEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTestSubject(row: P4TestSubjectEntity)
    @Query("SELECT COUNT(*) FROM user_sticker") suspend fun userStickerCount(): Int
    @Query("SELECT (SELECT COUNT(*) FROM user_sticker) + (SELECT COUNT(*) FROM persona_sticker) + (SELECT COUNT(*) FROM message_sticker) + (SELECT COUNT(*) FROM event_sticker) + (SELECT COUNT(*) FROM sticker_turn_result) + (SELECT COUNT(*) FROM typed_call_media_input)") suspend fun totalStickerCount(): Int
    @Query("SELECT * FROM user_sticker ORDER BY sortOrder, id") suspend fun userStickers(): List<UserStickerEntity>
    @Query("SELECT * FROM user_sticker WHERE id = :id") suspend fun userSticker(id: String): UserStickerEntity?
    @Query("SELECT * FROM user_sticker WHERE mediaId = :mediaId") suspend fun userStickerByMedia(mediaId: String): UserStickerEntity?
    @Query("SELECT IFNULL(MAX(sortOrder), -1) + 1 FROM user_sticker") suspend fun nextUserSortOrder(): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertUserSticker(row: UserStickerEntity)
    @Query("DELETE FROM user_sticker WHERE id = :id") suspend fun deleteUserSticker(id: String): Int
    @Query("UPDATE user_sticker SET sortOrder = :order WHERE id = :id") suspend fun reorderUserSticker(id: String, order: Long): Int

    @Query("SELECT * FROM persona_sticker WHERE personaId = :personaId ORDER BY status, createdAt, id") suspend fun personaStickers(personaId: String): List<PersonaStickerEntity>
    @Query("SELECT * FROM persona_sticker WHERE id = :id") suspend fun personaSticker(id: String): PersonaStickerEntity?
    @Query("SELECT * FROM persona_sticker WHERE personaId = :personaId AND sha256 = :sha256 AND byteSize = :byteSize") suspend fun personaStickerByContent(personaId: String, sha256: String, byteSize: Long): PersonaStickerEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPersonaSticker(row: PersonaStickerEntity)
    @androidx.room.Update suspend fun updatePersonaSticker(row: PersonaStickerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMessageSticker(row: MessageStickerEntity)
    @Query("SELECT * FROM message_sticker WHERE messageId IN (:messageIds) ORDER BY messageId, ordinal") suspend fun messageStickers(messageIds: List<Long>): List<MessageStickerEntity>
    @Query("SELECT * FROM message_sticker WHERE messageId = :messageId AND ordinal = :ordinal") suspend fun messageSticker(messageId: Long, ordinal: Int): MessageStickerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEventSticker(row: EventStickerEntity)
    @Query("SELECT * FROM event_sticker WHERE eventId = :eventId") suspend fun eventSticker(eventId: Long): EventStickerEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTurnResult(row: StickerTurnResultEntity)
    @Query("SELECT * FROM sticker_turn_result WHERE callId = :callId") suspend fun turnResult(callId: String): StickerTurnResultEntity?
}
