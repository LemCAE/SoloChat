package app.solochat

import android.app.Application
import androidx.room.Room
import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.ChatConfig
import app.solochat.core.agent.PriceResolver
import app.solochat.core.agent.PreparedAgentCall
import app.solochat.core.model.ModelUse
import app.solochat.core.model.ConversationModelBinding
import app.solochat.core.model.ModelSelection
import app.solochat.core.llm.LlmClient
import app.solochat.core.log.Log
import app.solochat.core.model.ModelPrice
import app.solochat.core.ports.ClockPort
import app.solochat.core.ports.StoragePort
import app.solochat.core.ports.SystemClock
import app.solochat.core.prompt.PromptResources
import app.solochat.core.protocol.PacingConfig
import app.solochat.core.protocol.RhythmLearner
import app.solochat.play.TurnHost
import app.solochat.data.AppLogSink
import app.solochat.data.SecretStore
import app.solochat.data.MediaLibrary
import app.solochat.data.MediaDraftSessions
import app.solochat.data.SettingsStore
import app.solochat.data.ModelConfigUpgradeCoordinator
import app.solochat.data.ModelAwareCallPreparation
import app.solochat.data.ModelCallPreparation
import app.solochat.data.resolveProviderCredentials
import app.solochat.data.RegistrySelection
import app.solochat.data.db.ALL_MIGRATIONS
import app.solochat.data.db.RoomStore
import app.solochat.data.db.SoloChatDatabase
import app.solochat.data.llm.OpenAiCompatClient
import app.solochat.data.logweb.LogWebServer
import app.solochat.data.logweb.TestGateway
import app.solochat.data.pricing.ModelPricing
import app.solochat.data.prompt.AssetPromptLoader
import app.solochat.data.prompt.PersonaDefaults
import app.solochat.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 应用的依赖装配点。
 *
 * 为什么不用 Hilt/Koin 这类框架：这个应用的对象图只有十来个节点，
 * 手写装配的代价远小于引入注解处理器（构建更慢、KSP 出错时更难查）。
 * 唯一要小心的是**初始化顺序**：数据库与设置要在 [onCreate] 里先备好。
 *
 * 注意 [promptResources] 与 [pricing] 是懒加载的：它们要读 assets（约 70KB JSON），
 * 放在启动路径上会让冷启动多花几十毫秒，而第一次真正用到是在用户发出第一条消息时。
 */
class SoloChatApp : Application() {

    /** 应用级作用域：常驻任务（拉取设置、写库）用它，生命周期 = 进程。 */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val db: SoloChatDatabase by lazy {
        Room.databaseBuilder(this, SoloChatDatabase::class.java, DB_NAME)
            // 不加 fallbackToDestructiveMigration：这个库是唯一的聊天记录，
            // 宁可启动失败让用户看到问题，也不能静默清空数据。
            //
            // 迁移写在 `Migrations.kt`（那里也写了为什么这个项目没法自动验迁移）。
            // 漏加这一句的症状是**一升级就打不开**：Room 找不到 1→2 的路径就直接抛。
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    val store: RoomStore by lazy { RoomStore(db) }
    val media: MediaLibrary by lazy { MediaLibrary(this, store) }
    val mediaDrafts: MediaDraftSessions by lazy { MediaDraftSessions(store, appScope) }
    val compression: app.solochat.play.CompressionCoordinator by lazy {
        app.solochat.play.CompressionCoordinator(app.solochat.core.agent.CompressionService(deps), appScope)
    }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val secrets: SecretStore by lazy { SecretStore(this) }

    val llm: LlmClient by lazy {
        OpenAiCompatClient(
            // 正式调用必须携带回合快照，不能回退到发送时的全局配置。
            baseUrlProvider = { "" },
            apiKeyProvider = { "" }
        )
    }

    val pricing: ModelPricing by lazy { ModelPricing(this) { clock.now() } }

    /** 日志 web 服务。懒加载 —— 不用它的用户不该为 Ktor 付启动开销。 */
    val logWeb: LogWebServer by lazy {
        LogWebServer(
            context = this,
            // 延迟取 TestGateway：直接传 this 会形成 App ↔ LogWebServer 的循环
            gatewayProvider = { testGateway },
            uiGatewayProvider = { uiTestGateway }
        )
    }

    /**
     * 测试接口的后端（发消息、看历史）。
     *
     * 懒加载，只有真的调用 `/api/send` 之类才会创建。
     */
    val testGateway: TestGateway by lazy { TestGateway(this) }
    private val sessionScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val sessions by lazy {
        app.solochat.play.SessionRegistry(sessionScope) { scope ->
            app.solochat.play.SessionController(turnHost, scope, store, store)
        }
    }
    val uiTestGateway by lazy {
        app.solochat.data.logweb.UiTestGateway(resolve = { id ->
            checkNotNull(store.getConversation(id)) { "对话不存在" }
            sessions.get(id).also { it.awaitReady() }
        })
    }

    suspend fun deleteConversation(id: String) {
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            sessions.remove(id) { deps.conversations.withConversation(id) { store.deleteConversation(id) } }
        }
    }

