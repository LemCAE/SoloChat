package app.solochat

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.solochat.ui.chat.ChatScreen
import app.solochat.ui.conversations.ConversationListScreen
import app.solochat.ui.logs.LogScreen
import app.solochat.ui.memory.MemoryScreen
import app.solochat.ui.persona.PersonaScreen
import app.solochat.ui.settings.SettingsScreen
import app.solochat.ui.theme.SoloChatTheme
import app.solochat.ui.usage.UsageScreen

/**
 * 唯一的 Activity。
 *
 * 用 Compose Navigation 管理七个目的地；会话设置在聊天上层展示，保持控制器存活。
 * 但"从设置里点某个会话跳过去"这种入口以后一定会加，
 * 有 back stack 管理会省掉一堆麻烦。
 */
class MainActivity : ComponentActivity() {

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode 由 Activity 自行处理，不会重建；主题变了也要重算系统栏图标。
        enableEdgeToEdge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SoloChatTheme {
                SoloChatNavHost()
            }
        }
    }
}

private object Routes {
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val PERSONAS = "personas"
    const val MEMORY = "memory"
    const val USAGE = "usage"
    const val LOGS = "logs"

    fun chat(id: String) = "chat/$id"
}

@Composable
private fun SoloChatNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.CONVERSATIONS,
        // ⚠️ **必须显式给过渡时长，不要吃 navigation-compose 的默认值。**
        //
        // 它的默认值（`DefaultNavTransitions`）是**前进方向 fadeIn/fadeOut 各 700ms**：
        //     enterTransition = { fadeIn(animationSpec = tween(700)) }
        //     exitTransition  = { fadeOut(animationSpec = tween(700)) }
        // 700ms 的交叉淡入意味着**两个整页同时被组合与绘制整整 0.7 秒**。
        // 这块屏是 90Hz（帧预算 11ms），那就是六十多帧的双份工作。
        //
        // 真机实测（`adb shell dumpsys gfxinfo`，"点一下会话行"这一个动作）：
        //   - 默认 700ms：这次导航期间渲染了 **60 帧**；
        //     改成 180/130ms 之后：同一个动作只渲染 **约 30 帧**。
        //   - GPU 侧 p99 只有 6~8ms（渲染不是瓶颈），而 `Number Slow UI thread` 有计数
        //     —— 说明成本在**主线程的组装与布局**上，正好对应"两页同时组装"。
        //   （不要引用 `Number High input latency`：这台 ROM 上它会出现大于总帧数的值，
        //     不可靠，只能当旁证。）
        //
        // 180/130ms 足够读成"页面切换了"，又不至于让主线程失去处理输入的余量。
        // 觉得太快可以折中到 300ms；想更像"推进一层"可以换 slideInHorizontally，
        // 但那要配返回方向的 slideOut，而且聊天页是整屏长列表，横滑更容易看到掉帧。
        enterTransition = { fadeIn(animationSpec = tween(180)) },
        exitTransition = { fadeOut(animationSpec = tween(130)) },
        popEnterTransition = { fadeIn(animationSpec = tween(180)) },
        popExitTransition = { fadeOut(animationSpec = tween(130)) }
    ) {
        composable(Routes.CONVERSATIONS) {
            ConversationListScreen(
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPersonas = { navController.navigate(Routes.PERSONAS) },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenUsage = { navController.navigate(Routes.USAGE) },
                onOpenLogs = { navController.navigate(Routes.LOGS) }
            )
        }
        composable(Routes.CHAT) { entry ->
            val id = entry.arguments?.getString("conversationId").orEmpty()
            ChatScreen(
                conversationId = id,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
                onOpenPersonas = { navController.navigate(Routes.PERSONAS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLogs = { navController.navigate(Routes.LOGS) }
            )
        }
        composable(Routes.PERSONAS) {
            PersonaScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.MEMORY) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.USAGE) {
            UsageScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LOGS) {
            LogScreen(onBack = { navController.popBackStack() })
        }
    }
}
