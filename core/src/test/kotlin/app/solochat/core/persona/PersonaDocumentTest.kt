package app.solochat.core.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设文档解析的回归测试。
 *
 * 铁律和 `ScriptParser` 一样：**永远不抛异常，永远有可用结果**。
 * 但这里的用例分布和它不同 —— 输入不是模型的一次输出，而是**用户从别处拿来的文档**，
 * 排版千奇百怪且用户不会读规范。所以一半用例在钉"宽松识别"：
 * 别名标题、`键：值` 表单、代码块包裹、emoji 头像、多张卡。
 *
 * 另一半在钉**不能读错**的地方：
 * - 「里设定」不能被当成「它是谁」（它俩在提示词里的要求正好相反）
 * - 文档里的示例代码块不能被当成人设导进来
 * - `你是谁：一个普通人` 的冒号后面不能丢
 */
class PersonaDocumentTest {

    /** 大多数用例只关心"恰好读出 1 张、内容是什么"。 */
    private fun one(raw: String, fallbackName: String? = null): PersonaDraft {
        val result = PersonaDocument.parse(raw, fallbackName)
        assertEquals(
            "应当恰好读出 1 张卡。实际 ${result.personas.size} 张，告警：${result.warnings}",
            1,
            result.personas.size
        )
        return result.personas.first()
    }

    // ------------------------------------------------------------------
    // 标准形状
    // ------------------------------------------------------------------

    @Test
    fun `Markdown 文档读出四段`() {
        val doc = """
            # 小默
            > 话不多，但一直在

            ## 你是谁
            一个普通人，有工作有作息。

            ## 你怎么说话
            用短句，会省略。

            ## 关系与边界
            可以不同意。

            ## 只有你知道的事
            她其实很在意。
        """.trimIndent()

        val d = one(doc)
        assertEquals("小默", d.name)
        assertEquals("话不多，但一直在", d.tagline)
        assertEquals("一个普通人，有工作有作息。", d.identity)
        assertEquals("用短句，会省略。", d.voice)
        assertEquals("可以不同意。", d.boundaries)
        assertEquals("她其实很在意。", d.secretNotes)
        assertEquals(listOf("它是谁", "怎么说话", "关系与边界", "只有它知道的事"), d.filledSections())
    }

    @Test
    fun `标题里的 emoji 变成头像`() {
        val d = one("# 🌙 小默\n\n## 你是谁\n一个普通人。")
        assertEquals("🌙", d.avatar)
        assertEquals("小默", d.name)
    }

    @Test
    fun `头像写在名字后面也认`() {
        val d = one("# 小默 🌙\n\n## 你是谁\n一个普通人。")
        assertEquals("🌙", d.avatar)
        assertEquals("小默", d.name)
    }

    @Test
    fun `全角冒号不会被当成头像`() {
        val d = one("# 小默：\n\n## 你是谁\n一个普通人。")
        assertEquals("小默", d.name)
        assertEquals("", d.avatar)
    }

    @Test
    fun `简介也可以是一个独立小节`() {
        val d = one("# 小默\n\n## 一句话简介\n话不多\n\n## 你是谁\n一个普通人。")
        assertEquals("话不多", d.tagline)
    }

    @Test
    fun `斜体行也当作一句话简介`() {
        val d = one("# 小默\n*话不多，但一直在*\n\n## 你是谁\n一个普通人。")
        assertEquals("话不多，但一直在", d.tagline)
    }

    // ------------------------------------------------------------------
    // 宽松识别：别名、表单、围栏、多张卡
    // ------------------------------------------------------------------

    @Test
    fun `别名标题都能认`() {
        val doc = """
            # 阿岩
            ## 身份
            开出租车的。

            ## 语气
            很直。

            ## 边界
            不聊政治。

            ## 里设定
            怕黑。
        """.trimIndent()

        val d = one(doc)
        assertEquals("开出租车的。", d.identity)
        assertEquals("很直。", d.voice)
        assertEquals("不聊政治。", d.boundaries)
        assertEquals("怕黑。", d.secretNotes)
    }

    @Test
    fun `标题同时含设定二字时先判里设定`() {
        // 「只有她知道的事（里设定）」里既含「只有她」也含「设定」。
        // 判成 IDENTITY 会让"要求不要直接说出来的内容"混进"它是谁"，那是两类内容互相污染。
        val doc = """
            # 阿岩
            ## 身份与背景
            开出租车的。

            ## 只有她知道的事（里设定）
            怕黑。
        """.trimIndent()

        val d = one(doc)
        assertEquals("开出租车的。", d.identity)
        assertEquals("怕黑。", d.secretNotes)
    }

