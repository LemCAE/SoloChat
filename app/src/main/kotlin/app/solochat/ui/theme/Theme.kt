package app.solochat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 聊天气泡与场景渲染需要几个**语义色**，Material3 的配色槽位里没有对应项
 * （比如"对方的气泡"和"我的气泡"、场景旁白的灰）。
 *
 * 所以单独做一个扩展调色板，用 CompositionLocal 下发，而不是到处硬编码颜色 ——
 * 硬编码的结果是明暗主题切换时总有一两处漏掉，在聊天界面里特别刺眼。
 */
data class ChatColors(
    /** 我的气泡。 */
    val bubbleMine: Color,
    val bubbleMineText: Color,
    /** 对方的气泡。 */
    val bubbleTheirs: Color,
    val bubbleTheirsText: Color,
    /** 面对面模式的旁白。 */
    val narrationText: Color,
    /** 面对面模式的动作描写。 */
    val actionText: Color,
    /** 时间轴上的系统分隔行（"—— 相遇 ——"）。 */
    val dividerText: Color,
    /** "正在输入…"的提示色。 */
    val typingText: Color
)

data class ChatDimens(
    /** 气泡最大宽度占屏宽的比例。太宽会失去"消息"的观感。 */
    val bubbleMaxWidthFraction: Float,
    val bubbleCorner: Dp,
    val bubbleGapSameSpeaker: Dp,
    val bubbleGapDifferentSpeaker: Dp
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        bubbleMine = Color(0xFF4C5BD4),
        bubbleMineText = Color.White,
        bubbleTheirs = Color(0xFF2A2F42),
        bubbleTheirsText = Color(0xFFE6E8F0),
        narrationText = Color(0xFF8B90A6),
        actionText = Color(0xFFB9BDD1),
        dividerText = Color(0xFF6F7488),
        typingText = Color(0xFF8B90A6)
    )
}

val LocalChatDimens = staticCompositionLocalOf {
    ChatDimens(
        // 0.78 是微信/Telegram 那一档：再宽就像文章而不像消息
        bubbleMaxWidthFraction = 0.78f,
        bubbleCorner = 18.dp,
        bubbleGapSameSpeaker = 3.dp,
        bubbleGapDifferentSpeaker = 10.dp
    )
}

