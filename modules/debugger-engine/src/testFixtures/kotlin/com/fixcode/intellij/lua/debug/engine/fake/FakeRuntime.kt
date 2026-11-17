package com.fixcode.intellij.lua.debug.engine.fake

import com.google.gson.JsonObject
import com.fixcode.intellij.lua.debug.protocol.BreakpointAck
import com.fixcode.intellij.lua.debug.protocol.BreakpointHitEvent
import com.fixcode.intellij.lua.debug.protocol.BreakpointSpec
import com.fixcode.intellij.lua.debug.protocol.BreakpointStateNames
import com.fixcode.intellij.lua.debug.protocol.BreakpointStatusEvent
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.CaptureValue
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.DisconnectArgs
import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.EvalModes
import com.fixcode.intellij.lua.debug.protocol.EvaluateArgs
import com.fixcode.intellij.lua.debug.protocol.Events
import com.fixcode.intellij.lua.debug.protocol.FrameCodec
import com.fixcode.intellij.lua.debug.protocol.FrameDecoder
import com.fixcode.intellij.lua.debug.protocol.HelloArgs
import com.fixcode.intellij.lua.debug.protocol.JsonDto
import com.fixcode.intellij.lua.debug.protocol.LoadHelpersArgs
import com.fixcode.intellij.lua.debug.protocol.LuaInfo
import com.fixcode.intellij.lua.debug.protocol.MessageCodec
import com.fixcode.intellij.lua.debug.protocol.OutputCategories
import com.fixcode.intellij.lua.debug.protocol.OutputEvent
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import com.fixcode.intellij.lua.debug.protocol.PausedEvent
import com.fixcode.intellij.lua.debug.protocol.ProcessInfo
import com.fixcode.intellij.lua.debug.protocol.ProfileActions
import com.fixcode.intellij.lua.debug.protocol.ProfileArgs
import com.fixcode.intellij.lua.debug.protocol.ProfileFunctionDto
import com.fixcode.intellij.lua.debug.protocol.ProfileResult
import com.fixcode.intellij.lua.debug.protocol.ProfileSorts
import com.fixcode.intellij.lua.debug.protocol.ProfileStackDto
import com.fixcode.intellij.lua.debug.protocol.ProfileStartResult
import com.fixcode.intellij.lua.debug.protocol.ProtocolMessage
import com.fixcode.intellij.lua.debug.protocol.ResumedBy
import com.fixcode.intellij.lua.debug.protocol.ResumedEvent
import com.fixcode.intellij.lua.debug.protocol.RunToLineArgs
import com.fixcode.intellij.lua.debug.protocol.RuntimeInfo
import com.fixcode.intellij.lua.debug.protocol.ScopeDto
import com.fixcode.intellij.lua.debug.protocol.ScopesArgs
import com.fixcode.intellij.lua.debug.protocol.ScopesResult
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsResult
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import com.fixcode.intellij.lua.debug.protocol.ThreadInfo
import com.fixcode.intellij.lua.debug.protocol.ThreadStatuses
import com.fixcode.intellij.lua.debug.protocol.ThreadsResult
import com.fixcode.intellij.lua.debug.protocol.SetViewRulesArgs
import com.fixcode.intellij.lua.debug.protocol.SetViewRulesResult
import com.fixcode.intellij.lua.debug.protocol.SetVariableArgs
import com.fixcode.intellij.lua.debug.protocol.SourceRef
import com.fixcode.intellij.lua.debug.protocol.StackFrameDto
import com.fixcode.intellij.lua.debug.protocol.StackTraceArgs
import com.fixcode.intellij.lua.debug.protocol.StackTraceResult
import com.fixcode.intellij.lua.debug.protocol.ValueResult
import com.fixcode.intellij.lua.debug.protocol.VariableDto
import com.fixcode.intellij.lua.debug.protocol.VariablesArgs
import com.fixcode.intellij.lua.debug.protocol.VariablesResult
import com.fixcode.intellij.lua.debug.protocol.VmClosedEvent
import com.fixcode.intellij.lua.debug.protocol.VmInfo
import com.fixcode.intellij.lua.debug.protocol.VmStateChangedEvent
import com.fixcode.intellij.lua.debug.protocol.VmStates
import com.fixcode.intellij.lua.debug.protocol.Welcome
import com.fixcode.intellij.lua.debug.protocol.jsonObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 用 Kotlin 写的协议 v3 假 runtime（设计文档第 11 节「插件端集成测试用假 runtime」）。
 *
 * 在本机回环端口上监听，按 protocol-v3.md 回应 IDE；测试用 [hit] 模拟「某个 VM 执行到某一行」，
 * 由它决定是否命中断点、是否暂停、是否发日志 / Probe 事件。行为尽量和真 runtime 一致，
 * 尤其是那些旧实现踩过的约束：控制命令必须带 pauseId、断线后恢复所有 VM、临时断点在任何暂停时清除。
 *
 * 线程：IDE 的请求在连接读线程上处理，测试线程调 [hit] 等方法；状态都在 [lock] 下修改。
 */
