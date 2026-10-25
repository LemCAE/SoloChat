# P2-A 最终独立复核

> 2026-10-25，Sol。审查对象为 `3b4438d` 之后的当前未提交工作区，不将任务书中的阶段性完成等同于最终通过。本轮只读源码/差异/现有证据，另写本报告；不修改实现、不装机、不访问真实配置、不清理 Key、不提交/推送/发布。

> 最新结论（2026-10-25）：R1～R11 已通过独立复核，P2-A 技术阶段关闭，可作为 P2-B 技术基线；正式启用验证仍未完成。详见文末“R11 最终复核与技术关闭”。以下首次及中间报告保留其当时结论。

## 结论

**暂不能关闭；当前实现不能作为已验收的 P2-B 开发基线。** 数据契约、追加 schema、升级阶段和大部分功能组件已交付，但正式接入存在配置不可重启、合法凭据被拒、真实调用无法备份等确定性契约矛盾。先定向修复下列问题，不需重写 P2-A，也不需用正式资料补测。是否由 Luna 或 Sol 修复由用户决定。

## 必须修复

### R1 · P1：正常保存 v2 配置后，下次启动被旧冻结候选阻断

- 位置：[ModelConfigUpgradeCoordinator.kt](../../app/src/main/kotlin/app/solochat/data/ModelConfigUpgradeCoordinator.kt) 37～39；[SettingsStore.kt](../../app/src/main/kotlin/app/solochat/data/SettingsStore.kt) 114～122；启动调用位于 SoloChatApp.kt 188、MainActivity.kt 73。
- 触发：升级 READY → 修改提供商名称/模型用途/价格并保存 → 重启。保存只更新当前 v2，冻结计划仍是升级时版本；协调器 READY 分支要求两者相等，必然报“READY 阶段与 v2 配置不一致”。Activity 此时只显示重试，聊天和设置均不可进入。
- 最小修复：READY 后验证并读取当前权威 v2，不再与不可变迁移候选做内容相等检查或重放旧关联。增加“升级→用户编辑→重新创建协调器/启动”的定向用例。现有测试分别验证 READY 重跑、编辑保存，没有连接两步。

### R2 · P1：凭据可用性错误地按 keyId 而非 providerId 建表

- 位置：[SoloChatApp.kt](../../app/src/main/kotlin/app/solochat/SoloChatApp.kt) 265～270；[ModelConfigResolver.kt](../../core/src/main/kotlin/app/solochat/core/agent/ModelConfigResolver.kt) 的 `credentialAvailabilityByProvider[provider.id]`。
- 影响：合法的 `providerId != keyId` 配置，即使加密槽位有 Key，正式调用也被判为 UNKNOWN/不可用。旧版本替换 Key 就可能已有独立槽位，不能假设两种 ID 相同。
- 最小修复：Key 内容仍按槽位索引，可用性按每个 provider.id 从其 keyId 建表；补实际组装层用例。当前准备器测试手工传入正确的两张表，未覆盖宿主组装。

### R3 · P1：真实调用快照使用数组，备份却只接受对象

- 位置：[CallConfigSnapshotMapping.kt](../../app/src/main/kotlin/app/solochat/data/db/CallConfigSnapshotMapping.kt) 12～15；[BackupRows.kt](../../app/src/main/kotlin/app/solochat/data/backup/BackupRows.kt) 66～72；[BackupData.kt](../../core/src/main/kotlin/app/solochat/core/backup/BackupData.kt) 138～141。
- 影响：AUTO 的真实参数被保存为 `[]`，显式模式保存为对象列表数组；导出按原文复制，`validateBackup` 却要求 JsonObject。有任何一条正常新调用快照就无法导出备份；真实 v2 备份往返未成立。
- 最小修复：统一实际写入、备份校验和读取契约，兼容已写入形状；从真实 `CallConfigSnapshot.toEntity` 构造记录完成 Rows→Codec→导入计划往返。现有备份夹具填的是手写 `{}`，因此未暴露此问题。

