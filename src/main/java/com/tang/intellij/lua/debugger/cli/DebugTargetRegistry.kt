package com.tang.intellij.lua.debugger.cli

import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-level registry for debugger adapters. Only immutable DTOs leave the
 * registry; XDebugger and Swing objects stay behind the adapter boundary.
 */
class DebugTargetRegistry : CliTargetProvider, AutoCloseable {
    private data class Entry(val adapter: DebugTargetAdapter, val journal: CliEventJournal)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(CliEventRecord) -> Unit>()

    fun register(adapter: DebugTargetAdapter, journal: CliEventJournal = CliEventJournal()): Boolean {
        val entry = Entry(adapter, journal)
        return entries.putIfAbsent(adapter.targetId, entry) == null
    }

    fun replace(adapter: DebugTargetAdapter, journal: CliEventJournal = CliEventJournal()): Unit {
        entries[adapter.targetId] = Entry(adapter, journal)
    }

    fun unregister(targetId: String): Boolean {
        val entry = entries[targetId] ?: return false
        // Append before removing the entry so clients already waiting on this
        // target's journal receive the terminal lifecycle event.
        val event = entry.journal.append("target.removed", targetId)
        listeners.forEach { listener -> runCatching { listener(event) } }
        return entries.remove(targetId, entry)
    }

    fun adapter(targetId: String): DebugTargetAdapter? = entries[targetId]?.adapter

    fun journal(targetId: String): CliEventJournal? = entries[targetId]?.journal

    fun addListener(listener: (CliEventRecord) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(
        targetId: String,
        type: String,
        vmId: String? = null,
        pauseId: Long? = null,
        payload: Map<String, Any?> = emptyMap()
    ): CliEventRecord? {
        val entry = entries[targetId] ?: return null
        val event = entry.journal.append(type, targetId, vmId, pauseId, payload)
        listeners.forEach { listener -> runCatching { listener(event) } }
        return event
    }

    override fun targets(): List<CliTargetSummary> = entries.values
        .map { runCatching { it.adapter.describe() }.getOrElse { failure ->
            CliTargetSummary(it.adapter.targetId, "<unavailable>", "ERROR", false)
        } }
        .sortedBy { it.targetId }

    fun adapters(): List<DebugTargetAdapter> = entries.values.map { it.adapter }.sortedBy { it.targetId }

    override fun close() {
        entries.keys.toList().forEach(::unregister)
        listeners.clear()
    }
}

/** Bounded, cursor-based event journal with cancellable waiters. */
class CliEventJournal(
    private val maxEntries: Int = 1_000,
    private val maxBytes: Long = 10L * 1024L * 1024L,
    private val gson: Gson = Gson()
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed: Condition = lock.newCondition()
    private val events = ArrayDeque<CliEventRecord>()
    private var bytes = 0L
    private var nextCursor = 1L
    private var cancellationGeneration = 0L
    private var closed = false

    init {
        require(maxEntries > 0)
        require(maxBytes > 0)
    }

    fun append(
        type: String,
        targetId: String,
        vmId: String? = null,
        pauseId: Long? = null,
        payload: Map<String, Any?> = emptyMap()
    ): CliEventRecord = lock.withLock {
        check(!closed) { "event journal is closed" }
        val event = CliEventRecord(nextCursor++, type, targetId, vmId, pauseId, payload.toMap())
        events.addLast(event)
        bytes += estimate(event)
        trim()
        changed.signalAll()
        event
    }

    fun readAfter(cursor: Long, limit: Int = 100): Result<CliEventPage> = lock.withLock {
        validateLimit(limit).getOrElse { return Result.failure(it) }
        pageLocked(cursor, limit)
    }

    /** Wait until at least one event after cursor exists or timeout/cancel occurs. */
    fun awaitAfter(
        cursor: Long,
        timeoutMillis: Long,
        limit: Int = 100,
        cancellation: (() -> Boolean)? = null
    ): Result<CliEventPage> {
        validateLimit(limit).onFailure { return Result.failure(it) }
        if (timeoutMillis <= 0) return Result.failure(IllegalArgumentException(CliErrorCodes.TIMEOUT))
        val timeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val startNanos = System.nanoTime()
        val deadline = if (Long.MAX_VALUE - startNanos < timeoutNanos) {
            Long.MAX_VALUE
        } else {
            startNanos + timeoutNanos
        }
        lock.withLock {
            val waiterGeneration = cancellationGeneration
            while (true) {
                if (closed || cancellationGeneration != waiterGeneration || cancellation?.invoke() == true) {
                    return Result.failure(InterruptedException(CliErrorCodes.CANCELLED))
                }
                val page = pageLocked(cursor, limit)
                if (page.isFailure) return page
                val value = page.getOrThrow()
                if (value.events.isNotEmpty()) return Result.success(value)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return Result.failure(java.util.concurrent.TimeoutException(CliErrorCodes.TIMEOUT))
                try {
                    changed.awaitNanos(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.failure(interrupted)
                }
            }
        }
    }

    fun cancelWaiters() {
        lock.withLock {
            cancellationGeneration++
            changed.signalAll()
        }
    }

    /** Wakes waiters without cancelling them; request-scoped cancellation uses this. */
    fun signalWaiters() = lock.withLock { changed.signalAll() }

    /** Kept for source compatibility; cancellation is generation based now. */
    fun resetCancellation() = Unit

    fun size(): Int = lock.withLock { events.size }
    fun oldestCursor(): Long = lock.withLock { events.firstOrNull()?.cursor ?: nextCursor }
    fun latestCursor(): Long = lock.withLock { events.lastOrNull()?.cursor ?: (nextCursor - 1).coerceAtLeast(0) }

    override fun close() {
        lock.withLock {
            closed = true
            cancellationGeneration++
            changed.signalAll()
        }
    }

    private fun pageLocked(cursor: Long, limit: Int): Result<CliEventPage> {
        if (cursor < 0) return Result.failure(IllegalArgumentException("invalid cursor"))
        val oldest = events.firstOrNull()?.cursor ?: nextCursor
        val latest = events.lastOrNull()?.cursor ?: (nextCursor - 1).coerceAtLeast(0)
        if (events.isNotEmpty() && cursor < oldest - 1) {
            return Result.failure(IllegalStateException(CliErrorCodes.EVENT_CURSOR_EXPIRED))
        }
        val selected = events.asSequence().filter { it.cursor > cursor }.take(limit).toList()
        val next = selected.lastOrNull()?.cursor ?: cursor
        return Result.success(CliEventPage(selected, next, oldest, latest, latest > next))
    }

    private fun trim() {
        while (events.size > maxEntries || bytes > maxBytes) {
            val removed = events.removeFirstOrNull() ?: break
            bytes -= estimate(removed)
        }
    }

    private fun estimate(event: CliEventRecord): Long = gson.toJson(event).toByteArray(Charsets.UTF_8).size.toLong()

    private fun validateLimit(limit: Int): Result<Unit> = if (limit in 1..1_000) {
        Result.success(Unit)
    } else {
        Result.failure(IllegalArgumentException("limit must be between 1 and 1000"))
    }
}
