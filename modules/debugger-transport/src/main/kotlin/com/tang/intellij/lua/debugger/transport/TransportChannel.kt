package com.tang.intellij.lua.debugger.transport

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

interface TransportChannel<M> : AutoCloseable {
    fun start()
    fun send(message: M)
}

enum class TransportConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

data class TransportConnectionEvent(
    val state: TransportConnectionState,
    val connectionEpoch: Long,
    val retryAttempt: Int = 0,
    val cause: Throwable? = null
)

class ConnectionEpochTracker {
    private val counter = AtomicLong()

    val current: Long
        get() = counter.get()

    fun next(): Long = counter.incrementAndGet()
}

class TransportQueueFullException(capacity: Int) :
    IOException("Transport send queue is full (capacity=$capacity)")

class BoundedTransportQueue<M>(private val capacity: Int = 1_024) {
    private val queue: ArrayBlockingQueue<M>

    init {
        require(capacity > 0) { "capacity must be positive" }
        queue = ArrayBlockingQueue(capacity)
    }

    fun offer(message: M) {
        if (!queue.offer(message)) throw TransportQueueFullException(capacity)
    }

    fun force(message: M) {
        while (!queue.offer(message)) queue.poll()
    }

    fun take(): M = queue.take()

    fun clear() = queue.clear()

    val size: Int
        get() = queue.size
}
