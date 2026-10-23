# SoloChat 交接文档

写给**接手这个项目的人**。假设你会 Kotlin / Compose / 协程，也读过 `README.md`，
所以这里不重复"怎么构建""功能有哪些"，只写三件事：

1. 现在到底处于什么状态（哪些真机验证过、哪些没有）
2. **改动这个代码库之前必须知道的几条不变式** —— 每一条都是我踩过一次真 bug 换来的
3. 剩下的活怎么接、怎么在真机上取证

> **当前入口（2026-10-23）**：状态与下一步见 [HANDOFF](HANDOFF.md)/[11](11-next-goals.md)，实际验证见 [04](04-changes.md)。当前 Room 为 v7；本机预览和发行均用 app.solochat + 同一发布签名。
> 本文件保留不变式、故障经验与取证方法。2026-10-10 的 v6、364 个测试和当次调试包装机是历史记录，不是当前版本或今日结果。
> 两处状态不一致时，先核对日期与当前源码；不得将历史测试、局部实机结果扩大为本轮已通过。
> v3→v4 / v4→v5 的实机迁移未补验，只有历史离线 SQL 核对；后续装机不得静默清库规避。


---

## 1. 当前状态

### 已经真机验证过的

| 能力 | 取证方式 |
|---|---|
| 2026-10-23 H2 历史分页与阅读位置 | 新建 TEST-HISTORY-H2-1023，160 条合成系统记录：首次 80 条、前页后 160 条，H2-081 上屏位置前后相同；追加第 161 条只提示新内容、位置不变，返回最新为 80 条并显示 H2-161。无模型调用，播出并发由 JVM 定向用例覆盖；详见 04 |
| 一轮里逐条气泡按节奏播出 | 真机日志的播出时间线（阅读 → 打字 → 播出 → 间隔） |
| 每播完一条就留在屏幕上 | 播出时间线 + 每条落定时间不同 |
| 模型能看到自己说过的话 | 提示词日志里的 `我：…` 行 + `历史构成: 对方 N 条 / 我 M 条` |
| 插话打断：未播的取消、说了一半的保留 | 真机日志 + `会话编排` 的"插话打断"一条 |
| 重启不产生幽灵消息 | 启动日志"清理残留未播出事件 0 条" |
| 时间戳按交付时刻、逐条不同 | `/api/history` 返回的 `time` 字段 |
| 历史**全量**注入（不设条数上限） | 提示词日志的完整正文 + `usage` 表：153 条的会话输入 8.0k token、命中 7.6k（94%）；`LIMIT -1` 另在真数据上核过（返回全部 153 条） |
| 提示词缓存命中 | `usage.cachedTokens`，两台口径一起看：`输入 5.3k / 缓存 4.1k`（旧 40 条窗口）→ `输入 8.0k / 缓存 7.6k`（全量）；分叉点用「连续两轮提示词逐字节比」定位，见 §5 |
| 模型输出写坏不再赔掉整轮 | 真机那一轮的「阶段: 解析」`事件数 9 / 降级 false`；另外 27 份历史上"降级过"的原文全量回归（`tools/dump-degraded.ps1` + `RealOutputCorpusTest`，27/27 救回） |
| 2026-10-10 阶段 1 界面与设置 | 专用新会话 TEST-UI-Phase1-1010；设置返回保留草稿、分组保存/放弃草稿、明暗与大字体；完整条件和回合取证见 04-changes.md 最新条目 |
| 2026-10-10 阶段 2 模型与统计 | 本轮新建 TEST-MODEL-A-1010 / B-1010；不同模型成功、无效模型失败、逐字段统计、模型隔离与全局汇总；JVM 与真机各自边界见 04-changes.md |
| 2026-10-10 阶段 3 手动压缩与 D10 | 本轮新建 TEST-COMPRESS-1010 / TEST-COMPRESS-CN-1010；三种方式、草稿手工修改/应用/恢复、摘要失败账目、中文事实回忆、实际请求前缀逐字节比与原数据核对；长场景/百轮未验，见 04-changes.md |
| v5→v6 追加迁移 | 实际升级后 Room 正常打开；升级前后与最终副本逐列核对原 4 会话/423 消息/537 事件/142 用量未变，DataStore/shared_prefs 字节一致；离线 SQL 对照导出 schema |

### 没有验证过的（重要）

