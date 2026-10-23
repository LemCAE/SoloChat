# ARCHITECTURE｜当前系统结构

> 当前结构摘要，按需再进入源码。设计缘由和故障取舍见 [归档索引](archive/INDEX.md)；长期约束见 [LESSONS](LESSONS.md)。

## 系统边界

SoloChat 是单机 Android 聊天应用。`:core` 是纯 Kotlin/JVM 领域与协议层，不依赖 Android；`:app` 提供 Compose、Room、网络适配、设置和应用生命周期。依赖方向只能是 `app → core`。API Key 和网页密码保存在本机加密存储；没有 SoloChat 云端服务。

- `core`: 会话/消息模型、模型协议解析、提示词与上下文规划、调用编排接口、事件播放计划、价格快照、备份数据协议。
- `app`: Room/DataStore/SecretStore、OpenAI 兼容客户端、调用适配、会话控制器、导航与 UI、内嵌日志和测试网关、备份/更新功能。
- Room 当前版本为 7；迁移定义在 `app/.../data/db/Migrations.kt`，schema 导出在 `app/schemas/`。

## 一轮生成与播出

1. `ChatScreen` 订阅应用级 `SessionRegistry` 中该会话唯一的 `SessionController`。
2. 控制器接收 AUTO/MANUAL 输入；AUTO 防抖后认领并保存推送消息，MANUAL 输入保存为尚未推送的消息。
3. `AgentTurnRunner` 经 `ContextPlanner` 取得当前有效模型、人设、记忆、已推送消息和已出口事件，交由 `PromptBuilder` 构建请求；一次调用返回事件脚本。
4. 事件先以 PENDING 落库并完成调用记账，然后播放器逐条播出。每条播出结束写入事件交付状态和助手上下文消息，再刷新 UI。
5. Room 时间轴查询按来源与稳定游标分页；`ChatProjector` 将可见记录投影为界面项目。搜索/定位是 [当前计划](PLAN.md)，不能据此认为功能已实现。

生成和播出解耦。会话控制器由应用按会话唯一持有；离开页面不停止当前回合。同一会话的发送、模式/模型/人物配置操作须共用串行化边界。

## 可见性与中断

事件状态按 PENDING → PLAYING → DELIVERED 推进。播出中断时当前条可标记 INTERRUPTED，尚未播出的条取消。只有 DELIVERED 与 INTERRUPTED 属于 spoken，可进入聊天时间轴和后续模型上下文；PENDING/PLAYING/CANCELED 不代表角色已经说出。助手事件播出后另写为消息供模型上下文使用，时间轴避免重复显示副本。

## 上下文、记忆与记账

- 用户消息仅在明确推送后进入模型上下文；手动代发保持未推送状态。
- system 稳定前缀由安全规则、人设、协议和静态风格组成；每轮变动信息位于动态区。已应用的上下文修订以版本指针启用，聊天显示仍保留原历史。
- `call_record` 保存每次调用及逐字段用量来源、开始时冻结的模型/价格快照；旧 usage 保持兼容。未知值不补造为零，费用为估算口径。
- 人物记忆归属人物卡；会话必须明确绑定有效人物 ID，缺失时阻止生成并要求选择。

## 持久化与备份

Room 负责会话、事件、消息、人物、记忆、调用、上下文修订及备份导入来源。迁移只追加；备份通过版本化 ZIP 导出并以副本方式事务导入。密钥、日志和临时凭据不随备份。详细格式与恢复语义见 [备份专题](reference/07-backup-format.md)。

## 运行时服务

应用内 Ktor 服务目前用于日志网页和测试网关；它不等于正式 LAN 聊天网页。测试命令只有在复用同一个 SessionRegistry/控制器入口时才与 UI 等价。更新功能手动查询公开发行信息、下载固定 APK 并在安装前检查包身份；系统确认界面返回不证明已安装。

## 关键源码入口

| 职责 | 入口 |
|---|---|
| 会话模型、事件、时间轴端口 | `core/src/main/kotlin/app/solochat/core/model/`、`core/.../ports/` |
| 上下文与请求 | `core/.../agent/`、`core/.../prompt/`、`app/src/main/kotlin/app/solochat/data/llm/` |
| 事件播放与会话唯一实例 | `core/.../playback/`、`app/src/main/kotlin/app/solochat/play/SessionController.kt`、`SessionRegistry.kt` |
| 数据库与迁移 | `app/src/main/kotlin/app/solochat/data/db/`、`app/schemas/` |
| UI 与导航 | `app/src/main/kotlin/app/solochat/ui/` |
| 备份、日志与调试 | `app/src/main/kotlin/app/solochat/data/backup/`、`data/logweb/` |
