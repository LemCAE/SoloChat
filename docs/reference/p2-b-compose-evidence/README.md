# P2-B Compose 真机截图

2026-10-25，PKM110，竖屏 1256×2760（约 359dp），仅运行隔离包 `app.solochat.debug`。为截图创建了无凭据的合成 Provider/model（`P2BTest`、`P2BChat`，地址 `https://example.invalid/v1`），未发请求；结束后已移除合成 Provider。正式包 `app.solochat` 未启动/覆盖。浅色截图期间临时关闭系统深色模式，拍摄后恢复深色模式。

报价与零价截图均为隔离测试数据；零价画面只在报价草稿中预览，未点父页面“保存报价”。参考预览的渠道/日历条件未勾选，未采用；旧价整理与导入均未提交。

| 画面 | 截图 |
|---|---|
| 报价列表：未设置报价保持未知 | ![报价列表](p2b-price-list.png) |
| 报价列表：明确的 0/0 草稿 | ![零价草稿](p2b-price-zero.png) |
| 普通编辑（深色） | ![编辑深色](p2b-editor.png) |
| 普通编辑（浅色） | ![编辑浅色](p2b-editor-light.png) |
| 峰价/阶梯独立折叠（深色） | ![高级规则深色](p2b-advanced.png) |
| 峰价/阶梯独立折叠（浅色） | ![高级规则浅色](p2b-advanced-light.png) |
| 参考条目列表 | ![参考列表](p2b-references.png) |
| DeepSeek 采用条件确认页：渠道与日历尚未确认，采用操作保持禁用 | ![参考采用条件](p2b-reference-preview.png) |
| 旧价格整理预览（该测试 Provider 没有旧价格） | ![旧价预览](p2b-migration.png) |
| v3 导入精确 ID 映射，导入只进入草稿 | ![导入预览](p2b-import-preview.png) |
| 报价列表（浅色） | ![报价浅色](p2b-price-list-light.png) |

本组截图用于本轮 Android UI 视觉/交互验收，不代表用户已接受，也不替代 B6 的数据保存、冲突、调用及历史账目集成验证。完整规则对照的 Compose 二次弹层未在本次真机图集中展示；已通过视觉确认的 Demo 基准图仍在 [原设计图 09](../p2-b-design-images/09-参考整条替换预览.png)。导入时目标模型尚无已保存报价，因此“已有报价默认跳过/显式覆盖”以 `PriceExchangeImportPlanTest` 的生产计划逻辑为当前自动化证据；保存失败保留草稿也只由 JVM/发布器路径验证，未在真机注入存储故障。
