package com.fixcode.intellij.lua.editor

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 意图动作「生成函数注解」：参数名靠词法器取。
 * 直接调用动作本身：走 fixture 的意图列表会顺带向 LSP4IJ 要 codeAction，测试环境里没有 emmylua_ls。
 */
class LuaGenerateFunctionDocTest : BasePlatformTestCase() {
    private val action = LuaGenerateFunctionDocIntention()

    private fun generate(before: String, after: String) {
        myFixture.configureByText("a.lua", before)
        assertTrue(action.isAvailable(project, myFixture.editor, myFixture.file))
        WriteCommandAction.runWriteCommandAction(project) { action.invoke(project, myFixture.editor, myFixture.file) }
        myFixture.checkResult(after)
    }

    private fun unavailable(text: String) {
        myFixture.configureByText("a.lua", text)
        assertFalse(action.isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun `test 已注册`() {
        assertTrue(IntentionManager.getInstance().intentionActions.any { IntentionActionDelegate.unwrap(it) is LuaGenerateFunctionDocIntention })
    }

    fun `test 参数和返回值`() = generate(
        "function M.add(a, b)<caret>\n    return a + b\nend\n",
        "---@param a any\n---@param b any\n---@return any\nfunction M.add(a, b)<caret>\n    return a + b\nend\n",
    )

    fun `test 没有返回值不加 return`() = generate(
        "    local function f(x, ...)<caret>\n        print(x)\n        return\n    end",
        "    ---@param x any\n    ---@param ... any\n    local function f(x, ...)<caret>\n        print(x)\n        return\n    end",
    )

    fun `test 嵌套函数的 return 不算`() = generate(
        "function M:on(cb)<caret>\n    self.cb = function() return 1 end\nend",
        "---@param cb any\nfunction M:on(cb)<caret>\n    self.cb = function() return 1 end\nend",
    )

    fun `test 已有的不重复`() = generate(
        "--- 说明\n---@param a number\nfunction f(a, b)<caret>\n    if a then return b end\nend",
        "--- 说明\n---@param a number\n---@param b any\n---@return any\nfunction f(a, b)<caret>\n    if a then return b end\nend",
    )

    fun `test 匿名函数赋值`() = generate(
        "local f = function(<caret>v) return v end",
        "---@param v any\n---@return any\nlocal f = function(<caret>v) return v end",
    )

    fun `test 都写过时不提供`() = unavailable("---@param a any\nfunction f(a)<caret> end")

    fun `test 不是函数行不提供`() = unavailable("local x = 1<caret>")

    fun `test 参数表没写完不提供`() = unavailable("function f(a, <caret>")
}
