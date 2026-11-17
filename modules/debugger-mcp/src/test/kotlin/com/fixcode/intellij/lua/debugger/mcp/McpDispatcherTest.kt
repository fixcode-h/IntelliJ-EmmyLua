package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.protocol.McpLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class McpDispatcherTest {
    private val logged = CopyOnWriteArrayList<String>()
    private val mcp = EmmyLuaDebugMcp({ FakeBackend() }, FakeConfirmer(), logger = McpLogger { _, event, _, _, _ -> logged += event })

    @Test
    fun `未知工具返回 unknown_tool 并记日志`() {
        val r = McpTestClient(mcp.dispatcher).call("no_such_tool")
        assertTrue(r.isError)
        assertEquals("unknown_tool", r.code)
        assertTrue(r["message"] as String, (r["message"] as String).contains("lua_debug_session"))
        assertTrue(logged.contains("mcp.tool.unknown.rejected"))
    }

    @Test
    fun `调用记进 Agent 页的记录`() {
        McpTestClient(mcp.dispatcher).call("lua_debug_session", mapOf("action" to "status"))
        val entry = mcp.callLog.recent(10).single()
        assertEquals("lua_debug_session.status", entry.tool)
        assertEquals("test-agent", entry.clientName)
        assertTrue(entry.success)
        assertTrue(logged.contains("mcp.tool.lua_debug_session"))
    }

    @Test
    fun `参数错记 rejected`() {
        val r = McpTestClient(mcp.dispatcher).call("lua_debug_session", mapOf("action" to "status", "bogus" to 1))
        assertEquals("invalid_argument", r.code)
        assertTrue(logged.contains("mcp.tool.lua_debug_session.rejected"))
        assertFalse(mcp.callLog.recent(1).single().success)
    }

    @Test
    fun `help 返回完整用法`() {
        val r = McpTestClient(mcp.dispatcher).call("lua_debug_session", mapOf("action" to "help"))
        assertFalse(r.isError)
        assertTrue((r["guide"] as String).contains("lua_debug_inspect"))
    }

    @Test
    fun `同一客户端复用会话，没有标识时共用默认会话`() {
        val d = mcp.dispatcher
        assertSame(d.client("sid-1", "claude", "1"), d.client("sid-1", "claude", "1"))
        assertSame(d.client(null, "claude", "1"), d.client("", "claude", "1"))
        assertEquals("default", d.client(null, null, null).id)
        assertEquals(3, d.activeClientCount())
    }
}
