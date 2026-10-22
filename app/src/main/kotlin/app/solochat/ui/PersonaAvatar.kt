package app.solochat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import app.solochat.core.model.AvatarRef
import app.solochat.data.AvatarImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 小尺寸共享头像。文件缺失时显示名字首字，不把本机图片引用当作文本。 */
@Composable
fun PersonaAvatar(avatar: String, name: String, modifier: Modifier = Modifier.size(32.dp)) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, avatar) {
        value = null
        value = withContext(Dispatchers.IO) {
            AvatarImages.file(context, avatar)?.takeIf { it.isFile }?.let { file ->
                runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        val loaded = bitmap
        if (loaded != null) Image(loaded, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val label = AvatarRef.label(avatar).ifBlank {
                name.trim().takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))) } ?: "聊"
            }
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}
