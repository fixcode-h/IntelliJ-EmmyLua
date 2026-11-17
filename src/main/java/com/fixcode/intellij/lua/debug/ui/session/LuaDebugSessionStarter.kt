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
import com.fixcode.intellij.lua.debug.ui.mcp.LuaMcpInstallPrompt

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
        var created: XDebugSession? = null
        val starter = starter(factory, target) { created = it }
        val manager = XDebuggerManager.getInstance(env.project)
        // 2025.3 起（拆分调试器）XDebugSession.getRunContentDescriptor 已弃用，调用会报错：
        // 改用 newSessionBuilder(...).environment(env).startSession() 的结果里的描述符。编译用的 2025.2 没有这套接口，反射调用
        val viaBuilder = startWithBuilder(manager, env, starter)
        if (viaBuilder != null) {
            // 拆分模式下前端拿到的可能不是这个对象，但 XDebugProcessStarter.start 一定在后端以真实会话调用
            created?.let { afterStart(env.project, it, target) }
            return viaBuilder
        }
        val session = manager.startSession(env, starter)
        afterStart(env.project, session, target)
        return session.runContentDescriptor
    }

    /** 新接口可用时返回描述符；接口不存在（2025.2）返回 null 走老办法。启动本身抛的异常原样抛出。 */
    @Throws(ExecutionException::class)
    private fun startWithBuilder(manager: XDebuggerManager, env: ExecutionEnvironment, starter: XDebugProcessStarter): RunContentDescriptor? {
        val newBuilder = XDebuggerManager::class.java.methods.firstOrNull {
            it.name == "newSessionBuilder" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(XDebugProcessStarter::class.java)
        } ?: return null
        // 方法按接口类型（XDebugSessionBuilder / XSessionStartedResult）取：实现类可能不是 public，直接用它的方法会被拒绝访问
        val builderType = newBuilder.returnType
        val withEnv = builderType.methods.firstOrNull {
            it.name == "environment" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(ExecutionEnvironment::class.java)
        } ?: return null
        val startSession = builderType.methods.firstOrNull { it.name == "startSession" && it.parameterCount == 0 } ?: return null
        val getDescriptor = startSession.returnType.methods.firstOrNull { it.name == "getRunContentDescriptor" && it.parameterCount == 0 }
            ?: return null
        val result = try {
            val builder = newBuilder.invoke(manager, starter) ?: return null
            val configured = withEnv.invoke(builder, env) ?: builder
            startSession.invoke(configured)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.targetException as? ExecutionException) ?: ExecutionException(e.targetException)
        } ?: return null
        return getDescriptor.invoke(result) as? RunContentDescriptor
            ?: throw ExecutionException("调试会话已启动，但 IDE 没有给出运行内容描述符，请把 IDE 版本反馈给我们")
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

    private fun starter(factory: LuaDebugProcessFactory, target: LuaDebugTarget, onCreate: (XDebugSession) -> Unit = {}) =
        object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                onCreate(session)
                return factory.createDebugProcess(session, target)
            }
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
        // 第一次启动 Lua 调试会话时检查一次：IDE 的 MCP 服务器开没开、它的地址写进本项目的 Agent 配置没有。
        LuaMcpInstallPrompt.maybeShow(project, LuaMcpInstallPrompt.Trigger.SESSION_START)
    }
}
