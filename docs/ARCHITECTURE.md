# ARCHITECTURE —— 系统是怎么搭起来的

> **定位**：结构。回答"有哪些模块、怎么连、数据长什么样、改一处会牵动哪里"。
> **为什么**这样设计 → [DECISIONS.md](DECISIONS.md) / [01-decisions.md](01-decisions.md)；
> 改代码的纪律与取证手法 → [02-handoff.md](02-handoff.md) §3、§5。
>
> 本文按 **2026-10-08 的代码**核对过一遍（含一次逐文件的只读审计）；2026-10-10 补充界面、设置、ContextPlan、调用记账与 v6 存储结构。
> 行号会腐化，**以文件名为准**；带「不确定」的地方没有验证过。

## 0. 一分钟速览

```
        ┌─────────────────────────── app（Android） ───────────────────────────┐
UI  ──► ChatScreen ──► SessionController ──► AgentTurnRunner（core）──► LlmClient ──► 厂商 API
          ▲                  │  ▲                    │
          │                  │  │                    ├─ ContextPlanner → PromptBuilder（core）◄─ StoragePort
          │                  │  │                    └─ ScriptParser（core）
          │                  │  └── Player.plan（core，纯函数）──► EventSink ──► Room
          └── ChatProjector ◄─┴── reloadNow() ◄── Room（事件 / 消息 / …）
```

四条要点：

1. **`:core` 是纯 Kotlin JVM**（无 `android.*`）：协议、节奏、防抖、提示词装配、播放计划、端口。
   一切"容易写错又必须反复回归"的逻辑都在这里，用秒级单测覆盖。
2. **一轮 = 一次模型调用**：请求结束就拿到完整脚本，播出由播放器异步做（生成/播出解耦）。
3. **端口-适配器**：`core` 只依赖接口（`StoragePort` / `EventSink` / `LlmClient` / `AgentDeps` /
   `TurnHost`），Android 侧由 `RoomStore` / `OpenAiCompatClient` / `SoloChatApp` 实现。
4. **界面是投影**：`events + messages → List<ChatItem>`，界面只画投影结果，不直接读事件表。

### 0.1 塑造出这套结构的几个决策（**理由与推翻条件见 [DECISIONS.md](DECISIONS.md)**）

| 决策 | 在结构上的体现 |
|---|---|
| 一轮 = 一次调用，输出**事件脚本**（D3） | `ScriptParser` / `ScriptEvent` / `Player` 这条链；没有工具调用往返 |
| 生成与播出**解耦**（D4） | `AgentTurnRunner` 只管到"事件落盘"，播出是 `SessionController` 的事；`delivery` 五态 |
| 模式是**会话上的属性**（D7） | `Conversation.mode` + `Event.mode`，没有"场景会话"这种类型 |
| 防抖在**用户侧**、不做语义预判（D6） | `Debounce`/`RhythmLearner` 在 core，纯规则、零 API 调用；另有手动两段开关 |
| 历史**默认不限条数** + D10 重排 | `ChatConfig.historyLimit`（0/负 → `LIMIT -1`）；稳定 system，user 中摘要/原文在前、动态状态在后 |
| 解析**宽进**（D11） | `ScriptParser` 四层 + `DegradedTurn`（历史脏行还原） |
| `core` 不含 Android、端口-适配器 | `ports/*` + `TurnHost`，让最易错的逻辑能在纯 JVM 里回归 |

## 1. 模块与依赖方向

| 模块 | 类型 | 依赖 | 内容 |
|---|---|---|---|
| `:core` | 纯 Kotlin JVM（`kotlin-jvm` + serialization） | 只有 kotlinx-serialization / coroutines / okhttp | `agent` `llm` `log` `model` `persona` `playback` `ports` `prompt` `protocol` |
| `:app` | Android（AGP + Compose + KSP/Room） | `:core` + AndroidX + Ktor | `data`(+`db`/`llm`/`logweb`/`persona`/`pricing`/`prompt`) `play` `service` `ui` |

**铁律**：`core` 里不许出现任何 `android.*`（否则它那些单测就跑不起来）。
判断依赖方向：`app → core`，**永不反向**；`core` 想读设置/存储时，走端口。

### core 各包

