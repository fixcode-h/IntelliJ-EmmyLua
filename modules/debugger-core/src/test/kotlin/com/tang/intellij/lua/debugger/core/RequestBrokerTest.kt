package com.tang.intellij.lua.debugger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class RequestBrokerTest {
    @Test
    fun `response delivered synchronously during send is not lost`() {
        RequestBroker(RequestRegistry<String>(1_000)).use { broker ->
            val result = AtomicReference<String>()

            broker.request(
                requestId = "fast",
                send = { assertTrue(broker.complete("fast", "done")) },
                callback = { result.set(it.getOrThrow()) }
            )

            assertEquals("done", result.get())
            assertEquals(0, broker.pendingCount)
        }
    }

    @Test
    fun `send failure completes exactly once without escaping`() {
        RequestBroker(RequestRegistry<String>(1_000)).use { broker ->
            val expected = IllegalStateException("send failed")
            val failure = AtomicReference<Throwable>()

            broker.request("failed", send = { throw expected }) { failure.set(it.exceptionOrNull()) }

            assertSame(expected, failure.get())
            assertFalse(broker.complete("failed", "late"))
            assertEquals(0, broker.pendingCount)
        }
    }

    @Test
    fun `registration failure is delivered without cancelling the existing request`() {
        RequestBroker(RequestRegistry<String>(1_000)).use { broker ->
            val failure = AtomicReference<Throwable>()
            broker.request("duplicate", send = {}) {}

            broker.request("duplicate", send = { error("must not send") }) {
                failure.set(it.exceptionOrNull())
            }

            assertTrue(failure.get() is IllegalStateException)
            assertEquals(1, broker.pendingCount)
        }
    }

    @Test
    fun `send cancellation is propagated without invoking the result callback`() {
        RequestBroker(RequestRegistry<String>(1_000)).use { broker ->
            val callbackInvoked = AtomicBoolean()

            org.junit.Assert.assertThrows(CancellationException::class.java) {
                broker.request("cancelled", send = { throw CancellationException("cancelled") }) {
                    callbackInvoked.set(true)
                }
            }

            assertFalse(callbackInvoked.get())
            assertEquals(0, broker.pendingCount)
        }
    }
}
