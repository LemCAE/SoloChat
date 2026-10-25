package app.solochat.ui.settings

import app.solochat.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
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
    onModelUses: () -> Unit, onProviderModels: () -> Unit,
    onPacing: () -> Unit, onBehavior: () -> Unit,
    onPersonas: () -> Unit, onMemory: () -> Unit, onPrice: () -> Unit,
    onUsage: () -> Unit, onBackup: () -> Unit, onAbout: () -> Unit, onDiagnostics: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    SettingsGroup(stringsContext.getString(R.string.ui_settingshub_6a43e963)) {
        SettingsLink(Icons.Filled.Category, stringsContext.getString(R.string.p2a_model_uses),
            modelSummary, onModelUses)
        SettingsLink(Icons.Filled.PlayArrow, stringsContext.getString(R.string.ui_settingshub_1ba8d0c7), onClick = onPacing)
        SettingsLink(Icons.Filled.Person, stringsContext.getString(R.string.ui_settingshub_c3ed73f7), onClick = onBehavior)
    }
    SettingsGroup(stringsContext.getString(R.string.p2a_service_config)) {
        SettingsLink(Icons.Filled.Settings, stringsContext.getString(R.string.p2a_providers_models),
            stringsContext.getString(R.string.p2a_providers_models_hint), onProviderModels)
        SettingsLink(Icons.Filled.ShoppingCart, stringsContext.getString(R.string.ui_settingshub_58d4fce7), priceSummary, onPrice)
    }
    SettingsGroup(stringsContext.getString(R.string.ui_settingshub_9622e1cf)) {
        SettingsLink(Icons.Filled.AccountBox, stringsContext.getString(R.string.ui_settingshub_a30da8f8), onClick = onPersonas)
        SettingsLink(Icons.Filled.Face, stringsContext.getString(R.string.ui_settingshub_8e1f4724), stringsContext.getString(R.string.ui_settingshub_8defe593), onMemory)
        SettingsLink(Icons.Filled.DateRange, stringsContext.getString(R.string.ui_settingshub_ec0a7354), stringsContext.getString(R.string.ui_settingshub_3eb6b1f1), onUsage)
        SettingsLink(Icons.Filled.Share, stringsContext.getString(R.string.ui_settingshub_aa1025cc), stringsContext.getString(R.string.ui_settingshub_1bbd364f), onBackup)
    }
    SettingsGroup(stringsContext.getString(R.string.ui_settingshub_013710d3)) {
        SettingsLink(Icons.Filled.Info, stringsContext.getString(R.string.ui_settingshub_882454e0), onClick = onAbout)
        SettingsLink(Icons.Filled.Build, stringsContext.getString(R.string.ui_settingshub_118e81f4), onClick = onDiagnostics)
    }
}
