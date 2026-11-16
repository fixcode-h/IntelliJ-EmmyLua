package com.fixcode.intellij.lua.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 回车补 `end`、缩进、`---` 续行、打 `end` 回退缩进。只靠词法器。 */
class LuaEnterTypingTest : BasePlatformTestCase() {
    private fun enter(before: String, after: String) {
        myFixture.configureByText("a.lua", before)
        myFixture.type('\n')
        myFixture.checkResult(after)
    }

    fun `test if then 补 end`() = enter(
        "if x then<caret>",
        "if x then\n    <caret>\nend",
    )

    fun `test function 参数表后补 end`() = enter(
        "local function f(a, b)<caret>\n",
        "local function f(a, b)\n    <caret>\nend\n",
    )

    fun `test 方法定义补 end`() = enter(
        "function M:foo()<caret>",
        "function M:foo()\n    <caret>\nend",
    )

    fun `test for do 补 end`() = enter(
        "for i = 1, 10 do<caret>",
        "for i = 1, 10 do\n    <caret>\nend",
    )

    fun `test while do 补 end`() = enter(
        "while true do<caret>",
        "while true do\n    <caret>\nend",
    )

    fun `test repeat 补 until`() = enter(
        "repeat<caret>",
        "repeat\n    <caret>\nuntil",
    )

    fun `test 已有 end 时不补`() = enter(
        "if x then<caret>\n    foo()\nend\n",
        "if x then\n    <caret>\n    foo()\nend\n",
    )

    fun `test 已有函数里新写 if 时补`() = enter(
        "function a()\n    if x then<caret>\nend\n",
        "function a()\n    if x then\n        <caret>\n    end\nend\n",
    )

    fun `test 嵌套块里已经配对的不补`() = enter(
        "function a()\n    if x then<caret>\n    end\nend\n",
        "function a()\n    if x then\n        <caret>\n    end\nend\n",
    )

    fun `test 回调函数补 end 并保留右括号`() = enter(
        "foo(function()<caret>)",
        "foo(function()\n    <caret>\nend)",
    )

    fun `test 光标后面就是 end 时拆开`() = enter(
        "if x then<caret>end",
        "if x then\n    <caret>\nend",
    )

    fun `test 表构造补右花括号`() = enter(
        "local t = {<caret>",
        "local t = {\n    <caret>\n}",
    )

    fun `test 花括号之间回车`() = enter(
        "local t = {<caret>}",
        "local t = {\n    <caret>\n}",
    )

    fun `test else 后缩进`() = enter(
        "if x then\n    a()\nelse<caret>\nend",
        "if x then\n    a()\nelse\n    <caret>\nend",
    )

    fun `test 普通语句跟上一行缩进`() = enter(
        "function a()\n    foo()<caret>\nend",
        "function a()\n    foo()\n    <caret>\nend",
    )

    fun `test 一行里开了又关不缩进`() = enter(
        "if x then return end<caret>",
        "if x then return end\n<caret>",
    )

    fun `test 字符串里的 then 不算`() = enter(
        "local s = \"if x then\"<caret>",
        "local s = \"if x then\"\n<caret>",
    )

    fun `test 注解行续写`() = enter(
        "---@param a number<caret>\nfunction f(a) end",
        "---@param a number\n--- <caret>\nfunction f(a) end",
    )

    fun `test 缩进的注解行续写`() = enter(
        "    --- 说明<caret>",
        "    --- 说明\n    --- <caret>",
    )

    fun `test 普通注释不续`() = enter(
        "-- 说明<caret>",
        "-- 说明\n<caret>",
    )

    fun `test 打 end 回退缩进`() {
        myFixture.configureByText("a.lua", "if x then\n    foo()\n    <caret>")
        myFixture.type("end")
        myFixture.checkResult("if x then\n    foo()\nend<caret>")
    }

    fun `test 打 else 回退缩进`() {
        myFixture.configureByText("a.lua", "function f()\n    if x then\n        foo()\n        <caret>\n    end\nend")
        myFixture.type("else")
        myFixture.checkResult("function f()\n    if x then\n        foo()\n    else<caret>\n    end\nend")
    }

    fun `test 打 elseif 回退缩进`() {
        myFixture.configureByText("a.lua", "if x then\n    foo()\n    <caret>")
        myFixture.type("elseif")
        myFixture.checkResult("if x then\n    foo()\nelseif<caret>")
    }

    fun `test 打成别的词时恢复缩进`() {
        myFixture.configureByText("a.lua", "if x then\n    foo()\n    <caret>")
        myFixture.type("ending")
        myFixture.checkResult("if x then\n    foo()\n    ending<caret>")
    }

    fun `test 打 until 回退缩进`() {
        myFixture.configureByText("a.lua", "repeat\n    foo()\n    <caret>")
        myFixture.type("until")
        myFixture.checkResult("repeat\n    foo()\nuntil<caret>")
    }

    fun `test 打右花括号回退缩进`() {
        myFixture.configureByText("a.lua", "local t = {\n    a = 1,\n    <caret>")
        myFixture.type("}")
        myFixture.checkResult("local t = {\n    a = 1,\n}<caret>")
    }

    fun `test 括号续行之后回到外层`() = enter(
        "foo(a,\n    b)<caret>",
        "foo(a,\n    b)\n<caret>",
    )
}
