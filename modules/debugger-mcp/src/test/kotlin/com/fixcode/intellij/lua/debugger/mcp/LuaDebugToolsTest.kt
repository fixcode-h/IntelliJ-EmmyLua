package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.protocol.ControlDecision
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpCallLog
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpPermissionPolicy
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpServer
import com.fixcode.intellij.lua.debugger.mcp.tools.LuaDebugTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LuaDebugToolsTest {
    private lateinit var backend: FakeBackend
    private lateinit var confirmer: FakeConfirmer
    private var backendAvailable = true
    private lateinit var client: McpTestClient

    private val S = LuaDebugTools.SESSION
    private val B = LuaDebugTools.BREAKPOINTS
    private val I = LuaDebugTools.INSPECT

    @Before
    fun setUp() {
        backend = FakeBackend()
        confirmer = FakeConfirmer()
        val policy = McpPermissionPolicy(confirmer)
        val tools = LuaDebugTools({ if (backendAvailable) backend else null }, policy).all()
        client = McpTestClient(McpServer("emmylua-debug", "test", tools, McpCallLog()))
        client.initialize()
    }

    @Test
    fun `三个工具，名字和 UEToVS 风格一致`() {
        @Suppress("UNCHECKED_CAST")
        val tools = (client.rpc("tools/list")["result"] as Map<String, Any?>)["tools"] as List<Map<String, Any?>>
        assertEquals(listOf(S, B, I), tools.map { it["name"] })
        for (t in tools) {
            val desc = t["description"] as String
            assertTrue(desc, "USE FIRST WHEN:" in desc && "INSTEAD OF:" in desc && "NOT FOR:" in desc)
            assertTrue("说明过长：${desc.length}", desc.length <= 1200)
            val titles = (t["_meta"] as Map<*, *>)["titles"] as Map<*, *>
            assertNotNull(titles["zh"]); assertNotNull(titles["en"])
            @Suppress("UNCHECKED_CAST")
            val props = ((t["inputSchema"] as Map<String, Any?>)["properties"] as Map<String, Any?>).keys
            props.forEach { assertTrue("参数应为 snake_case：$it", it.matches(Regex("[a-z][a-z0-9_]*"))) }
        }
    }

    @Test
    fun `未知参数直接报错并列出合法名字`() {
        val r = client.call(S, mapOf("action" to "status", "sessionId" to "x"))
        assertTrue(r.isError)
        assertEquals("invalid_argument", r.code)
        assertTrue(r["message"] as String, (r["message"] as String).contains("session_id"))
    }

    @Test
    fun `action 不合法时报错`() {
        val r = client.call(S, mapOf("action" to "fly"))
        assertEquals("invalid_argument", r.code)
    }

    @Test
    fun `没有引擎时 status 给提示，其他操作报 engine_unavailable`() {
        backendAvailable = false
        val status = client.call(S, mapOf("action" to "status"))
        assertFalse(status.isError)
        assertEquals("unavailable", status["engine"])
        assertEquals("engine_unavailable", client.call(B, mapOf("action" to "list")).code)
    }

    @Test
    fun `没有会话时 status 为空，wait 返回 idle`() {
        val status = client.call(S, mapOf("action" to "status"))
        assertEquals(0L, status["count"])
        assertEquals("idle", client.call(S, mapOf("action" to "wait", "timeout_ms" to 10))["event"])
    }

    @Test
    fun `首次控制要确认，本会话总是允许后不再问`() {
        backend.addSession()
        val first = client.call(B, mapOf("action" to "add", "file" to "/proj/Bag.lua", "line" to 10))
        assertFalse(first.raw, first.isError)
        assertEquals("1", first["id"])
        assertEquals(true, first["byAgent"])
        client.call(B, mapOf("action" to "add", "file" to "/proj/Bag.lua", "line" to 20))
        assertEquals(1, confirmer.asked.size)
    }

    @Test
    fun `用户拒绝时返回 permission_denied 且不执行`() {
        backend.addSession()
        confirmer.control = ControlDecision.DENY
        val r = client.call(B, mapOf("action" to "add", "file" to "/proj/Bag.lua", "line" to 10))
        assertEquals("permission_denied", r.code)
        assertTrue(backend.breakpoints.isEmpty())
    }

    @Test
    fun `wait 遇到没看过的暂停马上返回，带位置、栈和局部变量`() {
        backend.addSession()
        backend.firePause(line = 10)
        val r = client.call(S, mapOf("action" to "wait", "timeout_ms" to 1000))
        assertEquals("break", r["event"])
        assertEquals("breakpoint", r["reason"])
        val loc = r["location"] as Map<*, *>
        assertEquals(10L, loc["line"])
        assertEquals("/proj/Bag.lua", loc["file"])
        assertEquals(3, (r["stack"] as List<*>).size)
        val locals = r["locals"] as List<*>
        assertEquals("index", (locals[0] as Map<*, *>)["n"])
        assertEquals(2L, (locals[1] as Map<*, *>)["more"])
        // 同一次暂停不会再返回第二次
        assertEquals("timeout", client.call(S, mapOf("action" to "wait", "timeout_ms" to 50))["event"])
    }

    @Test
    fun `wait 等到另一个线程触发的暂停`() {
        backend.addSession()
        Thread { Thread.sleep(50); backend.firePause(line = 42) }.start()
        val r = client.call(S, mapOf("action" to "wait", "timeout_ms" to 5000))
        assertEquals("break", r["event"])
        assertEquals(42L, (r["location"] as Map<*, *>)["line"])
    }

    @Test
    fun `step 执行后等到新位置`() {
        backend.addSession()
        backend.firePause(line = 10)
        client.call(S, mapOf("action" to "wait", "timeout_ms" to 100))
        backend.nextPauseLine = 11
        val r = client.call(S, mapOf("action" to "step", "kind" to "into"))
        assertFalse(r.raw, r.isError)
        assertEquals("step", r["action"])
        assertEquals("break", r["event"])
        assertEquals(11L, (r["location"] as Map<*, *>)["line"])
        assertTrue(backend.commands.contains("step into"))
    }

    @Test
    fun `step toLine 要求 file 和 line`() {
        backend.addSession()
        assertEquals("invalid_argument", client.call(S, mapOf("action" to "step", "kind" to "toLine")).code)
        val r = client.call(S, mapOf("action" to "step", "kind" to "toLine", "file" to "/proj/A.lua", "line" to 5))
        assertEquals("break", r["event"])
        assertTrue(backend.commands.contains("runToLine /proj/A.lua:5"))
    }

    @Test
    fun `continue 默认不等待`() {
        backend.addSession()
        backend.firePause()
        val r = client.call(S, mapOf("action" to "continue"))
        assertEquals("running", r["event"])
    }

    @Test
    fun `单步等不到时给 timeout 和下一步建议`() {
        backend.addSession()
        backend.firePause()
        backend.nextPauseLine = null
        val r = client.call(S, mapOf("action" to "step", "wait_ms" to 50))
        assertEquals("timeout", r["event"])
        assertNotNull(r["note"])
    }

    @Test
    fun `没停下时取栈报 not_paused`() {
        backend.addSession()
        assertEquals("not_paused", client.call(I, mapOf("action" to "stack")).code)
    }

    @Test
    fun `stack 分页信封`() {
        backend.addSession()
        backend.firePause()
        val r = client.call(I, mapOf("action" to "stack", "limit" to 2))
        assertEquals(2L, r["count"])
        assertEquals(true, r["hasMore"])
        assertEquals(2L, r["nextOffset"])
        assertEquals(3L, r["total"])
        assertNotNull(r["source"])
    }

    @Test
    fun `过期引用翻译成 stale_reference 并带恢复提示`() {
        backend.addSession()
        val p = backend.firePause()
        backend.stalePauseId = p.pauseId
        val r = client.call(I, mapOf("action" to "locals"))
        assertEquals("stale_reference", r.code)
        assertTrue((r["message"] as String).contains("action=wait"))
    }

    @Test
    fun `任意 Lua 求值默认关闭，value_path 可用`() {
        backend.addSession()
        backend.firePause()
        assertEquals("eval_forbidden", client.call(I, mapOf("action" to "eval", "expr" to "os.exit()", "mode" to "lua")).code)
        val ok = client.call(I, mapOf("action" to "eval", "exprs" to listOf("self.hp", "#items")))
        val results = ok["results"] as List<*>
        assertEquals(2, results.size)
        assertEquals("self.hp", (results[0] as Map<*, *>)["n"])
        assertTrue(backend.commands.contains("eval VALUE_PATH self.hp"))
    }

    @Test
    fun `打开任意 Lua 后要控制确认`() {
        backend.addSession()
        backend.firePause()
        confirmer.luaEval = true
        confirmer.control = ControlDecision.DENY
        assertEquals("permission_denied", client.call(I, mapOf("action" to "eval", "expr" to "x()", "mode" to "lua")).code)
        confirmer.control = ControlDecision.ALLOW_ONCE
        assertFalse(client.call(I, mapOf("action" to "eval", "expr" to "x()", "mode" to "lua")).isError)
    }

    @Test
    fun `clear 默认只删 Agent 断点`() {
        backend.addSession()
        backend.breakpoints += BreakpointInfo("u1", BreakpointOrigin.IDE, "/proj/A.lua", 1, true)
        client.call(B, mapOf("action" to "add", "file" to "/proj/B.lua", "line" to 2))
        val r = client.call(B, mapOf("action" to "clear"))
        assertEquals(1L, r["removed"])
        assertEquals(listOf("u1"), backend.breakpoints.map { it.breakpointId })
    }

    @Test
    fun `list 只对 Agent 断点标 byAgent`() {
        backend.breakpoints += BreakpointInfo("u1", BreakpointOrigin.IDE, "/proj/A.lua", 1, true)
        val added = client.call(B, mapOf("action" to "add", "file" to "/proj/B.lua", "line" to 2, "capture" to listOf("self.hp"), "auto_continue" to true))
        // 没有会话：未生效并提示附加后下发
        assertNotNull(added["hint"])
        val r = client.call(B, mapOf("action" to "list"))
        val items = r["items"] as List<*>
        assertEquals(null, (items[0] as Map<*, *>)["byAgent"])
        val agent = items[1] as Map<*, *>
        assertEquals(true, agent["byAgent"])
        assertEquals(listOf("self.hp"), agent["capture"])
        assertEquals(false, r["hasMore"])
    }

    @Test
    fun `attach 每次都确认，拒绝时不附加`() {
        confirmer.launch = false
        assertEquals("permission_denied", client.call(S, mapOf("action" to "attach", "pid" to 100)).code)
        assertTrue(backend.commands.isEmpty())
        confirmer.launch = true
        val r = client.call(S, mapOf("action" to "attach", "pid" to 100))
        assertEquals("s-new", r["sessionId"])
    }

    @Test
    fun `设备附加失败时透传中文错误`() {
        val r = client.call(S, mapOf("action" to "attach", "device" to "emulator-5554", "package" to "com.foo"))
        assertEquals("attach_failed", r.code)
        assertEquals("设备未连接", r["message"])
    }

    @Test
    fun `多个会话时要求 session_id，只有一个停着的就用它`() {
        backend.addSession("s1", "a.exe (1)")
        backend.addSession("s2", "b.exe (2)")
        assertEquals("invalid_argument", client.call(I, mapOf("action" to "vms")).code)
        backend.firePause("s2")
        val r = client.call(I, mapOf("action" to "stack"))
        assertEquals("s2", r["sessionId"])
    }

    @Test
    fun `用户在 IDE 里继续时 wait 返回 resumed`() {
        backend.addSession()
        Thread { Thread.sleep(50); backend.fire(DebugEvent.Resumed("s1", null, byUser = true)) }.start()
        assertEquals("resumed", client.call(S, mapOf("action" to "wait", "timeout_ms" to 5000))["event"])
    }

    @Test
    fun `output 带 next 游标`() {
        backend.addSession()
        val r = client.call(I, mapOf("action" to "output"))
        assertEquals(1L, r["next"])
        assertEquals("hello", ((r["items"] as List<*>)[0] as Map<*, *>)["text"])
        assertEquals(0L, client.call(I, mapOf("action" to "output", "cursor" to 1))["count"])
    }
}
