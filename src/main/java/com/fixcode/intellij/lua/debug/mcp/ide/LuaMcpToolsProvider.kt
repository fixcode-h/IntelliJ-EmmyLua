package com.fixcode.intellij.lua.debug.mcp.ide

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.diagnostic.Logger
import com.fixcode.intellij.lua.debug.mcp.LuaDebugMcpTools
import com.fixcode.intellij.lua.debugger.mcp.json.Json
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpTool as LuaTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject

/**
 * 把 Lua 调试工具注册进 IDE 自带的 MCP 服务器（扩展点 `com.intellij.mcpServer.mcpToolsProvider`，
 * 2025.2 起内置）。只在 MCP Server 插件启用时由 `emmylua-mcp.xml` 加载。
 *
 * 工具的逻辑都在 `modules/debugger-mcp`，这里只做适配：参数 JSON 原样转给分发器，结果是一个 text
 * 内容块（紧凑 JSON），出错时 isError。描述、结果类的构造参数每个 IDE 版本都不一样，交给 [IdeMcpCompat]。
 */
class LuaMcpToolsProvider : McpToolsProvider {
    override fun getTools(): List<McpTool> {
        val dispatcher = LuaDebugMcpTools.getInstance().mcp.dispatcher
        return dispatcher.tools.mapNotNull { tool ->
            try {
                LuaIdeMcpTool(tool, IdeMcpCompat.descriptor(tool))
            } catch (e: Throwable) {
                // 新版 IDE 改了描述类的构造参数：这个工具注册不上，但不能拖垮别的工具和 MCP 服务器
                LOG.warn("mcp.register.failed tool=${tool.name}：这个 IDE 版本的 MCP 服务器接口不认识，工具没有注册", e)
                null
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(LuaMcpToolsProvider::class.java)
    }
}

private class LuaIdeMcpTool(private val tool: LuaTool, override val descriptor: McpToolDescriptor) : McpTool {

    override suspend fun call(args: JsonObject): McpToolCallResult {
        val client = IdeMcpCompat.clientOf(currentCoroutineContext())
        @Suppress("UNCHECKED_CAST")
        val raw = Json.parse(args.toString()) as? Map<String, Any?> ?: emptyMap()
        // 工具会阻塞（wait 最长 2 分钟、等用户在 IDE 里确认），放到 IO 线程；客户端取消时中断等待
        val result = runInterruptible(Dispatchers.IO) {
            val dispatcher = LuaDebugMcpTools.getInstance().mcp.dispatcher
            dispatcher.call(tool.name, raw, dispatcher.client(client.key, client.name, client.version))
        }
        return IdeMcpCompat.result(arrayOf(McpToolCallResultContent.Text(result.text)), result.isError)
    }
}
