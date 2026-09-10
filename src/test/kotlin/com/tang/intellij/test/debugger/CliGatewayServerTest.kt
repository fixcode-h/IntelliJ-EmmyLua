package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.CliGatewayServer
import com.tang.intellij.lua.debugger.cli.CliGatewayService
import com.tang.intellij.lua.debugger.cli.CliTargetProvider
import com.tang.intellij.lua.debugger.cli.CliTargetSummary
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import java.net.Socket
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.IOException
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CliGatewayServerTest {
    @Test
    fun `windows named pipe serves authenticated JSONL requests`() {
        assumeTrue("named pipe test requires Windows", System.getProperty("os.name").startsWith("Windows", true))
        System.setProperty("jna.nosys", "true")
        System.setProperty("jna.boot.library.path", File("build/ipcsocket-native").absoluteFile.path)
        val gateway = CliGatewayService(CliTargetProvider {
            listOf(CliTargetSummary("target-pipe", "Demo", "RUNNING", true))
        })
        val server = CliGatewayServer(gateway, "pipe-token")
        val endpoint = try {
            server.startNamedPipe("emmylua-test-${System.nanoTime()}")
        } catch (error: Throwable) {
            server.close()
            throw AssertionError("Windows named pipe provider unavailable", error)
        }
        try {
            val socket = connectNamedPipeWithRetry(endpoint.host)
            socket.use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                writer.write("{\"token\":\"pipe-token\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("authenticated"))
                writer.write("{\"requestId\":\"pipe-r1\",\"operation\":\"target.list\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("target-pipe"))
            }
        } finally {
            server.close()
        }
    }

    private fun connectNamedPipeWithRetry(name: String): Win32NamedPipeSocket {
        var last: IOException? = null
        repeat(20) {
            try {
                return Win32NamedPipeSocket("\\\\.\\pipe\\$name")
            } catch (error: IOException) {
                last = error
                Thread.sleep(50)
            }
        }
        throw last ?: IOException("named pipe connection failed")
    }

    @Test
    fun `named pipe endpoint accepts canonical and short names`() {
        assertEquals("emmylua-test", CliGatewayServer.normalizePipeName("emmylua-test"))
        assertEquals("emmylua.test", CliGatewayServer.normalizePipeName("\\\\.\\pipe\\emmylua.test"))
    }

    @Test
    fun `named pipe endpoint rejects paths and unsafe names`() {
        assertThrows(IllegalArgumentException::class.java) { CliGatewayServer.normalizePipeName("..\\pipe") }
        assertThrows(IllegalArgumentException::class.java) { CliGatewayServer.normalizePipeName("has space") }
        assertThrows(IllegalArgumentException::class.java) { CliGatewayServer.normalizePipeName("a".repeat(81)) }
    }

    @Test
    fun `loopback server authenticates and serves JSONL requests`() {
        val gateway = CliGatewayService(CliTargetProvider {
            listOf(CliTargetSummary("target-1", "Demo", "RUNNING", true))
        })
        val server = CliGatewayServer(gateway, "secret-token")
        val endpoint = server.start()
        try {
            val socket = try {
                Socket(endpoint.host, endpoint.port)
            } catch (error: ConnectException) {
                // Some sandboxed runners deny JVM loopback sockets; keep the
                // pure protocol/Gateway tests authoritative in that case.
                assumeNoException("loopback socket unavailable", error)
                return
            }
            socket.use { socket ->
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                writer.write("{\"token\":\"secret-token\"}\n")
                writer.flush()
                assertTrue(reader.readLine().contains("authenticated"))
                writer.write("{\"requestId\":\"r1\",\"operation\":\"target.list\"}\n")
                writer.flush()
                assertEquals(true, reader.readLine().contains("target-1"))
            }
        } finally {
            server.close()
        }
    }
}
