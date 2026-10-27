package app.solochat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.solochat.core.model.MAX_MEDIA_PIXELS
import app.solochat.core.model.decodeSampleSize
import app.solochat.core.model.fitWithin
import app.solochat.core.model.thumbnailEncoding
import app.solochat.core.model.MediaAsset
import app.solochat.core.model.MediaVariant
import app.solochat.core.model.mayDeleteUnpublishedMedia
import app.solochat.core.model.detectStaticImageMime
import app.solochat.core.ports.MediaContentPort
import app.solochat.data.db.RoomStore
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.solochat.core.backup.BackupMediaAsset

/** 私有、不可变的消息媒体原件与有界可再生图片缓存。 */
class MediaLibrary(private val context: Context, private val store: RoomStore) : MediaContentPort {
    private val root = File(context.filesDir, "media")
    private val objects = File(root, "objects")
    private val thumbnails = File(context.cacheDir, "media-thumbnails")
    private val maxOriginalBytes = 32L * 1024 * 1024
    private val thumbnailSide = 512
    private val displaySide = 2048
    private val variants = File(context.cacheDir, "media-variants")
    private val importLock = Mutex()

    data class BackupPromotion(val asset: MediaAsset, val createdFile: File?)

    /** 序列化备份恢复与普通原件导入，防止同一摘要同时发布两条资源身份。 */
    suspend fun <T> withBackupImportLock(block: suspend () -> T): T = importLock.withLock { block() }

    /** 校验暂存原件并在 Room 可见前提升到不可变私有文件；本方法不发布数据库行。 */
    suspend fun promoteBackupAsset(descriptor: BackupMediaAsset, staged: File, targetId: String, now: Long): BackupPromotion {
        validateBackupStaged(descriptor, staged)
        val existing = store.mediaAssetByDigest(descriptor.sha256, descriptor.byteSize)
        if (existing != null) {
            val file = file(existing)
            check(existing.mimeType == descriptor.mimeType && existing.width == descriptor.width && existing.height == descriptor.height &&
                existing.orientation == descriptor.orientation) { "本机相同摘要图片资源异常，恢复已停止" }
            if (!file.exists()) return BackupPromotion(existing, copyVerifiedBackupFile(staged, file, descriptor))
            check(file.isFile && file.length() == descriptor.byteSize && sha256(file) == descriptor.sha256) {
                "本机相同摘要图片资源异常，恢复已停止"
            }
            return BackupPromotion(existing, null)
        }
        val key = "objects/${descriptor.sha256}"
        val destination = resolve(key)
        if (destination.exists()) {
            check(destination.isFile && destination.length() == descriptor.byteSize && sha256(destination) == descriptor.sha256) {
                "本机图片文件摘要冲突，恢复已停止"
            }
            return BackupPromotion(MediaAsset(targetId, descriptor.sha256, descriptor.mimeType, descriptor.byteSize,
                descriptor.width, descriptor.height, descriptor.orientation, key, now), null)
        }
        copyVerifiedBackupFile(staged, destination, descriptor)
        return BackupPromotion(MediaAsset(targetId, descriptor.sha256, descriptor.mimeType, descriptor.byteSize,
            descriptor.width, descriptor.height, descriptor.orientation, key, now), destination)
    }