- **整体动画与长期观感**。已有局部截图、UI 层级与交互检查，不等于完整观感验收；日志/纯函数结果也不能替代手机上的节奏与布局体验。
- **面对面模式**。协议、渲染、模式切换都实现了，但没有一段真实的长场景跑过。
- **主动消息**。只做到"检测到很久没动静 → 改通知文案"，不会真的发消息。
- **长会话**（>100 轮）。历史截断、记忆注入预算、摘要压缩都没在真实长度上压过。
- **人物卡文件导入**。解析器有 40 条测试，但**没有用真实文件管理器选过文件** ——
  SAF 的 mime 过滤、`OpenableColumns.DISPLAY_NAME` 在不同 ROM 上是否拿得到、
  中文文件名会不会出问题，这些都只能上真机点一遍。
  （能离线验的部分已验：`PersonaDocument.parse` 是纯函数，测试覆盖了各种排版。）
- **v3 → v4 / v4 → v5 两条迁移没在真机上装过包**。SQL 已拿真机库离线跑过两条升级路径
  （v3→v5 与 v4→v5：列定义、行数、既有内容、`integrity_check` 都比过），
  Room 那一层的 schema 校验与版本推进只能装机才看得到。
  装完请确认"升级后 App 能正常打开"。
- **人设按模式注入的观感**。测试钉住了"聊天模式不含面对面那几段"，但
  "面对面时人物是不是真的更有样子了"只能靠人读几段真实场景。

---

## 2. 上手路径（按这个顺序读）

```
core/model/Event.kt            事件与播出状态的定义 —— 整个系统的中枢接口
  ↓
core/protocol/ScriptParser.kt  模型输出 → 事件（永不抛异常，三条降级路径）
core/playback/Player.kt        纯计算：一次播出 = 一条状态序列（可穷举测试）
  ↓
agent/AgentTurnRunner.kt       一轮流水线：装配 → 调用 → 解析 → 落盘
  ↓
app/play/SessionController.kt  会话编排核心：防抖 / 抢占 / 播放 / 落盘
app/play/ChatProjector.kt      "什么该画到屏幕上" 的纯函数
  ↓
ui/chat/ChatScreen.kt          渲染 + 逐字动画 + 滚动
```

`SessionController` 是全项目最容易写错的地方，建议先读它的类注释，
再看 §3 的十条不变式。

**每一处已经落地的改动**（动了哪几行、依据是什么、拿什么证明它成了）记在
[04-changes.md](04-changes.md)，最新的在最上面。动手改之前扫一眼，能省掉重新推理一遍的功夫。

---

## 3. 改代码前必须知道的十条不变式

这十条都是**踩过 bug 之后固化下来的**。看着像可以简化的地方，其实每一处都对应一个
真实故障。想动它们之前，先看对应的测试 —— 那些测试是故意写得那么细的。

### ① 界面只认"出口过"的事件

```kotlin
// core/model/Timeline.kt：timelineEntries（ChatProjector 复用这一规则）
events.filter { it.kind.isVisible && it.delivery.spoken }
```

`spoken = DELIVERED || INTERRUPTED`。**`PENDING` 一条都不许画。**

> 违反的后果：助手事件在"模型返回后、播出前"就以 `PENDING` 落盘，于是它们被当成历史
> 画出来 —— 屏幕上先冒出两条带时间戳的气泡，紧接着活气泡又从第一条开始播。
> 这就是用户报的"先一次性输出两条，然后又播第一条动画"。
>
> 判据必须用 `delivery.spoken`，**不能用 `deliveredAt != null`**：后者是引擎字段，
> 谁顺手填一下就能让没出口的事件混进历史。回归测试：`ChatProjectorTest`。

### ② 播出期间不许 `reload` 整个时间轴，但每条播完必须刷

```kotlin
// SessionController.runPlayback，每次 Delivering 结束时
_state.value = _state.value.copy(live = null)
reloadNow()
```

以及 `reload()` 在演出期间会**让路并记账**（`reloadRequestedWhilePlaying`）。

> 违反的后果（两个方向都踩过）：
> - **不让路**：演出期间 `reload` 和演出交错，某条内容既在 `items` 里、又是当前活气泡，
>   屏幕上同一条画两次（真机截图抓到过，只存在一两帧）。
> - **让路但不补记账**：回合结束时的 `reload` 与演出收尾并发，被丢掉的刷新没人补做 ——
>   库里已有的回复**永远画不出来**（回归测试就是这么超时的）。
> - **只在收尾刷一次**：就是用户报的"逐条覆盖" —— 演出期间已播完的条目在 `items` 里
>   没有位置，屏幕上只剩当前活气泡，新的一条把上一条顶掉，直到整场演完才一起出现。