    @Test
    fun `键值对表单也认`() {
        val doc = """
            名字: 阿岩
            头像: 🔥
            简介: 说话很直
            你是谁: 一个开出租车的
            怎么说话: 一句话不超过十个字
            关系与边界: 不聊政治
            里设定: 他其实怕黑
        """.trimIndent()

        val d = one(doc)
        assertEquals("阿岩", d.name)
        assertEquals("🔥", d.avatar)
        assertEquals("说话很直", d.tagline)
        assertEquals("一个开出租车的", d.identity)
        assertEquals("一句话不超过十个字", d.voice)
        assertEquals("不聊政治", d.boundaries)
        assertEquals("他其实怕黑", d.secretNotes)
    }

    @Test
    fun `列表符号开头的键值对也认`() {
        val d = one("# 阿岩\n- 头像：🔥\n- 你是谁：开出租车的。")
        assertEquals("🔥", d.avatar)
        assertEquals("开出租车的。", d.identity)
    }

    @Test
    fun `冒号后面为空时后面的行会续上这一段`() {
        val doc = """
            # 阿岩

            你是谁:
            一个开出租车的，四十岁。
            白天睡觉，晚上出车。

            怎么说话:
            短句，爱用省略号。
        """.trimIndent()

        val d = one(doc)
        assertEquals("一个开出租车的，四十岁。\n白天睡觉，晚上出车。", d.identity)
        assertEquals("短句，爱用省略号。", d.voice)
    }

    @Test
    fun `正文里像键值对的句子不会丢`() {
        val doc = """
            # 阿岩
            ## 你是谁
            他说：我不去。
            目标：考研
        """.trimIndent()

        val d = one(doc)
        assertTrue("实际：${d.identity}", d.identity.contains("他说：我不去。"))
        assertTrue("实际：${d.identity}", d.identity.contains("目标：考研"))
    }

    @Test
    fun `整份文档被代码块包住也能读`() {
        val d = one("```markdown\n# 小默\n\n## 你是谁\n一个普通人。\n```")
        assertEquals("小默", d.name)
        assertEquals("一个普通人。", d.identity)
    }

    @Test
    fun `正文没结构时改用文档里的代码块`() {
        val doc = "这是你要的人设，直接导入就行：\n\n```\n# 小默\n## 你是谁\n一个普通人。\n```\n"
        val result = PersonaDocument.parse(doc)
        assertEquals(1, result.personas.size)
        assertEquals("小默", result.personas.first().name)
        assertEquals("一个普通人。", result.personas.first().identity)
    }

    @Test
    fun `文档里的示例代码块不会变成人设`() {
        // 这是 docs 里那份格式说明的形状：正文是一张卡，后面跟一段示例模板。
        // 示例必须被整段忽略，否则用户会导进来一张叫「示例」的空卡。
        val doc = """
            # 小默

            ## 你是谁
            一个普通人。

            下面只是格式示例，不要照抄：

            ```
            # 示例名字
            ## 你是谁
            示例内容
            ```
        """.trimIndent()

        val result = PersonaDocument.parse(doc)
        assertEquals("应当只读出正文那一张。实际：${result.personas.map { it.name }}", 1, result.personas.size)
        val d = result.personas.first()
        assertEquals("小默", d.name)
        assertFalse("示例内容漏进了正文：${d.identity}", d.identity.contains("示例内容"))
        assertTrue(result.personas.none { it.name == "示例名字" })
    }

    @Test
    fun `一份文档里的多张卡会一起导入`() {
        val doc = """
            # 小默
            ## 你是谁
            一个普通人。

            # 阿岩
            ## 你是谁
            开出租车的。

            ## 只有你知道的事
            怕黑。
        """.trimIndent()

        val result = PersonaDocument.parse(doc)
        assertEquals(2, result.personas.size)
        assertEquals("小默", result.personas[0].name)
        assertEquals("阿岩", result.personas[1].name)
        assertEquals("怕黑。", result.personas[1].secretNotes)
        assertTrue(result.warnings.any { it.contains("2 张") })
    }

    @Test
    fun `只有名字的空卡会被跳过并说明`() {
        val doc = "# 小默\n\n# 阿岩\n## 你是谁\n开出租车的。"
        val result = PersonaDocument.parse(doc)
        assertEquals(1, result.personas.size)
        assertEquals("阿岩", result.personas.first().name)
        assertTrue("告警：${result.warnings}", result.warnings.any { it.contains("只有名字") })
    }

