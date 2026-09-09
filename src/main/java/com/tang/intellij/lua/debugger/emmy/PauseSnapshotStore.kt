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
    val sourceEpoch: Long? = null
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
        if (snapshots.containsKey(key) && presented.contains(key)) {
            return PauseOfferResult(PauseOfferStatus.DUPLICATE, snapshot)
        }
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

    /** Releases the UI pause and promotes the oldest queued pause, if any. */
    @Synchronized
    fun releaseCurrent(): PauseSnapshot? {
        currentUiKey?.let { snapshots.remove(it) }
        currentUiKey = queued.removeFirstOrNull()
        return currentUiKey?.let { snapshots[it] }
    }

    @Synchronized
    fun get(vmId: String, pauseId: Long): PauseSnapshot? = snapshots[key(vmId, pauseId)]

    @Synchronized
    fun invalidate(vmId: String, pauseId: Long? = null) {
        if (pauseId == null) {
            snapshots.keys.removeIf { it.startsWith("$vmId#") }
            queued.removeIf { it.startsWith("$vmId#") }
            if (currentUiKey?.startsWith("$vmId#") == true) currentUiKey = null
        } else {
            val pauseKey = key(vmId, pauseId)
            snapshots.remove(pauseKey)
            queued.remove(pauseKey)
            if (currentUiKey == pauseKey) currentUiKey = null
        }
    }

    @Synchronized
    fun clear() {
        snapshots.clear()
        queued.clear()
        presented.clear()
        currentUiKey = null
    }

    @Synchronized
    fun size(): Int = snapshots.size

    private fun key(vmId: String, pauseId: Long): String = "$vmId#$pauseId"
}
