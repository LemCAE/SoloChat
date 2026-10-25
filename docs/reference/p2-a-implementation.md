# P2-A｜实施顺序与首批 Luna 任务

> 2026-10-25 定稿。产品/架构和阶段验收以 [PLAN](../PLAN.md) 为准，当前状态见 [HANDOFF](../HANDOFF.md)。P1 基线 `db806d0`，不发布。用户逐项交给 Luna，本文件不授权自动调度或一次实施全部任务。

> 2026-10-25 技术关闭：P2-A-1～10 及 R1～R11 已通过独立复核；本任务书保留用于追溯，正式启用待验收项见 PLAN。本次关闭不授权提交、发布或实施 P2-B。

## 1. 依赖与交付边界

先数据层、再接口/调用层，最后接已批准 Demo。任务 1～7 的实现保持未接入当前运行入口；不通过本轮真实升级去测试半成品。任务 10 在备份、调用与修复页面就绪后统一启用 v2，不引入可长期切换的新旧双引擎。

| 任务 | 依赖 | 单次交付目标 / 定向证据 |
|---|---|---|
| P2-A-1 数据契约与纯解析 | P1 | 稳定身份、继承/待确认结果、独立思考意图；core 定向用例 |
| P2-A-2 配置存储与升级计划 | 1 | v2 非秘密文档、旧配置只读转换计划、旧价资格；DataStore/计划用例，不发布 ready |
| P2-A-3 Room 追加结构 | 1 | v8→v9 会话关联/调用配置两表、存取适配；schema 编译和离线迁移核对 |
| P2-A-4 幂等升级协调 | 2、3 | 源冻结→保存计划→Room 追加→ready 的可恢复流程；故障点定向用例，不接启动入口 |
| P2-A-5 v1/v2 备份兼容 | 2、3 | 格式、导入重映射/暂停、人物待确认后端；BackupTest/BackupRowsTest 及临时库事务检查 |
| P2-A-6 请求参数适配 | 1 | 小型思考方言适配、能力限制和请求对象核对；不接真实模型，不改 SSE/计费引擎 |
| P2-A-7 调用准备适配 | 4、5、6 | 认领前可用的纯校验/快照准备 API、旧价资格及 call_record 配置快照首写；准备器定向用例，正式入口仍未切换 |
| P2-A-8 统一选择与用途页 | 7 | 跨提供商选择、三项用途（含标题）、会话覆盖/来源；Preview 与对应交互，不做标题生成 |
| P2-A-9 提供商与模型页 | 8 | 按 Demo 接连接/模型分层、聊天思考设置、现有价格页显式 providerId；Preview/表单检查，不重做价格引擎 |
| P2-A-10 升级/恢复与启用验收 | 8、9 | 接自动升级/异常修复与恢复 UI，统一切入口；保护资料后只做 PLAN 列出的必要设备/请求检查 |

4～5 已有详细任务书；6～7 按已批准的自动识别/手动兜底方案完成。8～9 的详细任务见第 10、11 节；A-10 的详细任务与实际实现边界见第 13 节。每项按依赖顺序单独验证；不顺手改其他模块，确有依赖调整先在交付中说明。

## 2. 首批共用约束

- `core` 不引用 Android；不引入通用提供商框架、另一套记账/价格计算器或全新持久化仓库。
- 以实际 Room v8、DataStore 当前组配置和 SecretStore 为源。组 ID/名称/地址/keyId 原样保留；v1 源不删除或双写，v2 就绪后才成为唯一权威。Key 不进入文档、计划、备份、测试输出。
- 无覆盖才继承，失效/待确认是明确状态。不能从同名、目录顺序或价格键推导提供商；请求 ID 保留大小写。
- 聊天模型上的思考设置仅作用聊天；压缩独立 OFF，本轮不新增压缩思考 UI。不能关闭时返回明确不支持，不省略字段冒充关闭，不变更明确选择。未来压缩专用设置优先于其 OFF 默认。
- 所有新数据/迁移代码先仅用临时配置、合成资料或副本检查；1～3 不装机、不读写日常资料、不请求模型。上机转换前仍按 LESSONS 保留前后数据库一致副本及旧偏好并核对，不以单测代替。
- 交付只记录实际改动、实际命令与结果、未验证项。不要全量测试、强制 rerun、重复 P1 设备验收或另建常驻验证框架。每项在 HANDOFF 简短标明完成/下一项，详细失败原因留实际相关位置。

## 3. P2-A-1：数据契约与纯解析

**目标**：为后续存储和调用提供唯一契约，本次不改任何现有运行行为。

**文件范围**：新增 `core/src/main/kotlin/app/solochat/core/model/ModelConfiguration.kt`、`core/.../agent/ModelConfigResolver.kt` 及对应 `core/src/test/.../agent/ModelConfigResolverTest.kt`。类型可就近合并；不改 app、StoragePort、LlmClient、现有 ChatConfig/计费/提示词。

**实施内容**：

1. 定义 Provider/Model 稳定引用、带版本的配置集合、全局默认与 chat/compression/title 用途选择、会话指定/继承/待确认。复用已有枚举可行时不另造；标题偏好可保存但没有执行入口。
2. 纯函数解析聊天“会话→聊天→全局”、压缩“压缩→全局”；返回稳定模型/提供商、继承来源或明确失败。凭据可用性由 app 提供非秘密状态，不在 core 读密钥；选择解析成功不等于已验证网络可用。
3. 定义聊天 AUTO/ON/OFF+可选强度，以及压缩独立 OFF 的解析输入/结果。能力区分开关、不可关闭、支持档位、未知及来源；只校验意图能否表达，不在本任务输出厂商 JSON 或猜 token 预算。
4. 定义非秘密调用配置快照的字段契约，供任务 3 持久化：模型/提供商 ID 和显示名、请求 ID、用途/继承来源、思考意图与来源、实际控制参数；不存 Key、请求正文或完整连接对象。实际请求参数由任务 6 填入，价格仍由原 call_record 保存。

**验证与完成标准**：

