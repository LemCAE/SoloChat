package app.solochat.core.protocol

/**
 * 一个可调的节奏参数：名字、范围、默认值，以及它在 [PacingConfig] 里读哪写哪。
 */
data class PacingParam(
    /** DataStore 里的键名。**改它等于把用户已有的设置丢掉**，别改。 */
    val key: String,

    /** 设置页上显示的名字。 */
    val label: String,

    /** 一句话说明"这个数字在管什么"。设置页里就显示在输入框下面。 */
    val hint: String,

    val default: Long,
    val min: Long,
    val max: Long,

    /** 设置页按它分子块。 */
    val group: String,

    val read: (PacingConfig) -> Long,
    val write: (PacingConfig, Long) -> PacingConfig
)

/**
 * 节奏参数的**一张表** —— 持久化、设置页、构造 [PacingConfig] 都从它长出来。
 *
 * ## 为什么做成表
 *
 * 这组参数有 20 个，而且还会继续加（面对面那一组就是后加的）。如果逐个手写
 * "键名 + 默认值 + 上下限 + 读 + 写"，加一个参数要改**五个地方**，漏一处就是
 * **"设置里改了没生效"** 这种最难查的 bug（它不报错，只是那个数字永远是默认值）。
 * 一张表只有一处。
 *
 * ## 三条不变式，由 `PacingParamsTest` 钉住
 *
 * 1. **表覆盖 `PacingConfig` 的每一个字段** —— 以后加了字段忘了进表，测试会红。
 * 2. **表里的默认值必须等于 `PacingConfig()` 的默认值** —— 否则设置页显示的数
 *    和程序真正用的数会对不上（用户会以为改了没生效，实际是默认值写错了一处）。
 * 3. 键名唯一、`min ≤ default ≤ max`。
 */
object PacingParams {

    const val GROUP_CHAT = "打字节奏（聊天）"
    const val GROUP_SCENE = "面对面"
    const val GROUP_READ = "阅读与延迟"
    const val GROUP_MEDIA = "图片 / 表情"

