package com.fixcode.intellij.lua.debug.ui.mcp

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugProjectState

/**
 * 「IDE 的 MCP 服务器没开，Agent 用不了 Lua 调试工具」的气泡提示：
 *
 * - 时机：启动 Lua 调试会话时（这时才可能用得上 Agent 调试）；
 * - IDE 里没有 MCP Server 插件（被禁用）时不提示；
 * - 一个 IDE 会话里每个项目最多弹一次，「不再提示」按项目记住。
 */
object LuaMcpEnablePrompt {
    private val SHOWN = Key.create<Boolean>("EmmyLua.Debug.McpPromptShown")
    const val GROUP_ID = "EmmyLua Debug"

    fun maybeShow(project: Project) {
        if (project.isDisposed || project.getUserData(SHOWN) == true) return
        val service = LuaMcpService.getInstance() ?: return
        if (LuaDebugProjectState.getInstance(project).mcpPromptDismissed) return
        val state = service.serverState()
        if (!state.available || state.running) return
        project.putUserData(SHOWN, true)
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(
                LuaDebugBundle.message("mcp.prompt.title"),
                LuaDebugBundle.message("mcp.prompt.content"),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.open")) {
                service.openServerSettings(project)
            })
            .addAction(NotificationAction.createSimpleExpiring(LuaDebugBundle.message("mcp.prompt.dismiss")) {
                LuaDebugProjectState.getInstance(project).mcpPromptDismissed = true
            })
            .notify(project)
    }
}
