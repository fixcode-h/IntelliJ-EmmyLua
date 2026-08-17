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

package com.tang.intellij.lua.debugger.emmy

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.tang.intellij.lua.debugger.*
import com.tang.intellij.lua.debugger.core.DebugSessionEvent
import com.tang.intellij.lua.debugger.core.DebugSessionController
import com.tang.intellij.lua.debugger.core.DebugSessionState
import com.tang.intellij.lua.debugger.core.RequestBroker
import com.tang.intellij.lua.debugger.core.RequestRegistry
import com.tang.intellij.lua.debugger.resources.DebuggerResourceService
import com.tang.intellij.lua.psi.LuaFileManager
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.project.LuaProjectSettings
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

abstract class EmmyDebugProcessBase(session: XDebugSession) : LuaDebugProcess(session), ITransportHandler {
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val evaluationRequests = RequestBroker(
        RequestRegistry<EvalRsp>(
            defaultTimeoutMillis = 10_000,
            callbackErrorHandler = { error -> this.error("Emmy evaluation callback failed: ${error.message}") }
        )
    )
    private val breakpoints = mutableMapOf<Int, BreakPoint>()
    private val breakpointIds = IdentityHashMap<XLineBreakpoint<*>, Int>()
    private var idCounter = 0;
    private var temporaryBreakpoint: BreakPoint? = null
    private val failureTerminationStarted = AtomicBoolean()
    protected var transporter: Transporter? = null
    private lateinit var targetBootstrap: EmmyTargetBootstrap
    private val lifecycle = DebugSessionController(
        threadName = "Emmy-Session",
        transitionListener = { transition ->
            log(
                "Emmy session: ${transition.previous} --${transition.event}--> ${transition.current}",
                DebugLogLevel.DEBUG
            )
        },
        errorHandler = { error ->
            this.error("Emmy session event failed: ${error.message}")
            terminateFailedSession()
        }
    )
    @Volatile private var sessionGeneration = 0L

