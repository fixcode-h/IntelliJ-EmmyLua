package com.tang.intellij.lua.debugger.emmy

import com.tang.intellij.lua.debugger.emmy.EmmyV2Envelope
import com.tang.intellij.lua.debugger.emmy.VmLifecycleDto
import com.tang.intellij.lua.debugger.emmy.VmSnapshotDto

/**
 * Backend-neutral VM state. Calls are serialized by the Emmy lifecycle
 * executor in production; synchronized methods also keep CLI/test readers safe.
 */
class VmRegistry {
    private companion object {
        /** Id prefix of VMs registered from the legacy AttachedNotify path. */
        const val LEGACY_VM_ID_PREFIX = "legacy-"
    }

    private val records = linkedMapOf<String, VmRecordModel>()
    /**
     * A VM id is opaque but can be reused by a host after a Lua state is
     * destroyed.  Remember the greatest retired generation so a late event
     * from the old state cannot recreate an active record.
     */
    private val retiredGenerations = linkedMapOf<String, Long>()
    private var sessionId: String? = null
    private var epoch: Long? = null
    private var eventSeq: Long = 0
    private var hasSequence = false
    private var connected = false
    private var awaitingSnapshot = true

    /** Starts a transport connection. Incremental events remain invalid until its snapshot arrives. */
    @Synchronized
    fun beginConnection() {
        connected = true
        awaitingSnapshot = true
        records.replaceAll { _, value -> value.copy(state = "LOST", activePauseId = null) }
    }

    /** Binds the handshake identity. A newer session/epoch starts a fresh snapshot fence. */
    @Synchronized
    fun acceptConnection(agentSessionId: String?, connectionEpoch: Long?): Boolean {
        if ((agentSessionId == null) != (connectionEpoch == null)) return false
        if (agentSessionId.isNullOrBlank() && connectionEpoch == null) return true
        if (agentSessionId != null && sessionId != null && agentSessionId != sessionId) {
            sessionId = agentSessionId
            epoch = connectionEpoch
            resetForNewConnection()
            return true
        }
        if (connectionEpoch != null && epoch != null) {
            if (connectionEpoch < epoch!!) return false
            if (connectionEpoch > epoch!!) {
                epoch = connectionEpoch
                resetForNewConnection()
                if (agentSessionId != null) sessionId = agentSessionId
                return true
            }
        }
        if (agentSessionId != null) sessionId = agentSessionId
        if (connectionEpoch != null) epoch = connectionEpoch
        connected = true
        return true
    }

    /**
     * Re-arms the snapshot fence once the v2 handshake identity is known.
     *
     * A legacy `AttachedNotify` may arrive before `InitRsp`. That path registers
     * a `legacy-*` record, marks the registry connected and clears the snapshot
     * fence, so the authoritative `vm.snapshot` is never requested and every
     * later `debug.paused` is discarded as an unknown VM. Dropping pre-handshake
     * records and re-arming the fence makes message ordering irrelevant.
     */
    @Synchronized
    fun rearmSnapshotFenceForV2() {
        records.entries.removeIf { it.key.startsWith(LEGACY_VM_ID_PREFIX) }
        legacyAliases.clear()
        awaitingSnapshot = true
    }

    @Synchronized
    fun markDisconnected() {
        connected = false
        awaitingSnapshot = true
        records.replaceAll { _, value -> value.copy(state = "LOST", activePauseId = null) }
    }

    @Synchronized
    fun isConnected(): Boolean = connected

    @Synchronized
    fun isAwaitingSnapshot(): Boolean = awaitingSnapshot

    @Synchronized
    fun applySnapshot(
        snapshot: VmSnapshotDto,
        agentSessionId: String? = null,
        connectionEpoch: Long? = null
    ): VmApplyResult {
        if (snapshot.snapshotEventSeq < 0L || snapshot.vms.any { !it.vmId.isValidVmId() || it.generation <= 0L || !it.state.isKnownVmState() } ||
            snapshot.vms.map { it.vmId }.toSet().size != snapshot.vms.size) {
            return VmApplyResult(VmApplyStatus.INVALID, message = "snapshot contains an invalid or duplicate VM")
        }
        if ((agentSessionId == null) != (connectionEpoch == null)) {
            return VmApplyResult(VmApplyStatus.STALE_EPOCH, message = "v2 snapshot must contain session and epoch together")
        }
        if (!acceptEpoch(agentSessionId, connectionEpoch, isSnapshot = true)) {
            return VmApplyResult(VmApplyStatus.STALE_EPOCH, message = "snapshot belongs to an old connection epoch")
        }
        if (!awaitingSnapshot && hasSequence && snapshot.snapshotEventSeq <= eventSeq) {
            return VmApplyResult(VmApplyStatus.DUPLICATE)
        }
        val stale = snapshot.vms.firstOrNull { vm ->
            vm.generation <= (retiredGenerations[vm.vmId] ?: 0L)
        }
        if (stale != null) {
            return VmApplyResult(
                VmApplyStatus.STALE_GENERATION,
                stale.vmId,
                "snapshot generation ${stale.generation} is not newer than retired generation " +
                    (retiredGenerations[stale.vmId] ?: 0L)
            )
        }
        records.clear()
        snapshot.vms.forEach { vm ->
            records[vm.vmId] = vm.toModel(snapshot.snapshotEventSeq)
        }
        eventSeq = snapshot.snapshotEventSeq
        hasSequence = true
        connected = true
        awaitingSnapshot = false
        return VmApplyResult(VmApplyStatus.APPLIED)
    }

