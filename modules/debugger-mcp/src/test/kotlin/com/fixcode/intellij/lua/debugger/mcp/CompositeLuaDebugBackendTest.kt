package com.fixcode.intellij.lua.debugger.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.ExecutionException

class CompositeLuaDebugBackendTest {
    // 用本机的绝对路径：写死 /work/a 在 Windows 上不是绝对路径（没有盘符），按项目路由会失败
    private val work = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("work")
    private val a = FakeBackend().apply { addSession("sa", "a.exe (1)") }
    private val b = FakeBackend().apply { addSession("sb", "b.exe (2)") }
    private val composite = CompositeLuaDebugBackend {
        listOf(
            CompositeLuaDebugBackend.Member("A", listOf(work.resolve("a")), a),
            CompositeLuaDebugBackend.Member("B", listOf(work.resolve("b")), b),
        )
    }

    @Test
    fun `会话合并，控制按 sessionId 路由`() {
        assertEquals(listOf("sa", "sb"), composite.listSessions().map { it.sessionId })
        b.firePause("sb")
        b.nextPauseLine = null
        composite.step("sb", null, StepKind.OVER).get()
        assertEquals(listOf("step over"), b.commands)
        assertTrue(a.commands.isEmpty())
    }

    @Test
    fun `断点按文件所属项目路由`() {
        composite.setBreakpoint(BreakpointSpec(work.resolve("b/Script/X.lua").toString(), 3)).get()
        assertEquals(1, b.breakpoints.size)
        assertTrue(a.breakpoints.isEmpty())
        val e = runCatching { composite.setBreakpoint(BreakpointSpec("X.lua", 3)) }.exceptionOrNull()
        assertEquals(ErrorCode.INVALID_ARGUMENT, (e as LuaDebugBackendException).code)
    }

    @Test
    fun `变量请求在多个项目间找到有效的那个`() {
        val p = b.firePause("sb")
        val page = composite.getVariables(VariablesRequest("${p.pauseId}:0", VariableScope.LOCALS, null, null, 1, 10)).get()
        assertEquals("index", page.variables[0].name)
        val stale = runCatching { composite.getVariables(VariablesRequest("gone:0", VariableScope.LOCALS, null, null, 1, 10)).get() }
        assertEquals(ErrorCode.STALE_REFERENCE, ((stale.exceptionOrNull() as ExecutionException).cause as LuaDebugBackendException).code)
    }
}
