package app.solochat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import app.solochat.core.model.AvatarRef
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** 系统选图后保存独立缩略图，不依赖临时 URI 权限，也不复制原图的 EXIF 资料。 */
object AvatarImages {
    fun file(context: Context, ref: String): File? = AvatarRef.imageId(ref)?.let {
        File(context.filesDir, "avatars/$it.jpg")
    }

    fun importImage(context: Context, uri: Uri): String {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val ratio = min(1.0, 1024.0 / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * ratio).toInt()), max(1, (info.size.height * ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (max(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1024) {
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("无法读取这张图片")
            val orientation = resolver.openInputStream(uri)?.use {
                runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            } ?: 1
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val thumbnail = Bitmap.createScaledBitmap(square, min(512, side), min(512, side), true)
        val ref = AvatarRef.image(UUID.randomUUID().toString())
        val target = requireNotNull(file(context, ref))
        target.parentFile!!.mkdirs()
        try {
            target.outputStream().use { require(thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "头像保存失败" } }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        // 引用可能被人物副本共享，替换/取消编辑时不主动删除旧文件。
        return ref
    }
}
