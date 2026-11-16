package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.project.Project
import com.fixcode.intellij.lua.debug.mcp.LuaMcpBackendProvider
import com.fixcode.intellij.lua.debugger.mcp.LuaDebugBackend

/** 把新引擎交给 MCP：每个项目一个 [LuaEngineMcpBackend]。 */
class LuaEngineMcpBackendProvider : LuaMcpBackendProvider {
    override fun backend(project: Project): LuaDebugBackend? =
        if (project.isDisposed || project.isDefault) null else LuaEngineMcpBackend.getInstance(project)
}
