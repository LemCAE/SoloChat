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
}
