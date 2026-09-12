package com.tang.intellij.lua.debugger.cli

object CliOperations {
    const val INSTANCE_LIST = "instance.list"
    const val TARGET_LIST = "target.list"
    const val TARGET_STATUS = "target.status"
    const val VM_LIST = "vm.list"
    const val WAIT = "wait"
    const val CANCEL = "cancel"
    const val STACK = "stack"
    const val SCOPES = "scopes"
    const val VARIABLES = "variables"
    const val LEASE_ACQUIRE = "lease.acquire"
    const val LEASE_HEARTBEAT = "lease.heartbeat"
    const val LEASE_RELEASE = "lease.release"
    const val BREAKPOINT_LIST = "breakpoint.list"
    const val BREAKPOINT_ADD = "breakpoint.add"
    const val BREAKPOINT_REMOVE = "breakpoint.remove"
    const val PAUSE = "debug.pause"
    const val CONTINUE = "debug.continue"
    const val STEP_IN = "debug.stepIn"
    const val STEP_OVER = "debug.stepOver"
    const val STEP_OUT = "debug.stepOut"
    const val EVALUATE = "debug.evaluate"
    const val PROBE_RUN = "probe.run"
    const val PROBE_REMOVE = "probe.remove"
    const val PROBE_STATUS = "probe.status"
    const val PROBE_LIST = "probe.list"
}

