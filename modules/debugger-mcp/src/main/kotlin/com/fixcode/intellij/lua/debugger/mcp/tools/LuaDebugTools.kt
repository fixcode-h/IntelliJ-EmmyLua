package com.fixcode.intellij.lua.debugger.mcp.tools

import com.fixcode.intellij.lua.debugger.mcp.*
import com.fixcode.intellij.lua.debugger.mcp.json.JsonArgs
import com.fixcode.intellij.lua.debugger.mcp.json.ToolArgumentException
import com.fixcode.intellij.lua.debugger.mcp.json.ToolException
import com.fixcode.intellij.lua.debugger.mcp.protocol.*
import com.fixcode.intellij.lua.debugger.mcp.tools.ResultJson.obj
import com.fixcode.intellij.lua.debugger.mcp.tools.ResultJson.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Lua 调试的三个 MCP 工具，形状照 UEToVS 的 `debug_session / debug_breakpoints / debug_inspect`
 * （规范见 AGENTS.md「MCP 工具规范」）：
 *
 * - `lua_debug_session`：status / targets / attach / stop / pause / continue / step / wait / reload
 * - `lua_debug_breakpoints`：list / add / remove / enable / clear
 * - `lua_debug_inspect`：stack / vms / locals / eval / set / output / probes / profile
 *
 * [backend] 每次调用时取：IDE 没有打开项目、新引擎没装好时返回 null，工具报 engine_unavailable。
 */