    @Synchronized
    fun applyLifecycle(
        lifecycle: VmLifecycleDto,
        agentSessionId: String? = null,
        connectionEpoch: Long? = null
    ): VmApplyResult {
        if (!lifecycle.vmId.isValidVmId() || lifecycle.generation <= 0L || lifecycle.eventSeq <= 0L ||
            !lifecycle.current.isKnownVmState()) {
            return VmApplyResult(VmApplyStatus.INVALID, lifecycle.vmId, "invalid VM lifecycle payload")
        }
        if ((agentSessionId == null) != (connectionEpoch == null)) {
            return VmApplyResult(VmApplyStatus.STALE_EPOCH, lifecycle.vmId,
                "v2 lifecycle must contain session and epoch together")
        }
        if (!acceptEpoch(agentSessionId, connectionEpoch, isSnapshot = false)) {
            return VmApplyResult(
                if (awaitingSnapshot) VmApplyStatus.SNAPSHOT_REQUIRED else VmApplyStatus.STALE_EPOCH,
                lifecycle.vmId,
                if (awaitingSnapshot) "connection snapshot has not been applied" else "lifecycle belongs to an old connection"
            )
        }
        if (lifecycle.eventSeq <= eventSeq) {
            return VmApplyResult(VmApplyStatus.DUPLICATE, lifecycle.vmId)
        }
        if (lifecycle.eventSeq != eventSeq + 1) {
            return VmApplyResult(
                VmApplyStatus.GAP,
                lifecycle.vmId,
                "expected eventSeq ${eventSeq + 1}, got ${lifecycle.eventSeq}"
            )
        }

        val previous = records[lifecycle.vmId]
        val retiredGeneration = retiredGenerations[lifecycle.vmId] ?: 0L
        if (lifecycle.generation <= retiredGeneration) {
            return VmApplyResult(
                VmApplyStatus.STALE_GENERATION,
                lifecycle.vmId,
                "lifecycle generation ${lifecycle.generation} is retired"
            )
        }
        if (previous != null && lifecycle.generation < previous.generation) {
            return VmApplyResult(VmApplyStatus.STALE_GENERATION, lifecycle.vmId,
                "lifecycle belongs to an older VM generation")
        }
        if (previous != null && lifecycle.generation > previous.generation) {
            // A live record may only be replaced after its close event.  A
            // generation jump otherwise allows an address-reuse event to
            // overwrite a still-active VM.
            return VmApplyResult(VmApplyStatus.STALE_GENERATION, lifecycle.vmId,
                "active VM generation cannot be replaced without a snapshot")
        }
        if (previous == null && lifecycle.current !in setOf("CREATED", "READY", "RUNNING", "PAUSED", "CLOSING", "ERROR")) {
            return VmApplyResult(VmApplyStatus.SNAPSHOT_REQUIRED, lifecycle.vmId,
                "lifecycle for an unknown VM requires a snapshot")
        }
        if (lifecycle.previous != null && previous != null && lifecycle.previous != previous.state) {
            return VmApplyResult(VmApplyStatus.INVALID, lifecycle.vmId,
                "lifecycle previous state does not match the registry")
        }
        val next = if (previous == null) {
            VmRecordModel(
                vmId = lifecycle.vmId,
                generation = lifecycle.generation,
                displayName = lifecycle.vmId,
                state = lifecycle.current,
                luaVersion = null,
                discovery = "UNKNOWN",
                diagnosticStateAddress = null,
                lastEventSeq = lifecycle.eventSeq,
                connectionEpoch = epoch,
                contextGeneration = lifecycle.contextGeneration,
                sourceEpoch = lifecycle.sourceEpoch
            )
        } else {
            previous.copy(
                generation = lifecycle.generation,
                state = lifecycle.current,
                lastEventSeq = lifecycle.eventSeq,
                connectionEpoch = epoch,
                contextGeneration = lifecycle.contextGeneration ?: previous.contextGeneration,
                sourceEpoch = lifecycle.sourceEpoch ?: previous.sourceEpoch,
                activePauseId = if (lifecycle.current == "CLOSED" || lifecycle.current == "CLOSING") {
                    null
                } else if ((lifecycle.contextGeneration != null && lifecycle.contextGeneration != previous.contextGeneration) ||
                    (lifecycle.sourceEpoch != null && lifecycle.sourceEpoch != previous.sourceEpoch) ||
                    lifecycle.generation != previous.generation) {
                    null
                } else previous.activePauseId
            )
        }
        if (lifecycle.current == "CLOSED") {
            retiredGenerations[lifecycle.vmId] = maxOf(retiredGeneration, lifecycle.generation)
            records.remove(lifecycle.vmId)
        } else {
            records[lifecycle.vmId] = next
        }
        eventSeq = lifecycle.eventSeq
        hasSequence = true
        return VmApplyResult(VmApplyStatus.APPLIED, lifecycle.vmId)
    }

