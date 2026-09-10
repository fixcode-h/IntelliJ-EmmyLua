package com.tang.intellij.lua.debugger.cli

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.NoSuchElementException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

fun interface CliTargetProvider {
    fun targets(): List<CliTargetSummary>
}

/** DTO-only command dispatcher. Backend objects never cross this boundary. */
class CliGatewayService(
    private val provider: CliTargetProvider,
    private val trustedProject: () -> Boolean = { true },
    private val targetRegistry: DebugTargetRegistry? = provider as? DebugTargetRegistry,
    private val authorization: AuthorizationService? = null,
    private val leases: ControlLeaseManager = ControlLeaseManager(),
    private val probes: AiProbeService? = null,
    private val instanceList: () -> List<CliInstanceDescriptor> = { emptyList() },
    private val audit: (String, String, String?, Boolean) -> Unit = { _, _, _, _ -> },
    private val maxRequestsPerSecond: Int = 120
) : AutoCloseable {
    private val gson = Gson()
    private data class WaitRegistration(
        val targetId: String,
        val clientId: String,
        val journal: CliEventJournal,
        val cancelled: AtomicBoolean = AtomicBoolean(),
        val serverClosed: AtomicBoolean = AtomicBoolean()
    )

    /**
     * Tracks non-wait requests so a second client can cancel them.  The
     * backend API intentionally has no cross-thread force-kill primitive for
     * Lua.  A request that is already inside native evaluation/control is
     * therefore marked and its eventual response is converted to
     * CANCEL_UNSUPPORTED instead of claiming that the operation was undone.
     */
    private data class ExecutionRegistration(
        val targetId: String?,
        val clientId: String,
        val operation: String,
        val cancelled: AtomicBoolean = AtomicBoolean()
    )

    private data class CachedResponse(val fingerprint: String, val createdAtMillis: Long, val response: CliResponse)

    private val waiters = ConcurrentHashMap<String, WaitRegistration>()
    private val executions = ConcurrentHashMap<String, ExecutionRegistration>()
    private val responseCache = Collections.synchronizedMap(object : LinkedHashMap<String, CachedResponse>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResponse>?): Boolean = size > 256
    })
    private val responseCacheTtlMillis = 30_000L
    private val maxWaitMillis = 10 * 60_000L
    private val closed = AtomicBoolean(false)
    private data class RateWindow(var startedAtMillis: Long, var count: Int)
    private val rateWindows = ConcurrentHashMap<String, RateWindow>()

    internal fun journalFor(targetId: String): CliEventJournal? = targetRegistry?.journal(targetId)

    /** Cancels one outstanding wait, used when a client connection closes. */
    internal fun cancelWait(requestId: String, clientId: String = "local"): Boolean {
        val registration = waiters[requestKey(clientId, requestId)] ?: return false
        registration.cancelled.set(true)
        registration.journal.signalWaiters()
        return true
    }

    fun handle(request: CliRequest): CliResponse {
        if (closed.get()) {
            return CliJsonLines.error(request.requestId, "SERVER_CLOSED", "CLI gateway is closed", true)
        }
        try {
            validateRequestShape(request)
            validateDeadline(request)
        } catch (error: CliGatewayException) {
            return CliJsonLines.error(request.requestId, error.code, error.message, error.retryable)
        } catch (error: CliProtocolException) {
            return CliJsonLines.error(request.requestId, error.code, error.message, error.retryable)
        } catch (error: IllegalArgumentException) {
            return CliJsonLines.error(request.requestId, CliErrorCodes.INVALID_ARGUMENT,
                error.message ?: "invalid request")
        }
        if (!allowRate(request)) {
            return CliJsonLines.error(request.requestId, CliErrorCodes.RATE_LIMITED,
                "request rate limit exceeded", retryable = true)
        }
        val fingerprint = requestFingerprint(request)
        var execution: ExecutionRegistration? = null
        if (request.operation != CliOperations.WAIT && request.operation != CliOperations.CANCEL) {
            synchronized(responseCache) {
                val key = requestKey(request)
                val cached = responseCache[key]
                if (cached != null && System.currentTimeMillis() - cached.createdAtMillis <= responseCacheTtlMillis) {
                    try {
                        validateCachedAccess(request)
                    } catch (error: CliGatewayException) {
                        return CliJsonLines.error(request.requestId, error.code, error.message, error.retryable)
                    }
                    return if (cached.fingerprint == fingerprint) cached.response
                    else CliJsonLines.error(request.requestId, "DUPLICATE_REQUEST_ID", "requestId was reused with different content")
                }
                if (cached != null) responseCache.remove(key)
                val registration = ExecutionRegistration(request.targetId, currentClient(request), request.operation)
                if (executions.putIfAbsent(key, registration) != null) {
                    return CliJsonLines.error(request.requestId, CliErrorCodes.REQUEST_IN_PROGRESS,
                        "requestId is already being processed", retryable = true)
                }
                execution = registration
            }
        }
        val startedNanos = System.nanoTime()
        val response = try {
            if (execution?.cancelled?.get() == true) {
                CliJsonLines.error(request.requestId, CliErrorCodes.CANCEL_UNSUPPORTED,
                    "request was cancelled before backend execution")
            } else {
            when (request.operation) {
                CliOperations.INSTANCE_LIST -> ok(request, instanceListJson())
                CliOperations.TARGET_LIST -> ok(request, targetList(request))
                CliOperations.TARGET_STATUS -> ok(request, targetStatus(request))
                CliOperations.VM_LIST -> ok(request, vmList(request))
                CliOperations.WAIT -> waitOnce(request)
                CliOperations.CANCEL -> cancel(request)
                CliOperations.STACK -> ok(request, stack(request))
                CliOperations.SCOPES -> ok(request, scopes(request))
                CliOperations.VARIABLES -> ok(request, variables(request))
                CliOperations.LEASE_ACQUIRE -> leaseAcquire(request)
                CliOperations.LEASE_HEARTBEAT -> leaseHeartbeat(request)
                CliOperations.LEASE_RELEASE -> leaseRelease(request)
                CliOperations.BREAKPOINT_LIST -> ok(request, breakpointList(request))
                CliOperations.BREAKPOINT_ADD -> breakpointMutation(request, add = true)
                CliOperations.BREAKPOINT_REMOVE -> breakpointMutation(request, add = false)
                CliOperations.PAUSE, CliOperations.CONTINUE, CliOperations.STEP_IN,
                CliOperations.STEP_OVER, CliOperations.STEP_OUT -> control(request)
                CliOperations.EVALUATE -> evaluate(request)
                CliOperations.PROBE_RUN -> probeInstall(request)
                CliOperations.PROBE_REMOVE -> probeRemove(request)
                CliOperations.PROBE_STATUS -> probeStatus(request)
                CliOperations.PROBE_LIST -> probeList(request)
                else -> CliJsonLines.error(request.requestId, "UNKNOWN_OPERATION", "unsupported operation")
            }
            }
        } catch (error: CliGatewayException) {
            CliJsonLines.error(request.requestId, error.code, error.message, error.retryable)
        } catch (error: CliProtocolException) {
            CliJsonLines.error(request.requestId, error.code, error.message, error.retryable)
        } catch (error: TimeoutException) {
            CliJsonLines.error(request.requestId, CliErrorCodes.TIMEOUT, error.message ?: "request timed out", true)
        } catch (error: IllegalAccessException) {
            CliJsonLines.error(request.requestId, CliErrorCodes.NOT_AUTHORIZED,
                error.message ?: "request is not authorized")
        } catch (error: NoSuchElementException) {
            CliJsonLines.error(request.requestId, CliErrorCodes.VALUE_NOT_FOUND,
                error.message ?: "value was not found")
        } catch (error: IllegalArgumentException) {
            val code = stableErrorCode(error.message) ?: CliErrorCodes.INVALID_ARGUMENT
            CliJsonLines.error(request.requestId, code, error.message ?: "invalid argument")
        } catch (error: Throwable) {
            val code = stableErrorCode(error.message) ?: CliErrorCodes.INTERNAL_ERROR
            CliJsonLines.error(request.requestId, code, error.message ?: "internal error")
        }
        // Backend evaluation and waits may outlive the user's grant or project
        // trust. Check again at delivery, including results produced before a
        // concurrent revocation was observed by the backend.
        val authorizedResponse = try {
            if (response.ok) {
                (request.targetId ?: request.arguments.string("targetId"))?.let {
                    requireRead(request, it)
                    requireTrusted(it)
                }
            }
            response
        } catch (error: CliGatewayException) {
            CliJsonLines.error(request.requestId, error.code, error.message, error.retryable)
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        val finalResponse = when {
            closed.get() -> CliJsonLines.error(
                request.requestId,
                CliErrorCodes.SERVER_CLOSED,
                "CLI gateway is closed",
                retryable = true
            )
            execution?.cancelled?.get() == true -> CliJsonLines.error(
                request.requestId,
                CliErrorCodes.CANCEL_UNSUPPORTED,
                "the backend operation could not be forcefully interrupted"
            )
            request.deadlineMillis != null && elapsedMillis > request.deadlineMillis!! -> CliJsonLines.error(
                request.requestId,
                CliErrorCodes.TIMEOUT,
                "request exceeded its deadline",
                retryable = true
            )
            else -> authorizedResponse
        }
        val boundedResponse = limitResponse(finalResponse)
        runCatching { audit(currentClient(request), request.operation, request.targetId, boundedResponse.ok) }
        if (request.operation != CliOperations.WAIT && request.operation != CliOperations.CANCEL) {
            synchronized(responseCache) {
                // Discovery results may contain details from several grants;
                // re-read them instead of replaying a pre-revocation listing.
                if (!closed.get() && request.operation != CliOperations.TARGET_LIST &&
                    request.operation != CliOperations.INSTANCE_LIST) {
                    responseCache[requestKey(request)] = CachedResponse(fingerprint, System.currentTimeMillis(), boundedResponse)
                }
                execution?.let { executions.remove(requestKey(request), it) }
            }
        }
        return boundedResponse
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        waiters.values.toList().forEach {
            it.serverClosed.set(true)
            it.cancelled.set(true)
            it.journal.signalWaiters()
        }
        waiters.clear()
        executions.values.toList().forEach { it.cancelled.set(true) }
        executions.clear()
        rateWindows.clear()
        synchronized(responseCache) { responseCache.clear() }
    }

    /** Emits zero or more `event` records followed by one terminal `done`. */
    fun handleStreaming(request: CliRequest, emit: (CliResponse) -> Unit): Boolean =
        handleStreaming(request, null, emit)

    internal fun handleStreaming(
        request: CliRequest,
        externalCancellation: (() -> Boolean)?,
        emit: (CliResponse) -> Unit
    ): Boolean {
        if (request.operation != CliOperations.WAIT) return false
        var registration: WaitRegistration? = null
        try {
            validateRequestShape(request)
            validateDeadline(request)
            if (closed.get()) {
                emit(CliJsonLines.error(request.requestId, "SERVER_CLOSED", "CLI gateway is closed", true).copy(done = true))
                return true
            }
            val targetId = requiredTarget(request)
            requireRead(request, targetId)
            requireTrusted(targetId)
            val journal = targetRegistry?.journal(targetId)
                ?: throw CliGatewayException(CliErrorCodes.TARGET_NOT_READY, "event journal is unavailable", true)
        val created = WaitRegistration(targetId, currentClient(request), journal)
            if (waiters.putIfAbsent(requestKey(request), created) != null) {
                emit(CliJsonLines.error(request.requestId, "DUPLICATE_REQUEST_ID", "wait request is already active").copy(done = true))
                return true
            }
            registration = created
            val cursor = request.cursor ?: request.arguments.long("cursor") ?: 0L
            val timeout = boundedWaitTimeout(request.deadlineMillis ?: request.arguments.long("timeoutMillis") ?: 30_000L)
            val limit = boundedInt(request.arguments.int("limit") ?: 100, 1, 1_000, "limit")
            val page = journal.awaitAfter(cursor, timeout, limit) {
                created.cancelled.get() || externalCancellation?.invoke() == true
            }
            if (page.isFailure) {
                val error = page.exceptionOrNull()
                val response = when {
                    created.serverClosed.get() || closed.get() -> CliJsonLines.error(
                        request.requestId, "SERVER_CLOSED", "CLI gateway is closed", true
                    ).copy(done = true, cursor = cursor)
                    error?.message == CliErrorCodes.CANCELLED -> CliResponse(request.requestId, false,
                        error = CliError(CliErrorCodes.CANCELLED, "wait cancelled"), done = true, cursor = cursor)
                    error?.message == CliErrorCodes.EVENT_CURSOR_EXPIRED -> CliResponse(request.requestId, false,
                        error = CliError(CliErrorCodes.EVENT_CURSOR_EXPIRED, "event cursor expired"), done = true,
                        cursor = journal.oldestCursor())
                    else -> CliResponse(request.requestId, false,
                        error = CliError(CliErrorCodes.TIMEOUT, "wait timed out", retryable = true), done = true,
                        cursor = cursor)
                }
                emit(limitResponse(response))
                return true
            }
            val value = page.getOrThrow()
            value.events.forEach { event ->
                requireRead(request, targetId)
                requireTrusted(targetId)
                emit(limitResponse(CliResponse(request.requestId, true, data = gson.toJsonTree(event).asJsonObject,
                    event = event.type, done = false, cursor = event.cursor)))
            }
            emit(limitResponse(CliResponse(request.requestId, true, data = JsonObject().apply {
                addProperty("reason", "EVENTS_AVAILABLE")
                addProperty("nextCursor", value.nextCursor)
                addProperty("hasMore", value.hasMore)
            }, done = true, cursor = value.nextCursor)))
            return true
        } catch (error: CliGatewayException) {
            emit(limitResponse(CliJsonLines.error(request.requestId, error.code, error.message, error.retryable).copy(done = true)))
            return true
        } catch (error: CliProtocolException) {
            emit(limitResponse(CliJsonLines.error(request.requestId, error.code, error.message, error.retryable).copy(done = true)))
            return true
        } catch (error: IllegalAccessException) {
            emit(limitResponse(CliJsonLines.error(request.requestId, CliErrorCodes.NOT_AUTHORIZED,
                error.message ?: "request is not authorized").copy(done = true)))
            return true
        } catch (error: NoSuchElementException) {
            emit(limitResponse(CliJsonLines.error(request.requestId, CliErrorCodes.VALUE_NOT_FOUND,
                error.message ?: "value was not found").copy(done = true)))
            return true
        } catch (error: Throwable) {
            val code = stableErrorCode(error.message) ?: CliErrorCodes.INTERNAL_ERROR
            runCatching { emit(limitResponse(CliJsonLines.error(request.requestId, code, error.message ?: "wait failed").copy(done = true))) }
            return true
        } finally {
            waiters.remove(requestKey(request), registration)
        }
    }

    private fun instanceListJson(): JsonObject = JsonObject().apply {
        val candidates = (CliInstanceDirectory.descriptorFiles().mapNotNull(CliInstanceDirectory::read) + instanceList())
            .filter { it.ideaInstanceId.isNotBlank() && it.pid > 0 && it.endpoint.isNotBlank() }
            .distinctBy { it.ideaInstanceId }
            .sortedBy { it.ideaInstanceId }
        val instances = JsonArray()
        candidates.forEach { descriptor ->
            // Never expose the token file path through the protocol. The
            // endpoint itself is redacted for an untrusted project.
            instances.add(JsonObject().apply {
                addProperty("schemaVersion", descriptor.schemaVersion)
                addProperty("ideaInstanceId", descriptor.ideaInstanceId)
                addProperty("pid", descriptor.pid)
                addProperty("product", descriptor.product)
                addProperty("endpoint", if (trustedProject()) descriptor.endpoint else "<redacted>")
                addProperty("startedAt", descriptor.startedAt)
            })
        }
        add("instances", instances)
    }

    private fun targetList(request: CliRequest): JsonObject = JsonObject().apply {
        val targets = JsonArray()
        provider.targets().forEach { target ->
            val authorized = authorization == null || isAuthorized(target.targetId, currentClient(request))
            val trusted = trustedFor(target.targetId)
            val item = JsonObject().apply {
                addProperty("targetId", target.targetId)
                addProperty("authorized", authorized)
                addProperty("trusted", trusted)
                // Do not disclose project/VM state to a client that has not
                // received an explicit target grant.
                addProperty("projectName", if (authorized && trusted) {
                    CliRedactionPolicy.redactProjectName(target.projectName, true)
                } else "<redacted>")
                if (authorized && trusted) {
                    addProperty("state", target.state)
                    addProperty("agentReady", target.agentReady)
                    addProperty("vmCount", target.vms.size)
                    addProperty("vmReady", target.vmReady)
                } else {
                    addProperty("state", if (!authorized) "UNAUTHORIZED" else CliErrorCodes.PROJECT_UNTRUSTED)
                }
            }
            targets.add(item)
        }
        add("targets", targets)
    }

    private fun targetStatus(request: CliRequest): JsonObject {
        val target = findTarget(requiredTarget(request))
        requireRead(request, target.targetId)
        requireTrusted(target.targetId)
        return JsonObject().apply {
            addProperty("targetId", target.targetId)
            addProperty("projectName", CliRedactionPolicy.redactProjectName(target.projectName, true))
            addProperty("state", target.state)
            addProperty("agentReady", target.agentReady)
            addProperty("vmCount", target.vms.size)
            addProperty("vmReady", target.vmReady)
            if (targetRegistry?.adapter(target.targetId) != null) addProperty("adapter", "EMMY")
        }
    }

    private fun vmList(request: CliRequest): JsonObject {
        val target = findTarget(requiredTarget(request))
        requireRead(request, target.targetId)
        requireTrusted(target.targetId)
        val vms = adapter(target.targetId)?.listVms() ?: target.vms
        return JsonObject().apply {
            addProperty("targetId", target.targetId)
            add("vms", gson.toJsonTree(vms).asJsonArray)
        }
    }

    private fun stack(request: CliRequest): JsonObject {
        val adapter = adapterForRequest(request)
        val vmId = requiredVm(request)
        val pauseId = requiredPause(request)
        val frames = adapter.stack(vmId, pauseId).getOrThrowCode()
        return JsonObject().apply {
            addProperty("targetId", adapter.targetId)
            addProperty("vmId", vmId)
            addProperty("pauseId", pauseId)
            add("frames", gson.toJsonTree(frames).asJsonArray)
        }
    }

    private fun scopes(request: CliRequest): JsonObject {
        val adapter = adapterForRequest(request)
        val vmId = requiredVm(request)
        val pauseId = requiredPause(request)
        val frameId = requiredFrame(request)
        val scopes = adapter.scopes(vmId, pauseId, frameId).getOrThrowCode()
        return JsonObject().apply { add("scopes", gson.toJsonTree(scopes).asJsonArray) }
    }

    private fun variables(request: CliRequest): JsonObject {
        val adapter = adapterForRequest(request)
        val vmId = requiredVm(request)
        val pauseId = requiredPause(request)
        val frameId = requiredFrame(request)
        val args = request.arguments
        val maxDepth = boundedInt(args.int("maxDepth") ?: 3, 0, 3, "maxDepth")
        val maxNodes = boundedInt(args.int("maxNodes") ?: 100, 1, 100, "maxNodes")
        val maxBytes = boundedInt(args.int("maxBytes") ?: 64 * 1024, 1, 64 * 1024, "maxBytes")
        val reference = args.string("variablesReference")
        val page = if (reference != null) {
            adapter.variablesReference(vmId, pauseId, frameId, reference, maxDepth, maxNodes, maxBytes)
        } else {
            adapter.variables(vmId, pauseId, frameId, args.string("path"), maxDepth, maxNodes, maxBytes)
        }.getOrThrowCode()
        return JsonObject().apply {
            add("variables", gson.toJsonTree(page.variables).asJsonArray)
            addProperty("truncated", page.truncated)
            addProperty("returnedCount", page.returnedCount)
            page.nextCursor?.let { addProperty("nextCursor", it) }
        }
    }

    private fun leaseAcquire(request: CliRequest): CliResponse {
        val targetId = requiredTarget(request)
        val owner = currentClient(request)
        requireRead(request, targetId)
        requireTrusted(targetId)
        val ttl = boundedLong(request.arguments.long("ttlMillis") ?: 30_000L, 1L, 5 * 60_000L, "ttlMillis")
        val lease = leases.acquire(targetId, owner, ttl).getOrElse {
            throw CliGatewayException(CliErrorCodes.TARGET_BUSY, "another client owns the target lease", true)
        }
        return ok(request, gson.toJsonTree(lease).asJsonObject)
    }

    private fun leaseHeartbeat(request: CliRequest): CliResponse {
        val leaseId = request.leaseId ?: request.arguments.string("leaseId")
            ?: throw CliGatewayException(CliErrorCodes.LEASE_REQUIRED, "leaseId is required")
        val existing = leases.find(leaseId)
            ?: throw CliGatewayException(CliErrorCodes.LEASE_EXPIRED, "lease is expired", true)
        val requestedTarget = request.targetId ?: request.arguments.string("targetId")
        if (requestedTarget != null && requestedTarget != existing.targetId) {
            throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "lease belongs to another target")
        }
        requireRead(request, existing.targetId)
        requireTrusted(existing.targetId)
        val lease = leases.heartbeat(leaseId, currentClient(request),
            boundedLong(request.arguments.long("ttlMillis") ?: 30_000L, 1L, 5 * 60_000L, "ttlMillis"))
            ?: throw CliGatewayException(CliErrorCodes.LEASE_EXPIRED, "lease is expired", true)
        return ok(request, gson.toJsonTree(lease).asJsonObject)
    }

    private fun leaseRelease(request: CliRequest): CliResponse {
        val leaseId = request.leaseId ?: request.arguments.string("leaseId")
            ?: throw CliGatewayException(CliErrorCodes.LEASE_REQUIRED, "leaseId is required")
        val existing = leases.find(leaseId)
            ?: return ok(request, JsonObject().apply { addProperty("released", false) })
        val requestedTarget = request.targetId ?: request.arguments.string("targetId")
        if (requestedTarget != null && requestedTarget != existing.targetId) {
            throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "lease belongs to another target")
        }
        requireRead(request, existing.targetId)
        requireTrusted(existing.targetId)
        return ok(request, JsonObject().apply { addProperty("released", leases.release(leaseId, currentClient(request))) })
    }

    private fun breakpointList(request: CliRequest): JsonObject {
        val adapter = adapterForRequest(request)
        return JsonObject().apply { add("breakpoints", gson.toJsonTree(adapter.listBreakpoints()).asJsonArray) }
    }

    private fun breakpointMutation(request: CliRequest, add: Boolean): CliResponse {
        val adapter = adapterForRequest(request)
        requireLease(request, adapter.targetId)
        val owner = "CLI:${currentClient(request)}"
        val args = request.arguments
        val specs = if (add) parseBreakpoints(args, adapter.targetId, owner) else emptyList()
        val remove = if (!add) args.get("breakpointIds")?.asJsonArray?.map { it.asString } ?: emptyList() else emptyList()
        val result = adapter.mutateBreakpoints(CliBreakpointMutation(
            add = specs,
            remove = remove,
            expectedRevision = args.long("expectedRevision"),
            owner = owner
        )).getOrThrowCode()
        return ok(request, gson.toJsonTree(result).asJsonObject)
    }

    private fun control(request: CliRequest): CliResponse {
        val adapter = adapterForRequest(request)
        requireLease(request, adapter.targetId)
        val vmId = requiredVm(request)
        val action = when (request.operation) {
            CliOperations.PAUSE -> "Break"
            CliOperations.CONTINUE -> "Continue"
            CliOperations.STEP_IN -> "StepIn"
            CliOperations.STEP_OVER -> "StepOver"
            else -> "StepOut"
        }
        val result = adapter.control(CliControlRequest(action, vmId, optionalPause(request), request.arguments.string("threadId")))
            .getOrThrowCode()
        return ok(request, gson.toJsonTree(result).asJsonObject)
    }

    private fun evaluate(request: CliRequest): CliResponse {
        val adapter = adapterForRequest(request)
        requireLease(request, adapter.targetId)
        val args = request.arguments
        val expression = args.string("expression")
            ?: throw CliGatewayException(CliErrorCodes.EVALUATION_DENIED, "expression is required")
        val sourceIdentity = args.getAsJsonObject("sourceIdentity")?.let(::parseSourceIdentity)
        val result = adapter.evaluate(CliEvaluationRequest(requiredVm(request), requiredPause(request), requiredFrame(request),
            expression, args.string("policy") ?: "VALUE_PATH",
            boundedInt(args.int("maxDepth") ?: 3, 0, 3, "maxDepth"),
            boundedInt(args.int("maxNodes") ?: 100, 1, 100, "maxNodes"),
            boundedInt(args.int("maxBytes") ?: 64 * 1024, 1, 64 * 1024, "maxBytes"),
            sourceIdentity,
            args.string("threadId"))).getOrThrowCode()
        requireLease(request, adapter.targetId)
        return ok(request, gson.toJsonTree(result).asJsonObject)
    }

    private fun probeInstall(request: CliRequest): CliResponse {
        val adapter = adapterForRequest(request)
        requireLease(request, adapter.targetId)
        val args = request.arguments
        val source = parseSourceIdentity(args.getAsJsonObject("sourceIdentity"))
        val spec = CliProbeSpec(
            probeId = args.string("probeId") ?: "probe-${System.nanoTime().toString(16)}",
            owner = "CLI:${currentClient(request)}", targetId = adapter.targetId, vmId = requiredVm(request),
            sourceIdentity = source, line = args.int("line")
                ?: throw CliGatewayException("INVALID_ARGUMENT", "line is required"),
            condition = args.string("condition"),
            captures = args.get("captures")?.asJsonArray?.map { it.asString } ?: emptyList(),
            hitLimit = boundedInt(args.int("hitLimit") ?: 1, 1, 1_000, "hitLimit"),
            timeoutMillis = boundedLong(args.long("timeoutMillis") ?: 60_000L, 1L, 10 * 60_000L, "timeoutMillis"),
            autoContinue = args.bool("autoContinue")
        )
        val result = probes?.install(spec, adapter, leases) ?: adapter.installProbe(spec)
        return ok(request, gson.toJsonTree(result.getOrThrowCode()).asJsonObject)
    }

    private fun probeRemove(request: CliRequest): CliResponse {
        val adapter = adapterForRequest(request)
        requireLease(request, adapter.targetId)
        val probeId = request.arguments.string("probeId")
            ?: throw CliGatewayException("INVALID_ARGUMENT", "probeId is required")
        val result = probes?.remove(probeId, "CLI:${currentClient(request)}")
            ?: adapter.removeProbe(probeId, "CLI:${currentClient(request)}")
        return ok(request, JsonObject().apply { addProperty("removed", result.getOrThrowCode()) })
    }

    private fun probeStatus(request: CliRequest): CliResponse {
        val adapter = adapterForRequest(request)
        val probeId = request.arguments.string("probeId")
            ?: throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "probeId is required")
        val status = probes?.status(probeId)
            ?: throw CliGatewayException(CliErrorCodes.PROBE_NOT_FOUND, "probe does not exist")
        if (status.targetId != null && status.targetId != adapter.targetId) {
            throw CliGatewayException(CliErrorCodes.PROBE_NOT_FOUND, "probe does not belong to target")
        }
        if (status.owner != null && status.owner != "CLI:${currentClient(request)}") {
            throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "probe belongs to another client")
        }
        return ok(request, gson.toJsonTree(status).asJsonObject)
    }

    private fun probeList(request: CliRequest): CliResponse {
        val adapter = adapterForRequest(request)
        val owner = "CLI:${currentClient(request)}"
        val statuses = probes?.statuses(adapter.targetId, owner)
            ?: emptyList()
        return ok(request, JsonObject().apply {
            add("probes", gson.toJsonTree(statuses).asJsonArray)
        })
    }

    private fun waitOnce(request: CliRequest): CliResponse {
        val targetId = requiredTarget(request)
        requireRead(request, targetId)
        requireTrusted(targetId)
        val journal = targetRegistry?.journal(targetId)
            ?: throw CliGatewayException(CliErrorCodes.TARGET_NOT_READY, "event journal is unavailable", true)
        val page = journal.readAfter(request.cursor ?: request.arguments.long("cursor") ?: 0L,
            boundedInt(request.arguments.int("limit") ?: 100, 1, 1_000, "limit")).getOrThrowCode()
        return CliResponse(request.requestId, true, data = JsonObject().apply {
            add("events", gson.toJsonTree(page.events).asJsonArray)
            addProperty("nextCursor", page.nextCursor)
            addProperty("hasMore", page.hasMore)
        }, done = true, cursor = page.nextCursor)
    }

    private fun cancel(request: CliRequest): CliResponse {
        val waitRequestId = request.arguments.string("waitRequestId")
            ?: request.arguments.string("requestIdToCancel")
        val client = currentClient(request)
        val waitRegistration = waitRequestId?.let { waiters[requestKey(client, it)] }
        val execution = waitRequestId?.let { executions[requestKey(client, it)] }
        val targetId = request.targetId
            ?: request.arguments.string("targetId")
            ?: waitRegistration?.targetId
            ?: execution?.targetId
            ?: throw CliGatewayException(CliErrorCodes.TARGET_REQUIRED, "targetId is required")
        requireRead(request, targetId)
        requireTrusted(targetId)
        val journal = targetRegistry?.journal(targetId)
            ?: throw CliGatewayException(CliErrorCodes.TARGET_NOT_READY, "event journal is unavailable")
        if (waitRequestId != null) {
            if (waitRegistration != null && waitRegistration.targetId != targetId) {
                throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "wait request belongs to another target")
            }
            if (waitRegistration != null && waitRegistration.clientId != currentClient(request)) {
                throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "wait request belongs to another client")
            }
            if (execution != null && execution.targetId != null && execution.targetId != targetId) {
                throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "request belongs to another target")
            }
            if (execution != null && execution.clientId != currentClient(request)) {
                throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "request belongs to another client")
            }
            waitRegistration?.let {
                it.cancelled.set(true)
                it.journal.signalWaiters()
            }
            execution?.cancelled?.set(true)
            if (execution != null) {
                return CliJsonLines.error(request.requestId, CliErrorCodes.CANCEL_UNSUPPORTED,
                    "the backend operation cannot be forcefully interrupted")
            }
            return ok(request, JsonObject().apply { addProperty("cancelled", waitRegistration != null) })
        }
        waiters.values.filter { it.targetId == targetId && it.clientId == client }.forEach {
            it.cancelled.set(true)
        }
        journal.signalWaiters()
        executions.values
            .filter { it.targetId == targetId && it.clientId == currentClient(request) }
            .forEach { it.cancelled.set(true) }
        return ok(request, JsonObject().apply { addProperty("cancelled", true); addProperty("scope", "target") })
    }

    private fun parseBreakpoints(args: JsonObject, targetId: String, owner: String): List<CliBreakpointSpec> {
        val array = args.get("breakpoints")?.asJsonArray ?: JsonArray()
        return array.map { raw ->
            val item = raw.asJsonObject
            CliBreakpointSpec(
                breakpointId = item.string("breakpointId") ?: "bp-${System.nanoTime().toString(16)}",
                owner = owner,
                vmId = item.string("vmId") ?: throw CliGatewayException(CliErrorCodes.AMBIGUOUS_VM, "vmId is required"),
                sourceIdentity = parseSourceIdentity(item.getAsJsonObject("sourceIdentity")),
                line = item.int("line") ?: throw CliGatewayException("INVALID_ARGUMENT", "line is required"),
                condition = item.string("condition"), logMessage = item.string("logMessage"),
                hitCondition = item.string("hitCondition"), scope = item.string("scope") ?: "SESSION"
            )
        }
    }

    private fun parseSourceIdentity(json: JsonObject?): CliSourceIdentity {
        if (json == null) throw CliGatewayException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH, "sourceIdentity is required")
        val canonicalPath = json.string("canonicalPath")?.trim().orEmpty()
        if (canonicalPath.isBlank()) {
            throw CliGatewayException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH, "canonicalPath is required")
        }
        val uri = json.string("uri")?.trim().orEmpty()
        if (uri.isBlank()) {
            throw CliGatewayException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH, "uri is required")
        }
        val hash = json.string("sourceHash")?.trim()?.also {
            if (!it.matches(Regex("[0-9a-fA-F]{64}"))) {
                throw CliGatewayException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH,
                    "sourceHash must be a SHA-256 hex digest")
            }
        }
        val epoch = json.long("sourceEpoch") ?: json.long("loaderEpoch")
        if (epoch != null && epoch <= 0L) {
            throw CliGatewayException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH, "source epoch must be positive")
        }
        val verified = json.bool("verified")
        if (verified && hash == null && epoch == null) {
            throw CliGatewayException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH,
                "verified source identity requires sourceHash or sourceEpoch")
        }
        return CliSourceIdentity(uri, canonicalPath, hash, epoch, verified)
    }

    private fun adapterForRequest(request: CliRequest): DebugTargetAdapter {
        val targetId = requiredTarget(request)
        requireRead(request, targetId)
        requireTrusted(targetId)
        return adapter(targetId) ?: throw CliGatewayException(CliErrorCodes.TARGET_NOT_READY, "target adapter unavailable", true)
    }

    private fun adapter(targetId: String): DebugTargetAdapter? = targetRegistry?.adapter(targetId)

    private fun findTarget(targetId: String): CliTargetSummary = provider.targets().firstOrNull { it.targetId == targetId }
        ?: throw CliGatewayException(CliErrorCodes.TARGET_NOT_FOUND, "target does not exist")

    private fun requiredTarget(request: CliRequest): String = request.targetId?.takeIf { it.isNotBlank() }
        ?.also { top ->
            request.arguments.string("targetId")?.takeIf { it.isNotBlank() }?.let { argument ->
                if (argument != top) throw CliGatewayException("INVALID_ARGUMENT", "targetId appears twice with different values")
            }
        }
        ?: request.arguments.string("targetId")?.takeIf { it.isNotBlank() }
        ?: throw CliGatewayException(CliErrorCodes.TARGET_REQUIRED, "targetId is required")

    private fun requiredVm(request: CliRequest): String {
        val topLevel = request.vmId?.takeIf { it.isNotBlank() }
        val argument = request.arguments.string("vmId")?.takeIf { it.isNotBlank() }
        if (topLevel != null && argument != null && topLevel != argument) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "vmId appears twice with different values")
        }
        return topLevel ?: argument
            ?: throw CliGatewayException(CliErrorCodes.AMBIGUOUS_VM, "vmId is required for this operation")
    }

    private fun requiredPause(request: CliRequest): Long = optionalPause(request)
        ?: throw CliGatewayException(CliErrorCodes.PAUSE_ID_REQUIRED, "pauseId is required")

    private fun optionalPause(request: CliRequest): Long? = request.arguments.long("pauseId")?.also {
        if (it <= 0) throw CliGatewayException(CliErrorCodes.PAUSE_ID_REQUIRED, "pauseId must be positive")
    }

    private fun requiredFrame(request: CliRequest): String = request.arguments.string("frameId")?.takeIf { it.isNotBlank() }
        ?: throw CliGatewayException(CliErrorCodes.STALE_PAUSE_REFERENCE, "frameId is required")

    private fun requireRead(request: CliRequest, targetId: String) {
        val auth = authorization ?: return
        if (!auth.canRead(targetId, currentClient(request))) {
            throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "target access has not been granted")
        }
    }

    private fun trustedFor(targetId: String): Boolean =
        targetRegistry?.adapter(targetId)?.projectTrusted ?: trustedProject()

    private fun requireTrusted(targetId: String) {
        if (!trustedFor(targetId)) {
            throw CliGatewayException(CliErrorCodes.PROJECT_UNTRUSTED,
                "project is not trusted for external debugging")
        }
    }

    private fun requireLease(request: CliRequest, targetId: String) {
        requireTrusted(targetId)
        val auth = authorization
        if (auth != null && !auth.canRead(targetId, currentClient(request))) {
            throw CliGatewayException(CliErrorCodes.NOT_AUTHORIZED, "target access has not been granted")
        }
        val leaseId = request.leaseId ?: request.arguments.string("leaseId")
            ?: throw CliGatewayException(CliErrorCodes.LEASE_REQUIRED, "control lease is required")
        if (!leases.isOwner(targetId, leaseId, currentClient(request))) {
            throw CliGatewayException(CliErrorCodes.LEASE_EXPIRED, "control lease is missing or expired", true)
        }
    }

    private fun isAuthorized(targetId: String, clientId: String): Boolean = authorization?.canRead(targetId, clientId) ?: true
    private fun currentClient(request: CliRequest?): String = request?.clientId?.takeIf { it.isNotBlank() } ?: "local"

    private fun requestKey(request: CliRequest): String = requestKey(currentClient(request), request.requestId)

    private fun requestKey(clientId: String, requestId: String): String = "${clientId.length}:$clientId$requestId"

    private fun validateCachedAccess(request: CliRequest) {
        val targetId = request.targetId ?: request.arguments.string("targetId")
        if (targetId != null) {
            requireRead(request, targetId)
            requireTrusted(targetId)
            if (request.operation in setOf(
                    CliOperations.CONTINUE, CliOperations.PAUSE, CliOperations.STEP_IN,
                    CliOperations.STEP_OVER, CliOperations.STEP_OUT, CliOperations.EVALUATE,
                    CliOperations.PROBE_RUN, CliOperations.PROBE_REMOVE,
                    CliOperations.BREAKPOINT_ADD, CliOperations.BREAKPOINT_REMOVE,
                    CliOperations.LEASE_HEARTBEAT)) {
                requireLease(request, targetId)
            }
        } else if (!trustedProject()) {
            throw CliGatewayException(CliErrorCodes.PROJECT_UNTRUSTED, "project is not trusted for external debugging")
        }
    }
    private fun ok(request: CliRequest, data: JsonObject): CliResponse = CliResponse(request.requestId, true, data = data)

    private fun boundedWaitTimeout(value: Long): Long {
        if (value <= 0L || value > maxWaitMillis) {
            throw CliGatewayException("INVALID_ARGUMENT", "timeoutMillis must be between 1 and $maxWaitMillis")
        }
        return value
    }

    private fun validateDeadline(request: CliRequest) {
        val deadline = request.deadlineMillis ?: return
        if (deadline <= 0L) throw CliGatewayException("INVALID_ARGUMENT", "deadlineMillis must be positive")
        // deadlineMillis is a relative budget in the wire protocol. Keeping
        // the check here makes direct/in-memory dispatch obey the same bound
        // as the socket server.
        if (deadline > maxWaitMillis) {
            throw CliGatewayException("INVALID_ARGUMENT", "deadlineMillis exceeds the maximum request budget")
        }
    }

    private fun validateRequestShape(request: CliRequest) {
        if (request.requestId.isBlank() || request.requestId.length > 128) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "invalid requestId")
        }
        if (request.operation.isBlank() || request.operation.length > 128) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "invalid operation")
        }
        val targetId = request.targetId
        val vmId = request.vmId
        val clientId = request.clientId
        val leaseId = request.leaseId
        if (targetId != null && (targetId.isBlank() || targetId.length > 256)) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "invalid targetId")
        }
        if (vmId != null && (vmId.isBlank() || vmId.length > 256)) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "invalid vmId")
        }
        if (clientId != null && (clientId.isBlank() || clientId.length > 128)) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "invalid clientId")
        }
        if (leaseId != null && (leaseId.isBlank() || leaseId.length > 128)) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "invalid leaseId")
        }
    }

    private fun allowRate(request: CliRequest): Boolean {
        if (maxRequestsPerSecond <= 0 || request.operation == CliOperations.CANCEL) return true
        val now = System.currentTimeMillis()
        val key = "${currentClient(request)}|${request.targetId ?: request.arguments.string("targetId") ?: "*"}"
        val window = rateWindows.computeIfAbsent(key) { RateWindow(now, 0) }
        synchronized(window) {
            if (now - window.startedAtMillis >= 1_000L) {
                window.startedAtMillis = now
                window.count = 0
            }
            if (window.count >= maxRequestsPerSecond) return false
            window.count++
            return true
        }
    }

    private fun requestFingerprint(request: CliRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(request).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun <T> Result<T>.getOrThrowCode(): T = getOrElse { error ->
        if (error is CliGatewayException) throw error
        val message = error.message ?: "operation failed"
        val code = when (error) {
            is TimeoutException -> CliErrorCodes.TIMEOUT
            is IllegalAccessException -> CliErrorCodes.NOT_AUTHORIZED
            is NoSuchElementException -> CliErrorCodes.VALUE_NOT_FOUND
            is IllegalArgumentException -> stableErrorCode(message) ?: CliErrorCodes.INVALID_ARGUMENT
            else -> stableErrorCode(message) ?: CliErrorCodes.INTERNAL_ERROR
        }
        throw CliGatewayException(code, message, code == CliErrorCodes.TIMEOUT || code == CliErrorCodes.TARGET_BUSY)
    }

    private fun stableErrorCode(message: String?): String? {
        val candidate = message?.trim()?.let { Regex("^[A-Z][A-Z0-9_]+$").matchEntire(it)?.value }
            ?: return null
        return candidate
    }

    private fun boundedInt(value: Int, minimum: Int, maximum: Int, name: String): Int {
        if (value !in minimum..maximum) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT,
                "$name must be between $minimum and $maximum")
        }
        return value
    }

    private fun boundedLong(value: Long, minimum: Long, maximum: Long, name: String): Long {
        if (value !in minimum..maximum) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT,
                "$name must be between $minimum and $maximum")
        }
        return value
    }

    private fun limitResponse(response: CliResponse): CliResponse = try {
        CliJsonLines.encode(response)
        response
    } catch (error: CliProtocolException) {
        CliJsonLines.error(response.requestId, CliErrorCodes.RESPONSE_TOO_LARGE,
            "response exceeds the protocol size limit")
    } catch (_: Throwable) {
        CliJsonLines.error(response.requestId, CliErrorCodes.RESPONSE_TOO_LARGE,
            "response exceeds the protocol size limit")
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name must be a string")
        }
        it.asString
    }

    private fun JsonObject.int(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name must be an integer")
        }
        val text = it.asJsonPrimitive.asString
        if (!text.matches(Regex("-?[0-9]+"))) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name must be an integer")
        }
        text.toIntOrNull() ?: throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name is out of range")
    }

    private fun JsonObject.long(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name must be an integer")
        }
        val text = it.asJsonPrimitive.asString
        if (!text.matches(Regex("-?[0-9]+"))) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name must be an integer")
        }
        text.toLongOrNull() ?: throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name is out of range")
    }

    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) {
            throw CliGatewayException(CliErrorCodes.INVALID_ARGUMENT, "$name must be a boolean")
        }
        it.asBoolean
    } ?: false
}

class CliGatewayException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false
) : RuntimeException(message)
