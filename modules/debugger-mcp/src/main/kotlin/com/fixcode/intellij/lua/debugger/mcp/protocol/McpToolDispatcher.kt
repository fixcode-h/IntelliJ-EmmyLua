package com.fixcode.intellij.lua.debugger.mcp.protocol

import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.json.JsonArgs
import com.fixcode.intellij.lua.debugger.mcp.json.ToolException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 工具调用的分发，和传输无关。传输由 IDE 自带的 MCP 服务器负责（2025.2 起内置，插件经
 * `com.intellij.mcpServer.mcpToolsProvider` 把工具注册进去），这里只管：按名字找工具、校验参数、
 * 把结果或错误写成统一的紧凑 JSON、记日志和 Agent 页的调用记录。
 */
class McpToolDispatcher(
    val tools: List<McpTool>,
    private val callLog: McpCallLog,
    private val logger: McpLogger = McpLogger.NONE,
) {
    private val toolsByName = LinkedHashMap<String, McpTool>().apply { tools.forEach { put(it.name, it) } }
    private val clients = ConcurrentHashMap<String, McpClientSession>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val toolNames: List<String> get() = toolsByName.keys.toList()

    fun tool(name: String): McpTool? = toolsByName[name]

    /** 调用的结果：[text] 是唯一的 text 内容块（紧凑 JSON），出错时 [isError]。 */
    class Result(val text: String, val isError: Boolean)

    /**
     * 按客户端取会话（记「这个客户端看过哪些暂停」）。[key] 由宿主给：IDE 的 MCP 会话 id，
     * 拿不到时用客户端名 + 版本，再拿不到就大家共用一个。
     */
    fun client(key: String?, name: String?, version: String?): McpClientSession {
        val k = key?.takeIf { it.isNotBlank() } ?: listOfNotNull(name, version).joinToString("/").ifEmpty { "default" }
        val session = clients.computeIfAbsent(k) {
            McpClientSession(it, name, version).also { pruneClients() }
        }
        session.lastActiveMillis = System.currentTimeMillis()
        return session
    }

    /** 最近 [activeWithinMillis] 内调用过工具的客户端数。 */
    fun activeClientCount(activeWithinMillis: Long = 10 * 60_000L): Int {
        val now = System.currentTimeMillis()
        return clients.values.count { now - it.lastActiveMillis <= activeWithinMillis }
    }

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * 调一个工具。结果统一是紧凑 JSON；出错时 `{"error": 代码, "message": 中文说明}` 且 [Result.isError]。
     * 每次调用写一行日志 `mcp.tool.<名字>`（参数摘要、结果、耗时），参数错 / 权限拒绝 `.rejected`，
     * 内部异常 `.failed`，非等待类超过 1 秒 `.slow`。会阻塞（wait、确认弹窗），调用方放在后台线程。
     */
    fun call(name: String, rawArgs: Map<String, Any?>, client: McpClientSession): Result {
        val started = System.nanoTime()
        val tool = toolsByName[name]
        var event = "mcp.tool." + (if (tool != null) name else "unknown")
        var level = McpLogger.Level.INFO
        var error: Throwable? = null
        var errorCode: String? = null
        val payload: Map<String, Any?> = try {
            if (tool == null) throw ToolException("unknown_tool", "没有工具 '$name'，可用的是 ${toolNames.joinToString()}。")
            tool.handler(ToolCall(tool, JsonArgs(rawArgs, tool.argumentNames), client))
        } catch (e: ToolException) {
            errorCode = e.code
            event += ".rejected"
            level = McpLogger.Level.WARN
            linkedMapOf("error" to e.code, "message" to e.message)
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            errorCode = if (e is InterruptedException) "cancelled" else "internal_error"
            event += ".failed"
            level = McpLogger.Level.ERROR
            error = e
            if (e is InterruptedException) {
                linkedMapOf("error" to "cancelled", "message" to "调用被客户端取消。")
            } else {
                linkedMapOf("error" to "internal_error", "message" to "${e.javaClass.simpleName}: ${e.message}（详情见 IDE 日志 idea.log）")
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val waits = name.endsWith("wait") || rawArgs["action"] in WAITING_ACTIONS || rawArgs.containsKey("wait_ms")
        if (errorCode == null && ms > SLOW_MILLIS && !waits) {
            // wait / 带等待的单步 / 采样（按 duration_ms 等）本来就慢，不算；其余超过 1 秒说明引擎或目标卡了
            event += ".slow"
            level = McpLogger.Level.WARN
        }
        val text = Json.write(payload)
        val argsSummary = JsonArgs(rawArgs).summary()
        val action = rawArgs["action"] as? String
        logger.log(level, event, payload["message"] as? String, linkedMapOf(
            "args" to argsSummary, "code" to errorCode, "ms" to ms, "client" to client.clientName,
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
                clientName = client.clientName,
                sessionId = rawArgs["session_id"] as? String,
                durationMillis = ms,
            )
        )
        listeners.forEach { runCatching { it() } }
        return Result(text, errorCode != null)
    }

    private fun pruneClients() {
        if (clients.size <= MAX_CLIENTS) return
        clients.values.sortedBy { it.lastActiveMillis }
            .take(clients.size - MAX_CLIENTS)
            .forEach { clients.remove(it.id) }
    }

    companion object {
        private const val MAX_CLIENTS = 64
        private const val SLOW_MILLIS = 1000L
        /** 按参数等待的动作：耗时是 Agent 要求的，不记 .slow。 */
        private val WAITING_ACTIONS = setOf("wait", "profile")
    }
}
