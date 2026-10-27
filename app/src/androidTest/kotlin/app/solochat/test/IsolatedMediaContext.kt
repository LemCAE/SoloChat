package app.solochat.test

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID

/** 合成 Room/媒体测试只写入本轮独占的应用缓存子目录。 */
class IsolatedMediaContext(base: Context, label: String) : ContextWrapper(base) {
    private val cacheRoot = base.cacheDir.canonicalFile
    val root = File(cacheRoot, "p3-acceptance-$label-${UUID.randomUUID()}")
    private val files = File(root, "files")
    private val cache = File(root, "cache")
    private val databases = File(root, "databases")

    init {
        check(root.canonicalFile.parentFile == cacheRoot && root.mkdirs()) { "无法创建本轮独占媒体测试目录" }
        check(files.mkdirs() && cache.mkdirs() && databases.mkdirs()) { "无法创建本轮测试子目录" }
    }

    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getDatabasePath(name: String): File = File(databases, name).also {
        require(it.canonicalFile.parentFile == databases.canonicalFile) { "测试数据库名称无效" }
    }
    override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()

    fun clean() {
        check(!root.exists() || root.deleteRecursively()) { "隔离媒体测试目录清理失败" }
    }
}
