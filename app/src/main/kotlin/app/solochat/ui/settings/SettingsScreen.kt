package app.solochat.ui.settings

import app.solochat.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.solochat.SoloChatApp
import app.solochat.core.agent.SendMode
import app.solochat.core.llm.LlmException
import app.solochat.core.protocol.PacingConfig
import app.solochat.core.protocol.PacingParam
import app.solochat.core.protocol.PacingParams
import app.solochat.core.model.PriceCatalogFormat
import app.solochat.data.SettingsStore
import app.solochat.data.ApiProfile
import app.solochat.data.ApiProfileTemplate
import app.solochat.data.SettingsSection
import app.solochat.ui.ToolTopBar
import app.solochat.ui.SettingsFormGroup
import app.solochat.ui.SettingsGroup
import app.solochat.ui.SettingsLink
import app.solochat.ui.InfoButton
import app.solochat.data.llm.OpenAiCompatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页里的"一层"页面。
 *
 * ⚠️ **刻意不用导航路由（NavHost）**：这几个子页面之间只是"往下一层 / 回上一层"，
 * 没有任何一个需要深链接（没人会想"把节奏页分享给别人"或者从通知直接跳进去），
 * 也没有跨页的参数。为它们各加一条路由，就要在 `MainActivity` 里多 plumb 一份
 * 路由表 + 每个页面多一组回调，收益是零。
 *
 * 用一个文件内的状态就够了，代价是**必须自己接管系统返回键** —— 见 `SettingsScreen`
 * 里的 `BackHandler`：不加的话按返回会从任何子页面直接弹掉整个设置页，
 * 用户以为丢了自己的改动。
 */
private enum class SettingsPage { HUB, API, PACING, PRICE, BEHAVIOR, WEB, DIAG, ABOUT }

/** 顶栏标题跟着页面走。主菜单就叫"设置"，别的用行标题，避免用户在子页面里迷路。 */
private fun pageTitle(page: SettingsPage, context: android.content.Context): String {
    return when (page) {
        SettingsPage.HUB -> context.getString(R.string.ui_settings_page_hub)
        SettingsPage.API -> context.getString(R.string.ui_settings_page_api)
        SettingsPage.PACING -> context.getString(R.string.ui_settings_page_pacing)
        SettingsPage.PRICE -> context.getString(R.string.ui_settings_page_price)
        SettingsPage.BEHAVIOR -> context.getString(R.string.ui_settings_page_behavior)
        SettingsPage.WEB -> context.getString(R.string.ui_settings_page_web)
        SettingsPage.DIAG -> context.getString(R.string.ui_settings_page_diag)
        SettingsPage.ABOUT -> context.getString(R.string.ui_settings_page_about)
    }
}

/**
 * [PacingDraftSaver] 里代替 `null` 的占位符。
 *
 * 用 NUL：`pacingDraft` 的值只有两种来源 —— 用户敲进数字输入框的内容，
 * 或者从 `Long` 转出来的字符串。两者都不可能包含 NUL，
 * 所以它不会和"用户真的输入了这个字符"撞车（换成 `""` 或 `"-"` 就会撞）。
 */
private const val PACING_DRAFT_CLEARED = "\u0000"

/**
 * [SettingsScreen] 里 `pacingDraft` 的 Saver。
 *
 * 为什么必须自己写：`rememberSaveable` 自带的 autoSaver 是把这个值**原样交给
 * Bundle** 的，只认基本类型等有限几种形状。`Map<String, String?>` 不在其中，
 * 直接 `rememberSaveable { mutableStateOf<Map<String, String?>>(emptyMap()) }`
 * 会在保存那一刻抛 "cannot be saved using the current SaveableStateRegistry" ——
 * 一旋屏就崩，比丢数据更糟。
 *
 * 形状：摊平成 `[key, value, key, value, ...]` 的字符串 List。
 * - 不用 JSON：这里只要在同一个进程生命周期内过一趟 Bundle，
 *   多引一种序列化格式就多一份要对齐的东西，而字符串 List 是 Bundle 的原生菜。
 * - 值允许为 null（= 用户把这个参数清空了），`null` 进不了 `List<String>`，
 *   所以用上面那个 NUL 哨兵顶替；`restore` 时再还原成 `null`。
 *   **不能把它压成 `""`**：`pacingDraft` 的"键在、值为 null"和"键不在"是两种不同含义
 *   （见 `pacingDraft` 的注释），压平了就会出现"清空过的参数一重建又弹回旧值"。
 */
private val PacingDraftSaver: Saver<Map<String, String?>, Any> = listSaver(
    save = { draft ->
        draft.entries.flatMap { listOf(it.key, it.value ?: PACING_DRAFT_CLEARED) }
    },
    restore = { flat ->
        buildMap<String, String?> {
            flat.chunked(2).forEach { pair ->
                val value = pair.getOrNull(1)
                put(pair[0], if (value == PACING_DRAFT_CLEARED) null else value)
            }
        }
    }
)