| 包 | 关键文件 | 管什么 |
|---|---|---|
| `protocol` | `ScriptParser.kt`（四层宽容解析）、`DegradedTurn.kt`（历史脏行还原）、`Pacing.kt`（节奏公式）、`Debounce.kt`（防抖 + `RhythmLearner`）、`PacingParams.kt`（可调参数表） | "模型说了什么"与"什么时候播" |
| `prompt` | `PromptBuilder.kt`、`PromptResources.kt` | 把安全规则/人设/协议/风格/记忆/历史装配成两条 API 消息 |
| `agent` | `AgentTurnRunner.kt`、`AgentDeps.kt`、`ContextPlan.kt`（有效模型、会话操作锁、实际上下文） | 正式请求与统计共用装配；生成记账完成后交给播出 |
| `playback` | `Player.kt` | **纯计算**出整场演出的状态序列（可对打断做穷举测试） |
| `ports` | `Ports.kt`（`StoragePort` / `ClockPort`）、`EventSink.kt` | 与外部世界的边界 |
| `model` | `Event.kt`、`Conversation.kt`（含 `Persona`）、`Usage.kt`、`CallRecord.kt`、`ChatMode.kt`、`UserUtterance.kt` | 事件/会话/记忆/用量领域模型；调用字段来源、冻结费用与聚合 |
| `model` 价格配置 | `PriceCatalog.kt` | 多价格表、逐模型峰谷/时区、配置校验；调用开始时间解析为冻结 `ModelPrice` |
| `llm` | `LlmClient.kt`、`SseFrameParser.kt`、`LlmError.kt` | 流式协议与错误分类 |
| `log` | `Log.kt` | 内存环形日志（600→**300** 条，单条上限 **64k** 字符） |
| `persona` | `PersonaDocument.kt` | 人物卡文档的解析/导出/`applyTo` |
| `backup` | `BackupData.kt`、`BackupCodec.kt`、`BackupImportPlan.kt` | 业务备份 ZIP 协议、引用校验及副本规划；app 已接 Room/SAF 导出与事务副本恢复，见 `07-backup-format.md` |

### app 各包

| 包 | 关键文件 | 管什么 |
|---|---|---|
| `data` | `Mappers.kt`、`SettingsStore.kt`、`SecretStore.kt`、`AppLogSink.kt` | 表↔模型翻译、设置、密钥、日志落 logcat |
| `data/pricing` 与价格页 | `ModelPricing.kt`、`PriceTableEditor.kt` | 随包快照转初始表、复用精确匹配和兜底；价格表集合随 PRICE 分组保存到 DataStore，界面管理草稿 |
| `data/db` | `SoloChatDatabase.kt`(v7)、`Entities.kt`、`Daos.kt`、`Migrations.kt`、`SchemaV6.kt`、`SchemaV7.kt`、`RoomStore.kt` | 唯一持久化实现（`StoragePort` + `EventSink`） |
| `data/llm` | `OpenAiCompatClient.kt` | OpenAI 兼容的流式客户端（SSE 分帧委托给 core） |
| `data/logweb` | `LogWebServer.kt`、`LogViewerHtml.kt`、`ResponseJson.kt`、`TestGateway.kt` | App 内嵌的日志网页与**测试接口**（真机取证靠它） |
| `data/prompt` | `AssetPromptLoader.kt`、`PersonaDefaults.kt` | 读 `assets/prompts/*.txt`；两张出厂人物卡（硬编码） |
| `data/pricing` | `ModelPricing.kt` | 内置价格表匹配（140 条）+ 纯函数 `PricingRules` |
| `play` | `SessionController.kt`（**最大也最要紧**，约 1560 行）、`ChatProjector.kt`、`TurnHost.kt` | 会话编排：防抖、认领、落盘、起回合、播出、打断、手动两段 |
| `data/backup` | `RoomBackupReader.kt`、`RoomBackupImporter.kt`、`BackupRows.kt`，配合 `db/BackupDao.kt` | 一致读取导出；确认后在单一事务中写副本、来源、暂停与账目标记，冲突回滚，不重算价格 |
| `ui` | `chat/ChatScreen.kt`、`chat/ConversationSettingsScreen.kt`、`CompactTopBar.kt`、`SettingsComponents.kt`、`ChatRender.kt`、`conversations/`、`persona/`、`memory/`、`usage/`、`settings/`、`logs/`、`backup/`、`theme/Theme.kt`、`PersonaLabel.kt` | Compose 界面（**8 个导航目的地**；全局设置分组主菜单 + 7 个子页（含只读版本信息）；会话设置为聊天上层全屏覆盖页） |
| `service` | `AgentForegroundService.kt` | 常驻服务（当前**无界面入口**，见 §8） |

## 2. 端口与适配器（为什么切这些接口）

