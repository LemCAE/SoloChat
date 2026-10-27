package app.solochat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.solochat.core.model.EventKind
import app.solochat.core.model.MediaAsset
import app.solochat.play.ChatItem
import app.solochat.ui.theme.LocalChatColors
import app.solochat.ui.theme.LocalChatDimens
import app.solochat.ui.MediaThumbnail

/**
 * 气泡与场景渲染。
 *
 * 这个文件只做排版，不做任何数据变换 —— 好处是"能不能渲染"变成纯函数式的：
 * 给它 [ChatItem] 就能断言出该画什么（见 ChatRenderTest）。
 *
 * 两种视觉语言的差别（这是这个应用的核心卖点，不能含糊）：
 *
 * | 模式     | 我的        | 对方的        | 动作 / 旁白      |
 * |----------|-------------|---------------|------------------|
 * | 发消息   | 右侧实色气泡 | 左侧浅色气泡 | 居中斜体小字     |
 * | 面对面   | 右侧台词     | 左侧台词      | 居中的场景叙述   |
 */
@Composable
fun ChatBubble(
    item: ChatItem,
    modifier: Modifier = Modifier,
    onAttachmentClick: (MediaAsset) -> Unit = {}
) {
    val colors = LocalChatColors.current
    val dimens = LocalChatDimens.current
    val maxWidth = (LocalConfiguration.current.screenWidthDp * dimens.bubbleMaxWidthFraction).dp

    when (item) {
        is ChatItem.Mine -> Row(
            modifier = modifier.fillMaxWidth().padding(
                top = if (item.sameGroup) dimens.bubbleGapSameSpeaker else dimens.bubbleGapDifferentSpeaker
            ),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            TimestampLabel(item.at)
            Spacer(Modifier.width(6.dp))
            if (item.attachments.isEmpty()) BubbleText(
                text = item.text, background = colors.bubbleMine, textColor = colors.bubbleMineText,
                maxWidth = maxWidth, shape = RoundedCornerShape(
                    topStart = dimens.bubbleCorner, topEnd = if (item.sameGroup) dimens.bubbleCorner else 4.dp,
                    bottomStart = dimens.bubbleCorner, bottomEnd = dimens.bubbleCorner
                )
            ) else Column(
                modifier = Modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(
                    topStart = dimens.bubbleCorner, topEnd = if (item.sameGroup) dimens.bubbleCorner else 4.dp,
                    bottomStart = dimens.bubbleCorner, bottomEnd = dimens.bubbleCorner
                )).background(colors.bubbleMine).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (item.isSticker) Text("表情", color = colors.bubbleMineText, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp))
                item.attachments.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.attachments.size == 1) {
                            val imageSize = minOf(maxWidth, 168.dp)
                            MediaThumbnail(
                                item.attachments.single(),
                                Modifier.size(imageSize),
                                onClick = { onAttachmentClick(item.attachments.single()) }
                            )
                        } else {
                            pair.forEach { asset ->
                                MediaThumbnail(asset, Modifier.weight(1f).heightIn(min = 96.dp, max = 168.dp).aspectRatio(1f),
                                    onClick = { onAttachmentClick(asset) })
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (item.text.isNotBlank()) Text(item.text, color = colors.bubbleMineText, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }

        is ChatItem.Theirs -> Row(
            modifier = modifier.fillMaxWidth().padding(
                top = if (item.sameGroup) dimens.bubbleGapSameSpeaker else dimens.bubbleGapDifferentSpeaker
            ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            BubbleText(
                text = item.text,
                background = colors.bubbleTheirs,
                textColor = colors.bubbleTheirsText,
                maxWidth = maxWidth,
                shape = RoundedCornerShape(
                    topStart = if (item.sameGroup) dimens.bubbleCorner else 4.dp,
                    topEnd = dimens.bubbleCorner,
                    bottomStart = dimens.bubbleCorner,
                    bottomEnd = dimens.bubbleCorner
                )
            )
            Spacer(Modifier.width(6.dp))
            TimestampLabel(item.at)
        }

        // 我自己的动作/感官：和对方的动作**同一套字（斜体、暗色、小字）**，
        // 只有对齐方式不同 —— 靠右 = 我做的。位置本身已经把"谁"说清楚了，
        // 所以不再像对方那样在前面冠一个名字。
        is ChatItem.MyAction -> Row(
            modifier = modifier.fillMaxWidth().padding(
                top = if (item.sameGroup) 2.dp else 8.dp
            ),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = item.text,
                color = colors.actionText,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        is ChatItem.Action -> Row(
            modifier = modifier.fillMaxWidth().padding(
                top = if (item.sameGroup) 2.dp else 8.dp
            ),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = buildString {
                    if (item.speaker.isNotBlank()) append(item.speaker)
                    append(" ")
                    append(item.text)
                },
                color = colors.actionText,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        is ChatItem.Narration -> Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.text,
                color = colors.narrationText,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }

        is ChatItem.Divider -> DividerLine(item.text, modifier)

        is ChatItem.Live -> ChatBubble(
            // 活气泡按它的类型走同一套渲染，只是内容可能还没长全。
            // `at` 一并带上：落定那一刻时间戳不能突然冒出来（会让整行高度跳一下）。
            item = when (item.kind) {
                EventKind.SAY -> ChatItem.Theirs(
                    id = -1,
                    text = item.text,
                    at = item.at,
                    speaker = item.speaker,
                    sameGroup = false
                )

                EventKind.ACT ->
                    ChatItem.Action(id = -1, text = item.text, speaker = item.speaker, sameGroup = false)

                EventKind.SCENE ->
                    ChatItem.Narration(id = -1, text = item.text)

                else -> ChatItem.Narration(id = -1, text = item.text)
            },
            modifier = modifier
        )
    }
}

@Composable
private fun BubbleText(
    text: String,
    background: Color,
    textColor: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape
) {
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 气泡旁边的时间戳。
 *
 * 用户明确要求"给每条加个时间戳"。这不只是好看：气泡的**出现顺序**在演出过程中
 * 是逐步建立的，而所有事件最终都会被标上同一个交付时间 —— 界面上看到时间戳，
 * 才能判断一个"顺序看起来不对"的现象到底是引擎播错了，还是自己看花了。
 *
 * 格式 `HH:mm`，故意不带秒：带秒会让人盯着秒数比较，而秒级差异在聊天里没有意义。
 */
@Composable
private fun TimestampLabel(at: Long) {
    if (at <= 0) return
    val colors = LocalChatColors.current
    Text(
        text = formatBubbleTime(at),
        color = colors.dividerText,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

/**
 * 气泡时间戳的格式化。
 *
 * 抽成**顶层纯函数**是为了能测：这是用户明确要求的功能（"给每条加个时间戳"），
 * 而时间格式化恰恰是那种"看起来对、边界上错"的东西（0、负值、跨天）。
 * 放在 composable 里就只能靠肉眼看。
 *
 * 格式 `HH:mm`，故意不带秒：带秒会让人盯着秒数比较，而秒级差异在聊天里没有意义。
 */
internal fun formatBubbleTime(at: Long): String {
    // 0 与负值表示"没有时间信息"（老数据、或还没落盘），不该显示成 1970 年
    if (at <= 0) return ""
    return synchronized(bubbleTimeFormat) { bubbleTimeFormat.format(java.util.Date(at)) }
}

private val bubbleTimeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

/**
 * 时间轴上的分隔行（"—— 见面了 ——"）。
 *
 * 模式切换是这个应用最独特的一件事，一条明确的分隔行让时间轴有断点，
 * 否则用户会以为界面渲染错了（见 docs/01-decisions.md D7）。
 */
@Composable
fun DividerLine(text: String, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = colors.dividerText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}
