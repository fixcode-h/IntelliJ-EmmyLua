package com.fixcode.intellij.lua.debug.xdebugger

import org.junit.Assert.assertEquals
import org.junit.Test

class LuaProfileNamesTest {
    @Test
    fun `匿名函数按定义行推断名字`() {
        val lines = mapOf(10 to "function M:ReceiveTick(DeltaSeconds)", 20 to "UE.SetTimer(function() end)")
        assertEquals("M:ReceiveTick", LuaProfileNames.functionName("<匿名>", 10, lines::get))
        assertEquals("<匿名>", LuaProfileNames.functionName("<匿名>", 20, lines::get))
        assertEquals("index", LuaProfileNames.functionName("index", 10, lines::get))
        assertEquals("<主 chunk>", LuaProfileNames.functionName("<主 chunk>", 0, lines::get))
    }

    @Test
    fun `字符串 chunk 显示成 string 加第一行`() {
        assertEquals("Game/UI/Main.lua", LuaProfileNames.chunkDisplay("@Game/UI/Main.lua"))
        assertEquals("stdin", LuaProfileNames.chunkDisplay("=stdin"))
        assertEquals("[string \"local rawget = _G.rawget...\"]", LuaProfileNames.chunkDisplay("local rawget = _G.rawget\nlocal x = 1\n"))
        assertEquals("[string \"return 1\"]", LuaProfileNames.chunkDisplay("return 1"))
        val long = "local a = " + "x".repeat(60)
        assertEquals("[string \"${long.take(40)}...\"]", LuaProfileNames.chunkDisplay(long))
    }

    @Test
    fun `折叠栈里的帧标签一起改名`() {
        val code = "local rawget = _G.rawget\nlocal f; f = 1"
        val renames = mapOf(
            LuaProfileNames.runtimeLabel("<匿名>", "@M.lua", 10) to LuaProfileNames.label("M:ReceiveTick", "@M.lua", 10),
            LuaProfileNames.runtimeLabel("index", code, 3) to LuaProfileNames.label("index", code, 3),
        )
        // runtime 标签：换行、分号换成 _
        assertEquals("index (local rawget = _G.rawget_local f_ f = 1:3)", LuaProfileNames.runtimeLabel("index", code, 3))
        val folded = "<主 chunk> (M.lua:0);<匿名> (M.lua:10);index (local rawget = _G.rawget_local f_ f = 1:3) 7\nfoo [C] 2\n"
        assertEquals(
            "<主 chunk> (M.lua:0);M:ReceiveTick (M.lua:10);index ([string \"local rawget = _G.rawget...\"]:3) 7\nfoo [C] 2\n",
            LuaProfileNames.rewriteFolded(folded, renames),
        )
    }
}
