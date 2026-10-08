package app.solochat.core.protocol

import app.solochat.core.model.ChatMode
import app.solochat.core.model.EventKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型输出里的一个事件（还没落盘，没有 id / 播出状态）。
 *
 * 与 [app.solochat.core.model.Event] 的区别：这里只有"模型说的话"，
 * 没有任何本地事实。中间的翻译层就是 [ScriptParser]。
 */
data class ScriptEvent(
    val kind: EventKind,
    val speaker: String = "",
    val text: String = "",

    /** 与上一条的间隔"感觉值"，引擎只当参考（会与按字数算的值混合）。 */
    val gapHintMs: Int? = null,

    /** 打了又删：显示"正在输入…"然后什么都不发。 */
    val typingHintMs: Int? = null,

    val waitReason: String = ""
) {
    /**
     * 落盘时用的说话者：模型没给名字就补一个占位。
     *
     * `say` / `act` 必须有人，否则界面上是无主气泡（`ChatItem.Action` 会把名字画在
     * 动作前面）；`scene` 是无人称旁白，**保持空**。
     *
     * ⚠️ 这条规则同时被两处用到：正常落盘（`AgentTurnRunner.toEvent`）与
     * 历史脏行修复（`DegradedTurn`）。**只能有一份实现** —— 两处写成两种，
     * 同一个库里就会出现两种说话者，而界面上看起来只是"名字有时候是 ta"。
     */
    val resolvedSpeaker: String
        get() = speaker.ifBlank {
            if (kind == EventKind.SAY || kind == EventKind.ACT) "ta" else ""
        }
}

/**
 * 一"轮"的完整脚本 = 一次模型调用的产出。
 *
 * [events] 为空表示模型选择了不说话（"还在等你说话" / "不想理你"），
 * 这不是错误，是 `wait` 语义。
 */
data class Script(
    val events: List<ScriptEvent>,

    /** 模型对模式的判断。null = 不表态（保持当前模式）。 */
    val nextMode: ChatMode? = null,

    /** 给用户看的切换理由，进确认卡片。 */
    val modeReason: String = "",

    /** 模型原始输出，只用于调试面板与"重试"时展示，**不进上下文**。 */
    val rawText: String = "",

    /**
     * 解析过程中的**非致命备注**，用于诊断"它为什么没说话"。
     *
     * 两类内容：被丢掉的东西（未知事件类型等），以及"被改造过"的东西
     * （独立成条的停顿被折进下一条 —— 见 [ScriptParser]）。有备注不代表这一轮失败。
     */
    val dropped: List<String> = emptyList(),

    /** 是否是降级解析（模型没按协议输出，整段当一条消息）。 */
    val degraded: Boolean = false
) {
    val isEmpty: Boolean get() = events.isEmpty()
}

/**
 * 把模型输出解析成脚本。
 *
 * **铁律：永远不抛出异常，永远有一个可用的结果。**
 * 因为解析失败如果抛出去，这一轮就白费了（钱已经花了）；而"降级成一条消息"
 * 至少让用户看到东西。
 *
 * ## 四层，从便宜到贵
 *
 * 1. **原样整份解析** —— 正常输出走这里，一个字符都不动。
 * 2. **修补后整份解析** —— 只修实测过的结构性小错（[normalize]）。
 * 3. **原样逐条抢救** —— 整份不合法时下钻一层，坏的那条跳过、好的照常（[salvage]）。
 * 4. **修补后逐条抢救**。
 *
 * 四层全落空才降级成一条纯文本消息。
 *
 * ⚠️ **为什么要有 2~4 层**：模型输出是长 JSON，一个字符写坏（多余冒号、
 * 少个逗号）在语法上就等于**整份作废**。而这是**一整轮**的产出 ——
 * 用户看到的是"一屏原始 JSON"（真机上 216 条里有 27 条是这样，见 docs/04-changes.md），
 * 钱也已经花了。整份不行就逐条来，这条纪律比"解析器要严格"重要得多。
 *
 * 刻意**不做**的事：
 * - 流式中途解析。JSON 被截断时给不出可靠结果，播出半截脚本比晚几百毫秒糟糕得多
 *   （见 docs/01-decisions.md D4）。**但已经完整的那几条会留下**，半截的那条不播。
 * - 猜"未转义的引号原本该在哪结束"。猜错等于**替模型编内容**，比降级更糟。
 */