    @Test
    fun `CRLF 换行也能读`() {
        val d = one("# 小默\r\n> 话不多\r\n\r\n## 你是谁\r\n一个普通人。\r\n")
        assertEquals("小默", d.name)
        assertEquals("话不多", d.tagline)
        assertEquals("一个普通人。", d.identity)
    }

    // ------------------------------------------------------------------
    // 降级路径
    // ------------------------------------------------------------------

    @Test
    fun `没有小节标题时整段当成人设正文并说明`() {
        val result = PersonaDocument.parse("# 小默\n她是个普通人，话不多，爱打游戏。")
        assertEquals(1, result.personas.size)
        val d = result.personas.first()
        assertEquals("她是个普通人，话不多，爱打游戏。", d.identity)
        assertEquals("", d.voice)
        assertTrue("告警：${result.warnings}", result.warnings.any { it.contains("小节标题") })
    }

    @Test
    fun `没名字时用文件名兜底`() {
        val d = one("## 你是谁\n一个开出租车的。\n\n## 你怎么说话\n短句。", fallbackName = "阿岩")
        assertEquals("阿岩", d.name)
    }

    @Test
    fun `没名字也没文件名时落库用兜底名`() {
        val d = one("## 你是谁\n一个普通人。")
        assertEquals("", d.name)
        assertEquals(PersonaDraft.DEFAULT_NAME, d.toPersona("id-1", 0L).name)
    }

    @Test
    fun `导入的一律不是出厂卡`() {
        val persona = one("# 小默\n## 你是谁\n一个普通人。").toPersona("id-1", 123L)
        assertFalse(persona.builtin)
        assertEquals("id-1", persona.id)
        assertEquals(123L, persona.createdAt)
    }

