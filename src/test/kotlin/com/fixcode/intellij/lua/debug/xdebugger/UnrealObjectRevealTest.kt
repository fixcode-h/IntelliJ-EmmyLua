package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.debug.protocol.VariableDto
import com.fixcode.intellij.lua.debug.ue.UnrealObjectReveal
import com.fixcode.intellij.lua.debug.ue.UnrealObjectReveal.Action
import com.google.gson.JsonParser
import junit.framework.TestCase

class UnrealObjectRevealTest : TestCase() {
    private fun row(kind: String?, hint: String?, attrs: List<String>? = null) =
        VariableDto(name = "self", value = "BP_Hero_C_0", type = "userdata", kind = kind, hint = hint, attrs = attrs)

    fun `test path comes from hint of ue objects only`() {
        val path = "/Game/Maps/UEDPIE_0_Main.Main:PersistentLevel.BP_Hero_C_0"
        assertEquals(path, UnrealObjectReveal.objectPathOf(row("ueObject", path)))
        assertEquals("/Game/Data/DA_Items.DA_Items", UnrealObjectReveal.objectPathOf(row("ueObject", " /Game/Data/DA_Items.DA_Items ")))
        // 不是 UE 对象、已失效、hint 是别的提示文字时都没有
        assertNull(UnrealObjectReveal.objectPathOf(row("object", path)))
        assertNull(UnrealObjectReveal.objectPathOf(row("ueObject", path, listOf("invalid"))))
        assertNull(UnrealObjectReveal.objectPathOf(row("ueObject", "被后面声明的同名局部变量遮蔽")))
        assertNull(UnrealObjectReveal.objectPathOf(row("ueObject", null)))
        assertNull(UnrealObjectReveal.objectPathOf(row("ueObject", "/")))
    }

    fun `test version compare`() {
        assertTrue(UnrealObjectReveal.versionAtLeast("2.10.0", "2.10.0"))
        assertTrue(UnrealObjectReveal.versionAtLeast("2.10.1", "2.10.0"))
        assertTrue(UnrealObjectReveal.versionAtLeast("3.0", "2.10.0"))
        assertTrue(UnrealObjectReveal.versionAtLeast("v2.11.0-beta.1", "2.10.0"))
        assertFalse(UnrealObjectReveal.versionAtLeast("2.9.1", "2.10.0"))
        assertFalse(UnrealObjectReveal.versionAtLeast(null, "2.10.0"))
        assertFalse(UnrealObjectReveal.versionAtLeast("", "2.10.0"))
    }

    fun `test describe reply`() {
        fun reply(s: String) = JsonParser.parseString(s).asJsonObject
        assertEquals("已在 UE 关卡里选中 Hero（PIE）",
            UnrealObjectReveal.describe(reply("""{"ok":true,"did":"actor","name":"Hero","pie":true}"""), Action.SELECT, "x"))
        assertEquals("已在 UE 内容浏览器里定位蓝图 WBP_Bag",
            UnrealObjectReveal.describe(reply("""{"did":"blueprint","name":"WBP_Bag"}"""), Action.SELECT, "x"))
        assertEquals("已在 UE 里打开蓝图 BP_Hero", UnrealObjectReveal.describe(reply("""{"did":"blueprint","name":"BP_Hero"}"""), Action.OPEN, "x"))
        assertEquals("已在 UE 里打开 DA_Items", UnrealObjectReveal.describe(reply("""{"did":"asset"}"""), Action.OPEN, "DA_Items"))
    }
}
