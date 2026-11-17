package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.execution.ExecutionException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.fixcode.intellij.lua.debug.api.LuaDebugProcessFactory
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpEnablePrompt

/**
 * 运行配置、Attach to Process、MCP 的 attach_* 三个入口都从这里起会话，
 * 保证它们看到的是同一种会话：同样的页签、同样的 MCP 提示。
 */
object LuaDebugSessionStarter {
    /** 正在调试的本机进程，「Lua 附加」据此跳过已附加的进程。 */
    private val attachedPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun isAttached(pid: Int): Boolean = pid in attachedPids

    /** 运行配置入口：返回给 ProgramRunner 的 RunContentDescriptor。 */
    @Throws(ExecutionException::class)
    fun start(env: ExecutionEnvironment, target: LuaDebugTarget): RunContentDescriptor {
        val factory = requireFactory(target)
        val session = XDebuggerManager.getInstance(env.project).startSession(env, starter(factory, target))
        afterStart(env.project, session, target)
        return session.runContentDescriptor
    }

    /** Attach to Process / MCP 入口：没有运行配置，直接开调试页签。 */
    @Throws(ExecutionException::class)
    fun startWithoutConfiguration(project: Project, target: LuaDebugTarget): XDebugSession {
        val factory = requireFactory(target)
        val session = XDebuggerManager.getInstance(project)
            .startSessionAndShowTab(target.displayName, null, starter(factory, target))
        afterStart(project, session, target)
        return session
    }

    /** 引擎没注册（并行开发期间、或被禁用）时给出明确提示，而不是空指针。 */
    @Throws(ExecutionException::class)
    private fun requireFactory(target: LuaDebugTarget): LuaDebugProcessFactory =
        LuaDebugProcessFactory.find(target)
            ?: throw ExecutionException(LuaDebugBundle.message("session.error.noEngine", target.displayName))

    private fun starter(factory: LuaDebugProcessFactory, target: LuaDebugTarget) = object : XDebugProcessStarter() {
        override fun start(session: XDebugSession): XDebugProcess = factory.createDebugProcess(session, target)
    }

    private fun afterStart(project: Project, session: XDebugSession, target: LuaDebugTarget) {
        if (target is LuaDebugTarget.LocalProcess) {
            attachedPids += target.pid
            session.addSessionListener(object : com.intellij.xdebugger.XDebugSessionListener {
                override fun sessionStopped() { attachedPids -= target.pid }
            })
        }
        // 页签要等会话 UI 建好再加；startSession 返回时通常已经建好，保险起见放到下一轮 EDT。
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && !session.isStopped) LuaDebugSessionTabs.install(project, session)
        }, project.disposed)
        // 第一次启动 Lua 调试会话时检查 IDE 的 MCP 服务器开没开（Lua 调试工具挂在它上面）。
        LuaMcpEnablePrompt.maybeShow(project)
    }
}
