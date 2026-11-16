package com.fixcode.intellij.lua.editor

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 后缀模板：表达式边界靠词法器找。 */
class LuaPostfixTemplateTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun expand(before: String, after: String) {
        myFixture.configureByText("a.lua", before)
        myFixture.type('\t')
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        myFixture.checkResult(after)
    }

    fun `test if`() = expand("x.if<caret>", "if x then\n    <caret>\nend")
    fun `test ifnot 成员链`() = expand("a.b:c().ifnot<caret>", "if not a.b:c() then\n    <caret>\nend")
    fun `test nn 带缩进`() = expand("function f()\n    t[1].nn<caret>\nend", "function f()\n    if t[1] ~= nil then\n        <caret>\n    end\nend")
    fun `test null`() = expand("self.x.null<caret>", "if self.x == nil then\n    <caret>\nend")
    fun `test local 猜变量名`() = expand("obj:GetName().local<caret>", "local name = obj:GetName()<caret>")
    fun `test return 只取等号右边的名字`() = expand("x = a + b.return<caret>", "x = a + return b<caret>")
    fun `test print 调用参数里有括号`() = expand("f(g(1), \"a\").print<caret>", "print(f(g(1), \"a\"))<caret>")
    fun `test fori`() = expand("n.fori<caret>", "for i = 1, n do\n    <caret>\nend")
    fun `test fori 数字`() = expand("10.fori<caret>", "for i = 1, 10 do\n    <caret>\nend")
    fun `test forp`() = expand("t.forp<caret>", "for k, v in pairs(t) do\n    <caret>\nend")
    fun `test fora`() = expand("list.fora<caret>", "for _, v in ipairs(list) do\n    <caret>\nend")
    fun `test par 括号表达式`() = expand("(a + b).par<caret>", "((a + b))<caret>")

    fun `test 注释里不展开`() {
        myFixture.configureByText("a.lua", "-- x.if<caret>")
        myFixture.type('\t')
        assertNull(TemplateManagerImpl.getTemplateState(myFixture.editor))
        assertFalse(myFixture.editor.document.text.contains("then"))
    }

    fun `test 表达式边界`() {
        fun start(text: String) = LuaPostfixExpr.findStart(text, text.length)?.let { text.substring(it) }
        assertEquals("a.b[c](d):e", start("x = a.b[c](d):e"))
        assertEquals("(a)", start("y = (a)"))
        assertNull(start("x = [1]"))
        assertEquals("a", start("x = f(a"))
        assertNull(start("x = a)"))
        assertNull(start("s = \"abc"))
        assertNull(start("x = a."))
        assertEquals("foo", start("local s = [[\nbar]] .. foo"))
        assertNull(start("--[[ x\n y"))
    }
}
