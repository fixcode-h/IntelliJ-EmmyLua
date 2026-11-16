package com.tang.intellij.test.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiNamedElement
import com.tang.intellij.lua.psi.LuaClassMethodDef
import com.tang.intellij.lua.psi.LuaFuncDef
import com.tang.intellij.lua.psi.LuaLocalFuncDef
import com.tang.intellij.test.LuaTestBase
import com.tang.intellij.test.fileTreeFromText

/**
 * 语言功能的冒烟用例：跳转、查找引用、补全都要在装上整个插件（含调试链路的注册）后还能用。
 * 调试重构时曾怀疑误删了语言功能，这里兜底，任何一项失效都会在 CI 里红。
 */
class LanguageFeaturesSmokeTest : LuaTestBase() {

    fun `test goto local function in same file`() {
        myFixture.configureByText("a.lua", """
            local function helper() end
            hel<caret>per()
        """.trimIndent())
        val target = gotoTarget()
        assertInstanceOf(target, LuaLocalFuncDef::class.java)
    }

    fun `test goto global function in another file`() {
        fileTreeFromText("""
            --- lib.lua
            function GlobalHelper() end

            --- main.lua
            GlobalHel--[[caret]]per()
        """.trimIndent()).createAndOpenFileWithCaretMarker()
        val target = gotoTarget()
        assertInstanceOf(target, LuaFuncDef::class.java)
        assertEquals("lib.lua", target.containingFile.name)
    }

    fun `test goto class method in another file`() {
        fileTreeFromText("""
            --- player.lua
            ---@class Player
            local Player = {}
            function Player:Jump() end
            return Player

            --- main.lua
            ---@type Player
            local p
            p:Ju--[[caret]]mp()
        """.trimIndent()).createAndOpenFileWithCaretMarker()
        val target = gotoTarget()
        assertInstanceOf(target, LuaClassMethodDef::class.java)
        assertEquals("player.lua", target.containingFile.name)
    }

    fun `test goto required module file`() {
        fileTreeFromText("""
            --- util.lua
            local M = {}
            return M

            --- main.lua
            local u = require("ut--[[caret]]il")
        """.trimIndent()).createAndOpenFileWithCaretMarker()
        val target = gotoTarget()
        assertEquals("util.lua", target.containingFile.name)
    }

    fun `test find usages across files`() {
        fileTreeFromText("""
            --- lib.lua
            function Shared--[[caret]]Func() end

            --- a.lua
            SharedFunc()

            --- b.lua
            SharedFunc()
        """.trimIndent()).createAndOpenFileWithCaretMarker()
        val element = myFixture.elementAtCaret
        val usages = myFixture.findUsages(element)
        val files = usages.mapNotNull { it.element?.containingFile?.name }.toSet()
        assertTrue("应在 a.lua 和 b.lua 找到引用，实际：$files", files.containsAll(listOf("a.lua", "b.lua")))
    }

    fun `test completion offers class members`() {
        fileTreeFromText("""
            --- player.lua
            ---@class Player
            local Player = {}
            function Player:Jump() end
            function Player:Run() end
            return Player

            --- main.lua
            ---@type Player
            local p
            p:--[[caret]]
        """.trimIndent()).createAndOpenFileWithCaretMarker()
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("补全里应有 Jump、Run，实际：$items", items.containsAll(listOf("Jump", "Run")))
    }

    private fun gotoTarget(): PsiNamedElement {
        val targets = GotoDeclarationAction.findAllTargetElements(myFixture.project, myFixture.editor, myFixture.caretOffset)
        assertTrue("没有跳转目标", targets.isNotEmpty())
        val target = targets.first()
        return target as? PsiNamedElement ?: error("跳转目标不是命名元素：$target")
    }
}
