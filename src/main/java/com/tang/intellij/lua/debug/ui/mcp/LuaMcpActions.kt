/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debug.ui.mcp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.tang.intellij.lua.debug.api.LuaMcpService
import com.tang.intellij.lua.debug.ui.LuaDebugBundle

/** Tools > EmmyLua 调试 > 安装 MCP 到项目… */
class InstallMcpAction : DumbAwareAction(
    LuaDebugBundle.message("action.installMcp"),
    LuaDebugBundle.message("action.installMcp.description"),
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        LuaMcpInstallDialog.show(e.project ?: return)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Tools > EmmyLua 调试 > MCP 状态：一句话说清端点地址和连接数，方便贴给 Agent 或排查。 */
class ShowMcpStatusAction : DumbAwareAction(
    LuaDebugBundle.message("action.mcpStatus"),
    LuaDebugBundle.message("action.mcpStatus.description"),
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val service = LuaMcpService.getInstance()
        val text = if (service == null) {
            LuaDebugBundle.message("mcp.error.noService")
        } else {
            val s = service.serverState()
            when {
                s.error != null -> LuaDebugBundle.message("statusbar.tooltip.error", s.error)
                !s.enabled -> LuaDebugBundle.message("statusbar.tooltip.disabled")
                !s.running -> LuaDebugBundle.message("statusbar.tooltip.stopped")
                else -> LuaDebugBundle.message("statusbar.tooltip.running", s.url, s.connectedClients)
            }
        }
        Messages.showInfoMessage(e.project, text, LuaDebugBundle.message("action.mcpStatus"))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
