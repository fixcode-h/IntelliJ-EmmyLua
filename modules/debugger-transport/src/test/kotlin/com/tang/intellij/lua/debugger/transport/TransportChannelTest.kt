package com.tang.intellij.lua.debugger.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportChannelTest {
    @Test
    fun `connection epochs only increase`() {
        val epochs = ConnectionEpochTracker()
        assertEquals(0, epochs.current)
        assertEquals(1, epochs.next())
        assertEquals(2, epochs.next())
        assertEquals(2, epochs.current)
    }

    @Test
    fun `bounded queue rejects overflow and force makes room for shutdown`() {
        val queue = BoundedTransportQueue<String>(2)
        queue.offer("first")
        queue.offer("second")
        assertThrows(TransportQueueFullException::class.java) { queue.offer("overflow") }

        queue.force("stop")

        assertEquals("second", queue.take())
        assertEquals("stop", queue.take())
    }
}
