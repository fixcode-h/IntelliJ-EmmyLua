package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.*
import com.tang.intellij.lua.debugger.emmy.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmmyDebugTargetAdapterTest {
    @Test
    fun `evaluate delegates to backend and preserves children`() {
        val child = CliVariableSnapshot("name", "string", "player", null, 0)
        val expected = CliCapturedValue("locals.player", true, "table", "table", children = listOf(child))
        val backend = FakeBackend(expected)
        val adapter = EmmyDebugTargetAdapter(backend)

        val result = adapter.evaluate(request())

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrThrow())
        assertEquals(1, backend.evaluateCalls)
    }

    @Test
    fun `evaluate rejects unsafe policy and stale frame`() {
        val backend = FakeBackend(CliCapturedValue("x", true, display = "ok"))
        val adapter = EmmyDebugTargetAdapter(backend)

        assertEquals(CliErrorCodes.EVALUATION_DENIED,
            adapter.evaluate(request().copy(policy = "UNSAFE_LUA_EXPRESSION")).exceptionOrNull()?.message)
        assertEquals(CliErrorCodes.STALE_PAUSE_REFERENCE,
            adapter.evaluate(request().copy(frameId = "missing")).exceptionOrNull()?.message)
        assertEquals(0, backend.evaluateCalls)
    }

    @Test
    fun `production adapter keeps scoped references bound and rejects stale frame`() {
        val dottedChild = VariableValue("a.b", LuaValueType.TSTRING.wireId, "dotted", LuaValueType.TSTRING.wireId, "string", 0, null)
        val numericChild = VariableValue("7", LuaValueType.TNUMBER.wireId, "numeric", LuaValueType.TSTRING.wireId, "string", 0, null)
        val local = VariableValue("same", LuaValueType.TSTRING.wireId, "local", LuaValueType.TTABLE.wireId, "table", 0,
            listOf(dottedChild, numericChild))
        val upvalue = VariableValue("same", LuaValueType.TSTRING.wireId, "upvalue", LuaValueType.TSTRING.wireId, "string", 0, null)
        val global = VariableValue("same", LuaValueType.TSTRING.wireId, "global", LuaValueType.TSTRING.wireId, "string", 0, null)
        val backend = FakeBackend(CliCapturedValue("x", true, display = "ok"),
            PauseSnapshot("vm-1", 7, "thread-1", stacks = listOf(
                Stack("C:/Game.lua", 10, "main", 0, listOf(local), listOf(upvalue), "frame-7", listOf(global)))))
        val adapter = EmmyDebugTargetAdapter(backend)

        val scopes = adapter.scopes("vm-1", 7, "frame-7").getOrThrow()
        assertEquals(listOf("locals", "upvalues", "globals"), scopes.map { it.name })
        assertEquals("local", adapter.variablesReference("vm-1", 7, "frame-7", scopes[0].variablesReference)
            .getOrThrow().variables.single().display)
        assertEquals("upvalue", adapter.variablesReference("vm-1", 7, "frame-7", scopes[1].variablesReference)
            .getOrThrow().variables.single().display)
        assertEquals("global", adapter.variablesReference("vm-1", 7, "frame-7", scopes[2].variablesReference)
            .getOrThrow().variables.single().display)
        val localSnapshot = adapter.variablesReference("vm-1", 7, "frame-7", scopes[0].variablesReference)
            .getOrThrow().variables.single()
        assertNotNull(localSnapshot.variablesReference)
        val childExpanded = adapter.variablesReference("vm-1", 7, "frame-7", localSnapshot.variablesReference!!)
        assertEquals(listOf("a.b", "[7]"), childExpanded.getOrThrow().variables.map { it.name })
        assertTrue(adapter.variablesReference("vm-1", 8, "frame-7", scopes[0].variablesReference).isFailure)
    }

    @Test
    fun `production adapter evicts oldest reference and keeps newest readable`() {
        val value = VariableValue("player", LuaValueType.TSTRING.wireId, "ok",
            LuaValueType.TSTRING.wireId, "string", 0, null)
        val backend = FakeBackend(CliCapturedValue("x", true, display = "ok"),
            PauseSnapshot("vm-1", 7, "thread-1", stacks = listOf(
                Stack("C:/Game.lua", 10, "main", 0, listOf(value), emptyList(), "frame-7"))))
        val adapter = EmmyDebugTargetAdapter(backend)
        val first = adapter.scopes("vm-1", 7, "frame-7").getOrThrow().single().variablesReference
        var newest = first
        repeat(1024) {
            newest = adapter.scopes("vm-1", 7, "frame-7").getOrThrow().single().variablesReference
        }

        assertTrue(adapter.variablesReference("vm-1", 7, "frame-7", first).isFailure)
        assertTrue(adapter.variablesReference("vm-1", 7, "frame-7", newest).isSuccess)
    }

    @Test
    fun `native truncated table has no fabricated empty reference`() {
        val truncated = VariableValue("state", LuaValueType.TSTRING.wireId, "table",
            LuaValueType.TTABLE.wireId, "table", 42, emptyList(), truncated = true)
        val backend = FakeBackend(CliCapturedValue("x", true, display = "ok"),
            PauseSnapshot("vm-1", 7, "thread-1", stacks = listOf(
                Stack("C:/Game.lua", 10, "main", 0, listOf(truncated), emptyList(), "frame-7"))))
        val page = EmmyDebugTargetAdapter(backend).variables("vm-1", 7, "frame-7", null, 3, 10, 4096).getOrThrow()
        val value = page.variables.single()
        assertTrue(page.truncated)
        assertTrue(value.truncated)
        assertEquals(null, value.variablesReference)
        assertEquals(null, value.childCount)
    }

    private fun request() = CliEvaluationRequest("vm-1", 1, "frame-1", "player")

    private class FakeBackend(private val evaluation: CliCapturedValue,
                              private val pauseSnapshot: PauseSnapshot = PauseSnapshot("vm-1", 1, "thread-1", stacks = listOf(
                                  Stack("C:/Game.lua", 10, "main", 0, emptyList(), emptyList(), "frame-1")))) : EmmyDebugBackend {
        override val debugTargetId = "target-1"
        var evaluateCalls = 0

        override fun debugTargetSummary() = CliTargetSummary("target-1", "Demo", "RUNNING", true)
        override fun debugVmList() = emptyList<CliVmSummary>()
        override fun debugPause(vmId: String, pauseId: Long?) = pauseSnapshot.takeIf { it.vmId == vmId && it.pauseId == pauseId }
        override fun debugControl(request: CliControlRequest) = Result.success(CliControlResult(request.action, true))
        override fun debugEvaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
            evaluateCalls++
            return Result.success(evaluation)
        }
        override fun debugBreakpoints() = emptyList<CliBreakpointSpec>()
        override fun debugMutateBreakpoints(request: CliBreakpointMutation) = Result.success(CliBreakpointResult(0, emptyList()))
        override fun debugInstallProbe(spec: CliProbeSpec) = Result.success(spec)
        override fun debugRemoveProbe(probeId: String, owner: String) = Result.success(true)
    }
}
