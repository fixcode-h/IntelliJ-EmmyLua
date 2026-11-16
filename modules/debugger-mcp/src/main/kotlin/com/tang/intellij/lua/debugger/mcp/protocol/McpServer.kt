package com.tang.intellij.lua.debugger.mcp.protocol

import com.tang.intellij.lua.debugger.mcp.json.Json
import com.tang.intellij.lua.debugger.mcp.json.JsonArgs
import com.tang.intellij.lua.debugger.mcp.json.ToolException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MCP 的 JSON-RPC 部分，和传输无关：HTTP 宿主把请求体和 `Mcp-Session-Id` 交进来，拿回状态码和响应体。
 *
 * 只实现调试需要的子集：initialize / ping / tools/list / tools/call。
 * 不做服务端主动推送（SSE）：wait_for_pause 是阻塞的请求-响应，所有客户端都支持，
 * 而服务端推送各家客户端支持程度不一。
 */
class McpServer(
    private val serverName: String,
    private val serverVersion: String,
    tools: List<McpTool>,
    private val callLog: McpCallLog,
    private val instructions: String? = null,
    /** 给 Agent 的使用说明（Markdown），作为资源 [guideUri] 提供。 */
    private val guide: String? = null,
    private val guideUri: String = "emmylua-debug://guide",
    private val logger: McpLogger = McpLogger.NONE,
) {
    private val toolsByName = LinkedHashMap<String, McpTool>().apply { tools.forEach { put(it.name, it) } }
    private val sessions = ConcurrentHashMap<String, McpClientSession>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 没带 Mcp-Session-Id 的请求（curl、简易客户端）共用这一个匿名会话。 */
    private val anonymous = McpClientSession("anonymous", null, null, LATEST_PROTOCOL)

    val toolNames: List<String> get() = toolsByName.keys.toList()

    fun clientSessions(): List<McpClientSession> = sessions.values.toList()

    /** 连接的客户端数（最近 [activeWithinMillis] 内有请求的会话）。 */
    fun activeClientCount(activeWithinMillis: Long = 10 * 60_000L): Int {
        val now = System.currentTimeMillis()
        return sessions.values.count { now - it.lastActiveMillis <= activeWithinMillis }
    }

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    class Response(
        val status: Int,
        val body: String?,
        /** initialize 成功时新分配的会话 id，宿主放进 Mcp-Session-Id 响应头。 */
        val newSessionId: String? = null,
    )

    fun handlePost(body: String, sessionIdHeader: String?): Response {
        val message = try {
            Json.parse(body)
        } catch (e: Json.ParseException) {
            return Response(400, rpcError(null, PARSE_ERROR, "请求体不是合法 JSON：${e.message}"))
        }
        if (message is List<*>) {
            // 2025-06-18 起 MCP 不再支持批量请求
            return Response(400, rpcError(null, INVALID_REQUEST, "不支持 JSON-RPC 批量请求"))
        }
        @Suppress("UNCHECKED_CAST")
        val obj = message as? Map<String, Any?>
            ?: return Response(400, rpcError(null, INVALID_REQUEST, "请求必须是 JSON 对象"))
        val id = obj["id"]
        val method = obj["method"] as? String

        if (method == null) {
            // 客户端发来的 response（我们不发请求，忽略）
            return Response(202, null)
        }

        if (method == "initialize") {
            if (id == null) return Response(400, rpcError(null, INVALID_REQUEST, "initialize 必须带 id"))
            return initialize(id, obj["params"] as? Map<*, *>)
        }

        val session = when {
            sessionIdHeader.isNullOrBlank() -> anonymous
            else -> sessions[sessionIdHeader]
                ?: return Response(404, rpcError(id, INVALID_REQUEST, "会话不存在或 IDE 已重启，请重新 initialize"))
        }
        session.lastActiveMillis = System.currentTimeMillis()

        if (id == null) {
            // 通知：notifications/initialized、notifications/cancelled 等，不需要回包
            return Response(202, null)
        }

        @Suppress("UNCHECKED_CAST")
        val params = obj["params"] as? Map<String, Any?> ?: emptyMap()
        return when (method) {
            "ping" -> Response(200, rpcResult(id, emptyMap<String, Any?>()))
            "tools/list" -> Response(200, rpcResult(id, mapOf("tools" to toolsByName.values.map { it.descriptor() })))
            "tools/call" -> Response(200, callTool(id, params, session))
            "resources/list" -> Response(200, rpcResult(id, mapOf("resources" to resources())))
            "resources/read" -> Response(200, readResource(id, params["uri"] as? String))
            "prompts/list" -> Response(200, rpcResult(id, mapOf("prompts" to emptyList<Any>())))
            else -> Response(200, rpcError(id, METHOD_NOT_FOUND, "不支持的方法 $method"))
        }
    }

    /** 客户端 DELETE 会话。 */
    fun endSession(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        if (removed) fireChanged()
        return removed
    }

    private fun initialize(id: Any, params: Map<*, *>?): Response {
        val requested = params?.get("protocolVersion") as? String
        val version = if (requested != null && requested in SUPPORTED_PROTOCOLS) requested else LATEST_PROTOCOL
        val clientInfo = params?.get("clientInfo") as? Map<*, *>
        val session = McpClientSession(
            id = UUID.randomUUID().toString(),
            clientName = clientInfo?.get("name") as? String,
            clientVersion = clientInfo?.get("version") as? String,
            protocolVersion = version,
        )
        sessions[session.id] = session
        pruneSessions()
        fireChanged()
        val result = linkedMapOf<String, Any?>(
            "protocolVersion" to version,
            "capabilities" to linkedMapOf(
                "tools" to linkedMapOf("listChanged" to false),
                "resources" to linkedMapOf("listChanged" to false),
            ),
            "serverInfo" to linkedMapOf("name" to serverName, "title" to "EmmyLua 调试", "version" to serverVersion),
        )
        if (instructions != null) result["instructions"] = instructions
        return Response(200, rpcResult(id, result), newSessionId = session.id)
    }

    private fun resources(): List<Map<String, Any?>> =
        if (guide == null) emptyList()
        else listOf(linkedMapOf(
            "uri" to guideUri, "name" to "guide", "title" to "How to use the EmmyLua debug tools", "mimeType" to "text/markdown",
        ))

    private fun readResource(id: Any, uri: String?): String =
        if (guide != null && uri == guideUri) {
            rpcResult(id, mapOf("contents" to listOf(linkedMapOf("uri" to guideUri, "mimeType" to "text/markdown", "text" to guide))))
        } else {
            rpcError(id, INVALID_PARAMS, "没有资源 $uri")
        }

    /**
     * 结果统一是一个 text 内容块，内容是紧凑 JSON；出错时 `isError: true` + `{"error": 代码, "message": 中文说明}`。
     * 每次调用写一行日志 `mcp.tool.<名字>`（参数摘要、结果数、耗时），超过 1 秒记 `.slow`。
     */
    private fun callTool(id: Any, params: Map<String, Any?>, session: McpClientSession): String {
        val name = params["name"] as? String
            ?: return rpcError(id, INVALID_PARAMS, "缺少工具名 name")
        @Suppress("UNCHECKED_CAST")
        val rawArgs = params["arguments"] as? Map<String, Any?> ?: emptyMap()
        val started = System.nanoTime()
        val tool = toolsByName[name]
        var event = "mcp.tool." + (if (tool != null) name else "unknown")
        var level = McpLogger.Level.INFO
        var error: Throwable? = null
        var errorCode: String? = null
        val payload: Map<String, Any?> = try {
            if (tool == null) throw ToolException("unknown_tool", "没有工具 '$name'，用 tools/list 查看可用工具。")
            tool.handler(ToolCall(tool, JsonArgs(rawArgs, tool.argumentNames), session))
        } catch (e: ToolException) {
            errorCode = e.code
            event += ".rejected"
            level = McpLogger.Level.WARN
            linkedMapOf("error" to e.code, "message" to e.message)
        } catch (e: Throwable) {
            errorCode = "internal_error"
            event += ".failed"
            level = McpLogger.Level.ERROR
            error = e
            linkedMapOf("error" to "internal_error", "message" to "${e.javaClass.simpleName}: ${e.message}（详情见 IDE 日志 idea.log）")
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (errorCode == null && ms > SLOW_MILLIS && !name.endsWith("wait")) {
            // wait / 带等待的单步本来就慢，不算；其余超过 1 秒说明引擎或目标卡了
            event += ".slow"
            level = McpLogger.Level.WARN
        }
        val text = Json.write(payload)
        val argsSummary = JsonArgs(rawArgs).summary()
        val action = rawArgs["action"] as? String
        logger.log(level, event, payload["message"] as? String, linkedMapOf(
            "args" to argsSummary, "code" to errorCode, "ms" to ms, "client" to session.clientName,
        ), error)
        callLog.add(
            McpCallEntry(
                timestampMillis = System.currentTimeMillis() - ms,
                tool = if (action != null) "$name.$action" else name,
                argsSummary = McpCallLog.oneLine(argsSummary),
                resultSummary = McpCallLog.oneLine(
                    if (errorCode != null) (payload["message"] as? String ?: errorCode) else text
                ),
                success = errorCode == null,
                clientName = session.clientName,
                sessionId = rawArgs["session_id"] as? String,
                durationMillis = ms,
            )
        )
        val result = linkedMapOf<String, Any?>("content" to listOf(linkedMapOf("type" to "text", "text" to text)))
        if (errorCode != null) result["isError"] = true
        return rpcResult(id, result)
    }

    private fun pruneSessions() {
        // 客户端常常不发 DELETE；只保留最近的若干个
        if (sessions.size <= MAX_SESSIONS) return
        sessions.values.sortedBy { it.lastActiveMillis }
            .take(sessions.size - MAX_SESSIONS)
            .forEach { sessions.remove(it.id) }
    }

    private fun fireChanged() = listeners.forEach { runCatching { it() } }

    companion object {
        const val LATEST_PROTOCOL = "2025-06-18"
        val SUPPORTED_PROTOCOLS = setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_SESSIONS = 64
        private const val SLOW_MILLIS = 1000L

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        fun rpcResult(id: Any?, result: Any?): String =
            Json.write(linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

        fun rpcError(id: Any?, code: Int, message: String): String =
            Json.write(linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to linkedMapOf("code" to code, "message" to message)))
    }
}
