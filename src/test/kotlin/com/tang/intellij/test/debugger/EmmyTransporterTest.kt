package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.emmy.ITransportHandler
import com.tang.intellij.lua.debugger.emmy.Message
import com.tang.intellij.lua.debugger.emmy.MessageCMD
import com.tang.intellij.lua.debugger.emmy.SocketChannelTransporter
import com.tang.intellij.test.LuaTestBase
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EmmyTransporterTest : LuaTestBase() {
    fun testWriteFailureClosesConnectionAndNotifiesDisconnectOnce() {
        val transporter = FailingWriteTransporter()
        val disconnects = AtomicInteger()
        val disconnected = CountDownLatch(1)
        transporter.handler = object : ITransportHandler {
            override fun onReceiveMessage(cmd: MessageCMD, json: String) = Unit
            override fun onDisconnect() {
                disconnects.incrementAndGet()
                disconnected.countDown()
            }

            override fun onConnect(suc: Boolean) = Unit
        }

        transporter.start()
        transporter.send(Message(MessageCMD.ReadyReq))

        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertEquals(1, disconnects.get())
        transporter.close()
    }

    private class FailingWriteTransporter : SocketChannelTransporter() {
        private val output = PipedOutputStream()
        private val input = PipedInputStream(output)

        override fun start() = run()

        override fun getInputStream(): InputStream = input

        override fun write(ba: ByteArray) {
            throw IOException("simulated write failure")
        }

        override fun close() {
            super.close()
            output.close()
            input.close()
        }
    }
}
