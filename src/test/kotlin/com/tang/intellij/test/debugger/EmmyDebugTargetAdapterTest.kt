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
    fun `production adapter expands scoped references and rejects stale frame`() {
        val child = VariableValue("name", LuaValueType.TSTRING.wireId, "player", 4, "string", 0, null)
        val table = VariableValue("player", LuaValueType.TSTRING.wireId, "table", 5, "table", 0, listOf(child))
        val backend = FakeBackend(CliCapturedValue("x", true, display = "ok"),
            PauseSnapshot("vm-1", 7, "thread-1", stacks = listOf(
                Stack("C:/Game.lua", 10, "main", 0, listOf(table), emptyList(), "frame-7"))))
        val adapter = EmmyDebugTargetAdapter(backend)

        val scope = adapter.scopes("vm-1", 7, "frame-7").getOrThrow().single()
        val expanded = adapter.variablesReference("vm-1", 7, "frame-7", scope.variablesReference)
        assertTrue(expanded.isSuccess)
        val player = expanded.getOrThrow().variables.single()
        assertEquals("player", player.name)
        assertNotNull(player.variablesReference)
        val childExpanded = adapter.variablesReference("vm-1", 7, "frame-7", player.variablesReference!!)
        assertEquals("name", childExpanded.getOrThrow().variables.single().name)
        assertTrue(adapter.variablesReference("vm-1", 8, "frame-7", scope.variablesReference).isFailure)
    }

    private fun request() = CliEvaluationRequest("vm-1", 1, "frame-1", "locals.player")

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
