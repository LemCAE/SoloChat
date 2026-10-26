# P2-B 实施记录：B1～B6

> 更新：2026-10-26。B1.1～B2.2 已完成且用户接受；按调整后的审核安排，不做中途 Sol 复核，独立复核并入 B6。B3～B5 已完成并由用户自行通过 Android UI 视觉与交互验收；B6 定向集成验证已完成。Sol 对 B1～B6 的最终整体复核通过，技术阶段关闭；本记录不代表 P2-B 已正式启用。

## 完成内容

### B1.1 报价契约

- `ModelRegistryDocument` 增加默认 `revision=0`，仍使用文档格式 v2；`RegistryProvider.pricing=null` 表示未整理，非空空集合表示已整理但没有绑定报价。
- 新增内部版本 1 的 `ProviderPricing`、精确请求 ID 绑定、未关联报价、来源/适用条件和旧价存档。持久化校验检查 R4 完整规则、Provider/模型归属、版本、身份、思考条件与连接指纹；旧 v2 缺字段仍按默认值解码。
- 没有改 R4 算法、全局格式版本、Room schema、历史调用/费用、ZIP 或真实配置。

### B1.2 只读旧价计划

- 新增纯 `ProviderPricingMigrationPlan.create`：调用方提供文档、Provider 和随包目录快照（可为 null）；计划给出 revision、SHA-256 源指纹、绑定、未知项、有效旧表未关联项、完整旧价及随包存档和可发布结果。
- 抽出 `LegacyPricingInput`，旧调用准备与计划共享兼容字段语义；模型匹配复用 `PricingRules.matchedModelId`，旧目录结果及兼容价均保留完整规则。包括禁用模型；未配置仍未知，零价、缓存 null/0 分开表达。
- 固定输入重复计算稳定；来源、模型身份、资格或随包快照变化可识别。计划本身不写 DataStore、Room、SecretStore 或真实资产。

### B2.1 原子保存与身份解绑

- READY 保存必须带 `expectedRevision`，DataStore `edit` 内再次比对当前修订；文档只能递增一版，溢出、旧 revision、未 READY 均拒绝。模型草稿经正式发布器合并，报价和旧兼容价由当前文档提供；新 Provider 明确得到空报价配置。
- 旧价格页写入也要带 revision，仅接受尚未整理 Provider；已整理时拒绝写旧字段。新报价编辑与整理提交共用小型生产发布器及 `configMutex`，提交前校验目标、规则、revision；整理计划会在当前文档上重算并校验源指纹。
- 显示名/思考编辑保留绑定；请求 ID 改动/模型删除同次将完整报价移到未关联，之后改回不自动挂回。尚未整理的 Provider 新增/删除模型或改请求 ID 会要求先提交整理计划。
- 新 Key 仍由原凭据发布器写入新槽位；草稿版本冲突在 Key 写入前拒绝。价格接口不接触 SecretStore。价格保存后的受控草稿回填仅在基准 revision 相同时更新报价与 revision，保留其他 dirty 编辑；过期草稿保留并拒绝合并。

### B2.2 正式调用与计价

- `pricing != null` 后，`ModelAwareCallPreparation` 只按 Provider/model ID 取绑定并核对精确 request ID、思考用途及连接条件；缺失/不适用报价为 unknown，不回退旧表、随包价或兼容价。非法文档拒绝准备。`pricing == null` 继续使用提取后的旧输入装配与同一 R4 解析器。
- 新路径用 `resolvePriceEntry` 冻结完整来源、条件和规则，按每次 `startedAt` 求时段，再沿原 `forInput` / `recordedCost` 处理；fingerprint 覆盖身份、规则、来源/条件及用途意图，不含 Key。聊天/压缩仍使用原 PreparedAgentCall 和记账入口。
- 没有修改历史 call_record、配置快照、Room、消息或备份数据；没有接入新 UI、参考库、价格交换 v3 或自动整理入口。

## 定向验证

最终执行命令（临时目录指向工作区 `.tmp`）：

