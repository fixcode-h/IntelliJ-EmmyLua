package com.fixcode.intellij.lua.debug.xdebugger

import org.junit.Assert.assertEquals
import org.junit.Test

class LuaMultilineSpansTest {
    private val text = "local a = 1\n--[[ x = 2\ny = 3 ]] b = 4\nlocal s = [[\nz = 5\n]]\nc = '单行'\n"
    private val ranges = LuaMultilineSpans.scan(text)

    @Test
    fun `只记跨行的长注释和长字符串 单行字符串不记`() {
        assertEquals(4, ranges.size)
        assertEquals(text.indexOf("--[["), ranges[0])
        assertEquals(text.indexOf("]] b") + 2, ranges[1])
        assertEquals(text.indexOf("[[\nz"), ranges[2])
    }

    @Test
    fun `行首落在上面开始的注释中间时返回注释结束处`() {
        val lineStart = text.indexOf("y = 3")
        assertEquals(text.indexOf(" b = 4"), LuaMultilineSpans.endOfSpanCovering(ranges, lineStart))
        assertEquals(text.length - "\nc = '单行'\n".length, LuaMultilineSpans.endOfSpanCovering(ranges, text.indexOf("z = 5")))
    }

    @Test
    fun `token 从行首开始或不在任何跨行 token 里时返回 -1`() {
        assertEquals(-1, LuaMultilineSpans.endOfSpanCovering(ranges, 0))
        assertEquals(-1, LuaMultilineSpans.endOfSpanCovering(ranges, text.indexOf("--[[")))
        assertEquals(-1, LuaMultilineSpans.endOfSpanCovering(ranges, text.indexOf("c = ")))
    }
}
