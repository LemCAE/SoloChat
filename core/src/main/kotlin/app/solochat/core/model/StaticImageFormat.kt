package app.solochat.core.model

import java.io.RandomAccessFile

/** 按文件内容识别消息图片，并拒绝 APNG/动画 WebP；不读取或缓存整张原图。 */
fun detectStaticImageMime(file: RandomAccessFile, length: Long): String {
    require(length >= 3) { "图片文件不完整" }
    file.seek(0)
    val header = ByteArray(minOf(12L, length).toInt()).also(file::readFully)
    return when {
        header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
        header.size >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> {
            require(!hasPngChunk(file, length, "acTL")) { "暂不支持 APNG 动画" }
            "image/png"
        }
        header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
            require(!hasWebpAnimationChunk(file, length)) { "暂不支持动画 WebP" }
            "image/webp"
        }
        else -> error("只支持 JPEG、PNG 和静态 WebP 图片")
    }
}

private fun hasPngChunk(file: RandomAccessFile, length: Long, wanted: String): Boolean {
    file.seek(8)
    while (file.filePointer + 12 <= length) {
        val size = file.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
        if (type == wanted) return true
        val next = file.filePointer + size + 4
        require(size <= length && next <= length) { "PNG 图片结构损坏" }
        file.seek(next)
        if (type == "IEND") break
    }
    return false
}

private fun hasWebpAnimationChunk(file: RandomAccessFile, length: Long): Boolean {
    file.seek(4)
    val riffSize = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
    require(riffSize + 8 == length) { "WebP 图片结构损坏" }
    file.seek(12)
    while (file.filePointer + 8 <= length) {
        val type = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
        val size = file.readInt().toLong() and 0xffffffffL
        if (type == "ANIM" || type == "ANMF") return true
        val next = file.filePointer + size + (size and 1L)
        require(next <= length) { "WebP 图片结构损坏" }
        file.seek(next)
    }
    return false
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
