package com.fixcode.intellij.lua.debug.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.fixcode.intellij.lua.debugger.mcp.CompositeLuaDebugBackend
import com.fixcode.intellij.lua.debugger.mcp.EmmyLuaDebugMcp
import com.fixcode.intellij.lua.debugger.mcp.LuaDebugBackend
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpLogger
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用级：Lua 调试工具的唯一一份状态（授权、调用记录、各客户端看过的暂停）。
 * 工具由 [com.fixcode.intellij.lua.debug.mcp.ide.LuaMcpToolsProvider] 注册到 IDE 自带的 MCP 服务器，
 * 调用都转到这里的 [mcp]；多个打开的项目合成一个引擎（[CompositeLuaDebugBackend]）。
 */
@Service(Service.Level.APP)
class LuaDebugMcpTools : Disposable {
    private val log = Logger.getInstance(LuaDebugMcpTools::class.java)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val mcp: EmmyLuaDebugMcp = EmmyLuaDebugMcp(
        backend = ::compositeBackend,
        confirmer = IdeMcpConfirmer(),
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
        // 调用、授权变化都通知 UI（Agent 页）
        mcp.dispatcher.addListener(::fireChanged)
        mcp.policy.addListener(::fireChanged)
    }

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    fun fireChanged() = listeners.forEach { runCatching { it() } }

    private fun compositeBackend(): LuaDebugBackend? {
        if (LuaMcpBackendProvider.EP.extensionList.isEmpty()) return null
        return CompositeLuaDebugBackend {
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
    }

    private fun focusedProjectName(): String? =
        com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.name

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(): LuaDebugMcpTools = service()
    }
}
