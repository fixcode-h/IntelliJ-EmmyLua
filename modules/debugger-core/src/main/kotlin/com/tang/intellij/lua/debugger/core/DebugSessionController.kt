package com.tang.intellij.lua.debugger.core

import java.util.concurrent.CompletableFuture

/** Backend-neutral owner of one debugger session's serialized lifecycle. */
class DebugSessionController(
    threadName: String,
    transitionListener: (DebugSessionTransition) -> Unit = {},
    errorHandler: (Throwable) -> Unit = {}
) : AutoCloseable {
    private val eventLoop = DebugSessionEventLoop(threadName, transitionListener, errorHandler)

    val state: DebugSessionState
        get() = eventLoop.state

    val generation: Long
        get() = eventLoop.generation

    fun start(): Long = eventLoop.start()

    fun post(token: Long, event: DebugSessionEvent, action: () -> Unit = {}) =
        eventLoop.post(token, event, action)

    fun execute(token: Long, action: () -> Unit) = eventLoop.execute(token, action)

    fun awaitIdle(): CompletableFuture<Unit> = eventLoop.awaitIdle()

    override fun close() = eventLoop.close()
}