### R4 · P1：v2 Key 替换退回原槽位覆盖，进程中断可能留下旧地址配新 Key

- 位置：[SoloChatApp.kt](../../app/src/main/kotlin/app/solochat/SoloChatApp.kt) 359～382。
- 影响：`saveModelRegistry` 强制沿用旧 keyId，先 set/delete 旧槽位，再写 DataStore。普通异常尝试内存回滚不能覆盖进程被杀/掉电；旧 Key 可能丢失，重启时旧连接读取新 Key。回滚错误也被吞掉。本轮未执行这些操作。
- 最小修复：复用原项目“新槽位先成功保存→一次发布新槽位指针及连接”的语义，原槽位继续保留，不引入清理；校验完成再写入。补发布前中断/保存失败的虚构 Key 用例，不能只引用旧设置路径的测试。

### R5 · P1：旧价资格屏蔽整份价格配置，反而启用随包报价

- 位置：[ModelAwareCallPreparation.kt](../../app/src/main/kotlin/app/solochat/data/ModelAwareCallPreparation.kt) 110～125；[ModelPricing.kt](../../app/src/main/kotlin/app/solochat/data/pricing/ModelPricing.kt) 153。
- 影响：新模型资格 false 时，即使用户在该提供商价格页明确填了报价，整份配置仍被丢弃；随后 `useOfficialPrice=true + priceCatalog=null` 又触发 initialCatalog。诸如新代理提供商的 `deepseek-v4-pro` 会自动套随包报价，既不保持未知，也不采用用户所填报价。
- 最小修复：保留明确配置的逐模型报价，资格仅约束旧兜底/null 随包兼容路径；无确认报价的新模型保持未知，不用 null 暗中启用旧 assets。补真实 PricingRules/initialCatalog 组合测试，现有假 PriceResolver 未执行此路径。
- 同时核对模型请求 ID 编辑：[ProviderModelSettingsScreen.kt](../../app/src/main/kotlin/app/solochat/ui/settings/ProviderModelSettingsScreen.kt) 248～258 改请求 ID 却保留旧资格；变为新模型时不能继续套原兜底资格，显式报价照其确认规则保留。

### R6 · P1：先保存价格、再保存模型草稿，会覆盖刚保存的报价

- 位置：[SettingsScreen.kt](../../app/src/main/kotlin/app/solochat/ui/settings/SettingsScreen.kt) 363、464、510；[SoloChatApp.kt](../../app/src/main/kotlin/app/solochat/SoloChatApp.kt) 344～372。
- 触发：进入设置形成 p2Draft A → 价格页保存报价 B → 返回修改模型/用途并保存。价格重读仅刷新 registryDocument；p2Draft 只有 null 时才创建，仍带旧报价 A。随后整文档保存无基准版本校验，会把 B 写回 A。互斥锁不能发现过期草稿。
- 最小修复：保存时校验草稿基准版本并明确拒绝过期覆盖，或只合并该页实际修改字段；价格保存后同步干净草稿，不能丢弃其他未保存编辑。补“价格保存→模型保存”的定向交互/存储用例。

### R7 · P1：旧测试网关仍能绕过 v2 选择与调用预检

- 位置：[TestGateway.kt](../../app/src/main/kotlin/app/solochat/data/logweb/TestGateway.kt) 84～118；[AgentTurnRunner.kt](../../core/src/main/kotlin/app/solochat/core/agent/AgentTurnRunner.kt) 115～118、185～193；ContextPlan.kt 18～20。
- 影响：`/api/send` 先把用户消息标为已推送，再 `runner.run(turn)` 不传 prepared call。运行器走旧 `modelOverride + deps.config()`；忽略 v2 会话/聊天用途和待确认、能力等规则，价格也用旧 Snapshot 字段，不写配置快照。它不必等价于 UI 的播放节奏，但不能保留另一套模型选择引擎。
- 最小修复：网关落盘前完成正式 v2 预检并传同一 prepared call，或让正式宿主的运行器统一准备；旧 null 兼容只留给测试宿主，不能用于 App 中可触达的收费请求。补网关遇到待确认/会话指定/思考 OFF 不支持时的用例。

