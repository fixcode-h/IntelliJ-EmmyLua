package com.fixcode.intellij.lua.debug.xdebugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaFrameNameTest {
    @Test
    fun `各种定义写法`() {
        assertEquals("M:ReceiveBeginPlay", LuaFrameName.fromDefinition("function M:ReceiveBeginPlay()"))
        assertEquals("A.B.c", LuaFrameName.fromDefinition("function A.B.c(x, y)"))
        assertEquals("foo", LuaFrameName.fromDefinition("\tlocal function foo(a)"))
        assertEquals("M.OnHit", LuaFrameName.fromDefinition("M.OnHit = function(self, other)"))
        assertEquals("cb", LuaFrameName.fromDefinition("    local cb = function()"))
        assertEquals("Tick", LuaFrameName.fromDefinition("    Tick = function(dt) end,"))
    }

    @Test
    fun `匿名函数和注释里的不算`() {
        assertNull(LuaFrameName.fromDefinition("self.Timer = UE.SetTimer(function() end)"))
        assertNull(LuaFrameName.fromDefinition("x = 1 -- function foo()"))
    }

    @Test
    fun `只在问号时推断`() {
        val lines = mapOf(12 to "function M:ReceiveBeginPlay()", 20 to "pcall(function()")
        assertEquals("Tick", LuaFrameName.display("Tick", 12, lines::get))
        assertEquals("M:ReceiveBeginPlay", LuaFrameName.display("?", 12, lines::get))
        assertEquals("匿名函数（第 20 行）", LuaFrameName.display("?", 20, lines::get))
        assertEquals("?", LuaFrameName.display("?", null, lines::get))
    }
}