class LuaDebugTools(
    private val backend: () -> LuaDebugBackend?,
    private val policy: McpPermissionPolicy,
    private val timeouts: Timeouts = Timeouts(),
    /** `lua_debug_session action=help` 返回的完整用法（Markdown）。 */
    private val guide: String? = null,
) {
    class Timeouts(
        /** 普通请求（取栈、变量、下断点）等引擎回包的上限。 */
        val requestMillis: Long = 15_000,
        /** 附加（注入 + 握手）的上限。 */
        val attachMillis: Long = 90_000,
    )

    fun all(): List<McpTool> = listOf(sessionTool(), breakpointsTool(), inspectTool())

    // ======================================================================= lua_debug_session

    private val sessionActions = arrayOf("status", "configs", "launch", "targets", "attach", "stop", "restart", "pause", "continue", "step", "wait", "reload", "help")

    private fun sessionTool() = McpTool(
        name = SESSION,
        title = "Start / step / wait in the Lua debugger",
        titleZh = "Lua 调试：会话与单步",
        description = ToolGuide(
            what = "Drives the IDE's EmmyLua debugger on running Lua (UE/UnLua, Unity, own engine, Android): start debugging a run " +
                "configuration (launch, Debug executor; the IDE's execute_run_configuration only Runs), attach to a process or " +
                "Android app, stop/restart, pause, continue, step, wait until it stops. reload: hot-reload Lua into UE via UELink.",
            useWhen = "you need to see Lua run: stop where a suspect line runs, catch a Lua error, step through code. Typical: " +
                "status (or configs -> launch name=..., or targets -> attach) -> $BREAKPOINTS add -> trigger the code -> wait -> " +
                "$INSPECT -> step / continue. After editing UnLua scripts: reload files=[...] instead of restarting PIE.",
            insteadOf = "adding print() calls to guess values: breakpoints show the real state without editing scripts.",
            notFor = "C++ or Blueprint (VS debugger tools). launch, attach, reload and the first control of a session ask the " +
                "user in the IDE; on permission_denied ask the user. A stopped VM freezes the game.",
            results = "status: sessions + stop location; configs: run configs; pause/step/wait: event = break / probe / resumed / " +
                "exited / timeout / idle, break: location, stack, locals; help: guide.",
        ).render(),
        inputSchema = schema {
            string("action", "What to do.", required = true, enum = sessionActions.toList())
            string("session_id", "Session from action=status (optional when there is only one session).")
            string("vm", "Lua VM id from $INSPECT action=vms (default: the stopped VM / all VMs).")
            string("kind", "targets: process / device. step: over (default) / into / out / toLine (run to file+line).",
                enum = listOf("process", "device", "over", "into", "out", "toLine"))
            string("config", "launch: run configuration name from action=configs (exact, or a unique part).")
            string("project", "configs/launch: project name when several projects are open.")
            boolean("debug", "launch: true (default) = Debug executor; false = Run.")
            integer("pid", "attach: process id (local process, or on the device with 'device').", minimum = 1)
            string("name", "attach: local process name part, e.g. UnrealEditor (several matches -> error listing pids).")
            integer("limit", "configs: page size (default 50, max 200).", minimum = 1, maximum = 200)
            integer("offset", "configs: items to skip.", minimum = 0)
            string("device", "attach: adb serial of an Android device (see action=targets kind=device).")
            string("package", "attach with device: application package name.")
            boolean("launch", "attach with device: restart the app and attach at startup.")
            string("file", "step toLine: Lua file (absolute or relative to the project).")
            integer("line", "step toLine: 1-based line.", minimum = 1)
            integer("frames", "status: stack frames to include for a stopped VM (0-20, default 5).", minimum = 0, maximum = 20)
            integer("wait_ms", "pause/continue/step/launch: also wait up to this long for the next stop (step 10000, pause 5000, others 0).",
                minimum = 0, maximum = 120000)
            integer("timeout_ms", "wait: max wait (default 30000).", minimum = 0, maximum = 120000)
            boolean("include_probes", "wait: also return on a probe hit (breakpoint with capture + auto_continue). Default true.")
            stringArray("modules", "reload: Lua module names relative to Content/Script, e.g. ['UI.BagPanel'].", 200)
            stringArray("files", "reload: Lua files (absolute or relative to the project) under Content/Script; converted to module names.", 200)
            boolean("changed", "reload: true = reload every module whose file changed since it was loaded (UELink lua.modules stale).")
        },
        level = PermissionLevel.CONTROL,
        destructive = false,
    ) { call ->
        guarded {
        val b = backend()
        when (call.args.choice("action", null, *sessionActions)) {
            "status" -> status(b, call.args)
            "configs" -> configs(requireBackend(b), call.args)
            "launch" -> launch(requireBackend(b), call)
            "restart" -> restart(requireBackend(b), call)
            "targets" -> targets(requireBackend(b), call.args)
            "attach" -> attach(requireBackend(b), call)
            "stop" -> stop(requireBackend(b), call)
            "pause" -> control(requireBackend(b), call, "pause", 5000) { s, vm -> pause(s, vm) }
            "continue" -> control(requireBackend(b), call, "continue", 0) { s, vm -> resume(s, vm) }
            "step" -> step(requireBackend(b), call)
            "wait" -> wait(b, call)
            "reload" -> reload(requireBackend(b), call)
            "help" -> obj("guide" to guide)
            else -> throw ToolArgumentException("缺少参数 'action'")
        }
        }
    }

    private fun status(b: LuaDebugBackend?, args: JsonArgs): Map<String, Any?> {
        if (b == null) return obj("engine" to "unavailable", "note" to ENGINE_UNAVAILABLE)
        val frames = args.int("frames", 5, 0, 20)
        val wanted = args.string("session_id")?.takeIf { it.isNotBlank() }
        val sessions = b.listSessions().filter { wanted == null || it.sessionId == wanted }
        if (wanted != null && sessions.isEmpty()) throw noSuchSession(b, wanted)
        val pauses = b.currentPauses()
        val items = sessions.map { s ->
            val m = LinkedHashMap(ResultJson.session(s))
            val p = pauses.firstOrNull { it.sessionId == s.sessionId }
            if (p != null) m["stopped"] = pauseNode(b, p, frames, includeLocals = false)
            m
        }
        val result = obj("count" to items.size)
        result["sessions"] = items
        if (items.isEmpty()) result["note"] = "没有调试会话。用 action=configs 看运行配置再 action=launch config=<名字> 以 Debug 启动，" +
            "或 action=targets 找进程或设备再 action=attach。"
        return result
    }

    private fun configs(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val project = args.string("project")?.takeIf { it.isNotBlank() }
        val all = b.listRunConfigurations().filter { project == null || it.project == null || it.project == project }
        val offset = args.int("offset", 0, 0, Int.MAX_VALUE)
        val limit = args.int("limit", 50, 1, 200)
        val m = LinkedHashMap(page(all.drop(offset).take(limit), offset, all.size > offset + limit, all.size) { c ->
            obj("name" to c.name, "type" to c.type, "project" to c.project, "lua" to c.lua,
                "noDebug" to !c.canDebug, "running" to c.running, "selected" to c.selected)
        })
        if (all.isEmpty()) m["note"] = "项目里没有运行配置：附加本机进程用 action=targets + action=attach。"
        return m
    }

    /** 以 Debug（默认）启动运行配置，和用户点调试按钮一样；Lua 配置等会话连上再返回。 */
    private fun launch(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val a = call.args
        val wanted = a.string("config")?.takeIf { it.isNotBlank() }
            ?: throw ToolArgumentException("launch 要给 'config'（运行配置名，先 action=configs 列出）")
        val project = a.string("project")?.takeIf { it.isNotBlank() }
        val debug = a.bool("debug", true)
        val waitMs = a.int("wait_ms", 0, 0, 120000)
        val config = resolveConfig(b.listRunConfigurations().filter { project == null || it.project == null || it.project == project }, wanted)
        if (debug && !config.canDebug) {
            throw ToolException("not_supported", "运行配置「${config.name}」（${config.type}）不能以 Debug 方式运行；可以 debug=false 以 Run 启动。")
        }
        val verb = if (debug) "以 Debug 启动" else "运行"
        policy.checkLaunch("${verb}运行配置「${config.name}」", call.client.clientName)?.let { throw denied(it) }
        // 先订阅再启动：启动后马上命中断点也不会漏
        PauseWaiter(b, call.client).use { waiter ->
            val started = await(b.launchConfiguration(LaunchRequest(config.name, debug, config.project ?: project)), timeouts.attachMillis)
            val m = obj("config" to started.name, "executor" to started.executor, "sessionId" to started.sessionId)
            val sid = started.sessionId
            if (sid != null) {
                b.listSessions().firstOrNull { it.sessionId == sid }?.let { m["target"] = it.target }
                if (waitMs > 0) {
                    val pause = waiter.unseenPause(sid)
                    m.putAll(if (pause != null) breakResult(b, call.client, pause)
                        else outcome(b, call.client, waiter.await(sid, waitMs.toLong(), includeProbe = false, stopOnResume = false)))
                }
            } else if (debug) {
                m["note"] = "不是 Lua 调试配置，已用 IDE 的调试器启动；它的会话不归 lua_debug_* 管。"
            }
            return m
        }
    }

    private fun resolveConfig(list: List<RunConfigInfo>, wanted: String): RunConfigInfo {
        list.filter { it.name == wanted }.let { if (it.size == 1) return it[0] }
        val exact = list.filter { it.name.equals(wanted, ignoreCase = true) }
        if (exact.size == 1) return exact[0]
        val partial = list.filter { it.name.contains(wanted, ignoreCase = true) }
        return when (partial.size) {
            1 -> partial[0]
            0 -> throw ToolException("target_not_found", "没有名为「$wanted」的运行配置。现有：" +
                list.take(20).joinToString("、") { it.name }.ifEmpty { "（无）" } + "。")
            else -> throw ToolArgumentException("「$wanted」匹配多个运行配置：" +
                partial.take(20).joinToString("、") { it.name + (it.project?.let { p -> " @$p" } ?: "") } + "；写全名，多项目时加 project。")
        }
    }

    /** 重启：用运行配置启动的按配置重跑，直接附加的按原目标重新附加；返回新会话。 */
    private fun restart(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val s = resolveSession(b, call.args)
        requireControl(s, call)
        val newId = await(b.restartSession(s.sessionId), timeouts.attachMillis)
        policy.forget(s.sessionId)
        val target = b.listSessions().firstOrNull { it.sessionId == newId }?.target ?: s.target
        return obj("restarted" to s.sessionId, "sessionId" to newId, "target" to target)
    }

    private fun targets(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val kind = when (args.choice("kind", null, "process", "device")) {
            "process" -> TargetKind.PROCESS
            "device" -> TargetKind.DEVICE
            else -> null
        }
        val list = await(b.listTargets(kind), timeouts.requestMillis)
        val (items, truncated) = ResultJson.fit(list, ResultJson.BUDGET) { ResultJson.target(it) }
        val m = obj("count" to items.size, "truncated" to truncated)
        m["items"] = items
        if (list.isEmpty()) m["note"] = "没有可附加的目标：本机进程要已加载 Lua；Android 设备要 adb 已连接且应用可调试（Development 包）。"
        return m
    }

    private fun attach(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val a = call.args
        val device = a.string("device")?.takeIf { it.isNotBlank() }
        val pid = a.int("pid", 1, Int.MAX_VALUE)
        val sessionId: String
        val description: String
        if (device != null) {
            val pkg = a.string("package")?.takeIf { it.isNotBlank() }
            if (pkg == null && pid == null) throw ToolArgumentException("附加设备要给 'package' 或 'pid'")
            description = "${pkg ?: "pid $pid"} @ $device"
            policy.checkLaunch(description, call.client.clientName)?.let { throw denied(it) }
            sessionId = await(b.attachDevice(AttachDeviceRequest(device, pkg, pid, a.bool("launch", false))), timeouts.attachMillis)
        } else {
            val name = a.string("name")?.takeIf { it.isNotBlank() }
            if (pid == null && name == null) throw ToolArgumentException("附加本机进程要给 'pid' 或 'name'；附加设备给 'device' + 'package'")
            description = if (pid != null) "进程 $pid" + (name?.let { " ($it)" } ?: "") else "进程 $name"
            policy.checkLaunch(description, call.client.clientName)?.let { throw denied(it) }
            sessionId = await(b.attachProcess(AttachProcessRequest(pid, name)), timeouts.attachMillis)
        }
        val target = b.listSessions().firstOrNull { it.sessionId == sessionId }?.target ?: description
        return obj("sessionId" to sessionId, "target" to target)
    }

    private fun stop(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val s = resolveSession(b, call.args)
        requireControl(s, call)
        await(b.stopSession(s.sessionId), timeouts.requestMillis)
        policy.forget(s.sessionId)
        return obj("stopped" to s.sessionId, "target" to s.target)
    }

    private fun control(
        b: LuaDebugBackend,
        call: ToolCall,
        action: String,
        defaultWait: Int,
        command: LuaDebugBackend.(String, String?) -> CompletableFuture<Unit>,
    ): Map<String, Any?> {
        val s = resolveSession(b, call.args)
        requireControl(s, call)
        val vm = call.args.string("vm")?.takeIf { it.isNotBlank() }
        val waitMs = call.args.int("wait_ms", defaultWait, 0, 120000)
        return runAndWait(b, call.client, s.sessionId, action, waitMs) { b.command(s.sessionId, vm) }
    }

    private fun step(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val s = resolveSession(b, call.args)
        val kind = call.args.choice("kind", "over", "over", "into", "out", "toLine")!!
        val vm = call.args.string("vm")?.takeIf { it.isNotBlank() }
        val waitMs = call.args.int("wait_ms", 10000, 0, 120000)
        if (kind == "toLine") {
            val file = call.args.requireString("file")
            val line = call.args.requireInt("line", 1, Int.MAX_VALUE)
            requireControl(s, call)
            return runAndWait(b, call.client, s.sessionId, "step", waitMs) { b.runToLine(s.sessionId, vm, file, line) }
        }
        if (call.args.has("file") || call.args.has("line")) throw ToolArgumentException("'file' / 'line' 只用于 kind=toLine")
        requireControl(s, call)
        val stepKind = when (kind) { "into" -> StepKind.INTO; "out" -> StepKind.OUT; else -> StepKind.OVER }
        return runAndWait(b, call.client, s.sessionId, "step", waitMs) { b.step(s.sessionId, vm, stepKind) }
    }

    private fun wait(b: LuaDebugBackend?, call: ToolCall): Map<String, Any?> {
        if (b == null) return obj("event" to "idle", "note" to ENGINE_UNAVAILABLE)
        val sessionId = call.args.string("session_id")?.takeIf { it.isNotBlank() }
        val sessions = b.listSessions()
        if (sessionId != null && sessions.none { it.sessionId == sessionId }) throw noSuchSession(b, sessionId)
        if (sessions.isEmpty()) {
            return obj("event" to "idle", "note" to "没有调试会话，没有可等待的暂停。先 action=attach，或请用户在 IDE 里启动调试。")
        }
        val timeout = call.args.int("timeout_ms", 30000, 0, 120000).toLong()
        val includeProbes = call.args.bool("include_probes", true)
        PauseWaiter(b, call.client).use { waiter ->
            // 已经停着且这个客户端没看过：马上返回（和 UEToVS 的 wait 一样）
            waiter.unseenPause(sessionId)?.let { return breakResult(b, call.client, it) }
            return outcome(b, call.client, waiter.await(sessionId, timeout, includeProbes, stopOnResume = true))
        }
    }

    /**
     * UE 热重载：经 UELink 的 lua.reload，不需要调试会话。会改游戏里的 Lua 状态，按控制处理（首次确认）。
     * 模块名宽松：`UI/BagPanel.lua` 也当 `UI.BagPanel`。
     */
    private fun reload(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val a = call.args
        val modules = a.stringList("modules", 200).map { normalizeModule(it) }
        val files = a.stringList("files", 200)
        val changed = a.bool("changed", false)
        if (modules.isEmpty() && files.isEmpty() && !changed) {
            throw ToolArgumentException("reload 要给 'modules'（模块名，如 UI.BagPanel）、'files'（Content/Script 下的 Lua 文件）或 changed=true（重载所有改过的）")
        }
        if (changed && (modules.isNotEmpty() || files.isNotEmpty())) throw ToolArgumentException("changed=true 不和 'modules' / 'files' 一起用")
        modules.firstOrNull { !MODULE_NAME.matches(it) }?.let {
            throw ToolArgumentException("模块名 '$it' 不合法：相对 Content/Script、用点分隔，例如 UI.BagPanel")
        }
        policy.checkControl(McpPermissionPolicy.RELOAD_KEY, "UE 里的 Lua（热重载）", call.client.clientName)?.let { throw denied(it) }
        val future = b.reloadModules(ReloadRequest(modules, files, changed)).exceptionallyCompose { e ->
            // 不支持时不要附带「升级调试 runtime」的通用提示：缺的是 UE 那边的 UELink / UnLua
            val cause = (e as? java.util.concurrent.CompletionException)?.cause ?: e
            if (cause is LuaDebugBackendException && cause.code == ErrorCode.NOT_SUPPORTED) {
                CompletableFuture.failedFuture(ToolException("not_supported", cause.message.orEmpty()))
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
        val r = await(future, timeouts.requestMillis)
        var reloaded = 0
        var failed = 0
        val budget = ResultJson.BUDGET / r.targets.size.coerceAtLeast(1)
        var truncated = false
        val targets = r.targets.map { t ->
            reloaded += t.items.count { it.ok }
            failed += t.items.count { !it.ok }
            // 失败的排前面：截断时先丢成功的
            val (items, cut) = ResultJson.fit(t.items.sortedBy { it.ok }, budget) { i ->
                obj("module" to i.module, "ok" to i.ok, "mode" to i.mode, "error" to i.error)
            }
            if (cut) truncated = true
            val m = obj("pid" to t.pid, "kind" to t.kind, "error" to t.error, "note" to t.note)
            put(m, "items", items)
            m
        }
        val m = obj("reloaded" to reloaded, "failed" to failed, "truncated" to truncated)
        m["targets"] = targets
        if (r.skipped.isNotEmpty()) m["skipped"] = r.skipped
        if (reloaded == 0 && failed == 0 && r.targets.none { it.error != null }) {
            m["note"] = if (changed) "没有改过的模块（UE 里加载的模块都是最新的）。" else "没有重载任何模块：检查模块名或文件是否在 Content/Script 下。"
        }
        return m
    }

    private fun normalizeModule(raw: String): String =
        raw.trim().replace('\\', '/').removeSuffix(".lua").trim('/').replace('/', '.')

    /** 先订阅事件再发命令，避免"很快就停下、事件先于订阅到达"的竞态。 */
    private fun runAndWait(
        b: LuaDebugBackend,
        client: McpClientSession,
        sessionId: String,
        action: String,
        waitMs: Int,
        command: () -> CompletableFuture<Unit>,
    ): Map<String, Any?> {
        PauseWaiter(b, client).use { waiter ->
            // 当前这次暂停 Agent 已经在用了，要等的是"下一次"
            b.currentPauses().filter { it.sessionId == sessionId }.forEach { client.markSeen(it.pauseId) }
            await(command(), timeouts.requestMillis)
            if (waitMs == 0) return obj("action" to action, "event" to "running", "sessionId" to sessionId)
            val result = LinkedHashMap(outcome(b, client, waiter.await(sessionId, waitMs.toLong(), includeProbe = false, stopOnResume = false)))
            val withAction = obj("action" to action)
            withAction.putAll(result)
            if (result["event"] == "timeout") {
                withAction["note"] = "已执行 $action，但 $waitMs 毫秒内没有停下（游戏可能没执行到 Lua）。可以 action=wait 继续等，或 action=pause。"
            }
            return withAction
        }
    }

    private fun outcome(b: LuaDebugBackend, client: McpClientSession, o: WaitOutcome): Map<String, Any?> = when (o) {
        is WaitOutcome.Paused -> breakResult(b, client, o.pause)
        is WaitOutcome.Probe -> obj("event" to "probe", "hit" to ResultJson.probeHit(o.hit))
        is WaitOutcome.Resumed -> obj(
            "event" to "resumed", "sessionId" to o.sessionId, "vm" to o.vmId, "byUser" to true,
            "note" to "用户在 IDE 里让它继续运行了，之前的引用已失效。",
        )
        is WaitOutcome.Ended -> {
            policy.forget(o.sessionId)
            obj("event" to "exited", "sessionId" to o.sessionId, "reason" to o.reason)
        }
        WaitOutcome.Timeout -> obj("event" to "timeout")
    }

    private fun breakResult(b: LuaDebugBackend, client: McpClientSession, p: PauseInfo): Map<String, Any?> {
        client.markSeen(p.pauseId)
        val m = obj("event" to "break")
        m.putAll(pauseNode(b, p, frames = 8, includeLocals = true))
        return m
    }

    /** 停下时的位置、栈、（可选）栈顶局部变量。栈和变量取不到不算错，省略即可。 */
    private fun pauseNode(b: LuaDebugBackend, p: PauseInfo, frames: Int, includeLocals: Boolean): Map<String, Any?> {
        val m = obj(
            "sessionId" to p.sessionId,
            "vm" to p.vmId,
            "vmName" to p.vmName,
            "reason" to ResultJson.reason(p.reason),
            "breakpoint" to p.breakpointId,
            "error" to p.errorMessage,
        )
        val stack = if (frames > 0) runCatching {
            await(b.getStack(p.sessionId, p.vmId, 0, frames), timeouts.requestMillis).frames
        }.getOrNull() else null
        val top = stack?.firstOrNull() ?: p.topFrame
        if (top != null) {
            m["location"] = obj("function" to top.function.ifBlank { "?" }, "file" to top.file, "line" to top.line, "chunk" to top.chunkName)
            if (top.file != null) put(m, "source", source(b, top.file, top.line, 2))
        }
        if (!stack.isNullOrEmpty()) m["stack"] = stack.map { ResultJson.frame(it) }
        if (includeLocals && top != null) {
            val locals = runCatching {
                await(b.getVariables(VariablesRequest(top.frameId, VariableScope.LOCALS, null, null, 1, 20)), timeouts.requestMillis)
            }.getOrNull()
            if (locals != null) put(m, "locals", ResultJson.fit(locals.variables, 1500) { ResultJson.value(it) }.first)
        }
        return m
    }

    private fun source(b: LuaDebugBackend, file: String, line: Int, radius: Int): List<Map<String, Any?>>? =
        b.sourceSnippet(file, line, radius)?.map { obj("line" to it.line, "text" to it.text.trimEnd(), "current" to (it.line == line)) }

    // ======================================================================= lua_debug_breakpoints

    private val bpActions = arrayOf("list", "add", "remove", "enable", "clear")

    private fun breakpointsTool() = McpTool(
        name = BREAKPOINTS,
        title = "Lua breakpoints",
        titleZh = "Lua 断点",
        description = ToolGuide(
            what = "Lists, adds, removes, enables and clears Lua breakpoints in the IDE (they show in the editor, marked as set by the agent): " +
                "file+line, condition, hit count, logpoints (print a message and keep running) and probes (capture values and keep running).",
            useWhen = "you want the game to stop where a suspect Lua line runs, only under a condition (hp <= 0), log values without stopping " +
                "(message 'hp={self.hp}'), or record values on every hit without freezing the game (capture=['self.hp'] + auto_continue=true, " +
                "read with $INSPECT action=probes or $SESSION action=wait).",
            insteadOf = "editing scripts to add print() or asserts and reloading them.",
            notFor = "C++ / Blueprint. clear removes only breakpoints set by these tools unless scope=all; the user's own breakpoints are " +
                "listed too (no byAgent), leave them alone. The first change asks the user in the IDE.",
            results = "items with id (for remove/enable), file, line, condition, hitCount, message, capture, byAgent, verified (false = script " +
                "not loaded yet or no code on that line; it binds when the script loads).",
        ).render(),
        inputSchema = schema {
            string("action", "What to do.", required = true, enum = bpActions.toList())
            string("file", "Lua file (absolute or relative to the project).")
            integer("line", "1-based line.", minimum = 1)
            string("condition", "add: Lua condition, e.g. hp <= 0.")
            integerOrString("hit_count", "add: stop on the Nth hit (5), or '>=3', '%2' (every 2nd hit).")
            string("message", "add: logpoint, prints e.g. 'hp={self.hp}' to the debug output and keeps running.")
            stringArray("capture", "add: value paths recorded on each hit, e.g. ['self.hp', 'item.id'] (probe).", 16)
            boolean("auto_continue", "add: keep running after a hit (with capture = probe that never freezes the game).")
            string("vm", "add: only in this Lua VM (default: all VMs).")
            string("id", "remove/enable: breakpoint id from list.")
            stringArray("ids", "remove/enable: several ids from list.", 64)
            boolean("enabled", "enable: true (default) or false.")
            string("scope", "list: agent / all (default all). clear: agent (default, only breakpoints set through these tools) or all.",
                enum = listOf("agent", "all"))
            integer("limit", "list: max items (default 50).", minimum = 1, maximum = 200)
            integer("offset", "list: items to skip (use nextOffset from the previous call).", minimum = 0)
        },
        level = PermissionLevel.CONTROL,
    ) { call ->
        guarded {
        val b = requireBackend(backend())
        when (call.args.choice("action", null, *bpActions)) {
            "list" -> listBreakpoints(b, call.args)
            "add" -> addBreakpoint(b, call)
            "remove" -> removeBreakpoints(b, call)
            "enable" -> enableBreakpoints(b, call)
            "clear" -> clearBreakpoints(b, call)
            else -> throw ToolArgumentException("缺少参数 'action'")
        }
        }
    }

    private fun listBreakpoints(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val origin = if (args.choice("scope", "all", "agent", "all") == "agent") BreakpointOrigin.AGENT else null
        val all = b.listBreakpoints(origin)
        val limit = args.int("limit", 50, 1, 200)
        val offset = args.int("offset", 0, 0, Int.MAX_VALUE)
        return page(all.drop(offset).take(limit), offset, all.size > offset + limit, all.size) { ResultJson.breakpoint(it) }
    }

    private fun addBreakpoint(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val a = call.args
        val file = a.requireString("file")
        val line = a.requireInt("line", 1, Int.MAX_VALUE)
        val hit = when (val v = a.raw["hit_count"]) {
            null -> null
            is Number -> v.toInt().also { if (it < 1) throw ToolArgumentException("'hit_count' 从 1 开始") }.toString()
            is String -> v.trim().ifEmpty { null }
            else -> throw ToolArgumentException("'hit_count' 应为整数或字符串")
        }
        requireBreakpointControl(call)
        val bp = await(b.setBreakpoint(BreakpointSpec(
            file = file,
            line = line,
            condition = a.string("condition")?.takeIf { it.isNotBlank() },
            hitCondition = hit,
            logMessage = a.string("message")?.takeIf { it.isNotBlank() },
            capture = a.stringList("capture", 16),
            autoContinue = a.bool("auto_continue", false),
            vmId = a.string("vm")?.takeIf { it.isNotBlank() },
        )), timeouts.requestMillis)
        val m = LinkedHashMap(ResultJson.breakpoint(bp))
        if (!bp.verified) {
            m["hint"] = bp.message ?: if (b.listSessions().isEmpty()) "还没有调试会话，附加后自动下发。" else "脚本还没加载，加载后自动生效。"
        }
        return m
    }

    private fun filterFrom(a: JsonArgs, action: String): BreakpointFilter {
        val id = a.string("id")?.takeIf { it.isNotBlank() }
        val ids = a.stringList("ids", 64)
        val file = a.string("file")?.takeIf { it.isNotBlank() }
        val line = a.int("line", 1, Int.MAX_VALUE)
        if (id == null && ids.isEmpty() && file == null) throw ToolArgumentException("$action 要给 'id'、'ids' 或 'file'（+ 'line'）")
        if (line != null && file == null) throw ToolArgumentException("'line' 要和 'file' 一起用")
        return BreakpointFilter(breakpointId = id, breakpointIds = ids, file = file, line = line)
    }

    private fun removeBreakpoints(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val filter = filterFrom(call.args, "remove")
        requireBreakpointControl(call)
        val n = await(b.removeBreakpoints(filter), timeouts.requestMillis)
        val m = LinkedHashMap<String, Any?>()
        m["removed"] = n
        if (n == 0) m["note"] = "没有匹配的断点，用 action=list 查看 id。"
        return m
    }

    private fun enableBreakpoints(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val filter = filterFrom(call.args, "enable")
        val enabled = call.args.bool("enabled", true)
        requireBreakpointControl(call)
        val n = await(b.enableBreakpoints(filter, enabled), timeouts.requestMillis)
        val m = LinkedHashMap<String, Any?>()
        m["enabled"] = enabled
        m["changed"] = n
        if (n == 0) m["note"] = "没有匹配的断点，用 action=list 查看 id。"
        return m
    }

    private fun clearBreakpoints(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val all = call.args.choice("scope", "agent", "agent", "all") == "all"
        requireBreakpointControl(call)
        val n = await(b.removeBreakpoints(BreakpointFilter(all = true, includeIde = all)), timeouts.requestMillis)
        val m = obj("scope" to if (all) "all" else "agent")
        m["removed"] = n
        return m
    }

    // ======================================================================= lua_debug_inspect

    private val inspectActions = arrayOf("stack", "vms", "locals", "eval", "set", "output", "probes", "profile")

    private fun inspectTool() = McpTool(
        name = INSPECT,
        title = "Inspect stopped Lua (stack, variables, output)",
        titleZh = "Lua 调试：栈和变量",
        description = ToolGuide(
            what = "Reads a stopped Lua VM in the IDE debugger: call stack with source lines, locals / upvalues / globals as a bounded tree, " +
                "value paths (self.items[3].id, #list) and, if allowed, any Lua expression; also debug output (print, Lua errors, " +
                "logpoints), probe records and a sampling profile, which work while the game runs.",
            useWhen = "$SESSION reports event=break: see where and why, read variables and table fields (depth 1-3, ref to expand), " +
                "evaluate value paths, set a value to test a fix (set), switch frames with frame=N; output / probes any time; profile while a slow scene runs.",
            insteadOf = "guessing values or adding print / os.clock() timers: reads the live state without changing scripts.",
            notFor = "a running VM (stack / locals / eval need event=break). mode=lua needs a one-time user confirmation per session (the user may have turned it off): prefer value " +
                "paths. Refs die when the VM continues. profile counts Lua instructions, not time in C/engine calls; Lua runs ~2x slower meanwhile.",
            results = "stack: frames[{i,function,file,line}]; locals/eval: value nodes {n,t,v,c,more,ref,err}; output/probes: items + next; " +
                "profile: samples + items[{fn,file,line,selfPct,totalPct}].",
        ).render(),
        inputSchema = schema {
            string("action", "What to read.", required = true, enum = inspectActions.toList())
            string("session_id", "Session (optional when there is only one, or only one is stopped).")
            string("vm", "Lua VM id (default: the stopped VM).")
            integer("frame", "Stack frame, 0 = top (default 0).", minimum = 0)
            string("scope", "locals: locals (default) / upvalues / globals. set: where a bare name lives (default: local, then upvalue).",
                enum = listOf("locals", "upvalues", "globals"))
            string("ref", "locals: expand this value (ref from a previous result) instead of a scope.")
            string("path", "locals: start from this value path inside the scope, e.g. self.items[3]. set: the variable to change (hp, self.hp, items[3]).")
            string("value", "set: new value; value_path mode: a literal (100, \"a\", true, nil) or another value path; mode=lua: any Lua expression.")
            string("filter", "locals: keep variables whose name contains it; output: keep lines containing it.")
            integer("depth", "locals/eval: table levels to expand (0-3, default 1).", minimum = 0, maximum = 3)
            string("expr", "eval: value path (self.hp, items[3].id, #list) or, with mode=lua, a Lua expression.")
            stringArray("exprs", "eval: several expressions (watch list).", 10)
            string("mode", "eval/set: value_path (default) or lua (arbitrary Lua; needs the user's permission).",
                enum = listOf("value_path", "lua"))
            integer("cursor", "output/probes: 'next' of the previous call, returns only newer items.", minimum = 0)
            integer("max_chars", "output: max characters (default 3000, newest kept).", minimum = 200, maximum = 10000)
            string("breakpoint", "probes: only this breakpoint id.")
            integer("duration_ms", "profile: how long to sample while the game keeps running (default 3000).", minimum = 100, maximum = 60000)
            string("sort", "profile: self (default, time in the function itself) / total (including callees).", enum = listOf("self", "total"))
            integer("limit", "stack: frames (default 20); locals: items per level (default 50); probes: records (default 50); " +
                "profile: functions (default 30).", minimum = 1, maximum = 200)
            integer("offset", "stack: frames to skip (use nextOffset).", minimum = 0)
        },
        level = PermissionLevel.READ,
    ) { call ->
        guarded {
        val b = requireBackend(backend())
        when (call.args.choice("action", null, *inspectActions)) {
            "stack" -> stack(b, call.args)
            "vms" -> vms(b, call.args)
            "locals" -> locals(b, call.args)
            "eval" -> eval(b, call)
            "set" -> set(b, call)
            "output" -> output(b, call.args)
            "probes" -> probes(b, call.args)
            "profile" -> profile(b, call.args)
            else -> throw ToolArgumentException("缺少参数 'action'")
        }
        }
    }

    private fun stack(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val p = requirePause(b, args)
        val limit = args.int("limit", 20, 1, 200)
        val offset = args.int("offset", 0, 0, Int.MAX_VALUE)
        val page = await(b.getStack(p.sessionId, p.vmId, offset, limit), timeouts.requestMillis)
        val hasMore = page.total?.let { it > offset + page.frames.size } ?: (page.frames.size == limit)
        val m = page(page.frames, offset, hasMore, page.total, ResultJson.MAX_BUDGET) { ResultJson.frame(it) }
        val result = obj("sessionId" to p.sessionId, "vm" to p.vmId)
        result.putAll(m)
        val top = page.frames.firstOrNull()
        if (offset == 0 && top?.file != null) put(result, "source", source(b, top.file, top.line, 3))
        return result
    }

    private fun vms(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val s = resolveSession(b, args)
        val list = b.listVms(s.sessionId)
        val m = obj("sessionId" to s.sessionId, "count" to list.size)
        m["items"] = list.map { ResultJson.vm(it) }
        if (list.isEmpty()) m["note"] = "还没有 VM 注册（游戏可能还没创建 lua_State）。"
        return m
    }

    private fun frameId(b: LuaDebugBackend, p: PauseInfo, args: JsonArgs): String {
        val frame = args.int("frame", 0, 0, Int.MAX_VALUE)
        if (frame == 0 && p.topFrame != null) return p.topFrame.frameId
        val page = await(b.getStack(p.sessionId, p.vmId, frame, 1), timeouts.requestMillis)
        return page.frames.firstOrNull()?.frameId
            ?: throw ToolArgumentException("没有第 $frame 帧" + (page.total?.let { "（共 $it 帧）" } ?: ""))
    }

    private fun locals(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val ref = args.string("ref")?.takeIf { it.isNotBlank() }
        val depth = args.int("depth", 1, 0, 3)
        val limit = args.int("limit", 50, 1, 200)
        val filter = args.string("filter")?.takeIf { it.isNotBlank() }
        val request = if (ref != null) {
            if (args.has("scope") || args.has("frame")) throw ToolArgumentException("'ref' 不和 'scope' / 'frame' 一起用")
            VariablesRequest(null, null, ref, args.string("path")?.takeIf { it.isNotBlank() }, depth.coerceAtLeast(1), limit)
        } else {
            val p = requirePause(b, args)
            val scope = when (args.choice("scope", "locals", "locals", "upvalues", "globals")) {
                "upvalues" -> VariableScope.UPVALUES
                "globals" -> VariableScope.GLOBALS
                else -> VariableScope.LOCALS
            }
            VariablesRequest(frameId(b, p, args), scope, null, args.string("path")?.takeIf { it.isNotBlank() }, depth.coerceAtLeast(1), limit)
        }
        val page = await(b.getVariables(request), timeouts.requestMillis)
        val vars = if (filter == null) page.variables else page.variables.filter { it.name.contains(filter, ignoreCase = true) }
        val (items, truncated) = ResultJson.fit(vars, ResultJson.MAX_BUDGET) { v ->
            if (depth == 0) ResultJson.value(v.copy(children = null)) else ResultJson.value(v)
        }
        val m = obj("scope" to request.scope?.name?.lowercase(), "ref" to ref, "count" to items.size)
        m["count"] = items.size
        page.total?.let { if (it > page.variables.size) m["more"] = it - page.variables.size }
        put(m, "truncated", truncated)
        m["items"] = items
        return m
    }

    private fun eval(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val a = call.args
        val exprs = buildList {
            a.string("expr")?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(a.stringList("exprs", 10))
        }
        if (exprs.isEmpty()) throw ToolArgumentException("eval 要给 'expr' 或 'exprs'")
        if (exprs.size > 10) throw ToolArgumentException("一次最多 10 个表达式")
        val mode = if (a.choice("mode", "value_path", "value_path", "lua") == "lua") EvalMode.LUA else EvalMode.VALUE_PATH
        val p = requirePause(b, a)
        if (mode == EvalMode.LUA) {
            if (!policy.luaEvalAllowed()) {
                throw ToolException(
                    "eval_forbidden",
                    "用户在设置里关掉了执行任意 Lua（防止 Agent 改动游戏状态）。改用 mode=value_path 读变量，" +
                        "或请用户在 设置 > 工具 > EmmyLua 调试 里打开「允许 Agent 执行任意 Lua」。",
                )
            }
            // 任意 Lua 可能改状态，按控制处理
            requireControl(b.listSessions().first { it.sessionId == p.sessionId }, call)
        }
        val frameId = frameId(b, p, a)
        val results = exprs.map { e ->
            try {
                val v = await(b.evaluate(EvaluateRequest(frameId, e, mode)), timeouts.requestMillis)
                ResultJson.value(v.copy(name = e))
            } catch (ex: LuaDebugBackendException) {
                if (ex.code == ErrorCode.STALE_REFERENCE || ex.code == ErrorCode.NOT_PAUSED) throw ex
                obj("n" to e, "err" to (ex.message ?: ex.code.name))
            }
        }
        val (items, truncated) = ResultJson.fit(results, ResultJson.MAX_BUDGET) { it }
        val m = obj("truncated" to truncated)
        m["results"] = items
        return m
    }

    /** 改变量：会改游戏状态，按控制处理（每个会话第一次弹确认）；mode=lua 还要设置里允许执行任意 Lua。 */
    private fun set(b: LuaDebugBackend, call: ToolCall): Map<String, Any?> {
        val a = call.args
        val path = a.string("path")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolArgumentException("set 要给 'path'（要改的变量，例如 self.hp）")
        val value = a.string("value")?.takeIf { it.isNotBlank() } ?: throw ToolArgumentException("set 要给 'value'（新值，例如 100、\"text\"、nil）")
        ValuePaths.splitLast(path) ?: throw ToolArgumentException("'$path' 不是能改的值路径：写成 hp、self.hp、items[3] 或 cfg[\"key\"]")
        val mode = if (a.choice("mode", "value_path", "value_path", "lua") == "lua") EvalMode.LUA else EvalMode.VALUE_PATH
        val scope = when (a.string("scope")?.takeIf { it.isNotBlank() }?.let { a.choice("scope", null, "locals", "upvalues", "globals") }) {
            "locals" -> VariableScope.LOCALS
            "upvalues" -> VariableScope.UPVALUES
            "globals" -> VariableScope.GLOBALS
            else -> null
        }
        val p = requirePause(b, a)
        if (mode == EvalMode.LUA && !policy.luaEvalAllowed()) {
            throw ToolException(
                "eval_forbidden",
                "用户在设置里关掉了执行任意 Lua。新值改用字面量或值路径（不带 mode=lua），或请用户在 设置 > 工具 > EmmyLua 调试 里打开「允许 Agent 执行任意 Lua」。",
            )
        }
        requireControl(b.listSessions().first { it.sessionId == p.sessionId }, call)
        val frameId = frameId(b, p, a)
        val v = await(b.setVariable(SetVariableRequest(frameId, path, value, mode, scope)), timeouts.requestMillis)
        val m = obj("ok" to true)
        m["value"] = ResultJson.value(v.copy(name = path))
        return m
    }

    private fun output(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val sessionId = args.string("session_id")?.takeIf { it.isNotBlank() }
        val cursor = args.int("cursor", 0, 0, Int.MAX_VALUE).toLong()
        val maxChars = args.int("max_chars", 3000, 200, 10000)
        val filter = args.string("filter")?.takeIf { it.isNotBlank() }
        // 不带 cursor 时取最后 200 条（和 UEToVS 一样），带上次的 next 只取新增
        val page = b.getOutput(sessionId, cursor, if (cursor == 0L) 200 else 1000)
        val entries = page.entries.let { if (cursor == 0L) it.takeLast(200) else it }
            .filter { filter == null || it.text.contains(filter, ignoreCase = true) }
        val (items, truncated) = ResultJson.fitTail(entries, maxChars) { ResultJson.output(it) }
        val m = obj("count" to items.size, "truncated" to truncated)
        m["count"] = items.size
        m["next"] = page.nextSeq
        m["items"] = items
        return m
    }

    private fun probes(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val page = b.getProbeHits(
            args.string("breakpoint")?.takeIf { it.isNotBlank() },
            args.int("cursor", 0, 0, Int.MAX_VALUE).toLong(),
            args.int("limit", 50, 1, 200),
        )
        val (items, truncated) = ResultJson.fit(page.hits, ResultJson.MAX_BUDGET) { ResultJson.probeHit(it) }
        val m = obj("truncated" to truncated)
        m["count"] = items.size
        m["next"] = if (truncated) (page.hits[items.size - 1].seq) else page.nextSeq
        m["items"] = items
        return m
    }

    /**
     * 采样 duration_ms 后返回函数表。按只读处理（不弹控制确认）：采样不暂停、不改变量、不改执行路径，
     * 和 output / probes 一样只观察；代价是采样期间 Lua 变慢，所以限时（≤ 60 秒）并且 runtime 到点自动停。
     */
    private fun profile(b: LuaDebugBackend, args: JsonArgs): Map<String, Any?> {
        val s = resolveSession(b, args)
        val vm = args.string("vm")?.takeIf { it.isNotBlank() }
        val duration = args.int("duration_ms", 3000, 100, 60000)
        val sort = if (args.choice("sort", "self", "self", "total") == "total") ProfileSort.TOTAL else ProfileSort.SELF
        val limit = args.int("limit", 30, 1, 200)
        if (vm != null && b.listVms(s.sessionId).none { it.vmId == vm }) {
            throw ToolException("no_vm", "没有 VM $vm。用 $INSPECT action=vms 查看 VM。")
        }
        await(b.startProfile(s.sessionId, vm, duration), timeouts.requestMillis)
        try {
            Thread.sleep(duration.toLong() + 100) // runtime 按 duration_ms 自己停，这里多等一点保证停了再取
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ToolException("cancelled", "采样被中断（IDE 正在关闭？）")
        }
        val report = await(b.stopProfile(s.sessionId, vm, sort, limit), timeouts.requestMillis)
        val (items, truncated) = ResultJson.fit(report.functions, ResultJson.BUDGET) { ResultJson.profileFunction(it) }
        val m = obj("sessionId" to s.sessionId, "vm" to vm, "samples" to report.samples, "durationMs" to report.durationMs,
            "sort" to sort.name.lowercase())
        val hasMore = report.totalFunctions > items.size
        m["count"] = items.size
        m["offset"] = 0
        m["total"] = report.totalFunctions
        m["hasMore"] = hasMore
        if (truncated) m["truncated"] = true
        m["items"] = items
        when {
            report.samples == 0L -> m["note"] = "没有采到样本：采样期间这个 VM 没执行 Lua（游戏在暂停、停在断点上，或这段时间没跑脚本）。" +
                "先 $SESSION action=continue，并在采样期间触发要分析的场景。"
            hasMore -> m["note"] = "只列出了前 ${items.size} 个函数；要更多用更大的 limit 重新采样（每次采样结果独立）。"
        }
        return m
    }

    // ======================================================================= 公共

    /** 分页信封：{count, offset, total?, hasMore, nextOffset?, truncated?, items}（照 UEToVS 的 Page）。 */
    private fun <T> page(
        list: List<T>,
        offset: Int,
        hasMore: Boolean,
        total: Int?,
        budget: Int = ResultJson.BUDGET,
        render: (T) -> Any?,
    ): Map<String, Any?> {
        val (items, truncated) = ResultJson.fit(list, budget, render)
        val more = hasMore || truncated
        val m = LinkedHashMap<String, Any?>()
        m["count"] = items.size
        m["offset"] = offset
        if (total != null) m["total"] = total
        m["hasMore"] = more
        if (more) m["nextOffset"] = offset + items.size
        if (truncated) m["truncated"] = true
        m["items"] = items
        return m
    }

    private fun requireBackend(b: LuaDebugBackend?): LuaDebugBackend =
        b ?: throw ToolException("engine_unavailable", ENGINE_UNAVAILABLE)

    private fun requireControl(session: SessionInfo, call: ToolCall) {
        policy.checkControl(session.sessionId, "调试会话 ${session.target}", call.client.clientName)?.let { throw denied(it) }
    }

    private fun requireBreakpointControl(call: ToolCall) {
        policy.checkControl(McpPermissionPolicy.BREAKPOINTS_KEY, "IDE 断点", call.client.clientName)?.let { throw denied(it) }
    }

    private fun denied(message: String) = ToolException("permission_denied", message)

    private fun resolveSession(b: LuaDebugBackend, args: JsonArgs): SessionInfo {
        val sessions = b.listSessions()
        val id = args.string("session_id")?.takeIf { it.isNotBlank() }
        if (id != null) return sessions.firstOrNull { it.sessionId == id } ?: throw noSuchSession(b, id)
        return when (sessions.size) {
            0 -> throw ToolException("no_session", "没有调试会话：先 $SESSION action=targets + action=attach，或请用户在 IDE 里启动调试。")
            1 -> sessions[0]
            // 多个会话时，只有一个停着的就用它
            else -> sessions.filter { it.pausedVmIds.isNotEmpty() }.singleOrNull()
                ?: throw ToolArgumentException("有多个调试会话，请传 'session_id'：" + sessions.joinToString("；") { "${it.sessionId}（${it.target}）" })
        }
    }

    /** 需要停着的命令：找到对应的暂停（session_id / vm 可省略）。 */
    private fun requirePause(b: LuaDebugBackend, args: JsonArgs): PauseInfo {
        val s = resolveSession(b, args)
        val vm = args.string("vm")?.takeIf { it.isNotBlank() }
        val pauses = b.currentPauses().filter { it.sessionId == s.sessionId && (vm == null || it.vmId == vm) }
        return pauses.firstOrNull() ?: throw ToolException(
            "not_paused",
            "${if (vm != null) "VM $vm" else "会话 ${s.target}"} 没有停下：先 $BREAKPOINTS action=add 下断点再 $SESSION action=wait，或 $SESSION action=pause。",
        )
    }

    private fun noSuchSession(b: LuaDebugBackend, id: String): ToolException {
        val sessions = b.listSessions()
        return ToolException(
            "no_session",
            "没有调试会话 $id（可能已结束）。" +
                if (sessions.isEmpty()) "当前没有会话。" else "当前会话：" + sessions.joinToString("；") { "${it.sessionId}（${it.target}）" },
        )
    }

    private fun <T> await(future: CompletableFuture<T>, timeoutMillis: Long): T {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw ToolException("timeout", "调试引擎 ${timeoutMillis / 1000} 秒内没有响应（目标进程可能卡住或已断开），用 $SESSION action=status 查看。")
        } catch (e: ExecutionException) {
            throw translate(e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ToolException("cancelled", "请求被中断（IDE 正在关闭？）")
        }
    }

    /** 引擎错误 → 工具错误码 + 带恢复提示的中文说明。 */
    private fun translate(e: Throwable): Throwable {
        if (e is ToolException) return e
        if (e !is LuaDebugBackendException) return e
        val hint = when (e.code) {
            ErrorCode.STALE_REFERENCE -> "游戏已继续运行，引用失效：先 $SESSION action=wait 或 $INSPECT action=stack 取新的。"
            ErrorCode.NOT_PAUSED -> "先下断点并 $SESSION action=wait，或 $SESSION action=pause。"
            ErrorCode.NO_SESSION -> "用 $SESSION action=status 查看当前会话。"
            ErrorCode.NO_VM -> "用 $INSPECT action=vms 查看 VM。"
            ErrorCode.EVAL_FORBIDDEN -> "改用 mode=value_path，或请用户在设置里允许执行任意 Lua。"
            ErrorCode.NOT_SUPPORTED -> "目标的调试 runtime 不支持，可能需要升级 runtime。"
            else -> null
        }
        val msg = e.message.orEmpty().trim()
        return ToolException(e.code.name.lowercase(), if (hint == null || msg.contains(hint)) msg else "$msg $hint".trim())
    }

    /** 直接调用（不经 future）的引擎方法抛出的异常也翻译成工具错误。 */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: LuaDebugBackendException) {
        throw translate(e)
    }

    companion object {
        const val SESSION = "lua_debug_session"
        const val BREAKPOINTS = "lua_debug_breakpoints"
        const val INSPECT = "lua_debug_inspect"

        private val MODULE_NAME = Regex("[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*")

        const val ENGINE_UNAVAILABLE = "IDE 里的 Lua 调试引擎还没准备好（没有打开的项目或插件版本过旧）。请用户打开 Lua 项目后重试。"
    }
}

internal sealed class WaitOutcome {
    data class Paused(val pause: PauseInfo) : WaitOutcome()
    data class Probe(val hit: ProbeHit) : WaitOutcome()
    data class Resumed(val sessionId: String, val vmId: String?) : WaitOutcome()
    data class Ended(val sessionId: String, val reason: String?) : WaitOutcome()
    object Timeout : WaitOutcome()
}

/** 订阅引擎事件并按条件等待。创建即订阅，close 退订。 */
internal class PauseWaiter(private val backend: LuaDebugBackend, private val client: McpClientSession) : AutoCloseable {
    private val queue = LinkedBlockingQueue<DebugEvent>()
    private val subscription = backend.addListener { queue.offer(it) }

    /** 已经停着、但这个客户端还没看过的暂停。 */
    fun unseenPause(sessionId: String?): PauseInfo? =
        backend.currentPauses().firstOrNull { p ->
            (sessionId == null || p.sessionId == sessionId) && p.pauseId !in client.seenPauseIds
        }

    fun await(sessionId: String?, timeoutMillis: Long, includeProbe: Boolean, stopOnResume: Boolean): WaitOutcome {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return WaitOutcome.Timeout
            val event = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: return WaitOutcome.Timeout
            if (sessionId != null && event.sessionId != sessionId) continue
            when (event) {
                is DebugEvent.Paused -> if (event.pause.pauseId !in client.seenPauseIds) return WaitOutcome.Paused(event.pause)
                is DebugEvent.ProbeHitEvent -> if (includeProbe) return WaitOutcome.Probe(event.hit)
                is DebugEvent.Resumed -> if (stopOnResume && event.byUser) return WaitOutcome.Resumed(event.sessionId, event.vmId)
                is DebugEvent.SessionEnded -> return WaitOutcome.Ended(event.sessionId, event.reason)
                else -> {}
            }
        }
    }

    override fun close() = subscription.close()
}