| 接口（core） | Android 实现 | 存在的理由 |
|---|---|---|
| `StoragePort` | `RoomStore` | 让 `AgentTurnRunner`、`SessionController` 能在纯 JVM 里用假存储测试。**读/写方法刻意分成两个名字**（`recentMessages` vs `modelVisibleMessages`）：默认参数一旦给错方向，泄漏是静默的 |
| `EventSink` | `RoomStore` | 播出过程的写入端（逐条、幂等、可撤销）。**单独一个接口**是因为调用者完全不同 |
| `ClockPort` | `SystemClock`（测试用 `VirtualClock`） | 时间必须是可注入的：交付时间决定"逐条出现"的语义 |
| `LlmClient` | `OpenAiCompatClient` | 换厂商只改这一处；测试用 `StubLlm` 精确控制"第几条请求时插话" |
| `PriceResolver` | `ModelPricing` | 成本估算与请求解耦 |
| `AgentDeps` | `SoloChatApp.deps` | 把 `AgentTurnRunner` 需要的依赖收成一个接口（否则测试要构造一个七参数的怪物） |
| `TurnHost` | `SoloChatApp.turnHost` | `SessionController` 对外部世界的全部依赖。**不能直接依赖 Application**，否则它最需要回归测试的逻辑没法在 JVM 里跑（第一版为此吃过亏） |

`PromptResources` 是把提示词从 assets 读进来的数据结构；测试里直接传字符串，所以**改提示词不需要跑测试**（但需要真机/装机验证）。

## 3. 一条消息的完整生命周期

按调用顺序（括号里是文件与方法）：

1. **输入** `ChatScreen` 建 `SessionController`，用户按发送 →
   `SessionController.send(kind)`。
   - `MANUAL`：只 `appendUnpushed()`（`message.sentToModel = false`，界面显示"代发"）→ 结束。
   - `AUTO`：进内存 `pending` + `scheduleDebounce()`。
2. **防抖**（仅 AUTO）`Debounce.decide()`：规则 + `RhythmLearner` 学到的窗口；界面显示
   "对方正在输入…"以外的等待提示（`AgentEvent.Waiting`）。
3. **认领与落盘** `flushTurn()` → `claimPending()`（取批与清空在**同一临界区**）→
   `persist()`（写 `message`，用户消息 `sentToModel = true`）。
4. **起一轮** `startTurn()` → `AgentTurnRunner.run()`：
   - **RF2 装配** `ContextPlanner.prepare()`：在会话锁内解析模型覆盖/继承 → `modelVisibleMessages()` 取历史 → `PromptBuilder.build()`
     → 两条 API 消息（system 稳定前缀、user 摘要/历史+变动区）。
   - **RF3 调用**：先保存 STARTED 调用及价格/上下文快照；`llm.chatStream()` 收正文、推理与部分用量；`withTimeout(config.timeoutMs)`。会话锁不跨网络等待。
   - **RF4 解析** `ScriptParser.parse()`：四层宽容解析 → `Script(events, nextMode, dropped, degraded)`。
   - **RF5 落盘** `persistEvents()`：事件以 `delivery = PENDING` 落库（**先落盘再播出**，
     这样用户气泡不会在等待期间消失）；同一轮的重复词在这里抑制；`remember` 事件另走
     `persistRemembered()`。
   - **RF6 记账**：以 callId 收尾 `call_record`，成功/静默在事务内关联兼容 `usage` 行；失败/取消也保留尝试。每个字段独立标来源，缺输入/输出按实际全文估算，缓存/推理未知不补 0。费用使用冻结单价。
   - 产出 `AgentEvent.ScriptReady(events, modeProposal, degraded)`。
5. **播出** `playEvents()` → `Player.plan()`（纯函数算出状态序列）→ `runPlayback()`：
   逐条 `sink.markDelivered()` + `appendSpokenAssistantLine()`（**播完一条就写助手历史**）
   + `reloadNow()` 刷新界面。
6. **投影** `ChatProjector.projectVisible(events, messages)` → `ChatUiState.items` → 渲染。
7. **收尾** `playEvents` 末尾刷新兼容 UI 用量状态、清 `live`、`endPlaybackPhase()`；新调用记录在 ScriptReady 前已保存，不依赖完整播完。回合只局部更新会话时间，不用旧会话快照覆盖配置。

**打断**（用户在播出中途发消息）：`send()` → `interruptPlayback()` → `Player.interrupt()` 把事件分成
"已出口 / 半句（`INTERRUPTED`）/ 取消（`CANCELED`）"三堆，再走同一条落盘与投影路径。

## 4. 数据模型

### 4.1 Room（版本 6，11 张表）

