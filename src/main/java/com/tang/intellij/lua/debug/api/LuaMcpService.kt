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

package com.tang.intellij.lua.debug.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * MCP Server 和安装器给 UI 用的门面：设置页、状态栏、未安装提示、Agent 页都只认这个接口。
 * 由「MCP」实现并注册到 `com.tang.intellij.lua.debug.mcpService`。
 *
 * 端口、是否启用、是否允许任意 Lua 这些设置存在 [com.tang.intellij.lua.debug.ui.settings.LuaDebugSettings]，
 * 实现读它即可；设置变化时 UI 会调 [applySettings]。
 */
interface LuaMcpService {
    fun serverState(): LuaMcpServerState

    /** 设置页点"应用"后调用：按新的启用状态和端口启停/重启服务。 */
    fun applySettings()

    /** 服务状态、已连客户端、项目安装状态变化时通知（任意线程，UI 自己切 EDT）。 */
    fun addListener(listener: () -> Unit, parent: Disposable)

    /**
     * 可以安装到的根目录：版本管理根（多个时第一个是默认，含 .idea 的那个），没有 VCS 时是项目根。
     */
    fun installRoots(project: Project): List<Path>

    /** 每个支持的客户端在 [root] 下的安装状态。读文件，可能阻塞，UI 在后台调用。 */
    fun installStatus(project: Project, root: Path): List<LuaMcpClientStatus>

    /**
     * 写入/更新配置（合并不覆盖）。返回每个客户端的结果；失败项带要手动粘贴的片段。
     * 阻塞，UI 在后台调用，写完后 UI 负责刷新 VFS 和提示加入版本管理。
     */
    fun install(project: Project, root: Path, clients: Collection<LuaMcpClient>): List<LuaMcpInstallResult>

    fun uninstall(project: Project, root: Path, clients: Collection<LuaMcpClient>): List<LuaMcpInstallResult>

    /** 最近的 Agent 调用（新的在后），给调试窗口「Agent」页用。 */
    fun recentCalls(limit: Int = 200): List<LuaMcpCallRecord>

    /** 「撤销 Agent 控制」：收回本 IDE 所有会话已授予的控制权，之后再控制要重新确认。 */
    fun revokeAgentControl()

    /** 「清除 Agent 断点」：删掉 origin = agent 的断点。 */
    fun clearAgentBreakpoints(project: Project)

    companion object {
        val EP: ExtensionPointName<LuaMcpService> =
            ExtensionPointName.create("com.tang.intellij.lua.debug.mcpService")

        fun getInstance(): LuaMcpService? = EP.extensionList.firstOrNull()
    }
}

data class LuaMcpServerState(
    val enabled: Boolean,
    val running: Boolean,
    /** 实际监听的端口；被占用顺延时和设置里的不同。 */
    val port: Int,
    val connectedClients: Int = 0,
    /** 启动失败原因（中文）。 */
    val error: String? = null,
) {
    val url: String get() = "http://127.0.0.1:$port/mcp"
}

/** 支持写入配置的 MCP 客户端，见设计文档 6.5。 */
enum class LuaMcpClient(val displayName: String, val projectLevel: Boolean) {
    CLAUDE_CODE("Claude Code", true),
    CURSOR("Cursor", true),
    VSCODE("VS Code / Copilot", true),
    GEMINI("Gemini CLI", true),
    /** 写用户级 ~/.codex/config.toml，UI 要单独提示"这是全局配置"。 */
    CODEX("Codex", false),
}

data class LuaMcpClientStatus(
    val client: LuaMcpClient,
    val configFile: Path,
    /** 对应目录已存在（例如有 .cursor/），安装对话框默认勾选。 */
    val detected: Boolean,
    /** 配置里有 emmylua-debug 项。 */
    val installed: Boolean,
    /** 已安装但地址和当前端口不一致，需要更新。 */
    val outdated: Boolean = false,
    /** 文件解析失败等。 */
    val error: String? = null,
)

data class LuaMcpInstallResult(
    val client: LuaMcpClient,
    val configFile: Path,
    val success: Boolean,
    /** 失败原因（中文）。 */
    val message: String? = null,
    /** 失败时让用户手动粘贴的配置片段。 */
    val manualSnippet: String? = null,
    /** 这次是新建的文件（UI 据此提示是否加入版本管理）。 */
    val created: Boolean = false,
)

data class LuaMcpCallRecord(
    val timestampMillis: Long,
    val tool: String,
    val argsSummary: String,
    val resultSummary: String,
    val success: Boolean,
    /** 发起调用的客户端名（MCP initialize 里的 clientInfo.name）。 */
    val clientName: String? = null,
    val sessionId: String? = null,
)
