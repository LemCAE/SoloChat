package app.solochat.core.model

import app.solochat.core.persona.PersonaDocument
import app.solochat.core.persona.PersonaDraft
import org.junit.Assert.*
import org.junit.Test

class AvatarRefTest {
    private val id = "e55fb244-8247-43d6-bc2b-4d0770df9455"

    @Test fun `本机图片引用往返人物卡 JSON 不被截成四个字符`() {
        val ref = AvatarRef.image(id)
        val parsed = PersonaDocument.parse(PersonaDocument.toJson(PersonaDraft(name = "头像测试", avatar = ref, identity = "测试身份")))
        assertEquals(ref, parsed.personas.single().avatar)
        assertEquals(id, AvatarRef.imageId(ref))
    }

    @Test fun `图片标识不接受路径且标签不显示图片引用`() {
        assertNull(AvatarRef.imageId("solochat-avatar:../../other"))
        assertNull(AvatarRef.imageId("solochat-avatar:1-1-1-1-1"))
        assertEquals("", AvatarRef.label(AvatarRef.image(id)))
        assertEquals("", AvatarRef.label("solochat-avatar:missing"))
        assertEquals("🐳", AvatarRef.label("🐳"))
    }
}
