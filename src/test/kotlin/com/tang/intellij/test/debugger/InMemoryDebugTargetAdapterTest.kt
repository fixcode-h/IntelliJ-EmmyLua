package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.*
import com.tang.intellij.lua.debugger.emmy.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryDebugTargetAdapterTest {
    private fun value(name: String, text: String, type: String = "string", children: List<VariableValue>? = null) =
        VariableValue(name, LuaValueType.TSTRING.wireId, text, LuaValueType.TSTRING.wireId, type, 0, children)

    private fun adapter(): InMemoryDebugTargetAdapter {
        val nested = value("nested", "ok")
        val globals = value("Global", "42", "number")
        val rootChildren = mutableListOf<VariableValue>()
        val root = VariableValue("state", LuaValueType.TSTRING.wireId, "table",
            LuaValueType.TTABLE.wireId, "table", 0, rootChildren)
        rootChildren += nested
        rootChildren += root // cycle must be represented as truncated, not recurse forever
        val stack = Stack("C:/Project/Main.lua", 12, "main", 0,
            localVariables = listOf(root), upvalueVariables = emptyList(),
            frameId = "frame-explicit", globalVariables = listOf(globals))
        val snapshot = PauseSnapshot("vm-1", 7, "thread-1", stacks = listOf(stack),
            connectionEpoch = 2, contextGeneration = 3, sourceEpoch = 4)
        return InMemoryDebugTargetAdapter("target-1",
            CliTargetSummary("target-1", "Demo", "PAUSED", true,
                listOf(CliVmSummary("vm-1", 1, "main", "PAUSED"))))
            .also { it.putPause(snapshot) }
    }

    @Test
    fun `variables expose recursive children globals and cycle truncation`() {
        val adapter = adapter()
        val scopes = adapter.scopes("vm-1", 7, "frame-explicit").getOrThrow()
        assertEquals(listOf("locals", "globals"), scopes.map { it.name })
        val page = adapter.variables("vm-1", 7, "frame-explicit", maxDepth = 3,
            maxNodes = 20, maxBytes = 4096).getOrThrow()
        assertEquals(listOf("state", "Global"), page.variables.map { it.name })
        val state = page.variables.first()
        assertNotNull(state.variablesReference)
        assertTrue(state.children.any { it.name == "nested" })
        assertTrue(state.children.any { it.name == "state" && it.truncated })

        val expanded = adapter.variablesReference("vm-1", 7, "frame-explicit",
            scopes.first { it.name == "locals" }.variablesReference, maxDepth = 1,
            maxNodes = 10, maxBytes = 1024).getOrThrow()
        assertEquals("state", expanded.variables.first().name)
    }

    @Test
    fun `value path evaluation and stale references are bounded`() {
        val adapter = adapter()
        val result = adapter.evaluate(CliEvaluationRequest(
            vmId = "vm-1", pauseId = 7, frameId = "frame-explicit", expression = "state.nested"
        )).getOrThrow()
        assertEquals("ok", result.display)
        assertTrue(adapter.evaluate(CliEvaluationRequest(
            vmId = "vm-1", pauseId = 7, frameId = "frame-explicit", expression = "state()"
        )).isFailure)
        adapter.removePause("vm-1", 7)
        assertTrue(adapter.variablesReference("vm-1", 7, "frame-explicit", "mem-ref-1").isFailure)
    }

    @Test
    fun `breakpoint revision conflict rolls back atomically`() {
        val adapter = adapter()
        val spec = CliBreakpointSpec("bp-1", "CLI:test", "vm-1",
            CliSourceIdentity("file:///C:/Project/Main.lua", "C:/Project/Main.lua"), 12)
        val first = adapter.mutateBreakpoints(CliBreakpointMutation(add = listOf(spec), owner = "CLI:test")).getOrThrow()
        assertEquals(1L, first.revision)
        assertTrue(adapter.mutateBreakpoints(CliBreakpointMutation(
            remove = listOf("bp-1"), expectedRevision = 0, owner = "CLI:test"
        )).isFailure)
        assertEquals(1, adapter.listBreakpoints().size)
    }
}
