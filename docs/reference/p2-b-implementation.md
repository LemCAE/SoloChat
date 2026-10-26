# P2-B｜实施任务书 B1～B6

> 2026-10-25，Sol 方案 / 用户调整。技术方案见 [p2-b-plan](p2-b-plan.md)，UI 基准为已通过视觉评审并局部收敛的 [Demo v1](solochat-pricing-design-v1.html)。B1～B2 已完成并被接受；其独立复核并入 B6 对整个 P2-B 的最终复核。B3～B5 已完成并由用户通过 UI 验收，B6 集成验证已完成。两轮整体复核的问题均已修复并通过定向测试，当前待 Sol 最终复核。真实价格/配置/Key/账目不得在实施期操作。

## 1. 依赖与共同边界

基于 P2-A 技术关闭及 HEAD `75a823a` 的实际源码。开始每项前检查工作区，保留既有修改；实现与验收分别记录，不能把计划当交付结果。

| 顺序 | 单次交付 | 依赖 / 接入边界 |
|---|---|---|
| B1.1 | 报价持久化契约与验证 | 已批准方案；只扩展格式，不改变调用或自动转换 |
| B1.2 | 旧价格只读转换计划与等价性 | B1.1；纯生成计划，不写 READY、Room 或真实价格 |
| B2.1 | revision、字段级原子发布与身份解绑 | B1.2；接既有正式配置写入口，保留旧页必要适配，不接新 UI |
| B2.2 | 新绑定规则进入正式准备/计价链 | B2.1；接同一 R4 计算器，合成配置贯通；无自动迁移入口 |
| B3 | 价格 v3 交换、Schema、映射预览 | B1；保留 v1/v2 reader；不直接发布导入数据 |
| B4 | Provider 报价列表/编辑、旧价预览整理、导入导出 | B2、B3；草稿与 revision CAS；旧价完整只读存档 |
| B5 | 版本化参考库与逐模型采用 | B4；按具体渠道和模型给来源条件，采用只进草稿 |
| B6 | 全阶段集成和独立最终复核 | B1～B5；Sol 对整个 P2-B 统一复核后再决定关闭/启用 |

- 不动 Room v9/schema、备份 ZIP 格式、历史 call_record/Token/费用、人物/会话归属、P2-A 模型选择与思考策略。不读写真实 Key，不清理历史槽位；测试只使用合成值和临时 DataStore。
- 不实现参考库、v3 文件/Schema、新报价 UI 或真实价格迁移。既有 v1/v2 文件仍由原解析器处理，后续交换格式独立交付。
- UI 的“完成”只改父页内存；“保存报价”才调用发布 API。折叠状态不进入报价契约；空白字符串不得经 `toDoubleOrNull() ?: 0.0` 发布。明确清除与未填是不同命令。
- 仅跑本项相关测试；不全量、不强制 rerun、不装机或请求真实服务。新增测试与正式入口共用实现，不另建一套测试专用保存/解析引擎。

## 2. B1.1｜数据契约

**目标**：可兼容解码 P2-A 文档、完整保存 R4 规则，并精确表达身份、未知与旧来源。

**实际文件范围**（包路径均相对仓库）：

- 改 `app/src/main/kotlin/app/solochat/data/ModelRegistryFormat.kt`。
- 新增 `app/src/main/kotlin/app/solochat/data/pricing/ProviderPricing.kt`，相关小类型可合并在同文件。
- 测试：新增 `app/src/test/kotlin/app/solochat/data/pricing/ProviderPricingTest.kt`；按需扩充现有 `SettingsStoreTest.kt` 的旧文档往返，不改生产保存行为。
- 只读复用 `core/.../model/PriceCatalog.kt`、`Usage.kt`、`CallRecord.kt`；不改 R4 算法。

**具体契约**：

