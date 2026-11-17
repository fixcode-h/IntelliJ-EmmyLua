package com.fixcode.intellij.lua.debug.mcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.fixcode.intellij.lua.debug.api.LuaMcpCallRecord
import com.fixcode.intellij.lua.debug.api.LuaMcpServerState
import com.fixcode.intellij.lua.debug.api.LuaMcpService
import com.fixcode.intellij.lua.debugger.mcp.BreakpointFilter

/**
 * [LuaMcpService] 的实现：UI（设置页、提示、Agent 页）只认这个接口。
 * 状态都在应用级的 [LuaDebugMcpTools] 里，这个类本身无状态，由扩展点创建。
 */
class LuaMcpServiceImpl : LuaMcpService {
    private val log = Logger.getInstance(LuaMcpServiceImpl::class.java)

    private val tools: LuaDebugMcpTools get() = LuaDebugMcpTools.getInstance()

    override fun serverState(): LuaMcpServerState {
        val available = PluginManagerCore.getPlugin(MCP_SERVER_PLUGIN)?.let { PluginManagerCore.isLoaded(it.pluginId) } == true
        if (!available) return LuaMcpServerState(available = false, running = false)
        val server = IdeMcpServerStatus.read()
        return LuaMcpServerState(
            available = true,
            running = server.running,
            url = server.url,
            activeClients = tools.mcp.dispatcher.activeClientCount(),
        )
    }

    override fun openServerSettings(project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MCP_SETTINGS_ID)
    }

    override fun toolNames(): List<String> = tools.mcp.dispatcher.toolNames

    override fun addListener(listener: () -> Unit, parent: Disposable) {
        val sub = tools.addListener(listener)
        Disposer.register(parent) { sub.close() }
    }

    override fun recentCalls(limit: Int): List<LuaMcpCallRecord> =
        tools.mcp.callLog.recent(limit).map {
            LuaMcpCallRecord(
                timestampMillis = it.timestampMillis,
                tool = it.tool,
                argsSummary = it.argsSummary,
                resultSummary = it.resultSummary,
                success = it.success,
                clientName = it.clientName,
                sessionId = it.sessionId,
            )
        }

    override fun revokeAgentControl() {
        tools.mcp.policy.revokeAll()
        log.info("mcp.control.revoked")
    }

    override fun clearAgentBreakpoints(project: Project) {
        val backend = LuaMcpBackendProvider.backendFor(project) ?: return
        backend.removeBreakpoints(BreakpointFilter(all = true)).whenComplete { n, e ->
            if (e != null) log.warn("mcp.breakpoints.clear.failed", e) else log.info("mcp.breakpoints.cleared count=$n")
        }
    }

    companion object {
        /** JetBrains 自带的 MCP Server 插件；2026.3 拆成内容模块后插件 ID 没变。 */
        val MCP_SERVER_PLUGIN: PluginId = PluginId.getId("com.intellij.mcpServer")

        /** 「设置 → 工具 → MCP 服务器」的 id（2025.2–2026.3 一致）。 */
        const val MCP_SETTINGS_ID = "com.intellij.mcpserver.settings"
    }
}

/**
 * IDE 自带 MCP 服务器是否在跑、地址是什么。`McpServerService` 是它的内部类（2025.2–2026.3 都有
 * `Companion.getInstance()`、`isRunning()`、`getServerSseUrl()`，2026.2 起还有 `getServerStreamUrl()`），
 * 所以用反射读，读不到当作没在跑，不影响工具本身。
 */
internal object IdeMcpServerStatus {
    class Status(val running: Boolean, val url: String?)

    fun read(): Status = try {
        val cls = Class.forName("com.intellij.mcpserver.impl.McpServerService", false, pluginClassLoader())
        val companion = cls.getField("Companion").get(null)
        val service = companion.javaClass.getMethod("getInstance").invoke(companion)
        val running = cls.getMethod("isRunning").invoke(service) as Boolean
        val url = if (!running) null else {
            listOf("getServerStreamUrl", "getServerSseUrl").firstNotNullOfOrNull { name ->
                runCatching { cls.getMethod(name).invoke(service) as? String }.getOrNull()
            }
        }
        Status(running, url)
    } catch (e: Throwable) {
        Status(running = false, url = null)
    }

    private fun pluginClassLoader(): ClassLoader? =
        PluginManagerCore.getPlugin(LuaMcpServiceImpl.MCP_SERVER_PLUGIN)?.pluginClassLoader
}
