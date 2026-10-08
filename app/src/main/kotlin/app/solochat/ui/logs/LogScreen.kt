package app.solochat.ui.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.solochat.core.log.Log
import app.solochat.core.log.LogEntry
import app.solochat.core.log.LogLevel

/**
 * 日志页。
 *
 * 排查问题是这个页面唯一的目的，所以几处刻意的设计：
 *
 * - **标题栏直接显示版本号**。用户反馈问题时第一步永远是"你装的哪个版本"，
 *   放在最显眼的位置就不用问了。导出的文本头部也带版本。
 * - **默认倒序（最新在上）**。出问题时最关心的是刚刚发生了什么。
 * - **长内容可展开**。提示词与模型原始输出可能有几千字，
 *   默认收起成一行，点一下才展开 —— 否则列表没法翻。
 * - **不落盘**。内存环形缓冲，退出即消失（用户明确说不用保存）。
 *   这同时避免了提示词（含全部对话）被持久化到磁盘的隐私风险。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val all by Log.entries.collectAsStateWithLifecycle()

    // 过滤条件是**纯 UI 偏好**，用 rememberSaveable：旋屏、切深色模式、被系统回收后
    // 重建界面时不该复位（默认落到"全部"上）。
    //
    // ⚠️ 类注释里曾经写着"默认只看『模型』和『编排』"，但代码从来是 `null`（= 全部）。
    // 以代码为准：默认全看，标签靠上面的 chip 自己筛。文档已按实际行为改正。
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var hideDebug by rememberSaveable { mutableStateOf(false) }
    // `verbose` 例外：它同时写给 Log 单例（那是真正的状态源），所以以单例为准、不做保存。
    var verbose by remember { mutableStateOf(Log.verbose) }

    val tags = remember(all) {
        all.map { it.tag }.distinct().sorted()
    }

    val filtered = remember(all, tagFilter, newestFirst, hideDebug) {
        all.asSequence()
            .filter { tagFilter == null || it.tag == tagFilter }
            .filter { !hideDebug || it.level != LogLevel.DEBUG }
            .let { if (newestFirst) it.toList().asReversed().asSequence() else it }
            .toList()
    }

    val listState = rememberLazyListState()
    // 倒序时新日志在顶部，不用滚；正序时要自动跟到底
    LaunchedEffect(filtered.size, newestFirst) {
        if (!newestFirst && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // 显式给 style：只写 fontWeight 会落到 LocalTextStyle（TopAppBar 默认的
                        // titleLarge 其实没被用上），字号就脱离了 typography 体系。
                        Text(
                            "日志",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        // 版本号放在这里：反馈问题的第一步永远是"你装的哪个版本"
                        Text(
                            text = Log.version,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(Log.export(all)))
                    }) { Text("复制全部") }
                    TextButton(onClick = { Log.clear() }) { Text("清空") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 声明 Scaffold 给的那份 inset 已经用掉，避免以后再往里面加
                // imePadding/其它 inset 修饰符时被算两遍。
                .consumeWindowInsets(padding)
        ) {

            // ---- 控制区 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = tagFilter == null,
                    onClick = { tagFilter = null },
                    label = { Text("全部 (${all.size})") }
                )
                for (tag in tags) {
                    val count = all.count { it.tag == tag }
                    FilterChip(
                        selected = tagFilter == tag,
                        onClick = { tagFilter = if (tagFilter == tag) null else tag },
                        label = { Text("$tag ($count)") }
                    )
                }
            }

            // 三个开关横排，窄屏 + 大字体下会挤爆，所以让它能横向滚。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最新在上", style = MaterialTheme.typography.labelSmall)
                Switch(checked = newestFirst, onCheckedChange = { newestFirst = it })
                Text("隐藏 DEBUG", style = MaterialTheme.typography.labelSmall)
                Switch(checked = hideDebug, onCheckedChange = { hideDebug = it })
                Text("记完整正文", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = verbose,
                    onCheckedChange = {
                        verbose = it
                        Log.verbose = it
                    }
                )
            }

            HorizontalDivider()

            if (filtered.isEmpty()) {
                Text(
                    text = "还没有日志。发一条消息就会有了。",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.seq }) { entry ->
                    LogRow(entry)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var expanded by remember(entry.seq) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // 多行内容默认只显示前几行（提示词可能有几千字，全展开列表就没法翻了）
    val lines = entry.text.lines()
    val isLong = lines.size > 3 || entry.text.length > 240
    val shown = when {
        expanded -> entry.text
        isLong -> lines.take(3).joinToString("\n").take(240) + " …"
        else -> entry.text
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.clock,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "  ${entry.tag}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (entry.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        }
        Text(
            text = shown,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
        )
        if (isLong) {
            // ⚠️ **不要**给这两个按钮加 `contentPadding = PaddingValues(0.dp)`。
            //
            // 那会把触摸区压到文字本身那么大（labelLarge 一行 ≈ 20dp 高），
            // 明显低于 Material 规范的 48dp —— 表现就是"展开全部"经常点不准，
            // 而它们恰好是长内容唯一的展开入口。默认的 contentPadding 才是对的。
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开全部（${entry.text.length} 字符）")
            }
        }
        // 长按不方便，给一个小按钮复制单条
        if (expanded) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }) {
                Text("复制这条")
            }
        }
    }
}
