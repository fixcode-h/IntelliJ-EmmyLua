package com.tang.intellij.lua.debugger.cli

import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CliServerEndpoint(val host: String, val port: Int)

/** Loopback JSONL transport. A future named-pipe adapter can reuse the same Gateway. */
class CliGatewayServer(
    private val gateway: CliGatewayService,
    private val token: String,
    private val maxClients: Int = 8
) : AutoCloseable {
    private val running = AtomicBoolean()
    private val clients: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start(): CliServerEndpoint {
        check(running.compareAndSet(false, true)) { "CLI server is already running" }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        clients.submit {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                clients.submit { serve(client) }
            }
        }
        return CliServerEndpoint("127.0.0.1", socket.localPort)
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val writer = client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            if (!authenticate(reader, writer)) return
            while (running.get()) {
                val line = reader.readLine() ?: return
                val response = runCatching {
                    gateway.handle(CliJsonLines.decodeRequest(line))
                }.getOrElse { error ->
                    CliJsonLines.error("", "INVALID_REQUEST", error.message ?: "invalid request")
                }
                writer.write(CliJsonLines.encode(response))
                writer.newLine()
                writer.flush()
            }
        }
    }

    private fun authenticate(reader: BufferedReader, writer: BufferedWriter): Boolean {
        val line = reader.readLine() ?: return false
        val supplied = runCatching {
            JsonParser.parseString(line).asJsonObject.get("token")?.asString
        }.getOrNull()
        val accepted = supplied != null && constantTimeEquals(token, supplied)
        val response = if (accepted) {
            "{\"ok\":true,\"authenticated\":true}"
        } else {
            "{\"ok\":false,\"error\":{\"code\":\"NOT_AUTHORIZED\",\"message\":\"invalid CLI token\"}}"
        }
        writer.write(response)
        writer.newLine()
        writer.flush()
        return accepted
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        var difference = if (expected.length == actual.length) 0 else 1
        val max = maxOf(expected.length, actual.length)
        for (index in 0 until max) {
            val left = if (index < expected.length) expected[index].code else 0
            val right = if (index < actual.length) actual[index].code else 0
            difference = difference or (left xor right)
        }
        return difference == 0
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.shutdownNow()
    }
}
