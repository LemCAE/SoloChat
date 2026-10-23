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
5. Room 时间轴查询按来源与稳定游标分页；`ChatProjector` 将可见记录投影为界面项目。当前会话及全局搜索共用完整库候选查询，再经相同映射与时间轴可见性规则过滤，按会话与来源键保留分页结果，不重载聊天窗口。全局候选联结当前会话/人物元数据，含归档会话，人物缺失时标为待确认；每批正文按候选 ID 有界读取。

生成和播出解耦。会话控制器由应用按会话唯一持有；离开页面不停止当前回合。同一会话的发送、模式/模型/人物配置操作须共用串行化边界。

## 历史读取与定位

| 路径 | 查询与窗口 | 与运行中会话的关系 |
|---|---|---|
| 聊天时间轴 | 最近 80 条，可向前分页 | 由 SessionController 持有，按出口事件刷新 |
| 当前会话/全局搜索 | 完整库候选，每页最多 40 条 | 独立只读结果，不改变已加载聊天窗口 |
| 点击搜索结果 | 来源类型+ID 精确查目标，前后各 20 条；两侧按页扩展 | HistoryContextState 独立持有，不获取 SessionRegistry，不逐页接到最新 |
| 定位页返回最新 | 读取该会话最近 80 条可见记录 | 仍是只读历史，不自动进入聊天或推送代发 |

定位沿用时间轴可见性，目标已删除或不再可见时提示并保留返回搜索/最新的路径。数据库变化只检查最新位置及目标有效性，不替换正在阅读的前后文；新内容提示由用户点击后加载。搜索层留在组合中，返回时保留查询、已加载结果与滚动位置；当前聊天的输入草稿仍由原输入区持有。

主要入口为 `core/.../model/TimelineContext.kt`、`StoragePort.timelineContext`、`RoomStore`、`ui/chat/HistoryContextState.kt` 与 `HistoryContextScreen.kt`。纯 Kotlin 来源键不直接用作 Compose 列表 key，UI 转成可保存在 Android Bundle 中的来源字符串。

## 可见性与中断

事件状态按 PENDING → PLAYING → DELIVERED 推进。播出中断时当前条可标记 INTERRUPTED，尚未播出的条取消。只有 DELIVERED 与 INTERRUPTED 属于 spoken，可进入聊天时间轴和后续模型上下文；PENDING/PLAYING/CANCELED 不代表角色已经说出。助手事件播出后另写为消息供模型上下文使用，时间轴避免重复显示副本。

## 上下文、记忆与记账

- 用户消息仅在明确推送后进入模型上下文；手动代发保持未推送状态。
- system 稳定前缀由安全规则、人设、协议和静态风格组成；每轮变动信息位于动态区。已应用的上下文修订以版本指针启用，聊天显示仍保留原历史。
- `call_record` 保存逐字段用量及开始时价格快照。复杂时段在调用开始求值，各输入档的当时价格放入 ModelPrice.frozenTiers；收尾和统计只用返回输入量从该快照选档，绝不查今天的价格表。旧字段缺失默认空阶梯，旧费用口径保持兼容。未知值不补造为零，费用为估算口径。
- 人物记忆归属人物卡；会话必须明确绑定有效人物 ID，缺失时阻止生成并要求选择。

## 持久化与备份

接口配置组元数据放在 DataStore 的 `api_profiles_v1`，全局选择为 `active_api_profile_v1`。每组拥有地址、默认模型、模型目录及独立价格；会话仍仅覆盖模型名，不修改会话归属。温度、上下文上限、节奏及行为设置继续全局共享。旧单接口字段完整复制为 `legacy` 首组，原偏好键保留但不再作为已迁移配置的读取兜底；迁移不触碰 Room。

组内只保存密钥槽位 ID，Key 留在 SecretStore 加密文件。换 Key 先同步提交新槽位，再一次 DataStore edit 发布地址与槽位指针，避免中断后出现旧地址配新 Key；成功发布后清理旧槽位。迁移先复制旧 Key 后发布首组，失败可重试；进程在发布前中断可能留下未被引用的加密槽位，不会参与请求。加密不可用时拒绝新组密钥保存与迁移，不写入明文降级文件。

配置写入、切换、删除及运行时快照发布共用应用级互斥锁。聊天与压缩在已冻结 ChatConfig 中取地址/Key，随 LlmRequest 传入网络层；进行中调用不受后续切换/删除影响。删除当前组后保持未选择，不自动启用别组或旧配置；无凭据时阻止请求。备份仍只包含既有聊天资料，不导出组密钥。

设置首页可快速切组，接口页管理当前组。接口/价格草稿未保存或目录/连通请求在途时暂时阻止切组；检查只消费当前组草稿，不自动保存。草稿变更后丢弃旧检查结果，Key 草稿不进入 SavedState Bundle，重建后需重填。模型目录与连通客户端固定使用一次捕获的地址/Key，目录分页不重新读取全局密钥。

接口新建模板限 DeepSeek 官方、阿里云百炼、自定义，仅预填地址；不生成模型或价格、不重分类既有组。独立价格页直接展示模型项，表管理入口可新建/切换/导入/导出；简单表 v1、复杂规则表 v2 均可读。`PriceTableTransfer` 在 core 校验版本、币种、单位、显式单价与已有价格约束；导入生成新表 ID，只追加当前组草稿，页面保存后才发布。SAF 文件读写在 app 层，交换文件不包含组 ID/地址/Key。参考资料由独立的 ReferencePrices 提供，只读分渠道展示日期、来源与适用条件；可表达条目须明确复制，R4 支持多时段/星期/日期例外与输入阶梯，复制后仍须明确配置启用；日历需核对确认。旧 initialCatalog 与 assets 保留兼容，价格解析器不读取新参考资料，已有旧表保留兼容；不变更 Room 或历史账目。格式见 [价格表专题](reference/price-table-format.md)。

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

设置页组织：接口详情以 modelList 展示本组模型，手动添加及目录获取合并进同一草稿，不维护第二份模型集合；选择只修改本组默认模型。价格页采用独立全屏窗口和滚动容器，避免继承接口表单位置；模型编辑和表管理覆盖时保留父窗口草稿。价格列表直接呈现在主页面，表切换与交换文件放入独立表管理页，未匹配兜底价折叠为可选设置。持久化仍由原页面保存入口统一发布。
