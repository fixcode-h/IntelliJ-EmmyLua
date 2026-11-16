package com.tang.intellij.lua.debug.xdebugger

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.tang.intellij.lua.debug.engine.BreakpointOrigin
import com.tang.intellij.lua.debug.engine.BreakpointRequest
import com.tang.intellij.lua.debug.engine.BreakpointVerification
import com.tang.intellij.lua.debug.engine.BreakpointView
import com.tang.intellij.lua.debug.engine.DebugEvent
import com.tang.intellij.lua.debug.engine.DebugSession
import com.tang.intellij.lua.debug.engine.Initiator
import com.tang.intellij.lua.debug.engine.PauseInfo
import com.tang.intellij.lua.debug.protocol.OutputCategories
import com.tang.intellij.lua.debug.protocol.PauseReasons
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.tang.intellij.lua.debugger.LuaLineBreakpointType
import com.tang.intellij.lua.debugger.LuaTracebackFilter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * XDebugger 适配层：把引擎会话（[DebugSession]）接到 IntelliJ 的调试 UI 上。
 * 只做翻译，不持有调试状态；断点、暂停、变量都以引擎为准，MCP 看到的是同一份。
 *
 * 引擎事件在会话线程上回调；XDebugSession 的 positionReached / breakpointReached 等方法本身线程安全，
 * 控制台输出也是，所以这里不切 EDT。
 */
