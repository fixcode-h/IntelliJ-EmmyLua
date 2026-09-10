package com.tang.intellij.test.debugger

import com.google.gson.JsonObject
import com.tang.intellij.lua.debugger.cli.CliGatewayService
import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliJsonLines
import com.tang.intellij.lua.debugger.cli.CliTargetSummary
import com.tang.intellij.lua.debugger.cli.CliVmSummary
import com.tang.intellij.lua.debugger.cli.CliResponse
import com.tang.intellij.lua.debugger.cli.AuthorizationService
import com.tang.intellij.lua.debugger.cli.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CliGatewayServiceTest {
    private val provider = {
        listOf(CliTargetSummary("target-1", "Demo", "RUNNING", true,
            listOf(CliVmSummary("vm-1", 1, "PIE", "PAUSED", "5.4.3", "HOST_API", 3))))
    }

    @Test
    fun `read-only gateway lists targets and VMs`() {
        val gateway = CliGatewayService(provider)
        val target = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r1\",\"operation\":\"target.list\"}"))
        assertTrue(target.ok)
        assertEquals("target-1", target.data?.getAsJsonArray("targets")?.get(0)?.asJsonObject?.get("targetId")?.asString)

        val vms = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r2\",\"operation\":\"vm.list\",\"targetId\":\"target-1\"}"))
        assertEquals("vm-1", vms.data?.getAsJsonArray("vms")?.get(0)?.asJsonObject?.get("vmId")?.asString)
    }

    @Test
    fun `gateway rejects missing target and redacts untrusted projects`() {
        val gateway = CliGatewayService(provider, trustedProject = { false })
        val response = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r1\",\"operation\":\"target.status\"}"))
        assertFalse(response.ok)
        assertEquals("TARGET_REQUIRED", response.error?.code)

        val list = gateway.handle(CliJsonLines.decodeRequest("{\"requestId\":\"r2\",\"operation\":\"target.list\"}"))
        assertEquals("<redacted>", list.data?.getAsJsonArray("targets")?.get(0)?.asJsonObject?.get("projectName")?.asString)
    }

    @Test
    fun `request cache is isolated by client and rechecks revoked access`() {
        val authorization = AuthorizationService()
        authorization.grant("target-1", "client-a")
        val gateway = CliGatewayService(provider, authorization = authorization)
        val first = gateway.handle(CliJsonLines.decodeRequest(
            "{\"requestId\":\"same\",\"clientId\":\"client-a\",\"operation\":\"target.status\",\"targetId\":\"target-1\"}"
        ))
        assertTrue(first.ok)

        val otherClient = gateway.handle(CliJsonLines.decodeRequest(
            "{\"requestId\":\"same\",\"clientId\":\"client-b\",\"operation\":\"target.status\",\"targetId\":\"target-1\"}"
        ))
        assertFalse(otherClient.ok)
        assertEquals("NOT_AUTHORIZED", otherClient.error?.code)

        authorization.revoke("target-1", "client-a")
        val revoked = gateway.handle(CliJsonLines.decodeRequest(
            "{\"requestId\":\"same\",\"clientId\":\"client-a\",\"operation\":\"target.status\",\"targetId\":\"target-1\"}"
        ))
        assertFalse(revoked.ok)
        assertEquals("NOT_AUTHORIZED", revoked.error?.code)
    }

    @Test
    fun `concurrent duplicate request is admitted once`() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = CliGatewayService({
            calls.incrementAndGet()
            entered.countDown()
            release.await(1, TimeUnit.SECONDS)
            provider()
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val request = CliJsonLines.decodeRequest(
                "{\"requestId\":\"concurrent\",\"clientId\":\"client-a\",\"operation\":\"target.list\"}"
            )
            val first = pool.submit<CliResponse> { gateway.handle(request) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = pool.submit<CliResponse> { gateway.handle(request) }.get(1, TimeUnit.SECONDS)
            assertEquals("REQUEST_IN_PROGRESS", second.error?.code)
            release.countDown()
            assertTrue(first.get(1, TimeUnit.SECONDS).ok)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
            gateway.close()
        }
    }

    @Test
    fun `client and request separators cannot collide in cache keys`() {
        val gateway = CliGatewayService(provider)
        gateway.use {
            fun request(client: String, id: String) = CliRequest(id, "target.status",
                targetId = "target-1", clientId = client)
            assertTrue(gateway.handle(request("a|b", "c")).ok)
            assertTrue(gateway.handle(request("a", "b|c")).ok)
        }
    }

    @Test
    fun `evaluation result is suppressed when grant is revoked during backend execution`() {
        val authorization = AuthorizationService()
        authorization.grant("target-1", "client-a")
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target-1", "client-a").getOrThrow()
        val registry = DebugTargetRegistry()
        val fallback = InMemoryDebugTargetAdapter("target-1", provider().first())
        registry.register(object : DebugTargetAdapter by fallback {
            override fun evaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
                authorization.revoke("target-1", "client-a")
                return Result.success(CliCapturedValue(request.expression, true, "string", "secret"))
            }
        })
        CliGatewayService(registry, authorization = authorization, leases = leases).use { gateway ->
            val response = gateway.handle(CliRequest("eval", CliOperations.EVALUATE, clientId = "client-a",
                targetId = "target-1", vmId = "vm-1", leaseId = lease.leaseId,
                arguments = JsonObject().apply {
                    addProperty("pauseId", 3); addProperty("frameId", "frame-3-0"); addProperty("expression", "secret")
                }))
            assertEquals("NOT_AUTHORIZED", response.error?.code)
            assertEquals(null, response.data)
        }
    }

    @Test
    fun `stream rechecks grant before every event delivery`() {
        val authorization = AuthorizationService()
        authorization.grant("target-1", "client-a")
        val registry = DebugTargetRegistry()
        registry.register(InMemoryDebugTargetAdapter("target-1", provider().first()))
        registry.publish("target-1", "first")
        registry.publish("target-1", "secret")
        val responses = mutableListOf<CliResponse>()
        CliGatewayService(registry, authorization = authorization).use { gateway ->
            gateway.handleStreaming(CliRequest("wait", "wait", clientId = "client-a", targetId = "target-1")) {
                responses += it
                if (it.event != null) authorization.revoke("target-1", "client-a")
            }
        }
        assertEquals(2, responses.size)
        assertEquals("first", responses.first().event)
        assertEquals("NOT_AUTHORIZED", responses.last().error?.code)
    }

    @Test
    fun `probe-bound wait timeout terminates only the owned probe`() {
        val registry = DebugTargetRegistry()
        val adapter = InMemoryDebugTargetAdapter("target-1", provider().first())
        registry.register(adapter)
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val probes = AiProbeService(registry)
        val source = CliSourceIdentity("file:///Project/Loading.lua", "C:/Project/Loading.lua")
        probes.install(CliProbeSpec("probe-1", "CLI:client-a", "target-1", "vm-1", source, 12), adapter, leases)
            .getOrThrow()
        CliGatewayService(registry, leases = leases, probes = probes).use { gateway ->
            val responses = mutableListOf<CliResponse>()
            gateway.handleStreaming(CliRequest("wait-1", CliOperations.WAIT, clientId = "client-a",
                targetId = "target-1", leaseId = lease.leaseId,
                arguments = JsonObject().apply {
                    addProperty("probeId", "probe-1")
                    addProperty("timeoutMillis", 1)
                })) { responses += it }
            assertEquals("TIMEOUT", responses.last().error?.code)
            assertEquals("EXPIRED", probes.status("probe-1")?.state?.name)
        }
        probes.close()
    }

    @Test
    fun `probe-bound wait keeps active probe after successful page and rejects other owner`() {
        val registry = DebugTargetRegistry()
        val adapter = InMemoryDebugTargetAdapter("target-1", provider().first())
        registry.register(adapter)
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val probes = AiProbeService(registry)
        val source = CliSourceIdentity("file:///Project/Loading.lua", "C:/Project/Loading.lua")
        probes.install(CliProbeSpec("probe-2", "CLI:client-a", "target-1", "vm-1", source, 12), adapter, leases)
            .getOrThrow()
        registry.publish("target-1", "probe.skipped", "vm-1", 1, mapOf("probeId" to "probe-2"))
        CliGatewayService(registry, leases = leases, probes = probes).use { gateway ->
            val denied = mutableListOf<CliResponse>()
            gateway.handleStreaming(CliRequest("denied", CliOperations.WAIT, clientId = "client-b",
                targetId = "target-1", arguments = JsonObject().apply {
                    addProperty("probeId", "probe-2")
                    addProperty("cursor", 0)
                })) { denied += it }
            assertEquals("NOT_AUTHORIZED", denied.single().error?.code)

            val responses = mutableListOf<CliResponse>()
            gateway.handleStreaming(CliRequest("page", CliOperations.WAIT, clientId = "client-a",
                targetId = "target-1", leaseId = lease.leaseId,
                arguments = JsonObject().apply {
                    addProperty("probeId", "probe-2")
                    addProperty("cursor", 0)
                })) { responses += it }
            assertTrue(responses.any { it.event == "probe.skipped" })
            assertTrue(responses.last().done == true)
            assertEquals("ACTIVE", probes.status("probe-2")?.state?.name)
        }
        probes.close()
    }

    @Test
    fun `released lease response can be replayed without repeating the mutation`() {
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target-1", "client-a").getOrThrow()
        CliGatewayService(provider, leases = leases).use { gateway ->
            val request = CliRequest("release", "lease.release", clientId = "client-a", targetId = "target-1", leaseId = lease.leaseId)
            val first = gateway.handle(request)
            assertTrue(first.ok)
            assertEquals(first, gateway.handle(request))
        }
    }

    @Test
    fun `descriptor janitor writes atomically and removes stale PID`() {
        val dir = Files.createTempDirectory("emmy-cli-test")
        val path = dir.resolve("instance.json")
        val janitor = com.tang.intellij.lua.debugger.cli.CliEndpointJanitor(path)
        janitor.write(CliInstanceDescriptor(ideaInstanceId = "i1", pid = 42, product = "Rider", endpoint = "npipe://x", startedAt = "now", tokenFile = "token"))
        assertEquals("i1", janitor.read()?.ideaInstanceId)
        assertTrue(janitor.removeIfStale { false })
        assertFalse(Files.exists(path))
    }
}
