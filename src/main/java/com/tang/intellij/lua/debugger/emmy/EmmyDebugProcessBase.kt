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
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import java.util.UUID

abstract class EmmyDebugProcessBase(session: XDebugSession) : LuaDebugProcess(session), ITransportHandler, EmmyDebugBackend {
    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 6
        /** A rejected snapshot response must be retried, but never forever. */
        const val MAX_SNAPSHOT_RETRY_ATTEMPTS = 4
        /** First-party CLI shipped with the plugin; used when no allow-list is set. */
        const val DEFAULT_CLI_AUTO_GRANT_CLIENT = "emmy-debug"
    }
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
    private val reconnectScheduled = AtomicBoolean()
    private val v2RequestSequence = AtomicLong()
    private val snapshotRequestOutstanding = AtomicBoolean()
    private val snapshotRetryAttempt = AtomicInteger()
    private val cliBreakpointRevision = AtomicLong()
    private val wireBreakpointRevision = AtomicLong()
    private val cliBreakpoints = java.util.concurrent.ConcurrentHashMap<String, CliBreakpointSpec>()
    private val cliProbes = java.util.concurrent.ConcurrentHashMap<String, CliProbeSpec>()
    private val breakpointComposer = BreakpointComposer()
    private data class PendingControl(
        val future: CompletableFuture<Result<CliControlResult>>,
        val vmId: String,
        val pauseId: Long?,
        val threadId: String?
    )
    private data class PendingEvaluation(
        val future: CompletableFuture<Result<CliCapturedValue>>,
        val vmId: String,
        val pauseId: Long,
        val threadId: String?,
        val frameId: String
    )
    private val v2EvaluationRequests = java.util.concurrent.ConcurrentHashMap<String, PendingEvaluation>()
    private val v2ControlRequests = java.util.concurrent.ConcurrentHashMap<String, PendingControl>()
    private val v2BreakpointRequests = java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Result<Long>>>()
    private val cliBreakpointMutationPending = java.util.concurrent.atomic.AtomicBoolean()
    private val cliStateGeneration = java.util.concurrent.atomic.AtomicLong()
    private val cliTargetId = "emmy-${session.project.locationHash}-${session.runProfile?.name ?: "session"}-${UUID.randomUUID().toString().take(12)}"
    @Volatile private var cliRegistered = false
    @Volatile private var v2Negotiated = false
    /** Agent handshake state is independent from IDEA lifecycle state. */
    @Volatile private var agentReady = false
    @Volatile private var agentSessionId: String? = null
    @Volatile private var connectionEpoch: Long? = null
    @Volatile private var awaitingVmSnapshot = true
    @Volatile private var cliCleanupDone = false
    private val stopFinalized = AtomicBoolean()
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
        cliCleanupDone = false
        v2Negotiated = false
        agentReady = false
        agentSessionId = null
        connectionEpoch = null
        awaitingVmSnapshot = true
        vmRegistry.markDisconnected()
        registerCliTarget()
        reconnectStopped.set(false)
        reconnectAttempt.set(0)
        reconnectScheduled.set(false)
        stopFinalized.set(false)
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
            // A transport can fail before it has emitted onConnect(false)
            // (for example when all candidate sockets are refused). Keep the
            // same bounded reconnect state machine for that path as well.
            log("连接调试目标失败: ${lastError?.message ?: "没有可用传输通道"}", DebugLogLevel.WARNING)
            scheduleReconnect(reconnectAttempt.get() + 1)
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
                    sendCompositeBreakpointSnapshotLegacy()
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
        breakpointComposer.clear()
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
            vmRegistry.beginConnection()
            agentReady = false
            awaitingVmSnapshot = true
            reconnectScheduled.set(false)
            lifecycle.post(sessionGeneration, DebugSessionEvent.CONNECTED) {
                ApplicationManager.getApplication().runReadAction {
                    sendInitReq()
                }
            }
        } else {
            log("调试传输连接失败", DebugLogLevel.WARNING)
            scheduleReconnect(reconnectAttempt.get() + 1)
        }
    }

    final override fun onDisconnect() {
        snapshotRequestOutstanding.set(false)
        v2Negotiated = false
        agentReady = false
        awaitingVmSnapshot = true
        cancelV2Requests(IOException("Emmy transport disconnected"))
        vmRegistry.markDisconnected()
        pauseSnapshots.clear()
        vmRegistry.invalidateAllPauses()
        runCatching { CliGatewayApplicationService.getInstance().notifyTargetDisconnected(debugTargetId) }
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
            MessageCMD.InitRsp -> {
                val obj = parseLegacyObject(cmd, json) ?: return
                v2Negotiated = runCatching {
                    obj.get("protocolVersion")?.takeUnless { it.isJsonNull }?.asInt == 2 ||
                        obj.get("version")?.takeUnless { it.isJsonNull }?.asString == "2"
                }.getOrDefault(false)
                if (v2Negotiated && !acceptHandshake(obj)) {
                    log("Emmy InitRsp 身份与当前连接不一致", DebugLogLevel.WARNING)
                    return
                }
                if (v2Negotiated) {
                    // v2 identity is now known: drop anything a pre-handshake
                    // legacy AttachedNotify registered and re-arm the snapshot
                    // fence so the authoritative vm.snapshot is the only source
                    // of VM records.
                    vmRegistry.rearmSnapshotFenceForV2()
                    awaitingVmSnapshot = vmRegistry.isAwaitingSnapshot()
                    snapshotRequestOutstanding.set(false)
                    snapshotRetryAttempt.set(0)
                }
            }
            MessageCMD.ReadyRsp -> {
                val obj = parseLegacyObject(cmd, json) ?: return
                if (v2Negotiated && !acceptHandshake(obj)) {
                    log("Emmy ReadyRsp 身份与当前连接不一致", DebugLogLevel.WARNING)
                    return
                }
                // v1 has no VM snapshot fence. A valid ReadyRsp is the
                // strongest lifecycle signal available in that protocol.
                agentReady = true
                if (!v2Negotiated) {
                    awaitingVmSnapshot = false
                    reconnectAttempt.set(0)
                    reconnectScheduled.set(false)
                }
                markInitialized()
                if (v2Negotiated && awaitingVmSnapshot) requestVmSnapshot()
            }

            MessageCMD.BreakNotify -> {
                // v2 publishes the complete, epoch-stamped pause; its legacy
                // companion must not resurrect a pause after reset/resume.
                if (v2Negotiated) return
                val parsed = parseLegacy<BreakNotify>(cmd, json) ?: return
                // A legacy agent reports its own protocol id (`vm-1`) while the
                // record is registered as `legacy-<epoch>-<stateAddress>`. Fold
                // them into one identity so the pause reaches setPause,
                // pauseSnapshots and the published event consistently.
                val mappedVmId = parsed.vmId?.let { vmRegistry.normalizeLegacyVmId(it) }
                val data = if (mappedVmId != null && mappedVmId != parsed.vmId) {
                    BreakNotify(
                        parsed.stacks, mappedVmId, parsed.pauseId, parsed.threadId,
                        parsed.pauseScope, parsed.consistency, parsed.pauseReason, parsed.reasons
                    )
                } else parsed
                val vmId = data.vmId
                val pauseId = data.pauseId
                var present = true
                var emitEvent = true
                if (vmId != null && pauseId != null) {
                    if (!vmRegistry.setPause(vmId, pauseId)) {
                        log(
                            "legacy 暂停未能登记：vmId=$vmId 不在 VM 注册表中，暂停查询将不可用",
                            DebugLogLevel.DEBUG
                        )
                    }
                    val vm = vmRegistry.resolve(vmId)
                    val pause = PauseSnapshot(
                        vmId = vmId,
                        pauseId = pauseId,
                        threadId = data.threadId,
                        scope = data.pauseScope ?: "THREAD",
                        consistency = data.consistency ?: "THREAD_ONLY",
                        stacks = data.stacks,
                        connectionEpoch = connectionEpoch,
                        contextGeneration = vm?.contextGeneration,
                        sourceEpoch = vm?.sourceEpoch,
                        reasons = data.reasons.toSet()
                    )
                    val offered = pauseSnapshots.offer(pause)
                    if (offered.status == PauseOfferStatus.STALE) return
                    present = offered.shouldPresent
                    emitEvent = offered.status != PauseOfferStatus.DUPLICATE
                }
                onBreak(data, present = present, emitEvent = emitEvent)
            }

            MessageCMD.EvalRsp -> {
                val rsp = parseLegacy<EvalRsp>(cmd, json) ?: return
                onEvalRsp(rsp)
            }

            MessageCMD.LogNotify -> {
                val notify = parseLegacy<LogNotify>(cmd, json) ?: return
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

    private fun parseLegacyObject(cmd: MessageCMD, json: String): JsonObject? {
        val parsed = runCatching { Gson().fromJson(json, JsonObject::class.java) }.getOrNull()
        if (parsed == null) reportMalformedLegacy(cmd)
        return parsed
    }

    private inline fun <reified T> parseLegacy(cmd: MessageCMD, json: String): T? =
        runCatching { Gson().fromJson(json, T::class.java) }
            .getOrElse {
                reportMalformedLegacy(cmd, it)
                null
            }

    private fun reportMalformedLegacy(cmd: MessageCMD, error: Throwable? = null) {
        log("忽略畸形 Emmy legacy 消息 $cmd: ${error?.message ?: "JSON object required"}", DebugLogLevel.WARNING)
        publishCliEvent("protocol.error", payload = mapOf(
            "protocol" to "legacy-v1",
            "command" to cmd.name,
            "code" to CliErrorCodes.INVALID_ARGUMENT,
            "message" to (error?.message ?: "malformed JSON")
        ))
    }

    /** Keep protocol diagnostics useful without flooding IDEA logs. */
    private fun boundedV2Payload(payload: JsonObject?): String {
        if (payload == null) return "<none>"
        val compact = payload.toString().replace(Regex("\\s+"), " ")
        return if (compact.length <= 2048) compact else compact.take(2048) + "..."
    }

    private fun snapshotVmSummary(payload: JsonObject?): String {
        if (payload == null) return "snapshotEventSeq=<none> vms=<none>"
        val sequence = runCatching { payload.get("snapshotEventSeq")?.asLong }.getOrNull()
        val vmSummary = runCatching {
            payload.getAsJsonArray("vms")?.mapNotNull { element ->
                runCatching {
                    val vm = element.asJsonObject
                    val id = vm.get("vmId")?.asString ?: "<missing>"
                    val generation = vm.get("generation")?.asLong ?: 0L
                    val state = vm.get("state")?.asString ?: "<missing>"
                    val context = vm.get("contextGeneration")?.asLong?.toString() ?: "-"
                    val source = vm.get("sourceEpoch")?.asLong?.toString() ?: "-"
                    "$id/$generation/$state/context=$context/source=$source"
                }.getOrNull()
            }?.joinToString(",") ?: "<none>"
        }.getOrElse { "<malformed>" }
        return "snapshotEventSeq=${sequence ?: "<missing>"} vms=$vmSummary"
    }

    protected fun isV2Negotiated(): Boolean = v2Negotiated

    /**
     * Connection epoch of the current transport. The attach path passes it to
     * the legacy VM record so its pause snapshots compare equal.
     */
    protected fun currentConnectionEpoch(): Long? = connectionEpoch

    private fun v2EnvelopeContext(envelope: EmmyV2Envelope): String =
        "requestId=${envelope.requestId ?: "-"} agentSessionId=${envelope.agentSessionId ?: "-"} " +
            "connectionEpoch=${envelope.connectionEpoch ?: "-"} contextGeneration=${envelope.contextGeneration ?: "-"} " +
            "sourceEpoch=${envelope.sourceEpoch ?: "-"} targetVm=${envelope.target?.vmId ?: "-"}"

    /**
     * v2 lifecycle messages are deliberately handled separately from legacy
     * BreakNotify/EvalRsp DTOs. The VM registry is the authority for v2
     * identity and pause fencing; legacy DTOs remain isolated for compatibility.
     */
    protected open fun handleV2Envelope(json: String): Boolean {
        val envelope = runCatching { EmmyV2Envelope.fromJson(json) }
            .getOrElse {
                log("解析 Emmy v2 消息失败: ${it.message}", DebugLogLevel.WARNING)
                return true
            }
        return when (envelope.type) {
            "agent.ready" -> {
                if (!acceptHandshake(envelope)) return true
                v2Negotiated = true
                vmRegistry.rearmSnapshotFenceForV2()
                awaitingVmSnapshot = vmRegistry.isAwaitingSnapshot()
                snapshotRequestOutstanding.set(false)
                snapshotRetryAttempt.set(0)
                agentReady = true
                markInitialized()
                if (awaitingVmSnapshot) requestVmSnapshot()
                true
            }
            "vm.snapshot", "vm.lifecycle" -> {
                val lifecyclePayload = if (envelope.type == "vm.lifecycle") envelope.payload?.let {
                    runCatching { EmmyJson.gson.fromJson(it, VmLifecycleDto::class.java) }.getOrNull()
                } else null
                val previousVm = lifecyclePayload?.let { vmRegistry.resolve(it.vmId) }
                val result = vmRegistry.applyEnvelope(envelope)
                if (envelope.type == "vm.snapshot") {
                    if (result.accepted) {
                        awaitingVmSnapshot = vmRegistry.isAwaitingSnapshot()
                        snapshotRequestOutstanding.set(false)
                        snapshotRetryAttempt.set(0)
                        if (!awaitingVmSnapshot) {
                            // The reconnect attempt is considered successful
                            // only after the snapshot fence is applied. This
                            // prevents a disconnect during handshake from
                            // resetting the retry budget prematurely.
                            reconnectAttempt.set(0)
                            reconnectScheduled.set(false)
                        }
                    } else if (envelope.requestId != null) {
                        // A response always terminates our request, success or
                        // not. Latching the flag here used to drop every later
                        // snapshot request, leaving the VM unregistered.
                        retryVmSnapshotAfterRejection(envelope.error?.code ?: result.status.name)
                    }
                } else if (result.requiresSnapshot) {
                    requestVmSnapshot()
                } else if (envelope.type == "vm.lifecycle") {
                    if (result.status != VmApplyStatus.APPLIED) {
                        log("忽略未应用的 Emmy VM lifecycle: ${result.status}", DebugLogLevel.DEBUG)
                        return true
                    }
                    val lifecycle = lifecyclePayload
                    val contextChanged = lifecycle != null &&
                        ((lifecycle.contextGeneration != null && lifecycle.contextGeneration != previousVm?.contextGeneration) ||
                            (lifecycle.sourceEpoch != null && lifecycle.sourceEpoch != previousVm?.sourceEpoch))
                    if (lifecycle != null && (lifecycle.current == "CLOSING" || lifecycle.current == "CLOSED" ||
                            contextChanged)) {
                        pauseSnapshots.invalidate(lifecycle.vmId)
                        if (contextChanged) {
                            resetContextState(lifecycle.vmId, lifecycle.contextGeneration, lifecycle.sourceEpoch)
                        }
                    }
                    lifecycle?.let { event ->
                        if (event.current == "CLOSING") {
                            runCatching { CliGatewayApplicationService.getInstance().notifyVmClosing(debugTargetId, event.vmId) }
                        } else if (event.current == "CLOSED") {
                            runCatching { CliGatewayApplicationService.getInstance().notifyVmClosed(debugTargetId, event.vmId) }
                        }
                        publishCliEvent("vm.lifecycle", event.vmId, null,
                            mapOf(
                                "state" to event.current,
                                "previous" to event.previous,
                                "generation" to event.generation,
                                "eventSeq" to event.eventSeq,
                                "reason" to event.reason,
                                "contextGeneration" to event.contextGeneration,
                                "sourceEpoch" to event.sourceEpoch
                            ))
                    }
                }
                if (envelope.type == "vm.snapshot") {
                    val level = if (result.accepted) DebugLogLevel.DEBUG else DebugLogLevel.WARNING
                    log(
                        "Emmy v2 vm.snapshot received: status=${result.status} " +
                            "message=${result.message ?: "-"} ok=${envelope.ok ?: "-"} " +
                            "error=${envelope.error?.code ?: "-"} ${v2EnvelopeContext(envelope)} " +
                            "${snapshotVmSummary(envelope.payload)} payload=${boundedV2Payload(envelope.payload)}",
                        level
                    )
                } else {
                    val level = if (result.status == VmApplyStatus.APPLIED || result.status == VmApplyStatus.DUPLICATE) {
                        DebugLogLevel.DEBUG
                    } else DebugLogLevel.WARNING
                    log(
                        "Emmy v2 vm.lifecycle received: status=${result.status} " +
                            "message=${result.message ?: "-"} ${v2EnvelopeContext(envelope)} " +
                            "payload=${boundedV2Payload(envelope.payload)}",
                        level
                    )
                }
                true
            }
            "debug.paused" -> {
                fun reject(reason: String): Boolean {
                    log(
                        "Emmy v2 debug.paused rejected: reason=$reason ${v2EnvelopeContext(envelope)} " +
                            "registryConnected=${vmRegistry.isConnected()} awaitingSnapshot=${vmRegistry.isAwaitingSnapshot()} " +
                            "payload=${boundedV2Payload(envelope.payload)}",
                        DebugLogLevel.WARNING
                    )
                    return true
                }
                if (!vmRegistry.acceptsEpoch(envelope.agentSessionId, envelope.connectionEpoch)) {
                    return reject("connection epoch/session mismatch or snapshot fence")
                }
                val paused = envelope.payload?.let {
                    runCatching { EmmyJson.gson.fromJson(it, DebugPausedDto::class.java) }.getOrNull()
                }
                val vmId = envelope.target?.vmId
                if (paused == null) return reject("malformed or missing payload")
                if (vmId == null) return reject("target.vmId is missing")
                val vm = vmRegistry.resolve(vmId) ?: return reject("unknown VM $vmId")
                if (!vmRegistry.isControlReady(vmId)) return reject("VM is not control-ready: state=${vm.state}")
                if (paused.pauseId <= 0) return reject("pauseId must be positive")
                if (envelope.contextGeneration != vm.contextGeneration) {
                    return reject("contextGeneration mismatch: envelope=${envelope.contextGeneration} registry=${vm.contextGeneration}")
                }
                if (envelope.sourceEpoch != vm.sourceEpoch) {
                    return reject("sourceEpoch mismatch: envelope=${envelope.sourceEpoch} registry=${vm.sourceEpoch}")
                }
                if (envelope.target?.pauseId != paused.pauseId) {
                    return reject("target.pauseId mismatch: target=${envelope.target?.pauseId} payload=${paused.pauseId}")
                }
                if (envelope.target?.threadId != paused.threadId) {
                    return reject("target.threadId mismatch: target=${envelope.target?.threadId} payload=${paused.threadId}")
                }
                run {
                    val pause = PauseSnapshot(
                            vmId = vmId,
                            pauseId = paused.pauseId,
                            threadId = paused.threadId,
                            scope = paused.pauseScope,
                            consistency = paused.consistency,
                            stacks = paused.stacks,
                            connectionEpoch = envelope.connectionEpoch,
                            contextGeneration = envelope.contextGeneration ?: vm?.contextGeneration,
                            sourceEpoch = envelope.sourceEpoch ?: vm?.sourceEpoch,
                            reasons = paused.reasons.toSet()
                        )
                    val offered = pauseSnapshots.offer(pause)
                    if (offered.status == PauseOfferStatus.STALE) return reject("pause snapshot is stale")
                    if (!vmRegistry.setPause(vmId, paused.pauseId)) {
                        log(
                            "v2 暂停未能登记：vmId=$vmId 当前不可暂停，暂停查询将不可用",
                            DebugLogLevel.DEBUG
                        )
                    }
                    onBreak(BreakNotify(
                        stacks = paused.stacks,
                        vmId = vmId,
                        pauseId = paused.pauseId,
                        threadId = paused.threadId,
                        pauseScope = paused.pauseScope,
                        consistency = paused.consistency,
                        pauseReason = paused.reason,
                        reasons = paused.reasons
                    ), present = offered.shouldPresent,
                        emitEvent = offered.status != PauseOfferStatus.DUPLICATE)
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
            "debug.action" -> {
                if (envelope.kind == "response") {
                    if (!vmRegistry.acceptsEpoch(envelope.agentSessionId, envelope.connectionEpoch)) return true
                    val requestId = envelope.requestId
                    if (requestId != null) {
                    val pending = v2ControlRequests.remove(requestId)
                    if (pending == null) return true
                    if (!matchesPendingTarget(envelope, pending.vmId, pending.pauseId, pending.threadId)) {
                        pending.future.complete(Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE)))
                        return true
                    }
                    val result = if (envelope.ok == false) {
                            Result.failure<CliControlResult>(IllegalStateException(
                                envelope.error?.code ?: CliErrorCodes.STALE_PAUSE_REFERENCE
                            ))
                        } else {
                            val payload = envelope.payload
                            Result.success(CliControlResult(
                                action = payload?.get("action")?.asString ?: "",
                                accepted = payload?.get("accepted")?.asBoolean ?: (envelope.ok != false),
                                pauseId = payload?.get("pauseId")?.asLong,
                                message = payload?.get("message")?.asString
                            ))
                        }
                        pending.future.complete(result)
                    }
                }
                true
            }
            "debug.eval" -> {
                if (envelope.kind == "response") {
                    if (!vmRegistry.acceptsEpoch(envelope.agentSessionId, envelope.connectionEpoch)) return true
                    val requestId = envelope.requestId
                    if (requestId != null) {
                        val pending = v2EvaluationRequests.remove(requestId)
                        if (pending == null) return true
                        if (!matchesPendingTarget(envelope, pending.vmId, pending.pauseId, pending.threadId,
                                pending.frameId)) {
                            pending.future.complete(Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE)))
                            return true
                        }
                        val result = parseV2EvaluationResponse(envelope)
                        pending.future.complete(result)
                    }
                }
                true
            }
            "debug.breakpoints.replace" -> {
                if (envelope.kind == "response") {
                    if (!isCurrentEnvelope(envelope)) {
                        log(
                            "忽略 Emmy v2 断点替换 ACK：会话不匹配 ${v2EnvelopeContext(envelope)} " +
                                "payload=${boundedV2Payload(envelope.payload)}",
                            DebugLogLevel.WARNING
                        )
                        return true
                    }
                    val requestId = envelope.requestId
                    if (requestId != null) {
                        val ackRevision = runCatching { envelope.payload?.get("revision")?.asLong }.getOrNull()
                        val ackCount = runCatching { envelope.payload?.get("count")?.asInt }.getOrNull()
                        log(
                            "收到 Emmy v2 断点替换 ACK：requestId=$requestId ok=${envelope.ok != false} " +
                                "revision=${ackRevision ?: "-"} count=${ackCount ?: "-"} " +
                                "error=${envelope.error?.code ?: "-"}",
                            if (envelope.ok == false) DebugLogLevel.WARNING else DebugLogLevel.DEBUG
                        )
                        val result = if (envelope.ok == false) {
                            Result.failure<Long>(IllegalStateException(
                                envelope.error?.code ?: "BREAKPOINT_REPLACE_FAILED"
                            ))
                        } else {
                            val revision = envelope.payload?.get("revision")?.asLong
                            if (revision == null) {
                                Result.failure<Long>(IllegalStateException("BREAKPOINT_REPLACE_EMPTY_ACK"))
                            } else {
                                Result.success(revision)
                            }
                        }
                        val pending = v2BreakpointRequests.remove(requestId)
                        if (pending == null) {
                            log("忽略迟到的 Emmy v2 断点替换 ACK：requestId=$requestId", DebugLogLevel.WARNING)
                        } else {
                            pending.complete(result)
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun requestVmSnapshot() {
        // A v2 request without the handshake identity is rejected by the agent
        // (MISSING_AGENT_SESSION_ID) with a null payload. Sending it anyway used
        // to latch `snapshotRequestOutstanding`, after which every later request
        // was silently dropped and the VM never registered.
        val session = agentSessionId
        val epoch = connectionEpoch
        if (session.isNullOrBlank() || epoch == null || epoch <= 0L) {
            log("暂不请求 VM snapshot：v2 握手身份尚未建立", DebugLogLevel.DEBUG)
            return
        }
        if (!snapshotRequestOutstanding.compareAndSet(false, true)) return
        val requestId = "vm-snapshot-${v2RequestSequence.incrementAndGet()}"
        val activeTransport = transporter
        if (activeTransport == null) {
            snapshotRequestOutstanding.set(false)
            log("无法请求 VM snapshot：当前没有可用传输通道", DebugLogLevel.WARNING)
            return
        }
        try {
        activeTransport.send(
            EmmyV2Message(
                EmmyV2Envelope(
                    kind = "request",
                    type = "vm.snapshot",
                    requestId = requestId,
                    agentSessionId = session,
                    connectionEpoch = epoch
                )
            )
        )
        } catch (error: Throwable) {
            snapshotRequestOutstanding.set(false)
            log("请求 VM snapshot 失败: ${error.message}", DebugLogLevel.WARNING)
            scheduleReconnect(reconnectAttempt.get() + 1)
        }
    }

    /**
     * Releases the snapshot latch and retries with a bounded backoff. Without
     * this a single rejected response fences the VM out of the registry for the
     * whole session, so every `debug.paused` is discarded as an unknown VM.
     */
    private fun retryVmSnapshotAfterRejection(reason: String) {
        snapshotRequestOutstanding.set(false)
        if (session.isStopped || reconnectStopped.get()) return
        val attempt = snapshotRetryAttempt.incrementAndGet()
        if (attempt > MAX_SNAPSHOT_RETRY_ATTEMPTS) {
            log("VM snapshot 连续被拒 $attempt 次（$reason），停止自动重试", DebugLogLevel.WARNING)
            return
        }
        log("VM snapshot 请求被拒绝（$reason），准备第 $attempt 次重试", DebugLogLevel.WARNING)
        ApplicationManager.getApplication().executeOnPooledThread {
            val delay = (100L shl (attempt - 1)).coerceAtMost(1_000L)
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }
            if (session.isStopped || reconnectStopped.get()) return@executeOnPooledThread
            lifecycle.execute(sessionGeneration) { requestVmSnapshot() }
        }
    }

    private fun acceptHandshake(json: JsonObject?): Boolean {
        if (json == null) return false
        val session = json.get("agentSessionId")?.takeUnless { it.isJsonNull }?.asString
        val epoch = json.get("connectionEpoch")?.takeUnless { it.isJsonNull }?.asLong
        if (session.isNullOrBlank() || epoch == null || epoch <= 0L) return false
        if (!vmRegistry.acceptConnection(session, epoch)) return false
        agentSessionId = session
        connectionEpoch = epoch
        awaitingVmSnapshot = vmRegistry.isAwaitingSnapshot()
        return true
    }

    private fun acceptHandshake(envelope: EmmyV2Envelope): Boolean {
        if (!vmRegistry.acceptConnection(envelope.agentSessionId, envelope.connectionEpoch)) return false
        agentSessionId = envelope.agentSessionId ?: agentSessionId
        connectionEpoch = envelope.connectionEpoch ?: connectionEpoch
        awaitingVmSnapshot = vmRegistry.isAwaitingSnapshot()
        return true
    }

    /** ACKs may arrive before the VM snapshot fence is released. */
    private fun isCurrentEnvelope(envelope: EmmyV2Envelope): Boolean =
        !envelope.agentSessionId.isNullOrBlank() &&
            envelope.agentSessionId == agentSessionId &&
            envelope.connectionEpoch != null &&
            envelope.connectionEpoch == connectionEpoch

    /** Rejects a response whose opaque pause/frame target does not match the request. */
    private fun matchesPendingTarget(
        envelope: EmmyV2Envelope,
        vmId: String,
        pauseId: Long?,
        threadId: String?,
        frameId: String? = null
    ): Boolean {
        val target = envelope.target ?: return false
        // VM identity is mandatory for every VM-scoped response. Accepting a
        // missing vmId would let a late response for another VM complete the
        // waiting request.
        if (target.vmId != vmId) return false
        if (pauseId != null && target.pauseId != null && target.pauseId != pauseId) return false
        if (pauseId != null && target.pauseId == null) return false
        if (!threadId.isNullOrBlank() && target.threadId != threadId) return false
        if (!frameId.isNullOrBlank() && target.frameId != frameId) return false
        return true
    }

    private fun scheduleReconnect(attempt: Int) {
        if (reconnectStopped.get() || session.isStopped) return
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectScheduled.set(false)
            lifecycle.post(sessionGeneration, DebugSessionEvent.FAILED) {
                this.error("调试传输重连达到最大次数")
                terminateFailedSession()
            }
            return
        }
        if (!reconnectScheduled.compareAndSet(false, true)) return
        reconnectAttempt.updateAndGet { current -> maxOf(current, attempt) }
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
            reconnectScheduled.set(false)
            lifecycle.execute(sessionGeneration) {
                if (reconnectStopped.get() || !::targetBootstrap.isInitialized) return@execute
                runCatching { startTransportCandidates(targetBootstrap.prepareReconnectTransports()) }
                    .onFailure { scheduleReconnect(reconnectAttempt.get() + 1) }
            }
        }
    }

    protected fun markInitialized() {
        agentReady = true
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
            if (breakpoints.remove(id) != null) sendCompositeBreakpointSnapshotLegacy()
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
            BreakPoint(shortPath, breakpoint.line + 1, logMessage = breakpoint.logExpressionObject?.expression,
                sourceIdentity = identity.toWire(), owner = "IDEA", breakpointId = "idea-$id")
        } else {
            BreakPoint(shortPath, breakpoint.line + 1, condition = breakpoint.conditionExpression?.expression,
                sourceIdentity = identity.toWire(), owner = "IDEA", breakpointId = "idea-$id")
        }
        val previous = breakpoints.put(id, protocolBreakpoint)
        if (sendUpdate && previous != protocolBreakpoint &&
            lifecycle.state in setOf(DebugSessionState.INITIALIZING, DebugSessionState.RUNNING)) {
            sendCompositeBreakpointSnapshotLegacy()
        }
    }

    override fun startPausing() {
        notifyCliUserControl()
        sendActionToAgent(DebugAction.Break)
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        notifyCliUserControl()
        clearInlineSnapshot()
        lifecycle.execute(sessionGeneration) {
            removeTemporaryBreakpoint()
            val breakpoint = BreakPoint(
                position.file.canonicalPath ?: position.file.path,
                position.line + 1,
                runToHere = true,
                sourceIdentity = SourceIdentity.fromPath(position.file.path).toWire(),
                owner = "SYSTEM",
                breakpointId = "run-to-position"
            )
            temporaryBreakpoint = breakpoint
            sendCompositeBreakpointSnapshotLegacy()
            sendActionToAgent(DebugAction.Continue)
        }
    }

    private fun onBreak(data: BreakNotify, present: Boolean = true, emitEvent: Boolean = true) {
        if (emitEvent) publishPauseEvent(data)
        // A pause that is already represented by the UI is a duplicate; a
        // pause for another VM/thread remains observable through the CLI
        // journal but must not replace the current XDebugger context.
        if (!present) return
        removeTemporaryBreakpoint()
        cancelEvaluationsThen(CancellationException("Emmy stack frame was replaced")) {
            handleBreak(data)
        }
    }

    /** Publishes the immutable pause event before any UI arbitration occurs. */
    private fun publishPauseEvent(data: BreakNotify) {
        val vmId = data.vmId
        val top = data.stacks.firstOrNull { it.file.isNotBlank() || it.line > 0 }
            ?: data.stacks.firstOrNull()
        val breakpoint = top?.let { stack ->
            breakpoints.values.firstOrNull { candidate ->
                candidate.line == stack.line &&
                    SourceIdentity.normalizePath(candidate.file) ==
                    SourceIdentity.normalizePath(stack.file) &&
                    (candidate.vmId == null || candidate.vmId == vmId)
            }
        }
        val matchingProbes = if (vmId != null && top != null) {
            cliProbes.values.filter { probe ->
                probe.vmId == vmId && probe.line == top.line &&
                    sourceIdentityMatches(probe.sourceIdentity, top.file, vmId, top.sourceIdentity)
            }
        } else emptyList()
        val reasons = buildSet {
            data.reasons.forEach(::add)
            if (isEmpty()) data.pauseReason?.split(',')?.map(String::trim)
                ?.filter(String::isNotEmpty)?.forEach(::add)
            if (breakpoint != null) add("USER")
            matchingProbes.forEach { add("PROBE:${it.probeId}") }
        }
        val sourcePath = top?.file
        val actualIdentity = top?.sourceIdentity
        publishCliEvent(
            "debug.paused", vmId, data.pauseId,
            mapOf(
                "file" to sourcePath,
                "canonicalPath" to sourcePath,
                "line" to top?.line,
                "frameId" to top?.let { it.frameId.takeIf(String::isNotBlank) ?: frameIdFor(data.pauseId ?: 0L, it) },
                "threadId" to data.threadId,
                "reason" to data.pauseReason,
                "reasons" to reasons,
                "pauseScope" to data.pauseScope,
                "consistency" to data.consistency,
                "sourceHash" to actualIdentity?.sourceHash,
                "sourceVerified" to actualIdentity?.verified,
                "sourceEpoch" to (vmId?.let { vmRegistry.resolve(it)?.sourceEpoch }
                    ?: actualIdentity?.sourceEpoch ?: actualIdentity?.loaderEpoch)
            )
        )
    }

    private fun handleBreak(data: BreakNotify) {
        val pause = if (v2Negotiated) data.vmId?.let { vm ->
            data.pauseId?.let { pauseSnapshots.get(vm, it) }
        } else null
        val frames = data.stacks.map { EmmyDebugStackFrame(it, this, pause) }
        val top = frames.firstOrNull { it.sourcePosition != null }
            ?: frames.firstOrNull { it.data.line > 0 }
            ?: frames.firstOrNull()
        val stack = LuaExecutionStack(frames)
        if (top != null)
            stack.setTopFrame(top)
        val sourcePosition = top?.sourcePosition
        val breakpoint = sourcePosition?.let { getBreakpoint(it.file, it.line) }
        val sourcePath = top?.data?.file
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
        notifyCliUserControl()
        clearInlineSnapshot()
        pauseSnapshots.clear()
        vmRegistry.list().forEach { vmRegistry.invalidatePause(it.vmId) }
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            sendActionToAgent(DebugAction.Continue)
        }
    }

    private fun removeTemporaryBreakpoint() {
        val breakpoint = temporaryBreakpoint ?: return
        temporaryBreakpoint = null
        sendCompositeBreakpointSnapshotLegacy()
    }

    final override fun stop() {
        reconnectStopped.set(true)
        cancelV2Requests(CancellationException("Emmy debug session stopped"))
        pauseSnapshots.clear()
        clearInlineSnapshot()
        cancelEvaluationsThen(CancellationException("Emmy debug session stopped")) {
            lifecycle.post(sessionGeneration, DebugSessionEvent.STOP_REQUESTED) {
                removeTemporaryBreakpoint()
                sendActionToAgent(DebugAction.Stop)
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
        cancelV2Requests(IOException("Emmy debug target stopped"))
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
        notifyCliUserControl()
        clearInlineSnapshot()
        pauseSnapshots.clear()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            sendActionToAgent(DebugAction.StepOver)
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        notifyCliUserControl()
        clearInlineSnapshot()
        pauseSnapshots.clear()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            sendActionToAgent(DebugAction.StepIn)
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        notifyCliUserControl()
        clearInlineSnapshot()
        pauseSnapshots.clear()
        cancelEvaluationsThen(CancellationException("Emmy execution resumed")) {
            publishCliEvent("debug.resumed")
            sendActionToAgent(DebugAction.StepOut)
        }
    }

    /**
     * Rebuilds the complete breakpoint view owned by this IDEA target.  The
     * native side receives one replacement snapshot so an update from IDEA,
     * CLI, or a Probe cannot erase contributions belonging to another owner.
     * This method is only called on the serialized lifecycle executor.
     */
    private fun sendCompositeBreakpointSnapshotLegacy(acknowledgement: CompletableFuture<Result<Long>>? = null): Result<Long> {
        val active = transporter ?: return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        val contributions = breakpointComposer
        contributions.clear()
        val identities = mutableMapOf<BreakpointKey, SourceIdentity>()

        fun identityFor(breakpoint: BreakPoint): SourceIdentity = breakpoint.sourceIdentity?.let {
            SourceIdentity(
                uri = it.uri,
                canonicalPath = it.canonicalPath.ifBlank { breakpoint.file },
                sourceHash = it.sourceHash,
                loaderEpoch = it.loaderEpoch,
                verified = it.verified,
                sourceEpoch = it.sourceEpoch
            )
        } ?: SourceIdentity.fromPath(breakpoint.file)

        fun keyFor(identity: SourceIdentity, vmId: String?, line: Int): BreakpointKey =
            BreakpointKey(
                sourceIdentity = buildString {
                    append(SourceIdentity.normalizePath(identity.canonicalPath))
                    identity.sourceHash?.let { append("#hash=").append(it) }
                    identity.effectiveEpoch?.let { append("#epoch=").append(it) }
                },
                vmId = vmId.orEmpty(),
                line = line
            )

        fun addContribution(breakpoint: BreakPoint, autoContinue: Boolean = false) {
            val identity = identityFor(breakpoint)
            val owner = breakpoint.owner?.takeIf { it.isNotBlank() } ?: "IDEA"
            val id = breakpoint.breakpointId?.takeIf { it.isNotBlank() }
                ?: "$owner-${identity.canonicalPath}:${breakpoint.line}"
            val key = keyFor(identity, breakpoint.vmId, breakpoint.line)
            identities[key] = identity
            contributions.upsert(
                key,
                BreakpointContribution(
                    owner = owner,
                    breakpointId = id,
                    condition = breakpoint.condition,
                    logMessage = breakpoint.logMessage,
                    hitCondition = breakpoint.hitCondition,
                    runToHere = breakpoint.runToHere,
                    autoContinue = autoContinue
                )
            )
        }

        breakpoints.values.forEach(::addContribution)
        cliBreakpoints.values.forEach { spec ->
            addContribution(
                BreakPoint(
                    file = spec.sourceIdentity.canonicalPath,
                    line = spec.line,
                    condition = spec.condition,
                    logMessage = spec.logMessage,
                    hitCondition = spec.hitCondition,
                    sourceIdentity = SourceIdentityWire(
                        canonicalPath = spec.sourceIdentity.canonicalPath,
                        uri = spec.sourceIdentity.uri,
                        sourceHash = spec.sourceIdentity.sourceHash,
                        sourceEpoch = spec.sourceIdentity.sourceEpoch,
                        verified = spec.sourceIdentity.verified
                    ),
                    owner = spec.owner,
                    breakpointId = spec.breakpointId,
                    vmId = spec.vmId
                ),
                autoContinue = spec.breakpointId.removePrefix("probe:").let { probeId ->
                    cliProbes[probeId]?.autoContinue == true
                }
            )
        }
        temporaryBreakpoint?.let(::addContribution)

        val snapshot = contributions.snapshot().map { composite ->
            val identity = identities[composite.key]
            val first = composite.contributions.firstOrNull()
            val source = identity?.toWire()
            BreakPoint(
                file = source?.canonicalPath ?: composite.key.sourceIdentity,
                line = composite.key.line,
                condition = first?.condition,
                logMessage = first?.logMessage,
                hitCondition = first?.hitCondition,
                runToHere = first?.runToHere ?: false,
                sourceIdentity = source,
                owner = first?.owner,
                breakpointId = first?.breakpointId,
                vmId = composite.key.vmId.takeIf { it.isNotBlank() },
                composite = true,
                contributions = composite.contributions.map {
                    BreakpointContributionWire(
                        owner = it.owner,
                        breakpointId = it.breakpointId,
                        condition = it.condition,
                        logMessage = it.logMessage,
                        hitCondition = it.hitCondition,
                        runToHere = it.runToHere,
                        autoContinue = it.autoContinue
                    )
                }
            )
        }

        if (v2Negotiated && isCurrentConnection()) {
            val revision = wireBreakpointRevision.incrementAndGet()
            val requestId = "ide-breakpoints-${v2RequestSequence.incrementAndGet()}"
            val payload = JsonObject().apply {
                addProperty("revision", revision)
                add("breakpoints", Gson().toJsonTree(snapshot))
            }
            val future = acknowledgement ?: CompletableFuture<Result<Long>>()
            v2BreakpointRequests[requestId] = future
            try {
                log(
                    "发送 Emmy v2 断点替换：requestId=$requestId revision=$revision count=${snapshot.size} " +
                        "agentSessionId=${agentSessionId ?: "-"} connectionEpoch=${connectionEpoch ?: "-"}",
                    DebugLogLevel.DEBUG
                )
                active.send(EmmyV2Message(EmmyV2Envelope(
                    kind = "request",
                    type = "debug.breakpoints.replace",
                    requestId = requestId,
                    agentSessionId = agentSessionId,
                    connectionEpoch = connectionEpoch,
                    payload = payload
                )))
            } catch (error: Throwable) {
                v2BreakpointRequests.remove(requestId)
                return Result.failure(error)
            }
            return Result.success(revision)
        }

        // Legacy agents do not send an ACK. Clear + replace keeps removals
        // deterministic while preserving the v1 wire ids.
        log("发送 Emmy legacy 断点替换：count=${snapshot.size} v2Negotiated=$v2Negotiated", DebugLogLevel.DEBUG)
        active.send(AddBreakPointReq(snapshot, clear = true, replaceComposite = true))
        acknowledgement?.complete(Result.success(0L))
        return Result.success(0L)
    }

    private fun isCurrentConnection(): Boolean =
        !agentSessionId.isNullOrBlank() && connectionEpoch != null && vmRegistry.isConnected()

    private fun registerCliTarget() {
        runCatching {
            CliGatewayApplicationService.getInstance().register(this)
            cliRegistered = true
            autoAuthorizeCliClients()
        }.onFailure { log("CLI Gateway 注册 Emmy target 失败: ${it.message}", DebugLogLevel.WARNING) }
    }

    /**
     * Whether the concrete run configuration opted into CLI auto-grant.
     */
    protected open fun configurationAllowsCliAutoGrant(): Boolean = false

    /**
     * Grants the first-party local CLI client without a manual prompt when the
     * run configuration opted in. Off by default: the gateway can read debuggee
     * values and drive execution, so this only ever runs for trusted projects,
     * and the grant stays withdrawable from Tools.
     */
    private fun autoAuthorizeCliClients() {
        if (!configurationAllowsCliAutoGrant()) return
        if (!projectTrusted) {
            log("已启用 CLI 自动授权，但项目未受信任，已跳过", DebugLogLevel.WARNING)
            return
        }
        val gateway = CliGatewayApplicationService.getInstance()
        runCatching { gateway.grant(debugTargetId, DEFAULT_CLI_AUTO_GRANT_CLIENT) }
            .onSuccess { log("已自动授权 CLI 客户端: $DEFAULT_CLI_AUTO_GRANT_CLIENT", DebugLogLevel.RUNTIME) }
            .onFailure { log("自动授权 CLI 客户端失败: ${it.message}", DebugLogLevel.WARNING) }
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
        agentReady = agentReady,
        vms = debugVmList(),
        vmReady = vmRegistry.list().any { it.state in setOf("READY", "RUNNING", "PAUSED") } &&
            vmRegistry.isConnected() && !vmRegistry.isAwaitingSnapshot()
    )

    override val projectTrusted: Boolean
        get() = runCatching {
            // Keep this optional because the 251 and 252 platform distributions
            // expose the trust service from different internal packages.
            val type = Class.forName("com.intellij.ide.impl.TrustedProjects")
            val method = type.getMethod("isTrusted", com.intellij.openapi.project.Project::class.java)
            method.invoke(null, session.project) as? Boolean ?: false
        }.getOrDefault(false)

    override fun debugVmList(): List<CliVmSummary> = vmRegistry.list().map {
        CliVmSummary(
            vmId = it.vmId,
            generation = it.generation,
            displayName = it.displayName,
            state = it.state,
            luaVersion = it.luaVersion,
            discovery = it.discovery,
            activePauseId = it.activePauseId,
            connectionEpoch = it.connectionEpoch,
            contextGeneration = it.contextGeneration,
            sourceEpoch = it.sourceEpoch
        )
    }

    override fun debugPause(vmId: String, pauseId: Long?): PauseSnapshot? {
        val vm = vmRegistry.resolve(vmId) ?: return null
        if (!vmRegistry.isConnected() || vmRegistry.isAwaitingSnapshot() ||
            vm.state in setOf("LOST", "CLOSED", "CLOSING", "ERROR")) return null
        val selected = pauseId ?: vm.activePauseId ?: return null
        val snapshot = pauseSnapshots.get(vmId, selected) ?: return null
        // Compare epochs only when both sides know theirs: a legacy record may be
        // registered before the handshake bound the transport epoch.
        if (snapshot.connectionEpoch != null && vm.connectionEpoch != null &&
            snapshot.connectionEpoch != vm.connectionEpoch) return null
        if (snapshot.contextGeneration != null && vm.contextGeneration != null &&
            snapshot.contextGeneration != vm.contextGeneration) return null
        if (snapshot.sourceEpoch != null && vm.sourceEpoch != null && snapshot.sourceEpoch != vm.sourceEpoch) return null
        return snapshot
    }

    override fun debugControl(request: CliControlRequest): Result<CliControlResult> {
        val vm = vmRegistry.resolve(request.vmId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.VM_NOT_FOUND))
        if (!vmRegistry.isControlReady(request.vmId)) {
            return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        }
        if (v2Negotiated && awaitingVmSnapshot) {
            return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        }
        val pause = if (request.action == "Break") null else debugPause(request.vmId, request.pauseId)
        if (request.action != "Break" && pause == null) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        if (pause != null && !request.threadId.isNullOrBlank() && pause.threadId != request.threadId) {
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
        if (!v2Negotiated) {
            val activeVms = vmRegistry.list().filter { it.state !in setOf("LOST", "CLOSED", "CLOSING", "ERROR") }
            if (activeVms.size != 1 || activeVms.singleOrNull()?.vmId != request.vmId) {
                return Result.failure(IllegalStateException(
                    if (activeVms.any { it.vmId == request.vmId }) CliErrorCodes.AMBIGUOUS_VM
                    else CliErrorCodes.VM_NOT_FOUND
                ))
            }
            return runOnLifecycle {
                sendRequired(DebugActionMessage(action))
                onControlAccepted(request)
                CliControlResult(request.action, true, request.pauseId)
            }
        }
        val token = sessionGeneration
        if (token == 0L || lifecycle.state.isTerminal) {
            return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        }
        val requestId = "cli-action-${v2RequestSequence.incrementAndGet()}"
            val future = CompletableFuture<Result<CliControlResult>>()
            v2ControlRequests[requestId] = PendingControl(future, request.vmId, request.pauseId, request.threadId)
        try {
            lifecycle.execute(token) {
                if (!v2ControlRequests.containsKey(requestId)) return@execute
                try {
                    sendRequired(makeV2ActionMessage(requestId, request.vmId, action, request.pauseId, request.threadId))
                } catch (error: Throwable) {
                    v2ControlRequests.remove(requestId)?.future?.complete(Result.failure(error))
                }
            }
        } catch (error: Throwable) {
            v2ControlRequests.remove(requestId)
            return Result.failure(error)
        }
        val awaited = awaitResult(future, 5_000L)
        if (awaited.isFailure) v2ControlRequests.remove(requestId)
        return awaited.map { result ->
            if (result.accepted) onControlAccepted(request)
            result.copy(action = request.action)
        }
    }

    override fun debugEvaluate(request: CliEvaluationRequest): Result<CliCapturedValue> {
        if (request.expression.length > 4096) return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_LIMIT_EXCEEDED))
        if (request.policy != "VALUE_PATH") {
            return Result.failure(IllegalArgumentException(CliErrorCodes.EVALUATION_DENIED))
        }
        val vm = vmRegistry.resolve(request.vmId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.VM_NOT_FOUND))
        if (!vmRegistry.isControlReady(request.vmId)) {
            return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        }
        if (awaitingVmSnapshot) return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        val snapshot = debugPause(request.vmId, request.pauseId)
            ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
            val frame = snapshot.stacks.firstOrNull {
                (it.frameId.takeIf(String::isNotBlank) ?: frameIdFor(snapshot.pauseId, it)) == request.frameId
            }
                ?: return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        if (!request.threadId.isNullOrBlank() && snapshot.threadId != request.threadId) {
            return Result.failure(IllegalStateException(CliErrorCodes.STALE_PAUSE_REFERENCE))
        }
        val requestedSource = request.sourceIdentity
        if (requestedSource != null && !sourceIdentityMatches(requestedSource, frame.file, request.vmId, frame.sourceIdentity)) {
            return Result.failure(IllegalStateException(CliErrorCodes.SOURCE_IDENTITY_MISMATCH))
        }
        if (v2Negotiated) {
            val token = sessionGeneration
            if (token == 0L || lifecycle.state.isTerminal) {
                return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
            }
            val requestId = "cli-eval-${v2RequestSequence.incrementAndGet()}"
            val requestEpoch = connectionEpoch
            val requestAgent = agentSessionId
            val future = CompletableFuture<Result<CliCapturedValue>>()
            v2EvaluationRequests[requestId] = PendingEvaluation(
                future, request.vmId, request.pauseId, request.threadId, request.frameId
            )
            try {
                lifecycle.execute(token) {
                    if (!v2EvaluationRequests.containsKey(requestId)) return@execute
                    try {
                        sendRequired(makeV2EvaluationMessage(requestId, request, frame))
                    } catch (error: Throwable) {
                        v2EvaluationRequests.remove(requestId)?.future?.complete(Result.failure(error))
                    }
                }
            } catch (error: Throwable) {
                v2EvaluationRequests.remove(requestId)
                cancelNativeRequest(requestId, token, requestEpoch, requestAgent)
                return Result.failure(error)
            }
            val awaited = awaitResult(future, 10_000L).map { value -> value.copy(expression = request.expression) }
            if (awaited.isFailure) {
                val error = awaited.exceptionOrNull() ?: IllegalStateException(CliErrorCodes.EVALUATION_DENIED)
                v2EvaluationRequests.remove(requestId)
                cancelNativeRequest(requestId, token, requestEpoch, requestAgent)
                return Result.failure(error)
            }
            return awaited
        }
        // v1's evaluator executes Lua code and may invoke metamethods even for
        // a syntactically simple path. External callers require native raw access.
        return Result.failure(IllegalStateException("UNSUPPORTED_CAPABILITY"))
    }

    private fun cancelNativeRequest(cancelledRequestId: String, token: Long, epoch: Long?, agent: String?) {
        if (!v2Negotiated || token == 0L || lifecycle.state.isTerminal || token != sessionGeneration ||
            epoch != connectionEpoch || agent != agentSessionId) return
        lifecycle.execute(token) {
            if (epoch != connectionEpoch || agent != agentSessionId) return@execute
            runCatching {
                sendRequired(EmmyV2Message(EmmyV2Envelope(
                    kind = "request", type = "request.cancel",
                    requestId = "cancel-${v2RequestSequence.incrementAndGet()}",
                    agentSessionId = agentSessionId, connectionEpoch = connectionEpoch,
                    payload = JsonObject().apply { addProperty("requestId", cancelledRequestId) }
                )))
            }
        }
    }

    override fun debugBreakpoints(): List<CliBreakpointSpec> = cliBreakpoints.values.sortedBy { it.breakpointId }

    override fun debugMutateBreakpoints(request: CliBreakpointMutation): Result<CliBreakpointResult> {
        if (vmRegistry.isAwaitingSnapshot() || !vmRegistry.isConnected()) {
            return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_READY))
        }
        if (!cliBreakpointMutationPending.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException(CliErrorCodes.TARGET_BUSY))
        }
        val token = sessionGeneration
        val result = CompletableFuture<Result<CliBreakpointResult>>()
        val ack = CompletableFuture<Result<Long>>()
        lifecycle.execute(token) {
            if (ack.isDone) {
                cliBreakpointMutationPending.set(false)
                return@execute
            }
            val before = cliBreakpoints.toMap()
            val stateGeneration = cliStateGeneration.get()
            try {
                if (request.expectedRevision != null && request.expectedRevision != cliBreakpointRevision.get()) {
                    throw IllegalStateException("BREAKPOINT_REVISION_CONFLICT")
                }
                request.remove.forEach { id ->
                    val existing = cliBreakpoints[id]
                    if (existing != null) {
                        if (request.owner != null && existing.owner != request.owner) {
                            throw IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED)
                        }
                        cliBreakpoints.remove(id)
                    }
                }
                request.add.forEach { spec ->
                    if (vmRegistry.resolve(spec.vmId) == null) throw IllegalStateException(CliErrorCodes.VM_NOT_FOUND)
                    if (request.owner != null && spec.owner != request.owner) {
                        throw IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED)
                    }
                    if (cliBreakpoints[spec.breakpointId]?.owner?.let { it != spec.owner } == true) {
                        throw IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED)
                    }
                    cliBreakpoints[spec.breakpointId] = spec
                }
                ack.whenComplete { received, error ->
                    // Completion may originate from a CLI timeout. All map
                    // mutations and rollback still belong to the lifecycle loop.
                    lifecycle.execute(token) {
                        v2BreakpointRequests.entries.removeIf { it.value === ack }
                        if (stateGeneration != cliStateGeneration.get()) {
                            result.complete(Result.failure(IllegalStateException(CliErrorCodes.CONTEXT_RESET)))
                            cliBreakpointMutationPending.set(false)
                            return@execute
                        }
                        val failure = error ?: received?.exceptionOrNull()
                        if (failure != null) {
                            cliBreakpoints.clear()
                            cliBreakpoints.putAll(before)
                            runCatching { sendCompositeBreakpointSnapshotLegacy() }
                            result.complete(Result.failure(failure))
                        } else {
                            result.complete(Result.success(CliBreakpointResult(
                                cliBreakpointRevision.incrementAndGet(), debugBreakpoints(), acknowledged = v2Negotiated)))
                        }
                        cliBreakpointMutationPending.set(false)
                    }
                }
                val sent = sendCompositeBreakpointSnapshotLegacy(ack)
                if (sent.isFailure) ack.complete(sent)
            } catch (error: Throwable) {
                cliBreakpoints.clear()
                cliBreakpoints.putAll(before)
                // Best effort rollback; preserve the original error for the
                // caller even if the transport is already gone.
                runCatching { sendCompositeBreakpointSnapshotLegacy() }
                result.complete(Result.failure(error))
                cliBreakpointMutationPending.set(false)
            }
        }
        val completed = awaitResult(result, 5_000L)
        if (completed.isFailure) {
            ack.complete(Result.failure(completed.exceptionOrNull() ?: java.util.concurrent.TimeoutException()))
            if (lifecycle.state.isTerminal || token != sessionGeneration) cliBreakpointMutationPending.set(false)
        }
        return completed
    }

    override fun debugInstallProbe(spec: CliProbeSpec): Result<CliProbeSpec> {
        if (spec.targetId != debugTargetId) return Result.failure(IllegalStateException(CliErrorCodes.TARGET_NOT_FOUND))
        if (vmRegistry.resolve(spec.vmId) == null) return Result.failure(IllegalStateException(CliErrorCodes.VM_NOT_FOUND))
        if (cliProbes.containsKey(spec.probeId)) return Result.failure(IllegalStateException("PROBE_EXISTS"))
        val breakpoint = CliBreakpointSpec(
            breakpointId = "probe:${spec.probeId}",
            owner = spec.owner,
            vmId = spec.vmId,
            sourceIdentity = spec.sourceIdentity,
            line = spec.line,
            condition = spec.condition,
            scope = "SESSION"
        )
        cliProbes[spec.probeId] = spec
        val installed = debugMutateBreakpoints(CliBreakpointMutation(add = listOf(breakpoint), owner = spec.owner))
        if (installed.isFailure) {
            cliProbes.remove(spec.probeId, spec)
            return installed.map { spec }
        }
        return Result.success(spec)
    }

    override fun debugRemoveProbe(probeId: String, owner: String): Result<Boolean> {
        val probe = cliProbes[probeId] ?: return Result.success(false)
        if (probe.owner != owner) return Result.failure(IllegalAccessException(CliErrorCodes.NOT_AUTHORIZED))
        // Remove the probe contribution before rebuilding the composite
        // snapshot.  Keeping it in cliProbes while the breakpoint mutation is
        // sent would cause sendCompositeBreakpointSnapshotLegacy() to add it
        // back immediately.
        cliProbes.remove(probeId, probe)
        val removed = debugMutateBreakpoints(CliBreakpointMutation(remove = listOf("probe:$probeId"), owner = owner))
        if (removed.isFailure) {
            cliProbes[probeId] = probe
            // Best effort restore of the in-memory/backend contribution.  The
            // original mutation error remains authoritative for the caller.
            runCatching {
                debugMutateBreakpoints(CliBreakpointMutation(
                    add = listOf(CliBreakpointSpec(
                        breakpointId = "probe:$probeId",
                        owner = probe.owner,
                        vmId = probe.vmId,
                        sourceIdentity = probe.sourceIdentity,
                        line = probe.line,
                        condition = probe.condition,
                        scope = "SESSION"
                    )),
                    owner = owner
                ))
            }
            return removed.map { true }
        }
        return Result.success(true)
    }

    private fun BreakPoint.toCliSpec(id: String, vmId: String): CliBreakpointSpec = CliBreakpointSpec(
        id, "IDEA", vmId, sourceIdentity?.let { CliSourceIdentity(it.uri, it.canonicalPath, it.sourceHash, it.sourceEpoch ?: it.loaderEpoch, it.verified) }
            ?: CliSourceIdentity("", file), line, condition, logMessage, hitCondition
    )

    private fun CliBreakpointSpec.toProtocol(): BreakPoint = BreakPoint(
        sourceIdentity.canonicalPath, line, condition, logMessage, hitCondition,
        owner = owner, breakpointId = breakpointId, vmId = vmId,
        sourceIdentity = SourceIdentityWire(sourceIdentity.canonicalPath, sourceIdentity.uri, sourceIdentity.sourceHash,
            sourceEpoch = sourceIdentity.sourceEpoch, verified = sourceIdentity.verified)
    )

    private fun makeV2ActionMessage(
        requestId: String,
        vmId: String,
        action: DebugAction,
        pauseId: Long?,
        threadId: String?
    ): EmmyV2Message {
        val payload = JsonObject().apply { addProperty("action", action.wireId) }
        return EmmyV2Message(
            EmmyV2Envelope(
                kind = "request",
                type = "debug.action",
                requestId = requestId,
                agentSessionId = agentSessionId,
                connectionEpoch = connectionEpoch,
                contextGeneration = vmRegistry.resolve(vmId)?.contextGeneration,
                sourceEpoch = vmRegistry.resolve(vmId)?.sourceEpoch,
                target = EmmyV2Target(vmId = vmId, threadId = threadId, pauseId = pauseId),
                payload = payload
            )
        )
    }

    private fun makeV2EvaluationMessage(
        requestId: String,
        request: CliEvaluationRequest,
        frame: Stack
    ): EmmyV2Message {
        val source = request.sourceIdentity ?: frame.sourceIdentity?.let {
            CliSourceIdentity(it.uri, it.canonicalPath, it.sourceHash, it.sourceEpoch, it.verified)
        } ?: CliSourceIdentity(frame.file, SourceIdentity.normalizePath(frame.file),
            sourceEpoch = debugPause(request.vmId, request.pauseId)?.sourceEpoch, verified = false)
        val sourceJson = JsonObject().apply {
            addProperty("uri", source.uri)
            addProperty("canonicalPath", source.canonicalPath)
            source.sourceHash?.let { addProperty("sourceHash", it) }
            source.sourceEpoch?.let { addProperty("sourceEpoch", it) }
            addProperty("verified", source.verified)
        }
        val payload = JsonObject().apply {
            addProperty("expr", request.expression)
            addProperty("stackLevel", frame.level)
            addProperty("depth", request.maxDepth)
            addProperty("maxDepth", request.maxDepth)
            addProperty("maxNodes", request.maxNodes)
            addProperty("maxBytes", request.maxBytes)
            addProperty("policy", request.policy)
            addProperty("cacheId", 0)
            add("sourceIdentity", sourceJson)
        }
        return EmmyV2Message(
            EmmyV2Envelope(
                kind = "request",
                type = "debug.eval",
                requestId = requestId,
                agentSessionId = agentSessionId,
                connectionEpoch = connectionEpoch,
                contextGeneration = debugPause(request.vmId, request.pauseId)?.contextGeneration,
                sourceEpoch = debugPause(request.vmId, request.pauseId)?.sourceEpoch,
                target = EmmyV2Target(
                    vmId = request.vmId,
                    threadId = request.threadId ?: debugPause(request.vmId, request.pauseId)?.threadId,
                    pauseId = request.pauseId,
                    frameId = request.frameId
                ),
                payload = payload
            )
        )
    }

    private fun parseV2EvaluationResponse(envelope: EmmyV2Envelope): Result<CliCapturedValue> {
        return EmmyEvaluationResult.decode(envelope)
    }

    private fun onControlAccepted(request: CliControlRequest) {
        if (request.action != "Break") {
            pauseSnapshots.invalidate(request.vmId, request.pauseId)
            vmRegistry.invalidatePause(request.vmId, request.pauseId)
            publishCliEvent("debug.resumed", request.vmId, request.pauseId, mapOf("action" to request.action))
        } else {
            publishCliEvent("debug.pauseRequested", request.vmId, request.pauseId)
        }
    }

    private fun currentVmId(): String? = pauseSnapshots.currentUiPause()?.vmId
        ?: vmRegistry.resolve()?.vmId

    /** Invalidates every reference that can outlive an UnLua context reload. */
    private fun resetContextState(vmId: String, contextGeneration: Long?, sourceEpoch: Long?) {
        vmRegistry.resetContext(vmId, contextGeneration, sourceEpoch)
        pauseSnapshots.invalidate(vmId)
        cancelV2Requests(CancellationException(CliErrorCodes.CONTEXT_RESET))
        cancelEvaluations(CancellationException(CliErrorCodes.CONTEXT_RESET))
        clearInlineSnapshot()
        // CLI breakpoints and probes carry source identity. Keeping them after
        // a loader epoch change would allow a stale script to be evaluated.
        cliBreakpoints.entries.removeIf { it.value.vmId == vmId }
        cliProbes.entries.removeIf { it.value.vmId == vmId }
        runCatching { sendCompositeBreakpointSnapshotLegacy() }
        publishCliEvent("vm.contextReset", vmId, payload = mapOf(
            "contextGeneration" to contextGeneration,
            "sourceEpoch" to sourceEpoch,
            "code" to CliErrorCodes.CONTEXT_RESET
        ))
        runCatching { CliGatewayApplicationService.getInstance().notifyVmContextReset(debugTargetId, vmId) }
    }

    private fun sendActionToAgent(action: DebugAction, vmId: String? = currentVmId(), pauseId: Long? = null, threadId: String? = null) {
        if (v2Negotiated && !vmId.isNullOrBlank()) {
            val requestId = "ide-action-${v2RequestSequence.incrementAndGet()}"
            runCatching { sendRequired(makeV2ActionMessage(requestId, vmId, action, pauseId, threadId)) }
                .onFailure { log("发送 v2 调试动作失败: ${it.message}", DebugLogLevel.WARNING) }
        } else {
            send(DebugActionMessage(action))
        }
    }

    private fun sourceIdentityMatches(identity: CliSourceIdentity, framePath: String, vmId: String? = null,
                                      loadedSource: SourceIdentityWire? = null): Boolean {
        val actual = loadedSource ?: SourceIdentityWire(canonicalPath = framePath, uri = framePath,
            sourceEpoch = vmId?.let { vmRegistry.resolve(it)?.sourceEpoch }, verified = false)
        if (identity.canonicalPath.isNotBlank() &&
            SourceIdentity.normalizePath(identity.canonicalPath) != SourceIdentity.normalizePath(actual.canonicalPath)) return false
        if (identity.verified && !actual.verified) return false
        if (identity.sourceHash != null && identity.sourceHash != actual.sourceHash) return false
        if (identity.sourceEpoch != null) {
            val vmEpoch = vmId?.let { vmRegistry.resolve(it)?.sourceEpoch }
            if (identity.sourceEpoch != (vmEpoch ?: actual.sourceEpoch ?: actual.loaderEpoch)) return false
        }
        return true
    }

    private fun <T> awaitResult(future: CompletableFuture<Result<T>>, timeoutMillis: Long): Result<T> = try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
        Result.failure(error.cause ?: error)
    }

    private fun cancelV2Requests(cause: Throwable) {
        cliStateGeneration.incrementAndGet()
        v2BreakpointRequests.values.forEach { it.complete(Result.failure(cause)) }
        v2BreakpointRequests.clear()
        v2ControlRequests.values.forEach { it.future.complete(Result.failure(cause)) }
        v2ControlRequests.clear()
        v2EvaluationRequests.values.forEach { it.future.complete(Result.failure(cause)) }
        v2EvaluationRequests.clear()
    }

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

    private fun notifyCliUserControl() {
        runCatching { CliGatewayApplicationService.getInstance().notifyUserControl(debugTargetId) }
    }

    private fun clearInlineSnapshot() {
        InlineDebugSnapshotStore.getInstance(session.project).clear(session)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    override fun registerAdditionalActions(leftToolbar: DefaultActionGroup, topToolbar: DefaultActionGroup, settings: DefaultActionGroup) {
        super.registerAdditionalActions(leftToolbar, topToolbar, settings)
        topToolbar.add(object : AnAction("选择 Lua VM 暂停") {
            override fun actionPerformed(event: AnActionEvent) {
                val pauses = listOfNotNull(pauseSnapshots.currentUiPause()) + pauseSnapshots.queuedPauses()
                if (pauses.isEmpty()) return
                val labels = pauses.map { "${vmRegistry.resolve(it.vmId)?.displayName ?: it.vmId} · ${it.vmId} · 暂停 ${it.pauseId}" }
                JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
                    .setTitle("选择要查看和控制的 Lua VM")
                    .setItemChosenCallback { label ->
                        val chosen = pauses[labels.indexOf(label)]
                        notifyCliUserControl()
                        lifecycle.execute(sessionGeneration) {
                            val selected = pauseSnapshots.select(chosen.vmId, chosen.pauseId) ?: return@execute
                            cancelEvaluationsThen(CancellationException("Lua VM selection changed")) {
                                handleBreak(BreakNotify(selected.stacks, selected.vmId, selected.pauseId,
                                    selected.threadId, selected.scope, selected.consistency, reasons = selected.reasons.toList()))
                            }
                        }
                    }.createPopup().showInBestPositionFor(event.dataContext)
            }
        })
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
