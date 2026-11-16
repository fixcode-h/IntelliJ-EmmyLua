package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.fixcode.intellij.lua.debug.api.LuaDebugSessionModel
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaMappingStatus
import com.fixcode.intellij.lua.debug.api.LuaSourceMappingEntry
import com.fixcode.intellij.lua.debug.api.LuaVmInfo
import com.fixcode.intellij.lua.debug.api.LuaVmState
import com.fixcode.intellij.lua.debug.engine.BreakpointOrigin
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.BreakpointVerification
import com.fixcode.intellij.lua.debug.engine.BreakpointView
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.Initiator
import com.fixcode.intellij.lua.debug.engine.PauseInfo
import com.fixcode.intellij.lua.debug.engine.SourceResolution
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.ExceptionModes
import com.fixcode.intellij.lua.debug.protocol.OutputCategories
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import com.fixcode.intellij.lua.debug.protocol.VmStates
import com.fixcode.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.fixcode.intellij.lua.debugger.LuaLineBreakpointType
import com.fixcode.intellij.lua.debugger.LuaTracebackFilter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * XDebugger 适配层：把引擎会话（[DebugSession]）接到 IntelliJ 的调试 UI 上。
 * 只做翻译，不持有调试状态；断点、暂停、变量都以引擎为准，MCP 看到的是同一份。
 *
 * 引擎事件在会话线程上回调；XDebugSession 的 positionReached / breakpointReached 等方法本身线程安全，
 * 控制台输出也是，所以这里不切 EDT。
 */