    @Test
    fun `头像缺省时给一个默认 emoji`() {
        val persona = one("# 小默\n## 你是谁\n一个普通人。").toPersona("id-1", 0L)
        assertEquals("🙂", persona.avatar)
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    @Test
    fun `JSON 单个对象`() {
        val doc = """
            {"name":"阿岩","头像":"🔥","identity":"开出租车的。",
             "voice":"很直。","boundaries":"不聊政治。","secretNotes":"怕黑。"}
        """.trimIndent()

        val result = PersonaDocument.parse(doc)
        assertEquals("json", result.format)
        val d = result.personas.first()
        assertEquals("阿岩", d.name)
        assertEquals("🔥", d.avatar)
        assertEquals("开出租车的。", d.identity)
        assertEquals("很直。", d.voice)
        assertEquals("不聊政治。", d.boundaries)
        assertEquals("怕黑。", d.secretNotes)
    }

    @Test
    fun `JSON 的 personas 数组与裸数组都认`() {
        val wrapped = PersonaDocument.parse("""{"personas":[{"name":"甲","identity":"一"},{"name":"乙","identity":"二"}]}""")
        assertEquals(2, wrapped.personas.size)
        assertEquals("甲", wrapped.personas[0].name)

        val bare = PersonaDocument.parse("""[{"name":"甲","identity":"一"}]""")
        assertEquals(1, bare.personas.size)
        assertEquals("一", bare.personas.first().identity)
    }

    @Test
    fun `JSON 里的中文键也认`() {
        val d = one("""{"名字":"甲","你是谁":"一","怎么说话":"短句","只有你知道的事":"秘密"}""")
        assertEquals("甲", d.name)
        assertEquals("一", d.identity)
        assertEquals("短句", d.voice)
        assertEquals("秘密", d.secretNotes)
    }

    @Test
    fun `坏 JSON 会退回 Markdown 而不是报错`() {
        val doc = "{\"name\": \"阿岩\"\n\n# 小默\n## 你是谁\n一个普通人。"
        val result = PersonaDocument.parse(doc)
        assertEquals(1, result.personas.size)
        val d = result.personas.first()
        assertEquals("小默", d.name)
        assertEquals("一个普通人。", d.identity)
        assertTrue("告警：${result.warnings}", result.warnings.any { it.contains("没解析成功") })
    }

    @Test
    fun `JSON 里没内容的条目会被跳过`() {
        val result = PersonaDocument.parse("""{"personas":[{"name":"空卡"},{"name":"甲","identity":"一"}]}""")
        assertEquals(1, result.personas.size)
        assertEquals("甲", result.personas.first().name)
        assertTrue("告警：${result.warnings}", result.warnings.any { it.contains("已跳过") })
    }

    // ------------------------------------------------------------------
    // 永不抛异常 / 往返
    // ------------------------------------------------------------------

    @Test
    fun `空文档给出理由而不是崩`() {
        for (raw in listOf(null, "", "   ", "\n\n", "\t")) {
            val result = PersonaDocument.parse(raw)
            assertTrue("「$raw」不该读出人设", result.isEmpty)
            assertTrue("「$raw」应当给一条理由", result.warnings.isNotEmpty())
        }
    }

    @Test
    fun `乱七八糟的输入永远不抛异常`() {
        val junk = listOf(
            "```", "```json", "# ", "##", "###", "::::", "{{{{", "[]", "{}", "null", "- - -",
            "> ", "|表格|表格|", "\u0000\uFFFD", "# 名字\n```\n未闭合的围栏",
            "你是谁", "## 你是谁\n## 你是谁\n## 你是谁", "# 名字\n\uD800 落单的代理项"
        )
        for (raw in junk) {
            val result = PersonaDocument.parse(raw)
            // 只要不抛异常就算过；顺带钉住"读出来的每张卡都必须有正文"
            assertTrue("「$raw」读出了空卡", result.personas.all { it.hasContent })
        }
    }

    @Test
    fun `写出去的文档能被自己读回来`() {
        val draft = PersonaDraft(
            name = "阿岩",
            tagline = "说话很直",
            avatar = "🔥",
            identity = "开出租车的，四十岁。",
            voice = "短句，爱用省略号。",
            sceneVoice = "当面更少话，靠动作。",
            mannerisms = "紧张时敲桌沿。",
            surroundings = "桌上一只凉了的杯子。",
            depiction = "偏听觉，短句，不用比喻。",
            boundaries = "不聊政治。",
            secretNotes = "怕黑。"
        )
        val back = one(PersonaDocument.toJson(draft))
        assertEquals(draft, back)
    }

    @Test
    fun `导出的文件带 format 与 version 头`() {
        val json = PersonaDocument.toJson(PersonaDraft(name = "甲", identity = "一"))
        assertTrue("导出必须是正式格式的头。实际：$json", json.contains("\"format\": \"${PersonaDocument.FORMAT_ID}\""))
        assertTrue("要带版本号，将来才好判新旧。实际：$json", json.contains("\"version\": ${PersonaDocument.FORMAT_VERSION}"))
        // 空段落也要写出来 —— "文件自己就是文档"是选 JSON 的主要理由
        assertTrue("空字段也必须在场。实际：$json", json.contains("\"surroundings\": \"\""))
        assertTrue("空字段也必须在场。实际：$json", json.contains("\"depiction\": \"\""))
    }

    @Test
    fun `模板本身是一份能用的文档`() {
        val result = PersonaDocument.parse(PersonaDocument.template())
        assertEquals("json", result.format)
        assertEquals(1, result.personas.size)
        val d = result.personas.first()
        assertEquals("小默", d.name)
        assertEquals("🌙", d.avatar)
        assertEquals("模板要把八段都示范一遍", 8, d.filledSections().size)
    }

    // ------------------------------------------------------------------
    // 面对面那几段（v3 → v4 加的素材，v4 → v5 加的笔法）
    // ------------------------------------------------------------------

    @Test
    fun `JSON 里面对面那四段用英文键读`() {
        val json = """
            {"name":"阿岩",
             "sceneVoice":"当面更少话。",
             "mannerisms":"紧张时敲桌沿。",
             "surroundings":"桌上一只凉了的杯子。",
             "depiction":"偏听觉，短句，不用比喻。"}
        """.trimIndent()
        val d = one(json)
        assertEquals("当面更少话。", d.sceneVoice)
        assertEquals("紧张时敲桌沿。", d.mannerisms)
        assertEquals("桌上一只凉了的杯子。", d.surroundings)
        assertEquals("偏听觉，短句，不用比喻。", d.depiction)
    }

    @Test
    fun `面对面那四段的中文键也认`() {
        val d = one(
            """{"名字":"阿岩","面对面时怎么说话":"当面更少话。","习惯与小动作":"敲桌沿。",""" +
                """"它身边的样子":"凉了的杯子。","怎么描写动作与景物":"偏听觉。"}"""
        )
        assertEquals("当面更少话。", d.sceneVoice)
        assertEquals("敲桌沿。", d.mannerisms)
        assertEquals("凉了的杯子。", d.surroundings)
        assertEquals("偏听觉。", d.depiction)
    }

    /**
     * 这条钉的是**别名表的判定顺序**，而且每一条都对应一种真实会读错的写法：
     * 「面对面时怎么说话」里含「说话」（会被当成打字习惯），「习惯与小动作」里含
     * 「动作」而边界那边有「不该做的事」的句式，「环境/场景」这类词最泛，
     * 「动作与景物怎么写」里又含「动作」（会被当成素材库）。
     */
    @Test
    fun `面对面那几段不会被认成别的段`() {
        val doc = """
            # 阿岩
            ## 面对面时怎么说话
            当面更少话。

            ## 习惯与小动作
            紧张时敲桌沿。

            ## 它身边的样子
            桌上一只凉了的杯子。

            ## 怎么描写动作与景物
            偏听觉，短句。

            ## 你怎么说话
            打字时用短句。

            ## 关系与边界
            不聊政治。
        """.trimIndent()

        val d = one(doc)
        assertEquals("当面更少话。", d.sceneVoice)
        assertEquals("紧张时敲桌沿。", d.mannerisms)
        assertEquals("桌上一只凉了的杯子。", d.surroundings)
        assertEquals("偏听觉，短句。", d.depiction)
        assertEquals("打字时用短句。", d.voice)
        assertEquals("不聊政治。", d.boundaries)
    }

    /**
     * 「怎么写」是**谈笔法**的信号，必须优先于「动作」这个素材信号。
     *
     * 判反了的后果很隐蔽：动作库那一段会收到一句"要短句、别用比喻"（对素材库没意义），
     * 而真正该管笔法的那一段是空的 —— 模型于是既没拿到笔法，素材段还被污染了。
     */
    @Test
    fun `谈写法的标题归怎么描写 - 不归动作库`() {
        assertEquals("短句，不用比喻。", one("# 甲\n## 动作怎么写\n短句，不用比喻。").depiction)
        assertEquals("偏听觉。", one("# 甲\n## 描写方式\n偏听觉。").depiction)
        assertEquals("冷一点。", one("# 甲\n## 笔调\n冷一点。").depiction)
        // 但"素材"那一类不能被抢走
        assertEquals("敲桌沿。", one("# 甲\n## 小动作\n敲桌沿。").mannerisms)
        assertEquals("敲桌沿。", one("# 甲\n## 习惯与小动作\n敲桌沿。").mannerisms)
    }

    @Test
    fun `环境描写与景物描写仍归它身边的样子`() {
        // 「…描写」既是素材也是写法，这里刻意判给**素材**：它回答的是"写什么"
        assertEquals("屋里只开了一盏灯。", one("# 甲\n## 环境描写\n屋里只开了一盏灯。").surroundings)
        assertEquals("窗外的树。", one("# 甲\n## 景物描写\n窗外的树。").surroundings)
        assertEquals("屋里只开了一盏灯。", one("# 甲\n## 环境\n屋里只开了一盏灯。").surroundings)
        assertEquals("桌上有个摆件。", one("# 甲\n## 场景\n桌上有个摆件。").surroundings)
        // 「背景」是身份那边的，不能被"环境/场景"那套抢走
        assertEquals("开出租车的。", one("# 甲\n## 背景\n开出租车的。").identity)
    }

    @Test
    fun `format 不认识时给告警但仍然读`() {
        val result = PersonaDocument.parse(
            """{"format":"someone.else","version":1,"personas":[{"name":"甲","identity":"一"}]}"""
        )
        assertEquals(1, result.personas.size)
        assertEquals("一", result.personas.first().identity)
        assertTrue("告警：${result.warnings}", result.warnings.any { it.contains("someone.else") })
    }

    @Test
    fun `版本比 App 新的文件会给告警`() {
        val result = PersonaDocument.parse(
            """{"format":"solochat.persona","version":99,"personas":[{"name":"甲","identity":"一"}]}"""
        )
        assertEquals(1, result.personas.size)
        assertTrue("告警：${result.warnings}", result.warnings.any { it.contains("99") })
    }

    @Test
    fun `Markdown 老文件仍然读得进来 - 只是不再是正式格式`() {
        // 正式格式改成 JSON 之后，这一条是"老文件不会突然全部失效"的保证
        val d = one("# 🌙 阿岩\n> 说话很直\n\n## 你是谁\n开出租车的。\n\n## 你怎么说话\n短句。")
        assertEquals("阿岩", d.name)
        assertEquals("🌙", d.avatar)
        assertEquals("说话很直", d.tagline)
        assertEquals("开出租车的。", d.identity)
        assertEquals("短句。", d.voice)
    }
}