| 表 | 关键列 | 语义要点 |
|---|---|---|
| `conversation` | `id, title, mode, modeSince, modeSinceEventId, personaId, pinned, archived, proactiveCooldownUntil, modelOverride, activeContextRevisionId, compressionMethod` | `mode` 是属性；modelOverride=null 继承全局，摘要指针在用户应用后启用 |
| `event` | `id, conversationId, kind, speaker, text, turnId, mode, gapHintMs, typingHintMs, waitReason, delivery, gapMs, typingMs, createdAt, deliveredAt` | **时间轴的原子单位**。`*HintMs` 是模型给的建议值，`*Ms` 是引擎算的实际值 |
| `message` | `id, conversationId, role, text, turnId, createdAt, interruptedAt, sentToModel` | **模型上下文**的来源。一个气泡 = 一行 |
| `persona` | 八段文案 + `avatar` + `builtin` | 按模式注入，见 §5 |
| `memory` | `id, content, tags, personaId, lastUsedAt, useCount, sourceConversationId, pinned` | `personaId = ''` 表示通用 |
| `scene_summary` | `fromEventId, toEventId, text, model` | 旧摘要缺消息覆盖依据，不注入；手动压缩走 context_revision/context_segment |
| `usage` | `prompt/completion/cached/reasoningTokens, imageCount, estimated, at` | 原行保留；新成功调用的兼容行由 call_record.usageId 关联，汇总不重复计入 |
| `call_record` | `id, operationId, conversationId, model, kind, status, startedAt, finishedAt, usageId, detailJson` | 实际请求逐次记账；JSON 保存字段来源、冻结单价、上下文快照及输出不完整标志；启动将残留 STARTED 标为 INTERRUPTED |
| `context_revision` | `id, conversationId, personaId, parentRevisionId, method, sourceSnapshotJson, state` | 草稿与已应用版本；冻结消息/事件、人设、父版本来源 |
| `context_segment` | `revisionId, ordinal, fromMessageId, toMessageId, sourceChecksum, text, model` | 按来源消息顺序连续覆盖；各版本独立分段，不覆盖旧版本 |
| `client_command` | `id, conversationId, payloadChecksum, status, resultJson` | 网页幂等命令基础，正式网页尚未接线 |

迁移（`Migrations.kt`，**只增不改**，不用 `fallbackToDestructiveMigration`）：
`1→2` memory 加 `personaId` 并回填 · `2→3` 用户消息 `sentToModel = 1`（启用死字段）·
`3→4` persona 加 `sceneVoice/mannerisms/surroundings` · `4→5` persona 加 `depiction` · `5→6` conversation 追加 `modelOverride`、`activeContextRevisionId`、`compressionMethod`（默认 ORIGINAL），追加上述四表及索引。没有重写旧消息或用量。
迁移无 instrumentation；`tools/verify-migration-v6.py` 在真机 v5 副本执行实际 SQL，对照导出 v6 schema、索引与旧行；实际升级后再次拉库核对。

### 4.2 几个"名字会骗人"的字段

| 字段 | 看起来是 | 实际是 |
|---|---|---|
| `Message.sentToModel` | "有没有发给模型" | **"已推送"**（手动模式里 `false` = 代发）。名字是历史遗留，沿用是为了不做一次迁移 |
| `Event.speaker` | 说话的角色的名字 | 模型的原始输出；模型没给时被填成占位 **`"ta"`**（`say`/`act`），`scene` 恒为 `""` |
| `Message.text` 里的 `*…*` / `（…）` | 装饰 | **类型标记**：用户与助手两侧用同一套编码（`UserUtterance` / `eventsToAssistantText`），解码只有一处 |
| `Event.mode` | 这条事件的模式 | 用于还原"这段是当时的场景"，**不表示**这条只能在那个模式播 |
| `Event.delivery` | | 见 4.3；**界面与上下文都只认 `spoken`** |

### 4.3 交付状态机

`PENDING → PLAYING → DELIVERED`；被打断时正在播的那条 → `INTERRUPTED`，没轮到播的 → `CANCELED`。

- `spoken = DELIVERED || INTERRUPTED` —— 只有这两种会进上下文、上时间轴。
- ⚠️ `INTERRUPTED` 的那条**界面上会画完整正文**（用户看到的是整句），所以"它只说了半句"是**错的**推断。
- 进程重启时残留的 `PENDING/PLAYING` 一律收成 `CANCELED`（`sweepUnfinished()`），
  一旦标成 `INTERRUPTED` 就会"重启后凭空冒出几条消息"（真机实测 7 条）。

### 4.4 内存态（`ChatUiState`，界面唯一状态源）

要点字段：`items`（已落定的时间轴）·`pendingItems`（已发出、还没交给模型）·`live`（正在播的那一条，
同一时刻最多一条）·`turnPhase`（IDLE/BATCHING/THINKING/SENDING，顶栏动画据此切换）·
`modeProposal`·`pendingSendCount`（手动模式的"代发"条数，决定发送键第三态）·
`title`/`personaName`/`personaAvatar`/`personaIsFallback`（顶栏那两行）·用量与成本（状态保留，聊天顶栏已不显示）。

