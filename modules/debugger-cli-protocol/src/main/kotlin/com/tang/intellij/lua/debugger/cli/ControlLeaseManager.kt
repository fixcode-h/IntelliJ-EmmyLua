package com.tang.intellij.lua.debugger.cli

data class ControlLease(
    val leaseId: String,
    val targetId: String,
    val owner: String,
    val expiresAtMillis: Long
)

class ControlLeaseManager(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val minTtlMillis: Long = MIN_TTL_MILLIS,
    private val maxTtlMillis: Long = MAX_TTL_MILLIS
) {
    private val leases = mutableMapOf<String, ControlLease>()
    private var sequence = 0L

    @Synchronized
    fun acquire(targetId: String, owner: String, ttlMillis: Long = 30_000): Result<ControlLease> {
        require(targetId.isNotBlank() && owner.isNotBlank()) { "targetId and owner must be non-blank" }
        require(ttlMillis in minTtlMillis..maxTtlMillis) {
            "ttlMillis must be between $minTtlMillis and $maxTtlMillis"
        }
        expire()
        val current = leases[targetId]
        if (current != null && current.owner != owner) {
            return Result.failure(IllegalStateException("TARGET_BUSY"))
        }
        val lease = ControlLease("lease-${++sequence}", targetId, owner, nowMillis() + ttlMillis)
        leases[targetId] = lease
        return Result.success(lease)
    }

    @Synchronized
    fun heartbeat(leaseId: String, ttlMillis: Long = 30_000): ControlLease? {
        return heartbeatInternal(leaseId, null, ttlMillis)
    }

    @Synchronized
    fun heartbeat(leaseId: String, owner: String, ttlMillis: Long = 30_000): ControlLease? {
        return heartbeatInternal(leaseId, owner, ttlMillis)
    }

    private fun heartbeatInternal(leaseId: String, owner: String?, ttlMillis: Long): ControlLease? {
        require(ttlMillis in minTtlMillis..maxTtlMillis) {
            "ttlMillis must be between $minTtlMillis and $maxTtlMillis"
        }
        expire()
        val current = leases.values.firstOrNull { it.leaseId == leaseId } ?: return null
        if (owner != null && current.owner != owner) return null
        val renewed = current.copy(expiresAtMillis = nowMillis() + ttlMillis)
        leases[current.targetId] = renewed
        return renewed
    }

    @Synchronized
    fun release(leaseId: String): Boolean {
        val target = leases.values.firstOrNull { it.leaseId == leaseId }?.targetId ?: return false
        leases.remove(target)
        return true
    }

    @Synchronized
    fun release(leaseId: String, owner: String): Boolean {
        val current = leases.values.firstOrNull { it.leaseId == leaseId } ?: return false
        if (current.owner != owner) return false
        leases.remove(current.targetId)
        return true
    }

    @Synchronized
    fun isOwner(targetId: String, leaseId: String, owner: String): Boolean {
        expire()
        val current = leases[targetId] ?: return false
        return current.leaseId == leaseId && current.owner == owner && current.expiresAtMillis > nowMillis()
    }

    @Synchronized
    fun revokeTarget(targetId: String): Boolean = leases.remove(targetId) != null

    @Synchronized
    fun revokeOwner(owner: String): Int {
        val targets = leases.filterValues { it.owner == owner }.keys.toList()
        targets.forEach { leases.remove(it) }
        return targets.size
    }

    @Synchronized
    fun clear() = leases.clear()

    @Synchronized
    fun current(targetId: String): ControlLease? {
        expire()
        return leases[targetId]
    }

    /** Resolves an opaque lease id without granting ownership or extending TTL. */
    @Synchronized
    fun find(leaseId: String): ControlLease? {
        expire()
        return leases.values.firstOrNull { it.leaseId == leaseId }
    }

    private fun expire() {
        val now = nowMillis()
        leases.entries.removeIf { it.value.expiresAtMillis <= now }
    }

    companion object {
        const val MIN_TTL_MILLIS: Long = 1
        const val MAX_TTL_MILLIS: Long = 5 * 60_000
    }
}
