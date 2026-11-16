package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.debug.xdebugger.LuaInlineValueScanner.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class LuaInlineValueScannerTest {
    private fun scan(line: String) = LuaInlineValueScanner.scan(line)
    private fun names(line: String) = scan(line).map { it.displayName }

    @Test
    fun `local 声明 RHS 是路径时作为备选 是字面量时作为兜底文本`() {
        assertEquals(
            listOf(
                Entry("a", listOf("a"), fallbackPath = listOf("self", "x", "y")),
                Entry("b", listOf("b"), literalText = "1"),
                Entry("c", listOf("c"), literalText = "\"s\""),
                Entry("d", listOf("d")),
            ),
            scan("local a, b, c, d = self.x.y, 1, \"s\""),
        )
    }

    @Test
    fun `local 属性 负数 nil 和没有 RHS 的声明`() {
        assertEquals(
            listOf(
                Entry("x", listOf("x"), literalText = "-5"),
                Entry("y", listOf("y"), literalText = "nil"),
            ),
            scan("local x <const>, y = -5, nil"),
        )
        assertEquals(listOf(Entry("z", listOf("z"))), scan("local z"))
    }

    @Test
    fun `赋值 取 LHS 末段 单独 self 不显示`() {
        assertEquals(
            listOf(Entry("x", listOf("self", "x"), fallbackPath = listOf("y"))),
            scan("self.x = y"),
        )
        assertEquals(emptyList<Entry>(), scan("self = other"))
        assertEquals(
            listOf(
                Entry("a", listOf("a"), literalText = "true"),
                Entry("c", listOf("b", "c"), fallbackPath = listOf("d")),
            ),
            scan("a, b.c = true, d"),
        )
    }

    @Test
    fun `下标赋值不显示 也不走兜底`() {
        assertEquals(emptyList<Entry>(), scan("t[i] = v * 2"))
    }

    @Test
    fun `一行多条语句`() {
        assertEquals(listOf("a", "c"), names("a = b c = d"))
        assertEquals(listOf("a", "b"), names("local a = 1; b = 2"))
    }

    @Test
    fun `for 循环变量`() {
        assertEquals(listOf(Entry("i", listOf("i"))), scan("for i = 1, #list do"))
        assertEquals(
            listOf(Entry("k", listOf("k")), Entry("v", listOf("v")), Entry("t", listOf("t"))),
            scan("for k, v in pairs(t) do"),
        )
    }

    @Test
    fun `函数签名的参数 函数名不显示`() {
        assertEquals(listOf("a", "b"), names("function M.Foo:bar(a, b, ...)"))
        assertEquals(listOf("x"), names("local function f(x)"))
        assertEquals(emptyList<String>(), names("function M.init()"))
    }

    @Test
    fun `匿名函数的参数和单行函数体`() {
        assertEquals(listOf("cb", "e", "y"), names("local cb = function(e) local y = e.value end"))
        assertEquals(listOf("v", "list"), names("table.sort(list, function(v) return v end)"))
    }

    @Test
    fun `table 字面量和 RHS 同一行时展开字段`() {
        assertEquals(
            listOf(
                Entry("t", listOf("t")),
                Entry("k", null, literalText = "1"),
                Entry("n", listOf("name")),
                Entry("p", listOf("self", "pos")),
            ),
            scan("local t = { k = 1, n = name, sub = {}, [1] = x, p = self.pos, f = g() }"),
        )
    }

    @Test
    fun `多行 table 里的字段行`() {
        assertEquals(listOf(Entry("k", null, literalText = "\"v\"")), scan("    k = \"v\","))
        assertEquals(listOf(Entry("speed", listOf("cfg", "speed"))), scan("    speed = cfg.speed }"))
        assertEquals(
            listOf(Entry("a", null, literalText = "1"), Entry("b", listOf("x"))),
            scan("a = 1, b = x,"),
        )
        // 最后一个字段没有逗号时与赋值无法区分，按赋值处理
        assertEquals(listOf(Entry("k", listOf("k"), fallbackPath = listOf("v"))), scan("    k = v"))
    }

    @Test
    fun `调用实参里的纯路径`() {
        assertEquals(
            listOf(Entry("b", listOf("self", "a", "b")), Entry("c", listOf("c"))),
            scan("self:m(self.a.b, c, 1, d[1], e(), self)"),
        )
        assertEquals(
            listOf(Entry("r", listOf("r")), Entry("x", listOf("self", "x"))),
            scan("local r = self:m(self.x)"),
        )
        // 多行调用：行尾没闭合时最后一个实参到行尾为止
        assertEquals(listOf("a", "b"), names("foo(a, b"))
    }

    @Test
    fun `兜底 收集不在点号冒号后面的名字`() {
        assertEquals(listOf("foo"), names("foo()"))
        assertEquals(listOf("x"), names("x"))
        assertEquals(listOf("a", "b"), names("if a == b then"))
        assertEquals(listOf("c"), names("return obj.c"))
        assertEquals(listOf("obj"), names("obj:method()"))
        assertEquals(emptyList<String>(), names("goto continue"))
        assertEquals(emptyList<String>(), names("::continue::"))
    }

    @Test
    fun `注释和字符串里的名字不算`() {
        assertEquals(listOf("a"), names("local a = 1 -- b = 2"))
        assertEquals(emptyList<String>(), names("-- foo(bar)"))
        assertEquals(listOf("s"), names("local s = \"x = y\""))
        assertEquals(emptyList<String>(), names("--[[ a = b ]]"))
    }

    @Test
    fun `去重 同名同路径只留一个`() {
        assertEquals(listOf(Entry("x", listOf("x"))), scan("print(x, x)"))
        assertEquals(listOf("a"), names("function f(a) print(a) end"))
    }
}
