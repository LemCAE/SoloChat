package app.solochat.core.model

/** 只有确认文件由当前导入创建、数据库核验成功且未发布、内容仍匹配时才允许清理。 */
fun mayDeleteUnpublishedMedia(
    createdByThisImport: Boolean,
    publicationLookup: Result<MediaAsset?>,
    fileStillMatches: Result<Boolean>
): Boolean = createdByThisImport && publicationLookup.isSuccess &&
    publicationLookup.getOrNull() == null && fileStillMatches.getOrDefault(false)
