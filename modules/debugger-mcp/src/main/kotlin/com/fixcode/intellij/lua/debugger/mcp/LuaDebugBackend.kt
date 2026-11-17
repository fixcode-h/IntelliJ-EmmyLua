package com.fixcode.intellij.lua.debugger.mcp

import java.util.concurrent.CompletableFuture

/**
 * MCP 工具层对调试引擎的全部要求。
 *
 * 由 IDE 调试引擎（`modules/debugger-engine` 的 DebugSessionManager 或其适配层）实现；
 * MCP 模块只认这个接口，所以能用假实现单测，也不依赖 IntelliJ。
 *
 * 约定：
 * - 所有 id 都是字符串，由引擎生成；`frameId` / `ref` 绑定 pauseId，过期时抛 [LuaDebugBackendException]
 *   （code = [ErrorCode.STALE_REFERENCE]），MCP 层会附上"先调 wait_for_pause / get_stack"的恢复提示。
 * - 返回 CompletableFuture 的方法可以在引擎线程完成；MCP 在自己的 HTTP 工作线程上带超时等待。
 * - 不返回 future 的方法要快（只读内存状态），可在任意线程调用。
 * - Agent 下的断点 origin = AGENT，和 IDE 断点在同一个 BreakpointStore 里（设计文档 3.1）。
 * - 权限（首次控制确认、任意求值开关、附加确认）由 MCP 层在调用前处理，引擎不用管；
 *   但 runtime 侧的 allowLuaEval 仍由引擎按 IDE 设置下发（协议 v3 setOptions），作为第二道保险。
 */
interface LuaDebugBackend {
    fun listSessions(): List<SessionInfo>

    /** 可附加的本机进程（只列看起来加载了 Lua 的）和已发现的设备上的可调试应用。 */
    fun listTargets(kind: TargetKind?): CompletableFuture<List<TargetInfo>>

    /** 启动一个附加会话，完成时返回 sessionId（握手完成后才完成）。 */
    fun attachProcess(request: AttachProcessRequest): CompletableFuture<String>

    fun attachDevice(request: AttachDeviceRequest): CompletableFuture<String>

    fun stopSession(sessionId: String): CompletableFuture<Unit>

    // ---- IDE 运行配置（IDE 自带 MCP 的 execute_run_configuration 只能 Run，调试要从这里启动）----

    /** 项目里的运行配置；[RunConfigInfo.running] 表示正在运行或调试。 */
    fun listRunConfigurations(): List<RunConfigInfo> = emptyList()