    /**
     * 按设置启动/停止日志 web 服务。
     *
     * 刻意做成"设置一改就立即生效"，而不是"下次启动才生效"：
     * 调日志通常是"现在遇到问题了，想马上看"，要求重启应用会让这件事没法用。
     */
    fun applyLogWebSetting(enabled: Boolean, port: Int, lan: Boolean) {
        // 打开日志服务 = 在调试状态，这时才允许"点标题栏打屏幕快照"
        debugDumpEnabled = enabled
        appScope.launch {
            val password = secrets.webPassword
            if (enabled) {
                val result = logWeb.start(port, lan, password)
                result.onFailure {
                    // 起不来最常见的原因是端口被占（比如上一次没停干净）。
                    // 这属于"用户能自己解决"的问题，所以给一句能照着做的提示。
                    Log.error(
                        "日志服务",
                        "启动失败，可能是端口 $port 已被占用。换一个端口再试。"
                    )
                }
            } else {
                logWeb.stop()
            }
        }
    }

    /**
     * 设置的内存快照。
     *
     * 为什么要有它：OkHttp 的请求头必须在**同步**回调里取 Key，而 DataStore 是异步的。
     * 所以在应用启动时读一次进内存，之后由 [refreshConfig] 在设置变更时更新。
     * 这不影响"改完立刻生效"——设置页每次保存都会调 refresh。
     */
    @Volatile
    var runtimeConfig: ChatConfig = ChatConfig()
        private set

    @Volatile private var modelConfigurationReady = false

    /** 串行发布设置与对应密钥，防止异步刷新把新组选择覆盖回旧组。 */
    /** A-4 协调器复用同一配置锁；启动/运行入口仍不调用协调器。 */
    internal val configMutex = Mutex()