### R8 · P2：多段压缩复用预览时间和价格，未按各次调用开始冻结

- 位置：[CompressionService.kt](../../core/src/main/kotlin/app/solochat/core/agent/CompressionService.kt) 96～104、212～219。
- 影响：generate 检查 currentCall 后实际请求仍复用 preview.preparedCall，所有摘要段都以预览时的 priceAt 记录开始时间和费用。即使生成前校验通过，后续摘要/合并请求跨峰谷仍用旧价；各次开始时间也被记为预览时间。预览到生成前价格已变化时现有代码会拒绝，这条保护不能覆盖多段生成期间的时段变化。
- 最小修复：连接/模型/规则版本继续按压缩作业冻结，每个实际摘要调用单独记录真实开始时间并在冻结的规则版本内选价；不能重读用户后来编辑的配置。增加非空 prepared call 的多段/跨时段测试。当前 CompressionServiceTest 的 prepareCall 总返回 null，25 项主要验证旧分支。

### R9 · P1：普通真实会话重新出现“更换人物”入口

- 位置：[ConversationSettingsScreen.kt](../../app/src/main/kotlin/app/solochat/ui/chat/ConversationSettingsScreen.kt) 209～211；[SoloChatApp.kt](../../app/src/main/kotlin/app/solochat/SoloChatApp.kt) 294～300；[RoomStore.kt](../../app/src/main/kotlin/app/solochat/data/db/RoomStore.kt) 142～145。
- 影响：任何空闲会话均可选任意人物并写原 personaId，不限恢复副本或缺失归属；下一轮人物/共享记忆归属随之改变。用户已在 P1 明确取消该能力，P2 只批准恢复缺失归属的确认入口。
- 最小修复：移除正常会话改归属入口，并在存储/应用入口限制恢复修复范围；备份内已明确的人物保持自动对应，不要求重新换卡。补正常会话不能改归属、缺失人物副本可明确修复的测试。

### R10 · P2：调用明细没有接入已保存的思考配置快照

- 位置：[StatisticsContent.kt](../../app/src/main/kotlin/app/solochat/ui/usage/StatisticsContent.kt) 204～232；RoomStore.calls 当前也未联结配置快照。
- 影响：推理 Token 仍显示，但提供商、发起时 AUTO/ON/OFF、强度/用途来源与实际参数都看不到；未满足用户已确认的调用明细要求，也无法区分同名模型的调用来源。
- 最小修复：只读按 callId 取现有快照用于明细，旧调用显示“未记录”，不要读当前配置补历史；保留现有 Token 来源及计价。补有/无快照和后续改配置不影响历史展示的定向检查。

## 已有证据及其边界

- **App 105 项属实**：当前 `app/build/test-results/testDebugUnitTest/` 八类 XML 合计 105、失败/错误均 0，时间戳 2026-10-25T06:05:36～39。包括协调器 6、调用准备 4、快照映射 2、设置 16、备份 Rows 4、草稿 5、SessionController 43、CompressionService 25。
- **core 是 28 项**：当前三个 XML 为 resolver 10 + thinking 6 + backup 12，均通过，时间戳 06:11:07；交接的 27 应修正，不为对数字重跑。
- **Room 2 项属实**：connected/debug 的 PKM110 报告为 2 项通过；源码确实在隔离 v8 合成库上用 Room v9 打开并比较 13 张旧表，以及验证调用+快照同成同败。该证据有效，不等于 DataStore/SecretStore/设置保存/备份 UI 的端到端测试。
- 准备器测试手工提供正确的可用性表，价格测试使用假 resolver；SessionController 正常宿主返回 null prepared call，压缩测试也返回 null；SettingsStore 编辑测试不再启动协调器。各局部测试通过与 R1～R8 并不矛盾，不能扩大为正式接入已通过。
- 设置主菜单源码已符合“聊天：模型选择/节奏/行为；服务配置：提供商与模型/价格”，旧入口不可从主菜单访问。深浅/窄屏证据按 Luna 报告认可；空白隔离包未覆盖有模型状态、思考限制、保存重启和恢复修复，不重复截图能补足的程度。
- 正式包、真实 Key/配置/聊天库没有被本轮审查触碰；没有证据证明真实资料已被损坏，风险指上述代码一旦正式启用的后果。

