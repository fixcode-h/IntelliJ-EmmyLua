package com.fixcode.intellij.lua.format

import junit.framework.TestCase

class LuaAlignHintsTest : TestCase() {
    fun `test 表字段的等号前补到两个空格`() {
        val src = "local t = {\n    a = 1,\n    bb= 2,\n    [1] = 3,\n    [\"k\"]   = 4,\n}\n"
        val want = "local t = {\n    a  = 1,\n    bb  = 2,\n    [1]  = 3,\n    [\"k\"]   = 4,\n}\n"
        assertEquals(want, LuaAlignHints.apply(src))
    }

    fun `test 语句里的赋值和比较不动`() {
        val src = "local a = 1\nb = a == 2\nlocal f = function() x = 1 end\n"
        assertEquals(src, LuaAlignHints.apply(src))
    }

    fun `test 表里函数体的赋值不动，嵌套表照补`() {
        val src = "local t = {\n    f = function() local x = 1 end,\n    sub = { y = 2 },\n}\n"
        val want = "local t = {\n    f  = function() local x = 1 end,\n    sub  = { y  = 2 },\n}\n"
        assertEquals(want, LuaAlignHints.apply(src))
    }

    fun `test 调用参数和下标里的等号不算`() {
        val src = "foo(a == b)\nlocal v = t[i == 1 and 1 or 2]\n"
        assertEquals(src, LuaAlignHints.apply(src))
    }

    fun `test 字符串和注释里的等号不算`() {
        val src = "local t = {\n    -- x = 1\n    s = \"a = b\",\n}\n"
        val want = "local t = {\n    -- x = 1\n    s  = \"a = b\",\n}\n"
        assertEquals(want, LuaAlignHints.apply(src))
    }

    fun `test 等号在行首的不补`() {
        val src = "local t = {\n    a\n    = 1,\n}\n"
        assertEquals(src, LuaAlignHints.apply(src))
    }
}
