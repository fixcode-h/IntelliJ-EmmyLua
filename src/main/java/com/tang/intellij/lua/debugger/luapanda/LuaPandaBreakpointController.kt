package com.tang.intellij.lua.debugger.luapanda

import com.tang.intellij.lua.debugger.core.BreakpointRepository
import com.tang.intellij.lua.debugger.core.BreakpointSnapshot
import com.tang.intellij.lua.debugger.core.DebugBreakpoint

class LuaPandaBreakpointController {
    private val repository = BreakpointRepository()

    fun upsert(id: Int, path: String, oneBasedLine: Int): List<LuaPandaBreakpoint> =
        repository.upsert(DebugBreakpoint(id.toString(), path, oneBasedLine)).map(::toProtocol)

    fun remove(id: Int): LuaPandaBreakpoint? = repository.remove(id.toString())?.let(::toProtocol)

    fun snapshots(): List<LuaPandaBreakpoint> = repository.snapshots().map(::toProtocol)

    fun persistentSnapshot(path: String): LuaPandaBreakpoint = toProtocol(repository.snapshot(path))

    fun snapshotWithTemporary(path: String, oneBasedLine: Int): LuaPandaBreakpoint {
        val persistent = persistentSnapshot(path)
        val breakpoints = (persistent.bks + BreakpointInfo(line = oneBasedLine))
            .distinctBy(BreakpointInfo::line)
            .sortedBy(BreakpointInfo::line)
        return persistent.copy(bks = breakpoints)
    }

    fun clear() = repository.clear()

    private fun toProtocol(snapshot: BreakpointSnapshot): LuaPandaBreakpoint = LuaPandaBreakpoint(
        path = snapshot.path,
        bks = snapshot.breakpoints.map { BreakpointInfo(line = it.line) }
    )
}