    override fun sessionInitialized() {
        super.sessionInitialized()
        sessionGeneration = lifecycle.start()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                targetBootstrap = createTargetBootstrap()
                val candidates = targetBootstrap.prepareTransports()
                lifecycle.post(sessionGeneration, DebugSessionEvent.TARGET_READY) {
                    startTransportCandidates(candidates)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                    this.error("准备调试目标失败: ${error.message}")
                    terminateFailedSession()
                }
            }
        }
    }

    protected abstract fun createTargetBootstrap(): EmmyTargetBootstrap

    private fun startTransportCandidates(candidates: List<Transporter>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    candidate.handler = this
                    candidate.logger = this
                    candidate.setEventDispatcher { action -> lifecycle.execute(sessionGeneration, action) }
                    transporter = candidate
                    candidate.start()
                    return@executeOnPooledThread
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    lastError = error
                    runCatching { candidate.close() }
                }
            }
            lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                this.error("连接调试目标失败: ${lastError?.message ?: "没有可用传输通道"}")
                terminateFailedSession()
            }
        }
    }

    private fun sendInitReq() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val emmyHelperPath = getEmmyHelperDirPath()
                    ?: throw IllegalStateException("无法获取 emmyHelper 目录路径")
                val customHelperPath = getCustomHelperDirPath()
                val emmyHelperExtName = getEmmyHelperExtName()
                val extList = LuaFileManager.extensions
                lifecycle.execute(sessionGeneration) {
                    resetBreakpointState()
                    transporter?.send(InitMessage(
                        emmyHelperPath = emmyHelperPath,
                        customHelperPath = customHelperPath,
                        emmyHelperName = "emmyHelper",
                        emmyHelperExtName = emmyHelperExtName,
                        ext = extList
                    ))

                    val ideBreakpoints = XDebuggerManager.getInstance(session.project)
                        .breakpointManager
                        .getBreakpoints(LuaLineBreakpointType::class.java)
                    ideBreakpoints.forEach { breakpoint ->
                        breakpoint.sourcePosition?.let { position ->
                            upsertBreakpoint(position, breakpoint, sendUpdate = false)
                        }
                    }
                    if (breakpoints.isNotEmpty()) {
                        transporter?.send(AddBreakPointReq(breakpoints.values.toList()))
                    }
                    transporter?.send(Message(MessageCMD.ReadyReq))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                    error("发送初始化请求失败: ${e.message}")
                    terminateFailedSession()
                }
            }
        }
    }
    
    /** 重建当前会话的完整断点快照。仅在 lifecycle 线程调用。 */
    private fun resetBreakpointState() {
        breakpoints.clear()
        breakpointIds.clear()
        idCounter = 0
    }
    
    private fun getEmmyHelperDirPath(): String? = try {
        DebuggerResourceService.emmyHelperDirectory(
            session.project,
            LuaProjectSettings.getInstance(session.project).enableDevMode
        ).toString()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        this.error("无法准备 Emmy Helper 资源: ${error.message}")
        null
    }
    
    /**
     * 获取自定义 helper 目录路径
     * 
     * 如果用户配置了自定义脚本，返回其所在目录路径
     */
    private fun getCustomHelperDirPath(): String {
        val settings = LuaProjectSettings.getInstance(session.project)
        val customPath = settings.customHelperPath
        
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.isDirectory) {
                return customFile.absolutePath
            }
        }
        
        return ""
    }
    
    /**
     * 获取扩展脚本名称
     * 
     * 如果用户配置了自定义扩展脚本名称，返回该名称
     * 否则返回默认的 "emmyHelper_ue"
     */
    private fun getEmmyHelperExtName(): String {
        val settings = LuaProjectSettings.getInstance(session.project)
        val customExtName = settings.customHelperExtName
        
        return if (!customExtName.isNullOrBlank()) {
            customExtName
        } else {
            "emmyHelper_ue"
        }
    }
    
    final override fun onConnect(suc: Boolean) {
        if (suc) {
            lifecycle.post(sessionGeneration, DebugSessionEvent.CONNECTED) {
                ApplicationManager.getApplication().runReadAction {
                    sendInitReq()
                }
            }
        } else {
            lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                this.error("调试传输连接失败")
                terminateFailedSession()
            }
        }
    }

    final override fun onDisconnect() {
        cancelEvaluationsThen(IOException("Emmy transport disconnected")) {
            if (lifecycle.state == DebugSessionState.STOPPING) {
                finishStop()
            } else {
                lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                    this.error("调试传输连接已断开")
                    terminateFailedSession()
                }
            }
        }
    }

    final override fun onReceiveMessage(cmd: MessageCMD, json: String) {
        when (cmd) {
            MessageCMD.ReadyRsp -> markInitialized()

            MessageCMD.BreakNotify -> {
                val data = Gson().fromJson(json, BreakNotify::class.java)
                onBreak(data)
            }

            MessageCMD.EvalRsp -> {
                val rsp = Gson().fromJson(json, EvalRsp::class.java)
                onEvalRsp(rsp)
            }

            MessageCMD.LogNotify -> {
                val notify = Gson().fromJson(json, LogNotify::class.java)
                log(notify.message, DebugLogLevel.fromValue(notify.type))
            }

            else -> {
                if (!handleBackendMessage(cmd, json)) {
                    log("Unknown Emmy message: $cmd", DebugLogLevel.DEBUG)
                }
            }
        }
    }

    protected open fun handleBackendMessage(cmd: MessageCMD, json: String): Boolean = false

    protected fun markInitialized() {
        lifecycle.post(sessionGeneration, DebugSessionEvent.INITIALIZED)
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val token = sessionGeneration
        if (token == 0L) return
        lifecycle.execute(token) { upsertBreakpoint(sourcePosition, breakpoint, sendUpdate = true) }
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val token = sessionGeneration
        if (token == 0L) return
        lifecycle.execute(token) {
            val id = breakpointIds.remove(breakpoint)
            val bp = breakpoints.remove(id)
            if (bp != null) {
                send(RemoveBreakPointReq(listOf(bp)))
            }
        }
    }

    private fun upsertBreakpoint(
        sourcePosition: XSourcePosition,
        breakpoint: XLineBreakpoint<*>,
        sendUpdate: Boolean
    ) {
        val shortPath = sourcePosition.file.canonicalPath ?: sourcePosition.file.path
        val id = breakpointIds[breakpoint] ?: idCounter++.also { breakpointIds[breakpoint] = it }
        val protocolBreakpoint = if (breakpoint.isLogMessage) {
            BreakPoint(shortPath, breakpoint.line + 1, logMessage = breakpoint.logExpressionObject?.expression)
        } else {
            BreakPoint(shortPath, breakpoint.line + 1, condition = breakpoint.conditionExpression?.expression)
        }
        val previous = breakpoints.put(id, protocolBreakpoint)
        if (sendUpdate && previous != protocolBreakpoint &&
            lifecycle.state in setOf(DebugSessionState.INITIALIZING, DebugSessionState.RUNNING)) {
            if (previous != null) send(RemoveBreakPointReq(listOf(previous)))
            send(AddBreakPointReq(listOf(protocolBreakpoint)))
        }
    }

    override fun startPausing() {
        send(DebugActionMessage(DebugAction.Break))
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        clearInlineSnapshot()
        lifecycle.execute(sessionGeneration) {
            removeTemporaryBreakpoint()
            val breakpoint = BreakPoint(
                position.file.canonicalPath ?: position.file.path,
                position.line + 1,
                runToHere = true
            )
            temporaryBreakpoint = breakpoint
            send(AddBreakPointReq(listOf(breakpoint)))
            send(DebugActionMessage(DebugAction.Continue))
        }
    }

    private fun onBreak(data: BreakNotify) {
        removeTemporaryBreakpoint()
        cancelEvaluationsThen(CancellationException("Emmy stack frame was replaced")) {
            handleBreak(data)
        }
    }

    private fun handleBreak(data: BreakNotify) {
        val frames = data.stacks.map { EmmyDebugStackFrame(it, this) }
        val top = frames.firstOrNull { it.sourcePosition != null }
            ?: frames.firstOrNull { it.data.line > 0 }
            ?: frames.firstOrNull()
        val stack = LuaExecutionStack(frames)
        if (top != null)
            stack.setTopFrame(top)
        val sourcePosition = top?.sourcePosition
        val breakpoint = sourcePosition?.let { getBreakpoint(it.file, it.line) }
        if (top != null && sourcePosition != null) {
            InlineDebugSnapshotStore.getInstance(session.project).update(
                session,
                top.data.toInlineSnapshot(
                    sourcePosition.file.canonicalPath ?: sourcePosition.file.path,
                    sourcePosition.line
                )
            )
        } else {
            clearInlineSnapshot()
        }
        if (breakpoint != null) {
            ApplicationManager.getApplication().invokeLater {
                session.breakpointReached(breakpoint, null, LuaSuspendContext(stack))
                session.showExecutionPoint()
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                // todo: fix "Internal classes usages" & "Internal methods usages" problem
                /*val se = session
                if (se is XDebugSessionImpl)
                    se.positionReached(LuaSuspendContext(stack), true)
                else
                    se.positionReached(LuaSuspendContext(stack))*/
                session.positionReached(LuaSuspendContext(stack))
                session.showExecutionPoint()
            }
        }
    }

    private fun onEvalRsp(rsp: EvalRsp) {
        if (!evaluationRequests.complete(rsp.seq.toString(), rsp)) {
            log("Ignored late Emmy evaluation response: ${rsp.seq}", DebugLogLevel.DEBUG)
        }
    }

    override fun run() {
        clearInlineSnapshot()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            send(DebugActionMessage(DebugAction.Continue))
        }
    }

    private fun removeTemporaryBreakpoint() {
        val breakpoint = temporaryBreakpoint ?: return
        temporaryBreakpoint = null
        send(RemoveBreakPointReq(listOf(breakpoint)))
    }

    final override fun stop() {
        clearInlineSnapshot()
        cancelEvaluationsThen(CancellationException("Emmy debug session stopped")) {
            lifecycle.post(sessionGeneration, DebugSessionEvent.STOP_REQUESTED) {
                removeTemporaryBreakpoint()
                send(DebugActionMessage(DebugAction.Stop))
                send(StopSign())
                runCatching { transporter?.close() }
                transporter = null
                if (::targetBootstrap.isInitialized) {
                    runCatching { targetBootstrap.stop() }
                }
                finishStop()
            }
        }
    }

    private fun finishStop() {
        lifecycle.post(sessionGeneration, DebugSessionEvent.TERMINATED) {
            try {
                evaluationRequests.close()
            } finally {
                breakpoints.clear()
                breakpointIds.clear()
                lifecycle.close()
            }
        }
    }

    private fun terminateFailedSession() {
        if (!failureTerminationStarted.compareAndSet(false, true)) return
        clearInlineSnapshot()
        temporaryBreakpoint = null
        cancelEvaluationsThen(IOException("Emmy debug target stopped")) {
            runCatching { transporter?.close() }
            transporter = null
            if (::targetBootstrap.isInitialized) {
                runCatching { targetBootstrap.stop() }
            }
            ApplicationManager.getApplication().invokeLater {
                if (!session.isStopped) session.stop()
            }
            try {
                evaluationRequests.close()
            } finally {
                breakpoints.clear()
                breakpointIds.clear()
                lifecycle.close()
            }
        }
    }

    override fun startStepOver(context: XSuspendContext?) {
        clearInlineSnapshot()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            send(DebugActionMessage(DebugAction.StepOver))
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        clearInlineSnapshot()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            send(DebugActionMessage(DebugAction.StepIn))
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        clearInlineSnapshot()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            send(DebugActionMessage(DebugAction.StepOut))
        }
    }

    private fun clearInlineSnapshot() {
        InlineDebugSnapshotStore.getInstance(session.project).clear(session)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    fun requestEvaluation(request: EvalReq, callback: (Result<EvalRsp>) -> Unit) {
        evaluationRequests.request(
            requestId = request.seq.toString(),
            send = {
                val activeTransport = transporter ?: throw IOException("Emmy transport is not connected")
                activeTransport.send(request)
            },
            callback = callback
        )
    }

    private fun cancelEvaluations(cause: Throwable): CancellationException? = try {
        evaluationRequests.cancelAll(cause)
        null
    } catch (cancellation: CancellationException) {
        cancellation
    }

    private inline fun cancelEvaluationsThen(cause: Throwable, action: () -> Unit) {
        val cancellation = cancelEvaluations(cause)
        try {
            action()
        } catch (error: Throwable) {
            if (cancellation != null) error.addSuppressed(cancellation)
            throw error
        }
        if (cancellation != null) throw cancellation
    }

    fun send(msg: IMessage) {
        transporter?.send(msg)
    }
}
