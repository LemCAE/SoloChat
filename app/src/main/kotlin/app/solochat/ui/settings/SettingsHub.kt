package app.solochat.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import app.solochat.ui.SettingsGroup
import app.solochat.ui.SettingsLink

/** 主菜单只做导航，编辑与持久化仍留在原分组页面。 */
@Composable
internal fun SettingsHub(modelSummary: String, priceSummary: String,
    onApi: () -> Unit, onPacing: () -> Unit, onBehavior: () -> Unit,
    onPersonas: () -> Unit, onMemory: () -> Unit, onPrice: () -> Unit,
    onUsage: () -> Unit, onBackup: () -> Unit, onAbout: () -> Unit, onDiagnostics: () -> Unit) {
    SettingsGroup("聊天") {
        SettingsLink(Icons.Filled.Settings, "接口与模型", modelSummary, onApi)
        SettingsLink(Icons.Filled.PlayArrow, "节奏", "打字速度、阅读延迟与消息间隔", onPacing)
        SettingsLink(Icons.Filled.Person, "聊天行为", "发送方式、模式提议与等待", onBehavior)
    }
    SettingsGroup("资料") {
        SettingsLink(Icons.Filled.AccountBox, "人物卡", "管理人物，导入与导出", onPersonas)
        SettingsLink(Icons.Filled.Face, "记忆", "通用记忆与人物记忆", onMemory)
        SettingsLink(Icons.Filled.ShoppingCart, "模型价格", priceSummary, onPrice)
        SettingsLink(Icons.Filled.DateRange, "用量统计", "费用、token 与调用记录", onUsage)
        SettingsLink(Icons.Filled.Share, "本地备份", "整理聊天资料，导出到文件", onBackup)
    }
    SettingsGroup("应用") {
        SettingsLink(Icons.Filled.Info, "版本信息", "当前版本与应用说明", onAbout)
        SettingsLink(Icons.Filled.Build, "排查与日志", "文件日志与日志网页", onDiagnostics)
    }
}
