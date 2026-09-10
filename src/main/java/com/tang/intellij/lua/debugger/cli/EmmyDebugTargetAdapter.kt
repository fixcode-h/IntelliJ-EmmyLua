package com.tang.intellij.lua.debugger.cli

import com.tang.intellij.lua.debugger.emmy.BreakPoint
import com.tang.intellij.lua.debugger.emmy.PauseSnapshot
import com.tang.intellij.lua.debugger.emmy.SourceIdentity
import com.tang.intellij.lua.debugger.emmy.Stack
import com.tang.intellij.lua.debugger.emmy.VariableValue
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Backend-neutral view implemented by [EmmyDebugProcessBase]. */
interface EmmyDebugBackend {
    val debugTargetId: String
    val projectTrusted: Boolean get() = true
    fun debugTargetSummary(): CliTargetSummary
    fun debugVmList(): List<CliVmSummary>
    fun debugPause(vmId: String, pauseId: Long?): PauseSnapshot?
    fun debugControl(request: CliControlRequest): Result<CliControlResult>
    fun debugEvaluate(request: CliEvaluationRequest): Result<CliCapturedValue>
    fun debugBreakpoints(): List<CliBreakpointSpec>
    fun debugMutateBreakpoints(request: CliBreakpointMutation): Result<CliBreakpointResult>
    fun debugInstallProbe(spec: CliProbeSpec): Result<CliProbeSpec>
    fun debugRemoveProbe(probeId: String, owner: String): Result<Boolean>
}

/** Adapter translating Emmy's existing pause snapshots into CLI DTOs. */
class EmmyDebugTargetAdapter(private val backend: EmmyDebugBackend) : DebugTargetAdapter {
    private data class ReferenceEntry(
        val vmId: String,
        val pauseId: Long,
        val frameId: String,
        val values: List<VariableValue>
    )

    private val references = ConcurrentHashMap<String, ReferenceEntry>()
    private val referenceSequence = AtomicLong()

    override val targetId: String get() = backend.debugTargetId
    override val projectTrusted: Boolean get() = backend.projectTrusted
    override fun describe(): CliTargetSummary = backend.debugTargetSummary()
    override fun listVms(): List<CliVmSummary> = backend.debugVmList()

    override fun currentPause(vmId: String, pauseId: Long?): CliPauseSnapshot? {
        val snapshot = backend.debugPause(vmId, pauseId) ?: return null
        return snapshot.toCliPause()
    }

    override fun stack(vmId: String, pauseId: Long): Result<List<CliFrame>> {
        val snapshot = backend.debugPause(vmId, pauseId) ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        return Result.success(snapshot.stacks.mapIndexed { index, stack -> stack.toCliFrame(snapshot, pauseId, index) })
    }

    override fun scopes(vmId: String, pauseId: Long, frameId: String): Result<List<CliScope>> {
        val snapshot = backend.debugPause(vmId, pauseId) ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val frame = findFrame(snapshot, frameId) ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        fun scope(name: String, values: List<VariableValue>): CliScope? =
            if (values.isEmpty()) null else CliScope(name, rememberReference(vmId, pauseId, frameId, values))
        return Result.success(listOf(
            scope("locals", frame.localVariables),
            scope("upvalues", frame.upvalueVariables),
            scope("globals", frame.globalVariables.orEmpty())
        ).filterNotNull())
    }

