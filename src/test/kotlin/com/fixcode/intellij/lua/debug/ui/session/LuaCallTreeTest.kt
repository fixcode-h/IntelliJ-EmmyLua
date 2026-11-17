package com.fixcode.intellij.lua.debug.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaCallTreeTest {
    private val folded = """
        <主 chunk> (M.lua:0);M:ReceiveTick (M.lua:10);update (Util.lua:5) 7
        <主 chunk> (M.lua:0);M:ReceiveTick (M.lua:10) 3
        <主 chunk> (M.lua:0);M:ReceiveTick (M.lua:10);pairs [C] 2
        <主 chunk> (M.lua:0);index ([string "local rawget = _G.rawget..."]:3) 8
        <其他> 1
    """.trimIndent()

    @Test
    fun `折叠栈合并成树，子节点按总样本数排序`() {
        val root = LuaCallTree.parse(folded)
        assertEquals(21, root.total)
        val main = root.children.first()
        assertEquals(20, main.total)
        assertEquals(listOf("M:ReceiveTick", "index"), main.children.map { it.name })
        val tick = main.children[0]
        assertEquals(12, tick.total)
        assertEquals(3, tick.self)
        assertEquals("M.lua:10", tick.location)
        assertEquals("[C]", tick.children.first { it.name == "pairs" }.location)
        assertEquals("[string \"local rawget = _G.rawget...\"]:3", main.children[1].location)
        assertEquals("", root.children.last().location)
        assertEquals(4, root.height)
    }

    @Test
    fun `按标签路径找回节点`() {
        val root = LuaCallTree.parse(folded)
        val update = root.children.first().children[0].children[0]
        assertEquals(3, update.depth)
        val again = LuaCallTree.parse(folded).find(update.path)
        assertEquals("update (Util.lua:5)", again?.label)
        assertNull(root.find(listOf("nope")))
    }
}
