package com.tang.intellij.lua.debugger.mcp.protocol

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

data class McpCallEntry(
    val timestampMillis: Long,
    val tool: String,
    val argsSummary: String,
    val resultSummary: String,
    val success: Boolean,
    val clientName: String?,
    val sessionId: String?,
    val durationMillis: Long,
)

/** 最近的 Agent 调用，给调试窗口「Agent」页显示。固定容量的环形缓冲。 */
class McpCallLog(private val capacity: Int = 500) {
    private val entries = ArrayDeque<McpCallEntry>()
    private val listeners = CopyOnWriteArrayList<(McpCallEntry) -> Unit>()

    fun add(entry: McpCallEntry) {
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
        listeners.forEach { runCatching { it(entry) } }
    }

    /** 最近 [limit] 条，旧的在前。 */
    fun recent(limit: Int): List<McpCallEntry> = synchronized(entries) {
        entries.toList().takeLast(limit.coerceAtLeast(0))
    }

    fun addListener(listener: (McpCallEntry) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    companion object {
        /** 参数摘要：一行、限长，表达式这类可能很长的值截断。 */
        fun summarize(args: Map<String, Any?>, max: Int = 160): String {
            val s = args.entries.joinToString(", ") { (k, v) ->
                val vs = when (v) {
                    is String -> "\"" + v.replace('\n', ' ') + "\""
                    else -> v.toString()
                }
                "$k=$vs"
            }
            return oneLine(s, max)
        }

        fun oneLine(s: String, max: Int = 160): String {
            val flat = s.replace('\n', ' ').replace('\r', ' ')
            return if (flat.length <= max) flat else flat.take(max - 1) + "…"
        }
    }
}
