package com.tang.intellij.test.debugger

import com.google.gson.JsonObject
import com.tang.intellij.lua.debugger.cli.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CliProbeWaitLifecycleTest {
    private class CapturingScheduler : ScheduledThreadPoolExecutor(1) {
        val timeouts = CopyOnWriteArrayList<Runnable>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            timeouts += command
            return super.schedule(command, 1, TimeUnit.DAYS)
        }
    }

    private class Fixture(journal: CliEventJournal = CliEventJournal()) : AutoCloseable {
        val registry = DebugTargetRegistry()
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target", "client", 30_000).getOrThrow()
        val backend = ConcurrentHashMap<String, CliProbeSpec>()
        var beforeRemove: (String) -> Unit = {}
        var removalResult: Result<Boolean>? = null
        val scheduler = CapturingScheduler()
        private val fallback = InMemoryDebugTargetAdapter("target",
            CliTargetSummary("target", "Demo", "RUNNING", true, listOf(CliVmSummary("vm", 1, "Lua", "RUNNING"))))
        val adapter = object : DebugTargetAdapter by fallback {
            override fun installProbe(spec: CliProbeSpec): Result<CliProbeSpec> {
                backend[spec.probeId] = spec
                return Result.success(spec)
            }
            override fun removeProbe(probeId: String, owner: String): Result<Boolean> {
                beforeRemove(probeId)
                removalResult?.let { return it }
                val existing = backend[probeId] ?: return Result.success(false)
                return Result.success(existing.owner == owner && backend.remove(probeId, existing))
            }
        }
        val probes = AiProbeService(registry, scheduler)
        val gateway = CliGatewayService(registry, leases = leases, probes = probes)

        init { registry.register(adapter, journal) }

        fun install(id: String = "probe", line: Int = 12) = probes.install(CliProbeSpec(
            id, "CLI:client", "target", "vm", CliSourceIdentity("file:///test.lua", "C:/test.lua"), line
        ), adapter, leases).getOrThrow()

        fun request(probeId: String? = "probe", timeout: Long = 1) = CliRequest(
            "wait", CliOperations.WAIT, targetId = "target", clientId = "client", leaseId = lease.leaseId,
            arguments = JsonObject().apply {
                probeId?.let { addProperty("probeId", it) }
                addProperty("timeoutMillis", timeout)
            }
        )

        fun run(request: CliRequest): List<CliResponse> = mutableListOf<CliResponse>().also { responses ->
            gateway.handleStreaming(request) { responses += it }
        }

        override fun close() {
            gateway.close()
            probes.close()
            registry.close()
        }
    }

    @Test
    fun `timeout removes only the bound probe and ordinary wait preserves all probes`() {
        Fixture().use { fixture ->
            fixture.install()
            fixture.install("other")
            assertEquals(CliErrorCodes.TIMEOUT, fixture.run(fixture.request(probeId = null)).last().error?.code)
            assertEquals(setOf("probe", "other"), fixture.probes.activeProbeIds())
            assertEquals(CliErrorCodes.TIMEOUT, fixture.run(fixture.request()).last().error?.code)
            assertTrue(fixture.probes.awaitCleanup())
            assertEquals(AiProbeState.EXPIRED, fixture.probes.status("probe")?.state)
            assertEquals(setOf("other"), fixture.backend.keys)
            assertEquals(AiProbeState.ACTIVE, fixture.probes.status("other")?.state)
        }
    }

    @Test
    fun `invalid arguments missing lease wrong target and expired cursor do not remove probe`() {
        Fixture(CliEventJournal(maxEntries = 1)).use { fixture ->
            fixture.install()
            val wrongTarget = InMemoryDebugTargetAdapter("other-target", CliTargetSummary("other-target", "Demo", "RUNNING", true))
            fixture.registry.register(wrongTarget)
            assertEquals(CliErrorCodes.NOT_AUTHORIZED,
                fixture.run(fixture.request().copy(targetId = "other-target")).last().error?.code)
            assertEquals(CliErrorCodes.LEASE_REQUIRED,
                fixture.run(fixture.request().copy(leaseId = null)).last().error?.code)
            for ((key, value) in listOf("cursor" to -1L, "limit" to 0L, "timeoutMillis" to 0L)) {
                val request = fixture.request().also { it.arguments.addProperty(key, value) }
                assertEquals("invalid $key", CliErrorCodes.INVALID_ARGUMENT, fixture.run(request).last().error?.code)
                assertEquals(AiProbeState.ACTIVE, fixture.probes.status("probe")?.state)
            }
            fixture.registry.publish("target", "first")
            fixture.registry.publish("target", "second")
            assertEquals(CliErrorCodes.EVENT_CURSOR_EXPIRED, fixture.run(fixture.request()).last().error?.code)
            assertTrue(fixture.probes.awaitCleanup())
            assertTrue(fixture.backend.containsKey("probe"))
            assertEquals(AiProbeState.ACTIVE, fixture.probes.status("probe")?.state)
        }
    }

    @Test
    fun `explicit cancellation terminates bound probe without resuming target`() {
        Fixture().use { fixture ->
            fixture.install()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val waiting = worker.submit<List<CliResponse>> { fixture.run(fixture.request(timeout = 30_000)) }
                val cancel = CliRequest("cancel", CliOperations.CANCEL, targetId = "target", clientId = "client",
                    arguments = JsonObject().apply { addProperty("waitRequestId", "wait") })
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!fixture.gateway.isWaiting("wait", "client") && System.nanoTime() < deadline) Thread.yield()
                assertTrue("wait never registered", fixture.gateway.isWaiting("wait", "client"))
                assertTrue(fixture.gateway.handle(cancel).data?.get("cancelled")?.asBoolean == true)
                assertEquals(CliErrorCodes.CANCELLED, waiting.get(2, TimeUnit.SECONDS).last().error?.code)
                assertTrue(fixture.probes.awaitCleanup())
                assertEquals(AiProbeState.CANCELLED, fixture.probes.status("probe")?.state)
                assertFalse(fixture.backend.containsKey("probe"))
            } finally { worker.shutdownNow() }
        }
    }

    @Test
    fun `event delivery failure terminates the bound probe`() {
        Fixture().use { fixture ->
            fixture.install()
            fixture.registry.publish("target", "available")
            runCatching {
                fixture.gateway.handleStreaming(fixture.request()) { throw IOException("client disconnected") }
            }
            assertTrue(fixture.probes.awaitCleanup())
            assertEquals(AiProbeState.CANCELLED, fixture.probes.status("probe")?.state)
            assertFalse(fixture.backend.containsKey("probe"))
        }
    }

    @Test
    fun `terminal probe still permits reading its queued events`() {
        Fixture().use { fixture ->
            fixture.install()
            fixture.probes.remove("probe", "CLI:client").getOrThrow()
            fixture.registry.publish("target", "probe.hit", payload = mapOf("probeId" to "probe"))
            val responses = fixture.run(fixture.request())
            assertEquals("probe.hit", responses.first().event)
            assertTrue(responses.last().done == true)
            assertTrue(responses.last().ok)
            assertEquals(AiProbeState.REMOVED, fixture.probes.status("probe")?.state)
        }
    }

    @Test
    fun `old timeout and wait handle cannot terminate reinstalled probe`() {
        Fixture().use { fixture ->
            fixture.install()
            val oldTimeout = fixture.scheduler.timeouts.single()
            val oldWait = fixture.probes.bindForWait("probe", "CLI:client", "target")!!
            fixture.probes.remove("probe", "CLI:client").getOrThrow()
            assertTrue(fixture.probes.awaitCleanup())
            fixture.install(line = 24)
            oldTimeout.run()
            assertFalse(fixture.probes.terminateFromWait(oldWait, AiProbeState.CANCELLED))
            assertTrue(fixture.probes.awaitCleanup())
            assertEquals(AiProbeState.ACTIVE, fixture.probes.status("probe")?.state)
            assertEquals(24, fixture.backend["probe"]?.line)
        }
    }

    @Test
    fun `queued cleanup does not delete a new probe generation`() {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.beforeRemove = { id ->
                if (id == "blocker") {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS)) { "cleanup release missing" }
                }
            }
            try {
                fixture.install("blocker")
                fixture.install()
                fixture.probes.remove("blocker", "CLI:client").getOrThrow()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                fixture.probes.remove("probe", "CLI:client").getOrThrow()
                fixture.install(line = 24)
                release.countDown()
                assertTrue(fixture.probes.awaitCleanup())
                assertEquals(AiProbeState.ACTIVE, fixture.probes.status("probe")?.state)
                assertEquals(24, fixture.backend["probe"]?.line)
            } finally { release.countDown() }
        }
    }

    @Test
    fun `cleanup retries a backend result failure and clears diagnostic after success`() {
        Fixture().use { fixture ->
            fixture.install()
            fixture.removalResult = Result.failure(IOException("TRANSPORT_CLOSED"))
            fixture.probes.remove("probe", "CLI:client").getOrThrow()
            assertTrue(fixture.probes.awaitCleanup())
            assertEquals("TRANSPORT_CLOSED", fixture.probes.status("probe")?.cleanupErrorCode)
            assertTrue(fixture.backend.containsKey("probe"))
            val failure = fixture.registry.journal("target")!!.readAfter(0).getOrThrow().events.single()
            assertEquals("probe.cleanupFailed", failure.type)
            assertEquals(true, failure.payload["willRetry"])
            fixture.removalResult = null
            fixture.scheduler.timeouts.last().run()
            assertTrue(fixture.probes.awaitCleanup())
            assertFalse(fixture.backend.containsKey("probe"))
            assertNull(fixture.probes.status("probe")?.cleanupErrorCode)
        }
    }

    @Test
    fun `permanent cleanup exception stops after three attempts and remains observable`() {
        Fixture().use { fixture ->
            fixture.install()
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            fixture.beforeRemove = {
                attempts.incrementAndGet()
                throw IOException("TRANSPORT_CLOSED")
            }
            fixture.probes.remove("probe", "CLI:client").getOrThrow()
            assertTrue(fixture.probes.awaitCleanup())
            repeat(2) {
                fixture.scheduler.timeouts.last().run()
                assertTrue(fixture.probes.awaitCleanup())
            }
            assertEquals(3, attempts.get())
            assertEquals(3, fixture.scheduler.timeouts.size) // original expiry plus two retries
            assertEquals("TRANSPORT_CLOSED", fixture.probes.status("probe")?.cleanupErrorCode)
            val failures = fixture.registry.journal("target")!!.readAfter(0).getOrThrow().events
            assertEquals(listOf(1, 2, 3), failures.map { it.payload["attempt"] })
            assertEquals(false, failures.last().payload["willRetry"])
            assertTrue(fixture.backend.containsKey("probe"))
        }
    }
}
