package com.fixcode.intellij.lua.debugger.mcp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 内存里的假引擎：单测 MCP 工具层用，不需要原生 runtime。 */
class FakeBackend : LuaDebugBackend {
    val sessions = CopyOnWriteArrayList<SessionInfo>()
    val pauses = CopyOnWriteArrayList<PauseInfo>()
    val breakpoints = CopyOnWriteArrayList<BreakpointInfo>()
    val commands = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(DebugEvent) -> Unit>()
    private val bpIds = AtomicInteger()
    private val pauseIds = AtomicInteger()

    /** step/pause/runToLine 后自动产生的下一次暂停的行号；null = 不停。 */
    @Volatile
    var nextPauseLine: Int? = 11

    var stalePauseId: String? = null

    fun addSession(id: String = "s1", target: String = "game.exe (100)"): SessionInfo {
        val s = SessionInfo(id, SessionKind.ATTACH, target, SessionState.RUNNING, vmCount = 1)
        sessions += s
        return s
    }

    fun firePause(sessionId: String = "s1", line: Int = 10, reason: PauseReason = PauseReason.BREAKPOINT): PauseInfo {
        val id = "p" + pauseIds.incrementAndGet()
        val p = PauseInfo(
            sessionId, "vm-1", "GameLua", id, reason, breakpointId = "1",
            topFrame = FrameInfo("$id:0", 0, "Bag:OnClick", "/proj/Bag.lua", line, "Bag"),
        )
        pauses.removeIf { it.sessionId == sessionId }
        pauses += p
        listeners.forEach { it(DebugEvent.Paused(p)) }
        return p
    }

    fun fire(event: DebugEvent) = listeners.forEach { it(event) }

    override fun listSessions(): List<SessionInfo> = sessions.map { s ->
        s.copy(pausedVmIds = pauses.filter { it.sessionId == s.sessionId }.map { it.vmId })
    }

    override fun listTargets(kind: TargetKind?) =
        CompletableFuture.completedFuture(listOf(TargetInfo(TargetKind.PROCESS, "100", "game.exe", "Lua 5.4")))

    override fun attachProcess(request: AttachProcessRequest): CompletableFuture<String> {
        commands += "attach ${request.pid}"
        addSession("s-new", "game.exe (${request.pid})")
        return CompletableFuture.completedFuture("s-new")
    }