/**
 * 设置页。
 *
 * 结构是**主菜单 + 子页面**，不是一长条。理由：改版前所有设置（接口 / 节奏 / 价格 /
 * 模式 / 日志 / 版本）堆在一个滚动列表里，20 个节奏参数进来之后，想改个温度要
 * 划过大半屏无关内容；而"我到底能改哪些东西"这件事在长列表里根本看不出来。
 *
 * 主菜单按"用户会按什么顺序想事"排：
 * ① 先连上（接口与模型）② 再调像不像真人（节奏 / 行为）③ 然后看钱（价格）
 * ④ 出问题时去哪儿看（排查）。
 *
 * 两个刻意的设计（沿用旧版）：
 * - **Key 不回显明文**，只显示掩码；想改就整体重填。避免截图泄漏。
 * - **一键测试**必须给出可执行的结论（"连上了，延迟 320ms" / "地址或模型名不对"），
 *   而不是一个红叉。这是用户第一次用最容易卡住的地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenUsage: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val app = context.applicationContext as SoloChatApp
    val scope = rememberCoroutineScopeCompat()

    // 当前在哪一层（见 SettingsPage 的注释：这里就是"路由"）
    //
    // 用 `rememberSaveable` 而不是 `remember`：`remember` 只活在当前这一份 composition 里，
    // 进程被系统回收后重建时它会重新执行 init，用户会被**弹回主菜单** ——
    // 他本来在填「接口与模型」，屏幕一转/切个应用回来就发现自己站在设置首页，
    // 只会以为"刚才那些是不是没存上"。`SettingsPage` 是枚举（Serializable），
    // autoSaver 存得下，不需要额外写 Saver。
    var page by rememberSaveable { mutableStateOf(SettingsPage.HUB) }
    var dirtyPages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var advancedPacing by rememberSaveable { mutableStateOf(false) }

    // ⚠️ 下面整排输入框状态全部用 `rememberSaveable`，不是 `remember`。
    //
    // `remember` 的值不参与 `SavedStateRegistry`，Activity 的那份 composition 一旦重建
    // （系统回收后台进程后用户切回来、或哪天把 Manifest 里的 configChanges 去掉），
    // 这些值就全部回到 init：用户填了一半的 Base URL / 温度 / 节奏参数凭空消失。
    // 这里最贵的是**节奏页那 20 个输入框**：他是个一个格子改过来的，
    // 全丢了他不觉得自己做了什么操作，只会觉得这个 App 不可靠。
    //
    // Key 草稿仅留内存，不进入未加密的 SavedState Bundle；重建后需重新输入。
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiProfileId by rememberSaveable { mutableStateOf("") }
    var apiProfileName by rememberSaveable { mutableStateOf("") }
    var apiKeyId by rememberSaveable { mutableStateOf("") }
    var priceProfileId by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var keyEdited by rememberSaveable { mutableStateOf(false) }
    var clearApiKey by rememberSaveable { mutableStateOf(false) }
    var keyMask by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var temperature by rememberSaveable { mutableStateOf("0.85") }
    var historyLimit by rememberSaveable { mutableStateOf("0") }
    var debounceOverride by rememberSaveable { mutableStateOf("0") }
    var allowModeSwitch by rememberSaveable { mutableStateOf(true) }
    // 存 wire 字符串而不是枚举：这一页的既有约定就是"可保存的状态一律是字符串"，
    // 不必去推理 `rememberSaveable` 在枚举上走的是哪条序列化路径。
    var sendModeWire by rememberSaveable { mutableStateOf(SendMode.AUTO.wire) }
    var useOfficialPrice by rememberSaveable { mutableStateOf(true) }
    var priceCatalogJson by rememberSaveable { mutableStateOf("") }
    var priceInput by rememberSaveable { mutableStateOf("0") }
    var priceOutput by rememberSaveable { mutableStateOf("0") }
    var priceCached by rememberSaveable { mutableStateOf("0") }
    var priceConfigured by rememberSaveable { mutableStateOf(false) }
    var cachedPriceConfigured by rememberSaveable { mutableStateOf(false) }
    var showFallbackPrice by rememberSaveable { mutableStateOf(false) }

    /**
     * 节奏参数：**当前存着的整包配置** + **用户正在编辑的草稿**。
     *
     * 为什么不给 20 个参数各开一个 `var`（旧版只暴露 2 个就是那样写的）：
     * 那样每加一个参数要动五处（含界面），漏一处就是"设置里改了没生效"。
     * 这里只留两个状态，参数的**清单、标签、范围、默认值全部来自 `PacingParams` 那张表**，
     * 界面遍历它就能画出来 —— 加参数不用动这个文件。
     */
    // 这一份**刻意留在 `remember`**：它是整包 `PacingConfig`（领域对象），
    // `rememberSaveable` 的 autoSaver 只认 Bundle 装得下的类型，装不下这个 data class；
    // 而它的内容本来就全都存在 DataStore 里，重建后由 `LaunchedEffect` 从 store 重新读回来，
    // 再为它写一个 Saver 是纯粹的重复。⚠️ 也正因为它是 `remember`，
    // 下面 `LaunchedEffect` 里"重建后必须补一次领域数据"那一段不能省 ——
    // 它空了会让节奏页把 20 个参数全显示成默认值，用户一保存就把真实配置写成默认值。
    var pacingSnapshot by remember { mutableStateOf(PacingConfig()) }

    // key = param.key。值允许为 null：用户把输入框清空时存 null，
    // 这样能区分"没编辑过"（键不存在）和"清空了"（键在、值为 null），
    // 否则一删就又弹回旧值，删不干净。
    //
    // ⚠️ 这里必须自己写 Saver（见 `PacingDraftSaver`）：`rememberSaveable` 自带的
    // autoSaver 是把这个值**原样塞进 Bundle** 的，`Map<String, String?>` 既不是
    // 基本类型也不是它能接受的集合形状，直接写 `rememberSaveable { mutableStateOf(...) }`
    // 会在保存那一刻抛 "cannot be saved using the current SaveableStateRegistry"。
    // 而这一份草稿恰恰是最不该丢的：它是"参数被改过、还没点保存"的全部中间状态。
    var pacingDraft by rememberSaveable(stateSaver = PacingDraftSaver) {
        mutableStateOf<Map<String, String?>>(emptyMap())
    }

    // 日志 web 服务。同样是"填了一半的输入"：端口号和访问密码丢了，
    // 用户得回电脑上重新看一眼当初设的是什么。→ `rememberSaveable`。
    var logWebEnabled by rememberSaveable { mutableStateOf(false) }
    var logWebPort by rememberSaveable { mutableStateOf("8787") }
    var logWebLan by rememberSaveable { mutableStateOf(false) }
    var webPassword by rememberSaveable { mutableStateOf("") }

    // 上一次测试的结论要留住：用户点完"测试连通"再切出去查一眼地址、
    // 回来时如果结论没了，他只能再测一次（每次都是一次真实的网络请求 + 计费）。
    var testResult by rememberSaveable { mutableStateOf("") }
    var savedHint by rememberSaveable { mutableStateOf("") }
    var apiDraftRevision by remember { mutableStateOf(0L) }
    fun markDirty(target: SettingsPage = page) {
        if (target == SettingsPage.API) apiDraftRevision++
        if (target.name !in dirtyPages) dirtyPages = dirtyPages + target.name
        savedHint = ""
        saveFailed = false
    }

    // 请求中的标志保持 remember，不能在任务已经消失后恢复为 true。
    // - `testing` / `loadingModels` 是"请求正在飞"的瞬时标志，不是用户的内容。
    //   它们要是被恢复成 `true`，重建后按钮会永远处于 `enabled = false`（禁用）状态 ——
    //   发起请求的那个协程早就随着旧进程没了，没有任何东西会把它改回 false，
    //   用户只能杀掉 App 重来。这类"描述在途任务"的标志必须跟着任务一起死。
    // 模型清单作为接口页草稿保存；拉取成功后仍须明确保存才持久化。
    var testing by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var models by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var apiProfiles by remember { mutableStateOf(emptyList<ApiProfile>()) }
    var activeProfileId by remember { mutableStateOf("") }
    var showProfilePicker by remember { mutableStateOf(false) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var newProfileTemplate by rememberSaveable { mutableStateOf(ApiProfileTemplate.DEEPSEEK.name) }
    var confirmDeleteProfile by remember { mutableStateOf(false) }

    suspend fun loadFromStore(target: SettingsPage? = null) {
        val snapshot = app.apiSettingsSnapshot()
        apiProfiles = snapshot.apiProfiles
        activeProfileId = snapshot.apiProfileId
        if (target == null || target == SettingsPage.API) {
            apiDraftRevision++
            apiProfileId = snapshot.apiProfileId
            apiProfileName = snapshot.apiProfileName
            apiKeyId = snapshot.apiKeyId
            baseUrl = snapshot.baseUrl
            model = snapshot.model
            temperature = snapshot.temperature.toString()
            historyLimit = snapshot.historyLimit.toString()
            apiKey = ""
            keyEdited = false
            clearApiKey = false
            testResult = ""
            keyMask = app.secrets.masked(snapshot.apiKeyId)
            models = snapshot.modelList
        }
        // 节奏整包拿过来，界面再按表逐项读 —— 不在这里逐个字段搬
        if (target == null || target == SettingsPage.PACING) {
            pacingSnapshot = snapshot.pacing
            pacingDraft = emptyMap()
        }
        if (target == null || target == SettingsPage.BEHAVIOR) {
            debounceOverride = snapshot.debounceOverrideMs.toString()
            allowModeSwitch = snapshot.allowModeSwitch
            sendModeWire = snapshot.sendMode.wire
        }
        if (target == null || target == SettingsPage.PRICE) {
            priceProfileId = snapshot.apiProfileId
            useOfficialPrice = snapshot.useOfficialPrice
            val catalog = snapshot.priceCatalog ?: withContext(Dispatchers.IO) { app.pricing.initialCatalog }
            priceCatalogJson = PriceCatalogFormat.encode(catalog)
            priceInput = snapshot.priceInput.toString()
            priceOutput = snapshot.priceOutput.toString()
            priceCached = snapshot.priceCached.toString()
            priceConfigured = snapshot.priceConfigured
            cachedPriceConfigured = snapshot.cachedPriceConfigured
        }
        if (target == null || target == SettingsPage.WEB) {
            logWebEnabled = snapshot.logWebEnabled
            logWebPort = snapshot.logWebPort.toString()
            logWebLan = snapshot.logWebLan
            webPassword = app.secrets.webPassword
        }
    }

    /**
     * 一个节奏参数"现在存着的值"。
     *
     * 包一层 `runCatching` 不是为了防 DataStore（那边已经夹过上下限），
     * 而是防"表和配置对不上"：万一以后表里进了个当前 [PacingConfig] 没有的字段，
     * 设置页应当退回默认值显示，而不是整页崩掉。表的默认值等于配置的默认值，
     * 这条由 `PacingParamsTest` 钉着。
     */
    fun storedValue(param: PacingParam): Long =
        runCatching { param.read(pacingSnapshot) }.getOrDefault(param.default)

    /**
     * "本页这一次进入，store 里的值有没有灌进输入框"。
     *
     * ⚠️ 这道判断是上面那批 `rememberSaveable` 能真正生效的前提，不是可有可无的清理：
     * `LaunchedEffect(Unit)` 在 composition 重建之后**一定会重跑**，而重建恰恰是
     * `rememberSaveable` 把草稿恢复出来的时刻。如果这里仍然无条件调 `loadFromStore()`，
     * 刚恢复出来的草稿会被 store 里的旧值当场盖掉 —— 那么上面所有 `rememberSaveable`
     * 都等于白改：改了也只是"先恢复、再被覆盖"，用户看到的还是丢数据。
     */
    var loadedFromStore by rememberSaveable { mutableStateOf(false) }
    var loadFailure by remember { mutableStateOf("") }
    var loadAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(loadAttempt) {
        try {
            if (loadedFromStore) {
                // 重建（进程被回收后切回来）或从日志页返回：输入框已经有值了，不能再灌。
                // 但 `pacingSnapshot` 是 remember 的领域数据，没有 Saver，重建后必须补回来；
                // 模型列表已是可恢复草稿，只有接口页没有未保存修改时才刷新。
                // `pacingSnapshot` 若是空的，节奏页会把 20 个参数全按默认值显示，
                // 用户看一眼以为"我的设置被重置了"，随手一保存就把真实配置覆盖成默认值 ——
                // 这是会写坏用户数据的，比丢草稿严重得多。
                val snapshot = app.apiSettingsSnapshot()
                pacingSnapshot = snapshot.pacing
                apiProfiles = snapshot.apiProfiles
                activeProfileId = snapshot.apiProfileId
                if (SettingsPage.API.name !in dirtyPages) models = snapshot.modelList
                if (SettingsPage.API.name !in dirtyPages) loadFromStore(SettingsPage.API)
                if (SettingsPage.PRICE.name !in dirtyPages) loadFromStore(SettingsPage.PRICE)
            } else {
                loadFromStore()
                loadedFromStore = true
            }
            loadFailure = ""
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            loadFailure = stringsContext.getString(R.string.ui_settingsscreen_12858e1d)
        }
    }

    fun persist(afterSave: () -> Unit = {}) {
        if (saving || testing || loadingModels || !loadedFromStore || page in listOf(SettingsPage.HUB, SettingsPage.DIAG, SettingsPage.ABOUT)) return
        val target = page
        saving = true
        scope.launch {
            try {
                val current = app.settings.snapshot()
                fun decimal(raw: String, label: String): Double = raw.trim().toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_settingsscreen_30bf664a, label))
                val section = SettingsSection.valueOf(target.name)
                if (target == SettingsPage.API) {
                    require(!keyEdited || apiKey.isNotBlank() || clearApiKey) { stringsContext.getString(R.string.ui_settingsscreen_acf36ad5) }
                }
                val draft = when (target) {
                    SettingsPage.API -> current.copy(apiProfileId = apiProfileId, apiProfileName = apiProfileName, apiKeyId = apiKeyId,
                        baseUrl = baseUrl, model = model,
                        temperature = decimal(temperature, stringsContext.getString(R.string.ui_settingsscreen_54dfd3c6)), modelList = models,
                        historyLimit = historyLimit.trim().toIntOrNull()
                            ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_settingsscreen_4e8ba050)))
                    SettingsPage.PACING -> current.copy(pacing = PacingParams.all.fold(current.pacing) { config, param ->
                        val raw = pacingDraft[param.key]?.trim().orEmpty()
                        if (raw.isEmpty()) config else param.write(config, raw.toLongOrNull()
                            ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_settingsscreen_8dad3ed0, param.label)))
                    })
                    SettingsPage.PRICE -> current.copy(apiProfileId = priceProfileId, useOfficialPrice = useOfficialPrice,
                        priceCatalog = PriceCatalogFormat.decode(priceCatalogJson),
                        priceInput = decimal(priceInput, stringsContext.getString(R.string.ui_settingsscreen_542be564)), priceOutput = decimal(priceOutput, stringsContext.getString(R.string.ui_settingsscreen_a0f8ad6d)),
                        priceCached = decimal(priceCached, stringsContext.getString(R.string.ui_settingsscreen_8c286fec)), priceConfigured = priceConfigured,
                        cachedPriceConfigured = cachedPriceConfigured)
                    SettingsPage.BEHAVIOR -> current.copy(allowModeSwitch = allowModeSwitch,
                        sendMode = SendMode.fromWire(sendModeWire), debounceOverrideMs = debounceOverride.trim().toLongOrNull()
                            ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_settingsscreen_cb315c52)))
                    SettingsPage.WEB -> {
                        require(!logWebEnabled || !logWebLan || webPassword.isNotBlank()) { stringsContext.getString(R.string.ui_settingsscreen_93534a4a) }
                        current.copy(logWebEnabled = logWebEnabled, logWebLan = logWebLan,
                            logWebPort = logWebPort.trim().toIntOrNull()
                                ?: throw IllegalArgumentException(stringsContext.getString(R.string.ui_settingsscreen_cf1b1c5b)))
                    }
                    else -> return@launch
                }
                val oldPassword = if (target == SettingsPage.WEB) app.secrets.webPassword else ""
                val replacementKey = if (target != SettingsPage.API) null else if (clearApiKey) ""
                    else apiKey.takeIf { it.isNotBlank() }
                app.saveSettings(section, draft, replacementKey)
                when (target) {
                    SettingsPage.API -> Unit
                    SettingsPage.PACING -> app.refreshRhythm()
                    SettingsPage.PRICE -> Unit
                    SettingsPage.BEHAVIOR -> app.refreshRhythm()
                    SettingsPage.WEB -> {
                        app.secrets.webPassword = webPassword
                        if (current.logWebEnabled != draft.logWebEnabled || current.logWebPort != draft.logWebPort ||
                            current.logWebLan != draft.logWebLan || oldPassword != webPassword) {
                            app.applyLogWebSetting(draft.logWebEnabled, draft.logWebPort, draft.logWebLan)
                        }
                    }
                    else -> Unit
                }
                loadFromStore(target)
                dirtyPages = dirtyPages - target.name
                savedHint = stringsContext.getString(R.string.ui_settingsscreen_d8182a0e, pageTitle(target, stringsContext))
                saveFailed = false
                afterSave()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                savedHint = if (error is IllegalArgumentException) error.message.orEmpty() else stringsContext.getString(R.string.ui_settingsscreen_fe73b086)
                saveFailed = true
            } finally { saving = false }
        }
    }

    val canChangeProfiles = loadedFromStore && !saving && !testing && !loadingModels &&
        SettingsPage.API.name !in dirtyPages && SettingsPage.PRICE.name !in dirtyPages

    fun inspectApi(fetchModels: Boolean) {
        if (saving || testing || loadingModels) return
        val revision = apiDraftRevision
        val draftUrl = SettingsStore.normalizeBaseUrl(baseUrl)
        val draftTypedKey = apiKey
        val draftKeyId = apiKeyId
        val draftClear = clearApiKey
        val draftKeyMissing = keyEdited && draftTypedKey.isBlank() && !draftClear
        val target = model.trim()
        if (fetchModels) loadingModels = true else testing = true
        testResult = ""
        scope.launch {
            try {
                require(!draftKeyMissing) { stringsContext.getString(R.string.ui_settingsscreen_92fb2858) }
                val result = withContext(Dispatchers.IO) {
                    val key = if (draftClear) "" else draftTypedKey.ifBlank { app.secrets.apiKeyFor(draftKeyId) }
                    val client = OpenAiCompatClient({ draftUrl }, { key })
                    if (fetchModels) client.listModels() to null else null to client.ping(target)
                }
                if (revision != apiDraftRevision) testResult = stringsContext.getString(R.string.ui_settingsscreen_ded97ef7)
                else if (fetchModels) {
                    models = (models + result.first.orEmpty()).distinct().sorted()
                    markDirty(SettingsPage.API)
                    testResult = stringsContext.getString(R.string.ui_settingsscreen_9381ce9a, models.size)
                } else testResult = stringsContext.getString(R.string.ui_settingsscreen_a9113188, result.second)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                testResult = if (revision != apiDraftRevision) stringsContext.getString(R.string.ui_settingsscreen_ded97ef7)
                    else if (draftKeyMissing) stringsContext.getString(R.string.ui_settingsscreen_92fb2858)
                    else (error as? LlmException)?.error?.advice() ?: stringsContext.getString(R.string.ui_settingsscreen_5d44c881)
            } finally {
                if (fetchModels) loadingModels = false else testing = false
            }
        }
    }

    fun changeProfiles(action: suspend () -> Unit) {
        if (!canChangeProfiles) return
        saving = true
        scope.launch {
            try {
                action()
                loadFromStore(SettingsPage.API)
                loadFromStore(SettingsPage.PRICE)
                savedHint = stringsContext.getString(R.string.ui_settingsscreen_796aa13b)
                saveFailed = false
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                savedHint = stringsContext.getString(R.string.ui_settingsscreen_c501ec66)
                saveFailed = true
                // 删除元数据成功而密钥清理失败时，也要显示实际选择。
                runCatching { loadFromStore(SettingsPage.API); loadFromStore(SettingsPage.PRICE) }
            } finally { saving = false }
        }
    }

    if (showProfilePicker) AlertDialog(
        onDismissRequest = { showProfilePicker = false },
        title = { Text(stringsContext.getString(R.string.ui_settingsscreen_448cbd0f)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringsContext.getString(R.string.ui_settingsscreen_b6c0ccb5))
            apiProfiles.forEach { profile ->
                TextButton(enabled = canChangeProfiles, onClick = {
                    showProfilePicker = false
                    if (profile.id != activeProfileId) changeProfiles { app.selectApiProfile(profile.id) }
                }) { Text((if (profile.id == activeProfileId) stringsContext.getString(R.string.ui_settingsscreen_227e440f) else stringsContext.getString(R.string.ui_settingsscreen_88cde357)) + profile.name) }
            }
            if (apiProfiles.isEmpty()) Text(stringsContext.getString(R.string.ui_settingsscreen_0e6c5049))
        } },
        confirmButton = { TextButton(onClick = { showProfilePicker = false }) { Text(stringsContext.getString(R.string.ui_settingsscreen_251f8761)) } }
    )
    if (showCreateProfile) AlertDialog(modifier = Modifier.imePadding(),
        onDismissRequest = { showCreateProfile = false },
        title = { Text(stringsContext.getString(R.string.ui_settingsscreen_d0a8ab59)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ApiProfileTemplate.entries.forEach { template ->
                ListItem(modifier = Modifier.clickable {
                    newProfileTemplate = template.name; newProfileName = stringsContext.getString(template.labelResId)
                }, headlineContent = { Text(stringsContext.getString(template.labelResId)) },
                    leadingContent = { RadioButton(newProfileTemplate == template.name, onClick = null) })
            }
            OutlinedTextField(value = newProfileName, onValueChange = { newProfileName = it },
                label = { Text(stringsContext.getString(R.string.ui_settingsscreen_7510281e)) }, singleLine = true)
            val template = ApiProfileTemplate.valueOf(newProfileTemplate)
            Text(stringsContext.getString(template.hintResId), style = MaterialTheme.typography.bodySmall)
            if (template.baseUrl.isNotBlank()) Text(template.baseUrl, style = MaterialTheme.typography.bodySmall)
            Text(stringsContext.getString(R.string.ui_settingsscreen_aa3e55a0), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = canChangeProfiles && newProfileName.isNotBlank(), onClick = {
            showCreateProfile = false
            val name = newProfileName
            val template = ApiProfileTemplate.valueOf(newProfileTemplate)
            changeProfiles {
                val id = app.addApiProfile(name, template)
                app.selectApiProfile(id)
                newProfileName = ""
            }
        }) { Text(stringsContext.getString(R.string.ui_settingsscreen_2e1d3a3d)) } },
        dismissButton = { TextButton(onClick = { showCreateProfile = false }) { Text(stringsContext.getString(R.string.ui_settingsscreen_65a6ca9f)) } }
    )
    if (confirmDeleteProfile) AlertDialog(
        onDismissRequest = { confirmDeleteProfile = false },
        title = { Text(stringsContext.getString(R.string.ui_settingsscreen_5ab68d29)) },
        text = { Text(stringsContext.getString(R.string.ui_settingsscreen_3fb8903a, apiProfileName)) },
        confirmButton = { TextButton(enabled = canChangeProfiles, onClick = {
            confirmDeleteProfile = false
            val id = activeProfileId
            changeProfiles { app.deleteApiProfile(id) }
        }) { Text(stringsContext.getString(R.string.ui_settingsscreen_b59c7514)) } },
        dismissButton = { TextButton(onClick = { confirmDeleteProfile = false }) { Text(stringsContext.getString(R.string.ui_settingsscreen_65a6ca9f)) } }
    )

    fun leavePage() {
        if (saving) return
        if (page.name in dirtyPages) confirmLeave = true
        else if (page == SettingsPage.HUB) onBack() else { page = SettingsPage.HUB; savedHint = "" }
    }
    BackHandler { leavePage() }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirmLeave = false },
            title = { Text(stringsContext.getString(R.string.ui_settingsscreen_12fbab73)) }, text = { Text(stringsContext.getString(R.string.ui_settingsscreen_8509a957)) },
            confirmButton = { TextButton(enabled = !saving, onClick = { persist {
                confirmLeave = false; page = SettingsPage.HUB; savedHint = ""
            } }) { Text(if (saving) stringsContext.getString(R.string.ui_settingsscreen_ab212277) else stringsContext.getString(R.string.ui_settingsscreen_45b80582)) } },
            dismissButton = { Column {
                TextButton(enabled = !saving, onClick = {
                    val target = page
                    scope.launch {
                        loadFromStore(target); dirtyPages = dirtyPages - target.name
                        confirmLeave = false; page = SettingsPage.HUB; savedHint = ""
                    }
                }) { Text(stringsContext.getString(R.string.ui_settingsscreen_ee436641)) }
                TextButton(enabled = !saving, onClick = { confirmLeave = false }) { Text(stringsContext.getString(R.string.ui_settingsscreen_106b68e5)) }
            } }
        )
    }
    val pageScroll = key(page) { rememberScrollState() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(savedHint, saveFailed) {
        if (savedHint.isNotBlank() && !saveFailed) {
            snackbar.showSnackbar(savedHint)
            savedHint = ""
        }
    }
    // 价格页使用独立窗口与滚动节点，避免继承接口表单的位置及焦点请求。
    // 模型/表管理对话框只覆盖此窗口，不卸载父价格草稿。
    if (page == SettingsPage.PRICE) Dialog(onDismissRequest = { leavePage() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { ToolTopBar(stringsContext.getString(R.string.ui_settingsscreen_58d4fce7), onBack = { leavePage() }, backEnabled = !saving,
                actions = { TextButton(enabled = loadedFromStore && !saving && page.name in dirtyPages,
                    onClick = { persist() }) { Text(if (saving) stringsContext.getString(R.string.ui_settingsscreen_ab212277) else stringsContext.getString(R.string.ui_settingsscreen_b2ef2293)) } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (saveFailed && savedHint.isNotBlank()) Text(savedHint, color = MaterialTheme.colorScheme.error)
                if (loadFailure.isNotBlank()) {
                    Text(loadFailure)
                    TextButton(onClick = { loadFailure = ""; loadAttempt++ }) { Text(stringsContext.getString(R.string.ui_settingsscreen_7723ab04)) }
                } else if (!loadedFromStore) Text(stringsContext.getString(R.string.ui_settingsscreen_6f80fc5d)) else {
                    if (activeProfileId.isBlank()) Text(stringsContext.getString(R.string.ui_settingsscreen_377bc92a)) else {
                        Text(stringsContext.getString(R.string.ui_settingsscreen_762a13b1, apiProfileName),
                            style = MaterialTheme.typography.bodySmall)
                        SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_bc4c87a9)) {
                            SwitchRow(
                                title = stringsContext.getString(R.string.ui_settingsscreen_58efe391),
                                hint = stringsContext.getString(R.string.ui_settingsscreen_b29489cf),
                                checked = useOfficialPrice,
                                onCheckedChange = { useOfficialPrice = it; markDirty() }
                            )
                        }
                        if (priceCatalogJson.isNotBlank()) {
                            val catalog = remember(priceCatalogJson) { PriceCatalogFormat.decode(priceCatalogJson) }
                            key(priceProfileId) {
                                PriceTableEditor(catalog, currentModel = model, useTable = useOfficialPrice, modelChoices = models,
                                    onChange = { priceCatalogJson = PriceCatalogFormat.encode(it); markDirty() })
                            }
                        }
                        TextButton(onClick = { showFallbackPrice = !showFallbackPrice }) {
                            Text(stringsContext.getString(R.string.ui_settingsscreen_2b8253ac, if (showFallbackPrice) stringsContext.getString(R.string.ui_settings_collapse) else stringsContext.getString(R.string.ui_settings_expand), if (priceConfigured) stringsContext.getString(R.string.ui_settings_configured) else stringsContext.getString(R.string.ui_settings_unconfigured)))
                        }
                        if (showFallbackPrice) SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_32f0f00e)) {
                            SwitchRow(title = stringsContext.getString(R.string.ui_settingsscreen_6d3d85fd), hint = stringsContext.getString(R.string.ui_settingsscreen_d829ab63),
                                checked = priceConfigured, onCheckedChange = { priceConfigured = it; markDirty() })
                            NumberField(
                                label = stringsContext.getString(R.string.ui_settingsscreen_034000fb),
                                value = priceInput,
                                onChange = { priceInput = it; priceConfigured = true; markDirty() },
                                hint = stringsContext.getString(R.string.ui_settingsscreen_eb4df3ac)
                            )
                            NumberField(
                                label = stringsContext.getString(R.string.ui_settingsscreen_e3ff9e2e),
                                value = priceOutput,
                                onChange = { priceOutput = it; priceConfigured = true; markDirty() },
                                hint = stringsContext.getString(R.string.ui_settingsscreen_1f4f6d4d)
                            )
                            // 这一项以前在设置页上没有入口（只有键和持久化），只能被读不能被改；
                            // 命中缓存的输入通常比普通输入便宜一个数量级，自填单价时不能少这一项。
                            SwitchRow(title = stringsContext.getString(R.string.ui_settingsscreen_d4775bb0), hint = stringsContext.getString(R.string.ui_settingsscreen_e9a7841d),
                                checked = cachedPriceConfigured, onCheckedChange = { cachedPriceConfigured = it; markDirty() })
                            NumberField(
                                label = stringsContext.getString(R.string.ui_settingsscreen_2fe445a2),
                                value = priceCached,
                                onChange = { priceCached = it; cachedPriceConfigured = true; priceConfigured = true; markDirty() },
                                hint = stringsContext.getString(R.string.ui_settingsscreen_acd1e962)
                            )
                        }

                        SaveButton(enabled = !saving && loadedFromStore && page.name in dirtyPages) { persist() }
                    }

                }
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { if (page != SettingsPage.PRICE) SnackbarHost(snackbar) },
        topBar = {
            ToolTopBar(title = pageTitle(page, stringsContext), onBack = { leavePage() }, backEnabled = !saving,
                actions = {
                    if (page !in listOf(SettingsPage.HUB, SettingsPage.DIAG, SettingsPage.ABOUT)) {
                        TextButton(enabled = loadedFromStore && !saving && !testing && !loadingModels && page.name in dirtyPages,
                            onClick = { persist() }) {
                            Text(if (saving) stringsContext.getString(R.string.ui_settingsscreen_ab212277) else stringsContext.getString(R.string.ui_settingsscreen_b2ef2293))
                        }
                    }
                })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 补上项目统一的 `consumeWindowInsets(padding)`：
                // `Modifier.padding()` 只是把内容往里缩，它**没有声明**"状态栏 / 导航栏
                // 那部分 inset 已经让过位了"。这一步就是那句声明，后面任何读 inset 的东西
                // （`imePadding()`、里层 `Scaffold`、LazyColumn 的 `contentPadding`）
                // 就不会把这几十 dp 再算第二遍。本页现在是 `padding + verticalScroll`
                // 的写法，只差这一条；加上它以后，将来给这里的输入框补键盘处理
                // （`imePadding()`）时不会踩到"底部被算两遍、输入框被顶到半空"那个坑。
                .consumeWindowInsets(padding)
                // 编辑子页有输入框；IME 必须在已消费系统栏之后处理。
                //
                // `MainActivity` 调了 `enableEdgeToEdge()`，它把 `decorFitsSystemWindows`
                // 设成 false —— 窗口从此**不再为键盘让位**，而 Scaffold 默认的
                // `contentWindowInsets` 又是 systemBars（不含 IME）。
                // 少这一行，键盘弹出时它是**盖在**内容上的：滚动视口还是整屏高，
                // 于是"把输入框滚进可视区"会把它滚到键盘背后去 ——
                // 表现就是"下面的单价/节奏格子点开键盘后根本看不见在打什么"。
                //
                // 顺序也不能换：必须在 `consumeWindowInsets` 之后（否则导航栏被算两遍），
                // 且在 `verticalScroll` **之前**（否则视口高度没减掉键盘，滚也滚不出来）。
                .imePadding()
                .verticalScroll(pageScroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (savedHint.isNotBlank() && saveFailed) {
                Text(savedHint, color = MaterialTheme.colorScheme.error)
            }

            // ⚠️ "Key 是明文保存的"必须显式告知，而且这张卡放在**所有页面**的顶部：
            // 真正要填 Key 的地方是「接口与模型」子页面，只留在主菜单等于没告知。
            if (app.secrets.degraded) {
                WarningCard(
                    stringsContext.getString(R.string.ui_settingsscreen_6d00c926),
                    stringsContext.getString(R.string.ui_settingsscreen_7501bfe5) +
                        stringsContext.getString(R.string.ui_settingsscreen_beb62a7d)
                )
            }

            if (loadFailure.isNotBlank()) {
                Text(loadFailure)
                TextButton(onClick = { loadFailure = ""; loadAttempt++ }) { Text(stringsContext.getString(R.string.ui_settingsscreen_7723ab04)) }
            } else if (!loadedFromStore) Text(stringsContext.getString(R.string.ui_settingsscreen_6f80fc5d)) else when (page) {

                // 主菜单只导航到既有页面，不创建另一份业务状态或保存入口。
                SettingsPage.HUB -> {
                    SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_c9ee3546)) {
                        Text(apiProfileName)
                        TextButton(contentPadding = PaddingValues(horizontal = 0.dp),
                            enabled = canChangeProfiles && apiProfiles.isNotEmpty(),
                            onClick = { showProfilePicker = true }) { Text(stringsContext.getString(R.string.ui_settingsscreen_00b3f86a)) }
                    }
                    val priceSummary = remember(priceCatalogJson, useOfficialPrice) {
                        if (!useOfficialPrice) stringsContext.getString(R.string.ui_settingsscreen_c5d99d63) else {
                            val catalog = PriceCatalogFormat.decode(priceCatalogJson)
                            stringsContext.getString(R.string.ui_settingsscreen_14d1a4d8, catalog.tables.first { it.id == catalog.selectedTableId }.name)
                        }
                    }
                    SettingsHub(
                        modelSummary = model.takeIf { it.isNotBlank() }?.let { stringsContext.getString(R.string.ui_settingsscreen_3ac5ca23, it) } ?: stringsContext.getString(R.string.ui_settingsscreen_3f7eb7c1),
                        priceSummary = priceSummary,
                        onApi = { page = SettingsPage.API }, onPacing = { page = SettingsPage.PACING },
                        onBehavior = { page = SettingsPage.BEHAVIOR }, onPrice = { page = SettingsPage.PRICE },
                        onPersonas = onOpenPersonas, onMemory = onOpenMemory, onUsage = onOpenUsage,
                        onBackup = onOpenBackup, onAbout = { page = SettingsPage.ABOUT },
                        onDiagnostics = { page = SettingsPage.DIAG })
                }

                // ---------------- ① 连上 ----------------
                SettingsPage.API -> {
                    SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_83b2b20e)) {
                        Text(stringsContext.getString(R.string.ui_settingsscreen_14d1a4d8, apiProfileName))
                        Text(stringsContext.getString(R.string.ui_settingsscreen_d254ac48),
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = canChangeProfiles && apiProfiles.isNotEmpty(),
                                onClick = { showProfilePicker = true }) { Text(stringsContext.getString(R.string.ui_settingsscreen_e803c9c4)) }
                            TextButton(enabled = canChangeProfiles, onClick = {
                                if (newProfileName.isBlank()) newProfileName = stringsContext.getString(ApiProfileTemplate.valueOf(newProfileTemplate).labelResId)
                                showCreateProfile = true
                            }) { Text(stringsContext.getString(R.string.ui_settingsscreen_d0a8ab59)) }
                            TextButton(enabled = canChangeProfiles && activeProfileId.isNotBlank(),
                                onClick = { confirmDeleteProfile = true }) { Text(stringsContext.getString(R.string.ui_settingsscreen_177550cd)) }
                        }
                        if (!canChangeProfiles && !saving) Text(stringsContext.getString(R.string.ui_settingsscreen_99fdb658))
                        if (activeProfileId.isNotBlank()) OutlinedTextField(value = apiProfileName,
                            onValueChange = { apiProfileName = it; markDirty() }, label = { Text(stringsContext.getString(R.string.ui_settingsscreen_7510281e)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (activeProfileId.isNotBlank()) {
                        SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_66e3a65d)) {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it; markDirty() },
                                label = { Text(stringsContext.getString(R.string.ui_settings_base_url)) },
                                trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_settings_base_url), stringsContext.getString(R.string.ui_settingsscreen_ec73e262)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it; keyEdited = it.isNotBlank(); markDirty() },
                                enabled = !clearApiKey,
                                label = { Text(stringsContext.getString(R.string.ui_settingsscreen_d906db49)) },
                                placeholder = { if (keyMask.isNotBlank()) Text(keyMask) },
                                trailingIcon = { InfoButton(stringsContext.getString(R.string.ui_settingsscreen_d906db49), stringsContext.getString(R.string.ui_settingsscreen_4db6b09e)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringsContext.getString(R.string.ui_settingsscreen_a01e9885), modifier = Modifier.weight(1f))
                                Switch(checked = clearApiKey, onCheckedChange = {
                                    clearApiKey = it; apiKey = ""; keyEdited = false; markDirty()
                                })
                            }
                        }
                        SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_ce24c42f)) {
                            ProviderModelList(models, model, onModelsChange = { models = it; markDirty() },
                                onSelect = { model = it; markDirty() })
                            Button(enabled = !saving && !testing && !loadingModels,
                                onClick = { inspectApi(true) }) { Text(if (loadingModels) stringsContext.getString(R.string.ui_settingsscreen_31e5509b) else stringsContext.getString(R.string.ui_settingsscreen_f066a89c)) }
                            TextButton(enabled = SettingsPage.API.name !in dirtyPages, onClick = { page = SettingsPage.PRICE }) {
                                Text(stringsContext.getString(R.string.ui_settingsscreen_c0748174))
                            }
                            if (SettingsPage.API.name in dirtyPages) Text(stringsContext.getString(R.string.ui_settingsscreen_63449433),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_5ab2cfbc)) {
                            Text(stringsContext.getString(R.string.ui_settingsscreen_77d4fd55),
                                style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !saving && !testing && !loadingModels,
                                onClick = { inspectApi(false) }) { Text(if (testing) stringsContext.getString(R.string.ui_settingsscreen_06a550a4) else stringsContext.getString(R.string.ui_settingsscreen_e16b76d1)) }
                        }
                        if (testResult.isNotBlank()) Text(testResult, style = MaterialTheme.typography.bodySmall)
                        SettingsFormGroup(stringsContext.getString(R.string.ui_settingsscreen_4d50eaeb)) {
                            NumberField(
                                label = stringsContext.getString(R.string.ui_settingsscreen_811f5757),
                                value = temperature,
                                onChange = { temperature = it; markDirty() },
                                hint = stringsContext.getString(R.string.ui_settingsscreen_9e5b0039)
                            )
                            NumberField(
                                label = stringsContext.getString(R.string.ui_settingsscreen_22981b7d),
                                value = historyLimit,
                                onChange = { historyLimit = it; markDirty() },
                                hint = stringsContext.getString(R.string.ui_settingsscreen_3714c766)
                            )
                        }
                        SaveButton(enabled = !saving && !testing && !loadingModels && loadedFromStore && page.name in dirtyPages) { persist() }
                    } else Text(stringsContext.getString(R.string.ui_settingsscreen_595dc936))
                }

                // ---------------- ② 节奏（全部参数） ----------------
                SettingsPage.PACING -> {
                    val commonKeys = setOf("min_ms_per_char", "max_ms_per_char", "gap_min_ms", "gap_max_ms")
                    TextButton(onClick = { advancedPacing = !advancedPacing }) {
                        Text(if (advancedPacing) stringsContext.getString(R.string.ui_settingsscreen_76e4f153) else stringsContext.getString(R.string.ui_settingsscreen_226cbaa8))
                    }
                    // 清单与范围仍只来自参数表；常用项筛选不改变未展示参数的值。
                    PacingParams.grouped().forEach { (group, params) ->
                        val shown = if (advancedPacing) params else params.filter { it.key in commonKeys }
                        if (shown.isNotEmpty()) SectionTitle(group)
                        shown.forEach { param ->
                            NumberField(
                                label = param.label,
                                // 编辑过就用草稿（可能是空串 = 用户清空了），
                                // 没编辑过才显示存着的值。不能用 `?:` 合并，
                                // 否则"清空"会被当成"没编辑"，一删就又弹回旧值。
                                value = if (pacingDraft.containsKey(param.key)) {
                                    pacingDraft[param.key].orEmpty()
                                } else {
                                    storedValue(param).toString()
                                },
                                onChange = { pacingDraft = pacingDraft + (param.key to it); markDirty() },
                                hint = stringsContext.getString(R.string.ui_settingsscreen_f2959d97, param.hint, param.min, param.max) +
                                    stringsContext.getString(R.string.ui_settingsscreen_14919d0a)
                            )
                        }
                    }

                    SaveButton(enabled = !saving && loadedFromStore && page.name in dirtyPages) { persist() }
                }

                // ---------------- ③ 钱 ----------------
                SettingsPage.PRICE -> Unit

                // ---------------- 聊天行为 ----------------
                SettingsPage.BEHAVIOR -> {
                    SectionTitle(stringsContext.getString(R.string.ui_settingsscreen_bce48915))
                    // 选中态用 `RadioButton` 承载，点击交给整行 —— 与模型列表同一个写法
                    // （理由见下面 `ModelPicker` 里的注释：不要把选中态拼进字符串）。
                    ListItem(
                        modifier = Modifier.clickable { sendModeWire = SendMode.AUTO.wire; markDirty() },
                        leadingContent = {
                            RadioButton(selected = sendModeWire == SendMode.AUTO.wire, onClick = null)
                        },
                        headlineContent = { Text(stringsContext.getString(R.string.ui_settingsscreen_e65cd484)) },
                        supportingContent = {
                            Text(
                                stringsContext.getString(R.string.ui_settingsscreen_591b3c08),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    ListItem(
                        modifier = Modifier.clickable { sendModeWire = SendMode.MANUAL.wire; markDirty() },
                        leadingContent = {
                            RadioButton(selected = sendModeWire == SendMode.MANUAL.wire, onClick = null)
                        },
                        headlineContent = { Text(stringsContext.getString(R.string.ui_settingsscreen_e7c66d42)) },
                        supportingContent = {
                            Text(
                                stringsContext.getString(R.string.ui_settingsscreen_a4d90171),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )

                    SectionTitle(stringsContext.getString(R.string.ui_settingsscreen_62149329))
                    SwitchRow(
                        title = stringsContext.getString(R.string.ui_settingsscreen_f66695c3),
                        hint = stringsContext.getString(R.string.ui_settingsscreen_eebd4cd3),
                        checked = allowModeSwitch,
                        onCheckedChange = { allowModeSwitch = it; markDirty() }
                    )

                    SectionTitle(stringsContext.getString(R.string.ui_settingsscreen_8351a051))
                    NumberField(
                        label = stringsContext.getString(R.string.ui_settingsscreen_7ca883f6),
                        value = debounceOverride,
                        onChange = { debounceOverride = it; markDirty() },
                        hint = stringsContext.getString(R.string.ui_settingsscreen_e2c69a2b)
                    )

                    SaveButton(enabled = !saving && loadedFromStore && page.name in dirtyPages) { persist() }
                }

                SettingsPage.ABOUT -> AboutUpdatesContent(onBackup = onOpenBackup)

                SettingsPage.DIAG -> {
                    SettingsGroup(stringsContext.getString(R.string.ui_settingsscreen_519cfddb)) {
                        SettingsLink(Icons.Filled.Build, stringsContext.getString(R.string.ui_settingsscreen_ef709a6e), onClick = onOpenLogs)
                        SettingsLink(Icons.Filled.Settings, stringsContext.getString(R.string.ui_settingsscreen_30b1f710)) {
                            page = SettingsPage.WEB
                        }
                    }
                    Text(stringsContext.getString(R.string.ui_settingsscreen_1d452d28),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                SettingsPage.WEB -> {
                    LogWebControls(
                        app = app,
                        enabled = logWebEnabled,
                        onEnabledChange = { logWebEnabled = it; markDirty(SettingsPage.WEB) },
                        port = logWebPort,
                        onPortChange = { logWebPort = it; markDirty(SettingsPage.WEB) },
                        lan = logWebLan,
                        onLanChange = { logWebLan = it; markDirty(SettingsPage.WEB) },
                        password = webPassword,
                        onPasswordChange = { webPassword = it; markDirty(SettingsPage.WEB) },
                        onApply = { persist() }
                    )

                    SaveButton(enabled = !saving && loadedFromStore && page.name in dirtyPages) { persist() }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 子页面末尾的保存按钮。
 *
 * 顶栏那个保留着（所有页面都能存），但手机上顶栏在单手够不到的地方，
 * 而"改完就想存"是这里最常见的动作，所以页尾再给一个。
 */
@Composable
private fun SaveButton(enabled: Boolean = true, onClick: () -> Unit) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    TextButton(enabled = enabled, onClick = onClick) { Text(stringsContext.getString(R.string.ui_settingsscreen_b2ef2293)) }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        trailingIcon = { InfoButton(label, hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        InfoButton(title, hint)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 日志 web 服务的开关与地址展示。
 *
 * 这块的交互重点是**安全约束要能被看懂**：说明书上写"日志里有你的全部对话内容"，
 * 而不是干巴巴一个"允许局域网"。因为一旦开了局域网又没密码，同一个 WiFi 下
 * 任何人都能在浏览器里翻你和她聊了什么。
 *
 * 三条规则在这里落地（服务端还有一道）：
 * 1. 默认关
 * 2. 默认只监听本机（要 `adb forward` 或用手机浏览器看）
 * 3. **要开局域网就必须设密码**，否则保存时会被拒绝并给出原因
 */
@Composable
private fun LogWebControls(
    app: SoloChatApp,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    lan: Boolean,
    onLanChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onApply: () -> Unit
) {
    val stringsContext = androidx.compose.ui.platform.LocalContext.current
    val status by app.logWeb.status.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow(
            stringsContext.getString(R.string.ui_settingsscreen_0ba11625),
            stringsContext.getString(R.string.ui_settingsscreen_4113a027),
            enabled,
            onEnabledChange
        )

        NumberField(
            stringsContext.getString(R.string.ui_settingsscreen_23df2b8a),
            port,
            onPortChange,
            stringsContext.getString(R.string.ui_settingsscreen_9d8d9a38)
        )

        SwitchRow(
            stringsContext.getString(R.string.ui_settingsscreen_ca81578e),
            stringsContext.getString(R.string.ui_settingsscreen_3501e197),
            lan,
            onLanChange
        )

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringsContext.getString(R.string.ui_settingsscreen_4b34e216)) },
            trailingIcon = {
                InfoButton(stringsContext.getString(R.string.ui_settingsscreen_4b34e216),
                    if (lan) stringsContext.getString(R.string.ui_settingsscreen_502a3ae8)
                    else stringsContext.getString(R.string.ui_settingsscreen_006c87ae))
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (enabled && lan && password.isBlank()) {
            WarningCard(
                title = stringsContext.getString(R.string.ui_settingsscreen_d2ce5e22),
                body = stringsContext.getString(R.string.ui_settingsscreen_b6150c14)
            )
        }

        TextButton(onClick = onApply) { Text(stringsContext.getString(R.string.ui_settingsscreen_fc2710b2)) }

        // ---- 当前状态 ----
        if (status.error.isNotBlank()) {
            WarningCard(title = stringsContext.getString(R.string.ui_settingsscreen_80e04f23), body = status.error)
        } else if (status.running) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringsContext.getString(R.string.ui_settingsscreen_7c18bd43), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringsContext.getString(R.string.ui_settingsscreen_127b29da, if (status.lan) stringsContext.getString(R.string.ui_settings_lan) else stringsContext.getString(R.string.ui_settings_local_only), status.port),
                        style = MaterialTheme.typography.labelSmall
                    )

                    val shown = if (status.lan) status.lanUrlWithToken else status.localUrl
                    if (shown.isNotBlank()) {
                        Text(
                            text = shown,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(shown))
                            }) { Text(stringsContext.getString(R.string.ui_settingsscreen_0e9854bc)) }
                        }
                    }

                    if (status.lan) {
                        Text(
                            text = stringsContext.getString(R.string.ui_settingsscreen_155867ed) +
                                stringsContext.getString(R.string.ui_settingsscreen_86d58e97),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val ips = app.logWeb.allLocalIpv4()
                        Text(
                            text = stringsContext.getString(R.string.ui_settingsscreen_b68e1871) +
                                stringsContext.getString(R.string.ui_settingsscreen_56d82543, status.port, status.port) +
                                stringsContext.getString(R.string.ui_settingsscreen_1c57ff10) +
                                if (ips.isNotEmpty()) stringsContext.getString(R.string.ui_settingsscreen_b4dc3680, ips.joinToString(", ")) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