/**
 * 暗色配色。
 *
 * ⚠️ **槽位要么在这里显式给，要么就别用。**
 *
 * 只填一部分槽位看起来"够用"，实际上没填的那些会落到 Material3 的 baseline 默认值，
 * 而 baseline 是一套偏紫的中性色 —— 和这里的靛蓝/深蓝完全不是一套。
 * 具体踩到的三处：日志页的 WARN 用 `tertiary`（baseline 是紫红 #7D5260）、
 * 警告卡用 `errorContainer`/`onErrorContainer`、
 * 而**所有 `AlertDialog` 与 `DropdownMenu` 内部都用 `surfaceContainerHigh`** ——
 * 不填的话菜单和对话框在暗色下是 #2B2930 那种偏紫灰，看起来像另一个 App。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C97FF),
    onPrimary = Color(0xFF10131F),
    primaryContainer = Color(0xFF2A2F52),
    onPrimaryContainer = Color(0xFFDDE1FF),
    inversePrimary = Color(0xFF4C5BD4),

    secondary = Color(0xFF9AA3C7),
    onSecondary = Color(0xFF10131F),
    secondaryContainer = Color(0xFF333A58),
    onSecondaryContainer = Color(0xFFDFE2F5),

    // 琥珀色。用途很具体：日志页的 WARN 级别，以及将来任何"提醒但不是错误"的语义。
    // 取 #F5C26B 是为了和日志网页版里的 `--warn` 同一个色 —— 两边看起来才是同一个应用。
    tertiary = Color(0xFFF5C26B),
    onTertiary = Color(0xFF2A1F05),
    tertiaryContainer = Color(0xFF4A3A1A),
    onTertiaryContainer = Color(0xFFFFE2A8),

    background = Color(0xFF101320),
    onBackground = Color(0xFFE6E8F0),

    surface = Color(0xFF161A28),
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF232838),
    onSurfaceVariant = Color(0xFFB9BDD1),
    surfaceTint = Color(0xFF8C97FF),
    inverseSurface = Color(0xFFE6E8F0),
    inverseOnSurface = Color(0xFF161A28),

    // 容器色阶：从最低（比 surface 更暗）到最高（比 surface 更亮）。
    // 对话框/菜单/底部弹层分别取 High 与 Highest，所以这一组必须是一段连续的坡。
    surfaceContainerLowest = Color(0xFF0B0E18),
    surfaceContainerLow = Color(0xFF141822),
    surfaceContainer = Color(0xFF181D2B),
    surfaceContainerHigh = Color(0xFF1F2434),
    surfaceContainerHighest = Color(0xFF262C3E),
    surfaceBright = Color(0xFF1E2333),
    surfaceDim = Color(0xFF101320),

    error = Color(0xFFFF8A8A),
    onError = Color(0xFF2A0D0D),
    // 错误容器必须与 onErrorContainer 成对给：`CardDefaults.cardColors(containerColor = …)`
    // 靠 `contentColorFor(containerColor)` 反查内容色，两者缺一个对比度就塌。
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Color(0xFF3A4056),
    // outlineVariant 是**分隔线**的颜色（HorizontalDivider 的默认值）。
    // 它要比 outline 更淡，否则列表会被一堆实线切碎。
    outlineVariant = Color(0xFF2A3044),
    scrim = Color(0xFF000000)
)

/** 亮色配色。槽位与暗色**一一对应**，少一个就会出现"只有浅色模式下难看"的问题。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4C5BD4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF10133A),
    inversePrimary = Color(0xFF8C97FF),

    secondary = Color(0xFF5A6183),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE2F5),
    onSecondaryContainer = Color(0xFF171B2C),

    // 同一个琥珀色在亮色下必须压深，否则白底上根本读不出来
    tertiary = Color(0xFF8A6100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2A8),
    onTertiaryContainer = Color(0xFF2A1F05),

    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF1A1D29),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D29),
    surfaceVariant = Color(0xFFE7E9F2),
    onSurfaceVariant = Color(0xFF4A4F63),
    surfaceTint = Color(0xFF4C5BD4),
    inverseSurface = Color(0xFF1A1D29),
    inverseOnSurface = Color(0xFFF5F6FA),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFF2F3F9),
    surfaceContainerHigh = Color(0xFFECEEF5),
    surfaceContainerHighest = Color(0xFFE6E8F2),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E5EE),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    outline = Color(0xFFC3C7D6),
    outlineVariant = Color(0xFFD6DAE6),
    scrim = Color(0xFF000000)
)

private val DarkChat = ChatColors(
    bubbleMine = Color(0xFF4C5BD4),
    bubbleMineText = Color(0xFFF2F3FF),
    bubbleTheirs = Color(0xFF262B3C),
    bubbleTheirsText = Color(0xFFE6E8F0),
    narrationText = Color(0xFF7F8497),
    actionText = Color(0xFFA9AEC4),
    dividerText = Color(0xFF6F7488),
    typingText = Color(0xFF8B90A6)
)

private val LightChat = ChatColors(
    bubbleMine = Color(0xFF4C5BD4),
    bubbleMineText = Color.White,
    bubbleTheirs = Color(0xFFEAECF4),
    bubbleTheirsText = Color(0xFF1A1D29),
    narrationText = Color(0xFF8A8FA3),
    actionText = Color(0xFF6A7086),
    dividerText = Color(0xFF9AA0B4),
    typingText = Color(0xFF8A8FA3)
)

@Composable
fun SoloChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val chat = if (darkTheme) DarkChat else LightChat
    // ⚠️ 这里**只**提供 ChatColors，不再提供 ChatDimens。
    //
    // 原来写的是 `LocalChatDimens provides LocalChatDimens.current` —— 把自己提供给自己，
    // 是个空操作：读到的永远是 staticCompositionLocalOf 的默认值。留着它会让人以为
    // "尺寸是可以从外面换的"，而实际上没有任何路径能换。真要做主题化时再补一个
    // 带参数的 provider，别用这种自赋值占位。
    CompositionLocalProvider(LocalChatColors provides chat) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
