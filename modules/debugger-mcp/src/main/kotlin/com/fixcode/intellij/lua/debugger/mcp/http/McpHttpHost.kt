package com.fixcode.intellij.lua.debugger.mcp.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpServer
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP Streamable HTTP 传输：`POST http://127.0.0.1:<port>/mcp`。
 *
 * 用 JDK 自带的 HttpServer：不引依赖，也不依赖 IntelliJ 的内置服务器（那个监听的端口不固定，
 * 不能写进项目配置给全组用）。
 *
 * 安全（MCP 规范对本地服务器的要求）：
 * - 只绑定 127.0.0.1；
 * - 校验 Host 头只能是回环地址，防 DNS rebinding；
 * - 有 Origin 头时只接受回环来源，防网页里的脚本跨站调用。
 * 没有 token：配置文件要能提交到仓库，安全靠以上三条 + IDE 内确认控制。
 */
class McpHttpHost(
    private val server: McpServer,
    private val path: String = "/mcp",
) {
    @Volatile
    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    /** 实际监听的端口；未启动时为 -1。 */
    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = http != null

    /**
     * 从 [preferredPort] 开始找可用端口，最多试 [attempts] 个。返回实际端口。
     * 失败抛 [IOException]，消息是中文。
     */
    @Synchronized
    fun start(preferredPort: Int, attempts: Int = 10): Int {
        stop()
        var lastError: IOException? = null
        for (candidate in preferredPort until preferredPort + attempts) {
            if (candidate !in 1..65535) break
            try {
                val s = HttpServer.create(InetSocketAddress(LOOPBACK, candidate), 64)
                val pool = newExecutor()
                s.executor = pool
                s.createContext(path) { exchange -> handle(exchange) }
                s.start()
                http = s
                executor = pool
                port = candidate
                return candidate
            } catch (e: BindException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException("端口 $preferredPort–${preferredPort + attempts - 1} 都被占用，MCP 服务没有启动：${lastError?.message}", lastError)
    }

    @Synchronized
    fun stop() {
        http?.stop(0)
        http = null
        executor?.shutdownNow()
        executor = null
        port = -1
    }

    private fun newExecutor(): ExecutorService {
        // wait_for_pause 会占住一个线程最多两分钟，所以要多线程；设上限防止被打爆
        val counter = AtomicInteger()
        // 固定上限 + 排队：超过上限的请求等前面的返回，而不是被拒绝
        return ThreadPoolExecutor(32, 32, 60, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "EmmyLua-MCP-${counter.incrementAndGet()}").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!hostAllowed(exchange.requestHeaders.getFirst("Host"))) {
                send(exchange, 403, "text/plain", "Host 不是本机地址，已拒绝。")
                return
            }
            if (!originAllowed(exchange.requestHeaders.getFirst("Origin"))) {
                send(exchange, 403, "text/plain", "Origin 不是本机地址，已拒绝。")
                return
            }
            if (exchange.requestURI.path.trimEnd('/') != path) {
                send(exchange, 404, "text/plain", "Not found")
                return
            }
            when (exchange.requestMethod.uppercase()) {
                "POST" -> handlePost(exchange)
                "DELETE" -> {
                    val sid = exchange.requestHeaders.getFirst(SESSION_HEADER)
                    if (sid != null) server.endSession(sid)
                    send(exchange, 200, null, null)
                }
                // 不提供服务端主动推送的 SSE 流：规范允许 GET 返回 405
                "GET" -> {
                    exchange.responseHeaders.add("Allow", "POST, DELETE")
                    send(exchange, 405, "text/plain", "本服务不提供 SSE 推送流，请用 POST。")
                }
                "OPTIONS" -> {
                    exchange.responseHeaders.add("Allow", "POST, DELETE")
                    send(exchange, 204, null, null)
                }
                else -> send(exchange, 405, "text/plain", "Method not allowed")
            }
        } catch (e: Throwable) {
            runCatching { send(exchange, 500, "text/plain", "内部错误：${e.message}") }
        } finally {
            exchange.close()
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (length != null && length > MAX_BODY) {
            send(exchange, 413, "text/plain", "请求体过大")
            return
        }
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) {
            send(exchange, 413, "text/plain", "请求体过大")
            return
        }
        val response = server.handlePost(String(bytes, Charsets.UTF_8), exchange.requestHeaders.getFirst(SESSION_HEADER))
        response.newSessionId?.let { exchange.responseHeaders.add(SESSION_HEADER, it) }
        send(exchange, response.status, if (response.body != null) "application/json; charset=utf-8" else null, response.body)
    }

    private fun send(exchange: HttpExchange, status: Int, contentType: String?, body: String?) {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (contentType != null) exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        // 204/202/无内容时长度 -1 表示没有响应体
        exchange.sendResponseHeaders(status, if (bytes == null || bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes != null && bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val SESSION_HEADER = "Mcp-Session-Id"
        private const val MAX_BODY = 4 * 1024 * 1024
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

        fun hostAllowed(host: String?): Boolean {
            if (host.isNullOrBlank()) return true // HTTP/1.0 客户端可能不带；我们只绑回环，外部连不进来
            val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBeforeLast(':', host)
            return name.lowercase() in LOOPBACK_HOSTS
        }

        fun originAllowed(origin: String?): Boolean {
            if (origin.isNullOrBlank()) return true // 非浏览器客户端不带 Origin
            if (origin == "null") return false
            val host = runCatching { URI(origin).host }.getOrNull() ?: return false
            return host.lowercase() in LOOPBACK_HOSTS
        }
    }
}
