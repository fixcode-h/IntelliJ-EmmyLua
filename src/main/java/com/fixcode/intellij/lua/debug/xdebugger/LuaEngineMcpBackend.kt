package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaDeviceService
import com.fixcode.intellij.lua.debug.api.LuaDeviceState
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.BreakpointVerification
import com.fixcode.intellij.lua.debug.engine.BreakpointView
import com.fixcode.intellij.lua.debug.engine.DebugEngineException
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.FrameHandle
import com.fixcode.intellij.lua.debug.engine.Initiator
import com.fixcode.intellij.lua.debug.engine.SessionState as EngineState
import com.fixcode.intellij.lua.debug.engine.SourcePaths
import com.fixcode.intellij.lua.debug.engine.StaleReferenceException
import com.fixcode.intellij.lua.debug.engine.VariableContainer
import com.fixcode.intellij.lua.debug.engine.VariableHandle
import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.EvalModes
import com.fixcode.intellij.lua.debug.protocol.OutputCategories
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import com.fixcode.intellij.lua.debug.protocol.ScopeNames
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugSessionStarter
import com.fixcode.intellij.lua.debugger.LuaLineBreakpointType
import com.fixcode.intellij.lua.debugger.mcp.AttachDeviceRequest
import com.fixcode.intellij.lua.debugger.mcp.AttachProcessRequest
import com.fixcode.intellij.lua.debugger.mcp.BreakpointFilter
import com.fixcode.intellij.lua.debugger.mcp.BreakpointInfo
import com.fixcode.intellij.lua.debugger.mcp.BreakpointOrigin
import com.fixcode.intellij.lua.debugger.mcp.BreakpointSpec
import com.fixcode.intellij.lua.debugger.mcp.DebugEvent
import com.fixcode.intellij.lua.debugger.mcp.ErrorCode
import com.fixcode.intellij.lua.debugger.mcp.EvalMode
import com.fixcode.intellij.lua.debugger.mcp.EvaluateRequest
import com.fixcode.intellij.lua.debugger.mcp.FrameInfo
import com.fixcode.intellij.lua.debugger.mcp.LuaDebugBackend
import com.fixcode.intellij.lua.debugger.mcp.LuaDebugBackendException
import com.fixcode.intellij.lua.debugger.mcp.OutputCategory
import com.fixcode.intellij.lua.debugger.mcp.OutputEntry
import com.fixcode.intellij.lua.debugger.mcp.OutputPage
import com.fixcode.intellij.lua.debugger.mcp.PauseInfo
import com.fixcode.intellij.lua.debugger.mcp.PauseReason
import com.fixcode.intellij.lua.debugger.mcp.ProbeHit
import com.fixcode.intellij.lua.debugger.mcp.ProbeHitPage
import com.fixcode.intellij.lua.debugger.mcp.ReloadItem
import com.fixcode.intellij.lua.debugger.mcp.ReloadRequest
import com.fixcode.intellij.lua.debugger.mcp.ReloadResult
import com.fixcode.intellij.lua.debugger.mcp.ReloadTargetResult
import com.fixcode.intellij.lua.debug.ue.UnrealLuaReloader
import com.fixcode.intellij.lua.debugger.mcp.SessionInfo
import com.fixcode.intellij.lua.debugger.mcp.SessionKind
import com.fixcode.intellij.lua.debugger.mcp.SessionState
import com.fixcode.intellij.lua.debugger.mcp.SourceLine
import com.fixcode.intellij.lua.debugger.mcp.StackPage
import com.fixcode.intellij.lua.debugger.mcp.StepKind
import com.fixcode.intellij.lua.debugger.mcp.TargetInfo
import com.fixcode.intellij.lua.debugger.mcp.TargetKind
import com.fixcode.intellij.lua.debugger.mcp.VariableInfo
import com.fixcode.intellij.lua.debugger.mcp.VariableScope
import com.fixcode.intellij.lua.debugger.mcp.VariablesPage
import com.fixcode.intellij.lua.debugger.mcp.VariablesRequest
import com.fixcode.intellij.lua.debugger.mcp.VmInfo
import com.fixcode.intellij.lua.debugger.mcp.VmState
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import com.fixcode.intellij.lua.debug.engine.BreakpointOrigin as EngineOrigin
import com.fixcode.intellij.lua.debug.engine.DebugEvent as EngineEvent
import com.fixcode.intellij.lua.debug.engine.PauseInfo as EnginePause

/**
 * 把一个项目的新引擎会话包装成 MCP 工具层要的 [LuaDebugBackend]。
 *
 * - 会话来自 [LuaDebugSessionManager]，和 IDE 调试窗口是同一批，Agent 和用户看到同一份状态。
 * - Agent 断点是项目级的：没有会话时也能下，之后开始的会话会自动带上；每个会话里引擎给的 id 不同，
 *   这里用自己的 `agent-N` 作对外 id，再按会话映射到引擎 id。
 * - IDE 断点以编辑器里的为准（XBreakpointManager），对外 id 是 `ide:<路径>:<行>`。
 * - frameId / ref 编码了会话、VM、暂停和连接代次，过期时报 STALE_REFERENCE。
 */
@Service(Service.Level.PROJECT)
class LuaEngineMcpBackend(private val project: Project) : LuaDebugBackend, Disposable {
    private class AgentBreakpoint(val id: String, @Volatile var request: BreakpointRequest)

