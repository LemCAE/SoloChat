package app.solochat.core.persona

import app.solochat.core.model.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把一份文档填进**已有的**人物卡」的回归测试。
 *
 * 为什么要单独一个文件：这条路径和"导入新建"共享解析器，但**语义完全不同** ——
 * 建新卡是没有历史包袱的，改旧卡一不小心就会**静默删掉用户手敲的东西**。
 * 而这个功能是给"我手上有一份改好的文档，要把这张卡换成它"用的，
 * 所以"什么该覆盖、什么绝不能动"必须被钉住。
 */
class PersonaDraftApplyTest {

    private val existing = Persona(
        id = "p-1",
        name = "小默",
        tagline = "老简介",
        avatar = "🐱",
        identity = "老的身份",
        voice = "老的说话方式",
        sceneVoice = "老的当面说话",
        mannerisms = "老的动作",
        surroundings = "老的环境",
        depiction = "老的笔法",
        boundaries = "老的边界",
        secretNotes = "老的里设定",
        builtin = true,
        createdAt = 111L,
        updatedAt = 222L
    )

    /** 文档里写了八段 —— 全部替换，本地事实一个字不动。 */
    @Test
    fun `八段都写了 - 全替换但保留本地事实`() {
        val draft = PersonaDraft(
            name = "陆昭",
            tagline = "新简介",
            avatar = "🐟",
            identity = "新的身份",
            voice = "新的说话方式",
            sceneVoice = "新的当面说话",
            mannerisms = "新的动作",
            surroundings = "新的环境",
            depiction = "新的笔法",
            boundaries = "新的边界",
            secretNotes = "新的里设定"
        )

        val merged = draft.applyTo(existing)

        assertEquals("陆昭", merged.name)
        assertEquals("新简介", merged.tagline)
        assertEquals("🐟", merged.avatar)
        assertEquals("新的身份", merged.identity)
        assertEquals("新的说话方式", merged.voice)
        assertEquals("新的当面说话", merged.sceneVoice)
        assertEquals("新的动作", merged.mannerisms)
        assertEquals("新的环境", merged.surroundings)
        assertEquals("新的笔法", merged.depiction)
        assertEquals("新的边界", merged.boundaries)
        assertEquals("新的里设定", merged.secretNotes)
        // ---- 本地事实：一个都不许动 ----
        assertEquals("id 必须保留，否则等于新建了一张卡", "p-1", merged.id)
        assertTrue("出厂标记不能被导入改掉（改掉用户就删不掉了）", merged.builtin)
        assertEquals("创建时间不动", 111L, merged.createdAt)
        assertEquals("updatedAt 留给保存那一层统一打点", 222L, merged.updatedAt)
    }

    /**
     * **文档里空着的段落 = "文档里没写"，不是"清空"。**
     *
     * 这条钉的是最贵的一种数据丢失：用户手上这份文档只写了「怎么说话」，
     * 而"清空"语义会把他手敲的另外几段一起删掉 —— 删完还看不出来是导入干的。
     */
    @Test
    fun `文档里空着的段落 - 保留原文而不是清空`() {
        val partial = PersonaDraft(voice = "只改了说话方式")

        val merged = partial.applyTo(existing)

        assertEquals("这一段是文档里写的，要换", "只改了说话方式", merged.voice)
        assertEquals("名字文档里没写，保留", "小默", merged.name)
        assertEquals("简介保留", "老简介", merged.tagline)
        assertEquals("头像保留", "🐱", merged.avatar)
        assertEquals("身份保留", "老的身份", merged.identity)
        assertEquals("当面说话保留", "老的当面说话", merged.sceneVoice)
        assertEquals("动作保留", "老的动作", merged.mannerisms)
        assertEquals("环境保留", "老的环境", merged.surroundings)
        assertEquals("笔法保留", "老的笔法", merged.depiction)
        assertEquals("边界保留", "老的边界", merged.boundaries)
        assertEquals("里设定保留", "老的里设定", merged.secretNotes)
    }

    /**
     * 面对面那几段走**同一套语义**（写了才换、没写保留）。
     *
     * 单独钉一条的理由：老卡上这几段一定是空的（v3 → v4 才加的列），
     * 而"老卡 + 一份只写了动作的文档"正是这个功能最常见的用法 ——
     * 如果实现时漏掉其中任何一段，表现都是"导入完发现那段还是空的，但也没报错"。
     */
    @Test
    fun `面对面那几段也遵守同一套语义`() {
        val merged = PersonaDraft(mannerisms = "新的动作").applyTo(existing)
        assertEquals("写了就换", "新的动作", merged.mannerisms)
        assertEquals("没写就保留", "老的当面说话", merged.sceneVoice)
        assertEquals("没写就保留", "老的环境", merged.surroundings)
        assertEquals("没写就保留", "老的笔法", merged.depiction)
    }

    /** 空的草稿（什么都没读到）不该把卡清成空白 —— 全字段原样返回。 */
    @Test
    fun `空草稿 - 原样返回`() {
        assertEquals(existing, PersonaDraft().applyTo(existing))
    }

    /** 只写了名字的文档：名字换掉，其余保留。 */
    @Test
    fun `只有名字 - 只换名字`() {
        val merged = PersonaDraft(name = "阿岩").applyTo(existing)
        assertEquals("阿岩", merged.name)
        assertEquals("老的身份", merged.identity)
        assertEquals("老的说话方式", merged.voice)
    }

    /**
     * 出厂卡不能被"导入"变成普通卡，普通卡也不能变成出厂卡 ——
     * 两个方向都试一遍，因为写反了只会在一半情况下出错。
     */
    @Test
    fun `导入不改出厂标记 - 两个方向都不改`() {
        val builtin = existing.copy(builtin = true)
        val plain = existing.copy(builtin = false)
        val draft = PersonaDraft(identity = "换掉身份")

        assertTrue("出厂卡导入后仍是出厂卡", draft.applyTo(builtin).builtin)
        assertEquals("普通卡导入后仍是普通卡", false, draft.applyTo(plain).builtin)
    }
}