### ③ 一件事只能有一个写入者

| 谁写 | 写什么 |
|---|---|
| `SessionController` | 用户消息（认领批次时）、assistant 历史（播出落定后） |
| `AgentTurnRunner` | **只产出事件，不写消息** |

> 违反的后果：两处都写 → 用户消息成对出现（第一版的 bug）。
> `AgentTurnRunner` 曾经写 assistant 历史，而且**从来没成功过**（见 §4 第 1 条）。

### ④ 判断"说过没有"要读存储，不要读手里的引用

```kotlin
// persistSpokenAssistantMessage
val latest = store.eventsOf(conversationId).associateBy { it.id }
val spoken = events.mapNotNull { latest[it.id] ?: it }   // ← 必须重读
```

`play.events` 是**开播前的快照**，`delivery` 全是 `PENDING`；播出状态的变化写在 store 那侧，
不会回填这份快照。拿快照去过滤，结果恒为空 —— 而且**不报任何错**。

> 同一个坑我踩了两次（一次在 `AgentTurnRunner`，一次在 `SessionController`）。
> 第二次差点又没发现：函数被正常调用了、日志也打了，只是写出来是空的。
> 所以 `persistSpokenAssistantMessage` 在写成功时会打一条 `阶段: 写助手历史` ——
> **日志里看不到这条，就说明它什么都没写。**

### ⑤ 时间戳取"事件出现的那一刻"，不是"处理它的那一刻"

```kotlin
play.startedAt[event.id] = clock.now()   // 进入 Delivering 时记
```

交付时间在**气泡出现**时记下，后面正常播完和被打断都复用它；`markDelivered` 与
`markInterrupted` 都从这个 map 取值。

> 违反的后果：一条 16:34:27 就已经显示给用户的气泡，被打断后时间戳变成 16:34:31 ——
> 界面上它**往下跳**了，跑到用户刚发的消息下面。用户看到的是"顺序乱了"。

### ⑥ 时钟一律走 `host.deps.clock`，不要 `System.currentTimeMillis()`

不只是为了测试。交付时间决定气泡在时间轴上的位置，它必须和防抖、回合标记用**同一个时钟**；
两边各拿各的系统时间，早晚出现"事件比它所属的回合还早"这种矛盾。

### ⑦ 「这个会话现在是谁」只能有一份判定

```kotlin
// core/model/Conversation.kt
fun resolvePersona(personas: List<Persona>, personaId: String?): Persona?
```

规则是只按 `conversation.personaId` 精确匹配；空值、已删除或不存在的卡返回空，不自动换成小默或其他人物。
新建会话必须明确选择人物。旧会话缺少有效关联时提示用户重新选择，并阻止生成；已有明确关联（包括旧 `default` 卡）的会话继续使用原卡，不改历史归属。
解析只能有这一份：上下文、聊天标题、列表与调试入口共用，避免显示的人物与模型读取的人物不同。回归测试见 `PersonaResolveTest`。

显示文本也只有一份（`app/ui/PersonaLabel.kt` 的 `personaLabel`），会话列表与标题栏共用。

### ⑧ 人设是**八段**，按模式注入，而且**素材与笔法要分清**

`Persona.toPromptBlock(mode)` 只把该给这个模式的段落拼进提示词：
`sceneVoice`（面对面时怎么说话）、`surroundings`（它身边的样子）、
`depiction`（怎么描写动作与景物）**只在面对面注入**，其余五段两种模式都给。

这三段的分工是这个功能最容易做糊的地方，改之前先分清：

| 段 | 回答的问题 |
|---|---|
| `mannerisms` / `surroundings` | **写什么**（动作素材 / 景物素材） |
| `depiction` | **写成什么样**（偏听觉还是偏视觉、长短、用不用比喻） |
| `20-style-scene.txt` | **不许写成什么样**（不写内心独白、不堆排比、不写成小说）—— 所有角色共用的底线 |

