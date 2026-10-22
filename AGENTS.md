# AGENTS.md —— 给 AI Coding Agent 的项目入口

> 本文件是**入口与索引**，不是百科。动手前先读这里，再按"文档索引"跳到对应文件。
> 文档与代码注释一律用中文；改代码前请先读 [docs/02-handoff.md](docs/02-handoff.md) 的十条不变式。

## 1. 这是什么

**SoloChat** —— 一个**私人自用的 Android 聊天 App**：跟一个（或多个）由大模型扮演的角色对话，
追求"像在用微信跟一个人聊天"，而不是"跟助手问答"。

三个决定了整个架构的产品选择：

1. **一轮 = 一次 API 调用**，模型返回一份**带节奏的事件脚本**（JSON），不是一段文本流。
   生成与播出彻底解耦（见 [DECISIONS](docs/DECISIONS.md) D3/D4）。
2. **两种模式**：`chat`（像发消息）与 `scene`（面对面：场景 / 动作 / 对白）。
   模式是**会话上的可变属性**，不是两套会话（D7）。
3. **单机、无服务端**：API Key 存本机加密存储；日志网页 / 测试网关是 App 内嵌的 Ktor 服务（D2）。

当前状态：`0.1.0-debug`，**阶段 1～3 已实施并装真机**；手动压缩三种方式、草稿编辑/应用/恢复及 D10 重排已有取证。百轮长会话与连续长场景尚未压测，不能把短对话验收扩大为长期质量保证。
进度与待办见 [docs/HANDOFF.md](docs/HANDOFF.md)。

## 2. 技术栈

| 项 | 值 |
|---|---|
| 语言 / 构建 | Kotlin 2.1.0、Gradle（wrapper）、AGP 8.7.3、KSP |
| Android | `compileSdk`/`targetSdk` 35、`minSdk` 26；包名 `app.solochat`（debug 后缀 `.debug`） |
| UI | Jetpack Compose（BOM 2024.12.01、material3、navigation-compose） |
| 存储 | Room 2.6.1（**版本 7**，schema 导出到 `app/schemas/`）、DataStore Preferences、EncryptedSharedPreferences |
| 网络 | OkHttp 4.12（手写 SSE 解析）、Ktor 2.3.12 CIO（仅用于 App 内的日志网页/测试网关） |
| 序列化 | kotlinx-serialization-json 1.7.3 |
| 协程 | kotlinx-coroutines 1.9.0（测试用 `kotlinx-coroutines-test` 虚拟时间） |
| 测试 | JUnit 4（**纯 JVM**，无 Robolectric、无 instrumentation 用例） |
| 字节码 | Java/Kotlin 目标 **17**，用 **JDK 21** 编译 |

## 3. 模块与目录

```
core/   纯 Kotlin JVM 模块 —— **不允许出现任何 android.* 引用**
        协议解析、节奏算法、防抖、提示词装配、播放计划、端口定义
app/    Android 层 —— Room/DataStore/网络实现、会话编排、Compose 界面、前台服务、日志网页
tools/  构建与真机取证脚本（build.ps1、dump-degraded.ps1、snapshot-at-playback.ps1 …）
docs/   见下面的索引
```

更细的模块职责、数据流与数据结构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 4. 构建、测试、装机

```powershell
pwsh tools/build.ps1                       # 编译 debug APK（默认任务）
pwsh tools/build.ps1 :core:test            # 只跑纯 JVM 单测（秒级）
pwsh tools/build.ps1 test                  # 跑全部单测
pwsh tools/build.ps1 :app:assembleDebug    # 只出包
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n app.solochat.debug/app.solochat.MainActivity
```

**必须用 `tools/build.ps1`，不要直接 `gradlew`** —— 它替你设好了这台机器上的四处约束
（`JAVA_HOME=D:\JDK21`、`GRADLE_USER_HOME` 与 `ANDROID_PREFS_ROOT` 必须在工作区内、
`local.properties` 的 `sdk.dir`）。细节见 [docs/02-handoff.md](docs/02-handoff.md) §7。

### 按改动选择验证

