package com.fixcode.intellij.lua.debugger.mcp

import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * 一个 IDE 只开一个 MCP 端口，但可能同时打开多个项目，每个项目有自己的调试引擎。
 * 这里把它们合成一个 [LuaDebugBackend]：
 * - 会话、暂停、输出、Probe 记录：合并各项目的结果，按 sessionId 路由（sessionId 由引擎生成，全局唯一）；
 * - 断点：按文件属于哪个项目路由；相对路径或不属于任何项目时，只开了一个项目就用它，否则报错让 Agent 给绝对路径；
 * - 目标列表、附加：本机进程和设备与项目无关，用第一个（当前焦点）项目。
 *
 * [members] 每次调用时取（项目会开关），第一个是当前焦点项目。
 */
class CompositeLuaDebugBackend(private val members: () -> List<Member>) : LuaDebugBackend {
    class Member(val name: String, val roots: List<Path>, val backend: LuaDebugBackend)

    private fun all() = members()

    private fun primary(): LuaDebugBackend = all().firstOrNull()?.backend
        ?: throw LuaDebugBackendException(ErrorCode.NO_SESSION, "IDE 没有打开项目。")

    private fun bySession(sessionId: String): LuaDebugBackend =
        all().firstOrNull { m -> m.backend.listSessions().any { it.sessionId == sessionId } }?.backend
            ?: throw LuaDebugBackendException(ErrorCode.NO_SESSION, "没有调试会话 $sessionId（可能已结束）。")

    private fun byFile(file: String): LuaDebugBackend {
        val members = all()
        if (members.size == 1) return members[0].backend
        val path = runCatching { Path.of(file) }.getOrNull()
        if (path != null && path.isAbsolute) {
            val norm = path.normalize()
            members.firstOrNull { m -> m.roots.any { norm.startsWith(it) } }?.let { return it.backend }
        }
        if (members.isEmpty()) throw LuaDebugBackendException(ErrorCode.NO_SESSION, "IDE 没有打开项目。")
        throw LuaDebugBackendException(
            ErrorCode.INVALID_ARGUMENT,
            "打开了多个项目（${members.joinToString { it.name }}），'$file' 不属于其中任何一个：请给出项目里的绝对路径。",
        )
    }

    override fun listSessions(): List<SessionInfo> = all().flatMap { it.backend.listSessions() }

    override fun listTargets(kind: TargetKind?) = primary().listTargets(kind)

    override fun attachProcess(request: AttachProcessRequest) = primary().attachProcess(request)

    override fun attachDevice(request: AttachDeviceRequest) = primary().attachDevice(request)

    override fun stopSession(sessionId: String) = bySession(sessionId).stopSession(sessionId)

    override fun listRunConfigurations(): List<RunConfigInfo> {
        val members = all()
        return members.flatMap { m ->
            m.backend.listRunConfigurations().map { if (members.size > 1) it.copy(project = m.name) else it }
        }
    }

