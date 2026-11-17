package com.fixcode.intellij.lua.debug.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * MCP 调试工具给 UI 用的门面：设置页、「MCP 服务器没开」提示、Agent 页都只认这个接口。
 * 由「MCP」实现并注册到 `com.fixcode.intellij.lua.debug.mcpService`。
 *
 * 工具注册在 IDE 自带的 MCP 服务器上（2025.2 起内置），端口和开关归它管；这里报告它的状态、给出打开它设置页的入口，
 * 并且「安装 MCP 到项目」：把 IDE 服务器的地址写进项目里各 Agent 的配置文件。是否允许任意 Lua 存在
 * [com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings]。
 */
interface LuaMcpService {
    /** IDE 自带 MCP 服务器的状态。读的是内存里的状态，可以在 EDT 上调。 */
    fun serverState(): LuaMcpServerState

    /** 打开「设置 → 工具 → MCP 服务器」。 */
    fun openServerSettings(project: Project?)

    /** 注册给 IDE MCP 服务器的工具名。 */
    fun toolNames(): List<String>

    /**
     * 可以安装到的根目录，第一个是推荐的：版本管理根（没有版本管理的 UE 项目用含 .uproject 的目录），
     * 然后是 IDE 识别的其他 VCS 根，最后是项目目录。读 VCS 配置，很快，可以在 EDT 上调。
     */
    fun installRoots(project: Project): List<Path>

    /** 每个支持的客户端在 [root] 下的安装状态。读文件，可能阻塞，UI 在后台调用。 */
    fun installStatus(project: Project, root: Path): List<LuaMcpClientStatus>

    /**
     * 写入/更新配置（合并不覆盖，写的是 IDE MCP 服务器的地址）。返回每个客户端的结果；失败项带要手动粘贴的片段。
     * 阻塞，UI 在后台调用，写完后 UI 负责刷新 VFS 和提示加入版本管理。
     */
    fun install(project: Project, root: Path, clients: Collection<LuaMcpClient>): List<LuaMcpInstallResult>

    fun uninstall(project: Project, root: Path, clients: Collection<LuaMcpClient>): List<LuaMcpInstallResult>

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
    /** 客户端连接用的地址：服务在跑时取 IDE 给出的；没跑时按设置里的端口拼（2026.1 起 `/stream`，之前 `/sse`）。 */
    val url: String? = null,
    /** 最近 10 分钟调用过 Lua 调试工具的客户端数。 */
    val activeClients: Int = 0,
    /** 写进客户端配置的条目名：照 IDE 自己「自动配置」用的（2025.x `jetbrains`，2026.1 起按产品名）。 */
    val serverName: String? = null,
    /** 端口或条目名读不到 IDE 的设置、是按默认值写的（弹窗里说明）。 */
    val guessed: Boolean = false,
    /** 这个版本的 IDE 要求请求带令牌头（名字），配置里写成从同名环境变量展开。 */
    val authHeader: String? = null,
)

/** 支持写入配置的 MCP 客户端。 */
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
    /** 配置里有 IDE MCP 服务器这一项。 */
    val installed: Boolean,
    /** 已安装但地址和 IDE 现在的不一致，或者还留着旧版的 emmylua-debug 项，需要更新。 */
    val outdated: Boolean = false,
    /** 还有旧版插件服务（9330 端口）留下的 emmylua-debug 项。 */
    val legacy: Boolean = false,
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
    /** 发起调用的客户端名（IDE MCP 服务器给出的 clientInfo.name，拿不到时为空）。 */
    val clientName: String? = null,
    val sessionId: String? = null,
)
