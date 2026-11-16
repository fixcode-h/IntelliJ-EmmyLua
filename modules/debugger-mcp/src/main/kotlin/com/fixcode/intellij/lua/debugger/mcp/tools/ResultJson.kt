package com.fixcode.intellij.lua.debugger.mcp.tools

import com.fixcode.intellij.lua.debugger.mcp.*
import com.fixcode.intellij.lua.debugger.mcp.json.Json

/**
 * 工具结果的紧凑 JSON（规范见 AGENTS.md「MCP 工具规范」，照 UEToVS 的 ResultJson）：
 * - 字段名 camelCase，默认值字段省略（false、空列表、null 不写），省 token；
 * - 行号从 1 开始，帧号 `i` 从 0（栈顶）开始；
 * - 值节点 `{n, t, v, c?, more?, ref?, err?}`：名字、类型、显示值、已展开的子项、没列出的子项数、继续展开用的引用、取值出错。
 */
internal object ResultJson {
    /** 一个结果的默认预算（字符数），超出时截断并标 truncated。 */
    const val BUDGET = 4000

    /** 读结构类结果（变量树、栈）的上限。 */
    const val MAX_BUDGET = 12000

    fun obj(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in pairs) put(m, k, v)
        return m
    }

    /** 省略默认值：null、false、空集合、空字符串。 */
    fun put(m: MutableMap<String, Any?>, key: String, value: Any?) {
        when (value) {
            null, false -> return
            is String -> if (value.isEmpty()) return
            is Collection<*> -> if (value.isEmpty()) return
            is Map<*, *> -> if (value.isEmpty()) return
        }
        m[key] = value
    }

    fun session(s: SessionInfo): Map<String, Any?> = obj(
        "sessionId" to s.sessionId,
        "kind" to s.kind.name.lowercase(),
        "target" to s.target,
        "state" to s.state.name.lowercase(),
        "vms" to s.vmCount,
        "pausedVms" to s.pausedVmIds,
        "lua" to s.luaVersion,
    )

    fun target(t: TargetInfo): Map<String, Any?> = obj(
        "kind" to t.kind.name.lowercase(),
        "id" to t.id,
        "name" to t.name,
        "package" to t.packageName,
        "pid" to t.pid,
        "detail" to t.detail,
    )

    fun vm(v: VmInfo): Map<String, Any?> = obj(
        "vm" to v.vmId,
        "name" to v.displayName,
        "state" to v.state.name.lowercase(),
        "sourceEpoch" to v.sourceEpoch,
    )

    fun frame(f: FrameInfo): Map<String, Any?> = obj(
        "i" to f.index,
        "function" to f.function.ifBlank { "?" },
        "file" to f.file,
        "line" to f.line,
        "chunk" to if (f.file == null) f.chunkName else null,
    )

    fun reason(r: PauseReason): String = when (r) {
        PauseReason.BREAKPOINT -> "breakpoint"
        PauseReason.STEP -> "step"
        PauseReason.PAUSE -> "pause"
        PauseReason.ERROR -> "error"
        PauseReason.ENTRY -> "entry"
        PauseReason.RUN_TO_LINE -> "toLine"
        PauseReason.OTHER -> "other"
    }

    /** 值节点。[depth] 已展开的层数由引擎决定，这里只转换。 */
    fun value(v: VariableInfo): Map<String, Any?> {
        val m = obj("n" to v.name, "t" to v.type, "v" to v.value)
        val children = v.children
        if (children != null) {
            put(m, "c", children.map { value(it) })
            v.childCount?.let { total -> if (total > children.size) m["more"] = total - children.size }
        } else if (v.childCount != null && v.childCount > 0) {
            m["more"] = v.childCount
        }
        put(m, "ref", v.ref)
        return m
    }

    fun breakpoint(b: BreakpointInfo): Map<String, Any?> = obj(
        "id" to b.breakpointId,
        "file" to b.file,
        "line" to b.line,
        // 默认启用，只在禁用时写 disabled=true（省略默认值）
        "disabled" to !b.enabled,
        "condition" to b.condition,
        "hitCount" to b.hitCondition,
        "message" to b.logMessage,
        "capture" to b.capture,
        "autoContinue" to b.autoContinue,
        "byAgent" to (b.origin == BreakpointOrigin.AGENT),
        "verified" to b.verified,
        "hint" to if (!b.verified) b.message else null,
    )

    fun probeHit(h: ProbeHit): Map<String, Any?> = obj(
        "seq" to h.seq,
        "bp" to h.breakpointId,
        "sessionId" to h.sessionId,
        "vm" to h.vmId,
        "file" to h.file,
        "line" to h.line,
        "values" to h.values,
    )

    /** 采样函数表的一行：百分比保留一位小数（Agent 看排名，不需要更细），样本数给出判断可信度。 */
    fun profileFunction(f: ProfileFunction): Map<String, Any?> = obj(
        "fn" to f.name,
        "file" to f.file,
        "line" to f.line.takeIf { it > 0 },
        "selfPct" to Math.round(f.selfPercent * 10) / 10.0,
        "totalPct" to Math.round(f.totalPercent * 10) / 10.0,
        "selfSamples" to f.selfSamples,
        "totalSamples" to f.totalSamples,
    )

    fun output(e: OutputEntry): Map<String, Any?> = obj(
        "seq" to e.seq,
        "cat" to e.category.name.lowercase(),
        "text" to e.text.trimEnd(),
        "sessionId" to e.sessionId,
    )

    /**
     * 按预算从列表头部取尽量多的项：返回 (取到的项, 是否截断)。
     * 至少放一项，保证 Agent 总能前进。
     */
    fun <T> fit(items: List<T>, budget: Int, render: (T) -> Any?): Pair<List<Any?>, Boolean> {
        val out = ArrayList<Any?>()
        var size = 0
        for (item in items) {
            val node = render(item)
            val len = Json.write(node).length + 1
            if (out.isNotEmpty() && size + len > budget) return out to true
            out += node
            size += len
        }
        return out to false
    }

    /** 和 [fit] 相反：保留最新的（列表尾部），给输出日志用。 */
    fun <T> fitTail(items: List<T>, budget: Int, render: (T) -> Any?): Pair<List<Any?>, Boolean> {
        val out = ArrayDeque<Any?>()
        var size = 0
        for (item in items.asReversed()) {
            val node = render(item)
            val len = Json.write(node).length + 1
            if (out.isNotEmpty() && size + len > budget) return out.toList() to true
            out.addFirst(node)
            size += len
        }
        return out.toList() to false
    }
}