> 为什么会写错：这三段都是"面对面专用"，很容易被当成一回事合掉。
> 合掉的后果是**所有角色被写成同一种腔调** —— 而那是这一类产品最容易被察觉的假。
>
> 反过来，面对面模式只有 `act` / `scene` 两种表达手段：没有素材，模型只能每轮
> 自己发明一个动作（表现是"这个人每轮都做不一样的小动作，不像一个人"）；
> 在发消息模式里给了这些素材与笔法，它就会开始写小作文 ——
> 而"像在发微信"恰恰要求它别这么做。
>
> 另一条：**空段落不产生空标题**（`section()` 里 `if (body.isBlank()) return`）。
> 留一个只有标题没有正文的段落，会让模型觉得"这里应该有内容"于是自己编一段补上 ——
> 比不写更糟。老卡（v3 → v4 / v4 → v5 之前建的）上这几段一定是空的，所以这条很常触发。
> 回归测试：`PromptBuilderTest` 的 `面对面才注入当面说话与环境素材` /
> `空的人设段落不产生空标题`。
>
> ⚠️ 动这几段就是动**稳定前缀**（人设块在安全规则之后、协议之前）。改了之后
> KV 缓存会从头再来一轮 —— 这是预期内的，但别在没理由的时候改。
>
> ⚠️ **界面文案里不用第三人称性别代词**（不写"她/他"）：一张人物卡可能属于任何人，
> 而这一页是给所有卡用的。人名可以随便，代词不行。
> 反过来，**别名表里必须收着「她/他/它」三种写法**（`她身边的样子`、`只有她知道的事`）——
> 那是为了认得出别人写的文档，不是这个 App 在假定性别。
>
> 还有一条平行的纪律：**条数上限只写在 `10-protocol-*.txt` 里**，风格文件里出现任何
> "N 条"都算回归（见 04-changes.md「条数上限：三处数字打架 → 收成一处」）。
> 所以往 `20-style-*.txt` 加约束时，**不要写数字**。

### ⑨ 每一页的 inset 都是它自己的事，而且两种写法不能混

`MainActivity` 调了 `enableEdgeToEdge()`，它内部会把 `decorFitsSystemWindows` 设成 false ——
也就是**窗口不再自动为键盘让位**，IME 从此是一份必须由我们自己消费的 inset。
而 `Scaffold` 默认的 `contentWindowInsets` 是 systemBars，**不含 IME**。所以：

| 页面形态 | 正确写法 |
|---|---|
| 不可滚动的容器 | `.padding(padding)` → `.consumeWindowInsets(padding)` → `.imePadding()` |
| 列表（`LazyColumn`/`LazyRow`） | inset 走 **`contentPadding`**，`consumeWindowInsets(padding)` 打在 modifier 上 |

两条硬规矩：

1. **`consumeWindowInsets` 必须在 `imePadding()` 之前。** `Scaffold` 给的底部 inset 已经含导航栏，
   而 `imePadding()` 读到的 `WindowInsets.ime` **也含导航栏**；`Modifier.padding()` 只是画内边距，
   **不会**声明"这些 inset 我用掉了"。少这一句，键盘弹出时底部就被算两遍，输入框被顶到半空。
2. **列表的 inset 不要打成 `Modifier.padding`。** 那会把列表视口本身缩进系统栏以内，
   内容再也滚不到状态栏/导航栏后面 —— 等于白做了 edge-to-edge。

另外：**FAB 不挡列表是列表自己的责任**，`contentPadding` 底部要额外留出 FAB 的空档（约 88dp），
`Scaffold` 不会替它挡。

> 违反的后果（这一轮一次性全踩到了）：输入框被顶到半空、键盘把"保存/取消"压在下面点不到、
> 最后一行被 FAB 盖住、日志流永远滚不到导航栏之后。

### ⑩ `Theme.kt` 的配色槽位：要么显式给，要么别用

Material3 的组件靠 `contentColorFor(containerColor)` 在 `colorScheme` 里**反查**配对的文字色。
槽位缺一个，查不到就落回 M3 baseline（一套偏紫的中性色），于是出现"底色换了、文字色还是旧的"
这种对比度塌陷，或者"弹层跟页面不是一套色"。

实际踩到的三处：`tertiary`（日志页的 WARN 标签）、`errorContainer`/`onErrorContainer`
（设置页的警告卡）、以及 **`surfaceContainer*`** —— 所有 `AlertDialog` / `DropdownMenu`
内部都用 `surfaceContainerHigh`，不定义的话弹层是另一个色系，看起来像另一个 App。

