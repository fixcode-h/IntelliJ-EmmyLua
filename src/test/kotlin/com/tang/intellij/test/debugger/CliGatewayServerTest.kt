package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.*
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import java.net.Socket
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.io.IOException
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CliGatewayServerTest {
    @Test
    fun `windows named pipe serves authenticated JSONL requests`() {
        assumeTrue("named pipe test requires Windows", System.getProperty("os.name").startsWith("Windows", true))
        val gateway = CliGatewayService(CliTargetProvider {
            listOf(CliTargetSummary("target-pipe", "Demo", "RUNNING", true))
        })
        val server = CliGatewayServer(gateway, "pipe-token")
        val endpoint = try {
            server.startNamedPipe("emmylua-test-${System.nanoTime()}")
        } catch (error: Throwable) {
            server.close()
            throw AssertionError("Windows named pipe provider unavailable", error)
        }
        try {
            val socket = connectNamedPipeWithRetry(endpoint.host)
            socket.use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                writer.write("{\"token\":\"pipe-token\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("authenticated"))
                writer.write("{\"requestId\":\"pipe-r1\",\"operation\":\"target.list\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("target-pipe"))
            }
        } finally {
            server.close()
        }
    }

    private fun connectNamedPipeWithRetry(name: String): Win32NamedPipeSocket {
        var last: IOException? = null
        repeat(20) {
            try {
                return Win32NamedPipeSocket("\\\\.\\pipe\\$name")
            } catch (error: IOException) {
                last = error
                Thread.sleep(50)
            }
        }
        throw last ?: IOException("named pipe connection failed")
    }

    @Test
    fun `named pipe endpoint accepts canonical and short names`() {
        assertEquals("emmylua-test", CliGatewayServer.normalizePipeName("emmylua-test"))
        assertEquals("emmylua.test", CliGatewayServer.normalizePipeName("\\\\.\\pipe\\emmylua.test"))
    }

    @Test
    fun `named pipe endpoint rejects paths and unsafe names`() {
        assertThrows(IllegalArgumentException::class.java) { CliGatewayServer.normalizePipeName("..\\pipe") }
        assertThrows(IllegalArgumentException::class.java) { CliGatewayServer.normalizePipeName("has space") }
        assertThrows(IllegalArgumentException::class.java) { CliGatewayServer.normalizePipeName("a".repeat(81)) }
    }

    @Test
    fun `loopback server authenticates and serves JSONL requests`() {
        val gateway = CliGatewayService(CliTargetProvider {
            listOf(CliTargetSummary("target-1", "Demo", "RUNNING", true))
        })
        val server = CliGatewayServer(gateway, "secret-token")
        val endpoint = server.start()
        try {
            val socket = try {
                Socket(endpoint.host, endpoint.port)
            } catch (error: ConnectException) {
                // Some sandboxed runners deny JVM loopback sockets; keep the
                // pure protocol/Gateway tests authoritative in that case.
                assumeNoException("loopback socket unavailable", error)
                return
            }
            socket.use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                writer.write("{\"token\":\"secret-token\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("authenticated"))
                writer.write("{\"requestId\":\"r1\",\"operation\":\"target.list\"}\n")
                writer.flush()
                assertEquals(true, reader.readLine().contains("target-1"))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `probe-bound wait disconnect cancels only its probe`() {
        val summary = CliTargetSummary("target-1", "Demo", "RUNNING", true,
            listOf(CliVmSummary("vm-1", 1, "PIE", "PAUSED")))
        val registry = DebugTargetRegistry()
        val adapter = RecordingAdapter("target-1", summary)
        registry.register(adapter)
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val probes = AiProbeService(registry)
        val gateway = CliGatewayService(registry, leases = leases, probes = probes)
        val server = CliGatewayServer(gateway, "socket-token")
        try {
            val endpoint = server.start()
            installProbeOverSocket(endpoint, "probe-a", lease.leaseId)
            installProbeOverSocket(endpoint, "probe-b", lease.leaseId)
            assertEquals(AiProbeState.ACTIVE, probes.status("probe-a")?.state)
            assertEquals(AiProbeState.ACTIVE, probes.status("probe-b")?.state)

            Socket(endpoint.host, endpoint.port).use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                authenticate(writer, reader)
                writer.write("{\"requestId\":\"wait-a\",\"operation\":\"wait\",\"targetId\":\"target-1\",\"clientId\":\"client-a\",\"leaseId\":\"${lease.leaseId}\",\"arguments\":{\"probeId\":\"probe-a\",\"timeoutMillis\":10000}}\n")
                writer.flush()
                val registeredBy = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (!gateway.isWaiting("wait-a", "client-a") && System.nanoTime() < registeredBy) Thread.yield()
                assertTrue("wait was not registered", gateway.isWaiting("wait-a", "client-a"))
            }

            val cancelledBy = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (probes.status("probe-a")?.state == AiProbeState.ACTIVE && System.nanoTime() < cancelledBy) Thread.yield()
            assertTrue(probes.awaitCleanup())
            assertEquals(AiProbeState.CANCELLED, probes.status("probe-a")?.state)
            assertEquals(AiProbeState.ACTIVE, probes.status("probe-b")?.state)
            assertEquals(listOf("probe-a"), adapter.removed)
        } finally {
            server.close()
            probes.close()
            registry.close()
        }
    }

    private fun installProbeOverSocket(endpoint: CliServerEndpoint, probeId: String, leaseId: String) {
        Socket(endpoint.host, endpoint.port).use { socket ->
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            authenticate(writer, reader)
            writer.write("{\"requestId\":\"run-$probeId\",\"operation\":\"probe.run\",\"targetId\":\"target-1\",\"vmId\":\"vm-1\",\"clientId\":\"client-a\",\"leaseId\":\"$leaseId\",\"arguments\":{\"probeId\":\"$probeId\",\"line\":12,\"sourceIdentity\":{\"uri\":\"file:///Project/Loading.lua\",\"canonicalPath\":\"C:/Project/Loading.lua\"},\"timeoutMillis\":60000}}\n")
            writer.flush()
            val response = reader.readLine()
            assertTrue(response.contains("run-$probeId"))
            assertTrue(response.contains("\"ok\":true"))
        }
    }

    private fun authenticate(writer: java.io.BufferedWriter, reader: java.io.BufferedReader) {
        writer.write("{\"token\":\"socket-token\"}\n")
        writer.flush()
        assertTrue(reader.readLine().contains("authenticated"))
    }

    private class RecordingAdapter(
        targetId: String,
        summary: CliTargetSummary
    ) : DebugTargetAdapter {
        private val delegate = InMemoryDebugTargetAdapter(targetId, summary)
        override val targetId: String get() = delegate.targetId
        val removed = mutableListOf<String>()

        override fun describe() = delegate.describe()
        override fun listVms() = delegate.listVms()
        override fun currentPause(vmId: String, pauseId: Long?) = delegate.currentPause(vmId, pauseId)
        override fun stack(vmId: String, pauseId: Long) = delegate.stack(vmId, pauseId)
        override fun scopes(vmId: String, pauseId: Long, frameId: String) = delegate.scopes(vmId, pauseId, frameId)
        override fun variables(vmId: String, pauseId: Long, frameId: String, path: String?, maxDepth: Int,
                               maxNodes: Int, maxBytes: Int, timeoutMillis: Long) =
            delegate.variables(vmId, pauseId, frameId, path, maxDepth, maxNodes, maxBytes, timeoutMillis)
        override fun variablesReference(vmId: String, pauseId: Long, frameId: String, reference: String,
                                        maxDepth: Int, maxNodes: Int, maxBytes: Int, timeoutMillis: Long) =
            delegate.variablesReference(vmId, pauseId, frameId, reference, maxDepth, maxNodes, maxBytes, timeoutMillis)
        override fun evaluate(request: CliEvaluationRequest) = delegate.evaluate(request)
        override fun control(request: CliControlRequest) = delegate.control(request)
        override fun mutateBreakpoints(request: CliBreakpointMutation) = delegate.mutateBreakpoints(request)
        override fun listBreakpoints() = delegate.listBreakpoints()
        override fun installProbe(spec: CliProbeSpec) = delegate.installProbe(spec)

        @Synchronized
        override fun removeProbe(probeId: String, owner: String): Result<Boolean> {
            removed += probeId
            return delegate.removeProbe(probeId, owner)
        }
    }
}