    override fun variables(vmId: String, pauseId: Long, frameId: String, path: String?,
                           maxDepth: Int, maxNodes: Int, maxBytes: Int): Result<CliVariablesPage> {
        val snapshot = backend.debugPause(vmId, pauseId) ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val frame = findFrame(snapshot, frameId) ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        if (maxDepth !in 0..32 || maxNodes !in 1..100_000 || maxBytes !in 1..16 * 1024 * 1024) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_LIMIT_EXCEEDED))
        }
        val roots = frame.localVariables.orEmpty() + frame.upvalueVariables.orEmpty() + frame.globalVariables.orEmpty()
        val list = when {
            path.isNullOrBlank() -> roots
            path == "locals" -> frame.localVariables
            path == "upvalues" -> frame.upvalueVariables
            path == "globals" -> frame.globalVariables.orEmpty()
            else -> listOf(resolvePath(roots, path).getOrElse { return Result.failure(it) })
        }
        val budget = Budget(maxNodes, maxBytes)
        val seen = IdentityHashMap<VariableValue, Boolean>()
        val values = list.mapNotNull { value ->
            toSnapshot(value, maxDepth, budget, seen, vmId, pauseId, frameId)
        }
        return Result.success(CliVariablesPage(values, budget.truncated, returnedCount = values.size))
    }

    override fun variablesReference(vmId: String, pauseId: Long, frameId: String, reference: String,
                                    maxDepth: Int, maxNodes: Int, maxBytes: Int): Result<CliVariablesPage> {
        if (maxDepth !in 0..32 || maxNodes !in 1..100_000 || maxBytes !in 1..16 * 1024 * 1024) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_LIMIT_EXCEEDED))
        }
        val entry = references[reference]
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        if (entry.vmId != vmId || entry.pauseId != pauseId || entry.frameId != frameId) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        val snapshot = backend.debugPause(vmId, pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        if (findFrame(snapshot, frameId) == null) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        val budget = Budget(maxNodes, maxBytes)
        val seen = IdentityHashMap<VariableValue, Boolean>()
        val values = entry.values.mapNotNull { value ->
            toSnapshot(value, maxDepth, budget, seen, vmId, pauseId, frameId)
        }
        return Result.success(CliVariablesPage(values, budget.truncated, returnedCount = values.size))
    }

    override fun evaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
        if (request.policy != "VALUE_PATH") {
            return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_DENIED))
        }
        if (request.maxDepth !in 0..3 || request.maxNodes !in 1..100 ||
            request.maxBytes !in 1..64 * 1024) {
            return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_LIMIT_EXCEEDED))
        }
        val snapshot = backend.debugPause(request.vmId, request.pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        if (findFrame(snapshot, request.frameId) == null ||
            (request.threadId != null && request.threadId != snapshot.threadId)) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        return backend.debugEvaluate(request)
    }

    override fun control(request: CliControlRequest): Result<CliControlResult> = backend.debugControl(request)
    override fun mutateBreakpoints(request: CliBreakpointMutation): Result<CliBreakpointResult> = backend.debugMutateBreakpoints(request)
    override fun listBreakpoints(): List<CliBreakpointSpec> = backend.debugBreakpoints()
    override fun installProbe(spec: CliProbeSpec): Result<CliProbeSpec> = backend.debugInstallProbe(spec)
    override fun removeProbe(probeId: String, owner: String): Result<Boolean> = backend.debugRemoveProbe(probeId, owner)

    private fun findFrame(snapshot: PauseSnapshot, frameId: String): Stack? = snapshot.stacks
        .mapIndexed { index, stack -> index to stack }
        .firstOrNull { (index, stack) ->
            (stack.frameId.isNotBlank() && frameId == stack.frameId) ||
                frameId == "frame-${snapshot.pauseId}-${stack.level}" ||
                frameId == "frame-${snapshot.pauseId}-$index"
        }?.second

    private fun Stack.toCliFrame(snapshot: PauseSnapshot, pauseId: Long, index: Int): CliFrame {
        val wire = sourceIdentity
        val source = if (wire != null) SourceIdentity(wire.uri, wire.canonicalPath, wire.sourceHash,
            sourceEpoch = wire.sourceEpoch ?: snapshot.sourceEpoch, verified = wire.verified)
        else SourceIdentity(file, SourceIdentity.normalizePath(file), sourceEpoch = snapshot.sourceEpoch,
            verified = snapshot.sourceEpoch != null)
        return CliFrame(
            frameId = this.frameId.takeIf { it.isNotBlank() }
                ?: "frame-$pauseId-${this.level.takeIf { it >= 0 } ?: index}",
            level = this.level,
            file = this.file,
            line = this.line,
            functionName = this.functionName,
            sourceIdentity = CliSourceIdentity(
                source.uri, source.canonicalPath, source.sourceHash,
                source.effectiveEpoch, source.verified
            )
        )
    }

    private fun PauseSnapshot.toCliPause(): CliPauseSnapshot = CliPauseSnapshot(
        CliPauseReference(vmId, pauseId, threadId, scope, consistency,
            connectionEpoch, contextGeneration, sourceEpoch),
        stacks.mapIndexed { index, stack -> stack.toCliFrame(this, pauseId, index) },
        reasons
    )

    private class Budget(nodes: Int, bytes: Int) {
        private var remainingNodes = nodes.coerceAtLeast(0)
        private var remainingBytes = bytes.coerceAtLeast(0)
        var truncated = false
        fun take(vararg values: String): Boolean {
            val size = values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }.toInt()
            if (remainingNodes <= 0 || remainingBytes < size) {
                truncated = true
                return false
            }
            remainingNodes--
            remainingBytes -= size
            return true
        }
    }

    private fun toSnapshot(
        value: VariableValue,
        depth: Int,
        budget: Budget,
        seen: IdentityHashMap<VariableValue, Boolean>,
        vmId: String,
        pauseId: Long,
        frameId: String
    ): CliVariableSnapshot? {
        val display = value.value
        val type = value.valueTypeName.ifBlank { value.valueTypeValue.name }
        if (!budget.take(value.nameValue, type, display)) return null
        val children = value.children.orEmpty()
        val reference = if (children.isNotEmpty()) rememberReference(vmId, pauseId, frameId, children) else null
        var truncated = false
        val eagerChildren = mutableListOf<CliVariableSnapshot>()
        val alreadySeen = seen.put(value, true) != null
        if (children.isNotEmpty()) {
            if (depth <= 0 || alreadySeen) {
                truncated = true
            } else {
                for (child in children) {
                    val childSnapshot = toSnapshot(child, depth - 1, budget, seen, vmId, pauseId, frameId)
                    if (childSnapshot == null) {
                        truncated = true
                        break
                    }
                    eagerChildren += childSnapshot
                }
            }
        }
        return CliVariableSnapshot(
            name = value.nameValue, type = type, display = display,
            variablesReference = reference,
            childCount = children.size,
            truncated = truncated,
            children = eagerChildren
        )
    }

    private fun rememberReference(vmId: String, pauseId: Long, frameId: String,
                                  values: List<VariableValue>): String {
        val id = "emmy-ref-${referenceSequence.incrementAndGet()}"
        references[id] = ReferenceEntry(vmId, pauseId, frameId, values.toList())
        while (references.size > 1024) {
            references.keys.firstOrNull()?.let(references::remove) ?: break
        }
        return id
    }

    private fun resolvePath(roots: List<VariableValue>, path: String): Result<VariableValue> {
        val evaluator = RestrictedValuePathEvaluator()
        val segments = evaluator.parseSegments(path).getOrElse { return Result.failure(it) }
        var current = roots.firstOrNull { it.name == segments.firstOrNull() || it.nameValue == segments.firstOrNull() }
        for (segment in segments.drop(1)) {
            current = current?.children?.firstOrNull { it.name == segment || it.nameValue == segment }
        }
        return current?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException(CliErrorCodes.VALUE_NOT_FOUND))
    }
}

