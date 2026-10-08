package app.solochat.data.persona

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.solochat.core.model.Persona
import app.solochat.core.persona.PersonaDocument
import app.solochat.core.persona.PersonaImport
import java.io.IOException
import java.util.UUID

/**
 * 人设文档的**读取**（Android 侧）。
 *
 * 解析本身在 `core/persona/PersonaDocument`，是纯函数、能在普通 JVM 测试里跑；
 * 这里只做两件必须碰 Android 的事：按内容 URI 读字符、把解析结果变成可入库的实体。
 *
 * 这个划分是有意的：**"读文件"是不需要测试的胶水，"读懂文档"才是必错点**。
 * 参考 `ModelPricing` 被拆成 `PricingRules`（纯逻辑）+ 读 assets（胶水）的做法。
 */
object PersonaImporter {

    /**
     * 单份文档的字符上限（256 KB）。
     *
     * 人设是给人读的几段话，正常一两千字。超过这个数只可能是选错了文件
     * （把整个聊天记录或者某本小说选进来了）—— 那种情况要**明确报错**，
     * 而不是默默读进来几百 KB 再塞进提示词。
     */
    const val MAX_CHARS = 256 * 1024

    /**
     * 文档的显示名，去掉扩展名。
     *
     * 它的用途是"文档里没写名字"时的兜底 —— 「小默.md」里的名字几乎一定就是小默。
     * 拿不到就返回空串，调用方会退回 [app.solochat.core.persona.PersonaDraft.DEFAULT_NAME]。
     */
    fun displayName(context: Context, uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0 || !cursor.moveToFirst()) return@use ""
                cursor.getString(index).orEmpty()
            }.orEmpty()
        }.getOrNull().orEmpty()
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
        return name.substringBeforeLast('.', name).trim()
    }

    /**
     * 读成文本。**失败会抛 [IOException]**（打不开、太大），由界面显示原因 ——
     * 这里刻意不吞异常：用户选了文件却什么都没发生，是最难排查的一种表现。
     */
    fun read(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("打不开这个文件")
        return stream.use { input ->
            val reader = input.bufferedReader(Charsets.UTF_8)
            val buffer = CharArray(8 * 1024)
            val out = StringBuilder()
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                out.append(buffer, 0, n)
                if (out.length > MAX_CHARS) {
                    throw IOException("文档超过 ${MAX_CHARS / 1024} KB，像是选错文件了")
                }
            }
            out.toString()
        }
    }

    /** 解析。纯函数，只是把 fallbackName 传下去。 */
    fun parse(raw: String, fallbackName: String?): PersonaImport =
        PersonaDocument.parse(raw, fallbackName)

    /**
     * 解析结果 → 可入库的人物卡。
     *
     * 每次导入都**新建 id**：文档里就算写了 `id` 也不复用。
     * 复用 id 意味着"导入"会静默覆盖用户自己改过的那张卡 —— 而导入是一件
     * 用户预期"只增不改"的操作。想更新就在人物卡页里手改。
     */
    fun toPersonas(result: PersonaImport, now: Long): List<Persona> =
        result.personas.map { it.toPersona(UUID.randomUUID().toString(), now) }

    /** 已有的人物卡名，用来在预览里提醒"会重名"。 */
    fun namesConflict(imported: List<Persona>, existing: List<Persona>): List<String> {
        val taken = existing.map { it.name.trim() }.toSet()
        return imported.map { it.name.trim() }.filter { it.isNotEmpty() && it in taken }.distinct()
    }
}
