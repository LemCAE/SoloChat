package app.solochat.data.backup

/** 旧 ZIP 只允许读取无消息附件的快照；计数检查和全部快照读取必须由同一事务包住。 */
internal suspend fun <T> readLegacyBackupSnapshot(
    transaction: suspend (suspend () -> T) -> T,
    attachmentCount: suspend () -> Int,
    readRows: suspend () -> T
): T = transaction {
    check(attachmentCount() == 0) {
        "当前备份格式不能包含图片；图片已保留在本机，请等待媒体备份支持后再导出。"
    }
    readRows()
}