> 规矩：用任何 `colorScheme.xxx` 之前先 grep 一遍 `Theme.kt`；
> 明暗两套**必须一一对应**，只补一个模式 = 只有那个模式难看。

---

## 4. 已经踩过、换个人一定会再踩的坑

按"如果重来会省多少时间"排序。

### 1. `AgentTurnRunner` 里那段写助手历史的代码从来没执行过

```kotlin
// 旧代码 —— appendMessage 一次都没跑过
val assistantText = PromptBuilder.eventsToAssistantText(persisted)
if (assistantText.isNotBlank()) { deps.storage.appendMessage(...) }
```

`eventsToAssistantText` 只收已出口的事件，而那一刻全是 `PENDING` → 恒为空。

**为什么极难发现**：界面上完全正常（时间轴走"事件"那条路，对方说过的话一条不少）。
只有 `/api/messages` 才看得出来 —— 消息表里**只有用户的话**。
后果是模型侧上下文里只有对方的发言，它把自己上一轮答过的内容当新话题又答一遍。

> 教训：**"界面上看起来对"和"模型看到了什么"是两件事**，必须分开取证。
> 为此加了 `/api/messages` 和日志里的 `历史构成: 对方 N 条 / 我 M 条`。

### 2. `INTERRUPTED` 与 `CANCELED` 混用 → 重启后凭空多出 7 条消息

`INTERRUPTED` 的语义是**说过**（说了一半）。进程重启时把残留的 `PENDING` 全标成
`INTERRUPTED`，于是从没播出去的消息全部冒出来。必须标 `CANCELED`。

> 教训：`delivery` 是**领域状态**，不是"技术上算完了没有"。为了修观感而提前落盘，
> 动到的就是领域语义 —— 凡是从"界面好看"出发的改动，回头都要查有没有碰数据含义。

### 3. 测试替身没改事件本身 → 骗过三轮调试

```kotlin
// 错：只记一张旁表
override suspend fun setDelivery(id: Long, delivery: DeliveryState) {
    deliveryStates[id] = delivery
}
```

投影和幂等判断读的都是**事件本身**，于是假替身让"取消"看起来生效了、实际事件还是 `PENDING`。
我为此在一段"逐条覆盖"的排查里连错三轮。

> 教训：替身必须和真实现**同构**到"读它的人看不出区别"。只记旁表不算实现。

### 4. 测试里 `advanceUntilIdle()` 推不动 `backgroundScope`

实测：同一个测试里 `this` 跑了 `[0,1,2]` 而 `backgroundScope` 是 `[]`。
用它录演出帧 → 录到 0 帧 → 断言因为"什么都没看到"而**假绿**（比失败更糟）。

同理，`withTimeout` 走的是**虚拟**时钟，而 `markDelivered` 这类挂起调用会把协程短暂带离
`TestCoroutineScheduler` —— 想等"真实调度器上那几步跑完"，虚拟时间一秒都不会流逝，必然超时。

现用的写法见 `SessionControllerTest.awaitSettled`：推进虚拟时间 + 真睡一小会儿，轮询可判定的终态。

### 5. 手写 JSON 已经栽过两次

一次 emoji 全变成 `�`（转义函数把配对的代理项也替换了），一次 `bubbles` 数组少了 `]`。
**任何要拼 JSON 的地方都走 `ResponseJson.kt`**，它全是纯函数，有 14 条用真解析器验证的往返测试。

### 6. Compose 组件走 `clickable` 而不是 `IconButton`？

`TopAppBar` 的 `actions` 里如果同时有菜单和"打快照"的需求，别叠两个可点区域 —— 实测
菜单弹不出来。现在"打屏幕快照"挂在**标题栏文字**上（`Modifier.clickable`），不在 `actions` 里。

### 7. material3 1.3.1 的 `TextField` 没有 `contentPadding`

那是 `BasicTextField` 的参数。想压输入框高度只能用 `Modifier.heightIn`。
（我照着印象写了一次，编译直接报 `Unresolved reference`。）

### 8. `adb shell input text` 不支持中文

报 `NullPointerException: Attempt to get length of null array`，只能发 ASCII。
模拟输入做上下文测试时，用英文加拼音（`mod ok le` / `women ba dian jian`），
模型照样能接住上下文。

### 9. "输出没解析出来"在界面上是**一屏原始 JSON**，不是空白

