package app.solochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class StaticImageFormatTest {
    @Test fun `检测 JPEG PNG 与静态 WebP 内容签名`() {
        assertEquals("image/jpeg", inspect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertEquals("image/png", inspect(pngChunk("IEND")))
        assertEquals("image/webp", inspect(hex("52494646300000005745425056503820240000005001009D012A0800060001402625004E80280000FEFB8D4B91D843761CBAE2E6C86F0000")))
        assertEquals("image/webp", inspect(hex("5249464622000000574542505650384C160000002F0740011007508F2A54A6B4018020FC6F9B88E87F08")))
        assertEquals("image/webp", inspect(webpChunk("VP8 ", byteArrayOf(1, 2, 3))))
    }

    @Test fun `按内容拒绝 APNG 和动画 WebP`() {
        assertTrue(runCatching { inspect(pngChunk("acTL")) }.exceptionOrNull()?.message?.contains("APNG") == true)
        assertTrue(runCatching { inspect(webpChunk("ANIM", byteArrayOf(0, 0, 0, 0, 0, 0))) }.exceptionOrNull()?.message?.contains("动画 WebP") == true)
        assertTrue(runCatching { inspect(webpChunk("ANMF", byteArrayOf(0, 0, 0, 0))) }.exceptionOrNull()?.message?.contains("动画 WebP") == true)
    }

    @Test fun `扩展名与格式无关且损坏签名明确拒绝`() {
        assertTrue(runCatching { inspect("GIF89a".toByteArray()) }.isFailure)
        assertTrue(runCatching { inspect(byteArrayOf(1, 2, 3)) }.isFailure)
    }

    private fun inspect(bytes: ByteArray): String {
        val file = File.createTempFile("media-format-", ".bin")
        return try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use { detectStaticImageMime(it, file.length()) }
        } finally { file.delete() }
    }

    private fun pngChunk(type: String): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 0, *type.toByteArray(Charsets.US_ASCII), 0, 0, 0, 0
    )

    private fun webpChunk(type: String, payload: ByteArray): ByteArray {
        val size = payload.size
        val chunk = byteArrayOf(*type.toByteArray(Charsets.US_ASCII), size.toByte(), (size shr 8).toByte(),
            (size shr 16).toByte(), (size shr 24).toByte(), *payload, *if (size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        val riffSize = 4 + chunk.size
        return byteArrayOf(*"RIFF".toByteArray(Charsets.US_ASCII), riffSize.toByte(), (riffSize shr 8).toByte(),
            (riffSize shr 16).toByte(), (riffSize shr 24).toByte(), *"WEBP".toByteArray(Charsets.US_ASCII), *chunk)
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
