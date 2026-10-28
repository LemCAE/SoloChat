package app.solochat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.solochat.SoloChatApp
import app.solochat.R
import app.solochat.core.model.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun MediaThumbnail(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Crop,
    showContainer: Boolean = true
) {
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val result = produceState<Result<android.graphics.Bitmap>?>(null, asset.id) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(app.media.thumbnail(asset).path) ?: error("缩略图解码失败") } }
    }.value
    val bitmap = result?.getOrNull()
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    Box(modifier.then(if (showContainer) Modifier.clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = context.getString(R.string.media_image_description), modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        else if (result == null) CircularProgressIndicator()
        else Text(context.getString(R.string.media_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun MediaViewerDialog(asset: MediaAsset, onDismiss: () -> Unit, onSave: () -> Unit, onQuote: () -> Unit,
    onCollectUser: (() -> Unit)? = null) {
    val localContext = LocalContext.current
    val app = localContext.applicationContext as SoloChatApp
    val result = produceState<Result<android.graphics.Bitmap>?>(null, asset.id) {
        value = withContext(Dispatchers.IO) { runCatching { app.media.decodeDisplay(asset) } }
    }.value
    val bitmap = result?.getOrNull()
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(asset.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(asset.id) { mutableFloatStateOf(0f) }
    val gesture = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offsetX += pan.x
        offsetY += pan.y
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(12.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).transformable(gesture), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = localContext.getString(R.string.media_original_description), modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY }, contentScale = ContentScale.Fit)
                else if (result == null) CircularProgressIndicator()
                else Text(localContext.getString(R.string.media_original_unavailable), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onSave, enabled = bitmap != null) { Text(localContext.getString(R.string.media_save_action)) }
                if (onCollectUser != null) TextButton(onClick = onCollectUser, enabled = bitmap != null) { Text("收藏表情") }
                TextButton(onClick = onQuote) { Text(localContext.getString(R.string.media_quote_action)) }
                TextButton(onClick = onDismiss) { Text(localContext.getString(R.string.media_close_action)) }
            }
        }
    }
}
