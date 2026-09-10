package com.tang.intellij.emmydebug

import com.tang.intellij.lua.debugger.cli.CliInstanceDescriptor
import com.tang.intellij.lua.debugger.cli.CliRequest
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun `actual CLI rejects missing empty and oversized token before connecting`() {
        val dir = Files.createTempDirectory("emmy-debug-token")
        val token = dir.resolve("token")
        fun reject(message: String) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runCli(arrayOf("target", "list", "--endpoint", "tcp://127.0.0.1:1",
                    "--token-file", token.toString()))
            }
            assertEquals(message, error.message)
        }
        try {
            reject("token file does not exist")
            Files.writeString(token, " ")
            reject("token file is empty")
            Files.writeString(token, "x".repeat(4097))
            reject("token file has an invalid size")
        } finally {
            Files.deleteIfExists(token)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `actual probe CLI preserves comma keys repeated captures and unverified local source`() {
        val dir = Files.createTempDirectory("emmy-debug-command")
        val token = Files.writeString(dir.resolve("token"), "secret\n")
        val source = Files.writeString(dir.resolve("runtime.lua"), "local value = 42")
        val received = java.util.concurrent.CompletableFuture<com.google.gson.JsonObject>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val worker = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        check(reader.readLine().contains("secret"))
                        writer.write("{\"ok\":true,\"authenticated\":true}\n")
                        writer.flush()
                        received.complete(JsonParser.parseString(reader.readLine()).asJsonObject)
                        writer.write("{\"requestId\":\"capture-test\",\"ok\":true,\"data\":{}}\n")
                        writer.flush()
                    }
                } catch (error: Throwable) { received.completeExceptionally(error) }
            }.apply { isDaemon = true; start() }
            try {
                assertEquals(0, runCli(arrayOf("probe", "run", "--endpoint", "tcp://127.0.0.1:${server.localPort}",
                    "--token-file", token.toString(), "--request-id", "capture-test", "--target", "target",
                    "--vm", "vm", "--lease", "lease", "--file", source.toString(), "--line", "3",
                    "--capture", "value[\"a,b\"],value.answer", "--capture", "value[\"a.b\"].nested")))
                val request = received.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("probe.run", request["operation"].asString)
                val arguments = request["arguments"].asJsonObject
                assertEquals(listOf("value[\"a,b\"]", "value.answer", "value[\"a.b\"].nested"),
                    arguments["captures"].asJsonArray.map { it.asString })
                assertEquals(false, arguments["sourceIdentity"].asJsonObject["verified"].asBoolean)
            } finally {
                worker.join(6000)
                Files.deleteIfExists(source)
                Files.deleteIfExists(token)
                Files.deleteIfExists(dir)
            }
        }
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

    @Test
    fun `wait returns nonzero for terminal error`() {
        assertEquals(5, runWaitWithTerminal("{\"requestId\":\"wait-test\",\"ok\":false,\"error\":{\"code\":\"TIMEOUT\",\"message\":\"timed out\"},\"done\":true}"))
    }

    @Test
    fun `wait returns zero for successful terminal done`() {
        assertEquals(0, runWaitWithTerminal("{\"requestId\":\"wait-test\",\"ok\":true,\"data\":{\"reason\":\"EVENTS_AVAILABLE\"},\"done\":true}"))
    }

    private fun runWaitWithTerminal(terminal: String): Int {
        val dir = Files.createTempDirectory("emmy-debug-wait")
        val token = Files.writeString(dir.resolve("token"), "secret")
        ServerSocket(0).use { server ->
            val thread = Thread {
                server.use { listener -> listener.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    check(reader.readLine().contains("secret"))
                    writer.write("{\"ok\":true,\"authenticated\":true}\n")
                    writer.flush()
                    check(reader.readLine().contains("\"operation\":\"wait\""))
                    writer.write(terminal + "\n")
                    writer.flush()
                } }
            }.apply { isDaemon = true; start() }
            try {
                return runCli(arrayOf("wait", "--endpoint", "tcp://127.0.0.1:${server.localPort}",
                    "--token-file", token.toString(), "--target", "target", "--request-id", "wait-test"))
            } finally {
                thread.join(2_000)
                Files.deleteIfExists(token)
                Files.deleteIfExists(dir)
            }
        }
    }

}
