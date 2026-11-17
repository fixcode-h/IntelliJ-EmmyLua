package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.protocol.ControlDecision
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpConfirmer
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpToolDispatcher
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

/** 直接调 [McpToolDispatcher]，不经过 IDE 的 MCP 服务器。 */
class McpTestClient(private val dispatcher: McpToolDispatcher, clientName: String = "test-agent") {
    private val client = dispatcher.client(null, clientName, "1")

    /** tools/list 里每个工具的描述（和注册给 IDE 的一致）。 */
    fun tools(): List<Map<String, Any?>> = dispatcher.tools.map { it.descriptor() }

    class ToolOutcome(val json: Map<String, Any?>, val isError: Boolean, val raw: String) {
        operator fun get(key: String): Any? = json[key]
        val code: String? get() = json["error"] as? String
    }

    fun call(tool: String, args: Map<String, Any?> = emptyMap()): ToolOutcome {
        // 和 IDE 宿主一样，参数先过一遍 JSON（数字变 Long 等）
        @Suppress("UNCHECKED_CAST")
        val raw = Json.parse(Json.write(args)) as Map<String, Any?>
        val r = dispatcher.call(tool, raw, client)
        return ToolOutcome(Json.parseObject(r.text), r.isError, r.text)
    }
}