模型的 JSON 只要有一个字符不合法，`ScriptParser` 就整体降级：**整段原文被当成一条 `say`**
发出去。所以在界面上看到"一条气泡里全是 `{"events":[…]}`"，那不是模型在建 JSON 玩，
而是解析失败了 —— 而**界面投影与日志都看不出"失败"这件事**（它是一条正常的 assistant 消息）。

> **2026-10-08 之后**：真机上那 27 条已经还原（时间轴里是正常的气泡/动作/旁白，
> 见 04-changes「时间轴恢复」）。但**`message` 表里那 27 条原始输出是刻意留着的**
> （复盘用），所以下面这个查询仍然会命中它们 —— 命中不等于"时间轴还是坏的"，
> 要看的是 `event` 表：
> ```sql
> SELECT COUNT(*) FROM event WHERE kind='say' AND text LIKE '{%';   -- 应当是 0
> ```

一眼查出历史上发生过多少次（走测试网关的消息表，别看界面）：

```powershell
$m = Invoke-RestMethod "http://127.0.0.1:8787/api/messages?t=$env:SOLOCHAT_LOG_PASSWORD&id=<会话id>&limit=400"
$m.items | Where-Object { $_.role -eq 'assistant' -and $_.text.TrimStart().StartsWith('{') } |
    ForEach-Object { "id=$($_.id) $($_.time) len=$($_.text.Length)" }
```

这些正文就是**真机语料**：`tools/dump-degraded.ps1` 会把它们拉到 `.tmp/degraded/`，
`:core:test` 里的 `RealOutputCorpusTest` 再逐条喂给解析器（语料不在版本库里，
目录不存在时该测试自动跳过）。**"跳过"不等于"通过"** —— 看这一次 BUILD 里它是
`PASSED` 还是 `SKIPPED`；另外 ⚠️ 语料文件不在 Gradle 的输入里，所以加了新语料之后
`:core:test` 可能被判 UP-TO-DATE 而**根本不重跑**，要 `--tests "*RealOutputCorpusTest*"`
或 `--rerun-tasks` 逼它跑一次。

日志侧的判据是 `模型` tag 的「阶段: 解析」那一条：`降级=true` 就是 WARN，
`事件构成` / `丢弃` 也在同一行里。

⚠️ **修好解析不等于修好行为 —— 历史会把错误教回去。**
实测（2026-10-08）：提示词改好、解析也修好之后，模型**照样**写
`{ "type": "typing_ms": 1600 }`。原因是历史里躺着它自己以前 11 次这样的输出
（降级成 `say` 之后原样进了消息表）。**范例比规则硬**，而历史不设上限（D10），
这些块不会自己滚出去。所以 `PromptBuilder.renderHistory` 会先把"整段 JSON"的历史
还原成正常几行再喂进去（见 04-changes 对应那一节）。
**下次遇到"提示词明明写清楚了它还是照错"，先数一遍历史里有没有这种范例。**

---

## 5. 真机取证：这台机器上能做什么、不能做什么

### 能做的

```powershell
# 编译检查使用 tools/build.ps1；需要装机时走同发布签名预览。
# 先读取当前安装版本码，再替换下方占位值，不能盲用旧记录。
pwsh tools/build-preview.ps1 -VersionCode <当前安装版本码> -VersionName 0.1.0-local
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am start -n app.solochat/app.solochat.MainActivity

# 日志网页（设置 → 排查 → 开启日志网页）。
# ⚠️ 密码不要写进文档/脚本：用环境变量或 secrets.properties（见 tools/logweb-auth.ps1）
$env:SOLOCHAT_LOG_PASSWORD = '<密码>'
curl "http://<手机地址>:8787/api/logs?t=$env:SOLOCHAT_LOG_PASSWORD"
curl "http://<手机地址>:8787/api/messages?id=<会话id>"    # 消息表 = 模型看到的
curl "http://<手机地址>:8787/api/history?id=<会话id>"     # 完整历史投影；不等于 H2 当前页面窗口

# 手机 IP 变了 / 只连了 USB 时，用端口转发，别去猜 IP
adb forward tcp:8787 tcp:8787
curl "http://127.0.0.1:8787/api/health"
```

### 看"KV 缓存到底能命中到哪里"（提示词顺序的唯一判据）

