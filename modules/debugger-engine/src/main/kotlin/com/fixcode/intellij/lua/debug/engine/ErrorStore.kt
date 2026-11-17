package com.fixcode.intellij.lua.debug.engine

import com.fixcode.intellij.lua.debug.protocol.LuaErrorDto

/**
 * 错误聚合的本地副本（协议第 21 节）。luaError 推送是限流过的，只用来让界面及时出现新错误、大致跟上计数；
 * 要精确计数时由 [DebugSession.errors] 向 runtime 拉一次全量覆盖。traceback 只在第一次推送里有，合并时保留。
 * 会话线程写、UI / MCP 线程读，所以整体加锁。
 */
class ErrorStore {
    private val entries = LinkedHashMap<Long, LuaErrorDto>()
    private var dropped = 0L

    @Synchronized
    fun merge(update: LuaErrorDto): LuaErrorDto {
        val old = entries[update.id]
        val merged = if (old == null) update else update.copy(
            traceback = update.traceback ?: old.traceback,
            chunkName = update.chunkName ?: old.chunkName,
            line = update.line ?: old.line,
            firstMs = if (old.firstMs > 0) old.firstMs else update.firstMs,
            count = maxOf(old.count, update.count)
        )
        entries[update.id] = merged
        return merged
    }

    /** 用 runtime 的全量结果覆盖（errors 命令）。 */
    @Synchronized
    fun replace(items: List<LuaErrorDto>, dropped: Long) {
        entries.clear()
        items.forEach { entries[it.id] = it }
        this.dropped = dropped
    }

    @Synchronized
    fun clear() {
        entries.clear()
        dropped = 0
    }

    /** 按最近出现时间倒序。 */
    @Synchronized
    fun snapshot(): List<LuaErrorDto> = entries.values.sortedWith(compareByDescending<LuaErrorDto> { it.lastMs }.thenByDescending { it.id })

    @Synchronized
    fun dropped(): Long = dropped
}