    private val lock = Any()
    private val agentBreakpoints = LinkedHashMap<String, AgentBreakpoint>()
    /** sessionId → (agent 断点 id → 引擎断点 id) */
    private val engineIds = HashMap<String, HashMap<String, CompletableFuture<String>>>()
    private var nextAgentId = 1

    init {
        val manager = LuaDebugSessionManager.getInstance(project)
        manager.addListener(object : LuaDebugSessionManager.Listener {
            override fun sessionAdded(entry: LuaDebugSessionManager.Entry) = pushAgentBreakpoints(entry.engine)
            override fun sessionRemoved(entry: LuaDebugSessionManager.Entry) {
                synchronized(lock) { engineIds.remove(entry.sessionId) }
            }
        }, this)
        manager.sessions().forEach { pushAgentBreakpoints(it.engine) }
    }

    override fun dispose() = Unit

    private fun entries() = LuaDebugSessionManager.getInstance(project).sessions()

    private fun entry(sessionId: String) = LuaDebugSessionManager.getInstance(project).find(sessionId)
        ?: throw LuaDebugBackendException(ErrorCode.NO_SESSION, "没有调试会话 $sessionId（可能已结束）：先 lua_debug_session action=status 看现有会话。")

    private fun engine(sessionId: String): DebugSession = entry(sessionId).engine

    // =====================================================================
    // 会话和目标
    // =====================================================================

    override fun listSessions(): List<SessionInfo> = entries().map { entry ->
        val engine = entry.engine
        val pauses = engine.pauses()
        SessionInfo(
            sessionId = entry.sessionId,
            kind = when (entry.target) {
                is LuaDebugTarget.LocalProcess -> SessionKind.ATTACH
                is LuaDebugTarget.AndroidDevice -> SessionKind.DEVICE
                is LuaDebugTarget.Address -> SessionKind.ADDRESS
            },
            target = entry.target.displayName,
            state = when (engine.state) {
                EngineState.CREATED, EngineState.CONNECTING, EngineState.CONFIGURING -> SessionState.CONNECTING
                EngineState.RUNNING -> if (pauses.isEmpty()) SessionState.RUNNING else SessionState.PAUSED
                else -> SessionState.DISCONNECTED
            },
            vmCount = engine.vms().size,
            pausedVmIds = pauses.map { it.vmId.toString() },
            luaVersion = engine.welcome?.lua?.displayName
        )
    }

    override fun listTargets(kind: TargetKind?): CompletableFuture<List<TargetInfo>> = background {
        val targets = ArrayList<TargetInfo>()
        if (kind == null || kind == TargetKind.DEVICE) {
            val devices = LuaDeviceService.getInstance()
            if (devices != null) {
                val list = try {
                    devices.listDevices()
                } catch (e: Exception) {
                    if (kind == TargetKind.DEVICE) throw LuaDebugBackendException(ErrorCode.TARGET_NOT_FOUND, e.message ?: "读取设备列表失败")
                    emptyList()
                }
                for (device in list) {
                    if (device.state != LuaDeviceState.ONLINE) {
                        targets += TargetInfo(TargetKind.DEVICE, device.serial, device.model, detail = "设备状态 ${device.state}，需要先在手机上允许 USB 调试")
                        continue
                    }
                    val processes = runCatching { devices.listProcesses(device.serial, onlyDebuggable = true) }.getOrDefault(emptyList())
                    processes.forEach { p ->
                        targets += TargetInfo(
                            TargetKind.DEVICE, device.serial, device.model,
                            detail = p.tags.joinToString().ifEmpty { null },
                            packageName = p.packageName, pid = p.pid
                        )
                    }
                }
            }
        }
        // 本机进程：判断是否加载了 Lua 要读模块列表，代价大且容易误判，这里不列；Agent 按 pid 或进程名附加
        targets
    }

