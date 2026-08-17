package com.tang.intellij.lua.debugger.core

import java.util.concurrent.CancellationException

/** Registers a request before sending it, then owns completion, timeout and cancellation. */
class RequestBroker<T>(
    private val registry: RequestRegistry<T>
) : AutoCloseable {
    fun request(
        requestId: String,
        timeoutMillis: Long? = null,
        send: () -> Unit,
        callback: (Result<T>) -> Unit
    ) {
        try {
            if (timeoutMillis == null) {
                registry.register(requestId, callback = callback)
            } else {
                registry.register(requestId, timeoutMillis, callback)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            registry.deliverFailure(callback, error)
            return
        }
        try {
            send()
        } catch (cancellation: CancellationException) {
            registry.discard(requestId)
            throw cancellation
        } catch (error: Throwable) {
            registry.cancel(requestId, error)
        }
    }

    fun complete(requestId: String, value: T): Boolean = registry.complete(requestId, value)

    fun cancel(requestId: String, cause: Throwable): Boolean = registry.cancel(requestId, cause)

    fun cancelAll(cause: Throwable = CancellationException("Request broker closed")): Int =
        registry.cancelAll(cause)

    fun contains(requestId: String): Boolean = registry.contains(requestId)

    val pendingCount: Int
        get() = registry.pendingCount

    override fun close() {
        registry.close()
    }
}