```powershell
pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.pricing.ProviderPricingTest --tests app.solochat.data.pricing.ProviderPricingMigrationPlanTest --tests app.solochat.data.pricing.ProviderPricingPublisherTest --tests app.solochat.data.pricing.ProviderPricingIntegrationTest --tests app.solochat.data.ModelAwareCallPreparationTest --tests app.solochat.data.ProviderCredentialPublisherTest --tests app.solochat.data.SettingsStoreTest --tests app.solochat.data.ModelConfigUpgradeCoordinatorTest --tests app.solochat.ui.settings.P2ModelSettingsDraftTest --tests app.solochat.play.CompressionServiceTest --tests 'app.solochat.play.SessionControllerTest.正式Runner使用新绑定报价准备并将同一费率写入CallRecord'
```

结果：`BUILD SUCCESSFUL`；本次选中的 87 项通过，0 失败/错误。覆盖契约兼容/完整规则往返、旧价 null/空/零/来源匹配、R4 峰谷/阶梯/日历与未知用量、迁移幂等/过期拒绝、临时真实 DataStore CAS 和关闭重开、旧 Key/价格字段边界、模型草稿受控回填、正式凭据发布器、Runner 调用及多段 CompressionService 按实际开始时间取价。贯通用例使用合成配置与 Fake LLM；不发网络请求。

## 偏差与未验证边界

- 与任务书没有实质架构或产品规则偏差；沿用 DataStore、`configMutex`、ProviderCredentialPublisher、PreparedAgentCall 和 R4。新增 `ReadyModelRegistryMerger` 是为模型草稿建立字段边界与复用解绑投影的局部生产助手，没有新建通用仓库。
- DataStore/Runner/CompressionService 测试不等同于 Android Application 重启、Room/历史记录逐字段备份往返、真机交互、真实 Key、真实旧用户配置转换或厂商账单核对；本轮均未执行。
- B4 前旧报价页仍是过渡入口：已整理 Provider 的旧字段保存会明确拒绝并提示使用模型报价入口，但新报价 UI 尚未接入。未整理 Provider 的身份变更需要调用“预览计划→明确提交”后端，B4 前没有可供用户操作的完整界面。
- 原任务书要求 B1～B2 后单独 Sol 复核，已按用户指令调整为 B6 对整个 P2-B 统一复核，不降低原测试与验收标准。

## B3 价格交换 v3

- 保留既有 `PriceTableTransfer` 的 v1/v2 兼容实现，新增独立 `PriceExchangeTransfer` v3。v3 交换精确 requestModelId、完整 R4 `ModelPriceEntry`、来源/适用条件、未关联条目、所有旧价格表（含非生效表）、旧兜底开关与金额，以及只读存档；文件内仅使用生成的 entry/table ID，不携带 Provider/model 稳定 ID、URL endpoint 或 Key。
- 新增 `docs/reference/pricing-exchange-v3.schema.json` 和合成示例 `pricing-exchange-v3.example.json`。Kotlin codec 限制 2 MiB，接受 BOM，严格拒绝缺少必需字段与未知字段；未关联/未知价不构造成零价。相同目标/大小写近似不会由 codec 合并，映射由 app `PriceExchangeImportPlan` 精确提出；歧义需显式映射，未匹配保留未关联。
- App 导入计划默认跳过已有报价，明确选择才整条替换；导入的未关联条目与只读档案保留在报价草稿的隔离分区，发布仍经 `ProviderPricingPublisher` 和 revision CAS。取消/解析失败没有保存入口。
- 定向命令 `pwsh tools/build.ps1 :core:cleanTest :core:test --tests app.solochat.core.model.PriceTableTransferTest --tests app.solochat.core.model.PriceExchangeTransferTest`：5 项通过，0 失败/错误。覆盖原 v1/v2 单表往返、v3 完整规则/存档往返、BOM、缺字段/未知字段、大小写不同请求 ID 原样保留和秘密内容不回显。

## B4 报价编辑与旧价整理

