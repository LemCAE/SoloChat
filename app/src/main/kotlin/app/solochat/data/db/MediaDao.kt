package app.solochat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDao {
    @Query("SELECT COUNT(*) FROM message_attachment")
    suspend fun attachmentCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(entity: MediaAssetEntity)

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

    @Query("SELECT * FROM call_media_input WHERE callId = :callId")
    suspend fun callInput(callId: String): CallMediaInputEntity?
}
