package app.solochat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.SendMode
import app.solochat.core.protocol.PacingConfig
import app.solochat.core.protocol.PacingParam
import app.solochat.core.protocol.PacingParams
import app.solochat.core.model.PriceCatalog
import app.solochat.core.model.PriceCatalogFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "solochat_settings"
)

/**
 * 应用设置的持久化。
 *
 * **API Key 不在这里** —— 它走 [SecretStore] 的加密存储。这里只放"泄露了也不致命"的配置
 * （接口地址、模型名、温度、节奏参数）。这条边界要一直守住：
 * 一旦有人图方便把 Key 挪进来，它就变成明文躺在 DataStore 里了。
 *
 * 为什么用 DataStore 而不是 SharedPreferences：它天然是 Flow，设置页改完
 * 界面各处的显示会自动跟着变，不需要手工发广播。
 */
class SettingsStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val apiProfiles = stringPreferencesKey("api_profiles_v1")
        val activeApiProfile = stringPreferencesKey("active_api_profile_v1")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val temperature = doublePreferencesKey("temperature")
        val thinkingEffort = stringPreferencesKey("thinking_effort")
        val maxTokens = intPreferencesKey("max_tokens")
        val timeoutMs = longPreferencesKey("timeout_ms")
        val historyLimit = intPreferencesKey("history_limit")

        /** 上面那个值已经做过"旧默认值 40 → 不限"的一次性迁移。见 [migrateLegacyHistoryLimit]。 */
        val historyLimitMigrated = booleanPreferencesKey("history_limit_migrated_v1")
        val useOfficialPrice = booleanPreferencesKey("use_official_price")
        val priceInput = doublePreferencesKey("price_input")
        val priceOutput = doublePreferencesKey("price_output")
        val priceCached = doublePreferencesKey("price_cached")
        val priceConfigured = booleanPreferencesKey("price_configured")
        val cachedPriceConfigured = booleanPreferencesKey("cached_price_configured")
        val priceCatalog = stringPreferencesKey("price_catalog_v1")
        val allowModeSwitch = booleanPreferencesKey("allow_mode_switch")
        val modeSwitchPolicy = stringPreferencesKey("mode_switch_policy")

        /** 发送方式：auto（防抖，默认）/ manual（两段，见 SendMode）。 */
        val sendMode = stringPreferencesKey("send_mode")
        val modelList = stringPreferencesKey("model_list")
        val debounceOverrideMs = longPreferencesKey("debounce_override_ms")

        /** 节奏参数不再逐个列键 —— 见 `PacingParams` 那张表。 */

        /** 前台服务常驻开关。 */
        val keepAlive = booleanPreferencesKey("keep_alive")

        /** 主动消息开关与免打扰。 */
        val proactiveEnabled = booleanPreferencesKey("proactive_enabled")
        val proactiveAfterMs = longPreferencesKey("proactive_after_ms")
        val proactiveCooldownMs = longPreferencesKey("proactive_cooldown_ms")
        val dndStartHour = intPreferencesKey("dnd_start_hour")
        val dndEndHour = intPreferencesKey("dnd_end_hour")

        /** 日志 web 服务。默认关闭 —— 开着就是在一个端口上暴露日志。 */
        val logWebEnabled = booleanPreferencesKey("log_web_enabled")
        val logWebPort = intPreferencesKey("log_web_port")
        val logWebLan = booleanPreferencesKey("log_web_lan")
    }

    val flow: Flow<Preferences> = dataStore.data

    /** 一次读全，用于构造 [ChatConfig]。界面每次调用都会拿到最新值。 */
    suspend fun snapshot(): Snapshot = mapOfPrefs(dataStore.data.first())

    /**
     * 组合成核心层需要的 [ChatConfig]。
     * `apiKey` 从 [SecretStore] 单独取 —— 这里传进来而不是自己持有，保持职责清晰。
     */
    fun toChatConfig(snapshot: Snapshot, apiKey: String): ChatConfig = ChatConfig(
        baseUrl = snapshot.baseUrl,
        apiKey = apiKey,
        model = snapshot.model,
        temperature = snapshot.temperature,
        thinkingEffort = snapshot.thinkingEffort,
        maxTokens = snapshot.maxTokens,
        priceInputPerM = snapshot.priceInput,
        priceOutputPerM = snapshot.priceOutput,
        priceCachedPerM = snapshot.priceCached,
        priceConfigured = snapshot.priceConfigured,
        cachedPriceConfigured = snapshot.cachedPriceConfigured,
        useOfficialPrice = snapshot.useOfficialPrice,
        priceCatalog = snapshot.priceCatalog,
        historyLimit = snapshot.historyLimit,
        timeoutMs = snapshot.timeoutMs,
        allowModeSwitch = snapshot.allowModeSwitch,
        modeSwitchPolicy = snapshot.modeSwitchPolicy,
        sendMode = snapshot.sendMode
    )

    /** 一组校验通过后只做一次 edit；其余页面的草稿不能参与写入。 */
    suspend fun saveSection(section: SettingsSection, draft: Snapshot, beforeWrite: () -> Unit = {}) {
        when (section) {
            SettingsSection.API -> {
                require(draft.temperature.isFinite() && draft.temperature in 0.0..2.0) { "温度需在 0 ~ 2 之间" }
                val url = normalizeBaseUrl(draft.baseUrl)
                if (url.isNotEmpty()) {
                    val uri = runCatching { java.net.URI(url) }.getOrNull()
                    require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
                        uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                        "请填写有效的接口地址"
                    }
                }
            }
            SettingsSection.PACING -> PacingParams.all.forEach {
                require(it.read(draft.pacing) in it.min..it.max) { "${it.label}需在 ${it.min} ~ ${it.max} ms 之间" }
            }
            SettingsSection.PRICE -> {
                require(listOf(draft.priceInput, draft.priceOutput, draft.priceCached)
                    .all { it.isFinite() && it >= 0.0 }) { "单价需为不小于 0 的有效数字" }
                draft.priceCatalog?.let(PriceCatalogFormat::validate)
            }
            SettingsSection.BEHAVIOR -> require(draft.debounceOverrideMs >= 0) { "防抖窗口不能小于 0" }
            SettingsSection.WEB -> require(draft.logWebPort in 1024..65535) { "端口需在 1024 ~ 65535 之间" }
        }
        dataStore.edit { prefs ->
            val grouped = prefs[Keys.apiProfiles] != null && section in listOf(SettingsSection.API, SettingsSection.PRICE)
            if (grouped) {
                val current = mapOfPrefs(prefs)
                require(current.apiProfileId == draft.apiProfileId && current.apiProfiles.any { it.id == draft.apiProfileId }) {
                    "接口组已变化，请重新打开设置后保存"
                }
                require(draft.apiProfileName.isNotBlank()) { "配置组名称不能为空" }
                require(draft.apiKeyId.matches(Regex("[A-Za-z0-9-]+"))) { "密钥槽位无效" }
                val old = ApiProfile.from(current)
                val updated = if (section == SettingsSection.API) old.copy(name = draft.apiProfileName.trim(),
                    baseUrl = normalizeBaseUrl(draft.baseUrl), model = draft.model.trim(), modelList = draft.modelList,
                    keyId = draft.apiKeyId)
                else old.copy(useOfficialPrice = draft.useOfficialPrice, priceCatalog = draft.priceCatalog,
                    priceInput = draft.priceInput, priceOutput = draft.priceOutput, priceCached = draft.priceCached,
                    priceConfigured = draft.priceConfigured, cachedPriceConfigured = draft.cachedPriceConfigured)
                beforeWrite()
                prefs[Keys.apiProfiles] = ApiProfilesFormat.encode(current.apiProfiles.map { if (it.id == updated.id) updated else it })
                if (section == SettingsSection.API) {
                    prefs[Keys.temperature] = draft.temperature
                    prefs[Keys.historyLimit] = draft.historyLimit
                }
                return@edit
            }
            beforeWrite()
            when (section) {
                SettingsSection.API -> {
                    prefs[Keys.baseUrl] = normalizeBaseUrl(draft.baseUrl)
                    prefs[Keys.model] = draft.model.trim()
                    prefs[Keys.temperature] = draft.temperature
                    prefs[Keys.historyLimit] = draft.historyLimit
                    prefs[Keys.modelList] = draft.modelList.joinToString("\n")
                }
                SettingsSection.PACING -> PacingParams.all.forEach {
                    prefs[longPreferencesKey(it.key)] = it.read(draft.pacing)
                }
                SettingsSection.PRICE -> {
                    prefs[Keys.useOfficialPrice] = draft.useOfficialPrice
                    prefs[Keys.priceInput] = draft.priceInput
                    prefs[Keys.priceOutput] = draft.priceOutput
                    prefs[Keys.priceCached] = draft.priceCached
                    prefs[Keys.priceConfigured] = draft.priceConfigured
                    prefs[Keys.cachedPriceConfigured] = draft.cachedPriceConfigured
                    if (draft.priceCatalog == null) prefs.remove(Keys.priceCatalog)
                    else prefs[Keys.priceCatalog] = PriceCatalogFormat.encode(draft.priceCatalog)
                }
                SettingsSection.BEHAVIOR -> {
                    prefs[Keys.allowModeSwitch] = draft.allowModeSwitch
                    prefs[Keys.sendMode] = draft.sendMode.wire
                    prefs[Keys.debounceOverrideMs] = draft.debounceOverrideMs
                }
                SettingsSection.WEB -> {
                    prefs[Keys.logWebEnabled] = draft.logWebEnabled
                    prefs[Keys.logWebPort] = draft.logWebPort
                    prefs[Keys.logWebLan] = draft.logWebLan
                }
            }
        }
    }

    /** Key 先成功复制到加密槽位，随后一次 edit 发布迁移；失败时保留全部旧值供重试。 */
    suspend fun migrateApiProfiles(copyLegacyKey: () -> Unit) {
        dataStore.edit { prefs ->
            if (prefs[Keys.apiProfiles] != null) return@edit
            val legacy = mapLegacyPrefs(prefs).copy(apiProfileId = ApiProfile.LEGACY_ID, apiProfileName = "原有接口")
            copyLegacyKey()
            prefs[Keys.apiProfiles] = ApiProfilesFormat.encode(listOf(ApiProfile.from(legacy)))
            prefs[Keys.activeApiProfile] = ApiProfile.LEGACY_ID
        }
    }

    /** 新建仅预填模板地址，模型与价格保持未配置；不改变当前选择或发请求。 */
    suspend fun addApiProfile(name: String, template: ApiProfileTemplate = ApiProfileTemplate.CUSTOM): String {
        require(name.isNotBlank()) { "配置组名称不能为空" }
        val id = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            val text = requireNotNull(prefs[Keys.apiProfiles]) { "接口配置尚未就绪" }
            prefs[Keys.apiProfiles] = ApiProfilesFormat.encode(ApiProfilesFormat.decode(text) + ApiProfile(id, name.trim(), baseUrl = template.baseUrl))
        }
        return id
    }

    suspend fun selectApiProfile(id: String) {
        dataStore.edit { prefs ->
            val profiles = ApiProfilesFormat.decode(requireNotNull(prefs[Keys.apiProfiles]))
            require(profiles.any { it.id == id }) { "配置组已不存在" }
            prefs[Keys.activeApiProfile] = id
        }
    }

    /** 删除当前组后保持未选择；不得偷偷启用另一个组。 */
    suspend fun deleteApiProfile(id: String) {
        dataStore.edit { prefs ->
            val profiles = ApiProfilesFormat.decode(requireNotNull(prefs[Keys.apiProfiles]))
            require(profiles.any { it.id == id }) { "配置组已不存在" }
            prefs[Keys.apiProfiles] = ApiProfilesFormat.encode(profiles.filterNot { it.id == id })
            if (prefs[Keys.activeApiProfile] == id) prefs[Keys.activeApiProfile] = ""
        }
    }
    // ---- 写入 ----

    suspend fun setBaseUrl(value: String) = put(Keys.baseUrl, normalizeBaseUrl(value))
    suspend fun setModel(value: String) = put(Keys.model, value.trim())
    suspend fun setTemperature(value: Double) = put(Keys.temperature, value.coerceIn(0.0, 2.0))
    suspend fun setThinkingEffort(value: String) = put(Keys.thinkingEffort, value)
    suspend fun setMaxTokens(value: Int) = put(Keys.maxTokens, value.coerceAtLeast(0))
    suspend fun setTimeoutMs(value: Long) = put(Keys.timeoutMs, value.coerceIn(5_000, 600_000))
    // 0 = 不限（默认）。上限给到 10 万只是防手滑输入天文数字，没有实际约束意义 ——
    // "不限"和"十万条"在真机上是一回事。
    suspend fun setHistoryLimit(value: Int) = put(Keys.historyLimit, value.coerceIn(0, 100_000))
    suspend fun setUseOfficialPrice(value: Boolean) = put(Keys.useOfficialPrice, value)
    suspend fun setPriceInput(value: Double) = put(Keys.priceInput, value.coerceAtLeast(0.0))
    suspend fun setPriceOutput(value: Double) = put(Keys.priceOutput, value.coerceAtLeast(0.0))
    suspend fun setPriceCached(value: Double) = put(Keys.priceCached, value.coerceAtLeast(0.0))
    suspend fun setAllowModeSwitch(value: Boolean) = put(Keys.allowModeSwitch, value)
    suspend fun setModeSwitchPolicy(value: String) = put(Keys.modeSwitchPolicy, value)

    /** 发送方式。存的是 [SendMode.wire]，读的时候认不出就退回 AUTO。 */
    suspend fun setSendMode(value: SendMode) = put(Keys.sendMode, value.wire)
    suspend fun setModelList(value: List<String>) = put(Keys.modelList, value.joinToString("\n"))
    suspend fun setKeepAlive(value: Boolean) = put(Keys.keepAlive, value)
    suspend fun setProactiveEnabled(value: Boolean) = put(Keys.proactiveEnabled, value)
    suspend fun setProactiveAfterMs(value: Long) = put(Keys.proactiveAfterMs, value)
    suspend fun setProactiveCooldownMs(value: Long) = put(Keys.proactiveCooldownMs, value)
    suspend fun setDndWindow(startHour: Int, endHour: Int) {
        dataStore.edit {
            it[Keys.dndStartHour] = startHour.coerceIn(0, 23)
            it[Keys.dndEndHour] = endHour.coerceIn(0, 23)
        }
    }

    /**
     * 写一个节奏参数。
     *
     * 键名、默认值、上下限全在 `PacingParams` 那张表里 —— 这里刻意**不再逐个列键**：
     * 20 个参数手写五遍（键名/默认/上下限/读/写），漏一处就是"设置里改了没生效"
     * 这种不报错的 bug。
     */
    suspend fun setPacingParam(param: PacingParam, value: Long) {
        dataStore.edit {
            it[longPreferencesKey(param.key)] = value.coerceIn(param.min, param.max)
        }
    }

    /** 用户手动覆盖自适应窗口（0 = 不覆盖，交给学习结果）。 */
    suspend fun setDebounceOverrideMs(value: Long) = put(Keys.debounceOverrideMs, value.coerceAtLeast(0))

    suspend fun setLogWebEnabled(value: Boolean) = put(Keys.logWebEnabled, value)
    suspend fun setLogWebPort(value: Int) = put(Keys.logWebPort, value.coerceIn(1024, 65535))
    suspend fun setLogWebLan(value: Boolean) = put(Keys.logWebLan, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    /**
     * 一次性把"旧默认值的历史条数"改成"不限"。
     *
     * 为什么需要它：`history_limit` 的旧默认值是 40，而设置页的保存是**整页写**——
     * 只要在接口页点过一次保存，40 就被当成"用户明确的选择"存了进去。于是把
     * `Snapshot` 的默认值改成 0 对这台机器**完全没有效果**（真机实测：库里就是 40），
     * 而且不报错、界面上还显示着 40，看起来像是"改了没用"。
     *
     * 判据刻意只认**恰好等于旧默认值**的那一种：别的取值一律尊重原样。
     * 迁移后打一个标记，之后用户再手动填 40 就是真的要 40，不会再被改写。
     *
     * 幂等：标记写过就直接返回（一次 DataStore 读，只在启动/保存设置时走）。
     */
    suspend fun migrateLegacyHistoryLimit() {
        val prefs = dataStore.data.first()
        if (prefs[Keys.historyLimitMigrated] == true) return
        val legacy = prefs[Keys.historyLimit]
        dataStore.edit {
            if (legacy == null || legacy == LEGACY_HISTORY_LIMIT) {
                it[Keys.historyLimit] = 0
            }
            it[Keys.historyLimitMigrated] = true
        }
    }

    companion object {
        /** 旧的出厂默认值（40 条）。只在这个迁移里被引用一次。 */
        const val LEGACY_HISTORY_LIMIT = 40
        /** 容错：用户常把地址填成 `api.deepseek.com`（没协议）或带尾斜杠。 */
        fun normalizeBaseUrl(raw: String): String {
            var v = raw.trim()
            if (v.isEmpty()) return ""
            if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
            return v.trimEnd('/')
        }
    }
}

