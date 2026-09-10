package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.*
import com.tang.intellij.lua.debugger.emmy.*
import org.junit.Assert.assertEquals
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

    private fun request() = CliEvaluationRequest("vm-1", 1, "frame-1", "locals.player")

    private class FakeBackend(private val evaluation: CliCapturedValue) : EmmyDebugBackend {
        override val debugTargetId = "target-1"
        var evaluateCalls = 0
        private val snapshot = PauseSnapshot("vm-1", 1, "thread-1", stacks = listOf(
            Stack("C:/Game.lua", 10, "main", 0, emptyList(), emptyList(), "frame-1")))

        override fun debugTargetSummary() = CliTargetSummary("target-1", "Demo", "RUNNING", true)
        override fun debugVmList() = emptyList<CliVmSummary>()
        override fun debugPause(vmId: String, pauseId: Long?) = snapshot.takeIf { it.vmId == vmId && it.pauseId == pauseId }
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
