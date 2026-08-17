package com.tang.intellij.lua.debugger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
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
    fun `failure handler can close the loop without stranding waiters`() {
        val cleaned = CountDownLatch(1)
        lateinit var loop: DebugSessionEventLoop
        loop = DebugSessionEventLoop("event-loop-test", errorHandler = {
            loop.close()
            cleaned.countDown()
        })
        val generation = loop.start()

        loop.execute(generation) { error("fatal callback") }

        assertTrue(cleaned.await(2, TimeUnit.SECONDS))
        loop.awaitIdle().get(2, TimeUnit.SECONDS)
        assertEquals(DebugSessionState.FAILED, loop.state)
    }

    @Test
    fun `cancellation does not fail the session`() {
        val failure = AtomicBoolean()
        DebugSessionEventLoop("event-loop-test", errorHandler = { failure.set(true) }).use { loop ->
            val generation = loop.start()
            loop.execute(generation) { throw CancellationException("cancelled") }
            loop.awaitIdle().get(2, TimeUnit.SECONDS)

            assertFalse(failure.get())
            assertEquals(DebugSessionState.PREPARING, loop.state)
        }
    }

    @Test
    fun `await idle completes after close`() {
        val loop = DebugSessionEventLoop("event-loop-test")
        loop.start()
        loop.close()

        loop.awaitIdle().get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `close completes an await idle barrier queued behind running work`() {
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val loop = DebugSessionEventLoop("event-loop-test")
        val generation = loop.start()
        loop.execute(generation) {
            workStarted.countDown()
            try {
                releaseWork.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        assertTrue(workStarted.await(2, TimeUnit.SECONDS))
        val idle = loop.awaitIdle()
        loop.close()
        releaseWork.countDown()

        idle.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `concurrent producers are serialized on one session thread`() {
        DebugSessionEventLoop("event-loop-test").use { loop ->
            val generation = loop.start()
            val producers = Executors.newFixedThreadPool(8)
            val activeActions = java.util.concurrent.atomic.AtomicInteger()
            val maxActiveActions = java.util.concurrent.atomic.AtomicInteger()
            try {
                val submissions = (1..200).map {
                    producers.submit {
                        loop.execute(generation) {
                            val active = activeActions.incrementAndGet()
                            maxActiveActions.accumulateAndGet(active, ::maxOf)
                            activeActions.decrementAndGet()
                        }
                    }
                }
                submissions.forEach { it.get(2, TimeUnit.SECONDS) }
                loop.awaitIdle().get(2, TimeUnit.SECONDS)

                assertEquals(1, maxActiveActions.get())
            } finally {
                producers.shutdownNow()
            }
        }
    }
}
