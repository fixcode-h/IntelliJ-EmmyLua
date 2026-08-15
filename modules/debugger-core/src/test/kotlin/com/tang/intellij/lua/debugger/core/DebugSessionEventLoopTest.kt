package com.tang.intellij.lua.debugger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DebugSessionEventLoopTest {
    @Test
    fun `stale generation event is discarded`() {
        DebugSessionEventLoop("event-loop-test").use { loop ->
            val generation = loop.start()
            loop.awaitIdle().get(2, TimeUnit.SECONDS)
            val executed = AtomicBoolean()

            loop.execute(generation + 1) { executed.set(true) }
            loop.awaitIdle().get(2, TimeUnit.SECONDS)

            assertFalse(executed.get())
            assertEquals(DebugSessionState.PREPARING, loop.state)
        }
    }

    @Test
    fun `duplicate stop is idempotent`() {
        val transitions = CopyOnWriteArrayList<DebugSessionTransition>()
        DebugSessionEventLoop("event-loop-test", transitions::add).use { loop ->
            val generation = loop.start()
            loop.post(generation, DebugSessionEvent.STOP_REQUESTED)
            loop.post(generation, DebugSessionEvent.STOP_REQUESTED)
            loop.awaitIdle().get(2, TimeUnit.SECONDS)

            assertEquals(DebugSessionState.STOPPING, loop.state)
            assertEquals(1, transitions.count { it.event == DebugSessionEvent.STOP_REQUESTED })
        }
    }

    @Test
    fun `callback failure moves session to failed`() {
        val failure = CountDownLatch(1)
        DebugSessionEventLoop("event-loop-test", errorHandler = { failure.countDown() }).use { loop ->
            val generation = loop.start()
            loop.execute(generation) { error("protocol callback failed") }

            assertTrue(failure.await(2, TimeUnit.SECONDS))
            loop.awaitIdle().get(2, TimeUnit.SECONDS)
            assertEquals(DebugSessionState.FAILED, loop.state)
        }
    }

    @Test
    fun `await idle completes after close`() {
        val loop = DebugSessionEventLoop("event-loop-test")
        loop.start()
        loop.close()

        loop.awaitIdle().get(2, TimeUnit.SECONDS)
    }
}