/** Small in-memory adapter used by protocol/CLI tests and lifecycle harnesses. */
class InMemoryDebugTargetAdapter(
    override val targetId: String,
    private var summary: CliTargetSummary,
    private val pauses: MutableMap<String, PauseSnapshot> = linkedMapOf()
) : DebugTargetAdapter {
    private val revision = AtomicLong()
    private val breakpoints = linkedMapOf<String, CliBreakpointSpec>()
    private val probes = linkedMapOf<String, CliProbeSpec>()
    private data class ReferenceEntry(
        val vmId: String,
        val pauseId: Long,
        val frameId: String,
        val values: List<VariableValue>
    )

    private val references = linkedMapOf<String, ReferenceEntry>()
    private var referenceSequence = 0L
    private var lastControl: CliControlRequest? = null

    @Synchronized
    override fun describe(): CliTargetSummary = summary.copy(vms = listVms())

    @Synchronized
    override fun listVms(): List<CliVmSummary> = summary.vms

    @Synchronized
    fun setSummary(value: CliTargetSummary) { summary = value }

    @Synchronized
    fun putPause(snapshot: PauseSnapshot) {
        pauses["${snapshot.vmId}#${snapshot.pauseId}"] = snapshot
        references.entries.removeIf { it.value.vmId == snapshot.vmId && it.value.pauseId == snapshot.pauseId }
    }

    @Synchronized
    fun removePause(vmId: String, pauseId: Long) {
        pauses.remove("$vmId#$pauseId")
        references.entries.removeIf { it.value.vmId == vmId && it.value.pauseId == pauseId }
    }

    @Synchronized
    fun lastControl(): CliControlRequest? = lastControl

    @Synchronized
    override fun currentPause(vmId: String, pauseId: Long?): CliPauseSnapshot? {
        val snapshot = pauses.values.firstOrNull { it.vmId == vmId && (pauseId == null || it.pauseId == pauseId) }
            ?: return null
        return CliPauseSnapshot(
            CliPauseReference(vmId, snapshot.pauseId, snapshot.threadId, snapshot.scope, snapshot.consistency,
                snapshot.connectionEpoch, snapshot.contextGeneration, snapshot.sourceEpoch),
            snapshot.stacks.mapIndexed { index, stack -> stack.toCliFrame(snapshot.pauseId, index) },
            snapshot.reasons
        )
    }

    @Synchronized
    override fun stack(vmId: String, pauseId: Long): Result<List<CliFrame>> = currentPause(vmId, pauseId)?.let {
        Result.success(it.frames)
    } ?: Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))

    @Synchronized
    override fun scopes(vmId: String, pauseId: Long, frameId: String): Result<List<CliScope>> {
        val snapshot = pause(vmId, pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val frame = findFrame(snapshot, pauseId, frameId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val result = mutableListOf<CliScope>()
        fun addScope(name: String, values: List<VariableValue>) {
            if (values.isEmpty()) return
            val reference = rememberReference(vmId, pauseId, frameId, values)
            result += CliScope(name, reference, expensive = name == "globals")
        }
        addScope("locals", frame.localVariables)
        addScope("upvalues", frame.upvalueVariables)
        addScope("globals", frame.globalVariables)
        return Result.success(result)
    }

    @Synchronized
    override fun variables(vmId: String, pauseId: Long, frameId: String, path: String?, maxDepth: Int,
                           maxNodes: Int, maxBytes: Int): Result<CliVariablesPage> {
        val snapshot = pause(vmId, pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val frame = findFrame(snapshot, pauseId, frameId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val validation = validateVariableLimits(maxDepth, maxNodes, maxBytes)
        if (validation != null) return Result.failure(IllegalArgumentException(validation))
        val roots = frame.localVariables + frame.upvalueVariables + frame.globalVariables
        val selected = if (path.isNullOrBlank()) roots else listOf(resolvePath(roots, path).getOrElse { return Result.failure(it) })
        return renderVariables(selected, vmId, pauseId, frameId, maxDepth, maxNodes, maxBytes)
    }

    @Synchronized
    override fun variablesReference(vmId: String, pauseId: Long, frameId: String, reference: String,
                                    maxDepth: Int, maxNodes: Int, maxBytes: Int): Result<CliVariablesPage> {
        val validation = validateVariableLimits(maxDepth, maxNodes, maxBytes)
        if (validation != null) return Result.failure(IllegalArgumentException(validation))
        val entry = references[reference]
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        if (entry.vmId != vmId || entry.pauseId != pauseId || entry.frameId != frameId || pause(vmId, pauseId) == null) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        return renderVariables(entry.values, vmId, pauseId, frameId, maxDepth, maxNodes, maxBytes)
    }

    @Synchronized
    override fun evaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
        if (request.policy != "VALUE_PATH") {
            return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_DENIED))
        }
        val snapshot = pause(request.vmId, request.pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val frame = findFrame(snapshot, request.pauseId, request.frameId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val source = request.sourceIdentity
        if (source != null && frame.file.isNotBlank()) {
            val actual = SourceIdentity.fromPath(frame.file)
            if (!source.verified || SourceIdentity.normalizePath(source.canonicalPath) !=
                SourceIdentity.normalizePath(actual.canonicalPath) ||
                (source.sourceHash != null && source.sourceHash != actual.sourceHash)) {
                return Result.failure(IllegalStateException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH))
            }
        }
        val value = resolvePath(frame.localVariables + frame.upvalueVariables + frame.globalVariables, request.expression)
            .getOrElse { return Result.failure(it) }
        val page = renderVariables(listOf(value), request.vmId, request.pauseId, request.frameId,
            request.maxDepth, request.maxNodes, request.maxBytes)
        if (page.isFailure) return Result.failure(page.exceptionOrNull() ?: IllegalStateException(CliErrorCodes.EVALUATION_DENIED))
        val result = page.getOrThrow().variables.firstOrNull()
            ?: return Result.failure(IllegalStateException(CliErrorCodes.VALUE_NOT_FOUND))
        return Result.success(CliCapturedValue(request.expression, true, result.type, result.display, result.truncated))
    }

    @Synchronized
    override fun control(request: CliControlRequest): Result<CliControlResult> {
        if (pause(request.vmId, request.pauseId ?: -1L) == null && request.action != "Break") {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        lastControl = request
        return Result.success(CliControlResult(request.action, true, request.pauseId))
    }

    @Synchronized
    override fun mutateBreakpoints(request: CliBreakpointMutation): Result<CliBreakpointResult> {
        if (request.expectedRevision != null && request.expectedRevision != revision.get()) {
            return Result.failure(IllegalStateException(CliErrorCodes.BREAKPOINT_REVISION_CONFLICT))
        }
        val owner = request.owner
        val before = LinkedHashMap(breakpoints)
        try {
            request.remove.forEach { id ->
                val existing = breakpoints[id] ?: return@forEach
                if (owner != null && existing.owner != owner) throw IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED)
                breakpoints.remove(id)
            }
            request.add.forEach { spec ->
                if (owner != null && spec.owner != owner) throw IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED)
                if (spec.vmId != "" && summary.vms.none { it.vmId == spec.vmId }) {
                    throw IllegalStateException(CliErrorCodes.VM_NOT_FOUND)
                }
                breakpoints[spec.breakpointId] = spec
            }
        } catch (error: Throwable) {
            breakpoints.clear()
            breakpoints.putAll(before)
            return Result.failure(error)
        }
        return Result.success(CliBreakpointResult(revision.incrementAndGet(), breakpoints.values.toList()))
    }

    @Synchronized
    override fun listBreakpoints(): List<CliBreakpointSpec> = breakpoints.values.toList()

    @Synchronized
    override fun installProbe(spec: CliProbeSpec): Result<CliProbeSpec> {
        if (probes.containsKey(spec.probeId)) return Result.failure(IllegalArgumentException(CliErrorCodes.PROBE_EXISTS))
        if (summary.vms.none { it.vmId == spec.vmId }) return Result.failure(IllegalStateException(CliErrorCodes.VM_NOT_FOUND))
        probes[spec.probeId] = spec
        return Result.success(spec)
    }

    @Synchronized
    override fun removeProbe(probeId: String, owner: String): Result<Boolean> {
        val probe = probes[probeId] ?: return Result.success(false)
        if (probe.owner != owner) return Result.failure(IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED))
        probes.remove(probeId)
        return Result.success(true)
    }

    private fun pause(vmId: String, pauseId: Long): PauseSnapshot? = pauses["$vmId#$pauseId"]

    private fun Stack.toCliFrame(pauseId: Long, index: Int): CliFrame = CliFrame(
        frameId = frameId.takeIf { it.isNotBlank() } ?: "frame-$pauseId-${level.takeIf { it >= 0 } ?: index}",
        level = level,
        file = file,
        line = line,
        functionName = functionName,
        sourceIdentity = if (file.isBlank()) null else SourceIdentity.fromPath(file).let {
            CliSourceIdentity(it.uri, it.canonicalPath, it.sourceHash, it.effectiveEpoch, it.verified)
        }
    )

    private fun findFrame(snapshot: PauseSnapshot, pauseId: Long, frameId: String): Stack? =
        snapshot.stacks.mapIndexed { index, stack -> stack to stack.toCliFrame(pauseId, index).frameId }
            .firstOrNull { (stack, id) -> id == frameId }?.first

    private fun validateVariableLimits(maxDepth: Int, maxNodes: Int, maxBytes: Int): String? = when {
        maxDepth !in 0..32 -> CliErrorCodes.EVALUATION_LIMIT_EXCEEDED
        maxNodes !in 1..100_000 -> CliErrorCodes.EVALUATION_LIMIT_EXCEEDED
        maxBytes !in 1..16 * 1024 * 1024 -> CliErrorCodes.EVALUATION_LIMIT_EXCEEDED
        else -> null
    }

    private fun renderVariables(values: List<VariableValue>, vmId: String, pauseId: Long, frameId: String,
                                maxDepth: Int, maxNodes: Int, maxBytes: Int): Result<CliVariablesPage> {
        val budget = Budget(maxNodes, maxBytes)
        val seen = IdentityHashMap<VariableValue, Boolean>()
        val rendered = values.mapNotNull { value ->
            toSnapshot(value, maxDepth, budget, seen, value.nameValue, vmId, pauseId, frameId)
        }
        val truncated = budget.truncated || rendered.size < values.size
        return Result.success(CliVariablesPage(rendered, truncated, returnedCount = rendered.size))
    }

    private class Budget(nodes: Int, bytes: Int) {
        var nodesLeft = nodes
        var bytesLeft = bytes
        var truncated = false
        fun take(vararg values: String): Boolean {
            val size = values.sumOf { it.toByteArray(Charsets.UTF_8).size }
            if (nodesLeft <= 0 || bytesLeft < size) {
                truncated = true
                return false
            }
            nodesLeft--
            bytesLeft -= size
            return true
        }
    }

    private fun toSnapshot(value: VariableValue, depth: Int, budget: Budget,
                           seen: IdentityHashMap<VariableValue, Boolean>, path: String,
                           vmId: String, pauseId: Long, frameId: String): CliVariableSnapshot? {
        val type = value.valueTypeName.ifBlank { value.valueTypeValue.name }
        if (!budget.take(value.nameValue, type, value.value)) return null
        val children = value.children.orEmpty()
        val reference = if (children.isEmpty()) null else rememberReference(vmId, pauseId, frameId, children)
        var childTruncated = false
        val eager = mutableListOf<CliVariableSnapshot>()
        val cycle = seen.put(value, true) != null
        if (children.isNotEmpty()) {
            if (depth <= 0 || cycle) {
                childTruncated = true
            } else {
                children.forEach { child ->
                    val childPath = if (path.isBlank()) child.nameValue else "$path.${child.nameValue}"
                    val rendered = toSnapshot(child, depth - 1, budget, seen, childPath, vmId, pauseId, frameId)
                    if (rendered == null) childTruncated = true else eager += rendered
                }
            }
        }
        return CliVariableSnapshot(value.nameValue, type, value.value, reference, children.size,
            childTruncated || value.truncated, sensitive = false, children = eager)
    }

    private fun rememberReference(vmId: String, pauseId: Long, frameId: String,
                                  values: List<VariableValue>): String {
        val id = "mem-ref-${++referenceSequence}"
        references[id] = ReferenceEntry(vmId, pauseId, frameId, values.toList())
        while (references.size > 1024) references.remove(references.keys.first())
        return id
    }

    private fun resolvePath(roots: List<VariableValue>, path: String): Result<VariableValue> {
        val segments = RestrictedValuePathEvaluator().parseSegments(path).getOrElse { return Result.failure(it) }
        var current = roots.firstOrNull { matchesName(it, segments.firstOrNull()) }
        segments.drop(1).forEach { segment ->
            current = current?.children.orEmpty().firstOrNull { matchesName(it, segment) }
        }
        return current?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException(CliErrorCodes.VALUE_NOT_FOUND))
    }

    private fun matchesName(value: VariableValue, segment: String?): Boolean {
        if (segment == null) return false
        return value.name == segment || value.nameValue == segment || value.name == "[$segment]" ||
            value.nameValue == "[$segment]"
    }
}
