package com.tang.intellij.lua.debugger.cli

data class BreakpointKey(val sourceIdentity: String, val vmId: String, val line: Int)

data class BreakpointContribution(
    val owner: String,
    val breakpointId: String = "default",
    val condition: String? = null,
    val logMessage: String? = null,
    val hitCondition: String? = null,
    val runToHere: Boolean = false,
    val autoContinue: Boolean = false
)

data class CompositeBreakpoint(
    val key: BreakpointKey,
    val contributions: List<BreakpointContribution>
)

class BreakpointComposer {
    private data class ContributionKey(val owner: String, val breakpointId: String)

    private val entries = linkedMapOf<BreakpointKey, MutableMap<ContributionKey, BreakpointContribution>>()

    @Synchronized
    fun upsert(key: BreakpointKey, contribution: BreakpointContribution) {
        require(contribution.owner.isNotBlank()) { "breakpoint owner must not be blank" }
        require(contribution.breakpointId.isNotBlank()) { "breakpointId must not be blank" }
        entries.getOrPut(key) { linkedMapOf() }[
            ContributionKey(contribution.owner, contribution.breakpointId)
        ] = contribution
    }

    @Synchronized
    fun remove(key: BreakpointKey, owner: String, breakpointId: String? = null): Boolean {
        val owners = entries[key] ?: return false
        val removed = if (breakpointId == null) {
            val before = owners.size
            owners.keys.removeIf { it.owner == owner }
            owners.size != before
        } else {
            owners.remove(ContributionKey(owner, breakpointId)) != null
        }
        if (owners.isEmpty()) entries.remove(key)
        return removed
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun snapshot(): List<CompositeBreakpoint> = entries.entries
        .sortedWith(compareBy({ it.key.sourceIdentity }, { it.key.vmId }, { it.key.line }))
        .map { (key, owners) ->
            CompositeBreakpoint(key, owners.values.sortedWith(compareBy({ it.owner }, { it.breakpointId })))
        }
}