    override fun attachDevice(request: AttachDeviceRequest): CompletableFuture<String> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.ATTACH_FAILED, "设备未连接"))

    override fun stopSession(sessionId: String): CompletableFuture<Unit> {
        sessions.removeIf { it.sessionId == sessionId }
        return CompletableFuture.completedFuture(Unit)
    }

    val runConfigs = CopyOnWriteArrayList<RunConfigInfo>()

    override fun listRunConfigurations(): List<RunConfigInfo> = runConfigs.toList()

    override fun launchConfiguration(request: LaunchRequest): CompletableFuture<LaunchResult> {
        commands += "launch ${request.name} debug=${request.debug}"
        val c = runConfigs.first { it.name == request.name }
        if (!c.lua) return CompletableFuture.completedFuture(LaunchResult(c.name, executor = if (request.debug) "debug" else "run"))
        addSession("s-launch", "${c.name} (200)")
        return CompletableFuture.completedFuture(LaunchResult(c.name, "s-launch"))
    }

    override fun restartSession(sessionId: String): CompletableFuture<String> {
        commands += "restart $sessionId"
        val old = sessions.first { it.sessionId == sessionId }
        sessions.remove(old)
        addSession("$sessionId-r", old.target)
        return CompletableFuture.completedFuture("$sessionId-r")
    }

    override fun listVms(sessionId: String) = listOf(VmInfo("vm-1", "GameLua", VmState.RUNNING, 1))

    override fun capabilities(sessionId: String) = setOf("valuePath", "luaEval")

    override fun setBreakpoint(spec: BreakpointSpec): CompletableFuture<BreakpointInfo> {
        val bp = BreakpointInfo(
            (bpIds.incrementAndGet()).toString(), BreakpointOrigin.AGENT, spec.file, spec.line, verified = sessions.isNotEmpty(),
            condition = spec.condition, capture = spec.capture, autoContinue = spec.autoContinue, function = spec.function,
        )
        breakpoints += bp
        return CompletableFuture.completedFuture(bp)
    }

    override fun removeBreakpoints(filter: BreakpointFilter): CompletableFuture<Int> {
        val before = breakpoints.size
        breakpoints.removeIf { b ->
            (filter.all && (filter.includeIde || b.origin == BreakpointOrigin.AGENT)) ||
                b.breakpointId == filter.breakpointId || b.breakpointId in filter.breakpointIds ||
                (filter.file == b.file && (filter.line == null || filter.line == b.line)) ||
                (filter.function != null && filter.function == b.function)
        }
        return CompletableFuture.completedFuture(before - breakpoints.size)
    }

    override fun enableBreakpoints(filter: BreakpointFilter, enabled: Boolean): CompletableFuture<Int> {
        var n = 0
        breakpoints.replaceAll { b ->
            if (b.breakpointId == filter.breakpointId || b.breakpointId in filter.breakpointIds) { n++; b.copy(enabled = enabled) } else b
        }
        return CompletableFuture.completedFuture(n)
    }

    override fun listBreakpoints(origin: BreakpointOrigin?) = breakpoints.filter { origin == null || it.origin == origin }

    override fun resume(sessionId: String, vmId: String?): CompletableFuture<Unit> {
        commands += "resume"
        pauses.removeIf { it.sessionId == sessionId }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun pause(sessionId: String, vmId: String?) = control("pause", sessionId)

    override fun step(sessionId: String, vmId: String?, kind: StepKind) = control("step ${kind.name.lowercase()}", sessionId)

    override fun runToLine(sessionId: String, vmId: String?, file: String, line: Int) = control("runToLine $file:$line", sessionId)

    private fun control(cmd: String, sessionId: String): CompletableFuture<Unit> {
        commands += cmd
        pauses.removeIf { it.sessionId == sessionId }
        val line = nextPauseLine
        if (line != null) {
            // 模拟 runtime 异步回 paused 事件
            Thread { Thread.sleep(30); firePause(sessionId, line, PauseReason.STEP) }.start()
        }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun currentPauses(): List<PauseInfo> = pauses.toList()

    override fun addListener(listener: (DebugEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun getStack(sessionId: String, vmId: String?, start: Int, count: Int): CompletableFuture<StackPage> {
        val p = pauses.firstOrNull { it.sessionId == sessionId }
            ?: return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_PAUSED, "VM 没有暂停。"))
        val top = p.topFrame?.line ?: 10
        val frames = (0 until 3).map { FrameInfo("${p.pauseId}:$it", it, "f$it", "/proj/Bag.lua", top + it * 10) }
        return CompletableFuture.completedFuture(StackPage(p.pauseId, frames.drop(start).take(count), 3))
    }

    override fun getVariables(request: VariablesRequest): CompletableFuture<VariablesPage> {
        val pauseId = request.frameId?.substringBefore(':')
        if (pauseId != null && (pauseId == stalePauseId || pauses.none { it.pauseId == pauseId })) {
            return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.STALE_REFERENCE, "这个栈帧已失效。"))
        }
        return CompletableFuture.completedFuture(VariablesPage(listOf(
            VariableInfo("index", "number", "7"),
            VariableInfo("item", "table", "{id=1003, count=2}", ref = "r1", childCount = 2),
        ), 2))
    }

    override fun evaluate(request: EvaluateRequest): CompletableFuture<VariableInfo> {
        commands += "eval ${request.mode} ${request.expression}"
        return CompletableFuture.completedFuture(VariableInfo(request.expression, "number", "42"))
    }

    override fun setVariable(request: SetVariableRequest): CompletableFuture<VariableInfo> {
        commands += "set ${request.mode} ${request.path}=${request.value}" + (request.scope?.let { " ${it.name}" } ?: "")
        return CompletableFuture.completedFuture(VariableInfo(request.path, "number", request.value))
    }

    override fun getOutput(sessionId: String?, sinceSeq: Long, limit: Int) = OutputPage(
        listOf(OutputEntry(1, 0, "s1", OutputCategory.STDOUT, "hello")).filter { it.seq > sinceSeq }, 1,
    )

    override fun getProbeHits(breakpointId: String?, sinceSeq: Long, limit: Int) = ProbeHitPage(emptyList(), sinceSeq)

    /** null = 模拟没有开着的 UE 实例。 */
    @Volatile
    var ueRunning: Boolean = true
    val reloads = CopyOnWriteArrayList<ReloadRequest>()

    override fun reloadModules(request: ReloadRequest): CompletableFuture<ReloadResult> {
        if (!ueRunning) {
            return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.TARGET_NOT_FOUND, "没找到开着的 UE 编辑器或游戏。"))
        }
        reloads += request
        val names = request.modules + request.files.map { it.substringAfterLast('/').removeSuffix(".lua") }
        val items = names.map { n ->
            if (n == "Broken") ReloadItem(n, false, error = "attempt to index a nil value") else ReloadItem(n, true, "require")
        }
        return CompletableFuture.completedFuture(ReloadResult(listOf(ReloadTargetResult(200, "editor", items, note = "require 兜底"))))
    }

    /** 采样：记录开始参数，stop 时按 sort / limit 返回预置的函数表。 */
    var profileFunctions: List<ProfileFunction> = listOf(
        ProfileFunction("Bag:Refresh", "/proj/Bag.lua", 20, 70, 90, 70.0, 90.0),
        ProfileFunction("tick", "/proj/Main.lua", 5, 10, 100, 10.0, 100.0),
        ProfileFunction("pairs", null, -1, 20, 20, 20.0, 20.0),
    )
    var profileSamples: Long = 100
    var profileStartedMs: Int? = null

    override fun startProfile(sessionId: String, vmId: String?, durationMs: Int): CompletableFuture<Unit> {
        commands += "profile start $durationMs"
        profileStartedMs = durationMs
        return CompletableFuture.completedFuture(Unit)
    }

    override fun stopProfile(sessionId: String, vmId: String?, sort: ProfileSort, limit: Int): CompletableFuture<ProfileReport> {
        commands += "profile stop"
        val sorted = profileFunctions.sortedByDescending { if (sort == ProfileSort.TOTAL) it.totalSamples else it.selfSamples }
        return CompletableFuture.completedFuture(
            ProfileReport(profileSamples, profileStartedMs?.toLong() ?: 0, sorted.take(limit), sorted.size, 1000, 0.4)
        )
    }

    /** 错误聚合：预置的全量。 */
    var errorItems: List<LuaErrorInfo> = listOf(
        LuaErrorInfo(2, "vm-1", "Bag.lua:20: attempt to index a nil value (field 'item')", "/proj/Bag.lua", 20, 300, 1000, 9000,
            traceback = "\t/proj/Bag.lua:20: in function 'Refresh'\n\t/proj/Main.lua:5: in function 'tick'\n"),
        LuaErrorInfo(1, "vm-1", "Main.lua:9: boom", "/proj/Main.lua", 9, 1, 500, 500, uncaught = true),
    )

    override fun getErrors(sessionId: String): CompletableFuture<ErrorReport> {
        commands += "errors"
        return CompletableFuture.completedFuture(ErrorReport(errorItems))
    }

    override fun sourceSnippet(file: String, line: Int, radius: Int) =
        (line - radius..line + radius).map { SourceLine(it, "line $it") }
}
