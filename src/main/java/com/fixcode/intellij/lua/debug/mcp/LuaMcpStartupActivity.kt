package com.fixcode.intellij.lua.debug.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开时确保 MCP 服务在跑（应用级，只会真正启动一次）。
 * 「没安装时提示」由 UI 的 LuaMcpInstallPromptActivity 负责，它通过 LuaMcpService 查安装状态。
 */
class LuaMcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LuaMcpServer.getInstance().applySettings()
    }
}
