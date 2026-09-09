package com.tang.intellij.lua.debugger.emmy

import com.tang.intellij.lua.debugger.emmy.EmmyV2Envelope
import com.tang.intellij.lua.debugger.emmy.VmLifecycleDto
import com.tang.intellij.lua.debugger.emmy.VmSnapshotDto

/**
 * Backend-neutral VM state. Calls are serialized by the Emmy lifecycle
 * executor in production; synchronized methods also keep CLI/test readers safe.
 */
class VmRegistry {
    private val records = linkedMapOf<String, VmRecordModel>()
    private var sessionId: String? = null
    private var epoch: Long? = null
    private var eventSeq: Long = 0
    private var hasSequence = false

    @Synchronized
    fun applySnapshot(
        snapshot: VmSnapshotDto,
        agentSessionId: String? = null,
        connectionEpoch: Long? = null
    ): VmApplyResult {
        if (!acceptEpoch(agentSessionId, connectionEpoch, isSnapshot = true)) {
            return VmApplyResult(VmApplyStatus.STALE_EPOCH, message = "snapshot belongs to an old connection epoch")
        }
        if (hasSequence && snapshot.snapshotEventSeq <= eventSeq) {
            return VmApplyResult(VmApplyStatus.DUPLICATE)
        }
        records.clear()
        snapshot.vms.forEach { vm ->
            records[vm.vmId] = vm.toModel(snapshot.snapshotEventSeq)
        }
        eventSeq = snapshot.snapshotEventSeq
        hasSequence = true
        return VmApplyResult(VmApplyStatus.APPLIED)
    }

    @Synchronized
    fun applyLifecycle(
        lifecycle: VmLifecycleDto,
        agentSessionId: String? = null,
        connectionEpoch: Long? = null
    ): VmApplyResult {
        if (!acceptEpoch(agentSessionId, connectionEpoch, isSnapshot = false)) {
            return VmApplyResult(VmApplyStatus.STALE_EPOCH, lifecycle.vmId)
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
        return when (envelope.type) {
            "vm.snapshot" -> {
                val payload = envelope.payload ?: return VmApplyResult(VmApplyStatus.INVALID)
                val snapshot = EmmyJson.gson.fromJson(payload, VmSnapshotDto::class.java)
                applySnapshot(snapshot, envelope.agentSessionId, envelope.connectionEpoch)
            }
            "vm.lifecycle" -> {
                val payload = envelope.payload ?: return VmApplyResult(VmApplyStatus.INVALID)
                val lifecycle = EmmyJson.gson.fromJson(payload, VmLifecycleDto::class.java)
                applyLifecycle(lifecycle, envelope.agentSessionId, envelope.connectionEpoch)
            }
            else -> VmApplyResult(VmApplyStatus.INVALID, message = "unsupported VM envelope")
        }
    }

    @Synchronized
    fun legacyAttached(stateAddress: Long, connectionEpoch: Long? = null): VmRecordModel {
        val legacyId = "legacy-${connectionEpoch ?: 0}-$stateAddress"
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
        records[vmId] = current.copy(activePauseId = pauseId)
    }

    @Synchronized
    fun currentEventSeq(): Long = eventSeq

    @Synchronized
    fun currentEpoch(): Long? = epoch

    @Synchronized
    fun acceptsEpoch(agentSessionId: String?, connectionEpoch: Long?): Boolean =
        (agentSessionId == null || sessionId == null || agentSessionId == sessionId) &&
            (connectionEpoch == null || epoch == null || connectionEpoch == epoch)

    @Synchronized
    fun invalidateAllPauses() {
        records.replaceAll { _, value -> value.copy(activePauseId = null) }
    }

    private fun acceptEpoch(newSessionId: String?, newEpoch: Long?, isSnapshot: Boolean): Boolean {
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
        return true
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
}

internal object EmmyJson {
    val gson = com.google.gson.Gson()
}