object CliErrorCodes {
    const val NOT_AUTHORIZED = "NOT_AUTHORIZED"
    const val TARGET_REQUIRED = "TARGET_REQUIRED"
    const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    const val TARGET_BUSY = "TARGET_BUSY"
    const val TARGET_NOT_READY = "TARGET_NOT_READY"
    const val VM_NOT_FOUND = "VM_NOT_FOUND"
    const val AMBIGUOUS_VM = "AMBIGUOUS_VM"
    const val PAUSE_ID_REQUIRED = "PAUSE_ID_REQUIRED"
    const val STALE_PAUSE_REFERENCE = "STALE_PAUSE_REFERENCE"
    const val EVENT_CURSOR_EXPIRED = "EVENT_CURSOR_EXPIRED"
    const val SOURCE_IDENTITY_MISMATCH = "SOURCE_IDENTITY_MISMATCH"
    const val LEASE_REQUIRED = "LEASE_REQUIRED"
    const val LEASE_EXPIRED = "LEASE_EXPIRED"
    const val CANCELLED = "CANCELLED"
    const val CANCEL_UNSUPPORTED = "CANCEL_UNSUPPORTED"
    const val TIMEOUT = "TIMEOUT"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE"
    const val SERVER_CLOSED = "SERVER_CLOSED"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val UNKNOWN_OPERATION = "UNKNOWN_OPERATION"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val INVALID_CONDITION = "INVALID_CONDITION"
    const val VALUE_NOT_FOUND = "VALUE_NOT_FOUND"
    const val CONTEXT_RESET = "CONTEXT_RESET"
    const val EVALUATION_DENIED = "EVALUATION_DENIED"
    const val EVALUATION_LIMIT_EXCEEDED = "EVALUATION_LIMIT_EXCEEDED"
    const val UNSUPPORTED_CAPABILITY = "UNSUPPORTED_CAPABILITY"
    const val PROJECT_UNTRUSTED = "PROJECT_UNTRUSTED"
    const val PROBE_EXISTS = "PROBE_EXISTS"
    const val PROBE_NOT_FOUND = "PROBE_NOT_FOUND"
    const val INVALID_PROBE = "INVALID_PROBE"
    const val PROBE_TIMEOUT = "PROBE_TIMEOUT"
    const val VM_NOT_READY = "VM_NOT_READY"
    const val BREAKPOINT_REVISION_CONFLICT = "BREAKPOINT_REVISION_CONFLICT"
    const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

data class CliSourceIdentity(
    val uri: String,
    val canonicalPath: String,
    val sourceHash: String? = null,
    val sourceEpoch: Long? = null,
    val verified: Boolean = false
)

data class CliFrame(
    val frameId: String,
    val level: Int,
    val file: String,
    val line: Int,
    val functionName: String,
    val sourceIdentity: CliSourceIdentity? = null
)

data class CliScope(
    val name: String,
    val variablesReference: String,
    val expensive: Boolean = false
)

data class CliVariableSnapshot(
    val name: String,
    val type: String,
    val display: String,
    val variablesReference: String? = null,
    val childCount: Int? = null,
    val truncated: Boolean = false,
    val sensitive: Boolean = false,
    /** Bounded eager children. More children can be requested through the reference. */
    val children: List<CliVariableSnapshot> = emptyList()
)

data class CliPauseReference(
    val vmId: String,
    val pauseId: Long,
    val threadId: String? = null,
    val scope: String = "THREAD",
    val consistency: String = "THREAD_ONLY",
    val connectionEpoch: Long? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null
)

data class CliBreakpointSpec(
    val breakpointId: String,
    val owner: String,
    val vmId: String,
    val sourceIdentity: CliSourceIdentity,
    val line: Int,
    val condition: String? = null,
    val logMessage: String? = null,
    val hitCondition: String? = null,
    val scope: String = "SESSION"
)

data class CliProbeSpec(
    val probeId: String,
    val owner: String,
    val targetId: String,
    val vmId: String,
    val sourceIdentity: CliSourceIdentity,
    val line: Int,
    val condition: String? = null,
    val captures: List<String> = emptyList(),
    val hitLimit: Int = 1,
    val timeoutMillis: Long = 60_000,
    val autoContinue: Boolean = false
)

data class CliCapturedValue(
    val expression: String,
    val success: Boolean,
    val type: String? = null,
    val display: String? = null,
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val children: List<CliVariableSnapshot> = emptyList()
)

data class CliPauseSnapshot(
    val reference: CliPauseReference,
    val frames: List<CliFrame>,
    val reasons: Set<String> = emptySet()
)

data class CliVariablesPage(
    val variables: List<CliVariableSnapshot>,
    val truncated: Boolean = false,
    val nextCursor: String? = null,
    val returnedCount: Int = variables.size
)

data class CliControlRequest(
    val action: String,
    val vmId: String,
    val pauseId: Long? = null,
    val threadId: String? = null
)

data class CliControlResult(
    val action: String,
    val accepted: Boolean,
    val pauseId: Long? = null,
    val message: String? = null
)

data class CliBreakpointMutation(
    val add: List<CliBreakpointSpec> = emptyList(),
    val remove: List<String> = emptyList(),
    val expectedRevision: Long? = null,
    /** The caller owner is required for destructive mutations. */
    val owner: String? = null
)

data class CliBreakpointResult(
    val revision: Long,
    val breakpoints: List<CliBreakpointSpec>,
    val acknowledged: Boolean = true
)

data class CliEvaluationRequest(
    val vmId: String,
    val pauseId: Long,
    val frameId: String,
    val expression: String,
    val policy: String = "VALUE_PATH",
    val maxDepth: Int = 3,
    val maxNodes: Int = 100,
    val maxBytes: Int = 64 * 1024,
    val sourceIdentity: CliSourceIdentity? = null,
    val threadId: String? = null
)

data class CliEventRecord(
    val cursor: Long,
    val type: String,
    val targetId: String,
    val vmId: String? = null,
    val pauseId: Long? = null,
    val payload: Map<String, Any?> = emptyMap()
)

data class CliEventPage(
    val events: List<CliEventRecord>,
    val nextCursor: Long,
    val oldestCursor: Long,
    val latestCursor: Long,
    val hasMore: Boolean,
    val expired: Boolean = false
)

/** DTO-only adapter boundary. Implementations must serialize access to backend/UI state. */
interface DebugTargetAdapter {
    val targetId: String
    /** Whether this target's project is trusted for external inspection/control. */
    val projectTrusted: Boolean get() = true
    fun describe(): CliTargetSummary
    fun listVms(): List<CliVmSummary>
    fun currentPause(vmId: String, pauseId: Long? = null): CliPauseSnapshot?
    fun stack(vmId: String, pauseId: Long): Result<List<CliFrame>>
    fun scopes(vmId: String, pauseId: Long, frameId: String): Result<List<CliScope>>
    fun variables(vmId: String, pauseId: Long, frameId: String, path: String? = null,
                 maxDepth: Int = 3, maxNodes: Int = 100, maxBytes: Int = 64 * 1024,
                 timeoutMillis: Long = 500): Result<CliVariablesPage>
    /** Expands a previously returned scope/value reference without changing
     * the wire shape used by older adapters. */
    fun variablesReference(vmId: String, pauseId: Long, frameId: String, reference: String,
                           maxDepth: Int = 3, maxNodes: Int = 100, maxBytes: Int = 64 * 1024,
                           timeoutMillis: Long = 500): Result<CliVariablesPage> =
        variables(vmId, pauseId, frameId, reference, maxDepth, maxNodes, maxBytes, timeoutMillis)
    fun evaluate(request: CliEvaluationRequest): Result<CliCapturedValue>
    fun control(request: CliControlRequest): Result<CliControlResult>
    fun mutateBreakpoints(request: CliBreakpointMutation): Result<CliBreakpointResult>
    fun listBreakpoints(): List<CliBreakpointSpec>
    fun installProbe(spec: CliProbeSpec): Result<CliProbeSpec>
    fun removeProbe(probeId: String, owner: String): Result<Boolean>
}

fun interface DebugTargetRegistryView {
    fun adapters(): List<DebugTargetAdapter>
}
