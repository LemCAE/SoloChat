package app.solochat.ui.settings

import app.solochat.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 沿用本组 modelList；手动添加和获取目录合并，不引入第二份模型归属。 */
@Composable
internal fun ProviderModelList(models: List<String>, selected: String,
    onModelsChange: (List<String>) -> Unit, onSelect: (String) -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(query) { mutableStateOf(12) }
    TextButton(onClick = { name = ""; adding = true }) { Text(stringsContext.getString(R.string.ui_providermodellist_18d35270)) }
    OutlinedTextField(query, { query = it }, label = { Text(stringsContext.getString(R.string.ui_providermodellist_d74aff38)) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    val filtered = (models + selected).filter { it.isNotBlank() }.distinct().sorted()
        .filter { it.contains(query.trim(), true) }
    if (filtered.isEmpty()) Text(stringsContext.getString(R.string.ui_providermodellist_8cddec52), style = MaterialTheme.typography.bodySmall)
    filtered.take(limit).forEach { id ->
        ListItem(modifier = Modifier.clickable { onSelect(id) },
            headlineContent = { Text(id) },
            leadingContent = { RadioButton(selected = id == selected, onClick = null) })
    }
    if (filtered.size > limit) TextButton(onClick = { limit += 30 }) {
        Text(stringsContext.getString(R.string.ui_providermodellist_bdd5f474, limit, filtered.size))
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false },
        title = { Text(stringsContext.getString(R.string.ui_providermodellist_21521dbc)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringsContext.getString(R.string.ui_providermodellist_26207792)) }, singleLine = true)
            Text(stringsContext.getString(R.string.ui_providermodellist_ff11de49))
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            val id = name.trim()
            onModelsChange((models + id).distinct().sorted())
            adding = false
        }) { Text(stringsContext.getString(R.string.ui_providermodellist_8f7136ed)) } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text(stringsContext.getString(R.string.ui_providermodellist_65a6ca9f)) } })
}
