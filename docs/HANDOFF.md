# HANDOFF｜当前交接

> 更新：2026-10-25。Sol 最终增量复核通过 R11，R1～R11 无剩余技术阻塞，P2-A 技术阶段关闭。正式启用验证未完成；P2-B 未实施。依据见 [最终复核](reference/p2-a-final-review.md)，待验收事项集中见 [PLAN §2.6](PLAN.md#26-p2-a-验收与任务入口)。

## 当前状态

U1～U3、H1～H4、C1～C3、R1～R4 已实施。**P1-1～P1-5 已完成并于 2026-10-25 关闭**，代码/测试/阶段文档整理为独立提交 `db806d0`（父提交 `a418ce6`），未推送、打标签或发布。复用已有 60 项定向结果及用户真机验收，本轮不重跑或装机。既有 `.gitignore`、`AGENTS.md` 改动保留，不混入 P1。

最新 Release 元数据为 `app.solochat`、版本码 12 / `0.1.0-p1-list-return-fix`；Luna 报告新旧发布签名一致、覆盖安装及返回列表即时画面复测通过。Sol 未重建、重装、连机或重复已通过的设备验收。

## P1 关闭依据

- 已核对最新 XML：SessionControllerTest 42、ChatProjectorTest 10、ConversationPreviewTest 8，合计 60 项、0 失败/错误；三事件用例逐条检查列表投影，插话用例检查取消余项不可见。与此前 41 项/UP-TO-DATE 记录区分，不累计残留报告。
- **最终入口**：首页“+”选择人物后直接新建会话，人物行仍进人物会话列表；人物页“+”直接建当前人物的会话。会话底部操作面板提供改名、置顶/取消置顶、删除确认；取消更换人物为用户决定，不恢复旧入口。早期任务书中的首页新建人物已作废。
- 已核对 NavHost 内列表快照、选中人物恢复和失效分组清理；只缓存展示数据，不替代 Room，不关闭后台会话。闪空首页修复的设备复测来自 Luna 报告。
- 静态双主题、导航、代发、置顶、菜单、搜索定位已有设备记录；中文长名与“逐条播出→返回列表更新→插话取消余项不入预览”的完整链路由用户亲自验收确认，补足先前设备欠项。详见 [P1 记录](archive/13-home-list-p1.md)，不再要求重复验收。
- 专用测试人物及会话保留，Luna 报告未操作真实资料；设备最后停在 P1 专用人物的会话列表。无 P1 阻塞项，长期/超大库等既有边界不随本次关闭扩大。

## P2-A 当前状态：技术已关闭，正式启用待验收

P2-A-1～10 和 R1～R11 已完成并通过 Sol 独立复核；A-8/9 深色视觉与交互验收已通过。v2 已接入运行来源，验证限隔离 Debug 包、合成数据及已记录范围；正式包和真实用户资料未升级，未发布。阶段基线已本地提交 `eaeb8f3`；仅保留既有 `.gitignore`、`AGENTS.md` 独立改动未提交。P2-B 价格配置/参考库仍未开始。

R11 本轮已核对草稿/发布器/凭据/调用准备/升级计划五类共 30 项 JVM 结果；不与上一轮 121 项累加。R9 双有效人物断言仅编译未运行，不阻塞技术关闭，留下一次获授权的隔离 Room 定向验证。没有重跑测试、连接设备、操作真实 Key/资料或清理槽位。以下交付记录保留各任务当时的实现与验证边界，不代表当前未接入或未关闭。

最新决定：可靠的旧模型对应自动升级，仅歧义/失效项需用户选择，取代旧稿逐项/批量确认；同品牌旧组不合并；旧兜底价仅用于原配置；旧备份缺失人物必须确认。配置升级只发生于旧结构转换，不作为日常入口，恢复仍是用户主动创建副本。A 即需限制旧兼容价的适用资格。

已定稿 Demo v2 保留正常/歧义升级、备份副本恢复及明暗主题；升级“使用当前默认模型”快捷选中具体模型，标题用途可选/保存但不执行。模型思考控件按聊天用途接入，不增加压缩专用面板；源位置见 PLAN，不再另起整套设计。

思考策略已确认：聊天 AUTO 遵循模型默认，可显式 ON/OFF；压缩独立 OFF，完全不跟聊天设置，未来压缩专用设置优先。自动识别优先、手动配置兜底：按官方 endpoint 与具体型号可靠声明适配，未知能力仍可 AUTO，ON/OFF 需要确认支持；A-8/9 已提供能力与有限方言的内存草稿设置。旧显式设置只保留原开关语义，不把旧 HIGH 等迁为以前未发送的强度；沿用用量/价格记录，不新增思考正文功能。

实施次序固定为数据/兼容层→请求与调用适配→已确认 UI→统一启用。A-10 已将设置页改为 READY 后持久化写入，并接入启动升级门禁、备份修复和 v2 调用链；本轮仅在隔离 Debug 包运行。正式用户资料升级、不可逆清理及发布仍需最终独立复核和明确授权。

### P2-A-1 交付记录（2026-10-25）

- 新增 core 提供商/模型稳定引用、版本化配置、用途选择、思考能力与非秘密调用快照契约；新增纯解析器，覆盖聊天会话→聊天用途→全局、压缩/标题用途→全局的选择链，并对失效、禁用、待确认及凭据不可用明确失败。标题偏好独立，不阻塞聊天或压缩。
- 思考解析支持聊天 AUTO/ON/OFF，压缩固定 OFF 且不读取聊天意图；未知能力或无法关闭/启用时返回明确结果。未生成厂商参数，也未接入 App、现有 ChatConfig 或调用链，当前运行行为保持旧解析。
- 定向命令 `pwsh tools/build.ps1 :core:test --tests app.solochat.core.agent.ModelConfigResolverTest`：10 项通过。未运行其他测试、构建、真机验收或真实配置迁移；这些不属于本项验收。
- 遗留：尚未做 P2-A-1 独立复核；后续存储、请求方言与调用接入依次留给 P2-A-2 起的任务。网络连通性和真实模型能力未验证。

### P2-A-2 交付记录（2026-10-25）

- 在现有 DataStore 增加 v2 非秘密文档与可复用升级准备记录的显式读写；不新建 DataStore，不改 SecretStore、槽位清理或旧运行读取路径。配置含稳定提供商/模型、全局及用途选择、逐模型旧价资格，并逐提供商保留原 R4 价格、开关及 null 价格目录语义。
- 只读计划沿旧组 ID、Key 槽位和原请求名建立一对一模型映射；裸手填名保留。缺失活动组/模型与未知旧思考值形成待确认项；空思考→AUTO、OFF→OFF、其他已知档位→ON 且不带强度。思考偏好只复制到旧全局/会话实际使用模型；新模型默认无旧价资格。
- 同一源版本重试复用 DataStore 中保存的 ID 映射；源变化后旧计划拒绝继续。没有扫描调用历史、读取真实 Key、自动执行升级或发布 v2 ready。
- 最小文档形状：`{"version":2,"providers":[{"id":"old-group","name":"接口名","baseUrl":"https://example.invalid/v1","keyId":"old-slot","protocol":"OPENAI_COMPATIBLE","legacyPricing":{"useOfficialPrice":true,"priceCatalog":null,"priceInput":0.0,"priceOutput":0.0,"priceCached":0.0,"priceConfigured":false,"cachedPriceConfigured":false}}],"models":[{"id":"stable-model","providerId":"old-group","displayName":"模型显示名","requestModelId":"Model-Case","legacyPriceEligible":true}],"globalDefault":{"state":"EXPLICIT","providerId":"old-group","modelId":"stable-model"}}`。无法映射时保留 `NEEDS_CONFIRMATION`，可能原因包括活动组缺失、全局模型为空/映射缺失、旧思考值未知。
- 定向命令 `pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.SettingsStoreTest --tests app.solochat.data.ModelConfigUpgradePlanTest`：22 项通过。Windows 临时目录需将 `TEMP`/`TMP` 指向工作区 `.tmp` 才能执行 DataStore 测试。
- 遗留：v2 仍未成为运行权威；真实旧配置、Key 槽位和价格未迁移，须由后续协调/启用任务在保护副本上处理。

### P2-A-3 交付记录（2026-10-25）

- Room 源 schema 从 v8 增至 v9，只追加 `conversation_model_binding` 与 `call_config_snapshot`；旧 `conversation.modelOverride`、调用、消息、事件、摘要及金额均未回填。新表无跨 DataStore 外键；同值绑定可重试，冲突失败；call_record 与配置快照有同一事务入口。删会话清绑定，调用历史保留；清空调用历史时同步清理快照。
- `pwsh tools/build.ps1 :app:assembleDebug` 成功，导出 `app/schemas/.../9.json`。离线 SQLite 核对以 v8 导出 schema 建临时副本，执行 `SchemaV9.kt` 两条实际新增 SQL；13 张旧表每表 1 行的列、主键、索引和行值均不变，新表与 v9 schema 一致。另核对同值重试、冲突拒绝、事务失败回滚及删会话保留调用快照，`integrity_check=ok`。
- 遗留：这是合成 v8 SQLite 副本，不是用户数据库或 Android Room 实际打开；设备升级/Room 运行时验收留给 P2-A-10。未执行真实配置转换、安装或模型调用。

### P2-A-4 交付记录（2026-10-25）

- 新增显式升级协调器，复用 `SoloChatApp.configMutex`；A-2 旧准备记录按 v1 读取并沿用原模型 ID，冻结完整计划后升级为 v2 阶段记录。按 `FROZEN → Room 单事务追加 → ROOM_APPENDED → DataStore 同次 edit 发布文档与 READY` 执行；同计划重试接受相同关联、冲突拒绝，ready 后只核对不重写。协调器未接启动或当前运行入口。
- 覆盖冻结后、Room 事务提交前/后、READY edit 内故障注入；源版本在跨存储继续及发布前复核。定向命令 `pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.ModelConfigUpgradeCoordinatorTest --tests app.solochat.data.ModelConfigUpgradePlanTest --tests app.solochat.data.SettingsStoreTest` 通过：协调器 6、计划 8、设置存储 14 项，均 0 失败。
- **A-10 必须在启动门禁且会话 UI 尚不可操作时调用协调器**：当前聊天页的 `modelOverride` 经 `RoomStore.changeModel` 写入，不持有 `configMutex`。本轮不改该运行路径；在运行期调用会留下源冻结竞争窗口。A-10 接入时须先落实上述启动顺序或明确共享该写入的锁。
- 未执行真实配置转换；Room 实际打开和 v8→v9 Android 运行时升级仍留 A-10。

### P2-A-5 交付记录（2026-10-25）

- ZIP 写出升为 v2，读取严格分派 v1/v2；v1 正文/counts 保持旧形状并拒绝 v2 新字段。v2 增加会话模型关联和调用配置快照；导入只重映射会话副本 ID 与 callId，原 provider/model/思考字段作为历史来源保留。
- 所有导入会话模型选择均写为本机待确认，沿用原备份暂停标记；移除无人物卡时自动创建“对方”。缺失人物以隔离标记保存，现有会话解析得到未解析状态，聊天输入仍被禁用；相关摘要保留文本但不自动激活。冻结价格、Token、usage 与调用账目关联未重算或二次记账。
- 定向命令 `pwsh tools/build.ps1 :core:test --tests app.solochat.core.backup.BackupTest` 通过 12 项；`pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.backup.BackupRowsTest` 通过。另从导出的 v9 schema 建临时 SQLite，注入导入来源主键冲突后确认本批会话、绑定、调用快照和导入标记均回滚，旧会话/调用行逐值不变；这是 SQLite/schema 核对，不代表 Android Room 实际导入测试。
- 未新增恢复选择 UI、未读取本机 v2 配置自动配对，也未接入聊天运行配置。跨安装真实恢复、Room 运行时/设备升级、人物与模型修复及解除暂停依赖 A-10；本轮没有真实配置或用户资料迁移。

### P2-A-6 交付记录（2026-10-25）

- A-1 模型契约增加默认空的可选思考方言覆盖，v2 `RegistryModel` 兼容读写；能力来源增加精确的厂商/型号声明。该最小契约扩展是为手动能力/方言设置 UI 预留，不改变既有数据语义，符合本轮明确授权。
- 新增纯 `ThinkingRequestAdapter`：只用官方 endpoint + 精确请求模型 ID 的小型白名单识别 DeepSeek / 百炼，代理同名模型不会自动套用；手动确认的能力/方言优先，未知仍允许 AUTO，显式 ON/OFF/强度不支持时明确拒绝。OpenAiCompatClient 新类型化参数与旧 `thinkingEffort` 路径并存，未接正式调用链或网络探测。
- 定向验证：`pwsh tools/build.ps1 :core:test --tests app.solochat.core.agent.ThinkingRequestAdapterTest` 6 项通过；App `DashScopeCompatibilityTest` 14 项、`SettingsStoreTest` 15 项通过。App 测试将 JVM 临时目录指向 `.tmp` 后成功；仅合成配置，没有真实厂商请求。
- 遗留：模型能力 API 目前没有被当前目录读取器保存/复用，首批自动覆盖因此限定于可核验的官方精确声明；其他厂商/型号需后续补可靠来源或由用户手动设置。真实模型响应未验证。

### P2-A-7 交付记录（2026-10-25）

- 新增隔离的 `ModelAwareCallPreparation`，统一使用一份 v2 解析结果/凭据和调用时间；凭据按原 `keyId` 槽位取用，返回隐藏凭据的请求 endpoint、冻结价格与非秘密 `CallConfigSnapshot`。`claimAfterPreflight` 在准备拒绝时不执行认领，成功则回传同一 prepared call。未知 AUTO 可继续，聊天思考与压缩 OFF 分离；未确认能力、待确认选择、缺凭据或暂停副本在准备阶段拒绝。新模型不继承旧提供商兼容价，只有迁入且具资格的模型沿用其旧 R4 价格配置。
- `RoomStore.recordCallStartedWithConfig` 通过 A-3 的唯一事务 DAO 同写 STARTED call_record 与开始配置快照；既有收尾 `recordCall` 不重写快照。新增快照实体映射与校验；未回填任何旧调用、usage 或价格。
- 定向验证：`ModelAwareCallPreparationTest` 4 项、`CallConfigSnapshotMappingTest` 2 项通过；A-6 同批 App 用例继续通过。按当前 v9 表结构在内存 SQLite 核对同成同败、同值重试、冲突拒绝和注入失败回滚，`integrity_check=ok`。没有 Android Room 实际打开证据。
- **A-10 接入依赖**：本轮没有改 SoloChatApp、SessionController、CompressionService 或当前配置来源。A-10 必须在会话锁内、请求认领前调用准备器，并将同一 PreparedModelCall 交给执行器；当前入口在 `flushTurn` 先认领旧待发消息，不能声称已完成新链路顺序验收。Room v8→v9 Android 运行时、真实在途配置变更、模型请求和用户资料保护验收也留 A-10。

### P2-A Demo 独立预览（2026-10-25）

- 原 Codex 可视化片段保持不变；新增仓库副本 [独立 Demo](reference/solochat-provider-model-design-v2.html)，补齐网页外壳、顶部五组导航样式/点击与键盘切换、明暗主题选择及内置 SVG 图标。原页面内容、模拟数据和交互逻辑沿用，不加载外部 CSS、字体或脚本，也不调用服务或写入应用数据。
- 静态检查通过：文档结构、五组面板/导航绑定、主题入口、两段内联 JavaScript 语法、18 个本地 SVG 图标覆盖完整，未发现外部资源引用。Browser MCP 未连接，Windows UI 检查未能确认地址；Edge 实际显示、控制台和点击/键盘/主题表现待用户手动验收。
- 修复后的 Demo 已用于 A-8/9 实现，Android 采用原生布局而非逐像素复制网页。A-8/9 深色视觉与交互阶段验收已通过；A-10 本轮另在 PKM110 隔离 Debug 包查看了明暗设置首页、模型选择页、选择器和提供商页，截图与限制记录在 A-10 交付记录。

### P2-A-8 交付记录（2026-10-25）

- 设置主菜单新增“模型用途”和“提供商与模型”入口；用途页覆盖全局默认、聊天、压缩、标题与会话来源示例，选择器按稳定 provider/model ID 跨组搜索，明确继承和待确认。压缩用途独立于聊天思考模式。
- A-8/9 共享 `P2ModelSettingsDraft`，只在设置页内存中投影现有 API 配置并保留原文档不可变；“保存草稿”只清除草稿脏标记。离开设置前对未保存草稿提示确认。没有调用 DataStore、Room 或 SecretStore，也没有改正式配置/调用入口。
- A-8 定向用例 `P2ModelSettingsDraftTest` 覆盖跨提供商同名模型稳定引用、继承/会话草稿隔离及内存保存不改源对象。最终 `pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.ui.settings.P2ModelSettingsDraftTest` 成功，4 项通过。Compose/资源编译通过。
- 会话行是设置页演示用例，不是实际聊天会话设置入口；真实 Room 绑定编辑、旧配置转换、调用门禁留 A-10。未检查 Compose Preview、未安装或操作 Android 设备，视觉/交互等待用户验收。

### P2-A-9 交付记录（2026-10-25）

- 提供商页按“连接/模型”分层，支持本地草稿中编辑连接、添加提供商/模型、启用状态、精确 requestModelId、聊天思考模式及有限能力/方言设置。思考能力摘要由 A-6 适配器按官方 endpoint+精确模型 ID 自动识别；未知能力显示未知且 AUTO 可用，显式 ON/OFF 只有适配器确认可表达时才可确认。
- 编辑草稿沿用 keyId 槽位元数据与旧价格资格/目录原值；不读取或提交真实 Key。价格入口仅能打开现有当前活动接口组价格页，其他提供商明确提示旧价格页当前范围；没有改 R4 或历史费用。没有发起目录/模型网络请求。
- A-9 在 `P2ModelSettingsDraftTest` 增加 provider/model 编辑保留 keyId、精确请求 ID、旧价资格且原对象不变的用例；最终定向类 4 项通过。`pwsh tools/build.ps1 :app:assembleDebug` 成功，Debug APK 只用于编译检查。`ThinkingRequestAdapterTest` 本轮命令为 `UP-TO-DATE`，未强制重跑；A-6 既有记录为 6 项通过。
- 当时未做 Compose Preview 或真机检查；后续 A-8/9 阶段由用户验收深色界面。A-10 已补 READY 后持久化写入口并接入统一运行配置/启动升级流程；真实厂商请求仍未验证。

数据/接口中间成果已在 A-10 统一启用。Room 源 schema 为 v9；实际 v8→v9 Room 运行验证只使用隔离测试数据库，真实用户库未打开。新配置表不修改旧历史/价格行，Key 槽位沿用；真实转换前仍须保留并核对两份一致数据库副本及旧偏好。

## 当前配置、价格与长期边界

- **当前运行来源为 READY v2 配置**：全局、聊天、压缩用途及会话可按稳定 provider/model ID 解析；调用前冻结连接、槽位凭据、思考参数、价格和非秘密快照。旧费用不重算，Key 槽位不随提供商编辑/移除清理。R4 价格系统未重构，入口按 providerId 打开原有价格页。细节见 [ARCHITECTURE](ARCHITECTURE.md) 和 [R1～R4 记录](archive/12-provider-pricing-r1-r4.md)。
- `TEST-R1-1023` 历史价格测试副本仍保留：星期/阶梯测试值不是官方规则，峰谷关闭、日历未确认，测试组无真实凭据；不得当参考价采用。
- A-5 已移除旧备份缺人物时自动造“对方”的路径；A-10 恢复副本修复流程已接入。普通会话不提供更换人物入口，缺失归属阻止生成；恢复修复只允许仍暂停且原人物缺失的隔离副本。完整跨安装恢复、U3 系统更新覆盖仍有端到端欠项。
- 真实服务跨组在途切换、存储故障、百轮/连续长场景和超大库未获证明；JVM、静态截图和短回合各按实际范围陈述。

### P2-A-10 交付记录（2026-10-25）

- 启动时在配置互斥锁内协调 v1→v2 升级，READY 前不放行模型运行；v2 成为唯一运行选择来源。调用前准备同一不可变配置、思考参数、价格及非秘密快照；消息认领发生在预检之后。压缩预检移出会话锁以避免重入死锁，压缩仍独立 OFF。设置页按“聊天/服务配置”分组，旧接口卡片和入口移除；模型价格按明确 providerId 进入既有 R4 页面。
- A-5 导入副本保留暂停状态。新增修复页选择本机人物与模型，预检成功才恢复；失败继续暂停。provider 移除会检查全局/用途/会话引用。旧 Key 槽位沿用并保留；曾尝试移除无引用槽位清理，但审批拒绝了该不可逆删除，本轮未删除任何槽位。
- 验证（A-10 原阶段证据）：此前定向 JVM 套件 105 项、0 失败；core resolver/thinking/backup 为 28 项（曾误记 27，现按审查报告修正）。此前 `:app:connectedDebugAndroidTest` 在 PKM110 通过 2 项：v8→v9 Room migration 逐表比较旧行并检查 v9 schema/integrity；call_record 与 snapshot 同事务提交/失败回滚。没有打开真实用户数据库。该阶段证据不覆盖本轮 R1～R10 修复；详见下方增量修复记录。
- PKM110 仅安装并启动 `app.solochat.debug`；`app.solochat` 正式包未启动/覆盖。设备为窄屏分辨率 1256×2760（约 359dp），在明暗主题查看设置首页；浅色查看模型选择和“选择提供商与模型”对话框，深色查看提供商/连接页。无已配置模型的全新隔离包展示“需要确认模型选择”，因此无法验证有模型时的跨提供商检索/选择、思考控件、已绑定会话详情或聊天调用；未编辑或保存配置，未发起真实请求。instrumentation 安装确认后，Gradle teardown 移除了 `app.solochat.debug`，随后已重新安装并启动同一 Debug APK；该包为新建空白测试资料，正式包未受影响。深色设置首页截图：[dark](../.tmp/p2a10-settings-dark.png)；浅色设置首页、模型选择和选择器截图：[light](../.tmp/p2a10-settings-light.png)、[models](../.tmp/p2a10-models-light.png)、[selector](../.tmp/p2a10-model-selector-light.png)；深色提供商详情：[provider](../.tmp/p2a10-providers-dark.png)。
- 遗留与复核：Room 真实用户资料迁移、真实备份恢复、SecretStore 槽位的孤儿清理、真实厂商响应和正式包升级均未做；不可逆清理因审批拒绝保持未实施，需要单独明确授权后再评估。未推送、未发布、未开始 P2-B。

### P2-A 最终审查 R1～R10 定向修复（2026-10-25）

最终审查报告见 [P2-A 最终复核](reference/p2-a-final-review.md)。本轮按其关闭项修复，不代表阶段关闭：

| 项 | 修复与代码位置 | 定向证据 |
|---|---|---|
| R1 READY 重启阻断 | `data/ModelConfigUpgradeCoordinator.kt` READY 只校验并读取当前 v2 权威文档；不与迁移冻结候选做内容相等检查。 | `ModelConfigUpgradeCoordinatorTest` 先升级、保存用户修改、关闭并重开 DataStore 后重建协调器。 |
| R2 凭据身份错配 | `data/ProviderCredentials.kt` 按 `keyId` 读 SecretStore、按 `providerId` 建可用性；`SoloChatApp.prepareModelCallLocked` 使用该组装结果。 | `ProviderCredentialsTest` 覆盖 providerId 与槽位 ID 不同。 |
| R3 快照备份冲突 | `data/db/CallConfigSnapshotMapping.kt` 新写对象格式；`core/backup/BackupData.kt` 接受旧参数数组；保留其余快照字段。 | `BackupRowsTest` 从实际 `CallConfigSnapshot.toEntity` 经 ZIP 导出、导入规划和恢复行往返，并单测兼容旧数组。 |
| R4 Key 替换非原子 | `data/ProviderCredentialPublisher.kt` 对既有提供商先写新槽位，再发布新指针；清空也切换到新空槽位。失败时旧槽位不变；本轮不清理任何 Key。 | `ProviderCredentialPublisherTest` 覆盖替换、发布失败和清空。 |
| R5 价格资格误屏蔽 | `data/ModelAwareCallPreparation.kt` 明确目录优先；旧资格只控制旧/null 兼容兜底，不合资格的新模型以未知价格结束，不落到随包同名价；请求模型 ID 改动清除旧资格。 | `ModelAwareCallPreparationTest` 运行真实 `PricingRules`/`initialCatalog`，区分明确报价、未配置新模型及迁入合资格模型。 |
| R6 价格过期覆盖 | `ui/settings/P2ModelSettingsDraft.kt` 合并已保存 provider 价格到仍有未保存编辑的草稿；`SettingsScreen.persist` 保留其他草稿字段。 | `P2ModelSettingsDraftTest` 覆盖价格 B 保存后模型/用途草稿仍保留且不回写价格 A。 |
| R7 网关绕过预检 | `data/logweb/TestGateway.kt` 在消息落盘前要求 CHAT v2 预检成功，并将同一 prepared call 传入 `AgentTurnRunner`；拒绝时不写用户消息。 | `TestGatewayTest` 验证 v2 模型/endpoint 实际请求、快照首写和拒绝不落盘。App 唯一的 Runner 调用点均传 prepared call。 |
| R8 压缩复用预览价格 | `PreparedAgentCall` 保留冻结价格规则；`CompressionService` 对规则与快照做预览后校验，每次摘要请求按真实开始时刻在冻结规则内选价；`AgentTurnRunner` 同样以开始时刻冻结 prepared 价格。 | `CompressionServiceTest` 覆盖非空 prepared call、多段跨时段、在途修改连接/价格仍按原快照，以及预览后规则版本变化拒绝。 |
| R9 普通会话换人物 | 删除 `ConversationSettingsScreen` 换人物入口及 `SoloChatApp.saveConversationPersona`；`RoomStore.repairPausedBackupPersona` 限制到暂停副本，要求人物缺失或与同一目标一致（支持预检失败后幂等重试）。 | Room instrumentation 验证普通会话拒绝、有效既有人物不能换卡、缺失人物副本可修复且保持暂停。 |
| R10 历史思考快照展示 | `RoomStore.statistics` 按调用 ID 读取已有快照；`StatisticsContent.CallDetail` 展示提供商、模型、用途、思考模式/强度/来源及实际参数；旧调用显示“未记录”，不读取当前配置。 | `StatisticsSnapshotTest` 覆盖有/无快照分离；Room instrumentation 覆盖调用快照读取。 |

- UI 收敛：正式提供商/用途页不再显示“评审草稿”等提示；明确保存仍调用 `app.saveModelRegistry` 发布 READY v2，失败保留草稿并反馈，取消只丢弃未保存编辑。连接信息区是只读摘要，“编辑提供商”打开唯一编辑表单，未发现第二套可写连接表单。未进行本轮 Android 视觉截图验收；只验证编译和保存路径源码/既有定向用例。
- 集成证据：定向 App JVM 用例 121 项（含升级、凭据、快照/备份、调用、设置草稿、SessionController 与压缩）通过；Room instrumentation 在 PKM110 共 4 项通过，包含 v8→v9 旧表逐行核对、call/snapshot 同事务回滚、备份导入中途故障整体回滚、快照导出/恢复、人物与模型待确认修复及暂停解除。均用虚构数据和专用数据库；未读写正式应用数据库、Key 或偏好。Core 既有证据为 28 项，未为本次数量而重跑。Room 测试完成后 Gradle teardown 移除了此前的 `app.solochat.debug`；恢复安装返回 ADB 错误码 `[-99]`，目前该测试包未安装。`app.solochat` 正式包仍在设备上，测试未安装或覆盖它。
- 未覆盖：该轮未做真实厂商请求、正式资料升级/备份恢复、发布包安装或最终 Android UI 视觉复核；恢复 UI 的真实手动操作、跨安装恢复也未端到端验证。PKM110 测试只运行隔离 Room 用例，不构成正式设备配置验收。上述独立验收仍保留，当前技术关闭结论见本文件顶部。

### P2-A R11 定向修复（2026-10-25）

- 新增提供商、模型草稿改用带类型前缀的 UUID（`provider-`、`model-`）；Key 使用独立 `key-` UUID。新提供商发布时由 `ProviderCredentialPublisher` 无条件重新分配新槽位，不信任草稿传入的 keyId；先写入有值的新 Key，再发布 v2 引用。未填写/清空时新槽位保持空；发布失败保留旧槽位和新写入槽位，不清理任何历史 Key。升级计划不变，原 providerId、modelId 及 keyId 继续按迁移结果保留。
- 设置页连接测试/获取模型只接受当前待保存的新 Key，或按已持久化文档中相同 providerId 的可信槽位读取；未保存的新提供商不按草稿 keyId 读取 SecretStore。保存后无 Key 的新提供商由正式调用预检拒绝。
- 定向 JVM：`P2ModelSettingsDraftTest`、`ProviderCredentialPublisherTest`、`ProviderCredentialsTest`、`ModelAwareCallPreparationTest`、`ModelConfigUpgradePlanTest` 共 **30 项通过**。覆盖新增→保存→删除→重新进入→再新增后身份不复用；新提供商不填/填入/清空 Key、凭据身份隔离、连接测试不探读旧槽、正式调用拒绝缺 Key、发布失败保留槽位，以及迁移保留旧 ID/Key 槽位。
- R9 覆盖补强：Room 用例现在以两个真实存在的合成人物作为普通会话原归属和目标，直接断言普通会话不能切换；也以两个有效人物覆盖暂停副本不能改写有效归属。`:app:compileDebugAndroidTestKotlin` 通过；本轮未安装或运行 instrumentation，故该新增 Room 断言尚未获得设备运行结果。
- 本轮 `git diff --check` 通过，仅有仓库既有 LF/CRLF 转换提示。只使用合成 Key 与数据；未访问正式资料、未运行收费请求、未删除 Key 槽位、未安装/覆盖设备包。Sol 后续增量复核通过 R11 并关闭技术阶段；R9 新断言仍明确未执行，不扩大为设备结果。

## P2-A 仓库收敛与正式签名候选包（2026-10-25）

- **本地提交**：`eaeb8f3 feat: 完成 P2-A 提供商模型配置阶段`，提交 76 个 app/core/阶段文档/README 文件；包含 Room v9 schema、隔离 Room 测试、最终复核报告和仓库 Demo v2。`.gitignore` 与 `AGENTS.md` 的既有独立改动未纳入。未推送/发布。
- **清理**：移除经核实为空或只含本阶段合成 v8 SQLite 测试库/结果的 `.tmp/p2a-test-tmp`、`.tmp/p2a3-v8-v9-*`；设备 APK 临时比对副本已删除。保留视觉截图、签名配置及现有隔离 AVD，不清理未知目录、正式资料或历史凭据。
- **Release 候选**：运行 `pwsh tools/build-preview.ps1 -VersionCode 14 -VersionName 0.1.0-p2a-final-candidate`，`assembleRelease` 成功。APK：`app/build/outputs/apk/release/app-release.apk`，大小 16,240,219 bytes；SHA-256 `F20F4E6DB0251F605101E040D5BA6DC6950CBD7FF85151FC1C0C9BEE8F1C1DCD`。`aapt` 确认包名 `app.solochat`、versionCode 14、versionName `0.1.0-p2a-final-candidate`；`apksigner verify` 通过。PKM110 当前正式包为 versionCode 13 / `0.1.0-p2a-ui-review`；其安装 APK 与候选包的签名证书 SHA-256 均为 `b8e441665be78b3ff12c6bb481c235cb0dfc09466fb2c7f9ec70629ad65fdbb0`。候选版版本码可覆盖当前版。只读取设备包信息并拉取安装 APK 作签名比对，随后移除临时副本；未安装候选包。
- **升级内容**：首次启动会在配置门闩下协调旧偏好到 DataStore v2；Room 从 v8 迁至 v9，追加会话模型绑定及调用配置快照表。迁移计划先冻结旧来源/ID 对应，再事务写入关联，最后发布 READY；不清理旧偏好、Key 槽位或历史账目。隔离 Room v8→v9 与合成数据的中断/恢复证据沿用前述记录，未触碰正式数据库。
- **备份边界与授权安装**：Manifest 的 `allowBackup` 与 `fullBackupContent` 均关闭。应用 ZIP 备份仅含 Room 聊天资料，不含 DataStore 配置、价格偏好或 SecretStore Key；导入是追加为暂停副本，不是原样回滚。此前未确认完整 app-private Room + DataStore + SecretStore/Keystore 的可恢复副本。2026-10-25 用户明确授权不再做额外备份检查并直接安装；因此按授权继续，不将其记录为备份可恢复性已验证。旧 APK 不能被假定可读取已升到 v9 的 Room。若将来迁移失败，应保留现有应用数据，不卸载/清除、不尝试旧 APK 降级；优先用同签名、更高 versionCode 修复。
- **设备结果**：使用同包名/签名的候选包执行 `adb install -r` 返回 `Success`；随后启动 `app.solochat/.MainActivity`，ADB 状态 `ok`，设备包信息为 versionCode 14 / `0.1.0-p2a-final-candidate`。未执行额外备份检查、未启动真实厂商请求；本轮没有独立核对迁移后数据库行、DataStore READY 内容或历史账目，不能仅凭 Activity 启动状态宣称这些检查通过。
- **剩余验收**：正式包已覆盖并启动，但真实厂商请求、迁移后资料逐行/偏好核对、恢复演练和完整 Android 功能验收仍未完成。未发布；P2-A 技术阶段已关闭，正式启用验收仍按 PLAN 保留边界。
