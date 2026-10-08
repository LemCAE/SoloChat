package app.solochat.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.solochat.SoloChatApp
import app.solochat.core.model.Usage
import app.solochat.core.model.estimateCost
import app.solochat.data.db.UsageEntity
import kotlinx.coroutines.launch

/**
 * 用量页。
 *
 * 三件事按"用户真正想知道什么"排：
 * ① 今天/这个月花了多少钱 ② 这些钱花在哪个模型上 ③ 有没有哪次是估算的
 *
 * **第三点必须显式标出来。** 网关没返回 usage 时我们是本地估的，
 * 如果不标注，用户会把一个估出来的数字当账单 —— 那是误导，
 * 而且会让人对整套成本功能失去信任。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScopeCompat()

    var todayTokens by remember { mutableStateOf(0L) }
    var todayCalls by remember { mutableStateOf(0L) }
    var todayCost by remember { mutableStateOf(0.0) }
    var todayRough by remember { mutableStateOf(false) }
    var monthCost by remember { mutableStateOf(0.0) }
    var byModel by remember { mutableStateOf<List<Triple<String, Long, Double>>>(emptyList()) }
    var cachedTokens by remember { mutableStateOf(0L) }

    suspend fun reload() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = calendar.timeInMillis
        val dayEnd = dayStart + 86_400_000L

        val monthStart = dayStart - (calendar.get(java.util.Calendar.DAY_OF_MONTH) - 1) * 86_400_000L

        val todayRows = app.store.usageBetween(dayStart, dayEnd)
        todayTokens = todayRows.sumOf { (it.promptTokens + it.completionTokens).toLong() }
        todayCalls = todayRows.size.toLong()
        cachedTokens = todayRows.sumOf { it.cachedTokens.toLong() }
        todayRough = todayRows.any { it.estimated }

        fun costOf(rows: List<UsageEntity>): Double = rows.sumOf { row ->
            val usage = Usage(
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                cachedTokens = row.cachedTokens,
                reasoningTokens = row.reasoningTokens,
                imageCount = row.imageCount,
                estimated = row.estimated
            )
            estimateCost(usage, app.pricing.resolve(row.model, app.runtimeConfig)).money
        }

        todayCost = costOf(todayRows)
        monthCost = costOf(app.store.usageBetween(monthStart, dayEnd))

        byModel = todayRows
            .groupBy { it.model }
            .map { (model, rows) ->
                Triple(model, rows.sumOf { (it.promptTokens + it.completionTokens).toLong() }, costOf(rows))
            }
            .sortedByDescending { it.second }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch { app.store.clearUsage(); reload() }
                    }) { Text("清空") }
                }
            )
        }
    ) { padding ->
        // ⚠️ 这一页**整体**是纵向可滚动的，而且里面的"按模型"列表**不能**用 LazyColumn。
        //
        // 两个坑叠在一起过：
        // ① 原来外层是不可滚动的 Column，内层却是一个没有高度约束的 LazyColumn。
        //    LazyColumn 会吃掉 Column 剩下的全部高度，于是底部的说明文字被挤出视口 ——
        //    而页面本身划不动，那段文字**永远看不到**。
        // ② 即使能看到，那也是"页中页"（整页不动、只有中间一小块在滚），
        //    模型一多就变成一屏只能看几行。
        //
        // 现在：外层 `verticalScroll` 负责滚动，"按模型"用普通的 `forEach` 平铺。
        // 今天的数据量最多几十行，不需要 lazy 那一套回收机制。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("今天", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "¥%.4f".format(todayCost),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "$todayCalls 次调用 · $todayTokens tokens" +
                            if (cachedTokens > 0) " · 命中缓存 $cachedTokens" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "本月 ¥%.4f".format(monthCost),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (todayRough) {
                        Text(
                            text = "包含估算：" +
                                "网关没有返回 token 数时是本地估的，金额只是数量级，不代表账单。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (byModel.isNotEmpty()) {
                Text(
                    "按模型（今天）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                )
                // 平铺而不是 LazyColumn：见上面关于"页中页"的注释。
                // 顺带解决了原来"缺 key 导致行错位"的问题 —— 没有 lazy 就没有回收。
                byModel.forEach { (model, tokens, cost) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = model,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "$tokens · ¥%.4f".format(cost),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = "价格来自内置的 140 个模型官方价（元 / 百万 token）。" +
                    "模型名没匹配到内置表时，会用你在设置里填的单价；" +
                    "两者都没有时金额显示为 0，而不是瞎猜一个。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp)
            )
        }
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
