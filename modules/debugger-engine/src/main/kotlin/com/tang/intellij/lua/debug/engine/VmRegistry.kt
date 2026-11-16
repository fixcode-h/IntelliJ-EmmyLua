package com.tang.intellij.lua.debug.engine

import com.tang.intellij.lua.debug.protocol.VmInfo
import com.tang.intellij.lua.debug.protocol.VmStateChangedEvent
import com.tang.intellij.lua.debug.protocol.VmStates

/**
 * VM 表。只在会话线程访问。
 *
 * 规则（来自旧实现踩过的坑）：
 * - vmId 在 runtime 进程内不复用，关闭过的 vmId 不能被迟到的事件“复活”（PIE 重启后旧 VM 的事件晚到）。
 * - 重连后以 welcome.vms 为准：不在快照里的 VM 视为已关闭。
 * - 收到未知 VM 的暂停时**不能静默丢弃**：旧版因为 VM 标识错配把暂停丢了，目标一直停着，IDE 却什么都不显示。
 *   这时建一个占位记录，照常呈现暂停。
 */
class VmRegistry {
    private val vms = LinkedHashMap<Long, VmView>()
    private val closed = HashSet<Long>()

    fun list(): List<VmView> = vms.values.toList()

    operator fun get(vmId: Long): VmView? = vms[vmId]

    fun isClosed(vmId: Long): Boolean = vmId in closed

    /** 换了一个进程（重连到了另一个 runtime）时清空墓碑，新进程的 vmId 可能从 1 重新开始。 */
    fun resetProcess() {
        vms.clear()
        closed.clear()
    }

    data class SnapshotChanges(val added: List<VmView>, val changed: List<VmView>, val closed: List<Long>)

    /** 用握手快照重建。 */
    fun applySnapshot(snapshot: List<VmInfo>): SnapshotChanges {
        val incoming = snapshot.filter { it.vmId !in closed }.associateBy { it.vmId }
        val removed = vms.keys.filter { it !in incoming }
        removed.forEach { close(it) }
        val added = ArrayList<VmView>()
        val changed = ArrayList<VmView>()
        for (info in incoming.values) {
            val existing = vms[info.vmId]
            val view = info.toView()
            vms[info.vmId] = view
            if (existing == null) added += view else if (existing != view) changed += view
        }
        return SnapshotChanges(added, changed, removed)
    }

    /** 返回 null 表示被拒绝（已关闭的 vmId 或重复）。 */
    fun add(info: VmInfo): VmView? {
        if (info.vmId in closed) return null
        val view = info.toView()
        val existing = vms[info.vmId]
        if (existing != null && !existing.placeholder && existing == view) return null
        vms[info.vmId] = view
        return view
    }

    fun applyState(event: VmStateChangedEvent): VmView? {
        val existing = vms[event.vmId] ?: return null
        val updated = existing.copy(
            state = event.state,
            paused = event.state == VmStates.PAUSED,
            sourceEpoch = event.sourceEpoch ?: existing.sourceEpoch
        )
        if (updated == existing) return null
        vms[event.vmId] = updated
        return updated
    }

    fun setPaused(vmId: Long, paused: Boolean): VmView? {
        val existing = vms[vmId] ?: return null
        if (existing.paused == paused) return null
        val updated = existing.copy(paused = paused, state = if (paused) VmStates.PAUSED else VmStates.RUNNING)
        vms[vmId] = updated
        return updated
    }

    /** 收到未知 VM 的暂停时调用。已关闭的 VM 返回 null（那是迟到的旧事件）。 */
    fun ensure(vmId: Long): VmView? {
        if (vmId in closed) return null
        return vms.getOrPut(vmId) {
            VmView(vmId, "Lua VM #$vmId", VmStates.RUNNING, 1, 1, null, paused = false, placeholder = true)
        }
    }

    fun close(vmId: Long): VmView? {
        closed += vmId
        return vms.remove(vmId)
    }

    private fun VmInfo.toView() = VmView(
        vmId, name.ifEmpty { "Lua VM #$vmId" }, state, generation, sourceEpoch, luaVersion,
        paused = state == VmStates.PAUSED
    )
}
