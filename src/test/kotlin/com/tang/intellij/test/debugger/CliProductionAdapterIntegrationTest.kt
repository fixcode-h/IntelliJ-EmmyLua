package com.tang.intellij.test.debugger

import com.google.gson.JsonParser
import com.tang.intellij.lua.debugger.cli.*
import com.tang.intellij.lua.debugger.emmy.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.net.ConnectException
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Exercises JSONL Gateway -> registry -> production adapter over loopback. */
class CliProductionAdapterIntegrationTest {
    @Test
    fun `real gateway expands production adapter references over JSONL`() {
        val child = VariableValue("a.b", LuaValueType.TSTRING.wireId, "child",
            LuaValueType.TSTRING.wireId, "string", 0, null)
        val root = VariableValue("player", LuaValueType.TSTRING.wireId, "table",
            LuaValueType.TTABLE.wireId, "table", 0, listOf(child))
        val backend = SnapshotBackend(PauseSnapshot("vm-1", 7, "thread-1", stacks = listOf(
            Stack("C:/Game.lua", 10, "main", 0, listOf(root), emptyList(), "frame-7"))))
        val registry = DebugTargetRegistry()
        registry.register(EmmyDebugTargetAdapter(backend))
        val gateway = CliGatewayService(registry)
        val server = CliGatewayServer(gateway, "integration-token")
        val endpoint = server.start()
        try {
            val socket = try {
                Socket(endpoint.host, endpoint.port)
            } catch (error: ConnectException) {
                assumeNoException("loopback socket unavailable", error)
                return
            }
            socket.use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                writer.write("{\"token\":\"integration-token\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("authenticated"))

                writer.write("{\"requestId\":\"scopes\",\"operation\":\"scopes\",\"targetId\":\"target-1\",\"vmId\":\"vm-1\",\"arguments\":{\"pauseId\":7,\"frameId\":\"frame-7\"}}\n")
                writer.flush()
                val scopes = JsonParser.parseString(reader.readLine()).asJsonObject
                    .get("data").asJsonObject.get("scopes").asJsonArray.get(0).asJsonObject
                val scopeReference = scopes.get("variablesReference").asString

                writer.write("{\"requestId\":\"variables\",\"operation\":\"variables\",\"targetId\":\"target-1\",\"vmId\":\"vm-1\",\"arguments\":{\"pauseId\":7,\"frameId\":\"frame-7\",\"variablesReference\":\"$scopeReference\"}}\n")
                writer.flush()
                val variables = JsonParser.parseString(reader.readLine()).asJsonObject
                    .get("data").asJsonObject.get("variables").asJsonArray
                assertEquals("player", variables.get(0).asJsonObject.get("name").asString)
                val childReference = variables.get(0).asJsonObject.get("variablesReference").asString

                writer.write("{\"requestId\":\"children\",\"operation\":\"variables\",\"targetId\":\"target-1\",\"vmId\":\"vm-1\",\"arguments\":{\"pauseId\":7,\"frameId\":\"frame-7\",\"variablesReference\":\"$childReference\"}}\n")
                writer.flush()
                val children = JsonParser.parseString(reader.readLine()).asJsonObject
                    .get("data").asJsonObject.get("variables").asJsonArray
                assertEquals("a.b", children.get(0).asJsonObject.get("name").asString)
            }
        } finally {
            server.close()
        }
    }

    private class SnapshotBackend(private val snapshot: PauseSnapshot) : EmmyDebugBackend {
        override val debugTargetId = "target-1"
        override fun debugTargetSummary() = CliTargetSummary("target-1", "Integration", "PAUSED", true,
            listOf(CliVmSummary("vm-1", 1, "main", "PAUSED")))
        override fun debugVmList() = debugTargetSummary().vms
        override fun debugPause(vmId: String, pauseId: Long?) =
            snapshot.takeIf { it.vmId == vmId && it.pauseId == pauseId }
        override fun debugControl(request: CliControlRequest) = Result.success(CliControlResult(request.action, true))
        override fun debugEvaluate(request: CliEvaluationRequest) =
            Result.success(CliCapturedValue(request.expression, true, "string", "ok"))
        override fun debugBreakpoints() = emptyList<CliBreakpointSpec>()
        override fun debugMutateBreakpoints(request: CliBreakpointMutation) =
            Result.success(CliBreakpointResult(0, emptyList()))
        override fun debugInstallProbe(spec: CliProbeSpec) = Result.success(spec)
        override fun debugRemoveProbe(probeId: String, owner: String) = Result.success(true)
    }
}
