package com.fixcode.intellij.lua.debug.engine

import com.fixcode.intellij.lua.debug.protocol.BreakpointAck
import com.fixcode.intellij.lua.debug.protocol.BreakpointSpec
import com.fixcode.intellij.lua.debug.protocol.BreakpointStateNames
import com.fixcode.intellij.lua.debug.protocol.BreakpointStatusEvent
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs

/**
 * 所有断点（IDE / Agent / 临时）的唯一来源。只在会话线程访问。
 *
 * 不再有旧实现的「IDE 断点 + CLI 断点 + Probe 合并」：Agent 的断点就是普通断点，带 origin 标记；
 * Probe 是断点的 capture + autoContinue 属性。
 *
 * 下发是增量的：记住 runtime 当前有哪些（[sent]），每次只发差异；新连接发全量（replaceAll）。
 */
class BreakpointStore(
    private val toSpec: (id: String, request: BreakpointRequest) -> BreakpointSpec,
    /** 当前 runtime 不支持这种断点（例如没有 functionBreakpoints 能力）时返回原因：不下发，状态为 INVALID。 */
    private val unsupported: (BreakpointRequest) -> String? = { null }
) {
    private class Entry(val id: String, var request: BreakpointRequest) {
        var ack: BreakpointAck? = null
        val perVm = LinkedHashMap<Long, BreakpointStatusEvent>()
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val sent = LinkedHashMap<String, BreakpointSpec>()
    private var revision = 0L
    private var nextId = 1L

    fun add(request: BreakpointRequest): String {
        val prefix = when (request.origin) {
            BreakpointOrigin.IDE -> "bp"
            BreakpointOrigin.AGENT -> "agent"
            BreakpointOrigin.TEMPORARY -> "tmp"
        }
        val id = "$prefix-${nextId++}"
        entries[id] = Entry(id, request)
        return id
    }

    /** 返回 false 表示断点不存在。内容变化后旧的验证状态作废。 */
    fun update(id: String, request: BreakpointRequest): Boolean {
        val entry = entries[id] ?: return false
        if (entry.request != request) {
            entry.request = request
            entry.ack = null
            entry.perVm.clear()
        }
        return true
    }

    fun remove(id: String): Boolean = entries.remove(id) != null

    fun removeIf(predicate: (String, BreakpointRequest) -> Boolean): List<String> {
        val ids = entries.values.filter { predicate(it.id, it.request) }.map { it.id }
        ids.forEach { entries.remove(it) }
        return ids
    }

    operator fun get(id: String): BreakpointRequest? = entries[id]?.request

    fun ids(): Set<String> = entries.keys.toSet()

    /** 当前想要 runtime 持有的断点（禁用的不下发）。 */
    private fun desired(): Map<String, BreakpointSpec> =
        entries.values.filter { it.request.enabled && unsupported(it.request) == null }.associate { it.id to toSpec(it.id, it.request) }

    /** 和 runtime 已有的比较，没有差异时返回 null。调用方发送后要 [markSent]。 */
    fun nextDelta(): SetBreakpointsArgs? {
        val desired = desired()
        val upserts = desired.filter { (id, spec) -> sent[id] != spec }.values.toList()
        val removes = sent.keys.filter { it !in desired }
        if (upserts.isEmpty() && removes.isEmpty()) return null
        return SetBreakpointsArgs(++revision, upserts, removes, replaceAll = false)
    }

    /** 新连接：revision 从 1 重新开始，全量替换。 */
    fun fullSnapshot(): SetBreakpointsArgs {
        sent.clear()
        revision = 0
        entries.values.forEach { it.ack = null; it.perVm.clear() }
        return SetBreakpointsArgs(++revision, desired().values.toList(), emptyList(), replaceAll = true)
    }

    fun markSent(args: SetBreakpointsArgs) {
        if (args.replaceAll) sent.clear()
        args.removes.forEach { sent.remove(it) }
        args.upserts.forEach { sent[it.id] = it }
    }

    /** 连接断开：runtime 已清空断点，状态回到 pending。 */
    fun connectionLost() {
        sent.clear()
        entries.values.forEach { it.ack = null; it.perVm.clear() }
    }

    fun applyAcks(acks: List<BreakpointAck>) {
        for (ack in acks) entries[ack.id]?.ack = ack
    }

    /** 返回 true 表示状态有变化。 */
    fun applyStatus(event: BreakpointStatusEvent): Boolean {
        val entry = entries[event.id] ?: return false
        val previous = entry.perVm.put(event.vmId, event)
        return previous != event
    }

    /** VM 关闭：它的验证状态不再算数。 */
    fun forgetVm(vmId: Long): Boolean {
        var changed = false
        entries.values.forEach { if (it.perVm.remove(vmId) != null) changed = true }
        return changed
    }

    fun view(id: String): BreakpointView? = entries[id]?.let(::view)

    fun views(): List<BreakpointView> = entries.values.map(::view)

    private fun view(entry: Entry): BreakpointView {
        val statuses = entry.perVm.values
        val verified = statuses.firstOrNull { it.state == BreakpointStateNames.VERIFIED }
        val mismatch = statuses.firstOrNull { it.state == BreakpointStateNames.MISMATCH }
        val ambiguous = statuses.firstOrNull { it.state == BreakpointStateNames.AMBIGUOUS }
        val invalid = statuses.firstOrNull { it.state == BreakpointStateNames.INVALID }
        val ack = entry.ack
        unsupported(entry.request)?.let { return BreakpointView(entry.id, entry.request, BreakpointVerification.INVALID, it) }
        return when {
            ack?.state == BreakpointStateNames.INVALID ->
                BreakpointView(entry.id, entry.request, BreakpointVerification.INVALID, ack.message)
            invalid != null -> BreakpointView(entry.id, entry.request, BreakpointVerification.INVALID, invalid.message)
            verified != null -> BreakpointView(
                entry.id, entry.request, BreakpointVerification.VERIFIED, verified.message,
                verified.line?.takeIf { it != entry.request.line },
                verified.chunkName?.takeIf { entry.request.isFunction }
            )
            mismatch != null -> BreakpointView(
                entry.id, entry.request, BreakpointVerification.MISMATCH,
                mismatch.message ?: "设备上的代码和本地文件不一致，断点行号可能不准。",
                mismatch.line?.takeIf { it != entry.request.line }
            )
            ambiguous != null -> BreakpointView(
                entry.id, entry.request, BreakpointVerification.AMBIGUOUS,
                ambiguous.message ?: "多个已加载的文件都能匹配这个断点，请在源码映射里指定对应关系。"
            )
            else -> BreakpointView(entry.id, entry.request, BreakpointVerification.PENDING)
        }
    }
}
