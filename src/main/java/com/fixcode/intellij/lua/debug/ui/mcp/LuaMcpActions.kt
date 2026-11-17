package com.fixcode.intellij.lua.debug.ui.mcp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle

/** Tools > EmmyLua 调试 > 打开 MCP 服务器设置…：Lua 调试工具挂在 IDE 自带的 MCP 服务器上，开关和客户端配置都在那里。 */
class OpenMcpServerSettingsAction : DumbAwareAction(
    LuaDebugBundle.message("action.openMcpSettings"),
    LuaDebugBundle.message("action.openMcpSettings.description"),
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        LuaMcpService.getInstance()?.openServerSettings(e.project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = LuaMcpService.getInstance()?.serverState()?.available == true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Tools > EmmyLua 调试 > MCP 状态：IDE 的 MCP 服务器开没开、地址、注册了哪些 Lua 调试工具。 */
class ShowMcpStatusAction : DumbAwareAction(
    LuaDebugBundle.message("action.mcpStatus"),
    LuaDebugBundle.message("action.mcpStatus.description"),
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage(e.project, LuaMcpStatusText.describe(), LuaDebugBundle.message("action.mcpStatus"))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** 状态的一句话说明，设置页和「MCP 状态」共用。 */
object LuaMcpStatusText {
    fun describe(): String {
        val service = LuaMcpService.getInstance() ?: return LuaDebugBundle.message("mcp.error.noService")
        val s = service.serverState()
        return when {
            !s.available -> LuaDebugBundle.message("mcp.state.unavailable")
            !s.running -> LuaDebugBundle.message("mcp.state.stopped")
            else -> LuaDebugBundle.message(
                "mcp.state.running", s.url ?: "", service.toolNames().joinToString(", "), s.activeClients,
            )
        }
    }
}