统一流程与定向命令见 [README「测试」](README.md#测试)。日常只跑相关用例；需要装机才出包，交互变化才做对应真机检查。跨模块且影响难以限定时才全量回归；Release 工作流运行一次核心与 Release JVM 单测。

不把全量测试、`--rerun-tasks`、明暗/大字体遍历或取证作为每次提交的门槛。只报告实际执行范围，不维护日常总数，不累加可能陈旧的 `build/test-results`。历史统计保留在 `04-changes.md`，不为更新数字额外跑测试。

## 5. 开发规范（这个项目里最容易翻车的地方）

**通用**

- **只做必要的验证与测试（用户 2026-10-21 补充）**：不要做任何不必要的繁杂验证与测试，包括不必要的探针、快照、证据采集、门禁、夹具、哈希、冒烟、基线等行为；只有在验证具体改动的正确性或保护用户数据确有必要时才进行。优先使用现有测试与最小范围的直接检查；结论已充分支持后不重复、不扩大全量验证，不为凑测试数量或交接材料新增验证设施。纯文档、文案等低影响修改不默认运行构建、全量测试或真机取证。下文及 docs 中的验证清单按本次改动的实际风险选用，不作为每轮开发的固定流程；涉及历史数据修改时，仍保留必要的数据保护核对。未做的验证如实说明，不以未验证结果宣称通过。
- **暂不提供实机（用户 2026-10-22 补充）**：接下来几轮继续阶段开发，按影响编译/测试；完成阶段后可推送 GitHub 并发布预览 APK 供用户下载。缺少设备不阻塞阶段实现，不反复索要实机；观感、键盘与实际操作路径明确留待用户确认，不宣称已经实机验收。
- **对话功能的真机测试必须新建专用测试会话**，名称明确标为测试；不使用既有真实会话，不在旧会话输入验收草稿，也不修改旧会话的消息、模式或人物卡归属。测试后的会话默认保留并说明用途，除非用户明确要求删除。
- 真实聊天可能自动保存人物记忆。验收优先复制独立测试人物卡，避免测试内容进入日常人物卡的共享记忆；清理时只处理已核实由本轮测试生成的资料，不动原有记忆。
- `TestGateway.reset` 只表示新建测试会话，不能删除已有会话；测试输入直接推给模型，必须设置 `sentToModel = true`。
- **先取证再下结论**。"代码自洽"和"真的对"是两件事：涉及观感、节奏、模型行为的改动，
  必须真机验证或拿真实数据核对，并把依据写进 `docs/04-changes.md`。
- **不要凭印象写注释**。这个仓库里已经有几处注释与代码不符（见 [docs/HANDOFF.md](docs/HANDOFF.md) 的"已知问题"）。
- 测试要**针对具体现象**命名（`打开会话时还原整段 JSON 的脏行 - 内容一个字符都不改`），
  不写笼统的冒烟测试；逻辑 bug 优先补能复现故障的定向回归，已有用例足以覆盖时直接复用。纯文案、布局问题不强造红→绿用例。
- 改用户数据（迁移 / 修复 / 清理）必须**拉两份库逐字核对**，见 [docs/02-handoff.md](docs/02-handoff.md) §5
  「动过历史数据」。
- **不要提交任何密钥**：API Key 与日志网页密码在 `SecretStore`（EncryptedSharedPreferences）。
  **代码、文档、脚本里都不许出现真实凭据**，一律写 `<密码>`；本机自用的凭据放
  `secrets.properties` 或 `.tmp/`（两者都在 `.gitignore` 里），脚本按 `tools/logweb-auth.ps1`
  的约定去取（它支持 `-Password` 参数 / `SOLOCHAT_LOG_PASSWORD` 环境变量 / 上面两个文件）。
  ⚠️ 历史遗留里曾有硬编码密码（脚本默认值与文档正文），2026-10-08 已清理；**如果拿到的是旧版本仓库，先检查这两处**。

**架构纪律（违反会出静默 bug）**

- `core` 里**不许出现 `android.*`** —— 它必须能在纯 JVM 里秒级测试。
- 界面**只认"出口过"的事件**（`Event.delivery.spoken`）：`PENDING` 的事件不许画。
- 播出期间不许 reload 整个时间轴，但**每条播完必须刷**。
- 一件事只能有**一个写入者**；两个地方各判一次，改坏一个没人会发现。
- 提示词的**稳定前缀必须逐字节不变**（KV 缓存 + `stablePrefixChars` 口径）；
  任何"每轮都变"的东西只能进变动区。
- 日志是给排查看的流水，**不是说明书**：解释写进代码注释，不要写进日志行。
- 面向用户的界面文案**不用第三人称性别代词**（一张人物卡可能属于任何人）。
- 界面优化优先正常字体下的主要使用体验，不为超大字体极端场景增加大量适配投入；保留基础键盘避让与滚动可用性（用户 2026-10-10 补充）。
- 价格表与逐模型峰谷可配置；初始全部关闭峰谷（用户 2026-10-21 确认）。只按调用开始时间选取并冻结价格，不能因改表、改时段或改时区重算历史费用。

完整清单与理由：`docs/02-handoff.md` §3（十条不变式）、§8（改动自检清单）。

## 6. 文档索引

| 想知道什么 | 看哪份 |
|---|---|
| **要动手改代码了**（必须知道的不变式、踩过的坑、真机取证手段、环境约束） | [docs/02-handoff.md](docs/02-handoff.md) |
| **为什么这么设计**、什么情况下该推翻它 | [docs/01-decisions.md](docs/01-decisions.md) |
| 系统怎么搭起来的（模块、数据流、数据结构） | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 现在做到哪、还剩什么、已知问题 | [docs/HANDOFF.md](docs/HANDOFF.md) |
| 决策速查 + 坑清单 + 试过但不可行的方案 | [docs/DECISIONS.md](docs/DECISIONS.md) |
| 每一处改动本身（改了什么、依据、怎么证明） | [docs/04-changes.md](docs/04-changes.md) |
| 目标、成功标准、分阶段计划（⚠️ 实施状态表偏旧） | [docs/00-plan.md](docs/00-plan.md) |
| 2026-10-10 分阶段实施方案（阶段 1～3 已落地，阶段 4、5 待做，时间功能暂缓） | [docs/05-next-development.md](docs/05-next-development.md) |
| 后续开发指导（修复、备份、历史分页；§11 GitHub 更新计划、§12 Rikkahub 参考与复用记录） | [docs/06-development-guide.md](docs/06-development-guide.md) |
| 备份协议、Room/SAF 导出与副本恢复、跨安装迁移边界 | [docs/07-backup-format.md](docs/07-backup-format.md) |
| UI 改进方向、页面方案、分批顺序与 Rikkahub 参考范围 | [docs/08-ui-improvement-plan.md](docs/08-ui-improvement-plan.md) |
| 人物卡文件格式（八段、导入/导出） | [docs/03-persona-format.md](docs/03-persona-format.md) |
| 面向用户的说明 | [README.md](README.md) |

**权威性约定**（避免读到过期数字）：

| 口径 | 权威来源 |
|---|---|
| 测试结果 | 本次实际执行的范围与结果；历史统计见 `04-changes.md`，不维护日常总数 |
| 功能是否已验证 | `02-handoff.md` §1「已经真机验证过的」 |
| 现在还剩什么 | `HANDOFF.md`（本文档集）与 `02-handoff.md` §6、`00-plan.md` 末节 |
| 某处为什么是这样 | 代码注释 > `01-decisions.md` > `00-plan.md`（越靠后越可能是当时的想法） |

> `00-plan.md` 与 `04-changes.md` 是**按时间累积**的，早期段落里的数字与状态已经过期
> （例如"core 108 / app 29 个测试""没有真机验证"）。用之前先对一下日期。

## 7. 约定：不确定就标出来

写文档/注释时，**不许把猜测写成事实**。这个项目用的写法：

- 实测数字 → 直接写，并注明来源（真机日志 / 库里的行数 / 哪份脚本）。
- 没验证过 → 明确写「**未在真机上验证**」或「只是代码自洽」。
- 推测 → 用「推测」「可能」并给出判据（"如果 X 出现，说明我错了"）。
- 不知道该看哪份 → 写「**不确定**：需要确认 …」，不要编一个看起来合理的答案。