- 新用例覆盖两条继承链、全局未设但显式用途有效、跨渠道同名、缺失/禁用引用不降级、未启用标题偏好不阻塞其他用途。
- 覆盖聊天 AUTO/ON/OFF、聊天 ON 下压缩仍 OFF、不能 OFF/未知能力的明确结果；旧设置迁移属于任务 2，不在此回填。
- 命令：`pwsh tools/build.ps1 :core:test --tests app.solochat.core.agent.ModelConfigResolverTest`（类名调整则同步实际命令）。只执行新类；已有 `:core:test` 不应因此被要求全量重跑。
- 本次结束时 App 仍完全使用旧解析；不得为了方便先把新解析挂进 runner。

## 4. P2-A-2：v2 配置存储与只读升级计划

**目标**：能保存/重读新配置，并从旧资料生成无损、可复用的升级草案；本次不执行 Room 关联写入或切换当前配置。

**文件范围**：`app/.../data/SettingsStore.kt`、`ApiProfile.kt`（只增加必要适配），新增相邻的 `ModelRegistryFormat.kt`、`ModelConfigUpgradePlan.kt`，对应 app JVM 测试；读取复用 SecretStore，**不改密钥存储和槽位清理算法**。不改 SoloChatApp 启动、数据库、调用器、备份和 Compose。

**实施内容**：

1. 使用现有 DataStore 承载一个版本化 v2 非秘密文档和升级准备记录，不另建 DataStore 实例；沿用旧 keyId。新格式成为未来单一连接/模型元数据源，旧 ApiProfile 只保留为迁移来源，不建立两套长期可编辑的提供商数据。
2. 全局/用途选择、稳定模型与兼容价资格可往返；保留每提供商完整旧 R4 价格数据、开关、非生效表、null 价格目录的旧语义。新模型/提供商没有旧兜底资格；此任务不重写计价计算器或价格编辑器。
3. 计划函数输入旧 Snapshot/ApiProfiles、会话旧覆盖投影和已准备的 ID 映射；输出候选 v2、会话关联草案、需确认项及原始源版本标记。不遍历历史 calls 来猜归属，不访问真实 Key。计划不改原对象。
4. 可靠对应沿原有效组和原请求名自动转换，裸手填名保留；缺有效组/失效引用保留待确认，不置空触发继承。不同品牌/同品牌旧组都一对一保留。
5. 旧思考空值→聊天 AUTO，OFF→OFF，其他已知档位仅保留原 ON 开关且不设新强度；不把 LOW/HIGH 凭空变成 reasoning_effort。只作用迁移时实际使用的默认/会话模型，不传播给其他旧组、新模型或压缩；未知值/能力无法对应则保留需处理原因。
6. ID 只创建一次并保存在准备记录；计划重用传入的映射，中断不能生成另一套 ID。源标记变化后旧草案拒绝发布；真正的冻结/续做/ready 顺序在任务 4 完成。当前读取/写入旧设置的运行路径不受影响，不自动触发准备。

**验证与完成标准**：

- 复用 SettingsStoreTest 的临时 DataStore，覆盖 v2 往返、版本/重复 ID 校验、源不变、旧 Key 槽位和全部价格原文保留、未知值不变零价、同名/手填/无有效组/旧思考转换、重用 ID 映射及过期草案。
- 错误文档/未知版本明确拒绝，不抹掉旧偏好或生成默认服务；检查用虚构 Key 标记不出现在格式化结果中。
- 命令按实际类：`pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.data.SettingsStoreTest --tests app.solochat.data.ModelConfigUpgradePlanTest`。新增独立格式测试时仅补该类，不全量运行；不改 SecretStore 时无需重复其完整验证。
- App 不发布 v2 ready、不迁移真实配置；返回一份最小字段示例与无法映射原因，供 Sol 核对后进入下一项。

## 5. P2-A-3：追加 Room 结构与存取

**目标**：给新会话选择与调用配置快照提供存储位置，不改既有历史行，不执行配置转换。

**文件范围**：`app/.../data/db/{Entities,Daos,SoloChatDatabase,Migrations}.kt`、新增同目录 `SchemaV9.kt`（沿现有风格）、`app/schemas/.../9.json`、`RoomStore.kt` 的必要存取。不接启动转换、配置解析、SessionController 或新 UI；尽量不扩现有 StoragePort/FakeStore，调用注入放任务 7。

**实施内容**：

1. Room 当前为 v8，新增 v8→v9 并注册；只 CREATE 会话模型关联和调用配置快照两表及必要索引。不能重建旧表、更新旧 modelOverride/人物/消息/事件/摘要/调用金额，不能使用 destructive fallback。
2. 会话表以 conversationId 唯一，保存选择状态、稳定模型引用或待确认来源，以及升级计划 ID；待确认不等于 null/继承。调用表以 callId 唯一保存版本化非秘密快照，历史调用没有此行即未记录。跨 DataStore 的 provider/model ID 不做虚假的 Room 外键；历史快照不随提供商删除而删除或重新解释。
3. 提供明确事务边界供任务 4 追加关联、任务 7 创建 call_record+配置快照；迁移重复写入必须同值才接受，冲突报错，不 REPLACE。进行中的调用后续状态/用量更新不得重写开始时配置。这里提供存取能力，不增加第二个记账调用源。
4. 对话删除时清理本对话关联，调用快照按既有历史调用保留规则处理；新配置表不作为删除人物/价格的联动入口。

**验证与完成标准**：

- `pwsh tools/build.ps1 :app:assembleDebug` 检查 Room 编译及 v9 schema；不装这个中间包。
- 参考现有 `tools/verify-migration-v6.py` 的离线 SQLite 方法，在 `.tmp/` 临时 v8 数据副本执行**实际新增 SQL**：核对旧表/列/主键/逐行内容不变、新表/索引正确，再检查关联存取的重复写入、冲突及事务失败回滚。Room 版本迁移按版本执行一次，不要求重复执行建表 SQL。不要复制旧 v6 假设，不添加大型框架；必要的临时核对不当成 instrumentation 验收。
- 未获实际 Android Room 打开证据时明确写未验证，统一留给任务 10 的一次必要升级检查；不把 JVM 或离线 SQLite 当真机 Room 通过。
- 交付 v9 schema 差异、实际验证范围和迁移 SQL 摘要；本任务没有任何真实资料迁移授权。

## 6. P2-A-4：幂等升级协调

**目标**：将 A-2 的只读升级计划安全地追加到 A-3 的 Room 表，并以 DataStore 单次发布作为唯一 ready 点；实现可中断、可重试的协调能力，不接入启动或当前配置读取路径。

