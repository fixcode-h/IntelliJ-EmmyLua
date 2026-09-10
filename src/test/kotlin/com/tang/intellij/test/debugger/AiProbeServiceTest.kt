package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AiProbeServiceTest {
    private fun source(path: String = "C:/Project/Loading.lua", hash: String? = null, epoch: Long? = null) =
        CliSourceIdentity("file:///$path", path, hash, epoch, verified = hash != null)

    private fun spec(
        probeId: String = "p-1",
        source: CliSourceIdentity = source(),
        captures: List<String> = listOf("self.State"),
        autoContinue: Boolean = false
    ) = CliProbeSpec(
        probeId = probeId,
        owner = "CLI:client-a",
        targetId = "target-1",
        vmId = "vm-1",
        sourceIdentity = source,
        line = 12,
        captures = captures,
        autoContinue = autoContinue
    )

    private fun pauseEvent(
        registry: DebugTargetRegistry,
        service: AiProbeService? = null,
        reasons: Set<String> = setOf("PROBE:p-1"),
        pauseId: Long = 7,
        path: String = "C:/Project/Loading.lua",
        line: Int = 12,
        sourceHash: String? = null,
        sourceEpoch: Long? = null
    ): CliEventRecord = registry.publish(
        "target-1", "debug.paused", "vm-1", pauseId,
        mapOf(
            "file" to path,
            "canonicalPath" to path,
            "line" to line,
            "frameId" to "frame-$pauseId-0",
            "threadId" to "thread-1",
            "reasons" to reasons,
            "sourceHash" to sourceHash,
            "sourceEpoch" to sourceEpoch
        )
    )!!.also {
        service?.awaitEventWorker()
        service?.awaitCleanup()
    }

    @Test
    fun `probe captures matching pause and auto continues only for probe-only reason`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter()
        registry.register(adapter)
        val leases = ControlLeaseManager()
        val lease = leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val service = AiProbeService(registry, scheduler)
        try {
            service.install(spec(autoContinue = true), adapter, leases).getOrThrow()
            pauseEvent(registry, service)
            assertEquals(listOf("self.State"), adapter.evaluated)
            assertEquals(listOf("Continue"), adapter.controls.map { it.action })

            // A user pause at the same location is observable but must not be
            // automatically continued by the Probe.
            val second = spec("p-2", captures = emptyList(), autoContinue = true)
            service.install(second, adapter, leases).getOrThrow()
            pauseEvent(registry, service, reasons = setOf("USER", "PROBE:p-2"), pauseId = 8)
            assertEquals(1, adapter.controls.size)
            assertTrue(lease.leaseId.isNotBlank())
        } finally {
            service.close()
        }
    }

    @Test
    fun `probe rejects source identity and stale pause or frame without evaluation`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter()
        registry.register(adapter)
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val service = AiProbeService(registry, scheduler)
        try {
            service.install(spec(source = source(hash = "expected")), adapter, leases).getOrThrow()
            pauseEvent(registry, service, sourceHash = "different")
            assertTrue(adapter.evaluated.isEmpty())

            // A matching source but a stale frame is rejected by the adapter;
            // the Probe must not turn that into a successful capture.
            adapter.evaluationFailure = IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE)
            pauseEvent(registry, service, pauseId = 9, sourceHash = "expected")
            assertEquals(1, adapter.evaluated.size)
            assertTrue(adapter.lastCapture?.success == false)
        } finally {
            service.close()
        }
    }

    @Test
    fun `lease expiry and lifecycle cleanup remove backend probe`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter()
        registry.register(adapter)
        var now = 100L
        val leases = ControlLeaseManager(nowMillis = { now })
        leases.acquire("target-1", "client-a", 10).getOrThrow()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val service = AiProbeService(registry, scheduler)
        try {
            service.install(spec(), adapter, leases).getOrThrow()
            now = 111L
            pauseEvent(registry, service)
            assertTrue(adapter.removed.contains("p-1"))

            // Re-install with a fresh lease and verify VM/target teardown is
            // idempotent and always removes the backend contribution.
            leases.acquire("target-1", "client-a", 100).getOrThrow()
            service.install(spec("p-2"), adapter, leases).getOrThrow()
            service.onVmClosed("target-1", "vm-1")
            service.onVmClosed("target-1", "vm-1")
            service.awaitCleanup()
            assertTrue(adapter.removed.contains("p-2"))
        } finally {
            service.close()
        }
    }

    @Test
    fun `capture budget is explicit and does not return oversized values`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter(value = "0123456789")
        registry.register(adapter)
        val events = mutableListOf<CliEventRecord>()
        val listener = registry.addListener { events += it }
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val service = AiProbeService(registry, scheduler, maxCaptures = 2, maxCaptureBytes = 8)
        try {
            service.install(spec(captures = listOf("a", "b")), adapter, leases).getOrThrow()
            pauseEvent(registry, service)
            assertEquals("the first value is evaluated once", 1, adapter.captures.size)
            val hit = events.last { it.type == "probe.hit" }
            @Suppress("UNCHECKED_CAST")
            val values = hit.payload["values"] as List<CliCapturedValue>
            assertEquals(2, values.size)
            assertFalse(values[1].success)
            assertEquals(CliErrorCodes.EVALUATION_LIMIT_EXCEEDED, values[1].errorCode)
        } finally {
            service.close()
            listener.close()
        }
    }

    @Test
    fun `capture budget includes nested children in serialized size`() {
        val registry = DebugTargetRegistry()
        val child = CliVariableSnapshot("nested", "string", "x".repeat(100))
        val adapter = FakeAdapter(children = listOf(child, child, child))
        registry.register(adapter)
        val events = mutableListOf<CliEventRecord>()
        val listener = registry.addListener { events += it }
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val service = AiProbeService(registry, ScheduledThreadPoolExecutor(1), maxCaptureBytes = 512)
        try {
            service.install(spec(captures = listOf("value")), adapter, leases).getOrThrow()
            pauseEvent(registry, service)
            @Suppress("UNCHECKED_CAST")
            val capture = (events.last { it.type == "probe.hit" }.payload["values"] as List<CliCapturedValue>).single()
            assertTrue(capture.truncated)
            assertTrue(com.google.gson.Gson().toJson(capture).toByteArray(Charsets.UTF_8).size <= 512)
            assertTrue(capture.children.size < 3)
        } finally {
            service.close()
            listener.close()
        }
    }

    @Test
    fun `probe evaluation timeout is terminal and removes backend`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter(evaluationDelayMillis = 40)
        registry.register(adapter)
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val service = AiProbeService(registry, ScheduledThreadPoolExecutor(1))
        try {
            service.install(spec().copy(timeoutMillis = 10), adapter, leases).getOrThrow()
            pauseEvent(registry, service)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (service.status("p-1")?.state == AiProbeState.ACTIVE && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(service.status("p-1")?.state != AiProbeState.ACTIVE)
            assertTrue(adapter.removed.contains("p-1"))
            val events = registry.journal("target-1")!!.readAfter(0).getOrThrow().events
            assertTrue(events.none { it.type == "probe.hit" || it.type == "probe.autoContinued" })
        } finally {
            service.close()
        }
    }

    @Test
    fun `user control revokes probe while evaluation is in flight`() {
        val registry = DebugTargetRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = FakeAdapter(evaluationEntered = entered, evaluationRelease = release)
        registry.register(adapter)
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val service = AiProbeService(registry, ScheduledThreadPoolExecutor(1))
        try {
            service.install(spec(autoContinue = true), adapter, leases).getOrThrow()
            val pause = Thread { pauseEvent(registry, service = service) }
            pause.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            service.notifyUserControl("target-1")
            assertEquals(AiProbeState.ORPHANED, service.status("p-1")?.state)
            service.awaitCleanup()
            assertTrue(adapter.removed.contains("p-1"))

            release.countDown()
            pause.join(1_000)
            assertTrue(adapter.controls.isEmpty())
            val events = registry.journal("target-1")!!.readAfter(0).getOrThrow().events
            assertTrue(events.none { it.type == "probe.hit" || it.type == "probe.autoContinued" })
        } finally {
            release.countDown()
            service.close()
        }
    }

    @Test
    fun `auto continue failure is reported and user control orphans probe`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter(controlFailure = IllegalStateException("CONTROL_REJECTED"))
        registry.register(adapter)
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val service = AiProbeService(registry, ScheduledThreadPoolExecutor(1))
        try {
            service.install(spec(autoContinue = true).copy(hitLimit = 2), adapter, leases).getOrThrow()
            pauseEvent(registry, service)
            assertEquals(AiProbeState.ACTIVE, service.status("p-1")?.state)
            assertTrue(registry.journal("target-1")!!.readAfter(0).getOrThrow().events.any {
                it.type == "probe.autoContinueError"
            })
            service.notifyUserControl("target-1")
            assertEquals(AiProbeState.ORPHANED, service.status("p-1")?.state)
            service.awaitCleanup()
            assertTrue(adapter.removed.contains("p-1"))
            assertTrue(service.remove("p-1", "client-b").getOrThrow() == false)
        } finally {
            service.close()
        }
    }

    @Test
    fun `closed probe service rejects new installations`() {
        val registry = DebugTargetRegistry()
        val adapter = FakeAdapter()
        registry.register(adapter)
        val leases = ControlLeaseManager()
        leases.acquire("target-1", "client-a", 30_000).getOrThrow()
        val service = AiProbeService(registry, ScheduledThreadPoolExecutor(1))
        service.close()
        assertTrue(service.install(spec(), adapter, leases).isFailure)
    }

    private class FakeAdapter(
        private val value: String = "Loading",
        private val evaluationDelayMillis: Long = 0,
        private val controlFailure: Throwable? = null,
        private val evaluationEntered: CountDownLatch? = null,
        private val evaluationRelease: CountDownLatch? = null,
        private val children: List<CliVariableSnapshot> = emptyList()
    ) : DebugTargetAdapter {
        override val targetId: String = "target-1"
        override fun describe() = CliTargetSummary(targetId, "Demo", "RUNNING", true,
            listOf(CliVmSummary("vm-1", 1, "PIE", "PAUSED")))
        override fun listVms() = describe().vms
        override fun currentPause(vmId: String, pauseId: Long?) = pauseId?.let {
            CliPauseSnapshot(
                CliPauseReference(vmId, it, "thread-1"),
                listOf(CliFrame("frame-$it-0", 0, "C:/Project/Loading.lua", 12, "main"))
            )
        }
        override fun stack(vmId: String, pauseId: Long) = Result.failure<List<CliFrame>>(
            IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        override fun scopes(vmId: String, pauseId: Long, frameId: String) = Result.failure<List<CliScope>>(
            IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        override fun variables(vmId: String, pauseId: Long, frameId: String, path: String?, maxDepth: Int,
                               maxNodes: Int, maxBytes: Int, timeoutMillis: Long) = Result.failure<CliVariablesPage>(
            IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))

        val evaluated = mutableListOf<String>()
        val controls = mutableListOf<CliControlRequest>()
        val removed = mutableListOf<String>()
        val captures = mutableListOf<CliCapturedValue>()
        var evaluationFailure: Throwable? = null
        var lastCapture: CliCapturedValue? = null

        override fun evaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
            evaluated += request.expression
            evaluationEntered?.countDown()
            evaluationRelease?.await(2, TimeUnit.SECONDS)
            if (evaluationDelayMillis > 0) Thread.sleep(evaluationDelayMillis)
            val failure = evaluationFailure
            if (failure != null) {
                val result = CliCapturedValue(request.expression, false, errorCode = failure.message)
                captures += result
                lastCapture = result
                return Result.failure(failure)
            }
            val result = CliCapturedValue(request.expression, true, "string", value, children = children)
            captures += result
            lastCapture = result
            return Result.success(result)
        }

        override fun control(request: CliControlRequest): Result<CliControlResult> {
            controls += request
            controlFailure?.let { return Result.failure(it) }
            return Result.success(CliControlResult(request.action, true, request.pauseId))
        }

        override fun mutateBreakpoints(request: CliBreakpointMutation) = Result.success(
            CliBreakpointResult(1, request.add))
        override fun listBreakpoints() = emptyList<CliBreakpointSpec>()
        override fun installProbe(spec: CliProbeSpec): Result<CliProbeSpec> = Result.success(spec)
        override fun removeProbe(probeId: String, owner: String): Result<Boolean> {
            removed += probeId
            return Result.success(true)
        }
    }
}