| 类型 / 字段 | 要求 |
|---|---|
| `ModelRegistryDocument.revision: Long = 0` | 非负；文档格式仍为 v2。与 `ModelConfigUpgradePlan.sourceRevision` 的旧源摘要完全不同，不能互代 |
| `RegistryProvider.pricing: ProviderPricing? = null` | null 表示尚未整理的旧源；非空即唯一生效绑定，包括空集合。B2 为正式新增 Provider 显式设置空配置，不能把类型默认值改成空而误判旧文档已整理 |
| `ProviderPricing(version=1, bindings, unassigned, legacyArchive)` | bindings 为 `Map<modelId, BoundModelPrice>`；未关联有独立稳定 ID；archive 可空且只读。同一 Provider 只有一份生效配置 |
| `BoundModelPrice(requestModelId, rule, origin, conditions)` | 精确、区分大小写的请求 ID 护栏；rule 为完整 `ModelPriceEntry`。modelId 是主键，`rule.model` 仅为 R4 合法小写规则键，不用于寻找模型 |
| `UnassignedModelPrice(id, requestModelId, rule, origin, reason)` | 原规则及来源原样保留，不参加运行；原因用有限类型，如旧条目未匹配、请求身份变化、模型删除。未知项如需承载，以显式无规则表示，禁止填零价 |
| `LegacyPricingArchive` | 保留整个原 `LegacyProviderPricing`，含选表、非生效表、开关、兜底、未启用规则及 null；实际参与兼容解析的随包源另存不可变快照及来源标识，并保留本次转换源指纹/计划标识供重试识别；不将其伪装为用户采用 |
| `PriceOrigin / PriceConditions` | 来源类型至少区分手动、旧表、旧兼容、旧随包、导入、参考；日期/链接/渠道缺失不补造。条件仅支持已规划的思考模式和连接适用性确认，不引入 DSL。连接确认可存非秘密指纹，不能在价格导出里带地址/Key |

持久化校验复用 `PriceCatalogFormat.validateEntry`，再检查内部版本、ID 唯一、绑定所属 Provider/模型及精确请求身份、非负有限数。完整保留 `standard/peak/peakEnabled/window/schedule/calendar/tiers/note/source/provider`；有阶梯时新编辑输出的 standard 与第 1 档一致，旧迁移 entry 的闲置 standard 仍原样保存，运行继续由 R4 选档，不为统一数据外观改旧值。

缺绑定即价格未知，不能用默认 `ModelPriceEntry()` 表示“没填”；明确 0 可以保存。缓存 null 表示随输入价，0 表示明确零价。字符串和未完成表单留在 UI 草稿；领域 Upsert 必须携带有效完整规则，Remove 必须是独立显式操作。

**定向验证 / 完成标准**：旧 v2 文档缺新增字段仍解码为 revision=0、pricing=null；带新字段完整往返；null 与空配置、未知与零、缓存 null/0、大小写同名和跨 Provider 模型均能区分。非法绑定/版本/数值明确拒绝。新增字段不破坏 P2-A 冻结 candidate、准备记录和 READY 后解码；不升级全局格式常量，不重写旧冻结记录。B1.1 不产生任何持久化转换。

## 3. B1.2｜只读转换计划

**目标**：先证明旧规则到新绑定的可恢复、可重试转换，再允许 B2 提供显式发布能力。

**文件范围**：新增 `app/.../data/pricing/ProviderPricingMigrationPlan.kt`；`ModelAwareCallPreparation.kt` 的旧价格输入装配可最小提取为 `data/pricing/LegacyPricingInput.kt`，由原准备器和计划共同使用。只读复用 `PricingRules` / `ModelPricing.initialCatalog`。测试新增 `ProviderPricingMigrationPlanTest.kt`，必要时调整现有 `ModelAwareCallPreparationTest.kt` 的共享输入断言。

**实施内容**：

1. 纯函数输入为文档快照、目标 providerId、必要的随包目录快照；输出 baseRevision、源指纹、bindings、unknown 项及原因、unassigned、完整 archive 和可发布结果。计划不包含 Key、聊天正文或历史调用，也不自行读取 assets/时钟/当前配置。
2. 源指纹覆盖目标旧价格全量、模型 ID/精确请求 ID/旧价资格及实际随包源；baseRevision 另作发布前并发校验。计划内记录身份由稳定来源位置生成，或一次生成后随计划复用；同源重建结果一致，不用每次随机追加存档。
3. 包括禁用模型。使用原输入装配和 `PricingRules.matchedModelId` 一次旧匹配：明确目录匹配可用于无旧兜底资格的既有模型；null 随包源与自填兜底只给原有资格模型。关闭读表时不能误用表中条目。已有匹配复制完整 entry，不能只取当前时刻单价。
4. 兼容兜底仅对该次源快照中有资格的具体模型物化；`priceConfigured=false` 仍未知，true 且 0 仍已知。未配置缓存忽略闲置数值并使用 null；明确缓存 0 保持 0。原闲置数值仍在 archive，不能丢失或误标为已核对。
5. 原有效表未匹配项进入未关联；非生效表留在全量存档，不创建模型或自动启用服务。旧小写/一次后缀可把同一规则复制给多个当时已有模型，但每个绑定保留自己的精确请求 ID；新运行不再用近似匹配。
6. `pricing != null` 返回已整理，不重新追加；新建 Provider 的空配置没有旧兼容资格。无法证明等价时计划标记阻止发布的原因，不近似补价。B1 不写任何标记，不接启动协调器或自动发布。