class FakeRuntime(
    val caps: List<String> = ALL_CAPS,
    val platform: String = "windows",
    val pid: Long = 4242,
    /** 非空时 hello 必须带这个 token。 */
    val token: String? = null
) : AutoCloseable {
    private val lock = Object()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "FakeRuntime-timer").apply { isDaemon = true } }
    @Volatile
    private var closed = false

    val port: Int get() = server.localPort

    // ---------- 可调参数 ----------

    /** setBreakpoints 的确认延迟：模拟断点表在别的线程上异步重建，确认前新断点不生效。 */
    @Volatile
    var breakpointAckDelayMillis: Long = 0

    /** 不回 ping，模拟网络假死。 */
    @Volatile
    var ignorePings: Boolean = false

    /** 接下来几次握手回 BUSY（模拟上一个 IDE 连接还没释放）。 */
    @Volatile
    var busyHandshakes: Int = 0

    /** 让下一个某命令返回指定错误。 */
    private val injectedErrors = HashMap<String, Pair<String, String>>()

    // ---------- runtime 状态（lock 保护） ----------
    private var client: ClientConnection? = null
    private val vms = LinkedHashMap<Long, FakeVm>()
    private var nextVmId = 1L
    private val breakpoints = LinkedHashMap<String, BreakpointSpec>()
    private var revision = 0L
    private val hitCounts = HashMap<Pair<String, Long>, Long>()
    private val statusSent = HashSet<Triple<String, Long, String>>()
    private var options = SetOptionsArgs()
    private var configured = false
    private var entryPending = false
    private var nextObjectId = 1000L
    private val frames = HashMap<Long, Pair<FakeVm, FakeFrame>>()
    private val refs = HashMap<Long, Pair<FakeVm, LinkedHashMap<String, FakeValue>>>()
    var helpers: LoadHelpersArgs? = null
    /** 最近一次 setViewRules 收到的规则。 */
    @Volatile
    var viewRules: com.google.gson.JsonObject? = null
        private set
    var connections: Int = 0
        private set

    /** IDE 发来的全部请求（按到达顺序），断言用。 */
    val requests: MutableList<ProtocolMessage.Request> = CopyOnWriteArrayList()

    /** runtime 发出的全部消息（按发送顺序）。 */
    val sent: MutableList<ProtocolMessage> = CopyOnWriteArrayList()

    inner class FakeVm(val vmId: Long, var name: String) {
        var paused = false
            internal set
        var pauseId = 0L
            internal set
        var threadId = "t$vmId"
        var sourceEpoch = 1L
        internal var stepping: String? = null
        internal var runToLine: Pair<SourceRef, Int>? = null
        internal var pausePending = false
        internal var stack: List<FakeFrame> = emptyList()
        /** 暂停时还活着的其他协程：threadId → 它的栈（threads / stackTrace{threadId} 用）。 */
        val coroutines: MutableMap<String, List<FakeFrame>> = java.util.concurrent.ConcurrentHashMap()
        /** 被 continue / 断线等恢复的记录，断言「目标没有卡在断点上」用。 */
        val resumes: MutableList<String> = CopyOnWriteArrayList()

        fun info() = VmInfo(vmId, name, if (paused) VmStates.PAUSED else VmStates.RUNNING, 1, sourceEpoch, "5.4", mainThreadId = threadId)
    }

    enum class HitResult { PAUSED, CONTINUED }

    init {
        Thread({ acceptLoop() }, "FakeRuntime-accept-$port").apply { isDaemon = true; start() }
    }

    // =====================================================================
    // 测试用 API
    // =====================================================================

    fun addVm(name: String = "GameLua"): FakeVm = synchronized(lock) {
        val vm = FakeVm(nextVmId++, name)
        vms[vm.vmId] = vm
        if (client?.handshaken == true) event(Events.VM_ADDED, vm.info())
        vm
    }

    fun closeVm(vm: FakeVm) = synchronized(lock) {
        vms.remove(vm.vmId)
        dropReferences(vm)
        event(Events.VM_CLOSED, VmClosedEvent(vm.vmId, "contextReset"))
    }

    fun resetContext(vm: FakeVm) = synchronized(lock) {
        vm.sourceEpoch++
        statusSent.removeIf { it.second == vm.vmId }
        event(Events.VM_STATE_CHANGED, VmStateChangedEvent(vm.vmId, VmStates.RUNNING, vm.sourceEpoch, "contextReset"))
    }

    fun vm(vmId: Long): FakeVm? = synchronized(lock) { vms[vmId] }

    fun breakpointTable(): Map<String, BreakpointSpec> = synchronized(lock) { LinkedHashMap(breakpoints) }

    fun options(): SetOptionsArgs = synchronized(lock) { options }

    fun isConfigured(): Boolean = synchronized(lock) { configured }

    fun hasClient(): Boolean = synchronized(lock) { client != null }

    fun failNext(command: String, code: String, message: String) = synchronized(lock) {
        injectedErrors[command] = code to message
    }

    /** 模拟网络断开（不发 disconnect）。 */
    fun dropClient() {
        val current = synchronized(lock) { client }
        current?.socket?.close()
    }

    fun print(vm: FakeVm, text: String) = synchronized(lock) {
        if (options.captureOutput == true) event(Events.OUTPUT, OutputEvent(OutputCategories.STDOUT, text, vm.vmId))
    }

    /** 直接发一个事件，用来模拟乱序 / 迟到 / 不合常理的 runtime 行为。 */
    fun sendRawEvent(name: String, body: JsonDto) = synchronized(lock) { event(name, body) }

    /** 宿主调用了 Emmy_Stop：发 terminated 然后断开。 */
    fun terminate(reason: String = "processExit") {
        synchronized(lock) { event(Events.TERMINATED, jsonObject { put("reason", reason) }) }
        dropClient()
    }

    /**
     * 模拟 [vm] 执行到 [chunkName] 的第 [line] 行。[stack] 是此刻的调用栈（栈顶在前），
     * 为空时用一个只有当前行的帧。返回是否因此暂停。
     */
    fun hit(vm: FakeVm, chunkName: String, line: Int, stack: List<FakeFrame> = emptyList()): HitResult = synchronized(lock) {
        check(!vm.paused) { "VM ${vm.vmId} 正在暂停，测试应先等 IDE 继续" }
        val frameStack = stack.ifEmpty { listOf(FakeFrame("main", chunkName, line)) }
        if (!configured) return HitResult.CONTINUED

        if (entryPending) {
            entryPending = false
            return pause(vm, frameStack, PauseReasons.ENTRY)
        }
        if (vm.pausePending) {
            vm.pausePending = false
            return pause(vm, frameStack, PauseReasons.PAUSE)
        }
        val matching = breakpoints.values.filter {
            it.function == null && (it.vmId == 0L || it.vmId == vm.vmId) && it.line == line && it.source != null && matches(it.source!!, chunkName)
        }
        matching.forEach { sendStatus(it, vm, chunkName, BreakpointStateNames.VERIFIED) }
        val pausing = processHits(vm, matching, frameStack, chunkName, line)
        if (pausing.isNotEmpty()) return pause(vm, frameStack, PauseReasons.BREAKPOINT, pausing)
        vm.runToLine?.let { (source, targetLine) ->
            if (targetLine == line && matches(source, chunkName)) return pause(vm, frameStack, PauseReasons.RUN_TO_LINE)
        }
        // 单步：下一行就停（假 runtime 不模拟栈深，足够覆盖 IDE 侧行为）；遇到断点按断点停
        if (vm.stepping != null) return pause(vm, frameStack, PauseReasons.STEP)
        HitResult.CONTINUED
    }

    /**
     * 模拟 [vm] 调用了名为 [function]（`Module.func` / `Class:Method` / 全局名，和断点原文比较）的函数，
     * 函数在 [chunkName]，第一行是 [firstLine]、定义行是 [lineDefined]。函数断点按协议 7.7 停在第一行。
     */
    fun call(vm: FakeVm, function: String, chunkName: String, firstLine: Int, lineDefined: Int = firstLine - 1,
             stack: List<FakeFrame> = emptyList()): HitResult = synchronized(lock) {
        check(!vm.paused) { "VM ${vm.vmId} 正在暂停，测试应先等 IDE 继续" }
        if (!configured) return HitResult.CONTINUED
        val frameStack = stack.ifEmpty { listOf(FakeFrame(function, chunkName, firstLine)) }
        val matching = breakpoints.values.filter {
            it.function == function && (it.vmId == 0L || it.vmId == vm.vmId) && (it.source == null || matches(it.source!!, chunkName))
        }
        matching.forEach {
            if (statusSent.add(Triple(it.id, vm.vmId, BreakpointStateNames.VERIFIED))) {
                event(Events.BREAKPOINT_STATUS, BreakpointStatusEvent(it.id, vm.vmId, BreakpointStateNames.VERIFIED, chunkName, lineDefined))
            }
        }
        val pausing = processHits(vm, matching, frameStack, chunkName, firstLine)
        if (pausing.isNotEmpty()) return pause(vm, frameStack, PauseReasons.BREAKPOINT, pausing)
        HitResult.CONTINUED
    }

    /** 断点命中后的共同处理（条件、命中次数、日志、采集）；返回要暂停的断点。 */
    private fun processHits(vm: FakeVm, matching: List<BreakpointSpec>, frameStack: List<FakeFrame>, chunkName: String, line: Int): List<String> {
        val pausing = ArrayList<String>()
        for (bp in matching) {
            if (!conditionHolds(bp, frameStack.first())) continue
            val count = hitCounts.merge(bp.id to vm.vmId, 1L, Long::plus)!!
            if (!hitConditionHolds(bp.hitCondition, count)) continue
            if (bp.logMessage != null) {
                event(Events.OUTPUT, OutputEvent(OutputCategories.LOGPOINT, interpolate(bp.logMessage!!, frameStack.first()) + "\n", vm.vmId, chunkName, line))
            }
            if (bp.autoContinue || bp.logMessage != null) {
                val captures = bp.capture.map { expr ->
                    val value = resolvePath(frameStack.first(), expr)
                    if (value == null) CaptureValue(expr, "nil", "nil", "找不到 $expr") else CaptureValue(expr, value.display(), value.type)
                }
                event(Events.BREAKPOINT_HIT, BreakpointHitEvent(bp.id, vm.vmId, count, line, vm.threadId, System.currentTimeMillis(), captures))
            } else {
                pausing += bp.id
            }
        }
        return pausing
    }

    /** 模拟出错即停（setOptions.exceptions 不为 none 时）。 */
    fun error(vm: FakeVm, chunkName: String, line: Int, message: String): HitResult = synchronized(lock) {
        if (options.exceptions == null || options.exceptions == "none") return HitResult.CONTINUED
        pause(vm, listOf(FakeFrame("main", chunkName, line)), PauseReasons.EXCEPTION, message = message)
    }

    fun awaitRequest(command: String, timeoutMillis: Long = 5_000, after: Int = 0): ProtocolMessage.Request {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            requests.drop(after).firstOrNull { it.command == command }?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("等了 $timeoutMillis ms 也没收到 $command，已收到：${requests.map { it.command }}")
    }

    fun awaitCondition(timeoutMillis: Long = 5_000, description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("等了 $timeoutMillis ms：$description")
    }

    fun commands(): List<String> = requests.map { it.command }

    override fun close() {
        closed = true
        try {
            server.close()
        } catch (_: IOException) {
        }
        dropClient()
        timer.shutdownNow()
    }

    // =====================================================================
    // 连接
    // =====================================================================

    private inner class ClientConnection(val socket: Socket) {
        private val output: OutputStream = socket.getOutputStream()
        var handshaken = false
        private var seq = 1L

        fun send(message: ProtocolMessage) {
            val bytes = FrameCodec.encode(MessageCodec.encode(message))
            synchronized(this) {
                try {
                    output.write(bytes)
                    output.flush()
                    sent += message
                } catch (_: IOException) {
                }
            }
        }

        fun nextSeq() = synchronized(this) { seq++ }
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            socket.tcpNoDelay = true
            val connection = ClientConnection(socket)
            synchronized(lock) {
                client?.socket?.close()
                client = connection
                connections++
            }
            Thread({ readLoop(connection) }, "FakeRuntime-reader").apply { isDaemon = true; start() }
        }
    }

    private fun readLoop(connection: ClientConnection) {
        val decoder = FrameDecoder()
        val buffer = ByteArray(16 * 1024)
        try {
            val input = connection.socket.getInputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (frame in decoder.feed(buffer, 0, read)) {
                    val message = MessageCodec.decode(frame)
                    if (message is ProtocolMessage.Request) handle(connection, message)
                }
            }
        } catch (_: Exception) {
        } finally {
            connection.socket.close()
            synchronized(lock) {
                if (client === connection) {
                    client = null
                    onDisconnected(ResumedBy.DISCONNECT)
                }
            }
        }
    }

    // ---------- 错误聚合（第 21 节）：测试直接推 luaError，errors 返回预置的全量 ----------

    /** errors list 返回的全量（测试预置；推送不会自动改它，和真 runtime 一样推送是限流过的） */
    @Volatile var errorList: List<com.fixcode.intellij.lua.debug.protocol.LuaErrorDto> = emptyList()
    @Volatile var errorsCleared = 0

    /** 推一条 luaError 事件（runtime 的推送）。 */
    fun pushLuaError(error: com.fixcode.intellij.lua.debug.protocol.LuaErrorDto) = synchronized(lock) {
        event(com.fixcode.intellij.lua.debug.protocol.Events.LUA_ERROR, error)
    }

    // ---------- 采样分析（第 20 节）：测试预置结果，假 runtime 不真的采样 ----------

    /** 每个 VM 的预置采样结果：函数（样本数）和折叠栈。result / stop 按 sort / limit / stackLimit 裁剪后返回。 */
    val profileData: MutableMap<Long, Pair<List<ProfileFunctionDto>, List<ProfileStackDto>>> = java.util.concurrent.ConcurrentHashMap()
    private val profiling = HashMap<Long, Int>() // vmId → intervalInstructions

    fun isProfiling(vmId: Long): Boolean = synchronized(lock) { profiling.containsKey(vmId) }

    private fun profile(request: ProtocolMessage.Request): Result {
        val args = ProfileArgs.fromJson(request.args)
        val targets = if (args.vmId != 0L) {
            listOf(vms[args.vmId] ?: return error(ErrorCodes.VM_NOT_FOUND, "Lua VM ${args.vmId} 不存在或已经关闭，请刷新 VM 列表。"))
        } else {
            vms.values.toList().ifEmpty { return error(ErrorCodes.VM_NOT_FOUND, "进程里还没有注册 Lua VM，等游戏执行 Lua 后再开始采样。") }
        }
        when (args.action) {
            ProfileActions.START -> {
                val interval = args.intervalInstructions ?: 1000
                if (interval !in 100..10_000_000) return error(ErrorCodes.INVALID_ARGS, "intervalInstructions 的范围是 100–10000000。")
                val duration = args.durationMs ?: 0
                if (duration !in 0..3_600_000) return error(ErrorCodes.INVALID_ARGS, "durationMs 的范围是 0–3600000（0 表示直到 stop）。")
                targets.forEach { profiling[it.vmId] = interval }
                return ok(ProfileStartResult(targets.map { it.vmId }, interval, duration))
            }
            ProfileActions.STOP, ProfileActions.RESULT -> {
                val sort = args.sort ?: ProfileSorts.SELF
                if (sort != ProfileSorts.SELF && sort != ProfileSorts.TOTAL) return error(ErrorCodes.INVALID_ARGS, "sort 只能是 self / total。")
                val limit = args.limit ?: 50
                if (limit !in 1..1000) return error(ErrorCodes.INVALID_ARGS, "limit 的范围是 1–1000。")
                val stackLimit = args.stackLimit ?: 500
                if (stackLimit !in 0..10000) return error(ErrorCodes.INVALID_ARGS, "stackLimit 的范围是 0–10000。")
                val interval = targets.maxOf { profiling[it.vmId] ?: 0 }
                val running = targets.any { profiling.containsKey(it.vmId) }
                if (args.action == ProfileActions.STOP) targets.forEach { profiling.remove(it.vmId) }
                val functions = targets.flatMap { profileData[it.vmId]?.first.orEmpty() }
                val stacks = targets.flatMap { profileData[it.vmId]?.second.orEmpty() }.sortedByDescending { it.samples }
                val samples = stacks.sumOf { it.samples }
                val sorted = functions.sortedByDescending { if (sort == ProfileSorts.TOTAL) it.totalSamples else it.selfSamples }
                    .map {
                        it.copy(
                            selfPercent = if (samples == 0L) 0.0 else it.selfSamples * 100.0 / samples,
                            totalPercent = if (samples == 0L) 0.0 else it.totalSamples * 100.0 / samples
                        )
                    }
                return ok(
                    ProfileResult(
                        running && args.action != ProfileActions.STOP, targets.map { it.vmId }, samples, 1000, interval,
                        sorted.take(limit), sorted.size, stacks.take(stackLimit), stacks.size, stacks.size > stackLimit, overheadMs = 0.5
                    )
                )
            }
            else -> return error(ErrorCodes.INVALID_ARGS, "profile 的 action 只能是 start / stop / result。")
        }
    }

    /** 协议第 4 节：断线（主动或异常）后清空断点、恢复所有 VM，游戏不能因为 IDE 掉线而卡住。 */
    private fun onDisconnected(by: String) {
        profiling.clear()
        breakpoints.clear()
        hitCounts.clear()
        statusSent.clear()
        revision = 0
        configured = false
        options = SetOptionsArgs()
        for (vm in vms.values) {
            vm.stepping = null
            vm.runToLine = null
            vm.pausePending = false
            if (vm.paused) resume(vm, by, notify = false)
        }
        frames.clear()
        refs.clear()
    }

    // =====================================================================
    // 请求处理（lock 下执行）
    // =====================================================================

    private fun handle(connection: ClientConnection, request: ProtocolMessage.Request) {
        requests += request
        synchronized(lock) {
            if (client !== connection) return
            injectedErrors.remove(request.command)?.let { (code, message) ->
                connection.send(ProtocolMessage.Response.failure(request.id, code, message))
                return
            }
            if (!connection.handshaken && request.command != Commands.HELLO) {
                reply(request, error(ErrorCodes.NOT_READY, "还没有完成握手，请先发送 hello。"))
                return
            }
            val result: Result = try {
                when (request.command) {
                    Commands.HELLO -> hello(connection, request)
                    Commands.LOAD_HELPERS -> {
                        helpers = LoadHelpersArgs.fromJson(request.args)
                        ok(jsonObject { put("vms", com.google.gson.JsonArray()) })
                    }
                    Commands.SET_OPTIONS -> {
                        val incoming = SetOptionsArgs.fromJson(request.args)
                        options = merge(options, incoming)
                        ok()
                    }
                    Commands.SET_VIEW_RULES -> {
                        val rules = SetViewRulesArgs.fromJson(request.args).rules
                        viewRules = rules
                        ok(SetViewRulesResult(rules.size()))
                    }
                    Commands.SET_BREAKPOINTS -> setBreakpoints(request)
                    Commands.CONFIGURATION_DONE -> {
                        configured = true
                        entryPending = options.stopOnEntry == true
                        ok()
                    }
                    Commands.VMS -> ok(jsonObject { putObjects("vms", vms.values.map { it.info() }) })
                    Commands.CONTINUE -> control(request, null, ResumedBy.CONTINUE)
                    Commands.STEP_IN, Commands.STEP_OVER, Commands.STEP_OUT -> control(request, request.command, ResumedBy.STEP)
                    Commands.RUN_TO_LINE -> runToLine(request)
                    Commands.PAUSE -> {
                        val vmId = request.args.get("vmId")?.asLong ?: 0L
                        vms.values.filter { (vmId == 0L || it.vmId == vmId) && !it.paused }.forEach { it.pausePending = true }
                        ok()
                    }
                    Commands.STACK_TRACE -> stackTrace(request)
                    Commands.THREADS -> {
                        val (vm, failure) = pausedVm(request)
                        if (vm == null) failure!! else ok(ThreadsResult(
                            listOf(ThreadInfo(vm.threadId, ThreadStatuses.RUNNING, "main")) +
                                vm.coroutines.keys.sorted().map { ThreadInfo(it, ThreadStatuses.SUSPENDED) }
                        ))
                    }
                    Commands.SCOPES -> scopes(request)
                    Commands.VARIABLES -> variables(request)
                    Commands.EVALUATE -> evaluate(request)
                    Commands.SET_VARIABLE -> setVariable(request)
                    Commands.PING -> if (ignorePings) return else ok(jsonObject { put("timeMs", System.currentTimeMillis()) })
                    Commands.CANCEL -> ok()
                    Commands.PROFILE -> if (Capabilities.PROFILER in caps) profile(request) else
                        error(ErrorCodes.UNKNOWN_COMMAND, "runtime 不支持命令 ${request.command}，请升级 runtime。")
                    Commands.ERRORS -> if (Capabilities.ERROR_REPORT !in caps) {
                        error(ErrorCodes.UNKNOWN_COMMAND, "runtime 不支持命令 ${request.command}，请升级 runtime。")
                    } else if (request.args.get("action")?.asString == "clear") {
                        errorList = emptyList(); errorsCleared++; ok()
                    } else {
                        ok(com.fixcode.intellij.lua.debug.protocol.ErrorsResult(errorList).toJson())
                    }
                    Commands.DISCONNECT -> {
                        DisconnectArgs.fromJson(request.args)
                        reply(request, ok())
                        client = null
                        onDisconnected(ResumedBy.DISCONNECT)
                        connection.socket.close()
                        return
                    }
                    else -> error(ErrorCodes.UNKNOWN_COMMAND, "runtime 不支持命令 ${request.command}，请升级 runtime。")
                }
            } catch (e: Exception) {
                error(ErrorCodes.INVALID_ARGS, "参数错误：${e.message}")
            }
            if (result.deferred) return
            reply(request, result)
        }
    }

    private class Result(val body: JsonObject?, val code: String?, val message: String?, val deferred: Boolean = false)

    private fun ok(body: JsonObject = JsonObject()) = Result(body, null, null)
    private fun ok(dto: JsonDto) = Result(dto.toJson(), null, null)
    private fun error(code: String, message: String) = Result(null, code, message)

    private fun reply(request: ProtocolMessage.Request, result: Result) {
        val connection = client ?: return
        val response = if (result.code == null) ProtocolMessage.Response.success(request.id, result.body ?: JsonObject())
        else ProtocolMessage.Response.failure(request.id, result.code, result.message ?: "")
        connection.send(response)
    }

    private fun event(name: String, body: JsonDto) = event(name, body.toJson())

    private fun event(name: String, body: JsonObject) {
        val connection = client ?: return
        if (!connection.handshaken) return
        connection.send(ProtocolMessage.Event(connection.nextSeq(), name, body))
    }

    private fun hello(connection: ClientConnection, request: ProtocolMessage.Request): Result {
        val hello = HelloArgs.fromJson(request.args)
        if (hello.protocol != 3) return error(ErrorCodes.PROTOCOL_MISMATCH, "调试 runtime 只支持协议 v3，请升级 IDE 插件或 runtime 到同一版本。")
        if (token != null && hello.token != token) return error(ErrorCodes.AUTH_FAILED, "配对码不正确，请核对后重试。")
        if (busyHandshakes > 0) {
            busyHandshakes--
            return error(ErrorCodes.BUSY, "这个进程已经被另一个调试器连接，先断开那边再试。")
        }
        connection.handshaken = true
        val welcome = Welcome(
            sessionId = "fake-${connections}",
            runtime = RuntimeInfo("fake-runtime", "3.0.0"),
            caps = caps.filter { it in hello.caps },
            vms = vms.values.map { it.info() },
            lua = LuaInfo("5.4"),
            platform = platform,
            arch = "x64",
            process = ProcessInfo(pid, "FakeGame.exe")
        )
        return ok(welcome)
    }

    private fun setBreakpoints(request: ProtocolMessage.Request): Result {
        val args = SetBreakpointsArgs.fromJson(request.args)
        if (args.revision <= revision) return error(ErrorCodes.INVALID_ARGS, "revision ${args.revision} 不大于当前 $revision")
        revision = args.revision
        val acks = args.upserts.map { spec ->
            when {
                spec.condition?.contains("syntax error") == true -> BreakpointAck(spec.id, BreakpointStateNames.INVALID, "条件表达式语法错误")
                spec.hitCondition != null && parseHitCondition(spec.hitCondition!!) == null ->
                    BreakpointAck(spec.id, BreakpointStateNames.INVALID, "命中次数条件写法不对：${spec.hitCondition}")
                else -> BreakpointAck(spec.id, BreakpointStateNames.PENDING)
            }
        }
        val apply = {
            if (args.replaceAll) {
                breakpoints.clear()
                hitCounts.clear()
            }
            args.removes.forEach { breakpoints.remove(it) }
            args.upserts.forEach { spec ->
                breakpoints[spec.id] = spec
                hitCounts.keys.removeIf { it.first == spec.id }
                statusSent.removeIf { it.first == spec.id }
            }
        }
        val response = SetBreakpointsResult(args.revision, acks)
        if (breakpointAckDelayMillis <= 0) {
            apply()
            return ok(response)
        }
        // 断点表在别的线程上重建：确认之前新断点不生效，后续请求照常处理
        val connection = client
        timer.schedule({
            synchronized(lock) {
                if (client !== connection) return@synchronized
                apply()
                reply(request, ok(response))
            }
        }, breakpointAckDelayMillis, TimeUnit.MILLISECONDS)
        return Result(null, null, null, deferred = true)
    }

    /**
     * 继续 / 单步：对暂停中的 VM 必须带当前 pauseId（旧 IDE 不带 pauseId，被拒绝后目标一直卡在断点上）。
     * 顺序保证：先发 resumed 事件，再回 response。
     */
    private fun control(request: ProtocolMessage.Request, stepKind: String?, by: String): Result {
        val vmId = request.args.get("vmId")?.asLong ?: 0L
        if (vmId == 0L && stepKind == null) {
            vms.values.filter { it.paused }.forEach { resume(it, by) }
            return ok()
        }
        val vm = vms[vmId] ?: return error(ErrorCodes.VM_NOT_FOUND, "Lua VM $vmId 已经关闭，请刷新 VM 列表。")
        if (!vm.paused) return error(ErrorCodes.NOT_PAUSED, "Lua VM $vmId 正在运行，先暂停或等断点命中。")
        val pauseId = request.args.get("pauseId")?.takeUnless { it.isJsonNull }?.asLong
            ?: return error(ErrorCodes.INVALID_ARGS, "控制命令必须带 pauseId（旧版 IDE 漏带导致目标卡在断点上）。")
        if (pauseId != vm.pauseId) return error(ErrorCodes.STALE_PAUSE, "这次暂停已经结束，请等下一次 paused 事件。")
        vm.stepping = stepKind
        resume(vm, by)
        return ok()
    }

    private fun runToLine(request: ProtocolMessage.Request): Result {
        val args = RunToLineArgs.fromJson(request.args)
        val vm = vms[args.target.vmId] ?: return error(ErrorCodes.VM_NOT_FOUND, "Lua VM ${args.target.vmId} 已经关闭。")
        if (vm.paused) {
            val pauseId = args.target.pauseId ?: return error(ErrorCodes.INVALID_ARGS, "控制命令必须带 pauseId（旧版 IDE 漏带导致目标卡在断点上）。")
            if (pauseId != vm.pauseId) return error(ErrorCodes.STALE_PAUSE, "这次暂停已经结束，请等下一次 paused 事件。")
        }
        vm.runToLine = args.source to args.line
        if (vm.paused) resume(vm, ResumedBy.RUN_TO_LINE)
        return ok()
    }

    private fun resume(vm: FakeVm, by: String, notify: Boolean = true) {
        val pauseId = vm.pauseId
        vm.paused = false
        vm.resumes += by
        dropReferences(vm)
        if (notify) event(Events.RESUMED, ResumedEvent(vm.vmId, pauseId, by))
    }

    private fun pause(vm: FakeVm, stack: List<FakeFrame>, reason: String, breakpointIds: List<String> = emptyList(), message: String? = null): HitResult {
        vm.paused = true
        vm.pauseId++
        vm.stepping = null
        vm.runToLine = null // 任何一次暂停都清除临时断点（协议 8.5）
        vm.stack = stack
        dropReferences(vm)
        val top = frameDto(vm, stack.first(), 0)
        event(
            Events.PAUSED,
            PausedEvent(
                vm.vmId, vm.pauseId, reason, vm.threadId, top, breakpointIds,
                exception = message?.let { com.fixcode.intellij.lua.debug.protocol.ExceptionInfo(it, "stack traceback:\n\t${stack.first().chunkName}:${stack.first().line}") }
            )
        )
        return HitResult.PAUSED
    }

    private fun frameDto(vm: FakeVm, frame: FakeFrame, level: Int, threadId: String = vm.threadId): StackFrameDto {
        val existing = frames.entries.firstOrNull { it.value.first === vm && it.value.second === frame }?.key
        val frameId = existing ?: (nextObjectId++).also { frames[it] = vm to frame }
        return StackFrameDto(frameId, frame.name, frame.chunkName, frame.line, level, frame.what, threadId)
    }

    private fun dropReferences(vm: FakeVm) {
        frames.values.removeIf { it.first === vm }
        refs.values.removeIf { it.first === vm }
    }

    private fun pausedVm(request: ProtocolMessage.Request): Pair<FakeVm?, Result?> {
        val vmId = request.args.get("vmId")?.asLong ?: 0L
        val vm = vms[vmId] ?: return null to error(ErrorCodes.VM_NOT_FOUND, "Lua VM $vmId 已经关闭。")
        if (!vm.paused) return null to error(ErrorCodes.NOT_PAUSED, "Lua VM $vmId 正在运行。")
        val pauseId = request.args.get("pauseId")?.asLong
        if (pauseId != null && pauseId != vm.pauseId) return null to error(ErrorCodes.STALE_PAUSE, "这次暂停已经结束，请等下一次 paused 事件。")
        return vm to null
    }

    private fun stackTrace(request: ProtocolMessage.Request): Result {
        val (vm, failure) = pausedVm(request)
        if (vm == null) return failure!!
        val args = StackTraceArgs.fromJson(request.args)
        val thread = args.target.threadId?.takeIf { it != vm.threadId }
        val stack = if (thread == null) vm.stack else vm.coroutines[thread] ?: return error(ErrorCodes.INVALID_ARGS, "没有协程 $thread（可能已结束）。")
        val page = stack.withIndex().drop(args.start).take(args.count).map { (level, frame) -> frameDto(vm, frame, level, thread ?: vm.threadId) }
        return ok(StackTraceResult(page, stack.size))
    }

    private fun scopes(request: ProtocolMessage.Request): Result {
        val args = ScopesArgs.fromJson(request.args)
        val (vm, frame) = frames[args.frameId] ?: return error(ErrorCodes.STALE_REFERENCE, "这个栈帧已失效（游戏已继续运行），请重新获取调用栈。")
        val scopes = ArrayList<ScopeDto>()
        if (frame.locals.isNotEmpty()) scopes += ScopeDto("locals", container(vm, frame.locals), frame.locals.size)
        if (frame.upvalues.isNotEmpty()) scopes += ScopeDto("upvalues", container(vm, frame.upvalues), frame.upvalues.size)
        return ok(ScopesResult(scopes))
    }

    private fun container(vm: FakeVm, entries: LinkedHashMap<String, FakeValue>): Long {
        val id = nextObjectId++
        refs[id] = vm to entries
        return id
    }

    private fun variableDto(vm: FakeVm, name: String, value: FakeValue, path: String): VariableDto = when (value) {
        is FakeValue.Table -> {
            val indexed = value.entries.keys.count { it.startsWith("[") }
            VariableDto(
                name, value.display(), "table", container(vm, value.entries), value.typeName,
                namedCount = value.entries.size - indexed, indexedCount = indexed, evaluateName = path
            )
        }
        is FakeValue.Prim -> VariableDto(name, value.text, value.type, evaluateName = path)
    }

    private fun variables(request: ProtocolMessage.Request): Result {
        val args = VariablesArgs.fromJson(request.args)
        val (vm, entries) = refs[args.ref] ?: return error(ErrorCodes.STALE_REFERENCE, "这个变量已失效（游戏已继续运行），请重新获取调用栈。")
        // 名字带括号的（(metatable) 等）是伪项：v3.0 混在子项里；有 richVariables 时放进 groups（variable-view.md 13.3）
        val rich = Capabilities.RICH_VARIABLES in caps
        val (pseudo, plain) = entries.entries.partition { rich && it.key.startsWith("(") }
        val page = plain.drop(args.start).take(args.count).map { (name, value) ->
            val dto = variableDto(vm, name, value, name)
            val max = args.maxValueLength
            if (rich && max != null && dto.value.length > max) dto.copy(value = dto.value.take(max) + "…", attrs = listOf("truncated")) else dto
        }
        val groups = if (args.start == 0) pseudo.map { (name, value) -> variableDto(vm, name, value, name).copy(kind = "group") } else emptyList()
        return ok(VariablesResult(page, plain.size, groups, sorted = if (rich) false else null))
    }

    private fun evaluate(request: ProtocolMessage.Request): Result {
        val args = EvaluateArgs.fromJson(request.args)
        val (vm, frame) = frames[args.frameId] ?: return error(ErrorCodes.STALE_REFERENCE, "这个栈帧已失效（游戏已继续运行），请重新获取调用栈。")
        if (args.mode == EvalModes.LUA) {
            if (Capabilities.LUA_EVAL !in caps) return error(ErrorCodes.UNSUPPORTED, "这个 runtime 不支持任意 Lua 求值。")
            if (options.allowLuaEval == false) return error(ErrorCodes.EVAL_FORBIDDEN, "当前不允许执行任意 Lua，请在 IDE 设置中开启，或改用 valuePath。")
        }
        val value = resolvePath(frame, args.expression)
            ?: FakeValue.parseLiteral(args.expression).takeIf { args.mode == EvalModes.LUA }
            ?: return error(ErrorCodes.EVAL_ERROR, "attempt to index a nil value (${args.expression})")
        val dto = variableDto(vm, args.expression, value, args.expression)
        return ok(ValueResult(dto.value, dto.type, dto.ref, dto.typeName))
    }

    private fun setVariable(request: ProtocolMessage.Request): Result {
        val args = SetVariableArgs.fromJson(request.args)
        val (vm, entries) = refs[args.ref] ?: return error(ErrorCodes.STALE_REFERENCE, "这个变量已失效。")
        val value = FakeValue.parseLiteral(args.value) ?: return error(ErrorCodes.EVAL_ERROR, "无法求值 ${args.value}")
        entries[args.name] = value
        val dto = variableDto(vm, args.name, value, args.name)
        return ok(ValueResult(dto.value, dto.type, dto.ref))
    }

    // =====================================================================
    // 断点匹配与条件（协议 7.2 / 7.3 的简化实现）
    // =====================================================================

    private fun sendStatus(bp: BreakpointSpec, vm: FakeVm, chunkName: String, state: String) {
        if (statusSent.add(Triple(bp.id, vm.vmId, state))) {
            event(Events.BREAKPOINT_STATUS, BreakpointStatusEvent(bp.id, vm.vmId, state, chunkName, bp.line))
        }
    }

    private fun matches(source: SourceRef, chunkName: String): Boolean {
        if (source.chunkName != null) return segments(source.chunkName!!) == segments(chunkName)
        val chunk = segments(chunkName)
        val path = segments(source.path)
        if (chunk.isEmpty() || path.isEmpty()) return false
        return if (chunk.size <= path.size) path.takeLast(chunk.size) == chunk else chunk.takeLast(path.size) == path
    }

    private fun segments(path: String): List<String> {
        if (path.startsWith("=")) return emptyList()
        val normalized = path.removePrefix("@").replace('\\', '/').lowercase()
        val stripped = listOf(".lua", ".luac", ".txt", ".bytes").firstOrNull { normalized.endsWith(it) }
            ?.let { normalized.dropLast(it.length) } ?: normalized
        return stripped.split('/').filter { it.isNotEmpty() && it != "." && !(it.length == 2 && it[1] == ':') }
    }

    /** 条件只支持 `path == literal`、`path > literal`、`path`（真值），够测 IDE 侧下发是否正确。 */
    private fun conditionHolds(bp: BreakpointSpec, frame: FakeFrame): Boolean {
        val condition = bp.condition ?: return true
        val match = Regex("""^\s*([\w.\[\]"']+)\s*(==|~=|>=|<=|>|<)\s*(.+?)\s*$""").find(condition)
        if (match == null) {
            val value = resolvePath(frame, condition.trim())
            return value != null && value != FakeValue.NIL && !(value is FakeValue.Prim && value.text == "false")
        }
        val (path, op, literal) = match.destructured
        val left = resolvePath(frame, path) as? FakeValue.Prim ?: return false
        val right = FakeValue.parseLiteral(literal) as? FakeValue.Prim ?: return false
        val l = left.text.toDoubleOrNull()
        val r = right.text.toDoubleOrNull()
        return when (op) {
            "==" -> left.text == right.text
            "~=" -> left.text != right.text
            ">" -> l != null && r != null && l > r
            "<" -> l != null && r != null && l < r
            ">=" -> l != null && r != null && l >= r
            "<=" -> l != null && r != null && l <= r
            else -> false
        }
    }

    private fun parseHitCondition(text: String): Pair<String, Long>? {
        val match = Regex("""^\s*(==|>=|>|%)?\s*(\d+)\s*$""").find(text) ?: return null
        return (match.groupValues[1].ifEmpty { "==" }) to match.groupValues[2].toLong()
    }

    private fun hitConditionHolds(text: String?, count: Long): Boolean {
        if (text == null) return true
        val (op, n) = parseHitCondition(text) ?: return false
        return when (op) {
            "==" -> count == n
            ">=" -> count >= n
            ">" -> count > n
            "%" -> n > 0 && count % n == 0L
            else -> false
        }
    }

    private fun interpolate(template: String, frame: FakeFrame): String =
        Regex("""\{([^{}]+)\}""").replace(template) { resolvePath(frame, it.groupValues[1].trim())?.display() ?: "nil" }

    /** valuePath（协议 10.2）：root(.ident | [key])*，root 先查局部变量再查 upvalue。 */
    fun resolvePath(frame: FakeFrame, expression: String): FakeValue? {
        val tokens = Regex("""[A-Za-z_]\w*|\[\s*\d+\s*]|\[\s*"[^"]*"\s*]|\[\s*'[^']*'\s*]""").findAll(expression).map { it.value }.toList()
        if (tokens.isEmpty() || tokens.joinToString("").replace(".", "") != expression.replace(".", "").replace(" ", "")) return null
        var current: FakeValue = frame.locals[tokens[0]] ?: frame.upvalues[tokens[0]] ?: return null
        for (token in tokens.drop(1)) {
            val table = current as? FakeValue.Table ?: return null
            val key = when {
                token.startsWith("[") && token.contains('"') -> token.substringAfter('"').substringBeforeLast('"')
                token.startsWith("[") && token.contains('\'') -> token.substringAfter('\'').substringBeforeLast('\'')
                token.startsWith("[") -> "[" + token.trim('[', ']', ' ') + "]"
                else -> token
            }
            current = table.entries[key] ?: return null
        }
        return current
    }

    private fun merge(base: SetOptionsArgs, incoming: SetOptionsArgs) = base.merge(incoming)

    companion object {
        val ALL_CAPS: List<String> = listOf(
            Capabilities.VALUE_PATH, Capabilities.LUA_EVAL, Capabilities.SET_VARIABLE, Capabilities.CONDITION,
            Capabilities.HIT_CONDITION, Capabilities.LOGPOINT, Capabilities.CAPTURE, Capabilities.RUN_TO_LINE,
            Capabilities.EXCEPTIONS, Capabilities.OUTPUT, Capabilities.HELPERS, Capabilities.TERMINATE,
            Capabilities.RICH_VARIABLES, Capabilities.VIEW_RULES, Capabilities.COROUTINES, Capabilities.PROFILER,
            Capabilities.ERROR_REPORT, Capabilities.FUNCTION_BREAKPOINTS
        )

        /** v3.0 原文的 runtime：没有变量视图增量，也没有采样分析、错误聚合、函数断点。 */
        val CAPS_V30: List<String> = ALL_CAPS - Capabilities.RICH_VARIABLES - Capabilities.VIEW_RULES - Capabilities.PROFILER -
            Capabilities.ERROR_REPORT - Capabilities.FUNCTION_BREAKPOINTS

        /** 没有 runToLine 的 runtime：IDE 要走「临时断点 + 确认后继续」的兜底。 */
        val CAPS_WITHOUT_RUN_TO_LINE: List<String> = ALL_CAPS - Capabilities.RUN_TO_LINE
    }
}