    /**
     * 用 IDE 的 Debug（或 Run）执行器启动一个运行配置，和用户点调试按钮一样。
     * Lua 调试配置等到会话连上再完成，返回带 sessionId 的结果；其他配置启动后就完成。
     */
    fun launchConfiguration(request: LaunchRequest): CompletableFuture<LaunchResult> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "这个 IDE 版本的插件不支持启动运行配置。"))

    /**
     * 重启调试会话：用运行配置启动的按配置重新执行（会重新找进程），Agent 直接附加的按原目标重新附加。
     * 完成时返回新会话的 sessionId。
     */
    fun restartSession(sessionId: String): CompletableFuture<String> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "这个 IDE 版本的插件不支持重启会话。"))

    fun listVms(sessionId: String): List<VmInfo>

    /** 运行时能力位（协议 v3 welcome.caps），例如 "luaEval"、"valuePath"、"logpoint"。 */
    fun capabilities(sessionId: String): Set<String>

    // ---- 断点 ----

    /** 下断点（origin = AGENT），返回断点；已存在同文件同行的 Agent 断点时更新它。 */
    fun setBreakpoint(spec: BreakpointSpec): CompletableFuture<BreakpointInfo>

    /**
     * 返回实际删掉的个数。按 id 指定时 IDE 断点也能删（Agent 明确点名）；
     * 按 all 批量删时只删 origin = AGENT 的，除非 includeIde = true。
     */
    fun removeBreakpoints(filter: BreakpointFilter): CompletableFuture<Int>

    fun listBreakpoints(origin: BreakpointOrigin?): List<BreakpointInfo>

    /** 启用/禁用匹配的断点，返回改动的个数。 */
    fun enableBreakpoints(filter: BreakpointFilter, enabled: Boolean): CompletableFuture<Int>

    // ---- 执行控制 ----

    fun resume(sessionId: String, vmId: String?): CompletableFuture<Unit>

    fun pause(sessionId: String, vmId: String?): CompletableFuture<Unit>

    fun step(sessionId: String, vmId: String?, kind: StepKind): CompletableFuture<Unit>

    fun runToLine(sessionId: String, vmId: String?, file: String, line: Int): CompletableFuture<Unit>

    /** 当前处于暂停的 VM（每个暂停一个快照）；没有暂停时为空。 */
    fun currentPauses(): List<PauseInfo>

    /**
     * 订阅引擎事件。返回值 close() 取消订阅。监听器在引擎线程调用，必须立即返回。
     * wait_for_pause 靠它实现；UI 点继续时 Agent 也会收到 Resumed(byUser = true)。
     */
    fun addListener(listener: (DebugEvent) -> Unit): AutoCloseable

    // ---- 查看 ----

    fun getStack(sessionId: String, vmId: String?, start: Int, count: Int): CompletableFuture<StackPage>

    fun getVariables(request: VariablesRequest): CompletableFuture<VariablesPage>

    fun evaluate(request: EvaluateRequest): CompletableFuture<VariableInfo>

    /**
     * 改一个变量（协议 v3 setVariable）。[SetVariableRequest.path] 是值路径：只有名字时改局部变量 / 上值（或按 scope 指定），
     * 带 `.x` / `[3]` 时改它前面那个 table 的字段。返回改后的值。默认不支持。
     */
    fun setVariable(request: SetVariableRequest): CompletableFuture<VariableInfo> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "这个调试引擎不支持修改变量。"))

    /** 调试器日志、print 输出、Lua 错误；seq 单调递增，sessionId 为空表示所有会话。 */
    fun getOutput(sessionId: String?, sinceSeq: Long, limit: Int): OutputPage

    fun getProbeHits(breakpointId: String?, sinceSeq: Long, limit: Int): ProbeHitPage

    /** 读本地源码片段（[line] 前后 [radius] 行），给栈帧附源码；拿不到返回 null。 */
    fun sourceSnippet(file: String, line: Int, radius: Int): List<SourceLine>? = null

    // ---- UE 热重载（不走调试连接，经 UELink 的 lua.reload；没附加也能用） ----

    /**
     * 让开着的 UE 编辑器 / 游戏重新加载 Lua 模块。找不到 UE 实例时抛 [ErrorCode.TARGET_NOT_FOUND]，
     * UE 那边没有 lua.reload（UELink 太旧或没装 UnLua）时抛 [ErrorCode.NOT_SUPPORTED]。
     * 默认不支持：非 UE 宿主的引擎实现不用管。
     */
    fun reloadModules(request: ReloadRequest): CompletableFuture<ReloadResult> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "这个 IDE 版本不支持 UE 热重载。"))

    // ---- 采样性能分析（协议 v3 第 20 节，能力 profiler） ----

    /**
     * 开始采样（[vmId] 为空表示所有 VM），runtime 到 [durationMs] 自动停（MCP 中途断了也不会一直采下去）。
     * runtime 没有 profiler 能力时抛 [ErrorCode.NOT_SUPPORTED]。
     */
    fun startProfile(sessionId: String, vmId: String?, durationMs: Int): CompletableFuture<Unit> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "这个调试引擎不支持采样性能分析。"))

    /** 停止采样（已经到点自动停了也可以调）并取函数表，按 [sort] 排序取前 [limit] 个。 */
    fun stopProfile(sessionId: String, vmId: String?, sort: ProfileSort, limit: Int): CompletableFuture<ProfileReport> =
        CompletableFuture.failedFuture(LuaDebugBackendException(ErrorCode.NOT_SUPPORTED, "这个调试引擎不支持采样性能分析。"))
}

enum class ProfileSort { SELF, TOTAL }

data class ProfileFunction(
    /** 函数名；取不到时 `<匿名>`，主 chunk `<主 chunk>`。 */
    val name: String,
    /** 映射到的本地文件；没映射上时是 chunkName，C 函数为空。 */
    val file: String?,
    /** 函数定义行；C 函数 -1、主 chunk 0。 */
    val line: Int,
    val selfSamples: Long,
    val totalSamples: Long,
    val selfPercent: Double,
    val totalPercent: Double,
)

