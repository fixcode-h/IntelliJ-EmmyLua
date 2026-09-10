package com.tang.intellij.lua.debugger.emmy

data class PauseSnapshot(
    val vmId: String,
    val pauseId: Long,
    val threadId: String? = null,
    val scope: String = "THREAD",
    val consistency: String = "THREAD_ONLY",
    val stacks: List<Stack> = emptyList(),
    val connectionEpoch: Long? = null,
    val contextGeneration: Long? = null,
    val sourceEpoch: Long? = null,
    val reasons: Set<String> = emptySet()
)

enum class PauseOfferStatus { CURRENT, QUEUED, DUPLICATE, STALE }
data class PauseOfferResult(val status: PauseOfferStatus, val snapshot: PauseSnapshot) {
    val shouldPresent: Boolean get() = status == PauseOfferStatus.CURRENT
}

/** Bounded pause/frame references. A missing entry is a stale reference. */
class PauseSnapshotStore(private val maxEntries: Int = 128) {
    private val snapshots = LinkedHashMap<String, PauseSnapshot>(16, 0.75f, true)
    private val queued = ArrayDeque<String>()
    private var currentUiKey: String? = null
    private val presented = mutableSetOf<String>()
    private val lastPauseByVm = LinkedHashMap<String, Long>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun put(snapshot: PauseSnapshot) {
        snapshots[key(snapshot.vmId, snapshot.pauseId)] = snapshot
        while (snapshots.size > maxEntries) {
            val evicted = snapshots.entries.iterator().next().key
            snapshots.remove(evicted)
            queued.remove(evicted)
            presented.remove(evicted)
            if (currentUiKey == evicted) currentUiKey = null
        }
    }

    /** Offers a pause from either v1 or v2; duplicate wire notifications present once. */
    @Synchronized
    fun offer(snapshot: PauseSnapshot): PauseOfferResult {
        val key = key(snapshot.vmId, snapshot.pauseId)
        if (snapshot.pauseId <= 0 ||
            (snapshot.pauseId <= (lastPauseByVm[snapshot.vmId] ?: 0L) && !snapshots.containsKey(key))) {
            return PauseOfferResult(PauseOfferStatus.STALE, snapshot)
        }
        if (snapshots.containsKey(key)) {
            return PauseOfferResult(PauseOfferStatus.DUPLICATE, snapshot)
        }
        invalidate(snapshot.vmId)
        rememberPause(snapshot.vmId, snapshot.pauseId)
        put(snapshot)
        if (currentUiKey == null) {
            currentUiKey = key
            presented += key
            return PauseOfferResult(PauseOfferStatus.CURRENT, snapshot)
        }
        if (currentUiKey == key) {
            presented += key
            return PauseOfferResult(PauseOfferStatus.DUPLICATE, snapshot)
        }
        if (!queued.contains(key)) queued.addLast(key)
        return PauseOfferResult(PauseOfferStatus.QUEUED, snapshot)
    }

    @Synchronized
    fun currentUiPause(): PauseSnapshot? = currentUiKey?.let { snapshots[it] }

    @Synchronized
    fun queuedPauses(): List<PauseSnapshot> = queued.mapNotNull { snapshots[it] }

    /** Only an explicit user selection changes the active VM while another is paused. */
    @Synchronized
    fun select(vmId: String, pauseId: Long): PauseSnapshot? {
        val selectedKey = key(vmId, pauseId)
        val snapshot = snapshots[selectedKey] ?: return null
        currentUiKey?.takeIf { it != selectedKey && snapshots.containsKey(it) }?.let {
            if (!queued.contains(it)) queued.addLast(it)
        }
        queued.remove(selectedKey)
        currentUiKey = selectedKey
        presented += selectedKey
        return snapshot
    }

    /** Releases the UI pause; queued VMs remain available for explicit selection. */
    @Synchronized
    fun releaseCurrent(): PauseSnapshot? {
        currentUiKey?.let {
            snapshots[it]?.let { snapshot -> rememberPause(snapshot.vmId, snapshot.pauseId) }
            snapshots.remove(it)
            presented.remove(it)
        }
        currentUiKey = null
        return currentUiKey?.let { snapshots[it] }
    }

    @Synchronized
    fun get(vmId: String, pauseId: Long): PauseSnapshot? = snapshots[key(vmId, pauseId)]

    @Synchronized
    fun invalidate(vmId: String, pauseId: Long? = null) {
        val newest = pauseId ?: snapshots.values.filter { it.vmId == vmId }.maxOfOrNull { it.pauseId }
        if (newest != null) rememberPause(vmId, newest)
        if (pauseId == null) {
            val obsolete = snapshots.filterValues { it.vmId == vmId }.keys.toSet()
            obsolete.forEach {
                snapshots.remove(it)
                presented.remove(it)
            }
            queued.removeIf { it in obsolete }
            if (currentUiKey?.let { it in obsolete } == true) {
                currentUiKey = null
            }
        } else {
            val pauseKey = key(vmId, pauseId)
            snapshots.remove(pauseKey)
            queued.remove(pauseKey)
            presented.remove(pauseKey)
            if (currentUiKey == pauseKey) {
                currentUiKey = null
            }
        }
    }

    /** Drops all references from an obsolete context/source epoch. */
    @Synchronized
    fun invalidateContext(vmId: String, contextGeneration: Long? = null, sourceEpoch: Long? = null) {
        val obsolete = snapshots.filterValues { snapshot ->
            snapshot.vmId == vmId &&
                ((contextGeneration != null && snapshot.contextGeneration != contextGeneration) ||
                    (sourceEpoch != null && snapshot.sourceEpoch != sourceEpoch))
        }.keys.toSet()
        obsolete.forEach {
            snapshots[it]?.let { snapshot -> rememberPause(snapshot.vmId, snapshot.pauseId) }
            snapshots.remove(it)
            presented.remove(it)
        }
        queued.removeIf { it in obsolete }
        if (currentUiKey?.let { it in obsolete } == true) {
            currentUiKey = null
        }
    }

    @Synchronized
    fun clear() {
        snapshots.clear()
        queued.clear()
        presented.clear()
        lastPauseByVm.clear()
        currentUiKey = null
    }

    @Synchronized
    fun size(): Int = snapshots.size

    private fun key(vmId: String, pauseId: Long): String = "${vmId.length}:$vmId#$pauseId"

    private fun rememberPause(vmId: String, pauseId: Long) {
        lastPauseByVm[vmId] = maxOf(lastPauseByVm[vmId] ?: 0, pauseId)
        while (lastPauseByVm.size > maxOf(1024, maxEntries * 8)) {
            lastPauseByVm.remove(lastPauseByVm.keys.first())
        }
    }

}
