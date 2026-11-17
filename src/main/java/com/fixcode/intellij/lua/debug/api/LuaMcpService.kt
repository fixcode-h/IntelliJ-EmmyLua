package com.fixcode.intellij.lua.debug.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * MCP 调试工具给 UI 用的门面：设置页、「MCP 服务器没开」提示、Agent 页都只认这个接口。
 * 由「MCP」实现并注册到 `com.fixcode.intellij.lua.debug.mcpService`。
 *
 * 工具注册在 IDE 自带的 MCP 服务器上（2025.2 起内置），端口、开关、客户端配置都归它管；
 * 这里只报告它的状态、给出打开它设置页的入口。是否允许任意 Lua 存在
 * [com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings]。
 */
interface LuaMcpService {
    /** IDE 自带 MCP 服务器的状态。读的是内存里的状态，可以在 EDT 上调。 */
    fun serverState(): LuaMcpServerState

    /** 打开「设置 → 工具 → MCP 服务器」。 */
    fun openServerSettings(project: Project?)

    /** 注册给 IDE MCP 服务器的工具名。 */
    fun toolNames(): List<String>

    /** 有 Agent 调用、授权变化时通知（任意线程，UI 自己切 EDT）。 */
    fun addListener(listener: () -> Unit, parent: Disposable)

    /** 最近的 Agent 调用（新的在后），给调试窗口「Agent」页用。 */
    fun recentCalls(limit: Int = 200): List<LuaMcpCallRecord>

    /** 「撤销 Agent 控制」：收回本 IDE 所有会话已授予的控制权，之后再控制要重新确认。 */
    fun revokeAgentControl()

    /** 「清除 Agent 断点」：删掉 origin = agent 的断点。 */
    fun clearAgentBreakpoints(project: Project)

    companion object {
        val EP: ExtensionPointName<LuaMcpService> =
            ExtensionPointName.create("com.fixcode.intellij.lua.debug.mcpService")

        fun getInstance(): LuaMcpService? = EP.extensionList.firstOrNull()
    }
}

data class LuaMcpServerState(
    /** IDE 里有 MCP Server 插件（JetBrains 自带，可能被用户禁用）。 */
    val available: Boolean,
    /** 「设置 → 工具 → MCP 服务器」里开了，并且服务在跑。 */
    val running: Boolean,
    /** 客户端连接用的地址（SSE / Streamable HTTP，取 IDE 给出的那个）。 */
    val url: String? = null,
    /** 最近 10 分钟调用过 Lua 调试工具的客户端数。 */
    val activeClients: Int = 0,
)

data class LuaMcpCallRecord(
    val timestampMillis: Long,
    val tool: String,
    val argsSummary: String,
    val resultSummary: String,
    val success: Boolean,
    /** 发起调用的客户端名（IDE MCP 服务器给出的 clientInfo.name，拿不到时为空）。 */
    val clientName: String? = null,
    val sessionId: String? = null,
)