**依赖**：A-2 的 `ModelRegistryDocument`、源版本计算、准备记录与计划生成；A-3 的 v9 表和 `ModelConfigDao` 同值重试/冲突拒绝能力。现有应用级配置锁用于串行化旧配置写入和升级；不得另建长期锁体系或改配置写入入口。

**文件范围**：`SettingsStore.kt`、`ModelRegistryFormat.kt`、`ModelConfigUpgradePlan.kt`（准备记录需能持久化完整冻结计划及阶段）、`data/db/Daos.kt`、必要的 `RoomStore.kt`/现有 v9 DAO；新增相邻的 `ModelConfigUpgradeCoordinator.kt` 及对应 app JVM 定向测试。仅为共享现有配置锁所必需时，最小调整 `SoloChatApp.kt` 的锁可见性，不挂启动调用。不得改运行配置解析、请求/调用链、UI、SecretStore 或迁移真实配置。

**实施内容**：

1. 定义持久化状态 `NONE → FROZEN → ROOM_APPENDED → READY`。`FROZEN` 必须在 Room 写入前保存完整且不可变的候选文档、会话关联草案、待确认项、源版本、稳定 `planId`/模型 ID 映射；A-2 当前准备记录只有源哈希与 ID 映射，需在原记录上做最小向后兼容扩展。未知/损坏阶段或版本明确失败，不能重置准备记录并生成新 ID。
2. 协调器在现有配置锁内读取旧 DataStore 源与 Room 会话覆盖，生成并冻结同一计划；执行顺序固定为：核对源版本并持久化 `FROZEN` → 单个 Room 事务写入全部会话关联 → 将阶段记为 `ROOM_APPENDED` → 在一次 DataStore `edit` 中写入 v2 文档和 `READY`。只有最后一步成功才报告 ready；待确认项可随完整计划发布，但不能伪装成继承或让无关用途失败。
3. 同一 `planId` 重试时复用冻结计划和所有 ID。Room 中“缺失则插入、完全相同则接受、字段冲突则失败”；不得 REPLACE、重建计划或部分发布。Room 事务中任一写失败整体回滚。ready 后重复调用只验证已发布文档/阶段一致，不重写历史行。
4. 启动恢复入口由后续 A-10 调用。A-4 只提供显式协调/恢复 API：`FROZEN` 可重放 Room 事务，`ROOM_APPENDED` 可验证关联后继续发布，`READY` 幂等返回。每次继续前重新核对源版本；若源变更、同一 ID 内容冲突或持久化失败，保留源及既有数据、阻止 READY，并返回可诊断错误，不静默另建计划。
5. 用最小可注入检查点覆盖冻结落盘后、Room 事务提交前/后、READY 的 DataStore edit 内。检查点只供定向测试注入异常，不新增通用故障框架。DataStore 文档与 READY 阶段必须由同一 `edit` 原子提交；失败后读取状态应保持旧值或可按上一步恢复。

**验证方法**：使用临时 DataStore、合成旧配置/会话和临时 v9 数据库；只运行新增协调器/准备记录定向类及受影响的 `ModelConfigUpgradePlanTest`。逐点注入失败后重试同一计划，核对 ID 不变、Room 无部分行、同值重复不增行、冲突/源变更不发布、待确认状态准确保留、READY 与文档同成同败。核对凭据槽位和全量旧价格仍逐字段相同且任何 Key 不进入准备记录、错误或测试输出。不得执行真实用户配置转换；Android Room 实际打开/旧库升级留给 A-10。

**完成标准**：状态迁移及恢复行为、跨存储提交顺序和冲突规则有定向测试；异常时不丢源、不重复建 ID、不发布不完整文档；没有启动接入或运行行为变化。报告状态机、注入点、实际测试命令/结果与未覆盖的 Room 运行时边界。

**已知依赖缺口**：A-2 准备记录目前不保存完整计划，不能单独支撑进程重启后恢复；这是 A-4 的必要兼容扩展，不得以“恢复时重算”替代冻结计划。若既有配置锁无法被显式协调 API 共享，应先报告并确认最小锁接入点，不自行绕开锁或新增第二套锁。

## 7. P2-A-5：v1/v2 备份兼容与副本导入

**目标**：在现有 ZIP 备份链路上增加 v2 模型关联/调用快照能力，继续严格读取 v1；导入仍只创建隔离、暂停的副本，数据库写入与导入来源标记在一个 Room 事务中完成。不把导入配置接入当前请求链路。

**依赖**：A-2 的版本化配置/稳定引用语义；A-3 的会话绑定及调用快照表、Room 事务与无覆盖插入规则。现有 `BackupData`、纯导入计划和 Room 备份读写适配是直接修改对象。

**文件范围**：`core/.../backup/{BackupData,BackupCodec,BackupImportPlan}.kt` 及 `core/.../backup/BackupTest.kt`；`app/.../data/backup/{BackupRows,RoomBackupReader,RoomBackupImporter}.kt`、`app/.../data/db/{BackupDao,Daos}.kt` 和 `BackupRowsTest`。只有为现有导入入口传递 v2 字段确有必要时，最小调整 `ui/backup/BackupViewModel.kt`；不新增模型/人物选择界面，不改聊天、会话选择器、SecretStore 或 A-6～10 文件。

**实施内容**：

