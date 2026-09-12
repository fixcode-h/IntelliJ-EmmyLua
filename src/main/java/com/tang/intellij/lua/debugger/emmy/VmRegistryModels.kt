package com.tang.intellij.lua.debugger.emmy

enum class VmApplyStatus {
    APPLIED,
    DUPLICATE,
    GAP,
    SNAPSHOT_REQUIRED,
    STALE_EPOCH,
    STALE_GENERATION,
    INVALID
}

enum class LegacyAttachStatus { REGISTERED, AMBIGUOUS }

data class VmApplyResult(
    val status: VmApplyStatus,
    val vmId: String? = null,
    val message: String? = null
) {
    val accepted: Boolean get() = status == VmApplyStatus.APPLIED || status == VmApplyStatus.DUPLICATE
    val requiresSnapshot: Boolean get() = status == VmApplyStatus.GAP || status == VmApplyStatus.SNAPSHOT_REQUIRED
}

data class VmRecordModel(
    val vmId: String,
    val generation: Long,
    val displayName: String,
    val state: String,
    val luaVersion: String?,
    val discovery: String,
    val diagnosticStateAddress: String?,
    val lastEventSeq: Long,
    val activePauseId: Long? = null,
    val connectionEpoch: Long? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null
)
