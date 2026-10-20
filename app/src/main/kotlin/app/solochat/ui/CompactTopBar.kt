package app.solochat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 标题区至少 48dp；长标题/大字体自然增高，状态栏只在这一处避让。 */
@Composable
fun CompactTopBar(
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navigationIcon?.invoke()
                Column(
                    Modifier.weight(1f).padding(start = if (navigationIcon == null) 16.dp else 4.dp,
                        end = 8.dp, top = 6.dp, bottom = 6.dp)
                ) { title() }
                actions()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
