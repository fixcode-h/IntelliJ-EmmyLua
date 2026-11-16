package com.tang.intellij.lua.debugger.mcp

import com.tang.intellij.lua.debugger.mcp.http.McpHttpHost
import com.tang.intellij.lua.debugger.mcp.json.Json
import com.tang.intellij.lua.debugger.mcp.protocol.McpLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

class McpProtocolTest {
    init {
        // HttpURLConnection 默认不让设置 Origin 头
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    }

    private val servers = ArrayList<EmmyLuaMcpServer>()
    private val logged = CopyOnWriteArrayList<String>()

    private fun newServer(): EmmyLuaMcpServer =
        EmmyLuaMcpServer({ FakeBackend() }, FakeConfirmer(), "test", logger = McpLogger { _, event, _, _, _ -> logged += event })
            .also { servers += it }

    @After
    fun tearDown() = servers.forEach { it.stop() }

    @Test
    fun `initialize 分配会话，协议版本协商`() {
        val server = newServer()
        val c = McpTestClient(server.protocol)
        val result = c.initialize()
        assertNotNull(c.sessionId)
        assertEquals("2025-06-18", result["protocolVersion"])
        assertEquals("emmylua-debug", (result["serverInfo"] as Map<*, *>)["name"])
        val instructions = result["instructions"] as String
        assertTrue("instructions 要在 2000 字内：${instructions.length}", instructions.length <= 2000)
    }

    @Test
    fun `未知会话返回 404，通知返回 202`() {
        val p = newServer().protocol
        assertEquals(404, p.handlePost("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", "nope").status)
        assertEquals(202, p.handlePost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", null).status)
        assertEquals(400, p.handlePost("{bad", null).status)
    }

    @Test
    fun `未知工具返回 unknown_tool 并记日志`() {
        val c = McpTestClient(newServer().protocol)
        c.initialize()
        val r = c.call("no_such_tool")
        assertEquals("unknown_tool", r.code)
        assertTrue(logged.contains("mcp.tool.unknown.rejected"))
    }

    @Test
    fun `调用记进 Agent 页的记录`() {
        val server = newServer()
        val c = McpTestClient(server.protocol)
        c.initialize()
        c.call("lua_debug_session", mapOf("action" to "status"))
        val entry = server.callLog.recent(10).single()
        assertEquals("lua_debug_session.status", entry.tool)
        assertEquals("test-agent", entry.clientName)
        assertTrue(logged.contains("mcp.tool.lua_debug_session"))
    }

    @Test
    fun `guide 资源可读`() {
        val c = McpTestClient(newServer().protocol)
        c.initialize()
        val r = c.rpc("resources/read", mapOf("uri" to EmmyLuaMcpServer.GUIDE_URI))
        val contents = ((r["result"] as Map<*, *>)["contents"] as List<*>)[0] as Map<*, *>
        assertTrue((contents["text"] as String).contains("lua_debug_session"))
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun post(port: Int, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, HttpURLConnection> {
        val conn = URI("http://127.0.0.1:$port/mcp").toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.responseCode to conn
    }

    @Test
    fun `HTTP 往返：initialize 带 Mcp-Session-Id，tools-list 用它`() {
        val server = newServer()
        val port = server.start(freePort())
        val (code, conn) = post(port, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")
        assertEquals(200, code)
        val sid = conn.getHeaderField(McpHttpHost.SESSION_HEADER)
        assertNotNull(sid)
        val init = Json.parseObject(conn.inputStream.readBytes().decodeToString())
        assertEquals("2025-03-26", (init["result"] as Map<*, *>)["protocolVersion"])
        val (code2, conn2) = post(port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", mapOf(McpHttpHost.SESSION_HEADER to sid))
        assertEquals(200, code2)
        assertTrue(conn2.inputStream.readBytes().decodeToString().contains("lua_debug_inspect"))
    }

    @Test
    fun `外部 Origin 被拒绝`() {
        val server = newServer()
        val port = server.start(freePort())
        val (code, _) = post(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("Origin" to "https://evil.example.com"))
        assertEquals(403, code)
        val (ok, _) = post(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("Origin" to "http://localhost:3000"))
        assertEquals(200, ok)
    }

    @Test
    fun `Host 和 Origin 校验规则`() {
        assertTrue(McpHttpHost.hostAllowed("127.0.0.1:9330"))
        assertTrue(McpHttpHost.hostAllowed("localhost:9330"))
        assertTrue(McpHttpHost.hostAllowed("[::1]:9330"))
        assertTrue(!McpHttpHost.hostAllowed("evil.com:9330"))
        assertTrue(!McpHttpHost.hostAllowed("127.0.0.1.evil.com"))
        assertTrue(!McpHttpHost.originAllowed("null"))
        assertTrue(McpHttpHost.originAllowed(null))
    }

    @Test
    fun `端口被占用时顺延`() {
        val busy = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        busy.use {
            val server = newServer()
            val port = server.start(busy.localPort)
            assertNotEquals(busy.localPort, port)
            assertTrue(port in busy.localPort + 1..busy.localPort + 9)
        }
    }

    @Test
    fun `GET 返回 405`() {
        val server = newServer()
        val port = server.start(freePort())
        val conn = URI("http://127.0.0.1:$port/mcp").toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        assertEquals(405, conn.responseCode)
    }
}