data class ProfileReport(
    val samples: Long,
    val durationMs: Long,
    val functions: List<ProfileFunction>,
    val totalFunctions: Int,
    val intervalInstructions: Int = 0,
    /** 采样回调本身的耗时，给 Agent 判断采样有没有影响结果。 */
    val overheadMs: Double? = null,
    /** 暂停在断点时到点、没计入的采样次数。 */
    val pausedSkipped: Long? = null,
)

class LuaDebugBackendException(
    val code: ErrorCode,
    message: String,
) : RuntimeException(message)

enum class ErrorCode {
    NO_SESSION,
    NO_VM,
    NOT_PAUSED,
    STALE_REFERENCE,
    NOT_SUPPORTED,
    EVAL_FORBIDDEN,
    TIMEOUT,
    TARGET_NOT_FOUND,
    ATTACH_FAILED,
    INVALID_ARGUMENT,
    INTERNAL,
}

enum class TargetKind { PROCESS, DEVICE }

data class RunConfigInfo(
    val name: String,
    /** 配置类型的显示名，例如「Lua 附加」「Application」。 */
    val type: String,
    /** 新调试链路的 Lua 配置：Debug 启动后会出现在 lua_debug_session 的会话里。 */
    val lua: Boolean,
    /** 有能以 Debug 方式运行它的 runner。 */
    val canDebug: Boolean,
    val running: Boolean = false,
    /** IDE 工具栏当前选中的配置。 */
    val selected: Boolean = false,
    /** 多项目时所属的项目名（由 Composite 填）。 */
    val project: String? = null,
)

data class LaunchRequest(
    val name: String,
    /** false = 用 Run 执行器（只对非 Lua 配置有意义）。 */
    val debug: Boolean = true,
    /** 多项目时指定项目名；省略时按配置名在各项目里找。 */
    val project: String? = null,
)

data class LaunchResult(
    val name: String,
    /** Lua 配置启动后连上的会话；非 Lua 配置为空。 */
    val sessionId: String? = null,
    val executor: String = "debug",
)

enum class SessionKind { ATTACH, DEVICE, ADDRESS }

enum class SessionState { CONNECTING, RUNNING, PAUSED, DISCONNECTED }

data class SessionInfo(
    val sessionId: String,
    val kind: SessionKind,
    /** 一句话描述目标，例如 "UE4Editor.exe (1234)"、"com.foo.game @ emulator-5554"。 */
    val target: String,
    val state: SessionState,
    val vmCount: Int,
    val pausedVmIds: List<String> = emptyList(),
    /** 例如 "Lua 5.4"；未知为空。 */
    val luaVersion: String? = null,
)

data class TargetInfo(
    val kind: TargetKind,
    /** 进程：pid；设备：设备序列号。 */
    val id: String,
    val name: String,
    val detail: String? = null,
    /** 设备上的包名（kind = DEVICE 时）。 */
    val packageName: String? = null,
    val pid: Int? = null,
)

data class AttachProcessRequest(
    val pid: Int?,
    val processName: String?,
    val captureOutput: Boolean = true,
)

data class AttachDeviceRequest(
    val deviceId: String,
    val packageName: String?,
    val pid: Int?,
    val launchAndAttach: Boolean = false,
)

enum class VmState { RUNNING, PAUSED, CLOSED }

data class VmInfo(
    val vmId: String,
    val displayName: String,
    val state: VmState,
    val sourceEpoch: Long,
)

enum class BreakpointOrigin { IDE, AGENT }

data class BreakpointSpec(
    /** 本地文件路径（绝对或相对项目根），引擎负责映射到 chunkName。函数断点可为空串（给了就只认这个文件里的函数）。 */
    val file: String,
    /** 行号；函数断点为 0。 */
    val line: Int,
    val condition: String? = null,
    /** 命中次数条件，例如 "5"、">=3"、"%2"。 */
    val hitCondition: String? = null,
    /** 日志点：命中时输出这句（支持 {expr}），不暂停。 */
    val logMessage: String? = null,
    /** 命中时采集的变量路径（受限求值语法），结果进 Probe 记录。 */
    val capture: List<String> = emptyList(),
    /** 命中后自动继续（Probe）。 */
    val autoContinue: Boolean = false,
    val vmId: String? = null,
    /** 函数断点：`Module.func`、`Class:Method` 或全局函数名，停在函数第一行（和 file + line 二选一）。 */
    val function: String? = null,
)

