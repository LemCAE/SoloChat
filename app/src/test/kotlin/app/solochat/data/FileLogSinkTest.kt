package app.solochat.data

import app.solochat.core.log.LogLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLogSinkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `重建文件日志器 - 中文换行和 emoji 原文仍可导出`() {
        val directory = temporary.newFolder()
        val body = "中文\n引号\"和 emoji 😀"
        FileLogSink(directory).write(123, LogLevel.WARN, "模型", body)
        val record = Json.parseToJsonElement(FileLogSink(directory).export().trim()).jsonObject
        assertEquals(body, record.getValue("text").jsonPrimitive.content)
        assertEquals("123", record.getValue("at").jsonPrimitive.content)
        assertEquals(1, directory.listFiles()!!.size)
    }
    @Test fun `超过预算轮转 - 只留最近三份，按旧到新导出且每行仍可解析`() {
        val directory = temporary.newFolder()
        val sink = FileLogSink(directory, maxBytes = 100, fileCount = 3)
        repeat(7) { sink.write(it.toLong(), LogLevel.INFO, "测试", "第 $it 条" + "字".repeat(12)) }
        assertEquals(3, directory.listFiles()!!.size)
        val times = sink.export().lineSequence().filter { it.isNotBlank() }.map {
            Json.parseToJsonElement(it).jsonObject.getValue("at").jsonPrimitive.content.toInt()
        }.toList()
        assertEquals(listOf(4, 5, 6), times)
    }
}
