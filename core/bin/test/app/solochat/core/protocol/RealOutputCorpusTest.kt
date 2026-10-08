package app.solochat.core.protocol

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真机语料回归：把设备上**曾经解析失败**的模型输出逐条喂给解析器。
 *
 * 语料不在版本库里（那是真实聊天内容），由 `tools/dump-degraded.ps1` 拉到
 * `.tmp/degraded/`；目录不在时这个类会**跳过**（assume），所以它不会拦住别人构建。
 *
 * ⚠️ **跳过不等于通过。** 想确认语料真的被跑过，看这一次 BUILD 的输出里
 * `RealOutputCorpusTest` 是 PASSED 还是 SKIPPED —— 02-handoff 里写着这个坑。
 *
 * 断言的口径刻意是"**每一份都不该再降级**"：这些正文当初之所以被当成一条纯文本消息
 * 发出去，都是因为解析层太脆。救不回来的那一份就是**新发现的形态**，
 * 应当照着它加解析层（或者像"未转义的引号"那样，明确写成已知边界）。
 */
class RealOutputCorpusTest {

    @Test
    fun `设备上曾经降级的输出 - 一份都不该再降级`() {
        val dir = File("../.tmp/degraded").absoluteFile
        assumeTrue("没有语料（先跑 pwsh tools/dump-degraded.ps1）：$dir", dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("语料目录是空的：$dir", files.isNotEmpty())

        val stillDegraded = mutableListOf<String>()
        val empty = mutableListOf<String>()
        for (file in files) {
            val script = ScriptParser.parse(file.readText())
            // 逐份打出来：只看"没降级"是不够的 —— 抢救成一坨垃圾也算"没降级"
            println(
                "  ${file.name}: 事件 ${script.events.size}" +
                    " | 首条 ${script.events.firstOrNull()?.let { "${it.kind.wire}「${it.text.take(24)}」" } ?: "-"}" +
                    " | 留白 ${script.events.count { it.typingHintMs != null || it.gapHintMs != null }}" +
                    " | 备注 ${script.dropped.size}"
            )
            when {
                script.degraded -> stillDegraded += "${file.name}（${script.dropped.firstOrNull()}）"
                script.events.isEmpty() -> empty += file.name
            }
        }

        println("真机语料：${files.size} 份，仍降级 ${stillDegraded.size}，解析成空 ${empty.size}")
        assertTrue(
            "还有 ${stillDegraded.size} 份救不回来：\n" + stillDegraded.joinToString("\n"),
            stillDegraded.isEmpty()
        )
        assertTrue(
            "有 ${empty.size} 份解析出来是空的（既没降级也没内容）：\n" + empty.joinToString("\n"),
            empty.isEmpty()
        )
    }

    /**
     * 同一批真机语料再过一遍**历史脏行还原**（[DegradedTurn]）：27 份都应该能拆开，
     * 而且**每一条正文都能在原文里原样找到**。
     *
     * 后一条才是重点：拆错类型只是个头不对，改写正文就是篡改聊天记录。
     * 另外对一下条数 —— 合法类型（say/act/scene/wait/read/remember）各对应一条事件，
     * 而独立成条的 `typing_ms` / `gap_ms` 会被折成留白、不产生事件，所以用正则数
     * `"type": "…"` 里**属于事件类型**的那些当预期值。
     */
    @Test
    fun `真机语料 - 每一份都能拆开，而且一个字符都没改过`() {
        val dir = File("../.tmp/degraded").absoluteFile
        assumeTrue("没有语料（先跑 pwsh tools/dump-degraded.ps1）：$dir", dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("语料目录是空的：$dir", files.isNotEmpty())

        val eventType = Regex("\"type\"\\s*:\\s*\"(say|act|scene|wait|read|remember)\"")
        val problems = mutableListOf<String>()
        for (file in files) {
            val raw = file.readText()
            val parts = DegradedTurn.split(raw)
            if (parts == null) {
                problems += "${file.name}: 拆不开（应当能拆）"
                continue
            }
            val expected = eventType.findAll(raw).count()
            if (parts.size != expected) {
                problems += "${file.name}: 拆出 ${parts.size} 条，原文里有 $expected 条事件"
            }
            for (part in parts) {
                if (!raw.contains(part.text)) {
                    problems += "${file.name}: 正文被改写「${part.text.take(20)}」"
                }
            }
        }
        println("历史脏行还原：${files.size} 份，问题 ${problems.size}")
        assertTrue("有 ${problems.size} 份不对：\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