会话设置订阅同一个 `ChatUiState`，查看设置或统计不另建/关闭 `SessionController`。空闲时可改名、删除、指定模型或进入人物卡管理；统计只读，忙碌时也能看。压缩覆盖页与 `/compress` 使用相同控制器，应用/恢复须空闲。会话配置修改与请求准备共用 `ConversationOperations` 按 id 的锁；改名/模型/模式/人设分别使用局部 UPDATE，换人设停用摘要指针。删除事务清理当前会话、事件、消息及摘要版本/分段/命令；保留人物卡、记忆与独立调用/用量。全局设置仍按组校验保存，接口与密码分别由 SecretStore 保存。

`EffectiveConfig` 只覆盖模型 id，默认继承全局；接口和密钥共用。`ContextPlanner` 共用当前有效模型、人设、记忆、已推送消息与出口历史，输出实际 BuiltPrompt、消息 id、稳定前缀字符和全文估算。正式请求保存这份快照；统计模拟下一轮，时间/历史可能不同。旧 scene_summary 缺消息覆盖依据，明确不启用。验证启用版本及来源后，用全部摘要段替换覆盖的最早消息前缀；来源失配/覆盖损坏/误指向草稿时回到原文装配，不改版本指针。现有有限窗口只作用于保留的原文，并在预览提示。

### 手动压缩（阶段 3）

`CompressionHistory` 根据有序消息和已出口 SAY/ACT/SCENE 事件确认助手调用组，只在完整组后提供切点；保留最近交流和最新待回应输入，不按用户/助手 turnId 相等配对。无法验证消息与出口事件一致的前缀不能压缩。

`CompressionService` 在会话锁内冻结来源消息/事件校验值、人设与父版本，再在锁外生成；可继续聊天。材料按完整交流打包进估算输入预算，单组太长需提高预算。原文重摘要和增量摘要可递归合并，落单摘要直接沿用，调用有上限；多段方式保留旧段原文并追加相邻新段。每个真实请求保存 SUMMARY 调用，统一 operationId，不经过事件解析、播放或记忆写入。

摘要目标字符数是提示词中的长度目标；请求输出上限沿用当前有效模型配置，0 表示接口默认。不能把目标字符数换算成硬性输出 token 上限：实际接口可能先输出推理，消耗全部配额后没有摘要正文。预览明确区分输入预算、摘要目标与接口输出上限。

生成仅保存 DRAFT；预览与编辑使用同一个 ContextPlanner，不请求模型。应用先检查空闲，再由 Room 事务重新校验来源、切点、父指针，保存 COMPLETE 和切换 conversation 指针。恢复只清指针，保留版本和原消息/事件。分段按来源列表验证连续覆盖，不假定全库消息 id 连号；新版本复制旧段时另建行 id，不覆盖旧版本。

`CompressionCoordinator` 用应用协程维护每会话生成任务，离开压缩页不取消；完成草稿落 Room，重启可从草稿列表加载。失败/取消不切指针，已发生调用在 NonCancellable 中收尾记账。日志正文受现有 verbose 开关控制。

`StatisticsSnapshot` 在 Room 事务内读取调用和未关联旧用量；全局今天/本月/全部、按模型及会话详情共用 `summarizeCalls`/`recordedCost`。新价格区分未知与已知免费；缓存未知且优惠不同显示范围，币种不混算。旧版没有价格快照，金额未知。`estimateCost` 仅供旧兼容路径，新统计不按当前全局价格重算历史。

## 5. 提示词装配（`PromptBuilder`）

两条 API 消息：

```
[system] 稳定前缀（同人设、模式、资源下逐字节不变）
         安全规则 → 人物设定（按模式）→ 模式与协议 → 风格 → 记忆规则
[user]   早期摘要 → 未压缩历史 → 记忆/打断/当前模式/cue → 现有时间锚点 → 本轮指令
```

- **稳定前缀的顺序即优先级**，不许调整；`BuiltPrompt.stablePrefixChars` 就是它的长度（成本口径）。
- **变动区**（记忆、当前模式、被打断提示、cue、时间）必须在最后 —— 任何"每轮都变"的东西
  放进前缀都会让缓存全灭。阶段 3 已按 D10 重排并比对实际请求；没有摘要时同样装配，空历史仍保留模式/cue/时间。启用或恢复摘要时 user 前部有意改变，不能承诺这一轮的缓存命中率。
