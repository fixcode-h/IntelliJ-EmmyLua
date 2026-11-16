package com.fixcode.intellij.lua.debug.mcp

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.fixcode.intellij.lua.debugger.mcp.LuaDebugBackend

/**
 * 把某个项目的调试引擎包装成 MCP 工具层要的 [LuaDebugBackend]。
 *
 * 由新调试引擎的适配层实现（引擎的 LuaDebugSessionManager → LuaDebugBackend），注册到
 * `com.fixcode.intellij.lua.debug.mcpBackend`。没有实现时 MCP 照常启动，工具返回 engine_unavailable，
 * 这样 MCP 和引擎可以分开合入、分开发布。
 */
interface LuaMcpBackendProvider {
    /** 项目还没有引擎（例如还在初始化）时返回 null。 */
    fun backend(project: Project): LuaDebugBackend?

    companion object {
        val EP: ExtensionPointName<LuaMcpBackendProvider> =
            ExtensionPointName.create("com.fixcode.intellij.lua.debug.mcpBackend")

        fun backendFor(project: Project): LuaDebugBackend? =
            EP.extensionList.firstNotNullOfOrNull { runCatching { it.backend(project) }.getOrNull() }
    }
}
