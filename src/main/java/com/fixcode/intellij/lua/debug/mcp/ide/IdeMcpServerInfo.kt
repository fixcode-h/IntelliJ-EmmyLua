package com.fixcode.intellij.lua.debug.mcp.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.fixcode.intellij.lua.debugger.mcp.install.McpInstaller
import com.fixcode.intellij.lua.debugger.mcp.install.McpServerEndpoint
import com.fixcode.intellij.lua.debugger.mcp.install.McpTransport

/**
 * IDE 自带 MCP 服务器在不在跑、客户端该连哪个地址、IDE 自己的「自动配置」用什么条目名。
 *
 * 和 [IdeMcpCompat] 一样全靠反射读，但单独一个类：IdeMcpCompat 的签名里直接用了 MCP Server 插件的类，
 * 而这里在 MCP Server 插件被禁用时也要能调（设置页、安装弹窗都会问），所以不碰任何它的类型，类都从它的类加载器按名字取。
 * 读不到的一律用默认值，并在 [Info] 里标出来，弹窗据此说明「按默认值写的」。
 *
 * | 版本 | 地址 | 条目名 | 令牌 |
 * | --- | --- | --- | --- |
 * | 2025.2–2025.3 | `McpServerService.getServerSseUrl()`：`http://127.0.0.1:<端口>/sse`（只有 SSE） | `jetbrains` | 无 |
 * | 2026.1–2026.3 | 另有 `getServerStreamUrl()`：`/stream`（Streamable HTTP，自动配置优先用它） | `McpClient.Companion.productSpecificServerKey()`，按产品名，如 `idea`、`rider`；旧的 `jetbrains` 被它删掉 | 无 |
 * | 之后（主干） | 同上 | 同上 | `getGlobalAuthToken()`：请求头 `IJ_MCP_AUTH_TOKEN`，项目级配置写成从环境变量展开 |
 *
 * 端口：服务在跑时用 `getPort()`（端口被占时 IDE 会顺延并写回设置）；没跑时读 `McpServerSettings` 的 `mcpServerPort`
 * （2025.2 在 `getState()` 上，2026.x 直接在设置上），再读不到用默认 64342。
 */
internal object IdeMcpServerInfo {
    /** JetBrains 自带的 MCP Server 插件；2026.3 拆成内容模块后插件 ID 没变。 */
    val PLUGIN_ID: PluginId = PluginId.getId("com.intellij.mcpServer")

    private const val PKG = "com.intellij.mcpserver"
    private const val AUTH_HEADER = "IJ_MCP_AUTH_TOKEN"

    data class Info(
        /** IDE 里有 MCP Server 插件且已加载。 */
        val available: Boolean,
        val running: Boolean,
        val port: Int,
        val transport: McpTransport,
        val url: String,
        val serverName: String,
        val authHeader: String?,
        /** 端口是按默认值猜的（读不到 IDE 的设置）。 */
        val portGuessed: Boolean,
        /** 条目名是按默认值猜的。 */
        val serverNameGuessed: Boolean,
    ) {
        val endpoint: McpServerEndpoint get() = McpServerEndpoint(url, transport, serverName, authHeader)
    }

    fun read(): Info {
        val loader = loader()
        val available = loader != null
        val serviceCls = loader?.let { runCatching { Class.forName("$PKG.impl.McpServerService", false, it) }.getOrNull() }
        val service = serviceCls?.let { cls -> runCatching { companionCall(cls, "getInstance") }.getOrNull() }
        val running = service != null && runCatching { serviceCls.getMethod("isRunning").invoke(service) as Boolean }.getOrDefault(false)
        val streamSupported = serviceCls?.methods?.any { it.name == "getServerStreamUrl" } == true
        val transport = if (streamSupported) McpTransport.STREAMABLE_HTTP else McpTransport.SSE
        val authHeader = if (serviceCls?.methods?.any { it.name == "getGlobalAuthToken" } == true) AUTH_HEADER else null

        var port: Int? = null
        var url: String? = null
        if (running) {
            port = runCatching { serviceCls!!.getMethod("getPort").invoke(service) as Int }.getOrNull()
            val getter = if (streamSupported) "getServerStreamUrl" else "getServerSseUrl"
            url = runCatching { serviceCls!!.getMethod(getter).invoke(service) as? String }.getOrNull()
        }
        if (port == null) port = loader?.let { settingsPort(it) }
        val portGuessed = port == null
        val finalPort = port ?: defaultPort(loader) ?: McpInstaller.DEFAULT_IDE_PORT
        val serverName = loader?.let { serverName(it) }
        return Info(
            available = available,
            running = running,
            port = finalPort,
            transport = transport,
            url = url ?: McpInstaller.url(finalPort, transport),
            serverName = serverName ?: McpInstaller.DEFAULT_SERVER_NAME,
            authHeader = authHeader,
            portGuessed = portGuessed,
            serverNameGuessed = serverName == null && streamSupported, // 2025.x 本来就是 jetbrains，不算猜
        )
    }

    private fun loader(): ClassLoader? {
        val plugin = PluginManagerCore.getPlugin(PLUGIN_ID) ?: return null
        if (!PluginManagerCore.isLoaded(plugin.pluginId)) return null
        return plugin.pluginClassLoader
    }

    private fun companionCall(cls: Class<*>, method: String): Any? {
        val companion = cls.getField("Companion").get(null)
        return companion.javaClass.getMethod(method).invoke(companion)
    }

    private fun settingsPort(loader: ClassLoader): Int? = runCatching {
        val cls = Class.forName("$PKG.settings.McpServerSettings", false, loader)
        val settings = companionCall(cls, "getInstance") ?: return null
        // 2026.x：设置接口上直接有 getMcpServerPort；2025.2：在 getState() 返回的 MyState 上
        runCatching { settings.javaClass.getMethod("getMcpServerPort").invoke(settings) as Int }.getOrElse {
            val state = settings.javaClass.getMethod("getState").invoke(settings)
            state.javaClass.getMethod("getMcpServerPort").invoke(state) as Int
        }
    }.getOrNull()?.takeIf { it in 1..65535 }

    private fun defaultPort(loader: ClassLoader?): Int? = loader?.let {
        runCatching {
            val cls = Class.forName("$PKG.settings.McpServerSettings", false, it)
            runCatching { cls.getField("DEFAULT_MCP_PORT").getInt(null) }.getOrElse { companionCall(cls, "getDEFAULT_MCP_PORT") as Int }
        }.getOrNull()
    }

    /** 2026.1 起 IDE 按产品名起条目名；2025.x 没有这个方法，用 jetbrains。 */
    private fun serverName(loader: ClassLoader): String? = runCatching {
        val cls = Class.forName("$PKG.clients.McpClient", false, loader)
        companionCall(cls, "productSpecificServerKey") as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