**定向验证 / 完成标准**：参数化覆盖开/关表、null 随包/空目录、显式目录与无资格模型、资格兜底、未知/零、缓存 null/0、同名/后缀、禁用模型、未关联和非生效表；未启用峰价、旧 window、多时段/星期/跨午夜、日历及阶梯数据完整保留。固定输入重复计划相同，源或模型变化可识别，原对象逐字段不变。

少量等价对照必须用实际 `PricingRules.resolveCatalog` 与新绑定的 `resolvePriceEntry`，比较 known、有效费率、cachedPriceKnown、冻结阶梯及 `forInput → recordedCost` 的范围/未知行为；不要求来源文案相同或闲置 cachedPerM 数字相同。取代表峰谷时刻、阶梯界点及缺返回量即可；复用 R4 边界测试，不复制整套算法测试。

## 4. B2.1｜原子保存、字段边界与身份解绑

**目标**：所有 READY 配置编辑共享修订检查，价格与模型草稿不互相覆盖；失败保持旧配置与草稿。

**文件范围**：`SettingsStore.kt`、`SoloChatApp.kt`、`ProviderCredentialPublisher.kt`、`ui/settings/P2ModelSettingsDraft.kt`、`ui/settings/SettingsScreen.kt` 的读版本/保存/回填适配。新增小型 `data/pricing/ProviderPricingPublisher.kt`（或同级配置更新助手），供宿主与 JVM 测试共用；不新建通用仓库。测试改 `SettingsStoreTest`、`ProviderCredentialPublisherTest`、`P2ModelSettingsDraftTest`；新增 `ProviderPricingPublisherTest`。

**保存契约与顺序**：

- 价格请求只传 providerId、expectedRevision、明确的模型 Upsert/Remove 和未关联修改；迁移另传已预览 plan，发布前重算必要旧源/随包源指纹与模型集合，拒绝过期预览。模型请求只传基础文档/版本与该页有权编辑的模型、连接、用途字段，不准提交候选 pricing/legacyPricing 来覆盖当前值；可以继续使用现有草稿形状，在正式合并器内剔除越权字段。
- 宿主持 `configMutex`，先 ensure READY，读取当前文档并检查 expectedRevision、目标存在性、规则/身份及移除限制，再构造最新文档上的合并结果。DataStore 的 READY edit 内再次比较磁盘 revision，成功一次递增并返回完整已保存文档；异常/冲突不写、不递增、不更新 runtimeConfig。版本耗尽明确拒绝，不能溢出复用。
- 新版所有 READY 写入必须收敛到这一入口；审计 `saveModelRegistry`、`saveProviderPricing` 和 `saveReadyModelRegistryV2`，不得留无版本旁路。初始化 `saveModelRegistryV2` 与 `publishModelConfigReady` 只属于原 P2-A 准备/冻结发布，保留原门禁和一致性，不套 READY 编辑递增、更不修改 frozen candidate。READY 重启校验当前文档，不能再比较旧冻结候选。
- 密钥发布前完成上述版本、内容及关联校验；替换/新增仍走 P2-A 新槽位发布器，不能信任草稿 keyId。DataStore 最后 CAS 失败时只可能遗留新的孤立槽位，旧引用/Key 不变且不清理。价格发布接口本身不能访问 SecretStore。
- 模型显示名/启用/思考编辑保留当前报价；精确 requestModelId 改变或模型删除时，同次文档发布将原绑定移到未关联，保留全规则与原身份，后续改回也不自动回挂。禁止把一个已有 modelId 改到另一 Provider。新模型无绑定、无旧价资格，新 Provider 显式空 pricing。草稿 withModel/删除也要兼容新绑定校验：可复用同一纯解绑助手生成本地投影，正式发布仍按当前文档重新计算，不信任草稿报价；不能在编辑请求 ID 时先被旧绑定的 validate 卡死。删除 Provider 沿用已有会话引用限制，未来 UI 的报价影响/导出确认不在本项新增。
- 本页价格保存成功，只在父模型草稿的基准 revision 恰好等于本次价格提交的 expectedRevision 时，合并返回的报价和修订号，保留未保存模型/用途/Key 编辑及 dirty 状态。若草稿本已过期或还有其他并发未确认变更，不偷换新基准；保存时报冲突。不能只把 revision 改成最新来绕过冲突。
- 未预期外部写入统一拒绝过期草稿，返回稳定的冲突结果，保留内存；重新载入须明确丢弃确认。不做通用自动三方合并。失败不可调用清草稿/成功回调。

