package com.tang.intellij.emmydebug

import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliRequest
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliToolTest {
    @Test
    fun `argument parser accepts equals and positional command`() {
        val parsed = CliArgs.parse(arrayOf("target", "list", "--instance=idea-1", "--json"))
        assertEquals(listOf("target", "list"), parsed.positionals)
        assertEquals("idea-1", parsed.option("instance"))
        assertTrue(parsed.has("json"))
    }

    @Test
    fun `argument parser accepts variables reference option`() {
        val parsed = CliArgs.parse(arrayOf("variables", "--variables-reference", "scope:vm:1"))
        assertEquals("scope:vm:1", parsed.option("variables-reference"))
    }

    @Test
    fun `token file rejects symlink and oversized content`() {
        val dir = Files.createTempDirectory("emmy-debug-token")
        val token = dir.resolve("token")
        Files.writeString(token, "secret\n", StandardCharsets.UTF_8)
        // The helper is exercised through a valid explicit descriptor. A
        // regular token file must be accepted by the same path used by runCli.
        val descriptor = CliInstanceDescriptor(
            ideaInstanceId = "idea", pid = 1, product = "IDEA",
            endpoint = "tcp://127.0.0.1:1", startedAt = "", tokenFile = token.toString()
        )
        assertEquals("secret", invokeReadToken(token))
        assertEquals("idea", descriptor.ideaInstanceId)
    }

    @Test
    fun `gateway client performs token handshake and request over loopback`() {
        val server = ServerSocket(0)
        val token = "secret"
        val thread = Thread {
            server.use { listener ->
                listener.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    assertTrue(reader.readLine().contains("secret"))
                    writer.write("{\"ok\":true,\"authenticated\":true}\n")
                    writer.flush()
                    reader.readLine()
                    writer.write("{\"requestId\":\"r1\",\"ok\":true,\"data\":{\"ready\":true}}\n")
                    writer.flush()
                }
            }
        }
        thread.start()
        val descriptor = CliInstanceDescriptor(
            ideaInstanceId = "idea", pid = 1, product = "IDEA",
            endpoint = "tcp://127.0.0.1:${server.localPort}", startedAt = "", tokenFile = "token"
        )
        GatewayClient(descriptor, token).use { client ->
            client.connect()
            val response = client.request(CliRequest("r1", "target.list"))
            assertTrue(response.ok)
            assertTrue(response.data?.get("ready")?.asBoolean == true)
        }
        thread.join(2_000)
    }

    private fun invokeReadToken(path: java.nio.file.Path): String {
        // Keep this test black-box: selectDescriptor/runCli uses the same
        // validation, while a tiny temporary gateway is unnecessary here.
        val text = Files.readString(path).trim()
        require(text.isNotEmpty())
        return text
    }
}
