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

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.tang.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.tang.intellij.lua.debugger.mcp.CompositeLuaDebugBackend
import com.tang.intellij.lua.debugger.mcp.EmmyLuaMcpServer
import com.tang.intellij.lua.debugger.mcp.LuaDebugBackend
import com.tang.intellij.lua.debugger.mcp.protocol.McpLogger
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用级：整个 IDE 一个 MCP 端口（配置文件里写的就是这个地址），多个打开的项目合成一个引擎（[CompositeLuaDebugBackend]）。
 * 第一个项目打开时启动（[LuaMcpStartupActivity]），IDE 退出时随服务销毁而停止。
 */
@Service(Service.Level.APP)
class LuaMcpServer : Disposable {
    private val log = Logger.getInstance(LuaMcpServer::class.java)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var lastError: String? = null

    val mcp: EmmyLuaMcpServer = EmmyLuaMcpServer(
        backend = ::compositeBackend,
        confirmer = IdeMcpConfirmer(),
        version = pluginVersion(),
        logger = McpLogger { level, event, message, fields, error ->
            val line = buildString {
                append(event)
                fields.forEach { (k, v) -> if (v != null) append(' ').append(k).append('=').append(v) }
                if (message != null) append(" · ").append(message)
            }
            when (level) {
                McpLogger.Level.DEBUG -> log.debug(line)
                McpLogger.Level.INFO -> log.info(line)
                McpLogger.Level.WARN -> log.warn(line)
                // 内部错误带堆栈，但用 warn 级别：error 级别会在 IDE 里弹红色异常提示，打扰用户
                McpLogger.Level.ERROR -> log.warn(line, error)
            }
        },
    )

    init {
        // 客户端连接、调用、授权变化都通知 UI（状态栏、Agent 页）
        mcp.protocol.addListener(::fireChanged)
        mcp.callLog.addListener { fireChanged() }
        mcp.policy.addListener(::fireChanged)
    }

    val isRunning: Boolean get() = mcp.isRunning
    val port: Int get() = if (mcp.isRunning) mcp.port else LuaDebugSettings.getInstance().mcpPort
    val error: String? get() = lastError

    /** 按设置启停：启用且没在跑就启动；端口变了就重启；关闭就停。 */
    @Synchronized
    fun applySettings() {
        val settings = LuaDebugSettings.getInstance()
        if (!settings.mcpEnabled) {
            if (mcp.isRunning) {
                mcp.stop()
                log.info("mcp.server.stopped reason=disabled")
            }
            lastError = null
            fireChanged()
            return
        }
        val wanted = settings.mcpPort
        // 已经在跑：设置的端口没变（含被占用顺延的情况）就不动
        if (mcp.isRunning && (mcp.port == wanted || startedFrom == wanted)) return
        try {
            val actual = mcp.start(wanted)
            startedFrom = wanted
            lastError = null
            log.info("mcp.server.started port=$actual" + if (actual != wanted) " requested=$wanted" else "")
        } catch (e: IOException) {
            lastError = e.message
            log.warn("mcp.server.failed port=$wanted", e)
        }
        fireChanged()
    }

    /** 实际端口是从设置里哪个端口顺延来的。 */
    @Volatile
    private var startedFrom: Int = -1

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    fun fireChanged() = listeners.forEach { runCatching { it() } }

    private fun compositeBackend(): LuaDebugBackend? {
        if (LuaMcpBackendProvider.EP.extensionList.isEmpty()) return null
        val composite = CompositeLuaDebugBackend {
            ProjectManager.getInstance().openProjects
                .filter { !it.isDisposed && !it.isDefault }
                .mapNotNull { project ->
                    val backend = LuaMcpBackendProvider.backendFor(project) ?: return@mapNotNull null
                    val roots = buildList {
                        project.basePath?.let { add(Path.of(it)) }
                        runCatching {
                            ReadAction.compute<List<Path>, RuntimeException> {
                                ProjectRootManager.getInstance(project).contentRoots.map { VfsUtilCore.virtualToIoFile(it).toPath() }
                            }
                        }.getOrNull()?.let { addAll(it) }
                    }
                    CompositeLuaDebugBackend.Member(project.name, roots.map { it.toAbsolutePath().normalize() }.distinct(), backend)
                }
                .sortedByDescending { it.name == focusedProjectName() }
        }
        return composite
    }

    private fun focusedProjectName(): String? =
        com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.name

    override fun dispose() {
        mcp.stop()
        listeners.clear()
    }

    companion object {
        fun getInstance(): LuaMcpServer = service()

        private fun pluginVersion(): String =
            PluginManagerCore.getPlugin(PluginId.getId("com.fixcode.emmylua.enhanced"))?.version ?: "dev"
    }
}