**过渡接口依赖**：B4 前保留旧页，但其保存也必须携带加载时 revision。旧 `LegacyProviderPricing` 编辑只可作用尚未整理的 Provider；pricing 非空后拒绝旧页写入并指向新入口，不能写一个运行时不再读取的旧表。B2 提供“预览计划→明确提交”的后端，不自动接启动、不在后台批量整理。

尚未整理的 Provider 若要新增/删除模型或改变请求 ID，不能先用改后的集合重建计划而把旧价重新归给新身份。后端要求先明确预览并提交旧价整理，再做身份变更；B4 接上该入口前，此路径明确返回需整理状态。B2 是中间接口交付，不能单独作为完整新版价格体验发布；不通过自动转换真实价格绕过依赖。普通改显示名/思考等不受此要求影响。

**定向验证 / 完成标准**：

1. 临时真实 DataStore 的同版本两个保存仅一成功；失败/CAS 冲突后文档、revision 与 READY/旧偏好不变；成功返回 r+1，关 store 再开仍一致。故障点在 edit 提交前，不能用只检查内存的替身冒充原子性。
2. 价格→模型保存、模型→旧价格草稿保存、同页受控回填与外部过期四种顺序；其他 Provider、用途、连接、Key 引用、未动模型报价均保留。改请求 ID/删模型后旧价只到未关联；重复重试不重复追加。
3. 旧转换计划成功一次；提交后重交同一计划识别已应用且无追加/递增，先核对 archive 的转换标识；结果已被用户继续修改时返回已整理/不再应用，不用旧计划覆盖；r 过期且未应用时拒绝。转换失败原 legacy 和 null pricing 不变，重试正确。
4. 版本冲突在 Key 写入之前被发现，不产生写 Key 调用；发布后故障保持 R11 槽位隔离。回归原冻结→READY、READY 编辑→重启，不重跑整套 Room。

## 5. B2.2｜正式调用适配与少量贯通验证

**目标**：新模型绑定经正式 v2 预检进入原 R4 计价与历史快照链，聊天和压缩规则一致。

**文件范围**：`data/ModelAwareCallPreparation.kt`、`data/pricing/ModelPricing.kt`，新增/扩展 `data/pricing/ProviderPricingResolver.kt`；`SoloChatApp.kt` 调用装配及必要的价格摘要适配。只在确有契约调整时改 `core/.../agent/AgentDeps.kt`、`AgentTurnRunner.kt`、`CompressionService.kt`；原则上复用原 PreparedAgentCall。测试扩充 `ModelAwareCallPreparationTest`、`play/CompressionServiceTest`，少量贯通可放 `data/pricing/ProviderPricingIntegrationTest`；聊天 Runner 可复用现有 `SessionControllerTest` 的夹具，不要求整类重跑。

**实施内容**：

