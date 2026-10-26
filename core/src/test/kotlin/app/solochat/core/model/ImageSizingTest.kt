package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {
    @Test fun `5800像素长边不会向下取样到两倍目标尺寸`() {
        val sample = decodeSampleSize(5800, 3100, 2048)
        assertEquals(4, sample)
        val decoded = ImageDimensions(5800 / sample, 3100 / sample)
        val target = fitWithin(decoded.width, decoded.height, 2048)
        assertTrue(maxOf(target.width, target.height) <= 2048)
    }

    @Test fun `小图不放大且旋转后也可以重新限制长边`() {
        assertEquals(1, decodeSampleSize(900, 500, 2048))
        assertEquals(ImageDimensions(774, 2048), fitWithin(3100, 8200, 2048))
        assertEquals(ImageDimensions(512, 128), fitWithin(2048, 512, 512))
    }

    @Test fun `缩略图含透明像素时选择保留 alpha 的 PNG`() {
        assertEquals(ThumbnailEncoding.PNG, thumbnailEncoding(hasAlpha = true))
        assertEquals(ThumbnailEncoding.JPEG, thumbnailEncoding(hasAlpha = false))
    }
}
