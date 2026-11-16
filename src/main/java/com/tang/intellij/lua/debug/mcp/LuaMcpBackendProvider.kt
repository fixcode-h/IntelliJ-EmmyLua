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

package com.tang.intellij.lua.debug.mcp

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.tang.intellij.lua.debugger.mcp.LuaDebugBackend

/**
 * 把某个项目的调试引擎包装成 MCP 工具层要的 [LuaDebugBackend]。
 *
 * 由新调试引擎的适配层实现（引擎的 LuaDebugSessionManager → LuaDebugBackend），注册到
 * `com.tang.intellij.lua.debug.mcpBackend`。没有实现时 MCP 照常启动，工具返回 engine_unavailable，
 * 这样 MCP 和引擎可以分开合入、分开发布。
 */
interface LuaMcpBackendProvider {
    /** 项目还没有引擎（例如还在初始化）时返回 null。 */
    fun backend(project: Project): LuaDebugBackend?

    companion object {
        val EP: ExtensionPointName<LuaMcpBackendProvider> =
            ExtensionPointName.create("com.tang.intellij.lua.debug.mcpBackend")

        fun backendFor(project: Project): LuaDebugBackend? =
            EP.extensionList.firstNotNullOfOrNull { runCatching { it.backend(project) }.getOrNull() }
    }
}