日志里**存着完整提示词**（`Log.body`）。上限 2026-10-08 从 2 万字符抬到 **6.4 万**
（`Log.MAX_TEXT`）—— 因为历史不设上限之后提示词已经到 3.7 万，原来那条"完整提示词"
其实只记了前一半，而被截掉的正好是**历史 + 变动区**（提示词尾部），
KV 那套"逐字节比两轮"的手段在尾巴上是失效的。环形缓冲同时从 600 条收到 300 条抵内存。

所以不用猜、
也不用自己算 token —— 把**连续两轮**的提示词拉下来逐字节比，第一个不同的字节就是
缓存失效点：

```powershell
$r = Invoke-RestMethod "http://127.0.0.1:8787/api/logs?t=$env:SOLOCHAT_LOG_PASSWORD&since=0"
$r.entries | Where-Object { $_.text -like '完整提示词*' } | ConvertTo-Json -Depth 5 | Set-Content logs.json
# 然后（Python）逐条比：共同前缀多长、分叉点落在哪一段
```

⚠️ 比之前要先**剥掉日志头** `完整提示词（N 字符）:\n` —— 它自己每轮都不一样，
留着看会让共同前缀永远是 0。

配合账单侧的 `usage` 表（服务商返回的真实数字，不是估算）：

```sql
SELECT at, promptTokens, cachedTokens FROM usage ORDER BY at DESC LIMIT 20;
```

**两个口径要一起看**：字符层面的分叉点说明"为什么"，`cachedTokens` 说明"值多少"。
只改提示词顺序不发消息时，两者都能立刻反映出来。

### 测试范围与重跑

