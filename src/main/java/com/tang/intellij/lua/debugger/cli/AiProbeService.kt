package com.tang.intellij.lua.debugger.cli

import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AiProbeState {
    ACTIVE, ORPHANED, COMPLETED, EXPIRED, LEASE_EXPIRED, VM_CLOSED,
    VM_CLOSING, CONTEXT_RESET, TARGET_DISCONNECTED, TARGET_CLOSED, REVOKED, REMOVED, SERVICE_CLOSED
}

data class AiProbeStatus(
    val probeId: String,
    val state: AiProbeState,
    val hitCount: Int,
    val updatedAtMillis: Long,
    val targetId: String? = null,
    val owner: String? = null
)

private fun createBoundedExecutor(name: String, queueCapacity: Int): ThreadPoolExecutor = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity),
    { task -> Thread(task, name).apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy()
)

/**
 * Owns the lifecycle of CLI probes. It deliberately consumes immutable event
 * DTOs and delegates all Lua access to the target adapter.
 */
class AiProbeService(
    private val eventRegistry: DebugTargetRegistry? = null,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "EmmyLua-AiProbe").apply { isDaemon = true }
    },
    private val maxCaptures: Int = 32,
    private val maxCaptureBytes: Int = 64 * 1024,
    private val conditionEvaluator: RestrictedConditionEvaluator = RestrictedConditionEvaluator(),
    private val trustedTarget: (String) -> Boolean = { true },
    private val evaluationExecutor: ExecutorService = createBoundedExecutor("EmmyLua-AiProbeEval", 32)
) : AutoCloseable {
    private val valuePathEvaluator = RestrictedValuePathEvaluator()
    private val gson = Gson()
    private data class Active(
        val spec: CliProbeSpec,
        val adapter: DebugTargetAdapter,
        val leases: ControlLeaseManager,
        val stopped: AtomicBoolean = AtomicBoolean(),
        var hits: Int = 0,
        val orphaned: AtomicBoolean = AtomicBoolean(),
        var lastEventCursor: Long = 0L,
        val deadlineNanos: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.timeoutMillis),
        var listenerHandle: AutoCloseable? = null,
        var terminalState: AiProbeState? = null
    )

    private val active = ConcurrentHashMap<String, Active>()
    private val installLocks = ConcurrentHashMap<String, Any>()
    private val terminal = ConcurrentHashMap<String, AiProbeStatus>()
    private val closed = AtomicBoolean()
    private var registryListener: AutoCloseable? = null
    private val eventExecutor: ThreadPoolExecutor = createBoundedExecutor("EmmyLua-AiProbeEvent", 256)
    private val cleanupExecutor: ThreadPoolExecutor = createBoundedExecutor("EmmyLua-AiProbeCleanup", 64)

    init {
        eventRegistry?.addListener(::dispatchEvent)?.let { handle ->
            // Keep a single registry listener for the service lifetime.
            registryListener = handle
        }
        scheduler.scheduleAtFixedRate({ expireInvalidLeases() }, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun install(spec: CliProbeSpec, adapter: DebugTargetAdapter, leases: ControlLeaseManager): Result<CliProbeSpec> {
        if (closed.get()) {
            return Result.failure(IllegalStateException(CliErrorCodes.SERVER_CLOSED))
        }
        if (spec.owner.isBlank() || !spec.owner.startsWith("CLI:")) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.NOT_AUTHORIZED))
        }
        if (adapter.targetId != spec.targetId) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.TARGET_NOT_FOUND))
        }
        if (!adapter.projectTrusted || !trustedTarget(spec.targetId)) {
            return Result.failure(IllegalStateException(CliErrorCodes.PROJECT_UNTRUSTED))
        }
        if (spec.probeId.isBlank() || spec.probeId.length > 128) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.INVALID_PROBE))
        }
        if (maxCaptures <= 0 || maxCaptureBytes <= 0 || spec.captures.size > maxCaptures) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.INVALID_PROBE))
        }
        val owner = spec.owner.removePrefix("CLI:")
        val lease = leases.current(spec.targetId)
        if (lease == null || !leases.isOwner(spec.targetId, lease.leaseId, owner)) {
            return Result.failure(IllegalStateException(CliErrorCodes.LEASE_REQUIRED))
        }
        if (spec.line <= 0 || spec.hitLimit !in 1..1_000 || spec.timeoutMillis !in 1..10 * 60_000L ||
            spec.captures.any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size > 4096 } ||
            spec.condition?.toByteArray(Charsets.UTF_8)?.size?.let { it > 4096 } == true ||
            spec.sourceIdentity.canonicalPath.isBlank()) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.INVALID_PROBE))
        }
        spec.condition?.takeIf { it.isNotBlank() }?.let { condition ->
            conditionEvaluator.validate(condition).getOrElse {
                return Result.failure(IllegalArgumentException(CliErrorCodes.INVALID_CONDITION, it))
            }
        }
        spec.captures.forEach { expression ->
            valuePathEvaluator.parseSegments(expression).getOrElse {
                return Result.failure(IllegalArgumentException(it.message ?: CliErrorCodes.EVALUATION_DENIED, it))
            }
        }
        val lock = installLocks.computeIfAbsent(spec.probeId) { Any() }
        try {
            synchronized(lock) {
            if (closed.get()) return Result.failure(IllegalStateException(CliErrorCodes.SERVER_CLOSED))
            if (active.containsKey(spec.probeId)) return Result.failure(IllegalArgumentException(CliErrorCodes.PROBE_EXISTS))
            val installed = adapter.installProbe(spec)
            if (installed.isFailure) return installed
            val state = Active(spec, adapter, leases)
            active[spec.probeId] = state
            // Installation can race with revoke/close while the backend call
            // is in flight. Detach immediately instead of leaving a zombie.
            if (closed.get() || !trustedTarget(spec.targetId) || !leaseIsValid(state)) {
                stop(state, removeBackend = true, terminalState =
                    if (closed.get()) AiProbeState.SERVICE_CLOSED else AiProbeState.REVOKED)
                return Result.failure(IllegalStateException(CliErrorCodes.LEASE_REQUIRED))
            }
            }
        } finally {
            installLocks.remove(spec.probeId, lock)
        }
        terminal.remove(spec.probeId)
        scheduler.schedule({ expire(spec.probeId) }, spec.timeoutMillis, TimeUnit.MILLISECONDS)
        return Result.success(spec)
    }

    fun remove(probeId: String, owner: String): Result<Boolean> {
        val state = active[probeId] ?: return Result.success(false)
        if (state.spec.owner != owner) return Result.failure(IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED))
        stop(state, removeBackend = true, terminalState = AiProbeState.REMOVED)
        return Result.success(true)
    }

    fun status(probeId: String): AiProbeStatus? = active[probeId]?.let {
        AiProbeStatus(probeId, AiProbeState.ACTIVE, it.hits, System.currentTimeMillis(), it.spec.targetId, it.spec.owner)
    } ?: terminal[probeId]

    fun activeProbeIds(): Set<String> = active.keys.toSet()

    /** Test/support hook to await events already accepted by the worker. */
    internal fun awaitEventWorker(timeoutMillis: Long = 2_000L): Boolean = runCatching {
        eventExecutor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    internal fun awaitCleanup(timeoutMillis: Long = 2_000L): Boolean = runCatching {
        cleanupExecutor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    fun statuses(targetId: String? = null, owner: String? = null): List<AiProbeStatus> {
        val activeStatuses = active.values.asSequence()
            .filter { targetId == null || it.spec.targetId == targetId }
            .filter { owner == null || it.spec.owner == owner }
            .map { AiProbeStatus(it.spec.probeId, AiProbeState.ACTIVE, it.hits, System.currentTimeMillis(), it.spec.targetId, it.spec.owner) }
        val terminalStatuses = terminal.values.asSequence()
            .filter { targetId == null || it.targetId == targetId }
            .filter { owner == null || it.owner == owner }
        return (activeStatuses + terminalStatuses)
            .sortedBy { it.probeId }
            .toList()
    }

    fun notifyUserControl(targetId: String) {
        active.values.filter { it.spec.targetId == targetId && !it.stopped.get() }.forEach { state ->
            if (state.orphaned.compareAndSet(false, true)) {
                eventRegistry?.publish(targetId, "probe.orphaned", state.spec.vmId, null,
                    mapOf("probeId" to state.spec.probeId, "reason" to "USER_CONTROL"))
                stop(state, removeBackend = true, terminalState = AiProbeState.ORPHANED)
            }
        }
    }

    fun onGrantRevoked(targetId: String, owner: String? = null) {
        val normalizedOwner = owner?.let { if (it.startsWith("CLI:")) it else "CLI:$it" }
        active.values.filter { it.spec.targetId == targetId && (normalizedOwner == null || it.spec.owner == normalizedOwner) }
            .forEach { stop(it, removeBackend = true, terminalState = AiProbeState.REVOKED) }
    }

    fun onVmClosed(targetId: String, vmId: String) {
        active.values.filter { it.spec.targetId == targetId && it.spec.vmId == vmId }
            .forEach { stop(it, removeBackend = true, terminalState = AiProbeState.VM_CLOSED) }
    }

    fun onVmClosing(targetId: String, vmId: String) {
        active.values.filter { it.spec.targetId == targetId && it.spec.vmId == vmId }
            .forEach { stop(it, removeBackend = true, terminalState = AiProbeState.VM_CLOSING) }
    }

    fun onVmContextReset(targetId: String, vmId: String) {
        active.values.filter { it.spec.targetId == targetId && it.spec.vmId == vmId }
            .forEach { stop(it, removeBackend = true, terminalState = AiProbeState.CONTEXT_RESET) }
    }

    fun onTargetClosed(targetId: String) {
        active.values.filter { it.spec.targetId == targetId }
            .forEach { stop(it, removeBackend = true, terminalState = AiProbeState.TARGET_CLOSED) }
    }

    fun onTargetDisconnected(targetId: String) {
        active.values.filter { it.spec.targetId == targetId }
            .forEach { stop(it, removeBackend = true, terminalState = AiProbeState.TARGET_DISCONNECTED) }
    }

    /** Receives target journal events; useful for tests and registry integration. */
    fun onEvent(event: CliEventRecord) {
        if (event.type == "vm.contextReset" || event.type == "context.reset") {
            event.vmId?.let { onVmContextReset(event.targetId, it) }
            return
        }
        if (event.type == "vm.lifecycle") {
            val state = event.payload["state"]?.toString()?.uppercase()
            if (state == "CLOSING") {
                event.vmId?.let { onVmClosing(event.targetId, it) }
            } else if (state == "CLOSED") {
                event.vmId?.let { onVmClosed(event.targetId, it) }
            }
            return
        }
        // probe.hit is emitted by this service and must not be fed back as a
        // second pause notification.
        if (event.type != "debug.paused") return
        val candidates = active.values.filter {
            it.spec.targetId == event.targetId && it.spec.vmId == event.vmId && !it.stopped.get()
        }
        candidates.forEach { state -> handlePause(state, event) }
    }

    private fun dispatchEvent(event: CliEventRecord) {
        // Lifecycle transitions only detach probes and publish terminal state;
        // backend removal is queued below, so the producer thread never waits
        // for adapter/lifecycle work.
        if (event.type == "vm.lifecycle" || event.type == "vm.contextReset" || event.type == "context.reset") {
            onEvent(event)
            return
        }
        if (closed.get()) return
        runCatching { eventExecutor.execute { onEvent(event) } }
    }

    private fun handlePause(state: Active, event: CliEventRecord) {
        synchronized(state) {
            if (state.stopped.get() || state.orphaned.get() || state.hits >= state.spec.hitLimit || event.cursor <= state.lastEventCursor) return
            if (remainingMillis(state) <= 0L) {
                publishConditionError(state, event.pauseId ?: 0L, CliErrorCodes.PROBE_TIMEOUT,
                    "probe evaluation deadline expired")
                stop(state, removeBackend = true, terminalState = AiProbeState.EXPIRED)
                return
            }
            state.lastEventCursor = event.cursor
            val payload = event.payload
            val file = payloadString(payload["file"]) ?: payloadString(payload["canonicalPath"])
            val line = number(payload["line"])
            if (file != null && state.spec.sourceIdentity.canonicalPath.isNotBlank() &&
                com.tang.intellij.lua.debugger.emmy.SourceIdentity.normalizePath(file) !=
                com.tang.intellij.lua.debugger.emmy.SourceIdentity.normalizePath(state.spec.sourceIdentity.canonicalPath)) return
            if (line != null && line.toInt() != state.spec.line) return
            val eventHash = payloadString(payload["sourceHash"])
            if (state.spec.sourceIdentity.sourceHash != null &&
                state.spec.sourceIdentity.sourceHash != eventHash) return
            val eventEpoch = number(payload["sourceEpoch"] ?: payload["loaderEpoch"])
            if (state.spec.sourceIdentity.sourceEpoch != null &&
                state.spec.sourceIdentity.sourceEpoch != eventEpoch) return
            val pauseId = event.pauseId ?: number(payload["pauseId"]) ?: return
            val frameId = payloadString(payload["frameId"])?.takeIf { it.isNotBlank() } ?: run {
                publishConditionError(state, pauseId, CliErrorCodes.STALE_PAUSE_REFERENCE,
                    "pause event does not contain a frame identity")
                return
            }
            val threadId = payloadString(payload["threadId"])
            val snapshot = state.adapter.currentPause(state.spec.vmId, pauseId)
            if (snapshot == null || snapshot.reference.vmId != state.spec.vmId ||
                snapshot.reference.pauseId != pauseId ||
                (snapshot.reference.threadId != null && snapshot.reference.threadId != threadId) ||
                snapshot.frames.none { it.frameId == frameId }) {
                publishConditionError(state, pauseId, CliErrorCodes.STALE_PAUSE_REFERENCE,
                    "pause/frame identity no longer matches the active snapshot")
                return
            }
            val reasons = extractReasons(payload)
            if (reasons.isNotEmpty() && reasons.none { isProbeReason(it, state.spec.probeId) }) return

            // A revoked/expired lease invalidates the probe before any Lua
            // evaluation or automatic control is attempted.
            val lease = state.leases.current(state.spec.targetId)
            val owner = state.spec.owner.removePrefix("CLI:")
            if (lease == null || !state.leases.isOwner(state.spec.targetId, lease.leaseId, owner)) {
                stop(state, removeBackend = true, terminalState = AiProbeState.LEASE_EXPIRED)
                return
            }

            val condition = state.spec.condition?.trim()
            if (!condition.isNullOrEmpty()) {
                val conditionResult = conditionEvaluator.evaluate(condition) { path ->
                    evaluateBounded(state, CliEvaluationRequest(
                        state.spec.vmId, pauseId, frameId, path,
                        policy = "VALUE_PATH", maxDepth = 0, maxNodes = 1,
                        maxBytes = maxCaptureBytes, sourceIdentity = state.spec.sourceIdentity,
                        threadId = threadId
                    )).fold(
                        onSuccess = { value ->
                            if (value.success) Result.success(value)
                            else Result.failure(IllegalStateException(
                                value.errorCode ?: CliErrorCodes.EVALUATION_DENIED,
                                value.errorMessage?.let { IllegalStateException(it) }))
                        },
                        onFailure = { Result.failure(it) }
                    )
                }
                if (conditionResult.isFailure) {
                    val error = conditionResult.exceptionOrNull()
                    publishConditionError(state, pauseId, errorCode(error), error?.message)
                    if (errorCode(error) == CliErrorCodes.PROBE_TIMEOUT) {
                        stop(state, removeBackend = true, terminalState = AiProbeState.EXPIRED)
                    }
                    return
                }
                if (state.stopped.get() || closed.get() || !leaseIsValid(state)) return
                if (conditionResult.getOrThrow().not()) {
                    eventRegistry?.publish(state.spec.targetId, "probe.skipped", state.spec.vmId, pauseId,
                        mapOf("probeId" to state.spec.probeId, "reason" to "CONDITION_FALSE"))
                    return
                }
            }

            if (state.stopped.get() || closed.get() || !leaseIsValid(state)) return
            state.hits++
            var usedBytes = 0
            var captureTimedOut = false
            val captures = state.spec.captures.map { expression ->
                if (usedBytes >= maxCaptureBytes) {
                    CliCapturedValue(expression, false, errorCode = CliErrorCodes.EVALUATION_LIMIT_EXCEEDED,
                        errorMessage = "capture byte budget exceeded")
                } else {
                    val remaining = maxCaptureBytes - usedBytes
                    evaluateBounded(state, CliEvaluationRequest(
                        state.spec.vmId, pauseId, frameId, expression,
                        policy = "VALUE_PATH", maxBytes = remaining.coerceAtLeast(1),
                        sourceIdentity = state.spec.sourceIdentity, threadId = threadId
                    )).fold(onSuccess = { value ->
                        val fitted = fitCapture(value, remaining)
                        usedBytes += fitted.second
                        fitted.first
                    }, onFailure = { error ->
                        if (errorCode(error) == CliErrorCodes.PROBE_TIMEOUT) captureTimedOut = true
                        CliCapturedValue(expression, false, errorCode = errorCode(error), errorMessage = error?.message)
                    })
                }
            }
            if (state.stopped.get() || closed.get()) return
            if (captureTimedOut) {
                eventRegistry?.publish(state.spec.targetId, "probe.timeout", state.spec.vmId, pauseId,
                    mapOf("probeId" to state.spec.probeId, "errorCode" to CliErrorCodes.PROBE_TIMEOUT,
                        "values" to captures))
                stop(state, removeBackend = true, terminalState = AiProbeState.EXPIRED)
                return
            }
            val canContinue = !captureTimedOut && !state.stopped.get() && !closed.get() &&
                leaseIsValid(state) && state.spec.autoContinue && canAutoContinue(state, reasons)
            var autoContinued = false
            var autoContinueError: Throwable? = null
            if (canContinue) {
                val result = state.adapter.control(CliControlRequest("Continue", state.spec.vmId, pauseId, threadId))
                autoContinued = result.getOrNull()?.accepted == true
                if (!autoContinued) autoContinueError = result.exceptionOrNull()
            }
            if (state.stopped.get() || closed.get()) return
            eventRegistry?.publish(state.spec.targetId, "probe.hit", state.spec.vmId, pauseId, mapOf(
                "probeId" to state.spec.probeId,
                "frameId" to frameId,
                "hitCount" to state.hits,
                "values" to captures,
                "autoContinued" to autoContinued,
                "autoContinueError" to autoContinueError?.let {
                    mapOf("errorCode" to errorCode(it), "errorMessage" to it.message.orEmpty())
                }
            ))
            if (autoContinued) {
                eventRegistry?.publish(state.spec.targetId, "probe.autoContinued", state.spec.vmId, pauseId,
                    mapOf("probeId" to state.spec.probeId))
            }
            if (autoContinueError != null) {
                eventRegistry?.publish(state.spec.targetId, "probe.autoContinueError", state.spec.vmId, pauseId,
                    mapOf("probeId" to state.spec.probeId,
                        "errorCode" to errorCode(autoContinueError),
                        "errorMessage" to autoContinueError?.message.orEmpty()))
            }
            // A timeout/lease revocation may have raced with evaluation. Do
            // not publish a hit or issue control after the probe became
            // terminal while the backend call was in flight.
            if (state.stopped.get() || closed.get()) return
            if (state.hits >= state.spec.hitLimit) {
                stop(state, removeBackend = true, terminalState = AiProbeState.COMPLETED)
            }
        }
    }

    private fun extractReasons(payload: Map<String, Any?>): Set<String> {
        val raw = payload["reasons"]
        return when (raw) {
            is Iterable<*> -> raw.mapNotNull(::payloadString).flatMap(::normalizeReasons).toSet()
            null -> payloadString(payload["reason"])?.split(',')?.flatMap(::normalizeReasons)?.toSet()
                ?: emptySet()
            else -> normalizeReasons(raw.toString()).toSet()
        }
    }

    private fun normalizeReasons(value: String): List<String> = value.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::uppercase)

    private fun payloadString(value: Any?): String? = when (value) {
        is String -> value
        is com.google.gson.JsonPrimitive -> if (value.isString) value.asString else value.toString()
        else -> value?.toString()
    }

    private fun number(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        is com.google.gson.JsonPrimitive -> if (value.isNumber) value.asLong else value.asString.toLongOrNull()
        else -> null
    }

    private fun canAutoContinue(state: Active, reasons: Set<String>): Boolean {
        if (state.orphaned.get() || reasons.isEmpty()) return false
        return reasons.all { isProbeReason(it, state.spec.probeId) }
    }

    private fun isProbeReason(reason: String, probeId: String): Boolean {
        val normalized = reason.trim().uppercase()
        return normalized == probeId.trim().uppercase() ||
            normalized == "PROBE:${probeId.trim()}".uppercase()
    }

    private fun publishConditionError(state: Active, pauseId: Long, code: String, message: String?) {
        eventRegistry?.publish(state.spec.targetId, "probe.conditionError", state.spec.vmId, pauseId,
            mapOf("probeId" to state.spec.probeId, "errorCode" to code,
                "errorMessage" to message.orEmpty()))
    }

    private fun fitCapture(value: CliCapturedValue, budget: Int): Pair<CliCapturedValue, Int> {
        if (!value.success) return value to 0
        if (jsonBytes(value) <= budget) return value to jsonBytes(value)
        var candidate = value.copy(truncated = true, errorCode = CliErrorCodes.EVALUATION_LIMIT_EXCEEDED,
            errorMessage = "capture byte budget exceeded")
        while (candidate.children.isNotEmpty() && jsonBytes(candidate) > budget) {
            candidate = candidate.copy(children = candidate.children.dropLast(1))
        }
        if (jsonBytes(candidate) > budget) {
            var display = candidate.display.orEmpty()
            while (display.isNotEmpty() && jsonBytes(candidate.copy(display = display)) > budget) {
                display = truncateUtf8(display, (display.toByteArray(Charsets.UTF_8).size * 3 / 4).coerceAtLeast(display.length - 1))
            }
            candidate = candidate.copy(display = display)
        }
        while (jsonBytes(candidate) > budget && candidate.display?.isNotEmpty() == true) {
            candidate = candidate.copy(display = truncateUtf8(candidate.display.orEmpty(), candidate.display.orEmpty().toByteArray(Charsets.UTF_8).size - 1))
        }
        return candidate to jsonBytes(candidate).coerceAtMost(budget)
    }

    private fun jsonBytes(value: CliCapturedValue): Int =
        gson.toJson(value).toByteArray(Charsets.UTF_8).size

    private fun remainingMillis(state: Active): Long =
        TimeUnit.NANOSECONDS.toMillis((state.deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    private fun evaluateBounded(
        state: Active,
        request: CliEvaluationRequest
    ): Result<CliCapturedValue> {
        val timeout = remainingMillis(state)
        if (timeout <= 0L) {
            return Result.failure(IllegalStateException(CliErrorCodes.PROBE_TIMEOUT))
        }
        val future = try {
            evaluationExecutor.submit<Result<CliCapturedValue>> { state.adapter.evaluate(request) }
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        return try {
            future.get(timeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Result.failure(IllegalStateException(CliErrorCodes.PROBE_TIMEOUT))
        } catch (error: Throwable) {
            Result.failure(error.cause ?: error)
        }
    }

    private fun errorCode(error: Throwable?): String {
        val message = error?.message.orEmpty()
        val match = Regex("[A-Z][A-Z0-9_]{2,}").find(message)?.value
        return match ?: CliErrorCodes.EVALUATION_DENIED
    }

    private fun expire(probeId: String) {
        active[probeId]?.let { stop(it, removeBackend = true, terminalState = AiProbeState.EXPIRED) }
    }

    private fun expireInvalidLeases() {
        active.values.toList().forEach { state ->
            if (state.stopped.get()) return@forEach
            if (!trustedTarget(state.spec.targetId)) {
                stop(state, removeBackend = true, terminalState = AiProbeState.REVOKED)
                return@forEach
            }
            val owner = state.spec.owner.removePrefix("CLI:")
            val lease = state.leases.current(state.spec.targetId)
            if (lease == null || !state.leases.isOwner(state.spec.targetId, lease.leaseId, owner)) {
                stop(state, removeBackend = true, terminalState = AiProbeState.LEASE_EXPIRED)
            }
        }
    }

    private fun stop(state: Active, removeBackend: Boolean, terminalState: AiProbeState) {
        if (!state.stopped.compareAndSet(false, true)) return
        synchronized(state) {
            state.terminalState = terminalState
            active.remove(state.spec.probeId, state)
            state.listenerHandle?.close()
            terminal[state.spec.probeId] = AiProbeStatus(
                state.spec.probeId, terminalState, state.hits, System.currentTimeMillis(),
                state.spec.targetId, state.spec.owner)
            while (terminal.size > 256) terminal.keys.firstOrNull()?.let(terminal::remove) ?: break
        }
        if (removeBackend) {
            val cleanup = Runnable {
                // A new generation may reuse the same id while this cleanup
                // is queued; never remove that generation's backend entry.
                if (active[state.spec.probeId] !== state) {
                    runCatching { state.adapter.removeProbe(state.spec.probeId, state.spec.owner) }
                }
            }
            runCatching { cleanupExecutor.execute(cleanup) }.onFailure {
                // During shutdown no worker is available; best effort cleanup
                // is preferable to holding the lifecycle/evaluation thread.
            }
        }
    }

    private fun leaseIsValid(state: Active): Boolean {
        val owner = state.spec.owner.removePrefix("CLI:")
        val lease = state.leases.current(state.spec.targetId)
        return lease != null && state.leases.isOwner(state.spec.targetId, lease.leaseId, owner)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.values.toList().forEach { stop(it, removeBackend = true, terminalState = AiProbeState.SERVICE_CLOSED) }
        registryListener?.close()
        registryListener = null
        scheduler.shutdownNow()
        evaluationExecutor.shutdownNow()
        eventExecutor.shutdownNow()
        cleanupExecutor.shutdownNow()
    }

    private fun isLuaTruthy(value: CliCapturedValue): Boolean {
        if (!value.success) return false
        if (value.type.equals("boolean", ignoreCase = true)) return value.display == "true"
        return !value.display.isNullOrBlank() && !value.display.equals("nil", true) &&
            !value.display.equals("false", true)
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return value.substring(0, end)
    }
}