- 人物卡**按模式注入**：`sceneVoice` / `surroundings` / `depiction` 只在面对面出现；
  空段落**整个不出现**（留空标题会让模型自己编一段）。
- 记忆有字符预算（`MEMORY_BUDGET_CHARS = 2000`）。
- 提示词正文在 `app/src/main/assets/prompts/`：`00-safety` `10-protocol-chat|scene`
  `20-style-chat|scene` `30-memory` `40-proactive`；`{{persona_name}}` 这类占位符在装配时替换。
- ⚠️ **示例比规则硬**：协议文件里的 few-shot 示例如果示范了不该做的事（曾经示范
  `"next_mode": "scene"`），模型会照抄。改提示词时**先看示例**。
- ⚠️ **历史里不能出现模型自己写坏的产物**：`renderHistory` 会把"整段 JSON"的历史还原成正常几行，
  否则模型把那种写法当范例照抄（实测 11 处）。

## 6. 播放与打断

- `Player.plan(events, pacing, readDelayMs)` 是**纯函数**：把事件编译成
  `SequenceStep(Reading/Typing/Gap/Delivering/Done, waitMs)` 序列 —— 于是"打断发生在任意时刻"
  可以穷举测试（`PlayerTest` 的中断矩阵）。
- 时长来源在 `Pacing.kt` 与 `PacingConfig`（可在设置页调）：模型给的感觉值 + 按字数的物理值混合；
  **面对面是另一套公式**（不算打字时间、动作与旁白按固定小拍子）。
- `SessionController.runPlayback()` 逐步执行，每一步都先确认"我是否还是当前那场演出"，
  然后逐条落定 + 刷新 + 写助手历史。
- 事件数是"每轮 4~6 条"是常态（面对面可以到 10+），所以**一轮 = 一次调用 + 多条气泡**。

## 7. 界面投影

`ChatProjector.projectVisible(events, messages)` → `List<ChatItem>`（7 种）：
`Mine`（我的话）·`Theirs`（对方的话）·`MyAction`（我的动作，靠右）·`Action`（对方动作，居中带名字）·
`Narration`（环境旁白，无人称）·`Divider`（系统分隔行，如"—— 见面了 ——"）·`Live`（正在播，未落定）。

- 门禁：事件必须 `delivery.spoken` 才画；`sameGroup`（同一人 + 3 分钟内）只影响圆角与间距。
- **三个来源互斥**：`items` / `pendingItems` / `live`，靠 eventId 交接（`alreadyProjected` 命中即清 `live`）。
- 逐字显示在界面侧（65ms/拍），引擎只负责"什么时候开始出现、什么时候算说完"。
- 发送键三态：有字 → 亮色发送；无字但有代发（手动模式）→ 蓝色推送；否则灰。
  **它只改图标 tint，不加实心底**（这是用户明确要求的）。

## 8. 平台设施

- **2026-10-10 增补：用户系统消息**：`ChatComposer` 的斜杠菜单 → `SessionController.sendSystem()` → `message(role=SYSTEM)`；显示为分隔记录，下一轮历史装配可见，不触发额外 API，不改变稳定前缀。
- **2026-10-10 增补：测试网关**：`TestGateway` 使用与 App 相同的 `AgentDeps`；测试输入直接标记为已推送，进入 `modelVisibleMessages`。`reset` 只新建带“测试会话”标题的会话，保留全部旧会话；测试接口仍跳过防抖与播出，不能据它判断动画或完整多轮播放行为。
- **2026-10-10 增补：文件日志**：`AppLogSink` 同时输出 logcat 与异步单线程文件队列，`FileLogSink` 在私有 `files/logs` 中写 UTF-8 JSONL（含时间、级别、tag、正文），四份约 2MiB 轮转。`LogScreen` 通过文件选择器导出跨重启记录；内存日志清空不删除文件。队列最多 512 条，过载或 IO 失败只报告 logcat，进程突然终止可能丢失尚未落盘的队列尾部。

- **日志** `Log`：内存环形缓冲（300 条、单条上限 64k），`Log.sink` 转 logcat（>3000 字符分片）。
  正文型长日志用 `Log.body`（提示词、模型原始输出）。**这是排查的唯一手段**，所以"完整提示词"
  的上限必须跟得上提示词增长（历史不设上限后已达 3.7 万字符）。
- **日志网页 / 测试网关** `LogWebServer`（Ktor CIO）：`GET /`（网页）、`/api/logs`、`/api/health`（免鉴权）、
  `/api/conversations`、`/api/history`、`/api/messages`、`POST /api/send`、`POST /api/delete*`。
  **只在"允许局域网"时校验密码**（`?t=` / `Authorization: Bearer` / `X-Log-Token`）；
  密码来自 `SecretStore.webPassword`（用户设置）。`TestGateway` 能同步跑完一轮并返回原文/用量 ——
  真机取证基本都走它。
