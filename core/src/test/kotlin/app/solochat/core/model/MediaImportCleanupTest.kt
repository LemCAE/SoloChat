package app.solochat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImportCleanupTest {
    @Test fun `查询失败或取消时保留提升后的文件`() {
        val canceled = Result.failure<MediaAsset?>(kotlinx.coroutines.CancellationException("canceled"))
        assertFalse(mayDeleteUnpublishedMedia(true, canceled, Result.success(true)))
        assertFalse(mayDeleteUnpublishedMedia(true, Result.failure(IllegalStateException("db")), Result.success(true)))
    }

    @Test fun `已发布共享对象或内容变化时不能删除`() {
        val asset = MediaAsset("a", "a".repeat(64), "image/png", 10, 2, 2, fileKey = "objects/a", createdAt = 1)
        assertFalse(mayDeleteUnpublishedMedia(true, Result.success(asset), Result.success(true)))
        assertFalse(mayDeleteUnpublishedMedia(true, Result.success(null), Result.failure(IllegalStateException("hash"))))
        assertFalse(mayDeleteUnpublishedMedia(false, Result.success(null), Result.success(true)))
    }

    @Test fun `仅当前操作新建且已确认未发布的完整文件可以删除`() {
        assertTrue(mayDeleteUnpublishedMedia(true, Result.success(null), Result.success(true)))
    }
}