1. 协议固定为 v1 只读、v2 读写：清单版本校验明确分派；v1 的正文结构和 counts 键保持原样，不能因 `BackupData` 增加字段而改变旧 v1 输出/解释，且 v1 正文不得携带 v2 新键；v2 新增可选 `conversationModelBindings` 与 `callConfigSnapshots`。保留 ZIP 条目、大小上限、校验和、排除清单及现有字段语义。v1/v2 都不包含接口配置、地址、Key、Key 槽位、用途默认值或本机偏好；不得由备份恢复凭据或提供商服务。
2. 读取先完成格式/关系/版本校验，再生成导入计划。所有会话、人物、记忆、摘要、分段、调用、操作号及其现有关联继续按当前规则重映射；新增绑定只重映射会话副本 ID，快照只重映射 `callId`，原 provider/model/request/思考字段仅作历史来源，不按目标机同名 ID 解析、不注册模型或获得旧价资格。调用记录中的冻结价格、Token、usage 及其关联保持原值，不二次写入消费账。
3. 导入后的模型选择统一写为显式待确认，包括 v1 裸 `modelOverride`、无覆盖及 v2 原来显式/继承/待确认状态；保留足够的非秘密来源说明供后续修复页展示，不能把原机“继承”直接当成本机已确认。所有副本沿现有 `backup_origin.paused` 机制保持暂停，A-5 不提供解除暂停入口；A-10 完成用户选本机已配置模型或本机默认继承的修复流程。
4. 人物卡只导入副本，合法源人物 ID 映射到对应副本，不与本机合并。找不到源人物（含无人物卡的旧包）时不得自动创建“对方”或绑定本机人物；保留不可误解析的待选归属及暂停状态，供后续明确选择。同步移除 `BackupImportPlan` 当前的 fallback persona 创建行为，并更新依赖该行为的旧用例；不在 A-5 增加人物选择 UI。
5. 保留历史数据保护：导出继续覆盖归档会话、完整消息/事件、旧 usage、call_record、历史冻结价格、摘要及孤立的已删除来源账目；调用快照若对应历史调用不存在则拒绝导出，不补造快照。既有历史调用没有快照时仍表示“未记录”。ZIP 读取/校验在事务外完成；按现有实现，在 Room 事务内读取目标最大 ID、生成重映射计划并插入全部副本、v2 新表行、`backup_import` 与暂停/来源标记。任一唯一键/外键/新表冲突整体回滚，禁止 REPLACE、覆盖或先部分写入后报成功。
6. `BackupViewModel` 继续走现有用户主动备份入口，只作必要的 v2 数据传递/结果摘要适配；不得在 A-5 读取本机 v2 配置来自动配对，不新增运行时双配置源。A-10 才验收跨安装完整恢复、待确认修复 UI、用户解除暂停以及升级/备份/请求链路合并后的实际运行。

**验证方法**：运行 `BackupTest`、`BackupRowsTest` 定向类；用既有 v1 ZIP 样本/固定旧结构验证可读且字段、counts 与历史价格逐项不变；生成 v2 包验证新字段校验、往返、坏版本/悬空关系拒绝及不含秘密。合成资料覆盖两版本重映射、无/缺失人物不造卡、各选择状态均待确认、历史 usage/call/快照保持与副本 ID 关联。用临时数据库检查 Room 导入事务：在新增表/来源标记写入阶段注入失败，确认所有业务表、新表及导入标记均无残留；重复身份冲突不得覆盖原行。沿用可用的临时库验证方式；Android Room 实际打开、v8→v9 设备升级和真实跨安装恢复统一留 A-10，不以纯 JVM/离线 SQL 宣称通过。

**完成标准**：v1 兼容、v2 新字段、导入重映射/待确认/暂停、既有历史账目保护及事务全回滚均有定向证据；旧“对方”自动兜底路径和相应测试已移除；当前聊天配置与请求行为未改变。交付说明实际 ZIP 版本兼容范围、测试命令/结果、临时库验证边界和待 A-10 验收项。

## 8. P2-A-6：请求参数适配

**目标**：为 A-7 提供纯、可测试的聊天思考参数解析，并让现有 OpenAI-compatible 请求对象能够表达少量已确认方言；AUTO/旧请求保持原样。本项不探测接口，不接设置 UI 或正式会话入口。

**依赖**：A-1 的 `ModelDefinition`、`ThinkingCapability`、意图与快照；A-2 的 `RegistryModel` 存储兼容；现有 `LlmRequest` 和 `OpenAiCompatClient`。A-1 契约没有方言字段，允许在模型定义增加默认空的可选字段；能力继续复用 `ThinkingCapability` 与 `CapabilitySource.USER_CONFIRMED`，不另造能力体系。

**文件范围**：`core/.../model/ModelConfiguration.kt`、`core/.../llm/LlmClient.kt`，新增纯 Kotlin `core/.../agent/ThinkingRequestAdapter.kt` 与测试；`app/.../data/ModelRegistryFormat.kt`、`app/.../data/llm/OpenAiCompatClient.kt` 及其现有/新增定向测试。不得改启动配置发布、模型目录 UI、SSE、价格计算或真实请求链路调用点。

**实施内容**：

