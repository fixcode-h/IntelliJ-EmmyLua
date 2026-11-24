package com.fixcode.intellij.lua.translation

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 注释翻译在 IDE 里的接线：动作、悬停 provider 注册上了，「光标所在的整段注释」按词法认。 */
class TranslationIdeTest : BasePlatformTestCase() {
    fun `test 动作和悬停 provider 已注册`() {
        assertTrue(ActionManager.getInstance().getAction("lua.translate.selection") is TranslateSelectionAction)
        assertTrue(ActionManager.getInstance().getAction("lua.translate.clearCache") is ClearTranslationCacheAction)
        assertTrue(DocumentationTargetProvider.EP_NAME.extensionList.any { it is TranslatedHoverTargetProvider })
    }

    fun `test 光标在注释里取整段注释`() {
        myFixture.configureByText(
            "a.lua",
            "local x = 1\n--- Returns the owner\n--- of this <caret>component.\n---@param a number\nlocal function f(a) end\n",
        )
        val range = CommentRange.around(myFixture.editor, myFixture.caretOffset)!!
        assertEquals("--- Returns the owner\n--- of this component.\n---@param a number", myFixture.editor.document.getText(range))
        assertEquals(
            "Returns the owner\nof this component.\n@param a number",
            TranslationText.cleanComment(myFixture.editor.document.getText(range)),
        )
    }

    fun `test 空行隔开的注释不连在一起、行尾注释不带代码`() {
        myFixture.configureByText("a.lua", "-- first block\n\n-- second <caret>block\nlocal y = 2 -- tail\n")
        val range = CommentRange.around(myFixture.editor, myFixture.caretOffset)!!
        assertEquals("-- second block", myFixture.editor.document.getText(range))

        myFixture.configureByText("b.lua", "local y = 2 -- tail <caret>note\n")
        val tail = CommentRange.around(myFixture.editor, myFixture.caretOffset)!!
        assertEquals("-- tail note", myFixture.editor.document.getText(tail))
    }

    fun `test 不在注释里取光标所在行`() {
        myFixture.configureByText("a.lua", "-- c\nlocal <caret>y = 2\n")
        val range = CommentRange.around(myFixture.editor, myFixture.caretOffset)!!
        assertEquals("local y = 2", myFixture.editor.document.getText(range))
    }
}