/** 可独立提交的分组。密钥仍由 SecretStore 保存，不进入偏好文件。 */
enum class SettingsSection { API, PACING, PRICE, BEHAVIOR, WEB }

/**
 * 一份设置快照。用不可变数据类而不是直接暴露 Preferences，
 * 是为了让界面与核心层都不必知道 DataStore 的键名 —— 键名改动只影响这一层。
 */
data class Snapshot(
    val apiProfileId: String = ApiProfile.LEGACY_ID,
    val apiProfileName: String = "原有接口",
    val apiKeyId: String = ApiProfile.LEGACY_ID,
    val apiProfiles: List<ApiProfile> = emptyList(),
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.85,
    val thinkingEffort: String = "",
    val maxTokens: Int = 0,
    val timeoutMs: Long = 180_000,
    val historyLimit: Int = 0,
    val useOfficialPrice: Boolean = true,
    val priceCatalog: PriceCatalog? = null,
    val priceInput: Double = 0.0,
    val priceOutput: Double = 0.0,
    val priceCached: Double = 0.0,
    val priceConfigured: Boolean = false,
    val cachedPriceConfigured: Boolean = false,
    val allowModeSwitch: Boolean = true,
    val modeSwitchPolicy: String = "CONFIRM",
    /** 发送方式：默认 [SendMode.AUTO]（= 原来的防抖行为，谁都没被改到）。 */
    val sendMode: SendMode = SendMode.AUTO,
    val modelList: List<String> = emptyList(),
    val keepAlive: Boolean = false,
    val proactiveEnabled: Boolean = false,
    val proactiveAfterMs: Long = 30 * 60_000L,
    val proactiveCooldownMs: Long = 2 * 60 * 60_000L,
    val dndStartHour: Int = 0,
    val dndEndHour: Int = 7,
    /**
     * 全部节奏参数，打包成一个 [PacingConfig]。
     *
     * 打包而不是摊成 20 个字段：界面要的是一整个配置对象，而"哪些参数存在"
     * 这件事只有 `PacingParams` 那张表知道（见那里的注释）。
     */
    val pacing: PacingConfig = PacingConfig(),
    val debounceOverrideMs: Long = 0,

    /** 日志 web 服务。默认全关：开着等于在网络端口上暴露日志。 */
    val logWebEnabled: Boolean = false,
    val logWebPort: Int = 8787,
    /** 是否允许局域网访问。开启时必须设访问密码。 */
    val logWebLan: Boolean = false
)

