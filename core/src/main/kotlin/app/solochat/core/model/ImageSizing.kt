package app.solochat.core.model

import kotlin.math.floor

data class ImageDimensions(val width: Int, val height: Int)

/** 选择不会向下取样不足的二次幂；最终输出仍应调用 [fitWithin] 再限边。 */
fun decodeSampleSize(width: Int, height: Int, maxSide: Int): Int {
    require(width > 0 && height > 0 && maxSide > 0)
    var sample = 1
    while ((width + sample - 1) / sample > maxSide || (height + sample - 1) / sample > maxSide) {
        sample *= 2
    }
    return sample
}

/** 返回保持纵横比且实际长边不超过 maxSide 的正尺寸。 */
fun fitWithin(width: Int, height: Int, maxSide: Int): ImageDimensions {
    require(width > 0 && height > 0 && maxSide > 0)
    val longest = maxOf(width, height)
    if (longest <= maxSide) return ImageDimensions(width, height)
    val scale = maxSide.toDouble() / longest
    return ImageDimensions(
        floor(width * scale).toInt().coerceAtLeast(1).coerceAtMost(maxSide),
        floor(height * scale).toInt().coerceAtLeast(1).coerceAtMost(maxSide)
    )
}

enum class ThumbnailEncoding(val extension: String) { JPEG("jpg"), PNG("png") }

fun thumbnailEncoding(hasAlpha: Boolean): ThumbnailEncoding =
    if (hasAlpha) ThumbnailEncoding.PNG else ThumbnailEncoding.JPEG
