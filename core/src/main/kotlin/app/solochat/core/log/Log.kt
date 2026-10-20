package app.solochat.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条日志。
 *
 * [text] 用换行分隔的"键: 值"列表而不是一个长字符串：界面可以折叠长内容
 * （比如完整提示词），而复制出来也还能看。
 */
data class LogEntry(
    val seq: Long,
    val at: Long,
    val level: LogLevel,
    val tag: String,
    val text: String
) {
    val clock: String get() = Log.formatClock(at)
}

/**
 * 把日志同时送到别处（例如 logcat）。
 *
 * 由 App 侧注入，**core 里不出现任何 Android API** —— `core` 是纯 JVM 模块，
 * 一旦引入 `android.util.Log`，它的单元测试就全跑不起来了。
 */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, text: String)
}

/**
 * 内存日志。
 *
 * 三个刻意的设计：
 *
 * 1. **内存环形缓冲**。文件落盘由 App 注入的 sink 异步负责，core 不处理磁盘策略。
 * 2. **完整提示词也进日志**。这是排查"模型怎么老回一个字"的唯一手段 ——
 *    必须能看见真正发出去的东西，而不是猜。设置里给了开关，默认开。
 * 3. **同步写入 + StateFlow**。写日志发生在协程里，但操作只是往列表尾部加一条，
 *    用同步块足够；用 Mutex 反而会把挂起语义带进播放路径
 *    （这个坑在 SessionController 上已经踩过一次）。
 */
object Log {

    /**
     * 保留多少条。300 条大约覆盖几十轮来回。
     *
     * 从 600 收到 300 是因为 [MAX_TEXT] 放开了：单条正文现在可以到几万字符，
     * 而一轮里只有两条是大的（提示词 + 原始输出），其余都是几十字符的小行。
     * 这样最坏情况下的常驻内存仍是个位数 MB。
     */
    const val CAPACITY = 300

    /**
     * 单条内容上限。提示词可能很长，但整段丢掉会让排查无从下手，
     * 所以是截断而不是拒绝。
     *
     * ⚠️ **这个数字必须跟得上历史的增长**（D10 之后历史不设上限）：真机上实测
     * 一次请求的提示词已经 **37347 字符**，而这里原来是 20000 —— 于是"完整提示词"
     * 只记了前一半，而被截掉的正好是**历史 + 变动区**（提示词的尾部）。
     * 后果不是"少看一点"，是**排查手段失效**：KV 缓存要逐字节比两轮的提示词，
     * 比不了尾巴；我自己就被它坑了一次（想确认"历史里是不是有模型自己写坏的写法"，
     * 日志里看不到，只能绕道去查消息表）。
     */
    const val MAX_TEXT = 64_000

    private val buffer = ArrayDeque<LogEntry>(CAPACITY + 1)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** 应用版本。启动时注入，导出的日志头部会带上它。 */
    @Volatile
    var version: String = "未知"

    /** 是否记录完整正文（提示词与模型原始输出）。设置页可关。 */
    @Volatile
    var verbose: Boolean = true

    /** 额外的输出目标（logcat）。 */
    @Volatile
    var sink: LogSink? = null

    private var seq = 0L

    fun debug(tag: String, message: String) = write(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = write(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = write(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = write(LogLevel.ERROR, tag, message)

    /** 多行"键: 值"形式，便于折叠阅读。 */
    fun fields(tag: String, level: LogLevel = LogLevel.INFO, vararg pairs: Pair<String, Any?>) {
        val text = pairs.joinToString("\n") { (key, value) ->
            "$key: ${value?.toString() ?: "-"}"
        }
        write(level, tag, text)
    }

    /**
     * 记录长正文（提示词、模型原始输出）。受 [verbose] 控制。
     * [label] 用于在界面上区分是哪一份内容。
     */
    fun body(tag: String, label: String, content: String) {
        if (!verbose) return
        val clipped = if (content.length > MAX_TEXT) {
            content.take(MAX_TEXT) + "\n…（已截断，共 ${content.length} 字符）"
        } else {
            content
        }
        write(LogLevel.DEBUG, tag, "$label（${content.length} 字符）:\n$clipped")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** 导出成纯文本，用于"复制全部"。带版本号 —— 贴日志时不必再说明版本。 */
    fun export(entries: List<LogEntry> = _entries.value): String = buildString {
        appendLine("SoloChat $version")
        appendLine("导出时间: ${formatClock(System.currentTimeMillis())}")
        appendLine("共 ${entries.size} 条")
        appendLine("─".repeat(40))
        for (entry in entries) {
            append(entry.clock)
            append(" [")
            append(entry.level.name)
            append("] ")
            append(entry.tag)
            append(": ")
            appendLine(entry.text)
        }
    }

    private fun write(level: LogLevel, tag: String, message: String) {
        val entry = synchronized(lock) {
            seq += 1
            val e = LogEntry(
                seq = seq,
                at = System.currentTimeMillis(),
                level = level,
                tag = tag,
                text = message
            )
            buffer.addLast(e)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _entries.value = buffer.toList()
            e
        }
        // 打到 logcat。用 runCatching 包住：日志本身绝不能把业务搞崩。
        runCatching { sink?.write(level, tag, entry.text) }
    }

    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun formatClock(at: Long): String = synchronized(clockFormat) {
        clockFormat.format(Date(at))
    }
}
