package com.tang.intellij.lua.debugger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RequestRegistryTest {
    @Test
    fun `complete delivers exactly once`() {
        RequestRegistry<String>(1_000).use { registry ->
            val value = AtomicReference<String>()
            registry.register("1") { result -> value.set(result.getOrThrow()) }

            assertTrue(registry.complete("1", "done"))
            assertFalse(registry.complete("1", "again"))
            assertEquals("done", value.get())
            assertEquals(0, registry.pendingCount)
        }
    }

    @Test
    fun `timeout fails and removes request`() {
        RequestRegistry<String>(25).use { registry ->
            val latch = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            registry.register("slow") { result ->
                failure.set(result.exceptionOrNull())
                latch.countDown()
            }

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() is TimeoutException)
            assertEquals(0, registry.pendingCount)
        }
    }

    @Test
    fun `cancel and cancelAll preserve their causes`() {
        RequestRegistry<String>(1_000).use { registry ->
            val firstCause = IllegalStateException("cancel one")
            val allCause = IllegalArgumentException("disconnect")
            val firstFailure = AtomicReference<Throwable>()
            val remainingFailures = mutableListOf<Throwable?>()

            registry.register("first") { firstFailure.set(it.exceptionOrNull()) }
            registry.register("second") { remainingFailures += it.exceptionOrNull() }
            registry.register("third") { remainingFailures += it.exceptionOrNull() }

            assertTrue(registry.cancel("first", firstCause))
            assertEquals(2, registry.cancelAll(allCause))
            assertSame(firstCause, firstFailure.get())
            assertEquals(listOf(allCause, allCause), remainingFailures)
            assertEquals(0, registry.pendingCount)
        }
    }

    @Test
    fun `callback failure is isolated`() {
        val callbackErrors = AtomicInteger()
        RequestRegistry<String>(1_000, callbackErrorHandler = { callbackErrors.incrementAndGet() }).use { registry ->
            registry.register("bad") { error("callback failed") }
            registry.register("good") { assertEquals("ok", it.getOrThrow()) }

            assertTrue(registry.complete("bad", "ignored"))
            assertTrue(registry.complete("good", "ok"))
            assertEquals(1, callbackErrors.get())
        }
    }

    @Test
    fun `callback cancellation is not swallowed`() {
        RequestRegistry<String>(1_000).use { registry ->
            registry.register("cancelled") { throw CancellationException("cancelled") }

            org.junit.Assert.assertThrows(CancellationException::class.java) {
                registry.complete("cancelled", "ignored")
            }
            assertEquals(0, registry.pendingCount)
        }
    }

    @Test
    fun `cancel all drains every request before propagating callback cancellation`() {
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val registry = RequestRegistry<String>(1_000, scheduler = scheduler)
        val secondDelivered = AtomicInteger()
        registry.register("cancelling") { throw CancellationException("callback cancelled") }
        registry.register("remaining") { secondDelivered.incrementAndGet() }

        org.junit.Assert.assertThrows(CancellationException::class.java) { registry.close() }

        assertEquals(1, secondDelivered.get())
        assertEquals(0, registry.pendingCount)
        assertTrue(scheduler.isShutdown)
    }
}