## 非阻塞事项与关闭前补验

- 旧未引用 Key 槽位保留是正确边界；不清理、不将清理设为关闭条件。P2-A 源码尚未提交是交付整理事项，不是正确性的替代证明。
- 已知厂商的精确白名单覆盖较小，其他模型需手动确认；这可以保留为有明确能力边界的后续事项，不得宣称全渠道兼容。思考 UI 目前一律展示多个强度，再在保存时拒绝；应按实际支持项收敛，不为此重做整体设计。
- 文档中“尚未实现/当前仍旧解析”的历史阶段注释和架构尾段未完全收敛，修复后统一更新；只读上下文统计仍有旧 modelOverride 路径，应在接入清理时核对实际来源显示。
- **技术关闭前必须补**：针对 R1～R10 的最小回归；隔离代表性配置完整升级→编辑→重启；不同 providerId/keyId；真实写入快照的备份导出→恢复→补归属/模型→解除暂停；非空 prepared call 的聊天/压缩及在途修改。可使用虚构凭据、隔离 Room/DataStore 和本地 HTTP 记录请求，不要求使用真实用户库或重复全量测试。
- **正式启用前的后续验收**：受保护真实资料升级、同发布签名正式包覆盖、实际厂商响应及真实跨安装恢复。它们不应在本次审查中擅自执行；正式资料未动本身不是应强迫补测的缺陷。不能用这些待验收事项替代当前已确定的修复。

修复及上述隔离链路通过后再做收敛复核，方可决定是否将 P2-A 关闭并冻结 P2-B 基线。本报告不是修复授权；在用户决定前暂停实施。

## R1～R10 修复后的增量独立复核（2026-10-25）

**结论：暂不能关闭，也暂不确认为已验收的 P2-B 基线。** 原十项问题的修复通过代码与现有证据复核，但新增提供商仍会复用旧身份及密钥槽位，见 R11。本轮只更新本报告，未修改业务代码、PLAN/HANDOFF 阶段状态，未运行测试、安装应用或操作真实资料。

### 原问题逐项结论

| 项目 | 结论及核对依据 |
|---|---|
| R1 | 通过。READY 分支校验当前 v2，不再与冻结候选比较；真实 DataStore 编辑、关闭重开及新协调器用例通过。该用例的 Room 为替身，不扩大为 Android 完整启动验收。 |
| R2 | 通过。正式宿主使用 `resolveProviderCredentials`，可用性按 providerId、Key 按 keyId；独立 ID 用例通过。 |
| R3 | 通过。实际写入改为参数对象，备份兼容旧数组；真实实体映射→ZIP→导入计划→实体往返有用例，Room 补验涵盖恢复与再导出。 |
| R4 | 原问题通过。既有提供商替换/清空 Key 使用新槽位，发布失败保留旧槽位。新增提供商分支仍有 R11，不能据此宣称全部密钥路径安全。 |
| R5 | 通过。明确价格目录保留，旧资格仅控制兼容兜底及 null 随包路径；新模型未知保持未知，改请求 ID 清除资格；用例执行真实 PricingRules。 |
| R6 | 通过。实际价格保存入口调用 `withPersistedPricing` 合并回模型草稿，保留未保存模型编辑；覆盖原先价格保存后再保存模型的覆盖路径。没有通用草稿版本冲突机制，不宣称支持任意多窗口并发编辑。 |
| R7 | 通过。网关落盘前强制 CHAT 预检，并将 prepared call 传入正式 Runner；预检拒绝不写消息，选定 endpoint/model/配置快照有断言。预检能力判断复用正式宿主及准备器。 |
| R8 | 通过。冻结配置与价格规则，每段摘要/合并及普通聊天按各自真实开始时间选价；非空 prepared call 的跨时段、在途配置修改及预览后规则变化用例覆盖原矛盾。 |
| R9 | 通过。普通改人物入口移除；Room 事务仅允许暂停恢复副本修复缺失人物，已有有效归属只能同值重试。 |
| R10 | 通过。统计事务按 callId 读取已存快照，明细展示历史提供商、用途、思考意图/来源/参数及原推理用量；无快照显示未记录，不查询当前模型补历史。 |

