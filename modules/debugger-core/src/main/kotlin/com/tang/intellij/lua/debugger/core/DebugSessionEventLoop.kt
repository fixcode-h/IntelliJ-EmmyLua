package com.tang.intellij.lua.debugger.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Serializes lifecycle transitions and protocol callbacks for a debugger session. */
class DebugSessionEventLoop(
    threadName: String,
    private val transitionListener: (DebugSessionTransition) -> Unit = {},
    private val errorHandler: (Throwable) -> Unit = {}
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(ThreadFactory { task ->
        Thread(task, threadName).apply { isDaemon = true }
    })
    private val generationCounter = AtomicLong()
    private val currentGeneration = AtomicLong()
    private val stateMachine = DebugSessionStateMachine()
    private val stateRef = AtomicReference(DebugSessionState.CREATED)
    private val closed = AtomicBoolean()
    private val idleWaiters = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()

    val state: DebugSessionState
        get() = stateRef.get()

    val generation: Long
        get() = currentGeneration.get()

    fun start(): Long {
        check(currentGeneration.compareAndSet(0, generationCounter.incrementAndGet())) {
            "Debug session event loop is already started"
        }
        val token = currentGeneration.get()
        post(token, DebugSessionEvent.START)
        return token
    }

    fun post(token: Long, event: DebugSessionEvent, action: () -> Unit = {}) {
        submit(token) {
            val transition = stateMachine.transition(event) ?: return@submit
            stateRef.set(transition.current)
            transitionListener(transition)
            action()
        }
    }

    fun execute(token: Long, action: () -> Unit) {
        submit(token, action)
    }

    fun awaitIdle(): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        idleWaiters.add(future)
        if (closed.get()) {
            completeIdleWaiter(future)
            return future
        }
        val token = currentGeneration.get()
        if (!submit(token) { completeIdleWaiter(future) }) {
            completeIdleWaiter(future)
        }
        return future
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        currentGeneration.incrementAndGet()
        idleWaiters.toList().forEach(::completeIdleWaiter)
        executor.shutdownNow()
    }

    private fun submit(token: Long, action: () -> Unit): Boolean {
        if (closed.get() || token != currentGeneration.get()) return false
        try {
            executor.execute {
                if (closed.get() || token != currentGeneration.get()) return@execute
                try {
                    action()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    val transition = stateMachine.transition(DebugSessionEvent.FAILED)
                    if (transition != null) {
                        stateRef.set(transition.current)
                        transitionListener(transition)
                    }
                    errorHandler(error)
                }
            }
            return true
        } catch (error: RejectedExecutionException) {
            if (!closed.get()) errorHandler(error)
            return false
        }
    }

    private fun completeIdleWaiter(future: CompletableFuture<Unit>) {
        idleWaiters.remove(future)
        future.complete(Unit)
    }
}