    /** 按项目名或配置名找项目；同名配置出现在多个项目里时要 Agent 给 project。 */
    override fun launchConfiguration(request: LaunchRequest): CompletableFuture<LaunchResult> {
        val members = all()
        if (members.isEmpty()) return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NO_SESSION, "IDE 没有打开项目。"))
        val candidates = if (request.project != null) {
            members.filter { it.name == request.project }.ifEmpty {
                return CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.INVALID_ARGUMENT,
                    "没有打开的项目 '${request.project}'，打开的是：${members.joinToString { it.name }}。"))
            }
        } else {
            members.filter { m -> m.backend.listRunConfigurations().any { it.name == request.name } }
        }
        return when {
            candidates.isEmpty() -> CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.TARGET_NOT_FOUND,
                "没有名为 '${request.name}' 的运行配置：先 lua_debug_session action=configs 列出现有配置。"))
            candidates.size > 1 -> CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.INVALID_ARGUMENT,
                "多个项目里都有 '${request.name}'（${candidates.joinToString { it.name }}），请用 project 指定。"))
            else -> candidates[0].backend.launchConfiguration(request)
        }
    }

    override fun restartSession(sessionId: String) = bySession(sessionId).restartSession(sessionId)

    override fun listVms(sessionId: String) = bySession(sessionId).listVms(sessionId)

    override fun capabilities(sessionId: String) = bySession(sessionId).capabilities(sessionId)

    override fun setBreakpoint(spec: BreakpointSpec) = byFile(spec.file).setBreakpoint(spec)

    override fun removeBreakpoints(filter: BreakpointFilter): CompletableFuture<Int> =
        fanOut(filter.file) { it.removeBreakpoints(filter) }

    override fun enableBreakpoints(filter: BreakpointFilter, enabled: Boolean): CompletableFuture<Int> =
        fanOut(filter.file) { it.enableBreakpoints(filter, enabled) }

    /** 指定文件时只发给那个项目；按 id / all 时发给所有项目，结果相加。 */
    private fun fanOut(file: String?, op: (LuaDebugBackend) -> CompletableFuture<Int>): CompletableFuture<Int> {
        if (file != null) return op(byFile(file))
        val futures = all().map { op(it.backend) }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.sumOf { it.join() } }
    }

    override fun listBreakpoints(origin: BreakpointOrigin?) = all().flatMap { it.backend.listBreakpoints(origin) }

    override fun resume(sessionId: String, vmId: String?) = bySession(sessionId).resume(sessionId, vmId)

    override fun pause(sessionId: String, vmId: String?) = bySession(sessionId).pause(sessionId, vmId)

    override fun step(sessionId: String, vmId: String?, kind: StepKind) = bySession(sessionId).step(sessionId, vmId, kind)

    override fun runToLine(sessionId: String, vmId: String?, file: String, line: Int) =
        bySession(sessionId).runToLine(sessionId, vmId, file, line)

    override fun currentPauses(): List<PauseInfo> = all().flatMap { it.backend.currentPauses() }

    override fun addListener(listener: (DebugEvent) -> Unit): AutoCloseable {
        // 订阅时的项目集合为准；wait 一次最多两分钟，期间新开的项目收不到事件可以接受
        val subs = all().map { it.backend.addListener(listener) }
        return AutoCloseable { subs.forEach { runCatching { it.close() } } }
    }

    override fun getStack(sessionId: String, vmId: String?, start: Int, count: Int) =
        bySession(sessionId).getStack(sessionId, vmId, start, count)

    override fun getVariables(request: VariablesRequest): CompletableFuture<VariablesPage> =
        firstSucceeding { it.getVariables(request) }

    override fun evaluate(request: EvaluateRequest): CompletableFuture<VariableInfo> =
        firstSucceeding { it.evaluate(request) }

    override fun setVariable(request: SetVariableRequest): CompletableFuture<VariableInfo> =
        firstSucceeding { it.setVariable(request) }

    /**
     * frameId / ref 不带项目信息：只有一个项目时直接发；多个时依次试，跳过报 STALE_REFERENCE / NO_SESSION 的。
     * 多项目同时调试很少见，这点开销可以接受，换来引擎不用在 id 里编码项目。
     */
    private fun <T> firstSucceeding(op: (LuaDebugBackend) -> CompletableFuture<T>): CompletableFuture<T> {
        val members = all()
        if (members.size <= 1) return op(members.firstOrNull()?.backend ?: primary())
        var chain: CompletableFuture<T> = CompletableFuture.failedFuture(
            LuaDebugBackendException(ErrorCode.STALE_REFERENCE, "这个引用已失效。")
        )
        for (m in members) {
            chain = chain.exceptionallyCompose { e ->
                val cause = (e as? java.util.concurrent.CompletionException)?.cause ?: e
                if (cause is LuaDebugBackendException && (cause.code == ErrorCode.STALE_REFERENCE || cause.code == ErrorCode.NO_SESSION)) {
                    op(m.backend)
                } else {
                    CompletableFuture.failedFuture(cause)
                }
            }
        }
        return chain
    }

    override fun getOutput(sessionId: String?, sinceSeq: Long, limit: Int): OutputPage {
        if (sessionId != null) return bySession(sessionId).getOutput(sessionId, sinceSeq, limit)
        val members = all()
        if (members.size == 1) return members[0].backend.getOutput(null, sinceSeq, limit)
        // 各项目的 seq 各自递增，合并后游标只在单项目时精确；多项目时按时间排序后截取
        val pages = members.map { it.backend.getOutput(null, sinceSeq, limit) }
        val entries = pages.flatMap { it.entries }.sortedBy { it.timestampMillis }.takeLast(limit)
        return OutputPage(entries, pages.maxOfOrNull { it.nextSeq } ?: sinceSeq)
    }

    override fun startProfile(sessionId: String, vmId: String?, durationMs: Int) = bySession(sessionId).startProfile(sessionId, vmId, durationMs)

    override fun stopProfile(sessionId: String, vmId: String?, sort: ProfileSort, limit: Int) =
        bySession(sessionId).stopProfile(sessionId, vmId, sort, limit)

    override fun getProbeHits(breakpointId: String?, sinceSeq: Long, limit: Int): ProbeHitPage {
        val members = all()
        if (members.size == 1) return members[0].backend.getProbeHits(breakpointId, sinceSeq, limit)
        val pages = members.map { it.backend.getProbeHits(breakpointId, sinceSeq, limit) }
        val hits = pages.flatMap { it.hits }.sortedBy { it.timestampMillis }.take(limit)
        return ProbeHitPage(hits, pages.maxOfOrNull { it.nextSeq } ?: sinceSeq)
    }

    /** 给了文件时按第一个文件所属的项目发（UE 项目根从项目目录往上找）；否则发给当前焦点项目。 */
    override fun reloadModules(request: ReloadRequest): CompletableFuture<ReloadResult> =
        (request.files.firstOrNull()?.let { byFile(it) } ?: primary()).reloadModules(request)

    override fun sourceSnippet(file: String, line: Int, radius: Int): List<SourceLine>? =
        all().firstNotNullOfOrNull { it.backend.sourceSnippet(file, line, radius) }
}