    /** 复用普通导入的有界格式、方向及实际解码校验；预览和发布前都调用。 */
    fun validateBackupStaged(descriptor: BackupMediaAsset, staged: File) {
        require(staged.isFile && staged.length() == descriptor.byteSize && sha256(staged) == descriptor.sha256) {
            "恢复图片缺失、长度或摘要校验失败"
        }
        val type = inspect(staged)
        require(type.mime == descriptor.mimeType && type.width == descriptor.width && type.height == descriptor.height &&
            staged.length() <= maxOriginalBytes && descriptor.width.toLong() * descriptor.height <= MAX_MEDIA_PIXELS) {
            "恢复图片的真实类型或尺寸与清单不符"
        }
        val orientation = runCatching {
            ExifInterface(staged.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        require(orientation == descriptor.orientation) { "恢复图片方向与清单不符" }
        val decoded = decodeBounded(staged, thumbnailSide)
        try {
            check(decoded.width > 0 && decoded.height > 0) { "恢复图片实际解码失败" }
        } finally {
            decoded.recycle()
        }
    }

    private fun copyVerifiedBackupFile(staged: File, destination: File, descriptor: BackupMediaAsset): File {
        destination.parentFile?.mkdirs()
        check(!destination.exists()) { "目标媒体文件已存在，拒绝覆盖" }
        val temporary = File(destination.parentFile, ".${UUID.randomUUID()}.part")
        try {
            FileInputStream(staged).use { input -> FileOutputStream(temporary).use { output ->
                input.copyTo(output, 64 * 1024); output.fd.sync()
            } }
            check(temporary.length() == descriptor.byteSize && sha256(temporary) == descriptor.sha256) { "提升媒体文件校验失败" }
            check(temporary.renameTo(destination)) { "恢复图片文件提升失败" }
            return destination
        } finally { temporary.delete() }
    }

    suspend fun import(uri: Uri, now: Long = System.currentTimeMillis()): MediaAsset = importLock.withLock {
        objects.mkdirs()
        val stage = File(objects, ".${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stage).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= maxOriginalBytes) { "图片超过 32 MiB 限制" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("无法读取选中的图片")
            require(size > 0) { "图片文件为空" }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val type = inspect(stage)
            decodeBounded(stage, thumbnailSide).recycle()
            val orientation = runCatching {
                ExifInterface(stage.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val existing = store.mediaAssetByDigest(sha256, size)
            if (existing != null) {
                val current = file(existing)
                check(current.isFile && sha256(current) == sha256) { "媒体库中的原件缺失或校验失败，请先保留这次重新导入" }
                return existing
            }
            val id = UUID.randomUUID().toString()
            val key = "objects/$sha256"
            val destination = resolve(key)
            var createdDestination = false
            if (!destination.exists()) {
                check(stage.renameTo(destination)) { "图片原件保存失败" }
                createdDestination = true
            } else {
                check(sha256(destination) == sha256) { "媒体库摘要冲突" }
            }
            val asset = MediaAsset(id, sha256, type.mime, size, type.width, type.height, orientation, key, now)
            return try {
                val published = store.publishMediaAsset(asset)
                if (published.id != asset.id) return published
                asset
            } catch (error: Throwable) {
                // 只清理由本次导入创建且确认没有数据库引用的新文件；既有同摘要文件不会被清理。
                val (published, matches) = withContext(NonCancellable) {
                    runCatching { store.mediaAssetByDigest(sha256, size) } to
                        runCatching { destination.isFile && destination.length() == size && sha256(destination) == sha256 }
                }
                if (mayDeleteUnpublishedMedia(createdDestination, published, matches)) destination.delete()
                throw error
            }
        } finally {
            stage.delete()
        }
    }

    /** 只供受限 P4 测试接口创建本机合成图片，不读取调用方路径或 URI。 */
    suspend fun importSyntheticTestImage(now: Long = System.currentTimeMillis()): MediaAsset = importLock.withLock {
        objects.mkdirs()
        val seed = UUID.randomUUID().mostSignificantBits
        val background = android.graphics.Color.rgb(104, 69, 115)
        val skin = if ((seed and 1L) == 0L) android.graphics.Color.rgb(244, 194, 160)
            else android.graphics.Color.rgb(229, 166, 145)
        val hair = android.graphics.Color.rgb(65, 48, 67)
        val ink = android.graphics.Color.rgb(54, 42, 56)
        val pixels = IntArray(32 * 32) { index ->
            val x = index % 32
            val y = index / 32
            val dx = x - 16
            val dy = y - 16
            val face = dx * dx / 100.0 + dy * dy / 144.0 <= 1.0 && y >= 5
            val hairline = y in 5..12 && kotlin.math.abs(dx) <= 10 &&
                dy <= -3 && (x + y) % 5 != 0
            val eye = y in 15..16 && (x in 11..12 || x in 20..21)
            val smile = y in 21..24 && x in 12..20 &&
                y == 21 + (dx * dx / 10).coerceAtMost(3)
            when {
                eye || smile -> ink
                hairline -> hair
                face -> skin
                else -> background
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "测试图片编码失败" }
            output.toByteArray()
        }.also { bitmap.recycle() }
        val stage = File(objects, ".${UUID.randomUUID()}.part")
        FileOutputStream(stage).use { it.write(encoded); it.fd.sync() }
        val digest = sha256(stage)
        val existing = store.mediaAssetByDigest(digest, stage.length())
        if (existing != null) {
            check(file(existing).isFile && sha256(file(existing)) == digest) { "同摘要测试图片原件异常" }
            stage.delete()
            return@withLock existing
        }
        val info = inspect(stage)
        decodeBounded(stage, thumbnailSide).recycle()
        val key = "objects/$digest"
        val destination = resolve(key)
        check(!destination.exists()) { "合成测试图片目标已存在" }
        check(stage.renameTo(destination)) { "合成测试图片提升失败" }
        val asset = MediaAsset(UUID.randomUUID().toString(), digest, info.mime, destination.length(), info.width,
            info.height, 0, key, now)
        try {
            val published = store.publishMediaAsset(asset)
            published
        } catch (error: Throwable) {
            val published = withContext(NonCancellable) { runCatching { store.mediaAssetByDigest(digest, destination.length()) } }
            if (published.isSuccess && published.getOrNull() == null) destination.delete()
            throw error
        } finally { stage.delete() }
    }

    fun file(asset: MediaAsset): File = resolve(asset.fileKey)

    /** 只返回受私有目录约束且长度匹配的不可变原件；备份编解码器会边读边复核摘要。 */
    fun backupSource(asset: MediaAsset): File = file(asset).also { source ->
        check(source.isFile && source.length() == asset.byteSize) { "图片原件缺失或长度变化，无法完整备份" }
    }

    fun thumbnail(asset: MediaAsset): File {
        thumbnails.mkdirs()
        val source = file(asset)
        check(source.isFile) { "图片原件缺失或校验失败" }
        val cached = listOf(File(thumbnails, "${asset.id}-v2.png"), File(thumbnails, "${asset.id}-v2.jpg"))
            .firstOrNull { it.isFile && it.length() > 0 }
        if (cached != null) { cached.setLastModified(System.currentTimeMillis()); return cached }
        check(sha256(source) == asset.sha256) { "图片原件缺失或校验失败" }
        val bitmap = decodeBounded(source, thumbnailSide)
        val encoding = thumbnailEncoding(bitmap.hasAlpha())
        val target = File(thumbnails, "${asset.id}-v2.${encoding.extension}")
        try {
            FileOutputStream(target).use { out ->
                val format = if (encoding.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, 88, out)) { "缩略图生成失败" }
            }
        } finally { bitmap.recycle() }
        pruneThumbnails(target)
        return target
    }

    /** 大图与发送版本都限边，不把原件完整解码到内存。 */
    fun decodeDisplay(asset: MediaAsset): Bitmap {
        val source = file(asset)
        check(source.isFile && sha256(source) == asset.sha256) { "图片原件缺失或校验失败" }
        return decodeBounded(source, displaySide)
    }

    override suspend fun asset(mediaId: String): MediaAsset? = store.mediaAsset(mediaId)

    override fun openSendVariant(variant: MediaVariant): FileInputStream {
        require(variant.cacheKey.startsWith("media-variants/") && ".." !in variant.cacheKey.split('/')) { "发送变体键无效" }
        val file = File(context.cacheDir, variant.cacheKey).canonicalFile
        check(file.path.startsWith(variants.canonicalPath + File.separator) && file.isFile) { "发送图片变体已失效" }
        check(file.length() == variant.byteSize && sha256(file) == variant.sha256) { "发送图片变体校验失败" }
        return FileInputStream(file)
    }

    override suspend fun prepareSendVariant(mediaId: String): MediaVariant {
        val asset = requireNotNull(store.mediaAsset(mediaId)) { "图片资源不存在" }
        val jpeg = asset.mimeType == "image/jpeg"
        val variantName = if (jpeg) "jpeg-2048-q85-v1" else "png-2048-v1"
        variants.mkdirs()
        val extension = if (jpeg) "jpg" else "png"
        val temporary = File(variants, ".${UUID.randomUUID()}.part")
        val bitmap = decodeDisplay(asset)
        try {
            FileOutputStream(temporary).use { output ->
                val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                check(bitmap.compress(format, if (jpeg) 85 else 100, output)) { "发送图片变体生成失败" }
                output.fd.sync()
            }
            val digest = sha256(temporary)
            val target = File(variants, "$digest.$extension")
            if (target.exists()) temporary.delete()
            else check(temporary.renameTo(target)) { "发送图片变体缓存保存失败" }
            return MediaVariant(asset.id, variantName, digest, if (jpeg) "image/jpeg" else "image/png",
                target.length(), bitmap.width, bitmap.height, "media-variants/$digest.$extension")
        } finally {
            bitmap.recycle()
            temporary.delete()
        }
    }

    private fun decodeBounded(source: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_MEDIA_PIXELS) { "图片尺寸无效或超过像素限制" }
        val sample = decodeSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: error("图片解码失败")
        val orientation = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val oriented = if (orientation == ExifInterface.ORIENTATION_NORMAL) decoded else {
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        val target = fitWithin(oriented.width, oriented.height, maxSide)
        if (target.width == oriented.width && target.height == oriented.height) return oriented
        return Bitmap.createScaledBitmap(oriented, target.width, target.height, true).also {
            if (it !== oriented) oriented.recycle()
        }
    }

    private data class ImageInfo(val mime: String, val width: Int, val height: Int)

    private fun inspect(file: File): ImageInfo {
        val mime = RandomAccessFile(file, "r").use { detectStaticImageMime(it, file.length()) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_MEDIA_PIXELS) { "图片损坏或超过 4000 万像素" }
        return ImageInfo(mime, bounds.outWidth, bounds.outHeight)
    }

    private fun resolve(key: String): File {
        require(!key.startsWith('/') && ".." !in key.split('/'))
        val target = File(root, key).canonicalFile
        check(target.path.startsWith(root.canonicalPath + File.separator)) { "图片文件键越界" }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** 缩略图可重建；最多留 128 张且不超过 64 MiB，清理只针对 cacheDir。 */
    private fun pruneThumbnails(keep: File) {
        val files = thumbnails.listFiles()?.filter { it.isFile }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        var count = 0
        for (file in files) {
            if (file == keep || (count < 128 && total + file.length() <= 64L * 1024 * 1024)) {
                count++
                total += file.length()
            } else file.delete()
        }
    }
}