class LuaV3DebugProcess(
    session: XDebugSession,
    val engine: DebugSession,
    val target: LuaDebugTarget,
    /** 会话结束后要清理的建连资源（例如 adb 转发），在后台线程调用。 */
    private val onClosed: () -> Unit = {}
) : XDebugProcess(session), LuaDebugSessionModel {
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val breakpointIds = ConcurrentHashMap<XLineBreakpoint<*>, CompletableFuture<String>>()
    private val subscription: AutoCloseable
    @Volatile
    private var stopping = false
    private val modelListeners = CopyOnWriteArrayList<LuaDebugSessionModel.Listener>()
    /** runtime 报过内容不一致的 chunk（热更新、没同步），「映射」页标成 HASH_MISMATCH。 */
    private val mismatchedChunks = ConcurrentHashMap.newKeySet<String>()
    /** 用户在 VM 页选的 VM；没有暂停时栈和变量为空，选择只是记下来。 */
    @Volatile
    private var selectedVmId: Long? = null

    init {
        session.setPauseActionSupported(true)
        subscription = engine.events.subscribe(::onEvent)
        LuaDebugSessionManager.getInstance(session.project).register(LuaDebugSessionManager.Entry(engine, target, session))
        session.addSessionListener(InlineValuesListener())
    }

    /** 编辑器行尾的变量值跟着「当前选中的栈帧」走：暂停、切帧时取快照，继续运行或结束时清掉。 */
    private inner class InlineValuesListener : XDebugSessionListener {
        private val store get() = LuaInlineValueStore.getInstance(session.project)

        override fun sessionPaused() = loadSelected()

        override fun stackFrameChanged() = loadSelected()

        override fun sessionResumed() = store.clear(session)

        override fun sessionStopped() = store.clear(session)

        private fun loadSelected() {
            val frame = session.currentStackFrame as? LuaV3StackFrame
                ?: (session.suspendContext?.activeExecutionStack?.topFrame as? LuaV3StackFrame)
                ?: return store.clear(session)
            store.load(session, engine, frame.handle)
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(BreakpointHandler(), ExceptionBreakpointHandler())

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
        if (!session.project.isDisposed) LuaDebugSessionManager.getInstance(session.project).unregister(engine.id)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                engine.close()
            } finally {
                onClosed()
            }
        }
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
        notifyModel(event)
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
        val exceptionBreakpoint = if (pause.reason == PauseReasons.EXCEPTION) exceptionBreakpoints.firstOrNull() else null
        if (exceptionBreakpoint != null) {
            session.breakpointReached(exceptionBreakpoint, pause.exception?.message, context)
        } else if (breakpoint != null) {
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

    /** 建连过程的日志（注入、转发每一步），显示在调试控制台。任意线程可调。 */
    fun log(text: String) = print("[EmmyLua] $text\n", ConsoleViewContentType.SYSTEM_OUTPUT)

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

    /** 启用中的「Lua 错误」断点；全部关掉时 runtime 回到不停（exceptions = none）。 */
    private val exceptionBreakpoints = java.util.concurrent.CopyOnWriteArrayList<XBreakpoint<LuaExceptionBreakpointProperties>>()

    private inner class ExceptionBreakpointHandler :
        XBreakpointHandler<XBreakpoint<LuaExceptionBreakpointProperties>>(LuaExceptionBreakpointType::class.java) {
        override fun registerBreakpoint(breakpoint: XBreakpoint<LuaExceptionBreakpointProperties>) {
            exceptionBreakpoints.remove(breakpoint)
            exceptionBreakpoints += breakpoint
            applyExceptionMode()
        }

        override fun unregisterBreakpoint(breakpoint: XBreakpoint<LuaExceptionBreakpointProperties>, temporary: Boolean) {
            exceptionBreakpoints.remove(breakpoint)
            applyExceptionMode()
        }
    }

    private fun applyExceptionMode() {
        val mode = when {
            exceptionBreakpoints.isEmpty() -> ExceptionModes.NONE
            exceptionBreakpoints.any { it.properties?.includeCaught == true } -> ExceptionModes.ALL
            else -> ExceptionModes.UNCAUGHT
        }
        engine.updateOptions(SetOptionsArgs(exceptions = mode)).whenComplete { _, error -> if (error != null) reportError(error) }
        if (mode != ExceptionModes.NONE && engine.welcome != null && !engine.hasCapability(Capabilities.EXCEPTIONS)) {
            print("[EmmyLua] 这个 runtime 还不支持出错即停，「Lua 错误」断点暂不生效；未捕获的错误仍会输出到控制台。\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)
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

    // ---------- 调试窗口的 VM / 映射页（LuaDebugSessionModel） ----------

    override fun vms(): List<LuaVmInfo> = engine.vms().map { vm ->
        LuaVmInfo(
            vmId = vm.vmId.toString(),
            displayName = vm.name,
            state = when {
                vm.paused -> LuaVmState.PAUSED
                vm.state == VmStates.CLOSING -> LuaVmState.CLOSED
                else -> LuaVmState.RUNNING
            },
            sourceEpoch = vm.sourceEpoch,
            luaVersion = vm.luaVersion
        )
    }

    override fun currentVmId(): String? {
        val vms = engine.vms()
        val id = engine.focusedPause()?.vmId
            ?: selectedVmId?.takeIf { selected -> vms.any { it.vmId == selected } }
            ?: vms.firstOrNull()?.vmId
        return id?.toString()
    }

    override fun selectVm(vmId: String) {
        val id = vmId.toLongOrNull() ?: return
        selectedVmId = id
        // 选中的 VM 停着时切焦点，引擎发 FocusChanged，onEvent 里重新呈现它的栈
        if (engine.pauses().any { it.vmId == id }) engine.focus(id)
        fireModel { it.vmsChanged() }
    }

    override fun sourceMappings(): List<LuaSourceMappingEntry> = engine.sourceMapper.entries().mapNotNull { entry ->
        when (val resolution = entry.resolution) {
            is SourceResolution.Mapped -> LuaSourceMappingEntry(
                entry.chunkName, resolution.localPath,
                if (entry.chunkName in mismatchedChunks) LuaMappingStatus.HASH_MISMATCH else LuaMappingStatus.MAPPED
            )
            is SourceResolution.Ambiguous -> LuaSourceMappingEntry(entry.chunkName, null, LuaMappingStatus.AMBIGUOUS, resolution.candidates)
            SourceResolution.Unmapped -> LuaSourceMappingEntry(entry.chunkName, null, LuaMappingStatus.UNMAPPED)
            SourceResolution.NotAFile -> null
        }
    }

    override fun overrideMapping(chunkName: String, localPath: String) {
        mismatchedChunks.remove(chunkName)
        engine.overrideMapping(chunkName, localPath).whenComplete { _, error ->
            if (error != null) reportError(error)
            fireModel { it.mappingsChanged() }
        }
    }

    override fun addListener(listener: LuaDebugSessionModel.Listener, parent: Disposable) {
        modelListeners += listener
        Disposer.register(parent) { modelListeners -= listener }
    }

    private fun notifyModel(event: DebugEvent) {
        if (modelListeners.isEmpty()) return
        when (event) {
            is DebugEvent.VmAdded, is DebugEvent.VmChanged, is DebugEvent.VmClosed,
            is DebugEvent.Resumed, is DebugEvent.FocusChanged -> fireModel { it.vmsChanged() }
            // 暂停时栈帧会解析新的 chunk，映射表可能多出几项
            is DebugEvent.Paused -> fireModel { it.vmsChanged(); it.mappingsChanged() }
            is DebugEvent.SourceMismatch -> {
                event.chunkName?.let(mismatchedChunks::add)
                fireModel { it.mappingsChanged() }
            }
            is DebugEvent.BreakpointsChanged -> fireModel { it.mappingsChanged() }
            else -> Unit
        }
    }

    /** 接口约定监听器在 EDT 上收到通知。 */
    private fun fireModel(action: (LuaDebugSessionModel.Listener) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!session.project.isDisposed) modelListeners.forEach(action)
        }
    }
}