class LuaV3DebugProcess(
    session: XDebugSession,
    val engine: DebugSession
) : XDebugProcess(session) {
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val breakpointIds = ConcurrentHashMap<XLineBreakpoint<*>, CompletableFuture<String>>()
    private val subscription: AutoCloseable
    @Volatile
    private var stopping = false

    init {
        session.setPauseActionSupported(true)
        subscription = engine.events.subscribe(::onEvent)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(BreakpointHandler())

    override fun sessionInitialized() {
        super.sessionInitialized()
        session.consoleView?.addMessageFilter(LuaTracebackFilter(session.project))
        print("[EmmyLua] 正在连接 ${engine.config.name} …\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        engine.start().whenComplete { welcome, error ->
            if (error != null) {
                val message = messageOf(error)
                print("[EmmyLua] 连接失败：$message\n", ConsoleViewContentType.ERROR_OUTPUT)
                session.reportMessage(message, MessageType.ERROR)
                session.stop()
                return@whenComplete
            }
            val lua = welcome.lua?.displayName ?: "Lua"
            val process = welcome.process?.let { " · ${it.name} (${it.pid})" } ?: ""
            print("[EmmyLua] 已连接：$lua$process · runtime ${welcome.runtime.version}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }

    // ---------- 执行控制 ----------

    override fun resume(context: XSuspendContext?) = control(context) { vmId -> engine.resume(vmId) }

    override fun startStepOver(context: XSuspendContext?) = control(context) { vmId -> engine.stepOver(vmId) }

    override fun startStepInto(context: XSuspendContext?) = control(context) { vmId -> engine.stepInto(vmId) }

    override fun startStepOut(context: XSuspendContext?) = control(context) { vmId -> engine.stepOut(vmId) }

    override fun startPausing() {
        engine.pause().whenComplete { _, error -> if (error != null) reportError(error) }
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) =
        control(context) { vmId -> engine.runToLine(position.file.path, position.line + 1, vmId) }

    override fun stop() {
        stopping = true
        subscription.close()
        breakpointIds.clear()
        ApplicationManager.getApplication().executeOnPooledThread { engine.close() }
    }

    /**
     * 控制命令失败时（被 runtime 拒绝、超时、断线），引擎保持暂停；XDebugger 已经把 UI 切到运行态，
     * 这里把暂停重新呈现回去并提示原因，避免「界面显示在运行、游戏却停在断点上」。
     */
    private fun control(context: XSuspendContext?, action: (Long?) -> CompletableFuture<Unit>) {
        val pause = (context as? LuaV3SuspendContext)?.pause ?: engine.focusedPause()
        action(pause?.vmId).whenComplete { _, error ->
            if (error == null) return@whenComplete
            reportError(error)
            val current = pause?.takeIf { engine.isCurrent(it) } ?: return@whenComplete
            session.positionReached(LuaV3SuspendContext(engine, current))
        }
    }

    // ---------- 引擎事件 ----------

    private fun onEvent(event: DebugEvent) {
        if (stopping) return
        when (event) {
            is DebugEvent.Paused -> if (event.focused) present(event.pause)
            is DebugEvent.FocusChanged -> event.pause?.let(::present)
            is DebugEvent.Resumed -> {
                // 用户在 IDE 里点的继续，XDebugger 已经切到运行态；Agent / 断线 / 宿主恢复的要通知 UI
                if (event.initiator != Initiator.USER && engine.focusedPause() == null) session.sessionResumed()
            }
            is DebugEvent.BreakpointsChanged -> event.breakpoints.forEach(::updateBreakpointState)
            is DebugEvent.Output -> printOutput(event)
            is DebugEvent.Disconnected -> print("[EmmyLua] 连接断开：${event.reason}，正在重连…\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
            is DebugEvent.Connected -> if (event.reconnect) print("[EmmyLua] 已重新连接，断点已重新下发。\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            is DebugEvent.SourceMismatch -> print(
                "[EmmyLua] ${event.chunkName ?: event.breakpointId}：设备上的代码和本地文件不一致，断点行号可能不准。\n",
                ConsoleViewContentType.LOG_WARNING_OUTPUT
            )
            is DebugEvent.Terminated -> {
                print("[EmmyLua] ${event.reason}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                session.stop()
            }
            else -> Unit
        }
    }

    private fun present(pause: PauseInfo) {
        val context = LuaV3SuspendContext(engine, pause)
        val breakpoint = if (pause.reason == PauseReasons.BREAKPOINT) breakpointFor(pause.breakpointIds) else null
        pause.exception?.let { print("[EmmyLua] 出错暂停：${it.message}\n${it.traceback ?: ""}\n", ConsoleViewContentType.ERROR_OUTPUT) }
        if (breakpoint != null) {
            // 条件、日志断点都在 runtime 侧处理过了，这里不再求值；返回 false 只可能是 IDE 侧挂起策略为「不挂起」
            if (!session.breakpointReached(breakpoint, null, context)) engine.resume(pause.vmId)
        } else {
            session.positionReached(context)
        }
    }

    private fun breakpointFor(ids: List<String>): XLineBreakpoint<*>? {
        if (ids.isEmpty()) return null
        return breakpointIds.entries.firstOrNull { (_, future) -> future.getNow(null) in ids }?.key
    }

    private fun printOutput(event: DebugEvent.Output) {
        val type = when (event.output.category) {
            OutputCategories.STDERR -> ConsoleViewContentType.ERROR_OUTPUT
            OutputCategories.RUNTIME -> if (event.output.level == "error") ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.LOG_INFO_OUTPUT
            OutputCategories.LOGPOINT -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        print(event.output.text, type)
    }

    private fun print(text: String, type: ConsoleViewContentType) {
        session.consoleView?.print(text, type)
    }

    private fun reportError(error: Throwable) {
        val message = messageOf(error)
        print("[EmmyLua] $message\n", ConsoleViewContentType.ERROR_OUTPUT)
        session.reportMessage(message, MessageType.WARNING)
    }

    // ---------- 断点 ----------

    private fun updateBreakpointState(view: BreakpointView) {
        val breakpoint = breakpointIds.entries.firstOrNull { (_, future) -> future.getNow(null) == view.id }?.key ?: return
        when (view.verification) {
            BreakpointVerification.VERIFIED -> session.setBreakpointVerified(breakpoint)
            BreakpointVerification.INVALID -> session.setBreakpointInvalid(breakpoint, view.message)
            BreakpointVerification.MISMATCH, BreakpointVerification.AMBIGUOUS -> session.setBreakpointInvalid(breakpoint, view.message)
            BreakpointVerification.PENDING -> Unit
        }
    }

    private inner class BreakpointHandler :
        XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(LuaLineBreakpointType::class.java) {
        override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
            val request = requestOf(breakpoint) ?: return
            breakpointIds[breakpoint] = engine.addBreakpoint(request)
        }

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) {
            breakpointIds.remove(breakpoint)?.thenAccept { id -> engine.removeBreakpoint(id) }
        }
    }

    private fun requestOf(breakpoint: XLineBreakpoint<*>): BreakpointRequest? {
        val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl) ?: return null
        val logMessage = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }
        return BreakpointRequest(
            file = file.path,
            line = breakpoint.line + 1,
            condition = breakpoint.conditionExpression?.expression?.takeIf { it.isNotBlank() },
            logMessage = logMessage,
            // IDE 的「不挂起」= 只记日志不停
            autoContinue = breakpoint.suspendPolicy == SuspendPolicy.NONE,
            origin = BreakpointOrigin.IDE
        )
    }
}
