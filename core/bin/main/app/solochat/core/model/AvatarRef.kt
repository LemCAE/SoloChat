package app.solochat.core.model

import java.util.UUID

/** 仍兼容旧 emoji 字段；图片只存不含路径的本机引用，不向标签或提示词暴露文件位置。 */
object AvatarRef {
    private const val PREFIX = "solochat-avatar:"
    fun image(id: String): String {
        require(UUID.fromString(id).toString() == id) { "头像标识无效" }
        return PREFIX + id
    }
    fun imageId(value: String): String? = value.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.takeIf { id -> runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) }
    fun isImage(value: String): Boolean = value.startsWith(PREFIX)
    fun label(value: String): String = if (isImage(value)) "" else value.trim()
}
