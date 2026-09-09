package com.tang.intellij.lua.debugger.emmy

data class PauseSnapshot(
    val vmId: String,
    val pauseId: Long,
    val threadId: String? = null,
    val scope: String = "THREAD",
    val consistency: String = "THREAD_ONLY",
    val stacks: List<Stack> = emptyList()
)

/** Bounded pause/frame references. A missing entry is a stale reference. */
class PauseSnapshotStore(private val maxEntries: Int = 128) {
    private val snapshots = LinkedHashMap<String, PauseSnapshot>(16, 0.75f, true)

    @Synchronized
    fun put(snapshot: PauseSnapshot) {
        snapshots[key(snapshot.vmId, snapshot.pauseId)] = snapshot
        while (snapshots.size > maxEntries) {
            snapshots.entries.iterator().next().also { snapshots.remove(it.key) }
        }
    }

    @Synchronized
    fun get(vmId: String, pauseId: Long): PauseSnapshot? = snapshots[key(vmId, pauseId)]

    @Synchronized
    fun invalidate(vmId: String, pauseId: Long? = null) {
        if (pauseId == null) {
            snapshots.keys.removeIf { it.startsWith("$vmId#") }
        } else {
            snapshots.remove(key(vmId, pauseId))
        }
    }

    @Synchronized
    fun clear() = snapshots.clear()

    @Synchronized
    fun size(): Int = snapshots.size

    private fun key(vmId: String, pauseId: Long): String = "$vmId#$pauseId"
}
