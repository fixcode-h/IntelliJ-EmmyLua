package com.fixcode.intellij.lua.debug.xdebugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaHoverExpressionTest {
    /** 悬停在 [line] 里第 [nth] 个 [word] 的中间，返回求值表达式和高亮的原文。 */
    private fun hover(line: String, word: String, nth: Int = 1): Pair<String, String>? {
        var at = -1
        repeat(nth) { at = line.indexOf(word, at + 1) }
        require(at >= 0) { "$word 不在 $line 里" }
        val h = LuaHoverExpression.find(line, at + word.length / 2) ?: return null
        return h.expression to line.substring(h.start, h.end)
    }

    @Test
    fun `局部变量和 self`() {
        assertEquals("Weapon" to "Weapon", hover("\tlocal Weapon = self:SpawnWeapon()", "Weapon"))
        assertEquals("self" to "self", hover("\tlocal Weapon = self:SpawnWeapon()", "self"))
        assertEquals("Weapon" to "Weapon", hover("\tif Weapon then", "Weapon"))
    }

    @Test
    fun `字段路径只接左边前缀`() {
        val line = "Weapon:K2_AttachToComponent(self.WeaponPoint.Owner, nil)"
        assertEquals("self.WeaponPoint" to "self.WeaponPoint", hover(line, "WeaponPoint"))
        assertEquals("self.WeaponPoint.Owner" to "self.WeaponPoint.Owner", hover(line, "Owner"))
    }

    @Test
    fun `方法名按点号求值 高亮原文`() {
        assertEquals("self.SpawnWeapon" to "self:SpawnWeapon", hover("local Weapon = self:SpawnWeapon()", "SpawnWeapon"))
        assertEquals("UE.EAttachmentRule.SnapToTarget" to "UE.EAttachmentRule.SnapToTarget", hover("x = UE.EAttachmentRule.SnapToTarget", "SnapToTarget"))
    }

    @Test
    fun `字面量下标接上 变量下标和调用结果不求值`() {
        assertEquals("list[1].id" to "list[1].id", hover("print(list[1].id)", "id"))
        assertEquals("t[\"k\"][2].v" to "t[\"k\"][2].v", hover("y = t[\"k\"][2].v", "v"))
        assertNull(hover("print(list[i].id)", "id"))
        assertNull(hover("print(foo().x)", "x"))
        assertEquals("i" to "i", hover("print(list[i].id)", "[i]"))
    }

    @Test
    fun `关键字 字面量 注释 字符串不求值`() {
        assertNull(hover("\tif Weapon then", "then"))
        assertNull(hover("x = 42", "42"))
        assertNull(hover("x = 1 -- Weapon here", "Weapon"))
        assertNull(hover("print(\"Weapon\")", "Weapon"))
    }
}
