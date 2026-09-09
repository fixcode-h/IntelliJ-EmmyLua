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
import com.tang.intellij.lua.debugger.cli.*
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

abstract class EmmyDebugProcessBase(session: XDebugSession) : LuaDebugProcess(session), ITransportHandler, EmmyDebugBackend {
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
    private val reconnectStopped = AtomicBoolean()
    private val reconnectAttempt = AtomicInteger(0)
    private val v2RequestSequence = AtomicLong()
    private val snapshotRequestOutstanding = AtomicBoolean()
    private val cliBreakpointRevision = AtomicLong()
    private val cliBreakpoints = java.util.concurrent.ConcurrentHashMap<String, CliBreakpointSpec>()
    private val cliProbes = java.util.concurrent.ConcurrentHashMap<String, CliProbeSpec>()
    private val cliTargetId = "emmy-${session.project.locationHash}-${session.runProfile?.name ?: "session"}"
    @Volatile private var cliRegistered = false
    protected val vmRegistry = VmRegistry()
    protected val pauseSnapshots = PauseSnapshotStore()
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

    override val debugTargetId: String get() = cliTargetId

    override fun sessionInitialized() {
        super.sessionInitialized()
        registerCliTarget()
        reconnectStopped.set(false)
        reconnectAttempt.set(0)
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
                    if (transporter === candidate) transporter = null
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
                        ext = extList,
                        authToken = targetBootstrap.authToken.orEmpty()
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
            reconnectAttempt.set(0)
            lifecycle.post(sessionGeneration, DebugSessionEvent.CONNECTED) {
                ApplicationManager.getApplication().runReadAction {
                    sendInitReq()
                }
            }
        } else {
            val attempt = reconnectAttempt.get()
            if (attempt > 0 && !reconnectStopped.get()) {
                scheduleReconnect(attempt + 1)
            } else {
                lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                    this.error("调试传输连接失败")
                    terminateFailedSession()
                }
            }
        }
    }

    final override fun onDisconnect() {
        pauseSnapshots.clear()
        vmRegistry.invalidateAllPauses()
        cancelEvaluationsThen(IOException("Emmy transport disconnected")) {
            if (lifecycle.state == DebugSessionState.STOPPING) {
                finishStop()
            } else if (!reconnectStopped.get() && ::targetBootstrap.isInitialized) {
                scheduleReconnect(1)
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
                val vmId = data.vmId
                val pauseId = data.pauseId
                if (vmId != null && pauseId != null) {
                    vmRegistry.setPause(vmId, pauseId)
                    val pause = PauseSnapshot(
                            vmId = vmId,
                            pauseId = pauseId,
                            threadId = data.threadId,
                            scope = data.pauseScope ?: "THREAD",
                            consistency = data.consistency ?: "THREAD_ONLY",
                            stacks = data.stacks
                        )
                    if (!pauseSnapshots.offer(pause).shouldPresent) return
                }
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

            MessageCMD.EnvelopeV2 -> {
                if (!handleV2Envelope(json)) {
                    log("Unknown Emmy v2 message", DebugLogLevel.DEBUG)
                }
            }

            else -> {
                if (!handleBackendMessage(cmd, json)) {
                    log("Unknown Emmy message: $cmd", DebugLogLevel.DEBUG)
                }
            }
        }
    }

    protected open fun handleBackendMessage(cmd: MessageCMD, json: String): Boolean = false

    /**
     * v2 lifecycle messages are deliberately handled separately from legacy
     * BreakNotify/EvalRsp DTOs. VmRegistry ownership is added in the next
     * phase; this hook keeps the wire contract observable without coupling the
     * transport to XDebugger objects.
     */
    protected open fun handleV2Envelope(json: String): Boolean {
        val envelope = runCatching { EmmyV2Envelope.fromJson(json) }
            .getOrElse {
                log("解析 Emmy v2 消息失败: ${it.message}", DebugLogLevel.WARNING)
                return true
            }
        return when (envelope.type) {
            "agent.ready" -> {
                markInitialized()
                true
            }
            "vm.snapshot", "vm.lifecycle" -> {
                val result = vmRegistry.applyEnvelope(envelope)
                if (envelope.type == "vm.snapshot") {
                    snapshotRequestOutstanding.set(false)
                } else if (result.requiresSnapshot) {
                    requestVmSnapshot()
                } else if (envelope.type == "vm.lifecycle") {
                    val lifecycle = envelope.payload?.let {
                        EmmyJson.gson.fromJson(it, VmLifecycleDto::class.java)
                    }
                    if (lifecycle != null && (lifecycle.current == "CLOSING" || lifecycle.current == "CLOSED" ||
                            lifecycle.contextGeneration != null || lifecycle.sourceEpoch != null)) {
                        pauseSnapshots.invalidate(lifecycle.vmId)
                    }
                    lifecycle?.let { event ->
                        publishCliEvent("vm.lifecycle", event.vmId, null,
                            mapOf("state" to event.current, "generation" to event.generation, "eventSeq" to event.eventSeq))
                    }
                }
                log("Emmy v2 ${envelope.type} received: ${result.status}", DebugLogLevel.DEBUG)
                true
            }
            "debug.paused" -> {
                if (!vmRegistry.acceptsEpoch(envelope.agentSessionId, envelope.connectionEpoch)) return true
                val paused = envelope.payload?.let {
                    EmmyJson.gson.fromJson(it, DebugPausedDto::class.java)
                }
                val vmId = envelope.target?.vmId
                if (paused != null && vmId != null) {
                    vmRegistry.setPause(vmId, paused.pauseId)
                    val pause = PauseSnapshot(
                            vmId = vmId,
                            pauseId = paused.pauseId,
                            threadId = paused.threadId,
                            scope = paused.pauseScope,
                            consistency = paused.consistency,
                            stacks = paused.stacks,
                            connectionEpoch = envelope.connectionEpoch,
                            contextGeneration = envelope.contextGeneration,
                            sourceEpoch = envelope.sourceEpoch
                        )
                    if (!pauseSnapshots.offer(pause).shouldPresent) return true
                    onBreak(BreakNotify(
                        stacks = paused.stacks,
                        vmId = vmId,
                        pauseId = paused.pauseId,
                        threadId = paused.threadId,
                        pauseScope = paused.pauseScope,
                        consistency = paused.consistency,
                        pauseReason = paused.reason
                    ))
                }
                true
            }
            "debug.resumed" -> {
                if (!vmRegistry.acceptsEpoch(envelope.agentSessionId, envelope.connectionEpoch)) return true
                envelope.target?.vmId?.let { vmId ->
                    val resumed = envelope.payload?.let {
                        EmmyJson.gson.fromJson(it, DebugResumedDto::class.java)
                    }
                    vmRegistry.invalidatePause(vmId, resumed?.pauseId)
                    pauseSnapshots.invalidate(vmId, resumed?.pauseId)
                }
                true
            }
            else -> false
        }
    }

    private fun requestVmSnapshot() {
        if (!snapshotRequestOutstanding.compareAndSet(false, true)) return
        val requestId = "vm-snapshot-${v2RequestSequence.incrementAndGet()}"
        val activeTransport = transporter
        if (activeTransport == null) {
            snapshotRequestOutstanding.set(false)
            log("无法请求 VM snapshot：当前没有可用传输通道", DebugLogLevel.WARNING)
            return
        }
        activeTransport.send(
            EmmyV2Message(
                EmmyV2Envelope(
                    kind = "request",
                    type = "vm.snapshot",
                    requestId = requestId
                )
            )
        )
    }

    private fun scheduleReconnect(attempt: Int) {
        if (reconnectStopped.get() || session.isStopped) return
        if (attempt > 4) {
            lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                this.error("调试传输重连达到最大次数")
                terminateFailedSession()
            }
            return
        }
        if (!reconnectAttempt.compareAndSet(attempt - 1, attempt)) return
        lifecycle.post(sessionGeneration, DebugSessionEvent.RECONNECTING) {
            log("Emmy 连接断开，准备第 $attempt 次重连", DebugLogLevel.RUNTIME)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val delay = (200L shl (attempt - 1)).coerceAtMost(3_000L)
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }
            if (reconnectStopped.get() || session.isStopped) return@executeOnPooledThread
            lifecycle.execute(sessionGeneration) {
                if (reconnectStopped.get() || !::targetBootstrap.isInitialized) return@execute
                startTransportCandidates(targetBootstrap.prepareReconnectTransports())
            }
        }
    }

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
        val identity = SourceIdentity.fromPath(shortPath)
        val protocolBreakpoint = if (breakpoint.isLogMessage) {
            BreakPoint(shortPath, breakpoint.line + 1, logMessage = breakpoint.logExpressionObject?.expression, sourceIdentity = identity.toWire())
        } else {
            BreakPoint(shortPath, breakpoint.line + 1, condition = breakpoint.conditionExpression?.expression, sourceIdentity = identity.toWire())
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
                runToHere = true,
                sourceIdentity = SourceIdentity.fromPath(position.file.path).toWire()
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
        publishCliEvent("debug.paused", data.vmId, data.pauseId, mapOf("reason" to data.pauseReason))
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
        pauseSnapshots.clear()
        vmRegistry.list().forEach { vmRegistry.invalidatePause(it.vmId) }
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            send(DebugActionMessage(DebugAction.Continue))
        }
    }

    private fun removeTemporaryBreakpoint() {
        val breakpoint = temporaryBreakpoint ?: return
        temporaryBreakpoint = null
        send(RemoveBreakPointReq(listOf(breakpoint)))
    }

    final override fun stop() {
        reconnectStopped.set(true)
        pauseSnapshots.clear()
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
        unregisterCliTarget()
        lifecycle.post(sessionGeneration, DebugSessionEvent.TERMINATED) {
            try {
                evaluationRequests.close()
            } finally {
                breakpoints.clear()
                breakpointIds.clear()
                cliBreakpoints.clear()
                cliProbes.clear()
                lifecycle.close()
            }
        }
    }

    private fun terminateFailedSession() {
        if (!failureTerminationStarted.compareAndSet(false, true)) return
        reconnectStopped.set(true)
        unregisterCliTarget()
        cliBreakpoints.clear()
        cliProbes.clear()
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
        pauseSnapshots.clear()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            send(DebugActionMessage(DebugAction.StepOver))
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        clearInlineSnapshot()
        pauseSnapshots.clear()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            send(DebugActionMessage(DebugAction.StepIn))
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        clearInlineSnapshot()
        pauseSnapshots.clear()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            send(DebugActionMessage(DebugAction.StepOut))
        }
    }

    private fun registerCliTarget() {
        runCatching {
            CliGatewayApplicationService.getInstance().register(this)
            cliRegistered = true
        }.onFailure { log("CLI Gateway 注册 Emmy target 失败: ${it.message}", DebugLogLevel.WARNING) }
    }

    private fun unregisterCliTarget() {
        if (!cliRegistered) return
        runCatching { CliGatewayApplicationService.getInstance().unregister(debugTargetId) }
        cliRegistered = false
    }

    override fun debugTargetSummary(): CliTargetSummary = CliTargetSummary(
        targetId = debugTargetId,
        projectName = session.project.name,
        state = lifecycle.state.name,
        agentReady = lifecycle.state == DebugSessionState.RUNNING
    ).copy(vms = debugVmList())

    override fun debugVmList(): List<CliVmSummary> = vmRegistry.list().map {
        CliVmSummary(it.vmId, it.generation, it.displayName, it.state, it.luaVersion, it.discovery, it.activePauseId)
    }

    override fun debugPause(vmId: String, pauseId: Long?): PauseSnapshot? {
        val selected = pauseId ?: vmRegistry.resolve(vmId)?.activePauseId ?: return null
        return pauseSnapshots.get(vmId, selected)
    }

    override fun debugControl(request: CliControlRequest): Result<CliControlResult> {
        if (vmRegistry.resolve(request.vmId) == null) return Result.failure(IllegalStateException(CliErrorCodes.VM_NOT_FOUND))
        if (request.action != "Break" && debugPause(request.vmId, request.pauseId) == null) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        val action = when (request.action) {
            "Break" -> DebugAction.Break
            "Continue" -> DebugAction.Continue
            "StepIn" -> DebugAction.StepIn
            "StepOver" -> DebugAction.StepOver
            "StepOut" -> DebugAction.StepOut
            else -> return Result.failure(IllegalArgumentException("UNSUPPORTED_CAPABILITY"))
        }
        return runOnLifecycle {
            sendRequired(DebugActionMessage(action))
            if (action != DebugAction.Break) {
                pauseSnapshots.invalidate(request.vmId, request.pauseId)
                vmRegistry.invalidatePause(request.vmId, request.pauseId)
                publishCliEvent("debug.resumed", request.vmId, request.pauseId, mapOf("action" to request.action))
            } else {
                publishCliEvent("debug.pauseRequested", request.vmId, request.pauseId)
            }
            CliControlResult(request.action, true, request.pauseId)
        }
    }

    override fun debugEvaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
        if (request.expression.length > 4096) return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_LIMIT_EXCEEDED))
        val snapshot = debugPause(request.vmId, request.pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val frame = snapshot.stacks.firstOrNull { frameIdFor(snapshot.pauseId, it) == request.frameId }
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        val future = CompletableFuture<Result<CliCapturedValue>>()
        val eval = EvalReq(request.expression, frame.level, 0, request.maxDepth,
            sourceIdentity = SourceIdentity.fromPath(frame.file).toWire())
        requestEvaluation(eval) { result ->
            future.complete(result.map { rsp ->
                val value = rsp.value
                CliCapturedValue(request.expression, rsp.success, value?.valueTypeName, value?.value,
                    truncated = false, errorMessage = rsp.error)
            })
        }
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            Result.failure(error.cause ?: error)
        }
    }

    override fun debugBreakpoints(): List<CliBreakpointSpec> = cliBreakpoints.values.sortedBy { it.breakpointId }

    override fun debugMutateBreakpoints(request: CliBreakpointMutation): Result<CliBreakpointResult> {
        val expected = request.expectedRevision
        if (expected != null && expected != cliBreakpointRevision.get()) {
            return Result.failure(IllegalStateException("BREAKPOINT_REVISION_CONFLICT"))
        }
        return runOnLifecycle {
            request.remove.forEach { id -> cliBreakpoints.remove(id)?.let { sendRequired(RemoveBreakPointReq(listOf(it.toProtocol()))) } }
            request.add.forEach { spec ->
                if (vmRegistry.resolve(spec.vmId) == null) throw IllegalStateException(CliErrorCodes.VM_NOT_FOUND)
                cliBreakpoints[spec.breakpointId] = spec
                sendRequired(AddBreakPointReq(listOf(spec.toProtocol())))
            }
            CliBreakpointResult(cliBreakpointRevision.incrementAndGet(), debugBreakpoints())
        }
    }

    override fun debugInstallProbe(spec: CliProbeSpec): Result<CliProbeSpec> {
        if (spec.targetId != debugTargetId) return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_FOUND))
        if (vmRegistry.resolve(spec.vmId) == null) return Result.failure(IllegalStateException(CliErrorCodes.VM_NOT_FOUND))
        cliProbes[spec.probeId] = spec
        return Result.success(spec)
    }

    override fun debugRemoveProbe(probeId: String, owner: String): Result<Boolean> {
        val probe = cliProbes[probeId] ?: return Result.success(false)
        if (probe.owner != owner) return Result.failure(IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED))
        cliProbes.remove(probeId, probe)
        return Result.success(true)
    }

    private fun BreakPoint.toCliSpec(id: String, vmId: String): CliBreakpointSpec = CliBreakpointSpec(
        id, "IDEA", vmId, sourceIdentity?.let { CliSourceIdentity(it.uri, it.canonicalPath, it.sourceHash, it.sourceEpoch ?: it.loaderEpoch, it.verified) }
            ?: CliSourceIdentity("", file), line, condition, logMessage, hitCondition
    )

    private fun CliBreakpointSpec.toProtocol(): BreakPoint = BreakPoint(
        sourceIdentity.canonicalPath, line, condition, logMessage, hitCondition,
        sourceIdentity = SourceIdentityWire(sourceIdentity.canonicalPath, sourceIdentity.uri, sourceIdentity.sourceHash,
            sourceEpoch = sourceIdentity.sourceEpoch, verified = sourceIdentity.verified)
    )

    private fun frameIdFor(pauseId: Long, stack: Stack): String = "frame-$pauseId-${stack.level}"

    private fun <T> runOnLifecycle(action: () -> T): Result<T> {
        val future = CompletableFuture<Result<T>>()
        val token = sessionGeneration
        if (token == 0L || lifecycle.state.isTerminal) return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        lifecycle.execute(token) {
            try { future.complete(Result.success(action())) }
            catch (error: Throwable) { future.complete(Result.failure(error)) }
        }
        return try { future.get(5, TimeUnit.SECONDS) } catch (error: Throwable) { Result.failure(error.cause ?: error) }
    }

    private fun sendRequired(message: IMessage) {
        val active = transporter ?: throw IOException("${CliErrorCodes.TARGET_NOT_READY}: Emmy transport is not connected")
        active.send(message)
    }

    private fun publishCliEvent(type: String, vmId: String? = null, pauseId: Long? = null, payload: Map<String, Any?> = emptyMap()) {
        runCatching { CliGatewayApplicationService.getInstance().registry().publish(debugTargetId, type, vmId, pauseId, payload) }
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
