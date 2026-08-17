package com.tang.intellij.lua.debugger.core

data class DebugBreakpoint(
    val id: String,
    val path: String,
    val line: Int,
    val condition: String? = null,
    val logMessage: String? = null
)

data class BreakpointSnapshot(
    val path: String,
    val breakpoints: List<DebugBreakpoint>
)

/** Owns the complete IDE-side breakpoint set and emits per-file snapshots. */
class BreakpointRepository(
    private val normalizePath: (String) -> String = ::normalizeDebuggerPath
) {
    private val breakpointsById = linkedMapOf<String, DebugBreakpoint>()

    @Synchronized
    fun upsert(breakpoint: DebugBreakpoint): List<BreakpointSnapshot> {
        require(breakpoint.id.isNotBlank()) { "Breakpoint id must not be blank" }
        require(breakpoint.line > 0) { "Breakpoint line must be positive" }
        val normalized = breakpoint.copy(path = normalizePath(breakpoint.path))
        require(normalized.path.isNotBlank()) { "Breakpoint path must not be blank" }

        val previous = breakpointsById.put(normalized.id, normalized)
        return listOfNotNull(previous?.path, normalized.path)
            .distinct()
            .map(::snapshotForNormalizedPath)
    }

    @Synchronized
    fun remove(id: String): BreakpointSnapshot? {
        val removed = breakpointsById.remove(id) ?: return null
        return snapshotForNormalizedPath(removed.path)
    }

    @Synchronized
    fun snapshot(path: String): BreakpointSnapshot = snapshotForNormalizedPath(normalizePath(path))

    @Synchronized
    fun snapshots(): List<BreakpointSnapshot> = breakpointsById.values
        .map(DebugBreakpoint::path)
        .distinct()
        .sorted()
        .map(::snapshotForNormalizedPath)

    @Synchronized
    fun clear() {
        breakpointsById.clear()
    }

    private fun snapshotForNormalizedPath(path: String): BreakpointSnapshot = BreakpointSnapshot(
        path,
        breakpointsById.values
            .filter { it.path == path }
            .sortedWith(compareBy(DebugBreakpoint::line, DebugBreakpoint::id))
    )
}

fun normalizeDebuggerPath(path: String): String {
    val replaced = path.trim().replace('\\', '/')
    if (replaced.isEmpty()) return replaced

    val prefix = when {
        replaced.startsWith("//") -> "//"
        replaced.startsWith('/') -> "/"
        else -> ""
    }
    val parts = ArrayDeque<String>()
    replaced.removePrefix(prefix).split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (prefix.isEmpty()) parts.addLast(part)
            else -> parts.addLast(part)
        }
    }
    return prefix + parts.joinToString("/")
}
