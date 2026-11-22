package com.fixcode.intellij.lua.debug.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * 断点「一直没对上代码」的诊断：断点图标变灰 + 悬停说明，以及「诊断断点」动作的报告。
 *
 * 数据来源（不新增协议）：
 * - runtime 在断点下发 5 秒后某个 VM 还没匹配上任何 chunk 时发的 `output { category: runtime, level: warn }`
 *   （protocol-v3.md 7.2 末尾），正文列出执行过的 chunk 原文，每行两个空格开头；
 * - 暂停时栈帧上的 chunkName、源码映射表里出现过的 chunk。
 *
 * 线程安全：引擎事件线程写，EDT / 定时器读。纯逻辑，不依赖 IDE。
 */
class BreakpointDiagnostics(
    /** 老 runtime 不发诊断输出时，断点保持「已下发」多久后也算没对上。 */
    private val pendingTimeoutMillis: Long = DEFAULT_PENDING_TIMEOUT_MS,
) {
    /** 本次会话见过的 chunk 原文（保持先后顺序）。 */
    private val chunks = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    /** 收到过 runtime「没匹配上」报告的 VM → 报告里一个 chunk 都没有（代码没跑 / 钩子没挂上）。 */
    private val unmatchedVms = ConcurrentHashMap<Long, Boolean>()
    /** 断点 id → 第一次看到它是「已下发」的时间。 */
    private val pendingSince = ConcurrentHashMap<String, Long>()

    fun chunks(): List<String> = synchronized(chunks) { chunks.toList() }

    fun recordChunk(chunkName: String?) {
        if (chunkName.isNullOrBlank() || chunkName.startsWith("=")) return
        chunks += chunkName
    }

    /** 最近一次收到 runtime「没匹配上」报告的时间。 */
    @Volatile
    private var lastReportAt = 0L

    /** 收到 runtime 输出；是断点诊断报告时记下其中的 chunk 并返回 true（调用方据此刷新断点图标）。 */
    fun recordOutput(category: String, text: String, vmId: Long?, now: Long = System.currentTimeMillis()): Boolean {
        if (category != "runtime" || !text.contains(UNMATCHED_MARKER)) return false
        val listed = text.lineSequence().filter { it.startsWith("  ") }.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        listed.forEach { recordChunk(it.removeSuffix("…")) }
        unmatchedVms[vmId ?: -1L] = listed.isEmpty()
        lastReportAt = now
        return true
    }

    /** 断点状态变化：记下开始等待的时间；生效、删除后清掉。 */
    fun recordBreakpoint(view: BreakpointView, now: Long) {
        // 函数断点没有文件可对，「路径对不上」的诊断不适用
        if (view.request.isFunction) return
        if (view.verification == BreakpointVerification.PENDING) pendingSince.putIfAbsent(view.id, now)
        else pendingSince.remove(view.id)
    }

    fun forget(breakpointId: String) {
        pendingSince.remove(breakpointId)
    }

    /** 新的一批断点下发（重连）：runtime 会重新计时、重新报告。 */
    fun resetRuntimeReports() {
        unmatchedVms.clear()
        lastReportAt = 0L
    }

    /**
     * 断点为什么还没对上代码；还不该下结论（没等够、已生效）时返回 null。
     * 结论成立的条件：断点开始等待之后 runtime 报过「没匹配上」，或等了 [pendingTimeoutMillis] 还是「已下发」。
     * [mapping] 是 chunk → 本地文件（IDE 的源码映射，没映射为 null），用来分清同名 chunk 是不是就是这个文件。
     */
    fun unmatchedReason(view: BreakpointView, now: Long, mapping: (String) -> String? = { null }): String? {
        if (view.verification != BreakpointVerification.PENDING || view.request.isFunction) return null
        val since = pendingSince[view.id] ?: return null
        if (lastReportAt < since && now - since < pendingTimeoutMillis) return null
        val seen = chunks()
        val sameName = suggestChunks(view.request.file, seen)
        val suggestions = mismatchedChunks(view.request.file, sameName, mapping)
        return when {
            // 同名 chunk 都已经映射到这个文件：路径没问题，只是下了断点之后这个文件里的代码还没再执行
            // （runtime 在文件里的函数下一次执行时才确认断点）。这时不能说「路径没对上」，图标也不该变灰。
            sameName.isNotEmpty() && suggestions.isEmpty() ->
                "这个文件之前执行过、路径也对得上；下了断点之后它里面的代码还没再执行，执行到时会自动生效。"
            suggestions.isNotEmpty() ->
                "游戏执行过 chunk \"${suggestions.first()}\"，文件名一样但路径没对上这个文件。" +
                    "用调试工具栏的「诊断断点」应用建议的映射，或在调试窗口「映射」页把它指到这个文件。"
            seen.isNotEmpty() ->
                "本次会话执行过的 ${seen.size} 个脚本里没有对得上这个文件的：这段代码可能还没执行，或游戏加载的是别处的副本。" +
                    "「诊断断点」里能看到执行过的 chunk 名。"
            unmatchedVms.values.any { it } ->
                "下发后游戏没有执行过任何脚本文件里的函数：断点所在的代码还没跑到，或调试钩子没挂到实际执行脚本的 lua_State 上。"
            else -> "还没有加载对得上这个文件的脚本：等这段代码执行时会自动生效；一直不生效时用「诊断断点」查看。"
        }
    }

    companion object {
        const val DEFAULT_PENDING_TIMEOUT_MS = 6_000L
        /** runtime 诊断输出的固定开头（native/emmy_debugger_core/src/vm.cpp BreakpointDiagnostics）。 */
        const val UNMATCHED_MARKER = "断点下发后还没有匹配上任何执行过的脚本"

        /**
         * 和断点文件「文件名一样」的 chunk（去掉扩展名、不分大小写比最后一段），按和文件路径共同后缀段数从多到少排。
         * 能对上一段以上说明多半是同一个文件、只是路径前缀不同，建议映射。
         */
        fun suggestChunks(file: String, chunks: Collection<String>): List<String> {
            val fileSegs = SourcePaths.segments(file, caseSensitive = false)
            val name = fileSegs.lastOrNull() ?: return emptyList()
            return chunks.mapNotNull { chunk ->
                val path = SourcePaths.chunkToPath(chunk) ?: return@mapNotNull null
                val segs = SourcePaths.segments(path, caseSensitive = false)
                if (segs.lastOrNull() != name) return@mapNotNull null
                var common = 0
                while (common < segs.size && common < fileSegs.size && segs[segs.size - 1 - common] == fileSegs[fileSegs.size - 1 - common]) common++
                chunk to common
            }.sortedByDescending { it.second }.map { it.first }.distinct()
        }

        /**
         * 真正「路径没对上」的同名 chunk：[suggestChunks] 里去掉已经映射到 [file] 本身的。
         * 不去掉的话，断点所在文件执行过（例如在同一文件的别的断点停过）、新断点还没执行到时，
         * 会把这个文件自己的 chunk 当成「同名但路径不对」，断点被误标成灰色。
         */
        fun mismatchedChunks(file: String, chunks: Collection<String>, mapping: (String) -> String?): List<String> {
            val target = SourcePaths.normalize(file)
            return suggestChunks(file, chunks).filter { chunk -> mapping(chunk)?.let(SourcePaths::normalize)?.equals(target, ignoreCase = true) != true }
        }

        /** 「诊断断点」报告的一个断点。 */
        data class BreakpointLine(val view: BreakpointView, val reason: String?, val suggestions: List<String>)

        /**
         * 生成报告正文（中文，给人看，也能直接贴给我们）。[mapping] 是 chunk → 本地文件（没映射为 null）。
         * 返回正文和建议的映射（chunk → 本地文件）。
         */
        fun report(
            breakpoints: List<BreakpointLine>,
            chunks: List<String>,
            mapping: (String) -> String?,
        ): Pair<String, Map<String, String>> {
            val suggested = LinkedHashMap<String, String>()
            val text = buildString {
                append("本次会话见过的脚本 chunk（${chunks.size} 个")
                if (chunks.size > MAX_CHUNKS) append("，只列前 $MAX_CHUNKS 个")
                append("）：\n")
                if (chunks.isEmpty()) append("  （还没有：游戏暂停过或 runtime 报告过之后才有）\n")
                chunks.take(MAX_CHUNKS).forEach { c ->
                    append("  ").append(c).append("  →  ").append(mapping(c) ?: "没有对应的本地文件").append('\n')
                }
                append("\n断点（${breakpoints.size} 个）：\n")
                if (breakpoints.isEmpty()) append("  （没有）\n")
                for (b in breakpoints) {
                    val r = b.view.request
                    append("  ").append(r.file).append(':').append(r.line).append("  ")
                    append(
                        when (b.view.verification) {
                            BreakpointVerification.VERIFIED -> "已生效" + (b.view.actualLine?.takeIf { it != r.line }?.let { "（实际第 $it 行）" } ?: "")
                            BreakpointVerification.PENDING -> if (b.reason != null) "没对上代码" else "已下发，等代码执行"
                            BreakpointVerification.MISMATCH -> "已生效，但游戏里的代码和本地文件不一致"
                            BreakpointVerification.AMBIGUOUS -> "对上了多个同名文件，没有生效"
                            BreakpointVerification.INVALID -> "无效"
                        }
                    ).append('\n')
                    (b.reason ?: b.view.message)?.let { append("    ").append(it).append('\n') }
                    val mapped = b.suggestions.filter { mapping(it) != SourcePaths.normalize(r.file) }
                    mapped.firstOrNull()?.let { chunk ->
                        suggested.putIfAbsent(chunk, r.file)
                        append("    建议映射：chunk \"").append(chunk).append("\" → ").append(r.file).append('\n')
                    }
                }
                if (suggested.isNotEmpty()) {
                    append("\n可以一键应用上面的建议映射；之后断点会按新的 chunk 名重新下发。\n")
                } else if (breakpoints.any { it.view.verification == BreakpointVerification.PENDING }) {
                    append("\n断点一直「已下发」时：先确认这段代码执行过；执行过还不停，点调试工具栏的「暂停」，")
                    append("控制台会打印游戏实际执行的 chunk 名；点了也停不下来说明调试钩子没生效。\n")
                }
            }
            return text to suggested
        }

        private const val MAX_CHUNKS = 200
    }
}
