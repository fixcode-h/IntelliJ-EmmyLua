package com.tang.intellij.test.debugger

import com.tang.intellij.lua.debugger.cli.CliGatewayServer
import com.tang.intellij.lua.debugger.cli.CliGatewayService
import com.tang.intellij.lua.debugger.cli.CliTargetProvider
import com.tang.intellij.lua.debugger.cli.CliTargetSummary
import java.net.Socket
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import org.junit.Assume.assumeNoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliGatewayServerTest {
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
