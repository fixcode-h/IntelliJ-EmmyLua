package com.tang.intellij.lua.debugger.cli

data class BreakpointKey(val sourceIdentity: String, val vmId: String, val line: Int)

data class BreakpointContribution(
    val owner: String,
    val condition: String? = null,
    val logMessage: String? = null,
    val hitCondition: String? = null
)

data class CompositeBreakpoint(
    val key: BreakpointKey,
    val contributions: List<BreakpointContribution>
)

class BreakpointComposer {
    private val entries = linkedMapOf<BreakpointKey, MutableMap<String, BreakpointContribution>>()

    @Synchronized
    fun upsert(key: BreakpointKey, contribution: BreakpointContribution) {
        entries.getOrPut(key) { linkedMapOf() }[contribution.owner] = contribution
    }

    @Synchronized
    fun remove(key: BreakpointKey, owner: String): Boolean {
        val owners = entries[key] ?: return false
        val removed = owners.remove(owner) != null
        if (owners.isEmpty()) entries.remove(key)
        return removed
    }

    @Synchronized
    fun snapshot(): List<CompositeBreakpoint> = entries.map { (key, owners) ->
        CompositeBreakpoint(key, owners.values.toList())
    }
}