1. pricing 非空时只按 providerId/modelId 查绑定，再精确核对 requestModelId 和条件；成功交 `resolvePriceEntry`，缺失/已清除/条件不能证明满足则 known=false，不查 legacy、随包或参考。无效存储/版本明确拒绝，不以未知掩盖结构错误；价格未知本身不阻止合法请求。pricing 为 null 的旧链只在明确过渡范围复用原输入助手与同一 R4 引擎。
2. 捕获不可变的完整 entry、origin、conditions 和有效用途意图；`resolvePriceAt(at)` 不再读 settings/当前资产。条件核对用本次用途：聊天按 AUTO/ON/OFF，压缩独立 OFF；AUTO 无法证明限定模式时价格未知，不改实际请求来迎合报价。
3. 沿现有聊天 `AgentTurnRunner`、压缩每段/合并的 `startedAt` 调用 `priceAt(startedAt)` 选峰谷，并冻结当时所有阶梯。收尾仅用接口输入量 `forInput` 和原 `recordedCost`；不能把预览时 price 当所有后续调用价格。
4. `priceRuleFingerprint` 覆盖所选身份、完整规则、来源/条件及用途有效意图，编码确定且无 Key。规则变化使尚未执行的压缩预览过期；无关 Provider 或全局 revision 改变不能冒充选定规则已变。在途 PreparedAgentCall 不改、下一次才取新配置。
5. 来源可写入既有 ModelPrice.source，完整历史仍按原 call_record 保存；不增账表、不回填旧记录。审计正式模型价格摘要避免仍从 legacy 显示整理后的当前价；不展开新 UI。

**必须完成的贯通用例**（总计少量，复用实际组件和现有夹具）：

| 链路 | 必须看到的结果 |
|---|---|
| 旧配置→计划→READY CAS 保存→关闭重开→真实 ModelAwareCallPreparation→R4→recordedCost | 至少一例峰谷+阶梯+日历完整规则和一例零/缓存 0；转换前后有效金额/未知等价。新规则解析不能被统一返回固定价的 fake 替代 |
| 模型草稿未保存→价格保存→受控回填→模型正式发布→重读→调用准备 | 模型编辑和新价都在；另一份过期草稿（含 Key 修改）被拒绝；断言正式 Publisher 与 DataStore 方法真正被调用 |
| 正式聊天及多段压缩使用非空 PreparedAgentCall | 使用录制请求的 fake LLM、虚拟时钟和原 Runner/CompressionService，不发网络；跨峰谷边界每次实际开始时取价，执行中改价/改模型不影响已准备请求；压缩 OFF 与聊天 ON 可得不同适用性 |
| 改 requestModelId / 清除→发布→下一次调用 | 原价归未关联或明确清除，新价格未知；即使旧表/随包有同名价格也不回退。不自动换模型，不影响已开始调用 |
| 已有 CallRecord / 快照→修改未来报价→历史读取/原备份夹具往返 | 原费用、价格字段、Token、配置快照逐字段不变，无二次记账；不引入 Room 变更才不要求新增设备数据库测试 |

宿主不能在普通 JVM 实例化 Android Application 时，将配置合并/发布的必要逻辑最小提取给 SoloChatApp 委托，贯通用例调用同一生产助手和真实临时 DataStore。Room/网络可用既有端口替身，但明确该证据不等于 Android 重启、真实服务账单或正式备份恢复；不要写一条只在测试里成立的“完整链路”。

**验证命令与完成标准**：按实际修改选择 `pwsh tools/build.ps1 :app:testDebugUnitTest --tests <本项类全名>`；core 只在触及共享契约或需复核边界时选跑 `AdvancedPricingTest` 等对应类。选中的 App 测试任务已含相关 Kotlin 编译，不额外默认打 APK。临时目录沿现有 SettingsStore 测试约定。交付报告写本次命令、实际测试结果与未验证边界，不累计旧 XML 数量。

B2 完成须能从一次明确提交到重读/正式预检/冻结计价说明同一份数据契约；纯单元测试通过不足以代替上述集成。B1～B2 已由用户接受；不再做中途 Sol 复核，证据随 B6 合并审查。

## 5. B3｜价格 v3 交换格式

**实际范围**：扩展 core `PriceTableTransfer.kt` 同目录的 `PriceExchangeTransfer`，新增 v3 文档/条目/来源/条件/旧存档结构；在 `docs/reference/` 提供 JSON Schema 和合成示例；app 新增 `PriceExchangeImportPlan` 做目标映射预览。