    @Synchronized
    fun applyEnvelope(envelope: EmmyV2Envelope): VmApplyResult {
        if (envelope.cmd != EMMY_V2_ENVELOPE_WIRE_ID || envelope.protocolVersion != 2) {
            return VmApplyResult(VmApplyStatus.INVALID, message = "unsupported VM envelope version")
        }
        val sessionId = envelope.agentSessionId
        val epoch = envelope.connectionEpoch
        if (sessionId.isNullOrBlank() || epoch == null || epoch <= 0L) {
            return VmApplyResult(VmApplyStatus.INVALID, message = "v2 VM envelope requires a non-empty session and positive epoch")
        }
        return runCatching {
            when (envelope.type) {
                "vm.snapshot" -> {
                    val payload = envelope.payload ?: return@runCatching VmApplyResult(
                        VmApplyStatus.INVALID,
                        message = "vm.snapshot payload is missing"
                    )
                    val snapshot = EmmyJson.gson.fromJson(payload, VmSnapshotDto::class.java)
                    applySnapshot(snapshot, sessionId, epoch)
                }
                "vm.lifecycle" -> {
                    val payload = envelope.payload ?: return@runCatching VmApplyResult(
                        VmApplyStatus.INVALID,
                        message = "vm.lifecycle payload is missing"
                    )
                    val lifecycle = EmmyJson.gson.fromJson(payload, VmLifecycleDto::class.java)
                    applyLifecycle(lifecycle, sessionId, epoch)
                }
                else -> VmApplyResult(VmApplyStatus.INVALID, message = "unsupported VM envelope")
            }
        }.getOrElse { error ->
            VmApplyResult(VmApplyStatus.INVALID, message = "malformed VM envelope: ${error.message ?: "payload"}")
        }
    }

    @Synchronized
    fun legacyAttached(stateAddress: Long, connectionEpoch: Long? = null): VmRecordModel {
        connected = true
        awaitingSnapshot = false
        val legacyId = "$LEGACY_VM_ID_PREFIX${connectionEpoch ?: 0}-$stateAddress"
        val record = VmRecordModel(
            vmId = legacyId,
            generation = 1,
            displayName = legacyId,
            state = "READY",
            luaVersion = null,
            discovery = "LEGACY_ATTACHED_NOTIFY",
            diagnosticStateAddress = "0x${stateAddress.toString(16)}",
            lastEventSeq = eventSeq
        )
        val registered = record.copy(connectionEpoch = connectionEpoch)
        records[legacyId] = registered
        return registered
    }

    /**
     * Native legacy agents publish their own opaque VM id (for example `vm-1`)
     * in v1 `BreakNotify`, while a legacy attach is registered here as
     * `legacy-<epoch>-<stateAddress>`. Remember the mapping so both sides name
     * the same record.
     */
    private val legacyAliases = linkedMapOf<String, String>()

    /**
     * Maps the VM id a legacy agent reports onto the record registered for the
     * legacy attach.
     *
     * Without this mapping `setPause("vm-1", …)` hits an unknown id and is
     * dropped, while the pause snapshot is stored under `vm-1`; every later
     * lookup through the registry id (IDE variables panel and the CLI
     * stack/scopes/variables/eval commands) then misses and reports a stale
     * pause.
     *
     * Returns null when the id cannot be mapped safely: nothing registered, more
     * than one legacy candidate, or a v2 record set. Callers must then keep the
     * original id.
     */
    @Synchronized
    fun normalizeLegacyVmId(nativeVmId: String): String? {
        if (records.containsKey(nativeVmId)) return nativeVmId
        legacyAliases[nativeVmId]?.let { mapped -> if (records.containsKey(mapped)) return mapped }
        // Never fold an older protocol id into a v2 record: its id is
        // authoritative and shared with the agent.
        val legacy = records.values.filter { it.vmId.startsWith(LEGACY_VM_ID_PREFIX) }
        if (legacy.size != 1) return null
        val target = legacy.single().vmId
        legacyAliases[nativeVmId] = target
        return target
    }