data class BreakpointFilter(
    val breakpointId: String? = null,
    val breakpointIds: List<String> = emptyList(),
    val file: String? = null,
    val line: Int? = null,
    /** 按函数断点的函数名匹配。 */
    val function: String? = null,
    /** 匹配所有断点（默认只 Agent 下的，includeIde 时连用户的一起）。 */
    val all: Boolean = false,
    /** 也作用于用户在 IDE 里下的断点（对应 clear scope=all）。 */
    val includeIde: Boolean = false,
)

data class BreakpointInfo(
    val breakpointId: String,
    val origin: BreakpointOrigin,
    val file: String,
    val line: Int,
    /** runtime 已确认（chunk 已加载且行有效）。 */
    val verified: Boolean,
    val condition: String? = null,
    val hitCondition: String? = null,
    val logMessage: String? = null,
    val capture: List<String> = emptyList(),
    val autoContinue: Boolean = false,
    val enabled: Boolean = true,
    /** 没验证的原因，例如"chunk 未加载"、"行上没有代码"。 */
    val message: String? = null,
    /** 函数断点的函数名（此时 line 为 0，file 是限定的文件或空串）。 */
    val function: String? = null,
)

enum class StepKind { OVER, INTO, OUT }

enum class PauseReason { BREAKPOINT, STEP, PAUSE, ERROR, ENTRY, RUN_TO_LINE, OTHER }

data class PauseInfo(
    val sessionId: String,
    val vmId: String,
    val vmName: String,
    val pauseId: String,
    val reason: PauseReason,
    val breakpointId: String? = null,
    /** reason = ERROR 时的错误消息。 */
    val errorMessage: String? = null,
    /** 顶层帧（paused 事件自带，不用再拉栈）。 */
    val topFrame: FrameInfo? = null,
)

data class FrameInfo(
    val frameId: String,
    val index: Int,
    val function: String,
    /** 映射到的本地文件；没映射时为空。 */
    val file: String?,
    val line: Int,
    val chunkName: String? = null,
)

data class StackPage(
    val pauseId: String,
    val frames: List<FrameInfo>,
    val total: Int?,
)

enum class VariableScope { LOCALS, UPVALUES, GLOBALS }

data class VariablesRequest(
    val frameId: String?,
    val scope: VariableScope?,
    /** 继续展开某个变量时传它的 ref（和 frameId/scope 二选一）。 */
    val ref: String?,
    /** 从作用域开始按路径取，例如 "self.items[3].id"。 */
    val path: String?,
    val depth: Int,
    val limit: Int,
)

data class VariableInfo(
    val name: String,
    val type: String,
    /** 显示值（table 是摘要，例如 "{id=1003, count=2, …}"）。 */
    val value: String,
    /** 可展开时有 ref。 */
    val ref: String? = null,
    /** table 的元素总数；未知为空。 */
    val childCount: Int? = null,
    /** depth > 1 时已经展开的子项。 */
    val children: List<VariableInfo>? = null,
)

data class VariablesPage(
    val variables: List<VariableInfo>,
    val total: Int?,
)

enum class EvalMode { VALUE_PATH, LUA }

data class EvaluateRequest(
    val frameId: String,
    val expression: String,
    val mode: EvalMode,
)

data class SetVariableRequest(
    val frameId: String,
    /** 要改的变量，值路径：hp、self.hp、items[3]、cfg["key"]。 */
    val path: String,
    /** 新值：value_path 模式下是字面量（1、"a"、true、nil）或另一个值路径；lua 模式下是任意 Lua 表达式。 */
    val value: String,
    val mode: EvalMode,
    /** 只有名字时在哪个作用域找；null 表示先局部变量后上值。 */
    val scope: VariableScope? = null,
)