- **常驻服务** `AgentForegroundService`：前台通知 + **60 秒**节拍（`TICK_MS`），
  每次 tick 拿"静默时长"和**硬编码的 30 分钟阈值**（`PROACTIVE_IDLE_MS`）比 ——
  只改通知文案、**不生成消息**；设置的 `proactive_after_ms` / 冷却 / 免打扰它都不读。
  ⚠️ 它只在 `settings.keepAlive == true` 时启动，而
  **代码里没有任何界面入口能打开这个开关**（`setKeepAlive` 无调用者）——
  实测 `dumpsys activity services app.solochat.debug` 里没有它（2026-10-08）。
  **不确定**：设备上 `keep_alive` 若曾为 `true` 则会启动（当时设备已掉线，未核该值）。
- **安全存储** `SecretStore`：`EncryptedSharedPreferences`（Keystore AES）存 API Key、
  各搜索服务的 Key、日志网页密码。加密不可用时**降级为明文但置 `degraded = true`**，
  设置页必须显式提示。界面只回显掩码（不提供"读全量"的入口）。

## 9. 文件 → 职责速查

<details>
<summary>core（22 个文件）</summary>

| 文件 | 职责 |
|---|---|
| `protocol/ScriptParser.kt` | 四层宽容解析 + 停顿折叠；`ScriptEvent` / `Script`；`resolvedSpeaker` |
| `protocol/DegradedTurn.kt` | 把"整段 JSON 的一条 `say`"拆回本来该有的几条（只改形状） |
| `protocol/Pacing.kt` | 节奏公式（聊天/面对面两套）、`ReadDelay`、`BubblePace` |
| `protocol/PacingParams.kt` | 可调参数表（设置页按分组自动渲染） |
| `protocol/Debounce.kt` | `IncomingMessage` / `DebounceTier` / `Debounce` / `RhythmLearner` |
| `prompt/PromptBuilder.kt` | 装配两条 API 消息、渲染历史、事件↔文本翻译、历史脏行还原 |
| `prompt/PromptResources.kt` | 七段提示词资源 + 占位符替换 + 优先级顺序 |
| `agent/AgentTurnRunner.kt` | RF2–RF6 流水线；去重抑制；模式提议校验；用量记账 |
| `agent/AgentDeps.kt` | `AgentEvent` / `TurnOutcome` / `AgentTurn` / `SendMode` / `ChatConfig` / `AgentDeps` |
| `playback/Player.kt` | 纯函数：编译播出计划；`interrupt()` 三分类 |
| `ports/Ports.kt` | `StoragePort` / `ClockPort`（含 `LIMIT -1` 的语义约定） |
| `ports/EventSink.kt` | 播出状态的写入端 |
| `model/Event.kt` | `EventKind`（6 种）/`DeliveryState`/`Event`/`Message` |
| `model/Conversation.kt` | `Conversation`/`Persona`（八段+按模式注入）/`resolvePersona`/`DEFAULT_PERSONA_ID` |
| `model/Usage.kt` | ⚠️ 文件名骗人：`Memory`/`SceneSummary`/`Usage`/`ModelPrice`/`CostEstimate`/`estimateCost` 都在这 |
| `model/ChatMode.kt` | `ChatMode`/`MessageRole` |
| `model/UserUtterance.kt` | 用户那句是"说话/动作/环境"的编解码（`*…*` / `（…）`） |
| `llm/LlmClient.kt` | `LlmClient`/`LlmRequest`/`LlmDelta`/`Usage` 的流式接口 |
| `llm/SseFrameParser.kt` | SSE 分帧（纯函数，好测） |
| `llm/LlmError.kt` | 错误分类与用户可读建议 |
| `log/Log.kt` | 内存日志、`body()` 长正文、导出 |
| `persona/PersonaDocument.kt` | 人物卡文档解析/导出/`applyTo`（导入时非破坏性覆盖） |

</details>

<details>
<summary>app（34 个文件）</summary>