    suspend fun apiSettingsSnapshot(): app.solochat.data.Snapshot = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        settings.snapshot()
    }

    /** 必须在所有可编辑 UI、会话发送和后台调用开放前完成。 */
    suspend fun awaitModelConfigurationReady() {
        if (modelConfigurationReady) return
        configMutex.withLock { ensureModelConfigurationReadyLocked() }
    }

    private suspend fun ensureModelConfigurationReadyLocked() {
        if (modelConfigurationReady) return
        // 所有旧偏好迁移在源冻结前、同一配置锁内完成，避免刷新任务与 A-4 采到不同源版本。
        settings.migrateLegacyHistoryLimit()
        // Key 槽位迁移先完成；失败时不得冻结/发布引用了缺失槽位的 READY 文档。
        settings.migrateApiProfiles { secrets.migrateLegacyApiKey() }
        val record = ModelConfigUpgradeCoordinator(settings, db, configMutex).coordinateLocked()
        check(record.phase == "READY") { "模型配置升级尚未完成：${record.phase}" }
        val document = checkNotNull(settings.loadModelRegistryV2()) { "READY 配置文档缺失" }
        document.validate()
        val snapshot = settings.snapshot()
        runtimeConfig = displayConfig(document, snapshot)
        modelConfigurationReady = true
    }

    private fun displayConfig(document: app.solochat.data.ModelRegistryDocument, snapshot: app.solochat.data.Snapshot): ChatConfig {
        val selection = document.globalDefault
        val provider = document.providers.firstOrNull { it.id == selection.providerId }
        val model = document.models.firstOrNull { it.id == selection.modelId && it.providerId == selection.providerId }
        val key = provider?.let { secrets.apiKeyFor(it.keyId) }.orEmpty()
        val behavior = settings.toChatConfig(snapshot, "")
        return if (provider == null || model == null || !provider.enabled || !model.enabled) behavior.copy(
            baseUrl = "", apiKey = "", model = "")
        else behavior.copy(baseUrl = provider.baseUrl, apiKey = key, model = model.requestModelId)
    }

    private suspend fun reloadConfigLocked(): ChatConfig {
        ensureModelConfigurationReadyLocked()
        val document = checkNotNull(settings.loadModelRegistryV2())
        return displayConfig(document, settings.snapshot()).also { runtimeConfig = it }
    }

    suspend fun saveSettings(section: app.solochat.data.SettingsSection, draft: app.solochat.data.Snapshot,
        replacementKey: String? = null) = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        check(section !in setOf(app.solochat.data.SettingsSection.API, app.solochat.data.SettingsSection.PRICE)) {
            "接口和价格配置已迁入提供商与模型设置"
        }
        val current = settings.snapshot()
        settings.saveSection(section, draft.copy(apiKeyId = current.apiKeyId))
        reloadConfigLocked()
    }

    suspend fun addApiProfile(name: String, template: app.solochat.data.ApiProfileTemplate = app.solochat.data.ApiProfileTemplate.CUSTOM): String = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        error("接口组已迁入提供商与模型设置")
    }

    suspend fun selectApiProfile(id: String): Unit = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        error("活动接口组已由模型用途选择替代")
    }

    suspend fun deleteApiProfile(id: String): Unit = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        error("请在提供商与模型设置中移除提供商")
    }

    /** v2 模型调用准备；任何失败都在会话认领消息前返回给调用方。 */
    suspend fun prepareChatCall(conversationId: String?): PreparedAgentCall =
        prepareModelCall(conversationId, ModelUse.CHAT)

    suspend fun prepareModelCall(conversationId: String?, use: ModelUse, allowPaused: Boolean = false): PreparedAgentCall {
        awaitModelConfigurationReady()
        return configMutex.withLock {
            if (conversationId == null) prepareModelCallLocked(null, use, allowPaused)
            else deps.conversations.withConversation(conversationId) { prepareModelCallLocked(conversationId, use, allowPaused) }
        }
    }

    private suspend fun prepareModelCallLocked(conversationId: String?, use: ModelUse, allowPaused: Boolean): PreparedAgentCall {
        val document = checkNotNull(settings.loadModelRegistryV2())
        val generic = settings.toChatConfig(settings.snapshot(), "")
        val binding = conversationId?.let { store.conversationModelBinding(it) }?.let { row ->
            ConversationModelBinding(when (row.selectionState) {
                "INHERIT" -> ModelSelection.Inherit
                "EXPLICIT" -> ModelSelection.Explicit(app.solochat.core.model.ModelReference(
                    requireNotNull(row.providerId), requireNotNull(row.modelId)))
                else -> ModelSelection.NeedsConfirmation(
                    if (row.providerId != null) app.solochat.core.model.ModelReference(row.providerId, requireNotNull(row.modelId)) else null,
                    row.pendingReason ?: "会话模型需要确认")
            })
        }
        val credentials = resolveProviderCredentials(document.providers, secrets::apiKeyFor)
        val result = ModelAwareCallPreparation.prepare(document, use, credentials.availabilityByProviderId,
            credentials.apiKeyBySlot, generic,
            clock.now(), pricing, binding, !allowPaused && conversationId?.let { store.isBackupPaused(it) } == true)
        val call = (result as? ModelCallPreparation.Ready)?.call
            ?: error((result as ModelCallPreparation.Rejected).detail)
        return call.toAgentCall(generic)
    }

    suspend fun saveConversationModelBinding(conversationId: String, selection: app.solochat.data.RegistrySelection) {
        awaitModelConfigurationReady()
        configMutex.withLock {
            deps.conversations.withConversation(conversationId) {
                val document = checkNotNull(settings.loadModelRegistryV2())
                selection.validate(document.models.mapTo(mutableSetOf()) { it.providerId to it.id })
                if (selection.state == "EXPLICIT") {
                    val provider = document.providers.first { it.id == selection.providerId }
                    val model = document.models.first { it.id == selection.modelId && it.providerId == selection.providerId }
                    require(provider.enabled && model.enabled) { "所选提供商或模型已停用" }
                }
                store.saveConversationModelBinding(app.solochat.data.db.ConversationModelBindingEntity(
                    conversationId = conversationId, selectionState = selection.state, providerId = selection.providerId,
                    modelId = selection.modelId, pendingReason = selection.reason, migrationSource = "USER", upgradePlanId = null))
            }
        }
    }

    suspend fun validateBackupResume(conversationId: String) {
        awaitModelConfigurationReady()
        val conversation = checkNotNull(store.getConversation(conversationId)) { "会话不存在" }
        checkNotNull(store.getPersona(conversation.personaId)) { "请先为恢复副本选择人物卡" }
        val binding = store.conversationModelBinding(conversationId)
        require(binding?.selectionState != "NEEDS_CONFIRMATION") { "请先确认恢复副本使用的模型" }
        prepareModelCall(conversationId, ModelUse.CHAT, allowPaused = true)
    }

    suspend fun repairAndResumeBackupConversation(conversationId: String, personaId: String,
        selection: app.solochat.data.RegistrySelection) {
        awaitModelConfigurationReady()
        configMutex.withLock {
            deps.conversations.withConversation(conversationId) {
                val document = checkNotNull(settings.loadModelRegistryV2())
                checkNotNull(store.getConversation(conversationId)) { "恢复副本已不存在" }
                checkNotNull(store.getPersona(personaId)) { "请选择仍然存在的人物卡" }
                selection.validate(document.models.mapTo(mutableSetOf()) { it.providerId to it.id })
                if (selection.state == "EXPLICIT") {
                    val provider = document.providers.first { it.id == selection.providerId }
                    val model = document.models.first { it.id == selection.modelId && it.providerId == selection.providerId }
                    require(provider.enabled && model.enabled) { "所选提供商或模型已停用" }
                }
                // 先明确修复归属，再做与真实聊天相同的凭据/思考/价格预检；失败时副本仍暂停。
                store.repairPausedBackupPersona(conversationId, personaId, clock.now())
                store.saveConversationModelBinding(app.solochat.data.db.ConversationModelBindingEntity(
                    conversationId, selection.state, selection.providerId, selection.modelId,
                    selection.reason, "USER", null))
                prepareModelCallLocked(conversationId, ModelUse.CHAT, allowPaused = true)
                store.resumeBackup(conversationId)
            }
        }
    }

    suspend fun pausedBackupConversations() = store.pausedBackupConversations()
    suspend fun availablePersonas() = store.allPersonas()
    suspend fun availableModelSelections(): List<app.solochat.data.RegistrySelection> {
        awaitModelConfigurationReady()
        val document = checkNotNull(settings.loadModelRegistryV2())
        return listOf(app.solochat.data.RegistrySelection.inherit()) + document.models
            .filter { model -> model.enabled && document.providers.any { it.id == model.providerId && it.enabled } }
            .map { app.solochat.data.RegistrySelection.explicit(app.solochat.core.model.ModelReference(it.providerId, it.id)) }
    }

    suspend fun saveModelRegistry(document: app.solochat.data.ModelRegistryDocument,
        keyChanges: Map<String, String?> = emptyMap()): app.solochat.data.ModelRegistryDocument = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        val old = checkNotNull(settings.loadModelRegistryV2())
        old.providers.filterNot { previous -> document.providers.any { it.id == previous.id } }.forEach { provider ->
            check(db.modelConfigs().explicitBindingCount(provider.id) == 0) {
                "仍有会话固定使用该提供商，请先修改这些会话的模型选择"
            }
        }
        val updated = app.solochat.data.ProviderCredentialPublisher(
            writeKey = secrets::setApiKeyFor,
            saveDocument = { published -> settings.saveReadyModelRegistryV2(published, old.revision) }
        ).save(old, document, keyChanges)
        runtimeConfig = displayConfig(updated, settings.snapshot())
        updated
    }

    suspend fun saveProviderPricing(providerId: String, pricing: app.solochat.data.LegacyProviderPricing,
        expectedRevision: Long): app.solochat.data.ModelRegistryDocument = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        val document = checkNotNull(settings.loadModelRegistryV2())
        check(document.revision == expectedRevision) { "模型配置已变化，请重新载入后再保存" }
        val provider = document.providers.firstOrNull { it.id == providerId } ?: error("提供商已不存在，请重新打开价格设置")
        check(provider.pricing == null) { "该提供商已整理，请使用新的模型报价入口保存" }
        check(document.revision < Long.MAX_VALUE) { "模型配置修订号已耗尽" }
        val updated = document.copy(revision = document.revision + 1, providers = document.providers.map {
            if (it.id == providerId) it.copy(legacyPricing = pricing) else it
        })
        settings.saveReadyModelRegistryV2(updated, expectedRevision)
        runtimeConfig = displayConfig(updated, settings.snapshot())
        updated
    }

    suspend fun saveModelPricing(providerId: String, expectedRevision: Long,
        upserts: Map<String, app.solochat.data.pricing.BoundModelPrice> = emptyMap(), removes: Set<String> = emptySet(),
        unassigned: List<app.solochat.data.pricing.UnassignedModelPrice>? = null,
        importedArchives: List<app.solochat.core.model.PriceExchangeArchive>? = null) = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        val current = checkNotNull(settings.loadModelRegistryV2())
        val updated = app.solochat.data.pricing.ProviderPricingPublisher.edit(current, providerId, expectedRevision,
            upserts, removes, unassigned, importedArchives)
        settings.saveReadyModelRegistryV2(updated, expectedRevision)
        runtimeConfig = displayConfig(updated, settings.snapshot())
        updated
    }

    suspend fun commitPricingMigration(plan: app.solochat.data.pricing.ProviderPricingMigrationPlan) = configMutex.withLock {
        ensureModelConfigurationReadyLocked()
        val current = checkNotNull(settings.loadModelRegistryV2())
        val updated = app.solochat.data.pricing.ProviderPricingPublisher.migrate(current, plan, pricing.initialCatalog)
        if (updated != current) {
            settings.saveReadyModelRegistryV2(updated, current.revision)
            runtimeConfig = displayConfig(updated, settings.snapshot())
        }
        updated
    }


    @Volatile
    private var clockOverride: ClockPort? = null

    val clock: ClockPort get() = clockOverride ?: SystemClock()

    /**
     * 是否允许"点标题栏打一份屏幕快照"。
     *
     * 跟着日志服务开关走：用户把日志服务打开，就等于在调试状态，这时点标题栏
     * 记一份"此刻屏幕上有什么"是顺手的事；关着的时候不该有这个行为。
     *
     * 为什么需要这个手段：这台机器上 `screenrecord` 完全不可用（SELinux 不让 shell
     * 往 `/sdcard` 和 `/data/local/tmp` 写，`exec-out` 流式输出也拿不到），
     * 而 `uiautomator dump` 一次要 2.5 秒 —— 想看"气泡是逐条累积还是逐条覆盖"
     * （差别在秒级两帧之间）这两个都不够用。状态是同步写的，抓它反而准。
     */
    @Volatile
    var debugDumpEnabled: Boolean = false

    val promptResources: PromptResources by lazy { AssetPromptLoader(this).load() }

    /** 从磁盘重读设置（启动时 + 每次保存后调用）。 */
    fun refreshConfig(onDone: ((ChatConfig) -> Unit)? = null) {
        appScope.launch {
            runCatching { awaitModelConfigurationReady(); configMutex.withLock { reloadConfigLocked() } }
                .onSuccess { onDone?.invoke(it) }
                .onFailure { Log.warn("设置", "接口配置读取失败，请检查加密存储与设置") }
        }
    }

    /**
     * 核心层需要的全部依赖。每次 [deps] 都是新建的轻量对象
     * （除了几个懒加载的重对象），所以调用方可以随意持有。
     */
    val deps: AgentDeps by lazy {
        object : AgentDeps {
            override val conversations = app.solochat.core.agent.ConversationOperations()
            override val storage: StoragePort get() = store
            override val clock: ClockPort get() = this@SoloChatApp.clock
            override val llm: LlmClient get() = this@SoloChatApp.llm
            override val prompts: PromptResources get() = promptResources
            override val pricing: PriceResolver get() = this@SoloChatApp.pricing
            override val mediaContent: app.solochat.core.ports.MediaContentPort get() = media

            override suspend fun prepareCall(conversationId: String, use: ModelUse): PreparedAgentCall =
                this@SoloChatApp.prepareModelCall(conversationId, use)

            override fun config(): ChatConfig = runtimeConfig

            override fun rhythmSamples(): List<Long> = cachedRhythmSamples

            override fun debounceWindowMs(): Long = cachedDebounceWindowMs
        }
    }

    /**
     * 给 [app.solochat.play.SessionController] 用的宿主接口。
     *
     * 存在的理由是可测性：控制器不该直接依赖 Application，否则它那些
     * "最需要回归测试"的逻辑（防抖与落盘的原子性）就没法在纯 JVM 里跑
     * —— 第一版正是因为这个，一个"同一批消息被投两遍"的 bug 只能靠看截图发现。
     */
    val turnHost: TurnHost by lazy {
        object : TurnHost {
            override val deps: AgentDeps get() = this@SoloChatApp.deps
            override fun config(): ChatConfig = runtimeConfig
            override fun pacingConfig(): PacingConfig = this@SoloChatApp.pacingConfig
            override fun refreshRhythm() = this@SoloChatApp.refreshRhythm()
            override suspend fun prepareChatCall(conversationId: String?) = this@SoloChatApp.prepareChatCall(conversationId)
            override suspend fun validateBackupResume(conversationId: String) = this@SoloChatApp.validateBackupResume(conversationId)
        }
    }

    /**
     * 当前聊天页的编排器（没有打开会话时为 null）。
     *
     * 仅用于当前页面的标题调试；运行实例由 sessions 持有，调试接口也从 sessions 获取。
     */
    @Volatile
    var activeController: app.solochat.play.SessionController? = null

    /**
     * 自适应窗口与学习样本的缓存。
     *
     * 刻意**不**在 [AgentDeps.debounceWindowMs] 里现查数据库：那个方法会在
     * "用户刚发完一条消息"的热路径上被调用，而查库是挂起操作。
     * 所以由 [refreshRhythm] 在后台低频刷新，热路径只读内存。
     */
    @Volatile
    var cachedRhythmSamples: List<Long> = emptyList()
        private set

    @Volatile
    var cachedDebounceWindowMs: Long = RhythmLearner.learnedWindowMs(emptyList())
        private set

    /** 用户手动覆盖的防抖窗口（0 = 交给学习结果）。 */
    @Volatile
    var debounceOverrideMs: Long = 0
        private set

    /**
     * 节奏参数的快照。
     *
     * 和 [runtimeConfig] 同样的理由：播放路径上不能挂起读 DataStore，
     * 所以由 [refreshRhythm] 在设置变更时同步进来。默认值就是出厂值，
     * 意味着"没改过设置也能用"。
     */
    @Volatile
    var pacingConfig: PacingConfig = PacingConfig()
        private set

    fun refreshRhythm() {
        appScope.launch {
            runCatching {
                val samples = store.globalRhythmSamples()
                val snapshot = settings.snapshot()
                cachedRhythmSamples = samples
                debounceOverrideMs = snapshot.debounceOverrideMs
                cachedDebounceWindowMs = if (snapshot.debounceOverrideMs > 0) {
                    snapshot.debounceOverrideMs
                } else {
                    RhythmLearner.learnedWindowMs(samples)
                }
                // 节奏参数整包来自设置（表在 `PacingParams`，见那里的注释）。
                // 这里不再逐个字段搬运 —— 那样每加一个参数就要改这一处，
                // 而这正是"设置里改了没生效"的高发地带。
                pacingConfig = snapshot.pacing
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 日志要先备好：后面所有的初始化失败都靠它留痕
        Log.version = "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, " +
            "${if (BuildConfig.DEBUG) "debug" else "release"})"
        Log.sink = AppLogSink(java.io.File(filesDir, "logs"))
        Log.info("启动", "SoloChat ${Log.version} 启动")

        // 上次进程被杀时残留的 PENDING/PLAYING 事件不可能再播了，先收尾再让界面渲染，
        // 否则用户会看到"上一条消息突然自己冒出来"。
        appScope.launch {
            val swept = runCatching { store.sweepUnfinished() }.getOrNull()
            store.sweepUnfinishedCalls()
            Log.info("启动", "启动收尾：取消残留未播出事件 ${swept ?: 0} 条")
        }
        // 出厂人物卡按 id 逐个补：老用户升级后会自动获得新增的出厂卡，已有同 id 卡不会覆盖。
        // 旧版小默由 v7→v8 数据库迁移降为普通卡，保留内容和会话关联。
        appScope.launch {
            runCatching {
                val existing = store.listPersonas().map { it.id }.toSet()
                val missing = PersonaDefaults.builtins().filter { it.id !in existing }
                missing.forEach { store.upsertPersona(it) }
                if (missing.isNotEmpty()) {
                    Log.info("启动", "补出厂人物卡 ${missing.size} 张：${missing.joinToString("、") { it.name }}")
                }
            }
        }
        // 常驻：默认不自动开 —— 常驻通知是个"需要用户同意"的东西，
        // 所以由设置页的开关触发（见 SettingsScreen 的说明）。
        appScope.launch {
            runCatching {
                awaitModelConfigurationReady()
                if (settings.snapshot().keepAlive) {
                    AgentForegroundService.start(this@SoloChatApp)
                }
            }
        }
        // 日志 web 服务：上次开着就继续开（端口被占时会失败并在日志里提示）
        appScope.launch {
            runCatching {
                val snapshot = settings.snapshot()
                if (snapshot.logWebEnabled) {
                    applyLogWebSetting(true, snapshot.logWebPort, snapshot.logWebLan)
                }
            }
        }
        refreshConfig()
        refreshRhythm()
    }

    companion object {
        private const val DB_NAME = "solochat.db"
    }
}