private fun mapOfPrefs(p: Preferences): Snapshot {
    val legacy = mapLegacyPrefs(p)
    val text = p[stringPreferencesKey("api_profiles_v1")]
    if (text == null) return legacy.copy(apiProfiles = listOf(ApiProfile.from(legacy)))
    val profiles = ApiProfilesFormat.decode(text)
    val active = p[stringPreferencesKey("active_api_profile_v1")].orEmpty()
    // 丢失选择或删除当前组时只返回空配置，不能从旧键或其他组兜底。
    return (profiles.firstOrNull { it.id == active } ?: ApiProfile("", "未选择接口组"))
        .applyTo(legacy).copy(apiProfiles = profiles)
}

private fun mapLegacyPrefs(p: Preferences): Snapshot = Snapshot(
    baseUrl = p[stringPreferencesKey("base_url")].orEmpty(),
    model = p[stringPreferencesKey("model")].orEmpty(),
    temperature = p[doublePreferencesKey("temperature")] ?: 0.85,
    thinkingEffort = p[stringPreferencesKey("thinking_effort")].orEmpty(),
    maxTokens = p[intPreferencesKey("max_tokens")] ?: 0,
    timeoutMs = p[longPreferencesKey("timeout_ms")] ?: 180_000,
    historyLimit = p[intPreferencesKey("history_limit")] ?: 0,
    useOfficialPrice = p[booleanPreferencesKey("use_official_price")] ?: true,
    priceCatalog = p[stringPreferencesKey("price_catalog_v1")]?.let(PriceCatalogFormat::decode),
    priceInput = p[doublePreferencesKey("price_input")] ?: 0.0,
    priceOutput = p[doublePreferencesKey("price_output")] ?: 0.0,
    priceCached = p[doublePreferencesKey("price_cached")] ?: 0.0,
    priceConfigured = p[booleanPreferencesKey("price_configured")] ?:
        ((p[doublePreferencesKey("price_input")] ?: 0.0) > 0.0 || (p[doublePreferencesKey("price_output")] ?: 0.0) > 0.0),
    cachedPriceConfigured = p[booleanPreferencesKey("cached_price_configured")] ?:
        ((p[doublePreferencesKey("price_cached")] ?: 0.0) > 0.0),
    allowModeSwitch = p[booleanPreferencesKey("allow_mode_switch")] ?: true,
    modeSwitchPolicy = p[stringPreferencesKey("mode_switch_policy")] ?: "CONFIRM",
    // 认不出的值退回 AUTO：默认行为必须是"没有变化"的那一个。
    sendMode = SendMode.fromWire(p[stringPreferencesKey("send_mode")]),
    modelList = p[stringPreferencesKey("model_list")]?.split('\n')?.filter { it.isNotBlank() }
        ?: emptyList(),
    keepAlive = p[booleanPreferencesKey("keep_alive")] ?: false,
    proactiveEnabled = p[booleanPreferencesKey("proactive_enabled")] ?: false,
    proactiveAfterMs = p[longPreferencesKey("proactive_after_ms")] ?: (30 * 60_000L),
    proactiveCooldownMs = p[longPreferencesKey("proactive_cooldown_ms")] ?: (2 * 60 * 60_000L),
    dndStartHour = p[intPreferencesKey("dnd_start_hour")] ?: 0,
    dndEndHour = p[intPreferencesKey("dnd_end_hour")] ?: 7,
    // 节奏参数从 `PacingParams` 那张表长出来 —— 表是唯一映射来源。
    // 读不到就用表里的默认值，并且**按表里的上下限夹一次**（防手工改坏了 DataStore）。
    pacing = PacingParams.all.fold(PacingConfig()) { acc, param ->
        param.write(acc, (p[longPreferencesKey(param.key)] ?: param.default).coerceIn(param.min, param.max))
    },
    debounceOverrideMs = p[longPreferencesKey("debounce_override_ms")] ?: 0,
    logWebEnabled = p[booleanPreferencesKey("log_web_enabled")] ?: false,
    logWebPort = p[intPreferencesKey("log_web_port")] ?: 8787,
    logWebLan = p[booleanPreferencesKey("log_web_lan")] ?: false
)
