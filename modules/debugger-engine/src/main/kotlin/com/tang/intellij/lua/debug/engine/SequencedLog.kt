package com.tang.intellij.lua.debug.engine

/** 定长的带序号记录（输出、Probe 命中）。线程安全：会话线程写，MCP 线程按 sinceSeq 读。 */
class SequencedLog<T>(private val capacity: Int) {
    private val items = ArrayDeque<Sequenced<T>>()
    private var nextSeq = 1L

    @Synchronized
    fun add(value: T, timeMillis: Long = System.currentTimeMillis()): Sequenced<T> {
        val item = Sequenced(nextSeq++, timeMillis, value)
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
        return item
    }

    /** seq 大于 [sinceSeq] 的记录，最多 [limit] 条（从旧到新）。 */
    @Synchronized
    fun since(sinceSeq: Long = 0, limit: Int = Int.MAX_VALUE): List<Sequenced<T>> =
        items.asSequence().filter { it.seq > sinceSeq }.take(limit).toList()

    @get:Synchronized
    val lastSeq: Long get() = nextSeq - 1
}