- 设置价格入口切换到 Provider 报价页：现有模型身份只读，编辑复用 `ModelPriceEditorScreen` 与 `AdvancedPriceEditor`；单模型“完成”更新本地草稿，`保存报价` 走正式 `SoloChatApp.saveModelPricing`/配置锁/revision。切 Provider 时未保存状态受保护；CAS 冲突和写入失败保留当前草稿。
- 普通费率拒绝空白；新报价不以 0 初始化。阶梯存在时顶部字段读取并更新第 1 档费率，R4 规则仍完整保留。峰价、阶梯、日历折叠为独立显示状态；显式清除通过既有编辑器的二次确认。
- 尚未整理的旧价显示 `ProviderPricingMigrationPlan` 预览，确认后用既有迁移发布器；旧 Provider 原价、所有价表、非生效表、兜底开关/金额、随包源快照由只读存档保留。没有改真实配置。
- 新增 v3 SAF 导入/导出入口，旧 v1/v2 文件仍可读。导入映射在预览中展示，默认跳过已有价，可明确整条覆盖；未关联和文件档案保持非活动。

## B5 参考库与逐模型采用

- `ReferencePrices` 增加稳定条目、渠道/地域、核对日期、官方来源、精确请求 ID、完整规则与条件。DeepSeek 两项保持峰谷默认关闭，因 2026 节假日日历未确认，采用预览要求用户核对并确认；百炼条目区分 qwen-max / qwen3.7-plus 的 OFF 模式与阶梯，qwen3.8-max 缓存价格不完整而只读。
- 报价页按精确模型展示参考条目；渠道不一致须用户确认，并把当前非秘密连接指纹绑定到 adopted quote；请求/思考条件不匹配时不能采用。全规则差异预览之后，参考替换只更新报价草稿，父页保存后才通过生产发布器生效。
- 首批价格与条件按官方 [DeepSeek API 价格页](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)、[阿里云百炼模型价格页](https://help.aliyun.com/zh/model-studio/model-pricing) 和[上下文缓存说明](https://help.aliyun.com/zh/model-studio/context-cache)逐条核对；没有调用远端更新接口。用户自定义估算仍可通过普通编辑填写，不保留官方参考来源。

## B3～B5 定向验证与边界

- Core 定向命令：`pwsh tools/build.ps1 :core:cleanTest :core:test --tests app.solochat.core.model.PriceTableTransferTest --tests app.solochat.core.model.PriceExchangeTransferTest`，5 项通过、0 失败/错误。保留 v1/v2 reader，验证 v3 往返、BOM、规则/存档、字段拒绝与大小写 request ID。
- App 定向命令（`TEMP`/`TMP` 指向工作区 `.tmp`）：`pwsh tools/build.ps1 :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests app.solochat.data.pricing.PriceExchangeImportPlanTest --tests app.solochat.data.pricing.ReferencePricesTest --tests app.solochat.data.pricing.ProviderPricingTest --tests app.solochat.data.pricing.ProviderPricingMigrationPlanTest --tests app.solochat.data.pricing.ProviderPricingPublisherTest --tests app.solochat.data.pricing.ProviderPricingIntegrationTest`，19 项通过、0 失败/错误。贯通用例沿真实 v3 codec、导入计划、生产发布器、临时 DataStore 关闭重开、`ModelAwareCallPreparation`、R4 和 `recordedCost`；使用合成数据及 Fake 端口，不发网络请求。另执行 `:app:assembleDebug` 成功，Compose/Kotlin 编译通过。
- B3 Schema 和示例通过本机 JSON 解析；金额/R4 语义由 Kotlin codec 校验，未使用另一套 Schema 计价器。真机截图来自 PKM110 竖屏 1256×2760（约 359dp）的隔离 `app.solochat.debug`；只建立合成 `P2BTest`/`P2BChat`，endpoint 为 `example.invalid`、没有 API Key，未发起请求。演示后删除本轮合成 Provider；正式 `app.solochat` 未启动/覆盖。
- Android 视觉证据见 [P2-B Compose 截图](p2-b-compose-evidence/README.md)。覆盖报价列表（未知与合成报价）、普通编辑、峰价/阶梯独立折叠、高级页面、参考列表/采用条件确认、旧价格整理预览、精确 ID 导入预览，以及明暗主题。未知页没有绑定报价；零价截图仅展示未保存草稿。完整规则差异弹层未在本次真机截图中展示，仍可查看此前已通过视觉评审的 Demo 基准图。导入截图证明精确 ID 对应现有模型及“导入到草稿”入口，但因测试时没有已生效目标报价，未用该画面验证已有报价跳过/覆盖提示；价格冲突与默认跳过由 JVM 导入计划测试覆盖。
- 真机只核对上述窄屏隔离配置；没有覆盖键盘打开时全部字段滚动、保存失败弹层、完整零价保存、不同厂商设备/字体尺寸。迁移截图来自空旧配置，不代表真实旧价已整理或全量存档；未在 UI 上提交迁移、导入或报价保存。没有进行 Android 进程重启、跨安装真实文件选择器、真实业务资料/Key、厂商实际账单或历史账目操作。
- B3～B5 的 Android 设备与截图范围仅限上一节所列隔离窄屏；B6 追加的存储/调用贯通验证和剩余边界见下一节。本阶段仍待 Sol 整体独立复核，不代表 P2-B 已正式启用。

## B6 整体集成验证

- 扩展现有 `ProviderPricingIntegrationTest` 生产链路：用临时 DataStore 从合成旧设置生成 READY 配置，旧价计划显式提交后关闭并重开 DataStore；重开后逐项确认源旧价格配置、活动目录、非生效目录、活动表未关联条目、兜底输入/输出及缓存零价仍在只读档案，活动未关联价仍留在单独区。
- 同一测试经生产 v3 codec 编码/解码，导入完整旧/额外档案及未关联报价。重复映射到同一模型时计划不可执行，尝试应用失败后 DataStore 文档与 revision 不变；已有报价在默认 skip 计划下保持原规则；显式覆盖后经 `ProviderPricingPublisher` 保存。模型展示名草稿随后经 `ProviderCredentialPublisher` 发布；过期草稿仍在 Key 写入前失败。关闭重开后核对模型/价格/档案/未关联项全部保留。
- 调用准备沿 `ModelAwareCallPreparation` 与真实 R4 规则计算首个 `CallRecord`。随后只改未来报价并再次发布：已准备的调用费率不变，既有合成 `CallRecord` 的冻结快照与 `recordedCost` 仍相同；下一次准备才读取新费率。另复跑正式 `SessionController` Runner 用例，确认同一绑定报价写入调用记录。
- B6 定向命令：Core `pwsh tools/build.ps1 :core:cleanTest :core:test --tests app.solochat.core.model.PriceTableTransferTest --tests app.solochat.core.model.PriceExchangeTransferTest`，5 项通过；App `pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.pricing.ProviderPricingIntegrationTest --tests 'app.solochat.play.SessionControllerTest.正式Runner使用新绑定报价准备并将同一费率写入CallRecord'`，4 项通过。两组均 0 失败/错误。仅使用合成旧配置、Key 字符串、调用记录与临时 DataStore，没有网络请求或真实账目。
- 与计划无实质偏差：沿用 `ProviderPricingMigrationPlan`、`ProviderPricingPublisher`、DataStore revision CAS、v3 codec/导入计划、`ModelAwareCallPreparation`、R4、Runner 与 `recordedCost`；没有新增 Room/schema 或计价实现。用户已自行完成 Android UI 验收；当前交付 Sol 对 B1～B6 的整体独立复核，不由本实施记录宣布关闭。
- 未验证真实旧设备配置的实际转换、Android 应用进程级重启、Room 中历史行逐字段对比、真实厂商账单、跨安装真实文件选择器兼容及正式包升级；不操作用户资料、Key 或费用记录。B6 的“历史费用保护”证据是未来 DataStore 价格更新后既有冻结 `CallRecord` 与生产 `recordedCost` 不变，不代替 Room 数据库验收。

### 第二轮复核问题修复

- Sol 增量复核指出高级编辑只同步了“高级规则→顶部表单”，顶部第一档的暂存修改仍可能在进入高级页面时被旧规则覆盖。现打开高级编辑前先把有效的顶部第 1 档输入/输出/缓存/峰价草稿同步进 R4 阶梯；存在空白/非法字段时留在编辑页并提示，不把空值转换为零。增加 `AdvancedPriceEditorDraftTest` 验证首档覆盖、其余档及日历保留。
- Sol 指出 Schema 对 `window.timeZone`、`schedule` 与 `calendar` 的嵌套必填约束严于 Kotlin decoder，后者会用 R4 默认值补出字段。现 v3 codec 对生效项、未关联项及旧表存档共用嵌套形状验证，要求 window、schedule、range、calendar、rates 与 tier 的完整字段；补充窗口时区与日历来源缺失的负例，并把这些嵌套必需字段纳入 Schema 契约测试。
- 最终定向验证：Core `pwsh tools/build.ps1 :core:test --tests app.solochat.core.model.PriceExchangeTransferTest --rerun-tasks`，4 项通过；App（`TEMP/TMP` 指向工作区 `.tmp`）`pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.ui.settings.AdvancedPriceEditorDraftTest --tests app.solochat.data.pricing.PriceExchangeImportPlanTest --tests app.solochat.data.pricing.ProviderPricingPublisherTest --tests app.solochat.data.pricing.ReferencePricesTest --tests app.solochat.data.pricing.ProviderPricingTest --tests app.solochat.data.pricing.ProviderPricingIntegrationTest`，19 项通过、0 失败/错误，含 Compose 主源码编译。Schema 文件是 Gradle 测试的外部输入，Core 使用 `--rerun-tasks` 确认测试实际重跑而不是复用缓存。
- `git diff --check` 通过（已有文件只有换行转换提示）。此次 UI 仅完成 Compose 编译及纯草稿回填测试，没有重新采集设备截图；新增/修正交互未做设备视觉复验。API 26 实机运行、真实配置、Key、Room 历史行和真实账单仍未验证。当前待 Sol 对全阶段及第二轮修复做最终确认，不关闭 P2-B。

### 最终复核发现修复

- Sol 最后一轮只读复核发现，v3 decoder 虽要求每个报价条目包含 `origin` 与 `conditions` 对象，但未校验对象内必需字段。因 Kotlin data class 默认值，`conditions: {}` 会被读成无思考模式/连接指纹限制，`origin: {}` 也会被补成默认 MANUAL 来源，与公开 Schema 的必填契约不一致。
- `PriceExchangeTransfer.decode` 现对 `origin` 的 `kind/sourceId/channel/checkedAt/url/note` 和 `conditions` 的 `thinkingMode/connectionFingerprint` 做显式必填检查；未知字段仍由严格 JSON decoder 拒绝。没有改变业务默认值或计价规则。
- Core 定向命令 `pwsh tools/build.ps1 :core:test --tests app.solochat.core.model.PriceExchangeTransferTest --rerun-tasks`：4 项通过、0 失败/错误。回归用例覆盖 Schema 对两个对象的 required 集合，并确认缺少 `origin.kind` 或 `conditions.thinkingMode` 的文件被拒绝。此前 App 19 项定向结果未受此次 Core-only 改动影响，未重复运行。
- 本次仅代码与测试/文档修改，没有操作真实价格、用户设置、Key、Room 账目、设备或正式包。Sol 已对补修完成最终只读确认并通过整体技术复核；修复后 UI 真机截图、API 26 实机、真实旧配置转换、进程重启、跨安装恢复、Room 历史逐行核对和真实账单仍未验证。

### P2-B 最终整体复核结论

- Sol 最终只读复核确认 B1～B6 无剩余技术关闭阻塞，建议关闭 P2-B 技术阶段；B1～B2 已并入该整体结论。此前各轮发现均已消除。Sol 未修改工作区或操作设备、真实资料；未提交、推送或发布。
- 最新 Core 测试 XML 为 `PriceExchangeTransferTest` 4 项通过、0 失败/错误；App 19 项沿用已核实且未受 Core-only 补修影响的上一轮结果，本轮没有重复运行。
- 关闭范围仅为技术阶段。正式启用前仍待独立验收：修复后的 UI 交互与 API 26 导入、受保护真实配置转换、应用进程重启、正式包升级与跨安装恢复、历史数据库逐行核对、真实请求及厂商账单核对。未触碰这些真实数据或服务。

### 用户手动试用后的 UI 调整

- 提供商“模型”页增加按显示名/请求 ID 搜索；“已加入 N 个模型”改为实际新增数，并统一提示文字起始边距。
- “模型报价”页在标题栏增加搜索入口，按已设置/未设置分组；未设置组默认折叠，搜索时展示匹配结果。
- 编辑模型思考设置增加说明图标，分别解释 AUTO/ON/OFF、请求强度、模型能力确认及参数方言。定位用户截图中的拒绝原因：选择 `DASHSCOPE_ENABLE_THINKING` 时 App 只编码 `enable_thinking` 开关，不编码 `low/high/...` 强度；截图同时选择 ON 与 high，因此 `ThinkingRequestParameters` 校验拒绝，旧界面却把异常显示成泛化提示。现明确显示冲突原因，并阻止该组合完成保存；已确认能力中的强度清单和本次请求所选强度分别说明。
- 用户提供的 `docs/reference/comPic/*.jpg` 与既有 P2-B PNG 截图规则均已加入 `.gitignore`；截图仍保留在工作区。
- 窄屏搜索栏现用短标签“搜索模型”，占位文案“名称或请求 ID”；DashScope `enable_thinking` 方言下本次请求强度选项即时置灰，并显式提供“不指定”选项，已选的旧强度需用户明确清除后才可保存。
- `pwsh tools/build.ps1 :app:assembleDebug` 最终成功（初次编译发现并修正 Compose trailingIcon 槽位类型后重建通过；本轮调整置灰/不指定和搜索文案后再次构建通过）。最新 Debug APK 已用 `adb install -r` 安装并启动于 PKM110，包名 `app.solochat.debug`、版本码 1 / `0.1.0-debug`；没有清除其隔离数据或触碰正式包。本轮未运行自动化测试，等待用户手动复验；正式包与 commit 按用户安排暂缓。

## 首轮整体复核问题修复

- Sol 首轮独立复核发现 B3～B5 的十项缺口/缺陷，已按现有产品规则修复：确认 DeepSeek 日历后同时启用峰谷；高级规则改第 1 档时同步父表单；每次新解绑使用新未关联记录 ID；未关联报价保留思考模式及连接指纹；价格文件读取改为兼容 API 26 的有界循环；保存期间阻止改动并冻结提交快照；重复目标仅在真实应用项间冲突，并可逐条留为未关联；渠道核对采用 HTTPS 精确主机；未关联条目可查看完整规则并显式预览关联，旧表/导入档案可查看规则；v3 Schema 与 Kotlin 严格必需字段一致。
- 补充定向回归：重复解绑两份报价并存；未关联 v3 条件保留；冲突映射通过单项保留未关联解决且默认跳过时不报无效冲突；DeepSeek 确认采用会得到可用峰谷日历；伪造主机/HTTP/错误区域要求人工核对；Schema 必需字段和示例可由 Kotlin codec 接受。
- 验证：`pwsh tools/build.ps1 :core:test --tests app.solochat.core.model.PriceExchangeTransferTest`，3 项通过；`$env:TEMP/.tmp`、`$env:TMP/.tmp` 下执行 `pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.pricing.PriceExchangeImportPlanTest --tests app.solochat.data.pricing.ProviderPricingPublisherTest --tests app.solochat.data.pricing.ReferencePricesTest --tests app.solochat.data.pricing.ProviderPricingTest --tests app.solochat.data.pricing.ProviderPricingIntegrationTest`，18 项通过，0 失败/错误；该任务同时成功编译 `:app:compileDebugKotlin`（含 Compose）。`git diff --check` 通过，只有已有文件的换行风格提示。
- 未重新采集真机截图；本轮增补的未关联报价查看/关联交互尚未由用户在设备验收。API 26 的兼容实现通过源码与编译验证，未在 API 26 实机运行。上述边界仍未验证；首轮及后续修复复核记录见本记录末尾，P2-B 不关闭、不进入其他阶段。