    override fun attachProcess(request: AttachProcessRequest): CompletableFuture<String> {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) {
            return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "本机进程附加目前只支持 Windows；真机用设备目标附加。"))
        }
        val handle = request.pid?.let { ProcessHandle.of(it.toLong()).orElse(null) }
            ?: request.processName?.let { name -> findProcess(name) }
            ?: return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.TARGET_NOT_FOUND,
                "找不到进程 ${request.pid ?: request.processName}：确认游戏在运行，或给出 pid。"))
        val name = handle.info().command().map { File(it).name }.orElse(request.processName ?: "pid ${handle.pid()}")
        return startSession(LuaDebugTarget.LocalProcess(handle.pid().toInt(), name, handle.info().command().orElse(null), request.captureOutput))
    }

    /** 按可执行文件名找进程（不区分大小写、可省略 .exe）；多个同名时要 Agent 给 pid。 */
    private fun findProcess(name: String): ProcessHandle? {
        val wanted = name.lowercase().removeSuffix(".exe")
        val matches = ProcessHandle.allProcesses().filter { p ->
            p.info().command().map { File(it).name.lowercase().removeSuffix(".exe") == wanted }.orElse(false)
        }.toList()
        if (matches.size > 1) {
            throw LuaDebugBackendException(ErrorCode.INVALID_ARGUMENT, "有 ${matches.size} 个 $name 进程（pid ${matches.joinToString { it.pid().toString() }}），请用 pid 指定。")
        }
        return matches.firstOrNull()
    }

    override fun attachDevice(request: AttachDeviceRequest): CompletableFuture<String> {
        val packageName = request.packageName
            ?: return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.INVALID_ARGUMENT, "真机附加要给出包名：先 lua_debug_session action=targets 列出设备上的应用。"))
        return startSession(LuaDebugTarget.AndroidDevice(request.deviceId, packageName, request.pid, request.launchAndAttach))
    }

    /** 和用户在 IDE 里附加走同一个入口，所以 Agent 开的会话也有调试窗口。握手完成后才返回会话 id。 */
    private fun startSession(target: LuaDebugTarget): CompletableFuture<String> {
        val started = CompletableFuture<DebugSession>()
        ApplicationManager.getApplication().invokeLater({
            try {
                val session = LuaDebugSessionStarter.startWithoutConfiguration(project, target)
                val process = session.debugProcess as? LuaV3DebugProcess
                    ?: throw IllegalStateException("调试会话不是新引擎创建的")
                started.complete(process.engine)
            } catch (e: Throwable) {
                started.completeExceptionally(LuaDebugBackendException(ErrorCode.ATTACH_FAILED, e.message ?: e.toString()))
            }
        }, ModalityState.any())
        return started.thenCompose { engine ->
            // 注入、建转发可能要十几秒
            val waiting = engine.awaitEvent(ATTACH_TIMEOUT_MS) { it is EngineEvent.Connected || it is EngineEvent.Terminated }
            // 订阅之前可能已经连上或失败了
            when {
                engine.state == EngineState.RUNNING -> waiting.complete(null)
                engine.state.isTerminal -> waiting.complete(EngineEvent.Terminated("连接失败"))
            }
            waiting.thenApply { event ->
                when {
                    engine.state == EngineState.RUNNING || event is EngineEvent.Connected -> engine.id
                    event is EngineEvent.Terminated -> throw LuaDebugBackendException(ErrorCode.ATTACH_FAILED, "附加失败：${event.reason}（详细步骤见 IDE 调试控制台）")
                    else -> throw LuaDebugBackendException(ErrorCode.TIMEOUT, "附加超时：${ATTACH_TIMEOUT_MS / 1000} 秒内没有连上，详细步骤见 IDE 调试控制台。")
                }
            }
        }
    }

    override fun stopSession(sessionId: String): CompletableFuture<Unit> {
        val entry = entry(sessionId)
        val done = CompletableFuture<Unit>()
        ApplicationManager.getApplication().invokeLater({
            entry.xSession.stop()
            done.complete(Unit)
        }, ModalityState.any())
        return done
    }

    override fun listVms(sessionId: String): List<VmInfo> = engine(sessionId).vms().map { vm ->
        VmInfo(
            vm.vmId.toString(), vm.name,
            if (vm.paused) VmState.PAUSED else if (vm.state == "closing") VmState.CLOSED else VmState.RUNNING,
            vm.sourceEpoch
        )
    }

    override fun capabilities(sessionId: String): Set<String> = engine(sessionId).welcome?.caps?.toSet() ?: emptySet()

    // =====================================================================
    // 断点
    // =====================================================================

    override fun setBreakpoint(spec: BreakpointSpec): CompletableFuture<BreakpointInfo> {
        val path = resolveFile(spec.file)
        if (spec.line < 1) throw LuaDebugBackendException(ErrorCode.INVALID_ARGUMENT, "行号从 1 开始。")
        val request = BreakpointRequest(
            file = path, line = spec.line,
            condition = spec.condition, hitCondition = spec.hitCondition, logMessage = spec.logMessage,
            capture = spec.capture, autoContinue = spec.autoContinue,
            vmId = spec.vmId?.let(::parseVm) ?: 0,
            origin = EngineOrigin.AGENT
        )
        val breakpoint = synchronized(lock) {
            val existing = agentBreakpoints.values.firstOrNull { SourcePaths.normalize(it.request.file) == path && it.request.line == spec.line }
            if (existing != null) {
                existing.request = request
                existing
            } else {
                AgentBreakpoint("agent-${nextAgentId++}", request).also { agentBreakpoints[it.id] = it }
            }
        }
        val syncs = entries().map { applyToEngine(it.engine, breakpoint) }
        return settle(syncs).thenApply { agentInfo(breakpoint) }
    }

    override fun removeBreakpoints(filter: BreakpointFilter): CompletableFuture<Int> {
        val agents = matchAgent(filter)
        synchronized(lock) { agents.forEach { agentBreakpoints.remove(it.id) } }
        val engineRemovals = entries().flatMap { entry ->
            agents.mapNotNull { bp -> engineIdFuture(entry.sessionId, bp.id, remove = true)?.thenCompose { entry.engine.removeBreakpoint(it) } }
        }
        val ide = matchIde(filter)
        if (ide.isNotEmpty()) onEdt {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            ide.forEach { manager.removeBreakpoint(it) }
        }
        return settle(engineRemovals).thenApply { agents.size + ide.size }
    }

    override fun listBreakpoints(origin: BreakpointOrigin?): List<BreakpointInfo> {
        val result = ArrayList<BreakpointInfo>()
        if (origin == null || origin == BreakpointOrigin.IDE) result += ideBreakpoints().map(::ideInfo)
        if (origin == null || origin == BreakpointOrigin.AGENT) result += synchronized(lock) { agentBreakpoints.values.toList() }.map(::agentInfo)
        return result
    }

    override fun enableBreakpoints(filter: BreakpointFilter, enabled: Boolean): CompletableFuture<Int> {
        val agents = matchAgent(filter).filter { it.request.enabled != enabled }
        agents.forEach { it.request = it.request.copy(enabled = enabled) }
        val syncs = entries().flatMap { entry -> agents.map { applyToEngine(entry.engine, it) } }
        val ide = matchIde(filter).filter { it.isEnabled != enabled }
        if (ide.isNotEmpty()) onEdt { ide.forEach { it.isEnabled = enabled } }
        return settle(syncs).thenApply { agents.size + ide.size }
    }

    /** 把一个 Agent 断点加到 / 更新到会话里，完成时 runtime 已确认（或连接还没建好）。 */
    private fun applyToEngine(engine: DebugSession, breakpoint: AgentBreakpoint): CompletableFuture<Unit> {
        val future = synchronized(lock) {
            val ids = engineIds.getOrPut(engine.id) { HashMap() }
            val existing = ids[breakpoint.id]
            if (existing == null) {
                engine.addBreakpoint(breakpoint.request).also { ids[breakpoint.id] = it }.thenApply { }
            } else {
                existing.thenCompose { engine.updateBreakpoint(it, breakpoint.request) }.thenApply { }
            }
        }
        return future.thenCompose { engine.syncBreakpoints() }
    }

    private fun pushAgentBreakpoints(engine: DebugSession) {
        synchronized(lock) { agentBreakpoints.values.toList() }.forEach { applyToEngine(engine, it) }
    }

    private fun engineIdFuture(sessionId: String, agentId: String, remove: Boolean = false): CompletableFuture<String>? = synchronized(lock) {
        val ids = engineIds[sessionId] ?: return null
        if (remove) ids.remove(agentId) else ids[agentId]
    }

    private fun matchAgent(filter: BreakpointFilter): List<AgentBreakpoint> {
        val ids = (filter.breakpointIds + listOfNotNull(filter.breakpointId)).toSet()
        val file = filter.file?.let(::resolveFile)
        return synchronized(lock) { agentBreakpoints.values.toList() }.filter { bp ->
            when {
                ids.isNotEmpty() -> bp.id in ids
                file != null -> SourcePaths.normalize(bp.request.file) == file && (filter.line == null || filter.line == bp.request.line)
                else -> filter.all
            }
        }
    }

    private fun matchIde(filter: BreakpointFilter): List<XLineBreakpoint<*>> {
        val ids = (filter.breakpointIds + listOfNotNull(filter.breakpointId)).toSet()
        val file = filter.file?.let(::resolveFile)
        return ideBreakpoints().filter { bp ->
            when {
                ids.isNotEmpty() -> ideId(bp) in ids
                // 按文件 / 全部批量操作时，只有明确要求才动用户的断点
                !filter.includeIde -> false
                file != null -> pathOf(bp) == file && (filter.line == null || filter.line == bp.line + 1)
                else -> filter.all
            }
        }
    }

    private fun ideBreakpoints(): List<XLineBreakpoint<*>> = ReadAction.compute<List<XLineBreakpoint<*>>, RuntimeException> {
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { it.type is LuaLineBreakpointType }
    }

    private fun pathOf(bp: XLineBreakpoint<*>): String =
        SourcePaths.normalize(VirtualFileManager.getInstance().findFileByUrl(bp.fileUrl)?.path ?: bp.fileUrl.removePrefix("file://"))

    private fun ideId(bp: XLineBreakpoint<*>) = "ide:${pathOf(bp)}:${bp.line + 1}"

    private fun ideInfo(bp: XLineBreakpoint<*>): BreakpointInfo {
        val path = pathOf(bp)
        val views = entries().flatMap { it.engine.breakpoints() }
            .filter { it.request.origin == EngineOrigin.IDE && SourcePaths.normalize(it.request.file) == path && it.request.line == bp.line + 1 }
        return BreakpointInfo(
            breakpointId = ideId(bp), origin = BreakpointOrigin.IDE, file = path, line = bp.line + 1,
            verified = views.any { it.verification == BreakpointVerification.VERIFIED },
            condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() },
            logMessage = bp.logExpressionObject?.expression?.takeIf { it.isNotBlank() },
            enabled = bp.isEnabled,
            message = statusMessage(views)
        )
    }

    private fun agentInfo(bp: AgentBreakpoint): BreakpointInfo {
        val request = bp.request
        val views = entries().mapNotNull { entry ->
            val id = engineIdFuture(entry.sessionId, bp.id)?.getNow(null) ?: return@mapNotNull null
            entry.engine.breakpoints().firstOrNull { it.id == id }
        }
        return BreakpointInfo(
            breakpointId = bp.id, origin = BreakpointOrigin.AGENT, file = request.file, line = request.line,
            verified = views.any { it.verification == BreakpointVerification.VERIFIED },
            condition = request.condition, hitCondition = request.hitCondition, logMessage = request.logMessage,
            capture = request.capture, autoContinue = request.autoContinue, enabled = request.enabled,
            message = if (views.isEmpty() && request.enabled) "还没有调试会话，开始调试后自动下发" else statusMessage(views)
        )
    }

    private fun statusMessage(views: List<BreakpointView>): String? {
        if (views.isEmpty() || views.any { it.verification == BreakpointVerification.VERIFIED }) return null
        return views.firstNotNullOfOrNull { it.message } ?: when (views.first().verification) {
            BreakpointVerification.PENDING -> "chunk 还没加载，加载后自动生效"
            else -> null
        }
    }

    // =====================================================================
    // 执行控制
    // =====================================================================

    override fun resume(sessionId: String, vmId: String?) = mapErrors(engine(sessionId).resume(vmId?.let(::parseVm), Initiator.AGENT))

    override fun pause(sessionId: String, vmId: String?) = mapErrors(engine(sessionId).pause(vmId?.let(::parseVm)))

    override fun step(sessionId: String, vmId: String?, kind: StepKind): CompletableFuture<Unit> {
        val engine = engine(sessionId)
        val vm = vmId?.let(::parseVm)
        return mapErrors(
            when (kind) {
                StepKind.OVER -> engine.stepOver(vm, Initiator.AGENT)
                StepKind.INTO -> engine.stepInto(vm, Initiator.AGENT)
                StepKind.OUT -> engine.stepOut(vm, Initiator.AGENT)
            }
        )
    }

    override fun runToLine(sessionId: String, vmId: String?, file: String, line: Int) =
        mapErrors(engine(sessionId).runToLine(resolveFile(file), line, vmId?.let(::parseVm), Initiator.AGENT))

    override fun currentPauses(): List<PauseInfo> = entries().flatMap { entry ->
        val engine = entry.engine
        engine.pauses().map { pauseInfo(entry.sessionId, engine, it) }
    }

    private fun pauseInfo(sessionId: String, engine: DebugSession, pause: EnginePause): PauseInfo {
        val vmName = engine.vms().firstOrNull { it.vmId == pause.vmId }?.name ?: "Lua VM #${pause.vmId}"
        val top = engine.topFrame(pause)?.let { frameInfo(sessionId, it) }
        return PauseInfo(
            sessionId = sessionId,
            vmId = pause.vmId.toString(),
            vmName = vmName,
            pauseId = pause.pauseId.toString(),
            reason = when (pause.reason) {
                PauseReasons.BREAKPOINT -> PauseReason.BREAKPOINT
                PauseReasons.STEP -> PauseReason.STEP
                PauseReasons.PAUSE, PauseReasons.HOST_BREAK -> PauseReason.PAUSE
                PauseReasons.EXCEPTION -> PauseReason.ERROR
                PauseReasons.ENTRY -> PauseReason.ENTRY
                PauseReasons.RUN_TO_LINE -> PauseReason.RUN_TO_LINE
                else -> PauseReason.OTHER
            },
            breakpointId = pause.breakpointIds.firstOrNull()?.let { publicBreakpointId(sessionId, engine, it) },
            errorMessage = pause.exception?.message,
            topFrame = top
        )
    }

    /** 引擎断点 id → 对外 id（Agent 断点用 agent-N，IDE 断点用 ide:路径:行）。 */
    private fun publicBreakpointId(sessionId: String, engine: DebugSession, engineId: String): String {
        synchronized(lock) {
            engineIds[sessionId]?.entries?.firstOrNull { it.value.getNow(null) == engineId }?.let { return it.key }
        }
        val view = engine.breakpoints().firstOrNull { it.id == engineId } ?: return engineId
        return if (view.request.origin == EngineOrigin.IDE) "ide:${SourcePaths.normalize(view.request.file)}:${view.request.line}" else engineId
    }

    override fun addListener(listener: (DebugEvent) -> Unit): AutoCloseable {
        val disposable = Disposer.newDisposable("lua-mcp-listener")
        Disposer.register(this, disposable)
        val subscriptions = HashMap<String, AutoCloseable>()
        fun subscribe(entry: LuaDebugSessionManager.Entry) = synchronized(subscriptions) {
            if (entry.sessionId in subscriptions) return@synchronized
            val engine = entry.engine
            subscriptions[entry.sessionId] = engine.events.subscribe { event -> translate(entry.sessionId, engine, event)?.let(listener) }
        }
        LuaDebugSessionManager.getInstance(project).addListener(object : LuaDebugSessionManager.Listener {
            override fun sessionAdded(entry: LuaDebugSessionManager.Entry) {
                subscribe(entry)
                listener(DebugEvent.SessionStarted(entry.sessionId))
            }

            override fun sessionRemoved(entry: LuaDebugSessionManager.Entry) {
                synchronized(subscriptions) { subscriptions.remove(entry.sessionId) }?.close()
            }
        }, disposable)
        entries().forEach(::subscribe)
        return AutoCloseable {
            Disposer.dispose(disposable)
            synchronized(subscriptions) { subscriptions.values.toList().also { subscriptions.clear() } }.forEach { it.close() }
        }
    }

    private fun translate(sessionId: String, engine: DebugSession, event: EngineEvent): DebugEvent? = when (event) {
        is EngineEvent.Paused -> DebugEvent.Paused(pauseInfo(sessionId, engine, event.pause))
        is EngineEvent.Resumed -> DebugEvent.Resumed(sessionId, event.vmId.toString(), byUser = event.initiator == Initiator.USER)
        is EngineEvent.BreakpointHit -> DebugEvent.ProbeHitEvent(probeHit(sessionId, engine, event.seq, System.currentTimeMillis(), event.hit, event.localPath))
        is EngineEvent.BreakpointsChanged -> DebugEvent.BreakpointsChanged(sessionId)
        is EngineEvent.Terminated -> DebugEvent.SessionEnded(sessionId, event.reason)
        else -> null
    }

    // =====================================================================
    // 查看
    // =====================================================================

    override fun getStack(sessionId: String, vmId: String?, start: Int, count: Int): CompletableFuture<StackPage> {
        val engine = engine(sessionId)
        val pause = pauseFor(engine, vmId)
        return mapErrors(engine.stackTrace(pause, start, count)).thenApply { page ->
            StackPage(pause.pauseId.toString(), page.frames.map { frameInfo(sessionId, it) }, page.total)
        }
    }

    override fun getVariables(request: VariablesRequest): CompletableFuture<VariablesPage> {
        val limit = request.limit.coerceIn(1, 200)
        val depth = request.depth.coerceIn(1, 4)
        request.ref?.let { ref ->
            val (engine, container) = parseRef(ref)
            return mapErrors(expand(engine, container, depth, limit))
        }
        val frame = request.frameId?.let(::parseFrame) ?: defaultFrame()
        val (sessionId, engine, framePromise) = frame
        val future = framePromise.thenCompose { handle ->
            if (request.path != null) {
                engine.evaluate(handle.pause, request.path!!, handle, EvalModes.VALUE_PATH, "agent").thenCompose { value ->
                    val variable = VariableInfo(request.path!!, value.type, value.value, value.children()?.let { refOf(sessionId, it) })
                    val children = value.children()
                    if (children == null || depth <= 1) {
                        CompletableFuture.completedFuture(VariablesPage(listOf(variable), 1))
                    } else {
                        expand(engine, children, depth - 1, limit).thenApply { page ->
                            VariablesPage(listOf(variable.copy(children = page.variables, childCount = page.total)), 1)
                        }
                    }
                }
            } else {
                val wanted = when (request.scope ?: VariableScope.LOCALS) {
                    VariableScope.LOCALS -> ScopeNames.LOCALS
                    VariableScope.UPVALUES -> ScopeNames.UPVALUES
                    VariableScope.GLOBALS -> ScopeNames.GLOBALS
                }
                engine.scopes(handle).thenCompose { scopes ->
                    val scope = scopes.firstOrNull { it.name == wanted }
                    if (scope == null) CompletableFuture.completedFuture(VariablesPage(emptyList(), 0))
                    else expand(engine, scope, depth, limit)
                }
            }
        }
        return mapErrors(future)
    }

    /** 取一层子项；[depth] > 1 时继续展开可展开的子项（每层同样的条数上限）。 */
    private fun expand(engine: DebugSession, container: VariableContainer, depth: Int, limit: Int): CompletableFuture<VariablesPage> =
        engine.variables(container, 0, limit).thenCompose { page ->
            val items = page.variables.map { handle ->
                val info = variableInfo(engine.id, handle)
                val children = handle.children()
                if (depth <= 1 || children == null) CompletableFuture.completedFuture(info)
                else expand(engine, children, depth - 1, limit).thenApply { info.copy(children = it.variables) }
            }
            CompletableFuture.allOf(*items.toTypedArray()).thenApply { VariablesPage(items.map { it.join() }, page.total) }
        }

    private fun variableInfo(sessionId: String, handle: VariableHandle): VariableInfo {
        val v = handle.variable
        val count = if (v.namedCount == null && v.indexedCount == null) null else (v.namedCount ?: 0) + (v.indexedCount ?: 0)
        return VariableInfo(v.name, v.typeName ?: v.type, v.value, handle.children()?.let { refOf(sessionId, it) }, count)
    }

    override fun evaluate(request: EvaluateRequest): CompletableFuture<VariableInfo> {
        val (sessionId, engine, framePromise) = parseFrame(request.frameId)
        val mode = if (request.mode == EvalMode.LUA) EvalModes.LUA else EvalModes.VALUE_PATH
        return mapErrors(framePromise.thenCompose { handle ->
            engine.evaluate(handle.pause, request.expression, handle, mode, "agent").thenApply { value ->
                VariableInfo(request.expression, value.typeName ?: value.type, value.value, value.children()?.let { refOf(sessionId, it) })
            }
        })
    }

    override fun getOutput(sessionId: String?, sinceSeq: Long, limit: Int): OutputPage {
        val sessions = if (sessionId != null) listOf(entry(sessionId)) else entries()
        val entries = sessions.flatMap { entry ->
            entry.engine.output(sinceSeq, limit).map { item ->
                val out = item.value
                OutputEntry(
                    seq = item.seq, timestampMillis = item.timeMillis, sessionId = entry.sessionId,
                    category = when (out.category) {
                        OutputCategories.STDOUT, OutputCategories.HOST, OutputCategories.LOGPOINT -> OutputCategory.STDOUT
                        OutputCategories.STDERR -> OutputCategory.STDERR
                        else -> if (out.level == "error") OutputCategory.ERROR else OutputCategory.DEBUGGER
                    },
                    text = out.text, vmId = out.vmId?.toString()
                )
            }
        }.sortedBy { it.timestampMillis }.take(limit)
        return OutputPage(entries, entries.maxOfOrNull { it.seq } ?: sinceSeq)
    }

    override fun getProbeHits(breakpointId: String?, sinceSeq: Long, limit: Int): ProbeHitPage {
        val hits = entries().flatMap { entry ->
            val engine = entry.engine
            val engineId = breakpointId?.let { id -> if (id.startsWith("agent-")) engineIdFuture(entry.sessionId, id)?.getNow(null) ?: return@flatMap emptyList() else id }
            engine.breakpointHits(sinceSeq, Int.MAX_VALUE)
                .filter { engineId == null || it.value.id == engineId }
                .take(limit)
                .map { probeHit(entry.sessionId, engine, it.seq, it.timeMillis, it.value, null) }
        }.sortedBy { it.timestampMillis }.take(limit)
        return ProbeHitPage(hits, hits.maxOfOrNull { it.seq } ?: sinceSeq)
    }

    private fun probeHit(
        sessionId: String, engine: DebugSession, seq: Long, time: Long,
        hit: com.fixcode.intellij.lua.debug.protocol.BreakpointHitEvent, localPath: String?
    ) = ProbeHit(
        seq = seq, timestampMillis = time, sessionId = sessionId,
        breakpointId = publicBreakpointId(sessionId, engine, hit.id),
        vmId = hit.vmId.toString(),
        file = localPath ?: engine.breakpoints().firstOrNull { it.id == hit.id }?.request?.file,
        line = hit.line,
        values = hit.captures.associate { it.expression to (it.error?.let { e -> "<$e>" } ?: it.value) }
    )

    override fun sourceSnippet(file: String, line: Int, radius: Int): List<SourceLine>? {
        val f = File(file)
        if (!f.isFile) return null
        return try {
            val lines = f.readLines()
            val from = (line - radius).coerceAtLeast(1)
            val to = (line + radius).coerceAtMost(lines.size)
            (from..to).map { SourceLine(it, lines[it - 1]) }
        } catch (_: Exception) {
            null
        }
    }

    // =====================================================================
    // UE 热重载（经 UELink，不走调试连接）
    // =====================================================================

    override fun reloadModules(request: ReloadRequest): CompletableFuture<ReloadResult> =
        CompletableFuture.supplyAsync({
            val files = request.files.map { java.nio.file.Path.of(resolveFile(it)) }
            val outcome = try {
                UnrealLuaReloader.getInstance(project).reload(request.modules, files, request.changed)
            } catch (e: UnrealLuaReloader.ReloadException) {
                val code = when (e.reason) {
                    UnrealLuaReloader.Reason.NOT_SUPPORTED -> ErrorCode.NOT_SUPPORTED
                    UnrealLuaReloader.Reason.NO_UE_PROJECT, UnrealLuaReloader.Reason.NO_INSTANCE -> ErrorCode.TARGET_NOT_FOUND
                }
                throw LuaDebugBackendException(code, e.message.orEmpty())
            }
            ReloadResult(
                targets = outcome.targets.map { t ->
                    ReloadTargetResult(
                        pid = t.instance.pid,
                        kind = t.instance.kind,
                        items = t.items.map { ReloadItem(it.module, it.ok, it.mode, it.error) },
                        note = t.note,
                        error = t.error,
                    )
                },
                skipped = outcome.skipped,
            )
        }, AppExecutorUtil.getAppExecutorService())

    // =====================================================================
    // id 编解码
    // =====================================================================

    private fun frameInfo(sessionId: String, frame: FrameHandle) = FrameInfo(
        frameId = listOf(sessionId, frame.pause.vmId, frame.pause.pauseId, frame.pause.connectionEpoch, "f${frame.frame.level}").joinToString(SEP),
        index = frame.frame.level,
        function = frame.frame.name,
        file = frame.localPath,
        line = frame.frame.line,
        chunkName = frame.frame.chunkName
    )

    private fun refOf(sessionId: String, container: VariableContainer): String =
        listOf(sessionId, container.pause.vmId, container.pause.pauseId, container.pause.connectionEpoch, "r${container.ref}").joinToString(SEP)

    private data class FrameRef(val sessionId: String, val engine: DebugSession, val frame: CompletableFuture<FrameHandle>)

    private fun parseFrame(frameId: String): FrameRef {
        val (sessionId, engine, pause, tail) = parseIds(frameId, 'f')
        val level = tail.toIntOrNull() ?: throw invalidId(frameId)
        val frame = engine.stackTrace(pause, level, 1).thenApply { page ->
            page.frames.firstOrNull { it.frame.level == level } ?: throw StaleReferenceException()
        }
        return FrameRef(sessionId, engine, frame)
    }

    private fun parseRef(ref: String): Pair<DebugSession, VariableContainer> {
        val (_, engine, pause, tail) = parseIds(ref, 'r')
        val id = tail.toLongOrNull() ?: throw invalidId(ref)
        return engine to VariableContainer(pause, id, "")
    }

    private data class Ids(val sessionId: String, val engine: DebugSession, val pause: EnginePause, val tail: String)

    private fun parseIds(text: String, kind: Char): Ids {
        val parts = text.split(SEP)
        if (parts.size != 5 || !parts[4].startsWith(kind)) throw invalidId(text)
        val engine = LuaDebugSessionManager.getInstance(project).find(parts[0])?.engine
            ?: throw LuaDebugBackendException(ErrorCode.NO_SESSION, "这个引用所属的调试会话已结束。")
        val vmId = parts[1].toLongOrNull() ?: throw invalidId(text)
        val pauseId = parts[2].toLongOrNull() ?: throw invalidId(text)
        val epoch = parts[3].toLongOrNull() ?: throw invalidId(text)
        val pause = engine.pauses().firstOrNull { it.vmId == vmId && it.pauseId == pauseId && it.connectionEpoch == epoch }
            ?: throw LuaDebugBackendException(ErrorCode.STALE_REFERENCE, StaleReferenceException.STALE_HINT)
        return Ids(parts[0], engine, pause, parts[4].substring(1))
    }

    private fun invalidId(text: String) =
        LuaDebugBackendException(ErrorCode.INVALID_ARGUMENT, "'$text' 不是有效的帧号或引用：用 lua_debug_inspect action=stack / locals 返回的值。")

    /** 没给 frameId：只有一个暂停时用它的栈顶帧。 */
    private fun defaultFrame(): FrameRef {
        val paused = entries().flatMap { entry -> entry.engine.pauses().map { entry to it } }
        val (entry, pause) = when {
            paused.isEmpty() -> throw LuaDebugBackendException(ErrorCode.NOT_PAUSED, "没有暂停的 VM：先 lua_debug_session action=wait 等断点命中，或 action=pause。")
            paused.size == 1 -> paused[0]
            else -> entries().firstNotNullOfOrNull { e -> e.engine.focusedPause()?.let { e to it } } ?: paused[0]
        }
        val top = entry.engine.topFrame(pause)
            ?: throw LuaDebugBackendException(ErrorCode.NOT_PAUSED, "暂停的位置没有 Lua 栈帧。")
        return FrameRef(entry.sessionId, entry.engine, CompletableFuture.completedFuture(top))
    }

    private fun pauseFor(engine: DebugSession, vmId: String?): EnginePause {
        val pauses = engine.pauses()
        if (vmId != null) {
            val id = parseVm(vmId)
            return pauses.firstOrNull { it.vmId == id }
                ?: throw LuaDebugBackendException(ErrorCode.NOT_PAUSED, "VM $vmId 没有暂停：先 lua_debug_session action=wait 或 action=pause。")
        }
        return engine.focusedPause() ?: pauses.firstOrNull()
            ?: throw LuaDebugBackendException(ErrorCode.NOT_PAUSED, "没有暂停的 VM：先 lua_debug_session action=wait 等断点命中，或 action=pause。")
    }

    private fun parseVm(vmId: String): Long = vmId.toLongOrNull()
        ?: throw LuaDebugBackendException(ErrorCode.NO_VM, "VM '$vmId' 不存在：用 lua_debug_inspect action=vms 查看。")

    // =====================================================================
    // 工具函数
    // =====================================================================

    /** 相对路径按项目根解析。 */
    private fun resolveFile(file: String): String {
        val path = SourcePaths.normalize(file)
        if (SourcePaths.isAbsolute(path)) return path
        val base = project.basePath ?: return path
        return SourcePaths.normalize(File(base, file).path)
    }

    /** 引擎错误换成 MCP 的错误码，说明原样保留（引擎的说明已经写了怎么办）。 */
    private fun <T> mapErrors(future: CompletableFuture<T>): CompletableFuture<T> = future.handle { value, error ->
        if (error == null) return@handle value
        throw CompletionException(translateError(DebugSession.unwrap(error)))
    }

    private fun translateError(error: Throwable): Throwable = when (error) {
        is LuaDebugBackendException -> error
        is StaleReferenceException -> LuaDebugBackendException(ErrorCode.STALE_REFERENCE, error.message ?: StaleReferenceException.STALE_HINT)
        is DebugEngineException -> LuaDebugBackendException(
            when (error.code) {
                ErrorCodes.STALE_REFERENCE, ErrorCodes.STALE_PAUSE -> ErrorCode.STALE_REFERENCE
                ErrorCodes.NOT_PAUSED -> ErrorCode.NOT_PAUSED
                ErrorCodes.VM_NOT_FOUND -> ErrorCode.NO_VM
                ErrorCodes.UNSUPPORTED -> ErrorCode.NOT_SUPPORTED
                ErrorCodes.EVAL_FORBIDDEN -> ErrorCode.EVAL_FORBIDDEN
                ErrorCodes.INVALID_ARGS, ErrorCodes.EVAL_ERROR -> ErrorCode.INVALID_ARGUMENT
                ErrorCodes.TIMEOUT -> ErrorCode.TIMEOUT
                ErrorCodes.DISCONNECTED -> ErrorCode.NO_SESSION
                else -> ErrorCode.INTERNAL
            },
            error.message ?: error.code
        )
        else -> LuaDebugBackendException(ErrorCode.INTERNAL, error.message ?: error.toString())
    }

    /** 等一批断点同步，最多 [SYNC_WAIT_MS]：没连上的会话不该让 Agent 一直等。 */
    private fun settle(futures: List<CompletableFuture<*>>): CompletableFuture<Unit> {
        if (futures.isEmpty()) return CompletableFuture.completedFuture(Unit)
        return CompletableFuture.allOf(*futures.toTypedArray())
            .completeOnTimeout(null, SYNC_WAIT_MS, TimeUnit.MILLISECONDS)
            .handle { _, _ -> }
    }

    private fun <T> background(action: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ action() }, AppExecutorUtil.getAppExecutorService())

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) action()
    }, ModalityState.any())

    companion object {
        private const val SEP = "|"
        private const val ATTACH_TIMEOUT_MS = 60_000L
        private const val SYNC_WAIT_MS = 2_000L

        fun getInstance(project: Project): LuaEngineMcpBackend = project.service()
    }
}
