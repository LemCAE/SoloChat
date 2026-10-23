package app.solochat.ui.settings

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
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(query) { mutableStateOf(12) }
    TextButton(onClick = { name = ""; adding = true }) { Text("手动添加模型") }
    OutlinedTextField(query, { query = it }, label = { Text("从本组目录选择默认模型") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    val filtered = (models + selected).filter { it.isNotBlank() }.distinct().sorted()
        .filter { it.contains(query.trim(), true) }
    if (filtered.isEmpty()) Text("暂无匹配模型，可手动添加或获取目录。", style = MaterialTheme.typography.bodySmall)
    filtered.take(limit).forEach { id ->
        ListItem(modifier = Modifier.clickable { onSelect(id) },
            headlineContent = { Text(id) },
            leadingContent = { RadioButton(selected = id == selected, onClick = null) },
            supportingContent = { Text(if (id == selected) "本组默认" else "点击设为本组默认") })
    }
    if (filtered.size > limit) TextButton(onClick = { limit += 30 }) {
        Text("显示更多（已显示 $limit / ${filtered.size}）")
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false },
        title = { Text("手动添加本组模型") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("模型 ID") }, singleLine = true)
            Text("使用服务商提供的完整 ID；不会检查模型是否可用，也不会自动生成价格。")
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            val id = name.trim()
            onModelsChange((models + id).distinct().sorted())
            adding = false
        }) { Text("添加到本组草稿") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}