/** 值路径拆成「父路径 + 最后一段」，最后一段写成 runtime setVariable 认的名字（`x`、`[3]`、`[key]`）。 */
object ValuePaths {
    /** 拆不开（空、括号或引号不配对）时返回 null。父路径为 null 表示整个路径就是一个名字。 */
    fun splitLast(path: String): Pair<String?, String>? {
        val p = path.trim()
        if (p.isEmpty()) return null
        var depth = 0
        var quote: Char? = null
        var lastSep = -1
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '[' -> { if (depth == 0) lastSep = i; depth++ }
                ']' -> { depth--; if (depth < 0) return null }
                '.' -> if (depth == 0) lastSep = i
            }
            i++
        }
        if (depth != 0 || quote != null) return null
        if (lastSep < 0) return if (isName(p)) null to p else null
        val parent = p.substring(0, lastSep).trim().takeIf { it.isNotEmpty() } ?: return null
        val tail = p.substring(lastSep)
        val leaf = if (tail.startsWith(".")) {
            tail.substring(1).trim().takeIf(::isName) ?: return null
        } else {
            val inner = tail.removePrefix("[").removeSuffix("]").trim()
            if (!tail.endsWith("]") || inner.isEmpty()) return null
            val key = if (inner.length >= 2 && (inner[0] == '"' || inner[0] == '\'') && inner.last() == inner[0]) inner.substring(1, inner.length - 1)
            else inner.takeIf { it.toDoubleOrNull() != null } ?: return null
            "[$key]"
        }
        return parent to leaf
    }

    private fun isName(s: String) = s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }
}

enum class OutputCategory { STDOUT, STDERR, ERROR, DEBUGGER }

data class OutputEntry(
    val seq: Long,
    val timestampMillis: Long,
    val sessionId: String,
    val category: OutputCategory,
    val text: String,
    val vmId: String? = null,
)

data class OutputPage(val entries: List<OutputEntry>, val nextSeq: Long)

data class ProbeHit(
    val seq: Long,
    val timestampMillis: Long,
    val sessionId: String,
    val breakpointId: String,
    val vmId: String,
    val file: String?,
    val line: Int,
    /** 采集路径 → 显示值；取不到的值是错误文本。 */
    val values: Map<String, String>,
)

data class ProbeHitPage(val hits: List<ProbeHit>, val nextSeq: Long)

data class SourceLine(val line: Int, val text: String)

/** 热重载要什么：模块名、本地文件（由 IDE 换算成模块名）或「所有改过的」，三选一（[changed] 时另两个为空）。 */
data class ReloadRequest(
    val modules: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val changed: Boolean = false,
)

data class ReloadItem(
    val module: String,
    val ok: Boolean,
    /** UELink 用的方式：reloader（项目接的热重载入口）/ require（兜底）。 */
    val mode: String? = null,
    val error: String? = null,
)

/** 一个 UE 实例（名片）上的结果。 */
data class ReloadTargetResult(
    val pid: Long,
    /** 名片的 kind：editor / game / server。 */
    val kind: String,
    val items: List<ReloadItem> = emptyList(),
    /** UELink 回包的 note（例如 require 兜底时模块顶部 local 会重新初始化）。 */
    val note: String? = null,
    /** 整个实例调用失败（连不上、没有 lua.reload）时的原因；这时 items 为空。 */
    val error: String? = null,
)

data class ReloadResult(
    val targets: List<ReloadTargetResult>,
    /** files 里换算不出模块名的（不在 Content/Script 下）：文件 → 原因。 */
    val skipped: Map<String, String> = emptyMap(),
)

/** 引擎事件：MCP 只关心这几种。 */
sealed class DebugEvent {
    abstract val sessionId: String

    data class Paused(val pause: PauseInfo) : DebugEvent() {
        override val sessionId: String get() = pause.sessionId
    }

    data class Resumed(
        override val sessionId: String,
        val vmId: String?,
        /** 用户在 IDE 里点了继续/单步（不是 Agent 发起的）。 */
        val byUser: Boolean,
    ) : DebugEvent()

    data class ProbeHitEvent(val hit: ProbeHit) : DebugEvent() {
        override val sessionId: String get() = hit.sessionId
    }

    data class SessionStarted(override val sessionId: String) : DebugEvent()

    data class SessionEnded(override val sessionId: String, val reason: String?) : DebugEvent()

    data class BreakpointsChanged(override val sessionId: String) : DebugEvent()
}
