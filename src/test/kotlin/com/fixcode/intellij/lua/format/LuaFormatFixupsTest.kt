package com.fixcode.intellij.lua.format

import junit.framework.TestCase

class LuaFormatFixupsTest : TestCase() {
    fun `test 匿名函数后有尾参数时单独一行的右括号接回去`() {
        val src = "foo(a, function ()\n    x()\nend, b\n)\n"
        assertEquals("foo(a, function ()\n    x()\nend, b)\n", LuaFormatFixups.apply(src))
    }

    fun `test 嵌套缩进里也接回去`() {
        val src = "    if x then\n        Req(Who, function ()\n            y()\n        end, self.Config.FakeInterval\n        )\n    end\n"
        val want = "    if x then\n        Req(Who, function ()\n            y()\n        end, self.Config.FakeInterval)\n    end\n"
        assertEquals(want, LuaFormatFixups.apply(src))
    }

    fun `test 正常的多行参数不动`() {
        val src = "foo(\n    a,\n    b\n)\nbar(function ()\n    x()\nend)\n"
        assertEquals(src, LuaFormatFixups.apply(src))
    }

    fun `test 上一行有注释或右括号后还有别的不动`() {
        val a = "foo(a, function ()\nend, b -- c\n)\n"
        assertEquals(a, LuaFormatFixups.apply(a))
        val b = "foo(a, function ()\nend, b\n):bar()\n"
        assertEquals(b, LuaFormatFixups.apply(b))
    }

    fun `test 长字符串里的不动`() {
        val src = "local s = [[\nend, b\n)\n]]\n"
        assertEquals(src, LuaFormatFixups.apply(src))
    }
}