按 [README「测试」](../README.md#测试) 选择相关套件，不将全量或强制重跑作为日常流程。定向测试后，输出目录可能只有部分结果或保留旧文件，不能累计 XML 当作全仓总数。仅在排查缓存或确需重新执行时用 `--rerun-tasks`，不为记录数量重跑。

### `kotlinx.serialization` 的 lenient 到底认什么（实测表）

改 `ScriptParser` 之前先看这张表：**别照印象写注释**（我原先写着"单引号也见过"，是错的）。
表里的"认/不认"是拿真解析器跑出来的（探针脚本见 04-changes 的解析那一节）：

| 形态 | `isLenient` |
|---|---|
| 键和值不带引号 `{type:say,text:你好}` | **认** |
| 尾逗号 `{"a":1,}` | **认**（还开了 `allowTrailingComma`） |
| 正文里有真换行 / tab / 控制字符 | **认**（原样读进来） |
| 单引号 `{'a':'b'}` | **不认** ← 交给 `normalize` |
| 弯引号 `{“a”:“b”}` | **不认** ← 交给 `normalize` |
| 全角冒号 / 全角逗号 | **不认** ← 交给 `normalize` |
| 值后面多一个冒号 `{"type":"typing_ms":2500}` | **不认** ← 交给 `normalize` |
| 少一个逗号 | **不认** ← 交给 `normalize` |
| 字符串里有**未转义的** `"` | **不认，且不修**（猜等于编内容，见 D11） |

`isLenient` 唯一放松的是"引号可以省"，**没有**放松引号种类与标点。
`normalize` 是逐字符状态机：字符串**里面**一律不动，所以 `"text":"他说“嗯”"` 里的
中文引号仍然是正文。

### 动过历史数据？拿"修之前/修之后两份库"逐字核

改用户数据（迁移、修复、清理）之后**不要靠界面确认**。拉两份库副本比：

```powershell
adb exec-out run-as app.solochat.debug cat databases/solochat.db     > .tmp\db\before.db
adb exec-out run-as app.solochat.debug cat databases/solochat.db-wal > .tmp\db\before.db-wal
adb exec-out run-as app.solochat.debug cat databases/solochat.db-shm > .tmp\db\before.db-shm
# …在设备上跑那次修改…
# 再同样拉一份 after.db（三个文件都要：WAL 里有还没落盘的内容，只拉主库会读到旧数据）
python .tmp\db\verify_timeline_repair.py .tmp\db\before.db .tmp\db\after.db
```

两条纪律（都踩过）：

1. **判据要与实现无关**。时间轴那次是从原文里按正则抠出 `text` 字段、还原转义，
   再和库里新行的正文按顺序逐字比 —— 而不是"再跑一遍我的解析器看结果一样"。
2. ⚠️ **两次快照之间世界会变**：用户完全可能又聊了一轮。第一版比整表指纹，
   于是那次新对话被报成"4 个表被动过"。正确判据是"**快照里已经存在的每一行**按主键逐行比，
   改动 0、消失 0"，新行只报个数。

### 历史设备限制（不是固定环境结论）

2026-10-08 曾遇到 screenrecord 无输出，以及 uiautomator dump 来不及捕捉逐条播出的瞬间。这些是当次设备/权限/时间分辨率限制，不能写成永久不可用。

后续已实际使用截图和 UI 层级核对局部页面与 H2 锚点。每次先查看当前连接和能力，只选择能验证本次改动的手段；瞬时播出可结合状态/日志，不为文档整理重试录屏或增加探针。

### 替代手段：抓状态，不抓像素

状态是**同步写**的，比抓屏幕准。日志服务开着时（= 调试状态），
**点一下聊天页标题栏**，就会往日志里打一份 `屏幕快照`：

```
阶段: 屏幕快照
历史(items): 38 条：… / women ba dian / 行，八点 / 我先去吃饭了，到了喊我
正在播(live): 无
待处理(pending): 0 条
```

判据：**正在播第 2 条时，`items` 里应当已经有第 1 条**。
如果 `items` 是空的、只有 `live`，那就是"逐条覆盖"。

配套脚本：`tools/send-message.ps1`（**每次重新查坐标** —— 键盘弹出来会把布局顶上去，
写死的坐标必点飞）、`tools/snapshot-at-playback.ps1`。

---

## 6. 下一步（按优先级）

当前顺序为 **H3 完整历史搜索→H4 定位→多组接口配置**，范围、起点、验收与阶段提交见 [11](11-next-goals.md)。U1～U3、H1/H2、备份接入及 UI 已实施项不重复开发。

百轮长会话/连续长场景、完整跨安装恢复与系统安装提交保留为独立验证欠项；按具体风险与专用资料安排，不成为每轮全量验证门槛。局域网网页、主动消息与群聊先明确边界，不能绕过唯一会话实例、重复计费或自动收费。

局部 UI 改进不授权更换整体视觉风格；时间戳每条显示、发送键与输入框等高等既有取舍，涉及变更时先核对当前用户要求与源码。choice 与图像生成继续沿用既有暂缓决策，不因交接整理扩大范围。

## 7. 环境约束（换机器会全部重遇）

这四条都固化在 `tools/build.ps1` 顶部注释里，日常构建直接跑它：

1. **只有 JDK 21**。模块声明 `jvmToolchain(17)` 会直接失败 → 改成"用当前 JDK、字节码目标 17"。
2. **沙箱不让写用户主目录**。`GRADLE_USER_HOME` 指到工作区内；debug 签名密钥必须靠
   `ANDROID_PREFS_ROOT` 改位置（`-Duser.home` 经 Gradle 守护进程会丢，**实测无效**）。
3. **命名管道被禁** → `kotlin.compiler.execution.strategy=in-process`。
4. **PowerShell 出不了网**（`SEC_E_NO_CREDENTIALS` / SSL 建连失败），**Node 的 https 正常**，
   所以下载统一走 `tools/download-gradle.mjs`。

另外：**`.bat` 必须纯 ASCII**（cmd 在中文 Windows 上按 GBK 解析，UTF-8 中文注释会吞掉换行）。

---

## 8. 按影响范围检查

测试命令统一见 [README「测试」](../README.md#测试)，不再维护每次交付必跑的全量清单。按本次影响选择：

| 修改范围 | 重点 |
|---|---|
| 历史提示词装配 | `PromptBuilderTest` 中历史还原、模式注入和稳定前缀的相关用例 |
| 界面投影 | `ChatProjectorTest` 中未出口不可见、打断与交付时间的相关用例 |
| 会话控制 | `SessionControllerTest` 中单次认领、插话和逐条保留的相关用例 |
| HTTP JSON | 相关解析/响应测试，序列化复用现有实现 |
| 布局、输入框 | 正常字体检查本次交互；键盘避让遵守 §3 ⑨。颜色变化才补明暗检查，不默认大字体遍历 |
| 迁移、修复、清理已有资料 | 按 §5 核对原记录；日常文案、价格设置等无关改动不拉库 |

§3 架构不变式仍须遵守。回归测试应对应具体故障或重要规则；优先复用已有用例，不为每个可逆小改动新造测试、快照或检查脚本。完成后简记改动、必要检查和未确认项即可。
