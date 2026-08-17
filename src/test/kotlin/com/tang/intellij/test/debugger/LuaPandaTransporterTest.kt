package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.luapanda.LuaPandaConnectionEvent
import com.tang.intellij.lua.debugger.luapanda.LuaPandaConnectionState
import com.tang.intellij.lua.debugger.luapanda.LuaPandaMessage
import com.tang.intellij.lua.debugger.luapanda.LuaPandaTcpClientTransporter
import com.tang.intellij.lua.debugger.luapanda.LuaPandaTransporter
import com.tang.intellij.lua.debugger.core.DebugSessionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LuaPandaTransporterTest {
    @Test
    fun `client reconnect emits ordered epochs`() {
        val server = ServerSocket(0)
        val events = Collections.synchronizedList(mutableListOf<LuaPandaConnectionEvent>())
        val firstConnected = CountDownLatch(1)
        val secondConnected = CountDownLatch(1)
        val reconnecting = CountDownLatch(1)
        val serverThread = Thread {
            server.accept().use {
                assertTrue(firstConnected.await(5, TimeUnit.SECONDS))
            }
            server.accept().use {
                assertTrue(secondConnected.await(5, TimeUnit.SECONDS))
            }
        }
        val transporter = LuaPandaTcpClientTransporter("127.0.0.1", server.localPort, true)
        transporter.setConnectionHandler { event ->
            events += event
            when (event.state) {
                LuaPandaConnectionState.CONNECTED -> {
                    if (event.connectionEpoch == 1L) firstConnected.countDown() else secondConnected.countDown()
                }
                LuaPandaConnectionState.RECONNECTING -> reconnecting.countDown()
                LuaPandaConnectionState.DISCONNECTED -> Unit
            }
        }

        serverThread.start()
        transporter.start()
        try {
            assertTrue(firstConnected.await(5, TimeUnit.SECONDS))
            assertTrue(reconnecting.await(5, TimeUnit.SECONDS))
            assertTrue(secondConnected.await(5, TimeUnit.SECONDS))

            val connectedEpochs = events
                .filter { it.state == LuaPandaConnectionState.CONNECTED }
                .map { it.connectionEpoch }
            assertEquals(listOf(1L, 2L), connectedEpochs)
            val states = events.map { it.state }
            assertTrue(states.indexOf(LuaPandaConnectionState.RECONNECTING) > states.indexOf(LuaPandaConnectionState.CONNECTED))
            assertEquals(2L, transporter.connectionEpoch)
        } finally {
            transporter.stop()
            server.close()
            serverThread.join(5_000)
        }
    }

    @Test
    fun `send failure cancels request and dispatches failure`() {
        val transporter = DisconnectedTransporter()
        val failure = AtomicReference<Throwable>()
        val dispatched = AtomicReference<(() -> Unit)>()
        transporter.setEventDispatcher { action -> dispatched.set(action) }

        transporter.commandToDebugger(
            cmd = "test",
            callbackFunc = { error("response must not be delivered") },
            failureFunc = { failure.set(it) }
        )

        assertEquals(null, failure.get())
        assertNotNull(dispatched.get())
        dispatched.get().invoke()
        assertTrue(failure.get() is IOException)
        assertEquals(0, transporter.pendingRequestCount)
    }

    @Test
    fun `shutdown barrier delivers cancelled request before lifecycle closes`() {
        val lifecycle = DebugSessionController("luapanda-callback-test")
        val generation = lifecycle.start()
        val transporter = ConnectedTransporter()
        val failureDelivered = CountDownLatch(1)
        transporter.setEventDispatcher { action -> lifecycle.execute(generation, action) }
        transporter.commandToDebugger(
            cmd = "pending",
            callbackFunc = { error("response must not be delivered") },
            failureFunc = { failureDelivered.countDown() }
        )

        lifecycle.execute(generation) {
            transporter.clearCallbacks()
            lifecycle.awaitIdle().whenComplete { _, _ -> lifecycle.close() }
        }

        assertTrue(failureDelivered.await(2, TimeUnit.SECONDS))
        lifecycle.awaitIdle().get(2, TimeUnit.SECONDS)
    }

    private class DisconnectedTransporter : LuaPandaTransporter() {
        override val writer: PrintWriter? = null
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(message: LuaPandaMessage) = Unit
    }

    private class ConnectedTransporter : LuaPandaTransporter() {
        override val writer = PrintWriter(StringWriter())
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(message: LuaPandaMessage) = Unit
    }
}