object ScriptParser {

    /**
     * 解析配置。三项都开了容错，但**别把它当成万能的**：
     * - `ignoreUnknownKeys`：模型常会多带字段（`emotion`、`tone` 之类）
     * - `isLenient`：放开的只有"键和值可以不带引号"，**单引号它照样不认**
     *   （我原先的注释写成"单引号也见过"，是错的；实测见 `ScriptParserTest`
     *   的「单引号其实不认 - 交给修补」）。单引号、弯引号、全角标点都是 [normalize] 的活。
     * - `allowTrailingComma`：多一个尾逗号是最常见的"小错"，为此整轮降级太浪费
     *
     * `allowTrailingComma` 目前仍是实验性 API，所以这里显式 opt-in。
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /** 间隔提示的合理区间。模型报出 50000ms 这种值时会被夹到这里。 */
    private val gapRange = 0..8_000
    private val typingRange = 0..20_000

    /**
     * `{"type":"typing_ms":2500}` —— 一个值后面又多了一个冒号。
     *
     * 这个形态在**合法 JSON 里不可能出现**（一个值的后面只可能是 `,` `}` `]`），
     * 所以可以放心用正则修，不必逐字符扫描：把多出来的那个冒号换成 `,"ms":`。
     * 真机证据：09:16 与 09:20 两轮降级全是这一处（其余 8 条都是好的）。
     */
    private val extraColon = Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"([^\"]*)\"\\s*:\\s*")

    /** 抢救路径下没有根对象可问，模式提议只能从文本里捞。 */
    private val nextModeField = Regex("\"next_mode\"\\s*:\\s*\"([A-Za-z_]+)\"")
    private val modeReasonField = Regex("\"mode_reason\"\\s*:\\s*\"([^\"]*)\"")

    fun parse(raw: String?): Script {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return Script(
                events = emptyList(),
                rawText = "",
                dropped = listOf("模型输出为空，按 wait 处理")
            )
        }

        val cleaned = stripFence(text)
        val payload = extractWhole(cleaned)
            ?: extractWhole(normalize(cleaned))
            ?: salvage(cleaned)
            ?: salvage(normalize(cleaned))
            ?: return degradedSay(text, "输出里没有可用的 JSON，按单条消息处理")

        val obj = payload.obj
        val events = mutableListOf<ScriptEvent>()
        val dropped = mutableListOf<String>()
        dropped += payload.notes

        // 独立成条的停顿（`{"type":"typing_ms",...}`）会先挂在这里，等下一个事件来认领。
        var pendingTyping: Int? = null
        var pendingGap: Int? = null
        var onlyPauses = true

        for ((index, element) in payload.events.withIndex()) {
            val eventObj = runCatching { element.jsonObject }.getOrNull()
            if (eventObj == null) {
                dropped += "第 ${index + 1} 条不是对象"
                onlyPauses = false
                continue
            }
            when (val slot = parseOne(eventObj, index, dropped)) {
                null -> onlyPauses = false   // 具体原因 parseOne 已经记下了

                is Slot.Pause -> {
                    if (slot.typingMs != null) pendingTyping = pendingTyping ?: slot.typingMs
                    if (slot.gapMs != null) pendingGap = pendingGap ?: slot.gapMs
                    dropped += "第 ${index + 1} 条是独立的停顿（${slot.typingMs ?: slot.gapMs}ms），已折进下一条"
                }

                is Slot.Event -> {
                    onlyPauses = false
                    var event = slot.value
                    if (pendingTyping != null || pendingGap != null) {
                        // 事件自己写的字段优先：模型在"这一条"上明确给了时长，就别用前面那段概括的
                        event = event.copy(
                            typingHintMs = event.typingHintMs ?: pendingTyping,
                            gapHintMs = event.gapHintMs ?: pendingGap
                        )
                        pendingTyping = null
                        pendingGap = null
                    }
                    events += event
                }
            }
        }
        if (pendingTyping != null || pendingGap != null) {
            dropped += "末尾还有一段独立停顿（${pendingTyping ?: pendingGap}ms），后面没有事件可挂，已丢弃"
        }

        val mode = parseMode(obj)
        val reason = obj?.get("mode_reason")?.asString().orEmpty()

        if (events.isEmpty() && dropped.isEmpty()) {
            // 合法的空脚本 = 模型主动选择不回
            return Script(events = emptyList(), nextMode = mode, modeReason = reason, rawText = text)
        }
        if (events.isEmpty() && onlyPauses) {
            // 整轮只有停顿、没有任何内容：模型其实什么都没说。
            // **这里绝不能降级** —— 降级会把整段 JSON 当成一条消息发出去，
            // 那正是这个 bug 在界面上的样子（用户对着一屏 JSON 发愣）。
            return Script(
                events = emptyList(),
                nextMode = mode,
                modeReason = reason,
                rawText = text,
                dropped = dropped
            )
        }
        if (events.isEmpty()) {
            // 全被丢了，宁可降级也别让用户面对一个空白
            return degradedSay(text, "事件全部无法解析：" + dropped.joinToString("；"))
        }
        return Script(
            events = events,
            nextMode = mode,
            modeReason = reason,
            rawText = text,
            dropped = dropped
        )
    }

    // ------------------------------------------------------------------
    // 第一、二层：整份解析

    private class Extracted(
        val obj: JsonObject?,
        val events: JsonArray,
        val notes: List<String> = emptyList()
    )

    /**
     * 从可能被 ```json 包裹、可能前后有废话的输出里抠出事件数组。
     * 取**最外层**的括号而不是正则贪婪匹配 —— 正文里含 `}` 的情况很常见。
     */
    private fun extractWhole(text: String): Extracted? {
        // 顶层直接是数组：[{...},{...}]
        if (text.startsWith("[")) {
            val end = text.lastIndexOf(']')
            if (end > 0) {
                val arr = runCatching {
                    json.parseToJsonElement(text.substring(0, end + 1)).jsonArray
                }.getOrNull()
                if (arr != null) return Extracted(null, arr)
            }
        }

        // 顶层是对象：{"events":[...], "next_mode":"chat"}
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null

        val events = runCatching {
            (obj["events"] ?: obj["messages"] ?: obj["script"])?.jsonArray
        }.getOrNull()

        if (events == null) {
            // 有对象但没有事件数组：可能是 {"text":"..."} 这种简写
            val single = obj["text"]?.asString() ?: obj["content"]?.asString()
            if (!single.isNullOrBlank()) {
                return Extracted(
                    obj,
                    JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("say"),
                                    "text" to JsonPrimitive(single)
                                )
                            )
                        )
                    )
                )
            }
            return null
        }
        return Extracted(obj, events)
    }

    /** 去掉 ```json ... ``` 围栏。模型几乎总会加，不加反而少见。 */
    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        val firstBreak = text.indexOf('\n')
        if (firstBreak < 0) return text
        val closing = text.lastIndexOf("```")
        return if (closing > firstBreak) {
            text.substring(firstBreak + 1, closing).trim()
        } else {
            // 围栏没闭合（被截断了）：至少把开头那行去掉
            text.substring(firstBreak + 1).trim()
        }
    }

    // ------------------------------------------------------------------
    // 第三、四层：逐条抢救

    /** 抢救过程中的收集筐。 */
    private class Bucket {
        val events = mutableListOf<JsonElement>()
        val notes = mutableListOf<String>()
        var skipped = 0
    }

    /**
     * 结构性抢救：整份文档不是合法 JSON 时，退一步**逐个对象**处理。
     *
     * 规则是"能整体吃下就整体吃下，吃不下就下钻一层"：
     * - 能解析、且里面有 `events` 数组 → 它就是容器，把数组元素逐个交给主循环
     * - 能解析、自己带 `type` / `kind` → 它就是一个事件
     * - 解析不了 → 把它的**直接子对象**拆出来重来（大括号配对扫描，认字符串与转义，
     *   所以正文里出现 `{` 也不会算错层级）
     *
     * 有了这一层，"一条坏、其余好"就不会连累整轮：坏的那条被跳过，好的照常播出。
     * 真机证据见 [parse] 的注释。
     */
    private fun salvage(text: String): Extracted? {
        val bucket = Bucket()
        for (span in outermostObjects(text)) collect(span, bucket)
        if (bucket.events.isEmpty()) return null

        bucket.notes.add(0, "整份 JSON 不合法，逐条抢救出 ${bucket.events.size} 条")
        if (bucket.skipped > 0) bucket.notes += "另有 ${bucket.skipped} 条实在解析不了，已跳过"
        return Extracted(modeRoot(text), JsonArray(bucket.events), bucket.notes)
    }

    private fun collect(span: String, bucket: Bucket) {
        val obj = parseObject(span)
        if (obj != null) {
            val array = runCatching {
                (obj["events"] ?: obj["messages"] ?: obj["script"])?.jsonArray
            }.getOrNull()
            if (array != null) {
                for (element in array) {
                    if (element is JsonObject) bucket.events += element else bucket.skipped++
                }
                return
            }
            if (obj["type"] != null || obj["kind"] != null) {
                bucket.events += obj
                return
            }
            val single = obj["text"]?.asString() ?: obj["content"]?.asString()
            if (!single.isNullOrBlank()) {
                bucket.events += JsonObject(
                    mapOf("type" to JsonPrimitive("say"), "text" to JsonPrimitive(single))
                )
                return
            }
        }

        // 自己解析不了：下钻到直接子对象。一个都没有就说明这一段彻底没救。
        val children = directChildren(span)
        if (children.isEmpty()) {
            bucket.skipped++
            return
        }
        for (child in children) collect(child, bucket)
    }

    private fun parseObject(span: String): JsonObject? =
        runCatching { json.parseToJsonElement(span).jsonObject }.getOrNull()
            ?: runCatching { json.parseToJsonElement(normalize(span)).jsonObject }.getOrNull()

    /** 一个对象 span 的直接子对象（去掉最外层括号后再找最外层对象）。 */
    private fun directChildren(span: String): List<String> {
        if (span.length < 2) return emptyList()
        return outermostObjects(span.substring(1, span.length - 1))
    }

    /**
     * 找出这段文本里**最外层**的配平对象（`{...}`）。
     *
     * 逐字符扫描而不是数 `}`：字符串里的花括号（`"这个集合是 {a, b}"`）与转义
     * 都不能参与配对，否则从第一个 `{` 到第一个 `}` 会切出一段废料。
     * 没配平的那条（被截断的）不返回 —— 半截内容永远不播；但**不能因此连累别的条**：
     * 所以这里先收下所有配平区间，再丢掉"被别的配平区间包住"的那些
     * （截断的输出里外层大括号一辈子不闭合，里面完整的那几条仍是候选）。
     */
    private fun outermostObjects(text: String): List<String> {
        val spans = mutableListOf<IntRange>()
        val stack = ArrayDeque<Int>()
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> stack.addLast(i)
                '}' -> stack.removeLastOrNull()?.let { spans += it..i }
            }
        }
        if (spans.isEmpty()) return emptyList()

        spans.sortWith(compareBy({ it.first }, { -it.last }))
        val kept = mutableListOf<IntRange>()
        for (span in spans) {
            if (kept.any { it.first <= span.first && it.last >= span.last }) continue
            kept += span
        }
        return kept.map { text.substring(it.first, it.last + 1) }
    }

    private fun modeRoot(text: String): JsonObject? {
        val mode = nextModeField.find(text)?.groupValues?.get(1) ?: return null
        val reason = modeReasonField.find(text)?.groupValues?.get(1).orEmpty()
        return JsonObject(
            mapOf(
                "next_mode" to JsonPrimitive(mode),
                "mode_reason" to JsonPrimitive(reason)
            )
        )
    }

    // ------------------------------------------------------------------
    // 修补

    /**
     * 定向修补。**只在原样解析失败之后才跑**，所以它的标准是"比丢掉整轮好"，
     * 而不是"对任何输入都安全"。
     *
     * 修的都是实测过的形态（探针结果见 docs/04-changes.md）：
     * - 弯引号被当引号用 `{“type”:“say”}`、单引号 `{'type':'say'}`
     *   —— `isLenient` **不管**这两种，实测都直接解析失败
     * - 全角冒号 / 全角逗号（中文模型顺手就打出来）
     * - 值后面多一个冒号 `{"type":"typing_ms":1800}`（真机上的元凶）
     * - 少一个逗号 `{"type":"say" "text":"嗯"}`
     *
     * **字符串里面一律不动** —— 中文弯引号与全角标点在正文里是正常内容
     * （`"text":"“嗯”"` 本来就是合法的）。所以要逐字符看状态，不能整体替换。
     */
    private fun normalize(text: String): String {
        val out = StringBuilder(text.length + 16)
        var close = '\u0000'          // 当前字符串的**收尾**引号；'\u0000' = 不在字符串里
        var escaped = false
        var lastSignificant = '\u0000'

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            if (close != '\u0000') {
                when {
                    escaped -> {
                        out.append(ch)
                        escaped = false
                    }
                    ch == '\\' -> {
                        out.append(ch)
                        escaped = true
                    }
                    ch == close -> {
                        // 收尾引号统一写成 `"`（弯引号、单引号在这里都被改写成直引号）
                        out.append('"')
                        close = '\u0000'
                        lastSignificant = '"'
                    }
                    else -> out.append(ch)
                }
                i++
                continue
            }

            // 字符串外的引号：直的、弯的、单的都当成字符串开始，统一改写成 `"`
            val pair = quotePair(ch)
            if (pair != null) {
                if (missingCommaBefore(text, i, lastSignificant)) out.append(',')
                out.append('"')
                close = pair
                i++
                continue
            }

            when (ch) {
                '：' -> {
                    out.append(':')
                    lastSignificant = ':'
                }
                '，' -> {
                    out.append(',')
                    lastSignificant = ','
                }
                else -> {
                    out.append(ch)
                    if (!ch.isWhitespace()) lastSignificant = ch
                }
            }
            i++
        }

        var fixed = out.toString()
        fixed = extraColon.replace(fixed) { m ->
            "\"${m.groupValues[1]}\":\"${m.groupValues[2]}\",\"ms\":"
        }
        return fixed
    }

    /**
     * 一个引号字符的**收尾**搭档。
     *
     * 刻意配成对（`“` 配 `”`）而不是"整个弯引号家族都能收尾"：
     * 正文里的中文引号是内容，`"text":"他说“嗯”"` 必须原样活着。
     * 只有配对才分得清"这一个是语法"还是"这一个是正文"。
     */
    private fun quotePair(ch: Char): Char? = when (ch) {
        '"' -> '"'
        '\'' -> '\''
        '“' -> '”'
        '”' -> '“'
        '‘' -> '’'
        '’' -> '‘'
        else -> null
    }

    /**
     * 少逗号：`{"type":"say" "text":"嗯"}`。
     *
     * 只在两个条件同时成立时才补：上一个有意义字符是一个值（字符串/数字/对象/数组），
     * 并且**眼前这一串引号后面跟着冒号**（说明它是键，不是值）。这样正文里的引号
     * 不会被误判成键。
     */
    private fun missingCommaBefore(text: String, at: Int, lastSignificant: Char): Boolean {
        val afterValue = lastSignificant == '"' || lastSignificant == '}' || lastSignificant == ']' ||
            lastSignificant.isDigit() || lastSignificant.isLetter()
        if (!afterValue) return false

        var j = at + 1
        var esc = false
        while (j < text.length) {
            val c = text[j]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' || c == '\'' || c == '“' || c == '”' || c == '‘' || c == '’' -> break
                else -> {
                    j++
                    continue
                }
            }
            j++
        }
        var k = j + 1
        while (k < text.length && text[k].isWhitespace()) k++
        return k < text.length && (text[k] == ':' || text[k] == '：')
    }

    // ------------------------------------------------------------------
    // 单条

    private sealed interface Slot {
        data class Event(val value: ScriptEvent) : Slot

        /** 独立成条的停顿，由调用方折进下一条的留白。 */
        data class Pause(val typingMs: Int?, val gapMs: Int?) : Slot
    }

    private fun parseOne(
        obj: JsonObject,
        index: Int,
        dropped: MutableList<String>
    ): Slot? {
        val rawType = obj["type"]?.asString() ?: obj["kind"]?.asString()
        val kind = EventKind.fromWire(rawType)
        if (kind == null) {
            // 先看它是不是"停顿被写成了一种事件类型"，那是模型的常见误解，不是垃圾
            pauseOf(rawType, obj)?.let { return it }
            dropped += "第 ${index + 1} 条未知类型「${rawType ?: "缺失"}」"
            return null
        }

        val content = obj["text"]?.asString()
            ?: obj["content"]?.asString()
            ?: obj["message"]?.asString()
            ?: ""

        val speaker = obj["speaker"]?.asString()
            ?: obj["who"]?.asString()
            ?: ""

        // `wait` / `read` 允许没有正文；其余类型正文空 = 无意义，丢掉
        if (kind != EventKind.WAIT && kind != EventKind.READ && content.isBlank()) {
            dropped += "第 ${index + 1} 条（${kind.wire}）正文为空"
            return null
        }
        // `scene` 是无人称旁白，带 speaker 是模型理解偏差，直接抹掉而不是报错
        val normalizedSpeaker = if (kind == EventKind.SCENE) "" else speaker.trim()

        return Slot.Event(
            ScriptEvent(
                kind = kind,
                speaker = normalizedSpeaker,
                text = content.trim(),
                gapHintMs = obj.intOf("gap_ms", "gap", "delay_ms")?.coerceIn(gapRange),
                typingHintMs = obj.intOf("typing_ms", "typing", "typing_delay_ms")
                    ?.coerceIn(typingRange),
                waitReason = obj["reason"]?.asString().orEmpty().trim()
            )
        )
    }

    /**
     * 把"停顿时长"当成一种事件类型的写法认下来。
     *
     * 协议里 `typing_ms` / `gap_ms` 是**挂在事件上的字段**，但模型很自然会把它当成
     * 一种独立事件 —— 真机上两种写法都出现过：
     *
     * ```
     * { "type": "typing_ms", "ms": 2500 }     ← 合法 JSON，类型不存在
     * { "type": "typing_ms": 2500 }           ← 连 JSON 都不合法（09:16 与 09:20 两次）
     * ```
     *
     * 意图是明确的（"这里停 2.5 秒"），代价却曾经是整轮。所以这里认下来，
     * 由调用方折进下一条的留白 —— **不新增存储类型，也不改播放器**。
     *
     * 没有数字的（比如 `{"type":"pause","text":"..."}`）不当停顿：那更可能是模型
     * 自创的别的类型，按未知类型丢掉更诚实。
     */
    private fun pauseOf(rawType: String?, obj: JsonObject): Slot.Pause? {
        val type = rawType?.trim()?.lowercase() ?: return null
        val ms = obj.intOf("ms", "value", "duration", "typing_ms", "gap_ms", "text", "n") ?: return null
        return when (type) {
            "typing_ms", "typing", "typing_delay_ms", "pause", "pause_ms",
            "silence", "silence_ms", "beat", "wait_ms" ->
                Slot.Pause(typingMs = ms.coerceIn(typingRange), gapMs = null)

            "gap_ms", "gap", "delay", "delay_ms" ->
                Slot.Pause(typingMs = null, gapMs = ms.coerceIn(gapRange))

            else -> null
        }
    }

    private fun parseMode(obj: JsonObject?): ChatMode? {
        val raw = obj?.get("next_mode")?.asString()?.trim()?.lowercase() ?: return null
        return when (raw) {
            "chat", "message", "messaging" -> ChatMode.CHAT
            "scene", "face", "face_to_face", "meeting" -> ChatMode.SCENE
            else -> null    // 未知值当不表态，不要瞎切
        }
    }

    private fun degradedSay(text: String, reason: String): Script {
        val body = text.trim()
        if (body.isEmpty()) {
            return Script(events = emptyList(), rawText = text, dropped = listOf(reason))
        }
        return Script(
            events = listOf(ScriptEvent(kind = EventKind.SAY, text = body)),
            rawText = text,
            dropped = listOf(reason),
            degraded = true
        )
    }

    // ------------------------------------------------------------------

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOf(vararg keys: String): Int? {
        for (k in keys) {
            val v = this[k] ?: continue
            runCatching { v.jsonPrimitive.intOrNull }.getOrNull()?.let { return it }
            // 模型偶尔会把数字写成字符串
            runCatching { v.jsonPrimitive.contentOrNull?.toIntOrNull() }.getOrNull()?.let { return it }
        }
        return null
    }
}
