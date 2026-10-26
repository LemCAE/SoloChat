package app.solochat.core.model

/** UI 与历史引用共用的单会话草稿状态。持久化仍由 StoragePort 完成。 */
data class MediaDraftSessionState(
    val loaded: Boolean = false,
    val draft: MediaDraft? = null,
    val assets: List<MediaAsset> = emptyList(),
    val importing: Boolean = false,
    val missingMediaIds: List<String> = emptyList(),
    val inFlightSubmissionIds: Set<String> = emptySet(),
    val saveError: String? = null
)

class MediaDraftSession(
    private val conversationId: String,
    private val newId: () -> String
) {
    private var current = MediaDraftSessionState()
    private var lastSubmissionPayload: Pair<List<SubmissionText>, List<String>>? = null
    private val completedSubmissionIds = linkedSetOf<String>()

    @Synchronized fun state(): MediaDraftSessionState = current

    @Synchronized fun restore(draft: MediaDraft?, assets: List<MediaAsset>, missingMediaIds: List<String> = emptyList()): MediaDraftSessionState {
        require(!current.loaded) { "草稿已加载" }
        val restored = draft?.takeIf { it.conversationId == conversationId } ?: emptyDraft(0, newId(), 0)
        val identified = if (restored.submissionId.isNotBlank()) restored else
            restored.copy(version = restored.version + 1, submissionId = newId())
        lastSubmissionPayload = identified.text to identified.mediaIds
        return publish(current.copy(loaded = true, draft = identified, assets = assets.distinctBy { it.id }, missingMediaIds = missingMediaIds.distinct()))
    }

    @Synchronized fun updateText(text: List<SubmissionText>, now: Long): MediaDraftSessionState = updateDraft(now) { it.copy(text = text.toList()) }

    @Synchronized fun removeMedia(mediaId: String, now: Long): MediaDraftSessionState = updateDraft(now) {
        it.copy(mediaIds = it.mediaIds.filterNot { id -> id == mediaId })
    }.let { state -> publish(state.copy(assets = state.assets.filterNot { asset -> asset.id == mediaId }, missingMediaIds = state.missingMediaIds - mediaId)) }

    @Synchronized fun clearMissing(now: Long): MediaDraftSessionState {
        val missing = current.missingMediaIds.toSet()
        return updateDraft(now) { it.copy(mediaIds = it.mediaIds - missing) }.let { state ->
            publish(state.copy(assets = state.assets.filterNot { asset -> asset.id in missing }, missingMediaIds = emptyList()))
        }
    }

    @Synchronized fun beginImport(): Boolean {
        if (!current.loaded || current.importing || current.missingMediaIds.isNotEmpty()) return false
        current = current.copy(importing = true)
        return true
    }

    @Synchronized fun finishImport(assets: List<MediaAsset>, now: Long): MediaDraftSessionState {
        check(current.importing) { "没有进行中的图片导入" }
        check(assets.isNotEmpty() && assets.size + draft().mediaIds.size <= MAX_SUBMISSION_IMAGES) { "一次最多附加 4 张图片" }
        val next = changed(draft().copy(mediaIds = draft().mediaIds + assets.map { it.id }), now)
        return publish(current.copy(draft = next, assets = (current.assets + assets).distinctBy { it.id }, importing = false))
    }

    @Synchronized fun cancelImport(): MediaDraftSessionState = publish(current.copy(importing = false))

    @Synchronized fun addReference(asset: MediaAsset, now: Long): MediaDraftSessionState {
        check(current.loaded && !current.importing) { "草稿尚未就绪" }
        if (asset.id in draft().mediaIds) return current
        check(draft().mediaIds.size < MAX_SUBMISSION_IMAGES) { "一次最多附加 4 张图片" }
        return publish(current.copy(draft = changed(draft().copy(mediaIds = draft().mediaIds + asset.id), now), assets = current.assets + asset))
    }

    /**
     * 在点击回调的同步边界捕获快照。版本不匹配说明回调已经过期，不能把旧内容写回当前草稿。
     * 当前编辑文本可在同一点击尚未经过 Compose effect 同步时补入；发送内容只参与提交身份，
     * 完整草稿仍保留所有模式的文本。
     */
    @Synchronized fun captureSubmission(
        expectedVersion: Long,
        completeText: List<SubmissionText>,
        submissionText: List<SubmissionText>,
        mediaIds: List<String>,
        now: Long
    ): MediaDraft {
        check(current.loaded && !current.importing && current.missingMediaIds.isEmpty()) { "图片草稿尚未就绪" }
        require(expectedVersion == draft().version) { "草稿已更新，请重新点击发送" }
        require(mediaIds.isNotEmpty() && mediaIds.size <= MAX_SUBMISSION_IMAGES) { "一次最多附加 4 张图片" }
        require(mediaIds == draft().mediaIds) { "图片草稿已更新，请重新点击发送" }
        require(completeText.size <= 3 && submissionText.size <= 3)
        require(submissionText.all { sent -> completeText.any { it.kind == sent.kind && it.text.trim() == sent.text.trim() } }) {
            "发送内容与草稿不一致，请重新点击发送"
        }
        val completeSnapshot = completeText.toList()
        val submittedSnapshot = submissionText.toList()
        val mediaSnapshot = mediaIds.toList()
        val payload = submittedSnapshot to mediaSnapshot
        val before = draft()
        val needsNewIdentity = completeSnapshot != before.text || payload != lastSubmissionPayload
        val snapshot = if (!needsNewIdentity) before else before.copy(
            text = completeSnapshot,
            mediaIds = mediaSnapshot,
            updatedAt = now,
            version = before.version + 1,
            submissionId = newId()
        )
        lastSubmissionPayload = payload
        if (snapshot !== before) publish(current.copy(draft = snapshot))
        return snapshot
    }

    @Synchronized fun beginSubmission(submissionId: String): Boolean {
        if (submissionId in completedSubmissionIds) return false
        publish(current.copy(inFlightSubmissionIds = current.inFlightSubmissionIds + submissionId))
        return true
    }

    /** 成功只清除仍与提交快照相同的草稿；提交过程中出现的新编辑会留下。 */
    @Synchronized fun finishSubmission(submissionId: String, success: Boolean, now: Long): MediaDraftSessionState {
        if (success) {
            completedSubmissionIds += submissionId
            if (completedSubmissionIds.size > 32) completedSubmissionIds.remove(completedSubmissionIds.first())
        }
        val pending = current.inFlightSubmissionIds - submissionId
        if (!success || current.draft?.submissionId != submissionId) return publish(current.copy(inFlightSubmissionIds = pending))
        val blank = emptyDraft(draft().version + 1, newId(), now)
        lastSubmissionPayload = blank.text to blank.mediaIds
        return publish(current.copy(draft = blank, assets = emptyList(), importing = false, missingMediaIds = emptyList(), inFlightSubmissionIds = pending))
    }

    private fun updateDraft(now: Long, transform: (MediaDraft) -> MediaDraft): MediaDraftSessionState {
        check(current.loaded) { "草稿尚未加载" }
        return publish(current.copy(draft = changed(transform(draft()), now)))
    }

    private fun changed(candidate: MediaDraft, now: Long): MediaDraft {
        val before = draft()
        if (candidate.text == before.text && candidate.mediaIds == before.mediaIds) return before
        val next = candidate.copy(updatedAt = now, version = before.version + 1, submissionId = newId())
        lastSubmissionPayload = next.text to next.mediaIds
        return next
    }

    private fun emptyDraft(version: Long, submissionId: String, now: Long) =
        MediaDraft(conversationId, emptyList(), emptyList(), now, version, submissionId)

    private fun draft(): MediaDraft = checkNotNull(current.draft) { "草稿尚未加载" }

    private fun publish(state: MediaDraftSessionState): MediaDraftSessionState {
        current = state
        return state
    }
}
