package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.protocol.ControlDecision
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpConfirmer
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpServer
import java.util.concurrent.CopyOnWriteArrayList

class FakeConfirmer(
    var control: ControlDecision = ControlDecision.ALLOW_SESSION,
    var launch: Boolean = true,
    var luaEval: Boolean = false,
) : McpConfirmer {
    val asked = CopyOnWriteArrayList<String>()

    override fun confirmControl(subject: String, clientName: String?): ControlDecision {
        asked += "control:$subject"
        return control
    }

    override fun confirmLaunch(description: String, clientName: String?): Boolean {
        asked += "launch:$description"
        return launch
    }

    override fun luaEvalAllowed() = luaEval
}

/** 直接对 McpServer 发 JSON-RPC，免去 HTTP。 */
class McpTestClient(private val server: McpServer) {
    private var nextId = 1
    var sessionId: String? = null

    fun initialize(): Map<String, Any?> {
        val resp = server.handlePost(
            Json.write(mapOf("jsonrpc" to "2.0", "id" to nextId++, "method" to "initialize",
                "params" to mapOf("protocolVersion" to "2025-06-18", "clientInfo" to mapOf("name" to "test-agent", "version" to "1")))),
            null,
        )
        sessionId = resp.newSessionId
        @Suppress("UNCHECKED_CAST")
        return (Json.parse(resp.body!!) as Map<String, Any?>)["result"] as Map<String, Any?>
    }

    fun rpc(method: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val resp = server.handlePost(
            Json.write(mapOf("jsonrpc" to "2.0", "id" to nextId++, "method" to method, "params" to params)), sessionId,
        )
        @Suppress("UNCHECKED_CAST")
        return Json.parse(resp.body!!) as Map<String, Any?>
    }

    class ToolOutcome(val json: Map<String, Any?>, val isError: Boolean, val raw: String) {
        operator fun get(key: String): Any? = json[key]
        val code: String? get() = json["error"] as? String
    }

    fun call(tool: String, args: Map<String, Any?> = emptyMap()): ToolOutcome {
        val r = rpc("tools/call", mapOf("name" to tool, "arguments" to args))
        @Suppress("UNCHECKED_CAST")
        val result = r["result"] as? Map<String, Any?> ?: error("工具调用返回了协议错误：$r")
        val content = result["content"] as List<*>
        val text = (content[0] as Map<*, *>)["text"] as String
        return ToolOutcome(Json.parseObject(text), result["isError"] == true, text)
    }
}
