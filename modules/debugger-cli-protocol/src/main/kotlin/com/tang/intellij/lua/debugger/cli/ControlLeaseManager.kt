package com.tang.intellij.lua.debugger.cli

data class ControlLease(
    val leaseId: String,
    val targetId: String,
    val owner: String,
    val expiresAtMillis: Long
)

class ControlLeaseManager(
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val leases = mutableMapOf<String, ControlLease>()
    private var sequence = 0L

    @Synchronized
    fun acquire(targetId: String, owner: String, ttlMillis: Long = 30_000): Result<ControlLease> {
        require(ttlMillis > 0) { "ttlMillis must be positive" }
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
        expire()
        val current = leases.values.firstOrNull { it.leaseId == leaseId } ?: return null
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
    fun current(targetId: String): ControlLease? {
        expire()
        return leases[targetId]
    }

    private fun expire() {
        val now = nowMillis()
        leases.entries.removeIf { it.value.expiresAtMillis <= now }
    }
}
