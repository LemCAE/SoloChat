# 自定义价格表 JSON v1 / v2

价格表属于当前接口组。价格页可新建空表、新增任意模型价格项，或通过“导入价格表”选择 JSON 文件 / 粘贴内容。导入先校验并预览表名及模型数，确认后新增独立表并选为本组草稿，**保存价格页后**才参与后续调用；不覆盖已有表，不修改别组，不重算旧账目。取消文件选择或导入不会写设置。

“导出当前表”导出点击时所选表的草稿内容；文件不含接口组 ID、地址、Key 或内部表 ID，可作为自行维护的模板。可直接编辑 [示例文件](price-table-example.json) 后导入。示例金额仅展示格式，不是官方报价。

## 文件约定

- UTF-8 JSON，最大 2 MiB；支持 UTF-8 BOM。简单表 `formatVersion=1`；带 `schedule` 或非空 `tiers` 的复杂表为 `2`。App 自动选择导出版本，并继续接受旧 v1 文件。
- `currency` 必须为 `CNY`，`unit` 必须为 `per_million_tokens`（人民币 / 百万 token）。不猜币种、不自动换汇或换算单位。
- `name` 是非空表名；`entries` 是模型价格项数组，可为空。
- 每项 `model` 为去除首尾空白的小写模型 ID，同一表不允许重复。按现有精确 ID / 快照后缀规则匹配，不根据服务商品牌猜价格。
- `standard.input`、`standard.output` 必须显式填写非负有限数字；缺价拒绝整表，不能因默认零值变成免费。
- `standard.cached` 可省略或为 `null`（沿用输入价）；显式 `0` 表示免费缓存命中，两者不同。
- 可选 `peak` 结构同 `standard`，填写时也须明确输入/输出价。`peakEnabled` 默认 `false`，不会因填写峰价而自行开启。
- 开启峰谷时必须同时有峰价和 `window`：`startMinute`、`endMinute` 是 0～1439 且不同的分钟值，`timeZone` 是有效时区，例如 `Asia/Shanghai`。含开始、不含结束，开始大于结束表示跨午夜；非峰时使用 `standard`。
- `note`、`source`、`provider` 是备注、来源和展示归属，不会改变请求地址。未知字段（例如误粘贴整份带 Key 的设置）会拒绝；格式错误不回显原始文件内容。

## 参考资料与旧表

“查看官方参考资料（非实时）”分渠道展示核对日期、适用范围和官方来源。只收录 DeepSeek 官方与百炼部分文本模型，详见[核对记录](price-reference-sources.md)。能准确表达的条目明确复制后才成为本组独立草稿；R4 已支持复杂时段和阶梯；未确认缓存价的条目仍只读，日历草稿须核对并确认。旧版“原内置表”继续按原设置工作，不静默替换或删除用户修改。

## 接口模板范围

当前仅提供 DeepSeek 官方、阿里云百炼和自定义接口三种新建入口。模板仅预填地址，模型、Key 和价格均由用户明确配置；既有组不会被重新分类或改地址。

地址依据（2026-10-23 核对）：[DeepSeek 官方文档](https://api-docs.deepseek.com/api/responses)、[百炼 Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)。百炼模板使用北京 OpenAI 兼容地址，其他地域或工作空间使用控制台对应地址；不在编辑页提供单一厂商的填址快捷按钮。

组织方式参考本地 Rikkahub 的 `ai/.../provider/ProviderSetting.kt`、`SettingProviderPage.kt` 和 `SettingProviderDetailPage.kt`：提供商拥有自己的模型、组内直接添加与编辑、导入有独立入口。本轮参考配置组织及交互思路，没有移植其实现，也不声称 Rikkahub 包含本价格表格式。


## v2 多时段、日期例外与阶梯

可编辑的[复杂表示例](price-table-example-v2.json)只展示格式，不代表官方报价。

每个模型可增加 `schedule` 和 `tiers`。不支持的版本或未知字段仍拒绝整表；v1 不接受复杂规则。

`schedule`：`ranges` 为多个 `{startMinute,endMinute}`，`weekdays` 为不重复的星期整数（1=周一，7=周日），`timeZone` 为有效时区。含开始、不含结束，跨午夜的凌晨部分归属于开始日期；多个时段按并集判断。

可选 `calendar` 包含 `validFrom`、`validThrough`、`excludedDates`、`includedDates`、`source`、`confirmed`。日期均为 ISO yyyy-MM-dd，例外必须在有效期内，排除和额外适用日期不能重复或冲突。`includedDates` 覆盖星期规则，`excludedDates` 排除日期；不联网猜假日。日历默认 `confirmed=false`，须核对本渠道后确认，确认时来源不可空。需要判定日历时，未确认或有效期外的费用未知。

`tiers` 按输入 token 上限递增排列。每项必须有 `upToInputTokens` 和 `standard`，可选 `peak`；峰谷开启时每档须有峰价。上限含本数，下一档下界不含前档上限；最后一档上限可为 null 表示不限。**所有输入及输出按命中档整档计费，不是分段累进**。`standard` / `peak` 都需显式填写 input、output，cached 省略随输入，0 表示免费缓存。超出最后一个有限上限时费用未知。

调用开始时冻结当时的时段判断和全部档位价格；返回后仅用接口实际返回的输入总量选档。缺返回输入量不使用本地估算冒充已知档位；改表、切组、改日历不会改变既有记录费用。Room 无迁移，旧价格表和旧调用快照按默认空扩展字段读取，不回填历史。

模型编辑中的“编辑多时段、日历与阶梯”提供对应表单。完成高级规则只回模型草稿，模型完成再回价格页草稿，最终保存价格页才发布。