### R11 · P1：新增提供商复用临时编号，可能读取或覆盖保留的旧 Key

- 位置：`ui/settings/P2ModelSettingsDraft.kt` 74～81、95～103；`data/ProviderCredentialPublisher.kt` 19～34；`ui/settings/SettingsScreen.kt` 891～908。
- 确定性触发链：新增提供商保存 Key → 将用途/相关会话改到其他模型并移除该提供商 → 保存、重新打开设置 → 再新增提供商。草稿计数重新从 1 开始，只排除当前文档中的 ID，因而可再次生成同一个 `preview-provider-N`，并将它同时作为 keyId。旧 SecretStore 槽位按要求仍保留。
- 影响：新提供商未填 Key 时，测试连接/获取模型列表及保存后的调用可直接读取旧槽位，将旧 Key 发到新地址；填写新 Key 时，发布器对新增提供商直接写 candidate.keyId，可能覆盖保留的旧槽位。清空时若不写入，也可能仍指向旧槽位。模型 ID 同样可重用，不满足稳定身份要求。上述为代码链路结论，本轮未用真实 Key 或网络请求复现。
- 最小修复：正式新增 provider/model 使用不可重用的稳定 ID（如 UUID）；新凭据槽位独立生成，发布器不能把新增提供商的草稿槽位当作可信的历史槽位。新建且未填/已清空的 Key 必须保持缺失，连接测试也不得读取已删除对象的槽位。保留所有旧槽位，不以删除凭据解决冲突。
- 最小验证：合成 Key 覆盖“保存→移除→重新打开→新增”后不填、填新值及清空三种情况，断言新旧身份/槽位分离、旧值不变、未配 Key 不可测试/请求；另覆盖新增 Key 写入后文档发布失败。只需定向草稿/发布器/凭据读取检查，无须正式包、真实资料或全量测试。

### 验证证据与剩余边界

- 已读取当前 XML：App 12 类合计 121 项，时间戳 07:05:36～38；PKM110 instrumentation 4 项，时间戳 07:04:34；均 0 失败、0 错误。core 三类 28 项为 06:11:07 的保留证据，本轮未重跑。
- Room 证据有效覆盖隔离 v8→v9 保留旧表、调用与快照同事务、恢复中途失败整体回滚、快照往返、暂停/人物修复。解除暂停的补验直接操作 DAO，不等于 `SoloChatApp.repairAndResumeBackupConversation` 及 Compose 恢复流程已端到端验收。已有 JVM/Room 结果均未覆盖 R11。
- `git diff --check` 本轮实际执行通过；提示 LF 将转换 CRLF，属于换行符提示。
- 非阻塞证据改进：R9 的“有效归属不可换”设备用例使用不存在的目标人物，会在目标有效性检查处提前拒绝；源码另有正确的原归属保护，建议将目标也建成有效人物以精确覆盖该分支。部分阶段性注释仍称 v2 未接入，应在交付整理时收敛，不扩成重构任务。
- ADB 隔离包恢复安装 `[-99]` 来自 Luna 报告，本轮未连机重试。它不否定已完成的 4 项 Room 结果，也不能替代未完成的恢复 UI/视觉验收；无需为此安装正式包。
- R11 修复并定向复核后，再决定技术关闭。正式启用前仍需按授权完成：保护副本上的真实配置升级及重启/Key/历史账目核对、同签名覆盖与跨安装恢复、实际厂商请求和思考能力响应、恢复 UI 的保存/失败/解除暂停及最终 Android 视觉检查。价格管理与参考库体验留 P2-B，不提前实施。

