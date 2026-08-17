package com.tang.intellij.lua.debugger.core

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Tracks protocol requests and guarantees that each request completes or fails exactly once. */
class RequestRegistry<T>(
    private val defaultTimeoutMillis: Long,
    private val scheduler: ScheduledExecutorService = newDaemonScheduler(),
    private val closeScheduler: Boolean = true,
    private val callbackErrorHandler: (Throwable) -> Unit = {}
) : AutoCloseable {
    private class Pending<T>(val callback: (Result<T>) -> Unit) {
        val timeout = AtomicReference<ScheduledFuture<*>?>()
    }

    private val pending = ConcurrentHashMap<String, Pending<T>>()

    init {
        require(defaultTimeoutMillis > 0) { "defaultTimeoutMillis must be positive" }
    }

    fun register(
        requestId: String,
        timeoutMillis: Long = defaultTimeoutMillis,
        callback: (Result<T>) -> Unit
    ) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val request = Pending(callback)
        check(pending.putIfAbsent(requestId, request) == null) {
            "Request '$requestId' is already registered"
        }
        val timeout = try {
            scheduler.schedule({
                if (pending.remove(requestId, request)) {
                    deliver(request, Result.failure(TimeoutException("Request '$requestId' timed out")))
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            pending.remove(requestId, request)
            throw error
        }
        request.timeout.set(timeout)
        if (pending[requestId] !== request) timeout.cancel(false)
    }

    fun complete(requestId: String, value: T): Boolean {
        val request = pending.remove(requestId) ?: return false
        request.timeout.get()?.cancel(false)
        deliver(request, Result.success(value))
        return true
    }

    fun cancel(requestId: String, cause: Throwable): Boolean {
        val request = pending.remove(requestId) ?: return false
        request.timeout.get()?.cancel(false)
        deliver(request, Result.failure(cause))
        return true
    }

    internal fun discard(requestId: String): Boolean {
        val request = pending.remove(requestId) ?: return false
        request.timeout.get()?.cancel(false)
        return true
    }

    fun cancelAll(cause: Throwable = CancellationException("Request registry closed")): Int {
        val requests = pending.entries.toList()
        var cancelled = 0
        var cancellation: CancellationException? = null
        for ((id, request) in requests) {
            if (pending.remove(id, request)) {
                request.timeout.get()?.cancel(false)
                cancelled++
                try {
                    deliver(request, Result.failure(cause))
                } catch (error: CancellationException) {
                    if (cancellation == null) cancellation = error
                }
            }
        }
        cancellation?.let { throw it }
        return cancelled
    }

    fun contains(requestId: String): Boolean = pending.containsKey(requestId)

    internal fun deliverFailure(callback: (Result<T>) -> Unit, cause: Throwable) {
        deliver(Pending(callback), Result.failure(cause))
    }

    val pendingCount: Int
        get() = pending.size

    override fun close() {
        try {
            cancelAll()
        } finally {
            if (closeScheduler) {
                scheduler.shutdownNow()
            }
        }
    }

    private fun deliver(request: Pending<T>, result: Result<T>) {
        try {
            request.callback(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            callbackErrorHandler(error)
        }
    }

    companion object {
        private fun newDaemonScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(ThreadFactory { task ->
                Thread(task, "EmmyLua-RequestTimeout").apply { isDaemon = true }
            })
    }
}
