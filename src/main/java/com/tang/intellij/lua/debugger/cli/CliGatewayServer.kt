package com.tang.intellij.lua.debugger.cli

import com.google.gson.JsonParser
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

data class CliServerEndpoint(
    val host: String,
    val port: Int,
    val endpoint: String = if (port > 0) "tcp://$host:$port" else host
)

/** Authenticated JSONL server. TCP is an explicit fallback; named pipe is preferred on Windows. */
class CliGatewayServer(
    private val gateway: CliGatewayService,
    private val token: String,
    private val maxClients: Int = 8,
    private val maxConcurrentRequests: Int = 16
) : AutoCloseable {
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val clients: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "EmmyLua-CliClient").apply { isDaemon = true }
    }
    private val requests: ThreadPoolExecutor = ThreadPoolExecutor(
        maxConcurrentRequests.coerceAtLeast(1), maxConcurrentRequests.coerceAtLeast(1),
        0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(maxConcurrentRequests.coerceAtLeast(1)),
        { task ->
        Thread(task, "EmmyLua-CliRequest").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()
    )
    private val clientSlots = Semaphore(maxClients.coerceAtLeast(1))
    private var serverSocket: ServerSocket? = null
    private val acceptedClients = CopyOnWriteArrayList<Socket>()

    @Synchronized
    fun start(): CliServerEndpoint {
        check(!closed.get()) { "CLI server is closed" }
        check(running.compareAndSet(false, true)) { "CLI server is already running" }
        return try {
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            acceptLoop(socket)
            CliServerEndpoint("127.0.0.1", socket.localPort)
        } catch (error: Throwable) {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            throw error
        }
    }

    /** Starts a Windows named-pipe endpoint. The name must be a pipe name, not a filesystem path. */
    @Synchronized
    fun startNamedPipe(name: String): CliServerEndpoint {
        val shortName = Companion.normalizePipeName(name)
        check(!closed.get()) { "CLI server is closed" }
        check(running.compareAndSet(false, true)) { "CLI server is already running" }
        return try {
            val socket = Win32NamedPipeServerSocket("\\\\.\\pipe\\$shortName")
            serverSocket = socket
            acceptLoop(socket)
            CliServerEndpoint(shortName, 0, "npipe://$shortName")
        } catch (error: Throwable) {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            throw error
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        clients.submit {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                } catch (_: Throwable) {
                    if (running.get()) continue else break
                }
                if (!clientSlots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                acceptedClients += client
                try {
                    clients.submit {
                        try { serve(client) } finally {
                            acceptedClients -= client
                            clientSlots.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    clientSlots.release()
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val waitIds = ConcurrentHashMap.newKeySet<Pair<String, String>>()
        val connectionClosed = AtomicBoolean()
        try {
            socket.use { client ->
                client.soTimeout = 30_000
                val input = BufferedInputStream(client.getInputStream())
                val writer = client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                if (!authenticate(input, writer)) return@use
                // The authentication handshake is bounded; an authenticated
                // client may keep the connection open while waiting for events.
                client.soTimeout = 0
                val outstanding = Semaphore(32)
                while (running.get()) {
                    val line = try {
                        CliJsonLines.readBoundedLine(input)
                    } catch (error: CliProtocolException) {
                        write(writer, CliJsonLines.error("", error.code, error.message, error.retryable))
                        return@use
                    } catch (_: IOException) { return@use }
                    if (line == null) return@use
                    val request = try {
                        CliJsonLines.decodeRequest(line)
                    } catch (error: CliProtocolException) {
                        write(writer, CliJsonLines.error("", error.code, error.message, error.retryable))
                        continue
                    } catch (error: Throwable) {
                        write(writer, CliJsonLines.error("", CliErrorCodes.INVALID_ARGUMENT,
                            error.message ?: "invalid request"))
                        continue
                    }
                    // Cancellation must be serviced even when all request
                    // workers are occupied by long-running waits.
                    if (request.operation == CliOperations.CANCEL) {
                        write(writer, gateway.handle(request))
                        continue
                    }
                    if (!outstanding.tryAcquire()) {
                        write(writer, CliJsonLines.error(request.requestId, CliErrorCodes.RATE_LIMITED,
                            "too many outstanding requests", true))
                        continue
                    }
                    val waitKey = (request.clientId ?: "local") to request.requestId
                    if (request.operation == CliOperations.WAIT) waitIds += waitKey
                    try {
                        requests.submit {
                            try {
                                if (connectionClosed.get()) return@submit
                                if (request.operation == CliOperations.WAIT) {
                                    gateway.handleStreaming(request, connectionClosed::get) { response -> write(writer, response) }
                                } else {
                                    write(writer, gateway.handle(request))
                                }
                            } finally {
                                if (request.operation == CliOperations.WAIT) waitIds -= waitKey
                                outstanding.release()
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        if (request.operation == CliOperations.WAIT) waitIds -= waitKey
                        outstanding.release()
                        write(writer, CliJsonLines.error(request.requestId,
                            if (closed.get()) CliErrorCodes.SERVER_CLOSED else CliErrorCodes.RATE_LIMITED,
                            "CLI request queue is unavailable", true))
                    }
                }
            }
        } finally {
            connectionClosed.set(true)
            waitIds.toList().forEach { (clientId, id) -> gateway.cancelWait(id, clientId) }
        }
    }

    private fun write(writer: BufferedWriter, response: CliResponse) {
        synchronized(writer) {
            try {
                writer.write(CliJsonLines.encode(response))
                writer.newLine()
                writer.flush()
            } catch (error: CliProtocolException) {
                if (error.code == CliErrorCodes.RESPONSE_TOO_LARGE) {
                    writer.write(CliJsonLines.encode(CliJsonLines.error(response.requestId,
                        CliErrorCodes.RESPONSE_TOO_LARGE, "response exceeds the protocol size limit")))
                    writer.newLine()
                    writer.flush()
                } else throw error
            }
        }
    }

    private fun authenticate(input: InputStream, writer: BufferedWriter): Boolean {
        val line = try {
            CliJsonLines.readBoundedLine(input)
        } catch (error: CliProtocolException) {
            writeRaw(writer, "{\"ok\":false,\"error\":{\"code\":\"${error.code}\",\"message\":\"authentication line too large\"}}")
            return false
        } catch (_: IOException) {
            return false
        } ?: return false
        if (line.isBlank()) {
            writeRaw(writer, "{\"ok\":false,\"error\":{\"code\":\"${CliErrorCodes.INVALID_ARGUMENT}\",\"message\":\"authentication line is empty\"}}")
            return false
        }
        val supplied = runCatching {
            val json = JsonParser.parseString(line).asJsonObject
            json.get("token")?.asString ?: json.get("authorization")?.asString?.removePrefix("Bearer ")
        }.getOrNull()
        val accepted = supplied != null && constantTimeEquals(token, supplied)
        writeRaw(writer, if (accepted) "{\"ok\":true,\"authenticated\":true}" else
            "{\"ok\":false,\"error\":{\"code\":\"NOT_AUTHORIZED\",\"message\":\"invalid CLI token\"}}")
        return accepted
    }

    private fun writeRaw(writer: BufferedWriter, value: String) {
        synchronized(writer) {
            writer.write(value)
            writer.newLine()
            writer.flush()
        }
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
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptedClients.toList().forEach { runCatching { it.close() } }
        acceptedClients.clear()
        gateway.close()
        clients.shutdownNow()
        requests.shutdownNow()
        runCatching { clients.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { requests.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    companion object {
        internal fun normalizePipeName(name: String): String {
            val shortName = name.removePrefix("\\\\.\\pipe\\")
            require(shortName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "invalid pipe name" }
            return shortName
        }
    }
}