    @Synchronized
    fun legacyAttachStatus(): LegacyAttachStatus =
        if (records.size > 1) LegacyAttachStatus.AMBIGUOUS else LegacyAttachStatus.REGISTERED

    @Synchronized
    fun resolve(vmId: String? = null): VmRecordModel? {
        if (vmId != null) return records[vmId]
        return if (records.size == 1) records.values.first() else null
    }

    @Synchronized
    fun list(): List<VmRecordModel> = records.values.toList()

    @Synchronized
    fun invalidatePause(vmId: String, pauseId: Long? = null) {
        val current = records[vmId] ?: return
        if (pauseId == null || current.activePauseId == pauseId) {
            records[vmId] = current.copy(activePauseId = null)
        }
    }

    @Synchronized
    fun setPause(vmId: String, pauseId: Long) {
        val current = records[vmId] ?: return
        if (current.state == "LOST" || current.state == "CLOSED" || current.state == "CLOSING") return
        records[vmId] = current.copy(activePauseId = pauseId)
    }

    @Synchronized
    fun isControlReady(vmId: String): Boolean {
        val state = records[vmId]?.state ?: return false
        return !awaitingSnapshot && connected && state in setOf("READY", "RUNNING", "PAUSED")
    }

    @Synchronized
    fun currentEventSeq(): Long = eventSeq

    @Synchronized
    fun currentEpoch(): Long? = epoch

    @Synchronized
    fun acceptsEpoch(agentSessionId: String?, connectionEpoch: Long?): Boolean =
        connected && !awaitingSnapshot &&
            if (agentSessionId == null && connectionEpoch == null) {
                true // legacy v1 messages have no identity envelope
            } else {
                agentSessionId != null && connectionEpoch != null &&
                    agentSessionId == sessionId && connectionEpoch == epoch
            }

    @Synchronized
    fun invalidateAllPauses() {
        records.replaceAll { _, value -> value.copy(activePauseId = null) }
    }

    /** Invalidates pause/frame references after a Lua context or source reload. */
    @Synchronized
    fun resetContext(vmId: String, contextGeneration: Long? = null, sourceEpoch: Long? = null): Boolean {
        val current = records[vmId] ?: return false
        records[vmId] = current.copy(
            activePauseId = null,
            contextGeneration = contextGeneration ?: current.contextGeneration,
            sourceEpoch = sourceEpoch ?: current.sourceEpoch
        )
        return true
    }

    private fun acceptEpoch(newSessionId: String?, newEpoch: Long?, isSnapshot: Boolean): Boolean {
        if ((newSessionId == null) != (newEpoch == null)) return false
        if (newSessionId != null && sessionId != null && newSessionId != sessionId) {
            if (!isSnapshot) return false
            records.clear()
            eventSeq = 0
            hasSequence = false
        }
        if (newEpoch != null && epoch != null && newEpoch < epoch!!) return false
        if (newEpoch != null && epoch != null && newEpoch > epoch!!) {
            if (!isSnapshot) return false
            records.clear()
            eventSeq = 0
            hasSequence = false
        }
        if (newSessionId != null) sessionId = newSessionId
        if (newEpoch != null) epoch = newEpoch
        if (!isSnapshot && awaitingSnapshot) return false
        connected = true
        return true
    }

    private fun resetForNewConnection() {
        records.replaceAll { _, value -> value.copy(state = "LOST", activePauseId = null) }
        eventSeq = 0
        hasSequence = false
        awaitingSnapshot = true
        connected = true
    }

    private fun VmDto.toModel(lastEventSeq: Long): VmRecordModel = VmRecordModel(
        vmId = vmId,
        generation = generation,
        displayName = displayName,
        state = state,
        luaVersion = luaVersion,
        discovery = discovery,
        diagnosticStateAddress = diagnosticStateAddress,
        lastEventSeq = lastEventSeq,
        connectionEpoch = epoch,
        contextGeneration = contextGeneration,
        sourceEpoch = sourceEpoch
    )

    private fun String.isValidVmId(): Boolean = isNotBlank() && length <= 256 &&
        (startsWith("vm-") || matches(Regex("[A-Za-z0-9._:-]+")))

    private fun String.isKnownVmState(): Boolean = this in setOf(
        "CREATED", "READY", "RUNNING", "PAUSED", "CLOSING", "CLOSED", "LOST", "ERROR"
    )
}

internal object EmmyJson {
    val gson = com.google.gson.Gson()
}