| 文件 | 职责 |
|---|---|
| `SoloChatApp.kt` | 手写 DI 根 + 启动顺序（日志→收尾→补出厂卡→常驻/日志服务→读设置） |
| `MainActivity.kt` | 唯一 Activity，NavHost（7 目的地）；自行处理 uiMode 时重新配置 edge-to-edge 系统栏 |
| `data/db/*` | 11 表 / v6 追加迁移 / `RoomStore`（含历史修复、调用记账与统计事务） |
| `data/Mappers.kt` | 表↔模型；**未知枚举兜底**（mode→CHAT、delivery→INTERRUPTED、kind→SAY、role→USER） |
| `data/SettingsStore.kt` | DataStore（`Keys` 里 20+ 个设置键 + 全部节奏参数的键）、`Snapshot`、旧值一次性迁移、按 SettingsSection 校验与保存；内部构造可注入 DataStore 供 JVM 测试 |
| `data/SecretStore.kt` | 加密存储（API Key / 搜索 Key / 网页密码）+ `degraded` 标记 + `masked()` |
| `data/llm/OpenAiCompatClient.kt` | 流式 chat/completions、`/models`、`ping` |
| `data/logweb/LogWebServer.kt` | 日志网页 + 测试接口（8 条路由）与鉴权 |
| `data/logweb/LogViewerHtml.kt` | 内联单页 HTML（日志查看/复制/清空） |
| `data/logweb/ResponseJson.kt` | **手写但集中的** JSON 编码（所有接口出口都走它） |
| `data/logweb/TestGateway.kt` | 同步跑完一轮：发消息 / 取历史 / 取消息表 |
| `data/pricing/ModelPricing.kt` | 内置价格表加载与匹配（精确 id，再剥快照后缀） |
| `data/prompt/AssetPromptLoader.kt` | 读 `prompts/*.txt`（带兜底） |
| `data/prompt/PersonaDefaults.kt` | 两张出厂人物卡（硬编码；`assets/data/persona-templates.json` 当前没被读） |
| `data/persona/PersonaImporter.kt` | 按 URI 读人物卡文档（≤256k 字符） |
| `data/AppLogSink.kt` | 日志转 logcat |
| `play/SessionController.kt` | 会话编排总控（防抖/认领/落盘/回合/播出/打断/手动两段/时间轴修复） |
| `play/ChatProjector.kt` | 事件+消息 → `ChatItem` 投影（+ 活气泡交接用的 eventIds） |
| `play/TurnHost.kt` | 控制器对外的依赖接口（可测性的关键） |
| `service/AgentForegroundService.kt` | 常驻 + 60s 节拍（只改通知；无界面入口） |
| `ui/chat/ChatScreen.kt` | 时间轴 + 输入区 + 顶栏 + 模式确认卡 |
| `ui/chat/ConversationSettingsScreen.kt` | 会话信息、改名/删除确认、人物卡管理入口、后续能力准备状态；查看覆盖页保持聊天控制器存活，删除成功后离开 |
| `ui/CompactTopBar.kt` | 主页/聊天/会话设置/全局设置共用的 48dp 最小标题区、状态栏避让与分隔线 |
| `ui/ChatRender.kt` | `ChatItem` → Compose 的具体画法（含时间戳、逐字显示） |
| `ui/conversations/ConversationListScreen.kt` | 会话列表（新建时先选人设；重命名/换人设/删除） |
| `ui/persona/PersonaScreen.kt` | 人物卡列表 + 编辑器（15 个存取字段）+ 导入/导出 |
| `ui/memory/MemoryScreen.kt` | 记忆增删改、固定、按归属筛选 |
| `ui/usage/StatisticsScreen.kt`、`StatisticsContent.kt` | 全局与会话统计、当前上下文、最近调用、冻结费用；全局按模型汇总 |
| `ui/settings/SettingsScreen.kt` | 主菜单 HUB + API/PACING/PRICE/BEHAVIOR/WEB/DIAG；按页保存，离页可保存/放弃/继续；节奏默认四项，展开显示全部 20 项 |
| `ui/logs/LogScreen.kt` | 日志筛选/复制/清空 |
| `ui/theme/Theme.kt` | 颜色槽位（`ChatColors`/`ChatDimens`）与配色纪律 |
| `ui/PersonaLabel.kt` | "这个会话在用谁"的展示（与 `resolvePersona` 同一份判定） |

</details>

## 10. 结构性已知问题（细节见 [HANDOFF.md](HANDOFF.md)）

- `ChatConfig.modeSwitchPolicy` **没有消费者**（设置页也只写布尔 `allowModeSwitch`）。
- `PromptContext.spokenEvents` **只写不读**。
- `assets/data/search-providers.json` 与 `thinking-dialects.json` **全仓库没有读取点**
  （搜索未接线）；`persona-templates.json` 也只有注释提到。
- `AgentForegroundService` **没有界面入口**（`setKeepAlive` 无调用者），内部用硬编码的
  60 秒节拍与 30 分钟静默阈值、不读设置里的 `proactive_after_ms` / 冷却 / 免打扰。
- 一批**注释与代码不符**（一次性核对出 20 处），清单在 [HANDOFF.md](HANDOFF.md) §4.1。
