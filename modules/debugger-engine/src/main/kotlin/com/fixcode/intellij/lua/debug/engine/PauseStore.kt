package com.fixcode.intellij.lua.debug.engine

/**
 * 每个 VM 至多一个当前暂停；UI 同一时刻只呈现其中一个（焦点），其余排队。只在会话线程访问。
 *
 * 规则（旧 PauseSnapshotStore 的用例）：
 * - pauseId 在每个 VM 内单调递增：已经结束或被取代的暂停不能被迟到的事件复活。
 * - 同一个暂停重复到达只算一次。
 * - 焦点暂停结束后，排队最久的那个自动成为焦点（否则那个 VM 一直停着、UI 上却看不到）。
 */
class PauseStore {
    enum class OfferStatus { FOCUSED, QUEUED, DUPLICATE, STALE }

    private val current = LinkedHashMap<Long, PauseInfo>()
    private val highestPauseId = HashMap<Long, Long>()
    var focusedVmId: Long? = null
        private set

    val focused: PauseInfo? get() = focusedVmId?.let { current[it] }

    fun all(): List<PauseInfo> = current.values.toList()

    operator fun get(vmId: Long): PauseInfo? = current[vmId]

    fun offer(pause: PauseInfo): OfferStatus {
        val existing = current[pause.vmId]
        if (existing != null && existing.pauseId == pause.pauseId && existing.connectionEpoch == pause.connectionEpoch) {
            return OfferStatus.DUPLICATE
        }
        val highest = highestPauseId[pause.vmId]
        if (highest != null && pause.pauseId <= highest) return OfferStatus.STALE
        highestPauseId[pause.vmId] = pause.pauseId
        current.remove(pause.vmId)
        current[pause.vmId] = pause
        val focusedVm = focusedVmId
        return if (focusedVm == null || focusedVm == pause.vmId || current[focusedVm] == null) {
            focusedVmId = pause.vmId
            OfferStatus.FOCUSED
        } else {
            OfferStatus.QUEUED
        }
    }

    fun isCurrent(pause: PauseInfo): Boolean {
        val existing = current[pause.vmId] ?: return false
        return existing.pauseId == pause.pauseId && existing.connectionEpoch == pause.connectionEpoch
    }

    data class Release(val released: PauseInfo, val newFocus: PauseInfo?, val focusChanged: Boolean)

    /** 结束一个暂停；[pauseId] 为空表示该 VM 的当前暂停。不是当前暂停时返回 null。 */
    fun release(vmId: Long, pauseId: Long? = null): Release? {
        val existing = current[vmId] ?: return null
        if (pauseId != null && existing.pauseId != pauseId) return null
        current.remove(vmId)
        if (focusedVmId != vmId) return Release(existing, focused, false)
        focusedVmId = current.keys.firstOrNull()
        return Release(existing, focused, true)
    }

    /** UI 切换 VM。目标 VM 没暂停时返回 null，焦点不变。 */
    fun focus(vmId: Long): PauseInfo? {
        val pause = current[vmId] ?: return null
        focusedVmId = vmId
        return pause
    }

    /** VM 关闭：它的暂停作废，pauseId 记录也清掉（vmId 不复用，留着没意义）。 */
    fun forgetVm(vmId: Long): Release? {
        val release = release(vmId)
        highestPauseId.remove(vmId)
        return release
    }

    /** 连接断开：runtime 会恢复所有 VM，本地暂停全部作废。pauseId 记录保留（重连不清零）。 */
    fun clear(): List<PauseInfo> {
        val released = current.values.toList()
        current.clear()
        focusedVmId = null
        return released
    }

    /** 换了进程：pauseId 从头计数。 */
    fun resetProcess() {
        clear()
        highestPauseId.clear()
    }
}