1. 在模型定义和 v2 RegistryModel 增加可选 `thinkingDialectOverride`（缺省为 null）；旧 v2 JSON 解码后语义不变。用户手动声明以 `USER_CONFIRMED` 的开关/档位能力和方言覆盖表达。未知枚举/非法覆盖拒绝，不忽略。
2. 新适配器输入 provider 的协议/官方 endpoint、精确 `requestModelId`、模型能力来源及思考意图；输出 AUTO 空参数、一个具体方言请求，或明确不支持原因。手动确认覆盖自动映射；自动映射必须同时命中精确官方 endpoint 与小型精确模型表，不依据显示名、同名模型、任意兼容代理或正则前缀猜测。未知仍允许 AUTO；ON/OFF 及显式 effort 只有能力来源和对应方言确认后才可生成参数。
3. 首批只实现 PLAN 已列官方 OpenAI-compatible 方言：DeepSeek `thinking.type`/`reasoning_effort` 与百炼 `enable_thinking`。自动声明只覆盖源码中的精确 endpoint 和型号白名单（DeepSeek 官方 `deepseek-flash`、`deepseek-v4-pro`；百炼官方当前文档列出的少量 Qwen3.8/3.7 hybrid/only 型号与 DeepSeek V4.1 Flash），不能扩大为名称前缀规则。依据为 PLAN 已核对的 [DeepSeek 思考模式文档](https://api-docs.deepseek.com/guides/thinking_mode/) 与 [百炼思考模型文档](https://www.alibabacloud.com/help/en/model-studio/deep-thinking)。档位只接受能力明确列出的值，不把强度换算成 token 预算，不同时生成冲突方言。手动方言复用上述有限枚举，不实现任意 JSON 字段插件。
4. `LlmRequest` 增加可选的类型化思考参数对象；旧 `thinkingEffort` 调用仍走现有请求生成语义。`OpenAiCompatClient` 按类型化方言生成对应 JSON；AUTO 不增加字段。适配器输出的实际非秘密参数可以直接写入 A-1 的 `actualThinkingParameters`。
5. 这里仅交付数据/纯适配/请求体构造能力。目录查询当前只返回模型字符串，不能把模型名列表当能力声明；不新增联网探测/收费请求，也不擅自改目录协议。任何在线能力发现和正式配置识别留后续完善。

**验证方法**：运行新增 `ThinkingRequestAdapterTest`、现有 `DashScopeCompatibilityTest` 与 `SettingsStoreTest`；覆盖官方 endpoint+精确模型命中、第三方同名不命中、未知 AUTO、未知/未确认 ON/OFF 明确失败、手动覆盖、模型档位限制、各方言 JSON、旧 LlmRequest body 兼容、旧 v2 缺字段读取及显式覆盖往返。仅用合成 provider/model，不发网络请求。

**完成标准**：自动规则有官方来源且仅按官方身份与具体模型命中；手动方言和能力可由非 UI 契约表示；AUTO 不变，显式意图不会被静默丢弃；旧格式/旧请求兼容。不接入当前运行入口，不验证真实厂商响应。

## 9. P2-A-7：调用准备、配置快照与记账适配

**目标**：提供 A-10 可调用的纯调用准备能力，使聊天/压缩按各自用途先解析并校验，再生成不可变请求/调用快照，并沿现有价格和 call_record 机制记录；当前旧运行入口保持未切换。

**依赖**：A-4 ready 文档及 A-5 导入待确认语义（不得消费未 ready 或暂停副本）；A-6 适配器与 A-1 快照；A-3 v9 `CallConfigSnapshotEntity`/DAO 原子写入口；现有 `ModelConfigResolver`、`ChatConfig`、`PriceResolver`、`CallRecord` 和会话锁语义。

**文件范围**：新增 `app/.../data/ModelAwareCallPreparation.kt` 与定向测试（它需读取现有 `RegistryProvider.legacyPricing` 并复用 app 的 `PriceResolver`）；`app/.../data/db/CallConfigSnapshotMapping.kt`、`RoomStore.kt` 及映射测试，通过已有 `ModelConfigDao.insertCallWithConfigIfAbsentOrSame` 暴露首写 call_record+快照事务入口。仅为测试覆盖调用前校验需要时，最小调整 `SessionControllerTest`/`CompressionServiceTest`。不改 SoloChatApp 配置源、不注入正式入口、不改 UI、SecretStore、历史 call_record/usage/价格行或计价算法。

**实施内容**：

1. 准备函数只接受一份已捕获的配置/解析结果、用途、会话选择来源、凭据可用状态、思考意图和调用时间；返回带同一 provider/model/request ID、endpoint/key、价格结果与 `CallConfigSnapshot` 的不可变 `PreparedModelCall`，或明确错误。Key 按选中 `provider.keyId` 槽位取得，不以 provider ID/名称猜槽位。不得在 runner 重读默认值/地址/Key/价格，也不得在调用对象日志中输出 endpoint/key。
2. 新入口调用顺序固定为“解析及凭据/能力/暂停/价格资格校验 → 请求认领 → 使用同一 PreparedModelCall 发起请求”；准备器自身纯函数式、无认领/记账/网络副作用。提供 `claimAfterPreflight` 小型顺序 API：拒绝时不执行认领回调，成功时将同一 prepared call 与认领结果一起返回。当前 SessionController 只有旧配置路径，不能在保持其运行行为和未启用 v2 的前提下接入新配置；A-7 不注入正式入口。真正的 SessionController 会话锁内顺序验收留 A-10。
3. 聊天使用模型上的 AUTO/ON/OFF；压缩调用每次明确独立 OFF，不读取聊天偏好。压缩 OFF 不被支持时应在修改原文/摘要激活状态前失败，不省略字段、不偷偷重试开启思考。A-7 不增加压缩思考设置。
4. 使用 A-2 的 `legacyPriceEligible` 守卫旧 R4 fallback：仅迁入且与原配置相同的模型可复用兼容价，新模型不套旧提供商兜底；显式新价格仍沿现有 `PriceResolver` 输入。价格只在准备时解析一次，后续只传冻结结果。旧价格、历史 usage/call_record 不重算、不回填。
5. 首次记账通过 A-3 DAO 事务同写 call_record 与非秘密配置快照；重试只接受同一 callId 的完全同值内容，冲突失败。收尾沿旧 `recordCall` 更新用量/状态，不重写开始时快照；旧历史调用没有快照仍表示未记录。压缩也必须走同一准备对象和相同快照规则。
6. 仅提供能力和隔离数据测试；不让正式启动读取 v2、不运行 A-4 转换、不自动解除 A-5 暂停。真正的 Room v8→v9 Android 打开、统一入口切换、真实在途修改、跨安装恢复和 UI 修复验证留 P2-A-10。

**验证方法**：运行 `ModelAwareCallPreparationTest`、`CallConfigSnapshotMappingTest`；证明未知 AUTO 可用、显式未确认意图被拒绝且认领回调未执行、成功时同一 prepared call 随认领结果返回、聊天思考不污染压缩 OFF、旧价资格边界、快照字段与请求参数一致、暂停/待确认/缺凭据拒绝。按 A-3 的临时 v9 SQLite 方法核对 RoomStore 所用 call_record+快照表首写原子性、同值重试和冲突拒绝；不以此宣称 Room 运行时通过。不得写入真实资料或发厂商请求。

**完成标准**：准备器在认领前给出明确可执行/拒绝结果；请求与非秘密快照来自同一不可变解析；聊天/压缩策略隔离；旧价规则及历史账目保护有定向证据；当前 SoloChatApp/正式配置入口仍使用旧行为。报告 A-10 接入前仍未在真实调用链验证的边界。

## 10. P2-A-8：统一模型选择与用途页

**目标**：按仓库独立 Demo 实现一个跨提供商选择器和模型用途页，覆盖全局默认、聊天、压缩、标题及会话覆盖示例；用途/来源清楚，选择由稳定 ID 表示。标题只保存草稿、不发起调用。视觉按 Android Compose 自然适配，不逐像素复制网页。

**依赖**：A-1 `ModelSelection`/`ModelConfiguration`/`ModelConfigResolver`；A-3 会话绑定契约；A-5 的待确认状态；A-7 调用解析契约；现有设置页状态路由。唯一视觉基准为仓库 [Demo v2](solochat-provider-model-design-v2.html)，不再依赖 Codex 临时片段。按用户确认，A-8/9 保存边界为内存草稿，因此本任务不读取/写入 Room；真实会话绑定编辑留 A-10。

**Compose 对应关系与文件范围**：修改 `ui/settings/SettingsHub.kt`、`SettingsScreen.kt` 增加两项入口和子页路由；新增 `ModelUseSettingsScreen.kt`、`P2ModelSettingsDraft.kt`、`P2SettingsDraftNotice.kt` 及对应定向测试；复用 `ui/SettingsComponents.kt` 的 `SettingsGroup`、`ToolTopBar` 与 Material 3 列表/搜索/选择弹层。会话仅用设置页内示例展示来源解析，不编辑真实会话。不得在 `ConversationSettingsScreen`、`SoloChatApp`、`SessionController`、`ChatScreen` 中接入新配置或改变当前请求行为；不实现标题生成、恢复流程或压缩思考控件。

**实施内容**：

1. 用稳定 provider/model ID 表示选择；统一选择器的行显示提供商、显示名及必要的真实 requestModelId，跨 provider 搜索，支持明确继承/选择，禁用/缺失项显示原因并不可确认。不能按同名、旧 UUID 或列表首项自动替换。
2. 用途页分别编辑全局默认、聊天、压缩、标题；后三项可明确继承全局，展示“继承全局 · 提供商/模型”或显式来源。标题允许保存但不得触发调用；压缩用途选择与聊天思考设置完全分离。
3. 会话页按“会话覆盖 → 聊天用途 → 全局默认”显示实际来源；选择器与用途页复用同一组件。继承与显式选择必须能区分；待确认/暂停备份/失效引用不可解释为继承，也不可用于放行发送。
4. 交互使用页内可丢弃草稿：切换 A-8/A-9 子页保留本次草稿，明确“本地草稿，不影响调用”，保存仅确认当前页内草稿，离开设置或进程重建后丢弃。不得调用 DataStore、Room、SecretStore，不运行旧配置转换，不发布 v2 READY/迁移计划，不更改 runtimeConfig；A-10 再把交互绑定到 ready v2/Room。

**验证方法**：定向测试覆盖跨 provider 同名模型与稳定引用、全局/用途/会话示例草稿隔离、内存“保存”不改变原文档，以及 provider/model 编辑保持槽位、价格资格和精确请求 ID。运行 `pwsh tools/build.ps1 :app:testDebugUnitTest --tests app.solochat.ui.settings.P2ModelSettingsDraftTest` 与 `pwsh tools/build.ps1 :app:assembleDebug`。缺失/禁用/暂停真实选择和真实会话持久化属于 A-10；本阶段不宣称验证。Compose Preview、真机明暗主题/窄宽度、密度、光学对齐、触控与文案留用户验收。

**留待 A-10**：Room v8→v9 Android 实际打开、真实会话设置保存、旧配置转换、调用入口解析/发送门禁和备份恢复端到端。A-8 不能自行打开这些链路。

**完成标准**：用途页和跨提供商选择器使用原生控件与稳定 ID；会话示例只展示草稿解析来源；定向逻辑验证及 Compose 编译通过。保存反馈明确仅作用于设置页内存草稿，没有写入用户数据或影响正式请求。静态代码编译已核实；视觉与交互由用户实际 Android 验收。

## 11. P2-A-9：提供商与模型页

**目标**：按 Demo 实现提供商“连接/模型”分层及模型编辑；显示模型来源、凭据状态和聊天思考能力，支持少量已批准方言的手动兜底；复用现有 R4 价格编辑入口，不重做价格引擎。

**依赖**：A-8 的统一路由与共享草稿；A-2 的 `ModelRegistryDocument` 与 `LegacyProviderPricing`；A-6 的 `ThinkingCapability`/`thinkingDialectOverride` 及 `ThinkingRequestAdapter`；现有 `ApiProfileTemplate`、凭据槽位元数据、Compose 表单、`ProviderModelList.kt` 和价格页入口。Demo 仓库副本为唯一视觉/交互基准。本任务不读写 Key、不发网络请求，也不接入模型目录客户端。

**Compose 对应关系与文件范围**：新增 `ProviderModelSettingsScreen.kt`，复用 `SettingsScreen.kt` 父级内存草稿、`SettingsComponents.kt` 与 Material 3 `TabRow`/列表/对话框/文本字段；修改 `SettingsScreen.kt`、`strings.xml` 并复用 A-8 的 `P2ModelSettingsDraft`。价格按钮仅可跳转现有当前接口组价格页。不得修改 `ModelPriceEditorScreen.kt`、`SettingsStore.kt`、`SoloChatApp.kt`、SecretStore、目录网络协议或调用入口；不建立第二份持久化 provider/model 库。

**实施内容**：

1. 提供商列表沿用现有模板概念（仅预填 endpoint），不合并同品牌旧组、不自动设默认；详情按“连接 / 模型”呈现。连接编辑只改内存草稿；Key 不回显、不进入 Bundle、日志或草稿。槽位元数据和旧价格字段由原对象复制保留，本任务不执行密钥替换。
2. 模型列表沿用每 provider 一个草稿列表；手动添加和编辑只改内存草稿。编辑含显示名、精确 requestModelId、启用态及模型级“聊天思考模式”AUTO/ON/OFF/强度；显式 ON/OFF/effort 只在 `ThinkingRequestAdapter` 判定可表达时允许确认，未知能力下 AUTO 可用。
3. 手动能力覆盖以 `CapabilitySource.USER_CONFIRMED` 保存，方言只允许 A-6 的有限枚举；编辑器解释“手动设置”与已确认/未知状态，默认不覆盖可靠自动声明。压缩固定 OFF，不出现压缩思考面板；标题不增加生成逻辑。
4. 模型价格入口保留在当前提供商页；当前活动接口组可进入现有价格页，其他草稿提供商显示提示。旧兼容价资格原样保留，新模型默认不得获得 `legacyPriceEligible`。不改格式、不改 R4、不更新任何历史费用。
5. 本阶段所有更改只更新 A-8/9 共享的页内 v2 草稿；“保存”只确认草稿、不写任何存储，Key 字段仅显示非秘密占位，不接收真实凭据。返回可丢弃提示和保存反馈要清楚区分“本地草稿”与“已持久化”。`SettingsStore.saveModelRegistryV2` 的 READY/FROZEN 限制保持不变；A-10 后续必须增加仅 READY 且复用 `configMutex` 的用户编辑写入口，不能放宽迁移写入守卫或旧偏好双写。

**验证方法**：运行 `P2ModelSettingsDraftTest` 覆盖 provider/model 草稿、原配置不可变、Key 槽位/旧价资格及精确 requestModelId 保留；运行 `ThinkingRequestAdapterTest` 核对显式思考能力/方言拒绝规则（A-6 已有定向结果）；运行 `pwsh tools/build.ps1 :app:assembleDebug` 检查 Compose 集成。未做 Key 替换、目录网络合并或非当前 provider 价格编辑验证，因为本阶段没有这些写入能力。Preview 及 Android 验收按 A-8 的密度、光学对齐、按钮紧凑、双主题/窄宽度标准由用户进行；不发送真实模型请求。

**留待 A-10**：升级/READY 前后切换、统一 runtimeConfig/SessionController/CompressionService 读取 v2、真实在途快照、旧配置转换、v8→v9 Room 运行时、跨安装恢复及用户历史资料验收。本任务不迁移真实配置、不开放未 ready 文档供请求执行。

**完成标准**：提供商与模型界面使用现有 Compose 体系和 v2 契约，凭据槽位及价格/历史兼容不变；思考设置仅能表达经确认能力；本阶段交互/保存仅作用于 A-8/9 页内草稿，完成定向用例和编译验证。真实持久化与 A-10 运行链路接入留后续。

## 12. 后续接入必须保持的边界

任务 4 的 ready 是最后发布点，持有既有应用级配置锁并阻止新请求；先冻结/保存完整源计划，再 Room 单事务追加，最后 DataStore 一次发布。中断续做同一计划；源冲突/存储失败阻止发布。歧义可以作为明确待确认发布，但仅影响其请求，不伪装成存储成功或继承。

任务 5 从格式和导入计划到 Room 事务一并更新：v1 保持严格读取，v2 新字段受校验；有效人物 ID 对应副本，缺失人物保留待选，移除自动创建“对方”的旧路径。只导入副本，模型/Key 不从本机同名猜配，暂停且可只读；所有调用历史价格/Token 来源保持，导入费用不重复计入实际新消费。调用配置快照仅重映射 callId/会话副本，原提供商/模型和思考参数只作历史来源，不注册服务、不套给目标模型或赋予旧兜底资格。

任务 6～7 只在一处解析请求：会话锁内先校验、后认领待发，把相同地址/Key/模型/用途思考/价格版本快照交给执行器；变更/删除配置不能影响在途请求。压缩独立 OFF；不支持时原文及已启用摘要保持不动，不偷偷开思考重试。旧兼容价资格在装配时校验，新模型价格未知不自动套用。

任务 8～10 按 Demo 接 UI，控件名明确“聊天思考模式”；不新增压缩思考面板、标题生成逻辑或 P2-B 价格重构。启用前对本次需动资料保留前后两份一致库/旧偏好并核对既有行不变；只用专用测试人物/会话/提供商及恢复副本，不替换日常配置做破坏性演练。设备不可用时保留未验收状态，不据离线结果宣布整个 P2-A 完成。

## 13. P2-A-10：配置升级、恢复修复与统一启用

**目标**：在启动门禁下将旧偏好可靠升级为唯一运行用的 v2 配置；把 A-7 同一份调用准备结果接入聊天与压缩，并为迁移歧义、会话绑定和导入副本提供明确修复入口。A-8/9 草稿改为 READY 后持久化保存。正式入口不保留第二套长期配置引擎。

**依赖与代码现状**：A-1～9；DataStore 已有升级阶段记录与 `ModelConfigUpgradeCoordinator`，Room 已有 v9 会话绑定/调用快照及备份导入事务；`ModelAwareCallPreparation` 仍未接入 `SessionController`、`AgentTurnRunner`、`CompressionService`。当前 `flushTurn` 先认领消息再检查暂停；应用无升级启动门禁，`runtimeConfig` 仍来自旧 `Snapshot`。设置页仍可进旧 API 页，价格页只保存当前接口组；备份页没有绑定修复/解除暂停流程。当前会话 `modelOverride` 写 Room 不持配置锁，故升级协调器必须先于会话/发送入口启用，不能在可编辑后才运行。

**文件范围**：启动与配置：`SoloChatApp.kt`、`MainActivity.kt` 及创建聊天/后台任务的最小入口、`SettingsStore.kt`、`ModelConfigUpgradeCoordinator.kt`、`ModelAwareCallPreparation.kt`、`SecretStore.kt`。调用：`core/.../agent/AgentDeps.kt`、`AgentTurnRunner.kt`、`CompressionService.kt`、必要的 `AgentTurn`/存储端口、`SessionController.kt`、`RoomStore.kt`、`CallConfigSnapshotMapping.kt`、`OpenAiCompatClient.kt`。设置：`SettingsScreen.kt`、`SettingsHub.kt`、`P2ModelSettingsDraft.kt`、`ProviderModelSettingsScreen.kt`、`ModelUseSettingsScreen.kt`、`ModelPriceEditorScreen.kt` 与必要 ViewModel、`SettingsStore.kt`、`SecretStore.kt`、`strings.xml`。修复/恢复：绑定 DAO/实体/RoomStore、`ConversationSettingsScreen.kt` 及必要导航、`BackupViewModel.kt`、`BackupScreen.kt`、`RoomBackupImporter.kt`。仅为本任务添加对应单元/Room instrumentation 测试和 Room 测试依赖；更新 `docs/ARCHITECTURE.md`、本任务书与 `docs/HANDOFF.md`。不得改 P2-B 价格格式/算法或无关设置组。

**实施顺序**：

1. **启动门禁与升级**：建立一个可等待的应用配置初始化结果；任何聊天、压缩、模型调用和设置编辑都须等待成功。复用 `configMutex`，协调器在首次可写会话绑定/发送前执行；旧数据仍以 A-2 快照构造冻结计划，Room 先事务追加、DataStore 最后一次 edit 发布文档+READY。READY 后只读 v2；FROZEN/ROOM_APPENDED 可重入同计划。故障时保留旧偏好、计划及已追加的不可变关联，显示可重试/待处理原因，不回退旧调用，不删除旧源键或 Key 槽位。
2. **READY 写入与凭据**：新增独立于迁移发布的用户保存 API，只允许 READY；在同一 `configMutex` 内对文档校验、持久化编辑和发布内存快照。新 provider 的 Key 替换使用现有 `SecretStore` 的 `keyId` 槽位，不读回/记录明文；删除 provider 前清除引用/选择，只有确认无引用后才清理槽位。旧 API 页的 endpoint、model、目录、Key 替换/清除、温度、最大 token、超时及活动组切换须由“提供商与模型”或已有聊天行为页承接；仅在替代能力覆盖后移除旧入口/快速切换，不能丢失全局聊天行为设置。价格沿原 R4 编辑器按 providerId 读写，不切换全局活动组。
3. **统一聊天调用**：在 `SessionController` 的会话串行区内，先检查初始化、人物、暂停/待确认和模型/凭据/思考/价格，再认领待发消息；拒绝不得认领或写成已发送。把同一 `PreparedModelCall` 传给执行器，使用其 endpoint、Key、请求模型、思考参数、冻结价格及快照，不二次解析运行配置。首写 call_record+快照使用 A-3 Room 事务；请求完成仅更新旧调用记录与 usage。v2 文档 READY 后不再读旧模型/地址/Key 选择；旧价仅按迁入资格；删除无调用者后收敛旧 API profile 运行读写，保留仅为格式升级所需的兼容读取。
4. **压缩调用**：每个摘要请求在同一会话锁内解析 COMPRESSION 并冻结 `PreparedModelCall`；思考强制 OFF 且不支持 OFF 时，须在创建/激活摘要前拒绝。预览展示同一已解析模型及价格；生成期间不得因改配置重解析，摘要调用记录关联配置快照。取消/失败保留原文及原启用摘要；历史费用与旧摘要不回写。
5. **迁移/恢复修复与设置 UI**：升级待确认项显示原因并要求显式模型选择；会话绑定持久化到 v9，失效/禁用/凭据缺失分别呈现且阻止请求。备份导入继续保持副本暂停；提供副本列表及修复：仅显式选择有效人物与本机模型后写绑定，完整校验再解除暂停；历史 call/snapshot 仅作历史来源，恢复过程不注册 provider、不更改旧行/摘要/费用。设置 IA 按用户决定收敛为 Chat（模型选择、回复节奏、聊天行为）和服务配置（提供商与模型、模型价格）；删除旧入口前复核功能承接。A-8/9 草稿增加 READY 后保存、取消/返回丢弃、存储失败保留草稿并反馈。
6. **验证与文档**：先跑各受影响的定向 JVM 用例，再增加最小 Android Room instrumentation 覆盖导出 v8 库→Room v9 实际打开、旧行逐值保留、绑定和 call snapshot 写事务/回滚；使用可恢复的一致测试副本，禁止真实库破坏性故障注入。隔离测试还需覆盖 DataStore/Room 中断点恢复、导入 v1/v2 ID 重映射与事务回滚、会话预检失败不认领、配置变更后在途请求仍用旧快照、压缩 OFF 拒绝不改摘要、READY 持久化保存/取消/失败及旧入口功能迁移。仅最后的设备验收才可操作受保护副本；真实用户资料升级、不可逆清理、发布留独立复核与授权。

**明确不接入 / 留待 P2-A-10 最后集成验收（P2-A-10 实现阶段之前不可提前做）**：不做 P2-B 价格重构、网络能力探测、真实厂商请求、历史价格/调用回填或用户资料自动修复；不引入第二套长期配置引擎。合成 SQLite/JVM 用例不等同 Room Android runtime。当前任务中必须完成隔离副本的 Room v8→v9 runtime 验证；受保护正式资料的覆盖安装、升级/清理及发布仍需最终独立复核和明确授权。

**验证方法与完成标准**：用本地 DataStore 与隔离 Room 测试库注入协调器各阶段失败并重启重试，证明同 plan 幂等、源冲突拒绝、未 READY 不可调用；在 Room Android 测试中使用受保护 v8 副本启动 v9，逐行比较全部旧业务表并验证 `integrity_check`。覆盖跨 provider/会话稳定绑定、模型禁用/待确认/缺 Key 不认领、聊天显式思考拒绝与 AUTO、压缩独立 OFF、调用快照/旧 R4 资格、v1/v2 备份恢复及回滚、缺人物/模型修复后再解除暂停、设置保存/取消/故障和旧入口能力承接。Debug 构建并在 PKM110 或隔离模拟器检查亮/暗主题、窄屏、主导航/设置/修复交互；视觉状态由实际截图/操作报告。全部受影响定向测试通过、Room runtime 有真实结果、运行入口 READY-only、文档/HANDOFF 更新且未用真实用户资料做破坏性操作，才算技术交付完成；设备/凭据/工具限制造成的欠项须显式保留，不能宣称整个 P2-A 验收通过。

## 14. 最终独立审查定向修复记录（2026-10-25）

按 [最终复核报告](p2-a-final-review.md) 修复 R1～R10，不重写 P2-A，也不改变已批准产品规则。逐项代码路径、回归证据和遗留边界集中记录在 [HANDOFF](../HANDOFF.md) 的“最终审查 R1～R10 定向修复”节。

READY 后当前 v2 是配置权威；凭据替换使用新槽位后再发布引用，不覆盖或清理旧槽位；明确模型报价不受旧价兼容资格屏蔽，未知报价不套随包同名价；价格保存合并回未保存模型草稿；测试网关先做 v2 CHAT 预检；压缩冻结连接、思考与价格规则版本，各段按真实开始时间选价；普通会话不再可换人物，恢复人物修复限制到暂停且缺失归属副本；调用明细只读展示按 callId 已持久化的快照。正式提供商/用途页面不再呈现评审草稿提示，保存继续写 READY v2。

本轮 12 个受影响 App JVM 测试类共 121 项通过；隔离 PKM110 Room instrumentation 共 4 项通过，含 v8→v9 旧表逐行保留、call/snapshot 事务、备份导入中途故障回滚和副本待确认修复/暂停解除。没有打开正式数据库、导入真实资料、发起收费请求或清理凭据槽位。core 既有准确证据为 28 项。正式资料升级、恢复 UI 手动验收、跨安装恢复及最终独立复核仍未完成，不得据此将 P2-A 标为关闭。
