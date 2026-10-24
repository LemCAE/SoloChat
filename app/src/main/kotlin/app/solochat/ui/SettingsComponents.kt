package app.solochat.ui

import app.solochat.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 工具页统一使用标准高度；聊天页继续使用独立的紧凑双行标题。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolTopBar(title: String, onBack: () -> Unit, backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringsContext.getString(R.string.ui_settingscomponents_072b258d))
            }
        }, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

/** 关联项共用一个表面，不为每行设置额外的卡片和阴影。 */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun SettingsLink(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = subtitle?.let { value ->
            { Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant) })
}

/** 表单字段也沿用分组外观，间距由容器统一提供。 */
@Composable
fun SettingsFormGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroup(title) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun InfoButton(title: String, message: String) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.Info, contentDescription = stringsContext.getString(R.string.ui_common_info_accessibility, title))
    }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringsContext.getString(R.string.ui_common_close)) } }
    )
}