当前暂停在审查报告，等待用户决定定向补修安排。

## R11 最终复核与技术关闭（2026-10-25）

**R11 通过；未发现剩余技术关闭阻塞。P2-A-1～10 及 R1～R11 的技术交付完成，关闭技术阶段。** R1～R10 沿用上轮独立结论，本轮只检查 R11 及其直接影响。此结论不代表真实资料已升级或正式启用验收通过。

| 核查项 | 结论与依据 |
|---|---|
| 新增身份 | `P2ModelSettingsDraft` 对新增 provider/model 使用带类型前缀的 UUID，Key 草稿另用独立 UUID；不再依赖重置计数。草稿测试包含保存、移除、重新打开、再次新增后身份分离。既有身份不重写。 |
| 新槽位发布 | 正式 `SoloChatApp.saveModelRegistry` 仍调用 `ProviderCredentialPublisher`；新增提供商无条件分配新槽位，不采信 candidate.keyId。未填写/清空时发布空槽位，填写时先同步保存新 Key 再发布文档。旧槽位全部保留。 |
| 连接与正式调用 | 连接测试及获取模型均调用 `requireProviderKeyForConnectionTest`：有待保存值时仅用该值，否则仅按已持久化的同一 providerId 查可信槽位；新草稿不探读自己的 keyId。清空不回退旧 Key。正式预检按已发布槽位判断缺失并拒绝调用。 |
| 失败与重试 | 发布失败保留旧文档/旧槽位及可能留下的新孤立槽位；下次调用发布器重新分配 UUID，不从失败草稿恢复旧槽位。替换/新增发布失败有定向用例；重试分配按代码核对，未做真实进程终止试验。 |
| 升级兼容 | 升级仍由原计划保留旧 providerId/keyId，并复用已冻结模型映射；不走 UI 新建身份分支。既有提供商无 Key 变更时，发布器从当前文档保留可信槽位。升级计划 8 项及调用准备 5 项通过，未发现影响 R1～R10 的直接回归。 |

**本轮证据**：已读取 App JVM 五类 XML，草稿 7、发布器 7、凭据 3、调用准备 5、升级计划 8，共 30 项，0 失败/错误，时间戳 07:33:59～07:34:00。不重跑、不与上一轮 121 项累加。Android 测试类产物晚于更新后的源码，与 Luna 的编译通过报告一致；编译不等于运行。`git diff --check` 本轮通过，仅有既有换行符提示。

**R9 补强不阻塞技术关闭**：源码现已用有效人物分别覆盖普通会话与暂停副本的拒绝路径；存储保护逻辑未变，上一轮已判为非阻塞证据改进。本轮不为此安装应用或重复 Room 验收。新增双有效人物断言仅编译、尚未执行；可随下一次获授权的隔离 Room 验证定向运行，不能将上轮 4 项结果称为新版断言已通过。

**基线与启用边界**：确认本轮复核工作区为 P2-A 技术基线，尚未提交；待授权后以独立提交固定不可变版本。本轮仅整理文档，未修改业务代码、执行测试、操作设备/正式资料/真实 Key、清理槽位或推送发布。正式启用前的独立验收集中见 [PLAN §2.6](../PLAN.md#26-p2-a-验收与任务入口)。P2-B 尚未实施，继续等待用户安排。