**验收边界**：v1/v2 原 reader 与已有格式测试继续通过；v3 显式保留完整 R4 entry、请求 ID、来源/条件、未关联报价、旧表/兜底开关和非生效表存档。严格限制 2 MiB、BOM、必需字段、未知字段和无凭据字段；允许大小写不同的精确 request ID 原样往返，但不能据大小写近似自动关联。导入必须先选目标 Provider；多候选、大小写仅相似、同一目标重复映射均须用户解决。无匹配项保留未关联；已有报价默认跳过，明确选择才整条覆盖；不自动增模型/改激活状态/发布配置。

**定向验证**：v1/v2 reader、v3 完整规则/存档往返、BOM/超限/未知字段/缺字段、精确 ID/大小写与重复映射、未关联、现有报价跳过及失败不发布。Schema 与样例结构核验；金额仍由 Kotlin 规则校验，Schema 不证明来源价格正确。

## 6. B4｜报价编辑与旧价整理

**界面层级**：Provider → 模型报价 → 单模型编辑。以 Demo v1 为基准，不重做全局风格。模型绑定身份只读；新建报价不能用默认 0 填满表单。输入/输出/缓存直接编辑；空白拒绝提交，缓存 null 与 0 分开；有阶梯时顶部单价编辑第一档实际费率，不造重复基础费率。高级规则与峰价、阶梯、日历分别折叠，折叠只影响显示，完整规则保留。单模型“完成”只改本页草稿；“保存报价”走 `SoloChatApp.saveModelPricing` 和 revision CAS。删除报价单独二次确认。冲突/保存失败保留草稿。

**旧价整理与档案**：仅显示 `ProviderPricingMigrationPlan` 预览，展示可绑定/未知/未关联数量和将保留的旧数据；用户明确确认后调用既有发布器。完整存档保留原价目录、选表、非生效表、兜底开关/金额、来源以及兼容随包快照；旧表仅查看/导出，不参加运行。导入 v3/兼容 v1、v2 先展示映射和覆盖预览，批量默认跳过；所有修改先作用于本页草稿，父页保存后才生效。不得对真实历史资料做自动整理。

**定向验证与视觉验收**：编译 Compose；覆盖空白/0、缓存 null/0、清除确认、模型身份锁定、第一阶梯写回、折叠后规则保留、父草稿 CAS、保存失败保留及旧价完整存档。B4～B5 完成后暂停，提供列表/普通与高级编辑/冲突/状态的 Android Compose 或隔离设备截图，等待用户视觉及交互验收；未验收前不得开始 B6。

## 7. B5｜只读参考库与逐模型采用

**数据与来源**：首批只放两个已规划渠道的少量精确模型条目，字段包括稳定条目 ID、渠道/地域、requestModelId、核对日期、官方来源链接、完整规则和适用条件；不联网更新。价格与来源逐项核对，条件不能被当前规则完整表达时仅供只读核对。峰谷日历未确认时要求用户显式确认，不能把谷价说成全天价；模型思考模式不匹配则不能采用。连接渠道不匹配需用户确认，采用时保存非秘密连接指纹。

**采用操作**：只对已存在的目标 Provider/model ID 提供候选；不按同名自动跨 Provider 关联，不自动创建模型。采用前预览输入/输出/缓存、峰价/时段/日历/阶梯、来源及条件的完整替换；确认后替换整条规则并仅更新草稿。已有报价不被参考更新触碰；自定义估算必须与已核对参考来源分开标记。

**定向验证**：逐条核对参考数据元信息、准确/只读判定、渠道与思考条件门槛、全规则替换差异、只入草稿、现有报价不自动变动。采用后通过同一生产 `ProviderPricingPublisher` 保存，调用仍经 `ProviderPricingResolver` 和 R4；不复制计价算法。

## 8. B6 与最终复核

B6 在 Android UI 验收通过后开始。已使用合成配置、临时 DataStore 与现有调用准备/R4/Runner 路径完成定向验证：旧价整理提交与重开保存完整旧表/未关联/兼容价，v3 往返、默认跳过、显式覆盖及映射冲突失败不发布，模型与价格草稿交叉保存，已准备调用和历史费用不受未来改价影响。具体测试命令、断言与未验证边界见[实施记录](p2-b-implementation-record.md)。没有操作真实 Key、价格或账目，也没有改 Room/schema。

B1～B6 交付证据整理完毕后，Sol 只做一次覆盖整个 P2-B 的独立最终复核。通过后再按用户明确的后续授权决定阶段关闭或正式启用；不得由某个子项完成自动推导阶段关闭。