    val all: List<PacingParam> = listOf(

        // ---------------- 聊天：按字数 ----------------

        PacingParam(
            key = "min_ms_per_char",
            label = "每个字最少耗时",
            hint = "每个字各自在区间里随机取值再累加，所以同一句话每次的总时长都不同",
            default = 700, min = 0, max = 5_000, group = GROUP_CHAT,
            read = { it.minMsPerChar },
            write = { c, v -> c.copy(minMsPerChar = v) }
        ),
        PacingParam(
            key = "max_ms_per_char",
            label = "每个字最多耗时",
            hint = "调小 = 对方打字更快。和上一项一起决定\"越长越慢\"的斜率",
            default = 1_100, min = 0, max = 5_000, group = GROUP_CHAT,
            read = { it.maxMsPerChar },
            write = { c, v -> c.copy(maxMsPerChar = v) }
        ),
        PacingParam(
            key = "gap_min_ms",
            label = "两条之间最少间隔",
            hint = "下限，防止一串气泡瞬间刷屏",
            default = 300, min = 0, max = 20_000, group = GROUP_CHAT,
            read = { it.gapMinMs },
            write = { c, v -> c.copy(gapMinMs = v) }
        ),
        PacingParam(
            key = "gap_max_ms",
            label = "两条之间最多间隔",
            hint = "上限，防止长句被压成\"等了半分钟\"",
            default = 20_000, min = 0, max = 60_000, group = GROUP_CHAT,
            read = { it.gapMaxMs },
            write = { c, v -> c.copy(gapMaxMs = v) }
        ),
        PacingParam(
            key = "typing_ms_per_char",
            label = "\"正在输入\"每字耗时",
            hint = "显示\"正在输入…\"的时长按字算，这一项是每字多少毫秒",
            default = 110, min = 0, max = 1_000, group = GROUP_CHAT,
            read = { it.typingMsPerChar },
            write = { c, v -> c.copy(typingMsPerChar = v) }
        ),
        PacingParam(
            key = "typing_max_ms",
            label = "\"正在输入\"时长上限",
            hint = "再长的消息也不会一直显示\"正在输入\"",
            default = 12_000, min = 0, max = 60_000, group = GROUP_CHAT,
            read = { it.typingMaxMs },
            write = { c, v -> c.copy(typingMaxMs = v) }
        ),

        // ---------------- 面对面（另一套节奏，见 Pacing.scenePace） ----------------

        PacingParam(
            key = "scene_beat_ms",
            label = "动作/旁白之间的固定拍子",
            hint = "面对面**不按字数**算：一个动作写三个字还是三十个字，发生的时间差不多",
            default = 700, min = 0, max = 10_000, group = GROUP_SCENE,
            read = { it.sceneBeatMs },
            write = { c, v -> c.copy(sceneBeatMs = v) }
        ),
        PacingParam(
            key = "scene_say_min_ms",
            label = "台词最少间隔",
            default = 500, min = 0, max = 10_000, group = GROUP_SCENE,
            hint = "台词按字数伸缩，这是下限",
            read = { it.sceneSayMinMs },
            write = { c, v -> c.copy(sceneSayMinMs = v) }
        ),
        PacingParam(
            key = "scene_say_ms_per_char",
            label = "台词每字耗时",
            hint = "面对面里说话比打字快（没有\"发送\"这个动作），所以这个值应当明显小于聊天那组的每字耗时",
            default = 60, min = 0, max = 1_000, group = GROUP_SCENE,
            read = { it.sceneSayMsPerChar },
            write = { c, v -> c.copy(sceneSayMsPerChar = v) }
        ),
        PacingParam(
            key = "scene_say_max_ms",
            label = "台词间隔上限",
            hint = "再长的台词也不会把场景拖住",
            default = 3_000, min = 0, max = 30_000, group = GROUP_SCENE,
            read = { it.sceneSayMaxMs },
            write = { c, v -> c.copy(sceneSayMaxMs = v) }
        ),
        PacingParam(
            key = "scene_read_ms",
            label = "\"愣一下\"的时间",
            hint = "面对面里没有\"拿起手机读消息\"这个动作，话是当场听见的，所以只留一个反射时间",
            default = 350, min = 0, max = 10_000, group = GROUP_SCENE,
            read = { it.sceneReadMs },
            write = { c, v -> c.copy(sceneReadMs = v) }
        ),

        // ---------------- 阅读与延迟 ----------------

        PacingParam(
            key = "read_short_ms",
            label = "短消息的阅读延迟",
            hint = "你发的消息越短，对方\"看得越快\"",
            default = 600, min = 0, max = 30_000, group = GROUP_READ,
            read = { it.readShortMs },
            write = { c, v -> c.copy(readShortMs = v) }
        ),
        PacingParam(
            key = "read_medium_ms",
            label = "中等消息的阅读延迟",
            hint = "按你那条消息的字数分档：< 20 字 / < 60 字 / 更长",
            default = 1_400, min = 0, max = 30_000, group = GROUP_READ,
            read = { it.readMediumMs },
            write = { c, v -> c.copy(readMediumMs = v) }
        ),
        PacingParam(
            key = "read_long_ms",
            label = "长消息的阅读延迟",
            default = 2_600, min = 0, max = 60_000, group = GROUP_READ,
            hint = "你发了一整段时，对方\"看了一会儿\"",
            read = { it.readLongMs },
            write = { c, v -> c.copy(readLongMs = v) }
        ),
        PacingParam(
            key = "read_jitter_ms",
            label = "阅读延迟的随机抖动",
            hint = "在上面的档位之外再随机加 0~这么多毫秒，避免每次都是同一个数",
            default = 700, min = 0, max = 10_000, group = GROUP_READ,
            read = { it.readJitterMs },
            write = { c, v -> c.copy(readJitterMs = v) }
        ),
        PacingParam(
            key = "read_hint_max_ms",
            label = "情绪提示的封顶",
            hint = "模型可以在协议里给一个\"我现在很冷淡，慢点回\"的提示，但再生气也不该让你干等超过这么久",
            default = 15_000, min = 0, max = 120_000, group = GROUP_READ,
            read = { it.readHintMaxMs },
            write = { c, v -> c.copy(readHintMaxMs = v) }
        ),

        // ---------------- 图片 / 表情 ----------------

        PacingParam(
            key = "media_gap_min_ms",
            label = "发图前最少停顿",
            hint = "相当于\"找图\"的时间。图片不按字数排间隔",
            default = 3_000, min = 0, max = 60_000, group = GROUP_MEDIA,
            read = { it.mediaGapMinMs },
            write = { c, v -> c.copy(mediaGapMinMs = v) }
        ),
        PacingParam(
            key = "media_gap_max_ms",
            label = "发图前最多停顿",
            default = 5_000, min = 0, max = 60_000, group = GROUP_MEDIA,
            hint = "同上一项，区间上限",
            read = { it.mediaGapMaxMs },
            write = { c, v -> c.copy(mediaGapMaxMs = v) }
        ),
        PacingParam(
            key = "media_react_min_ms",
            label = "收到图片后的反应时间（少）",
            hint = "看到图之后\"发出前那一小段沉默\"，与上一条的间隔无关",
            default = 500, min = 0, max = 30_000, group = GROUP_MEDIA,
            read = { it.mediaReactMinMs },
            write = { c, v -> c.copy(mediaReactMinMs = v) }
        ),
        PacingParam(
            key = "media_react_max_ms",
            label = "收到图片后的反应时间（多）",
            default = 1_300, min = 0, max = 30_000, group = GROUP_MEDIA,
            hint = "同上一项，区间上限",
            read = { it.mediaReactMaxMs },
            write = { c, v -> c.copy(mediaReactMaxMs = v) }
        )
    )

    /** 按分组切好，设置页直接照着画。 */
    fun grouped(): List<Pair<String, List<PacingParam>>> =
        all.groupBy { it.group }.map { (g, list) -> g to list }
}
