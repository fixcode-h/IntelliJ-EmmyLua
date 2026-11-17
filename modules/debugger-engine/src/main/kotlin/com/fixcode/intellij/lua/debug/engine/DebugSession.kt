package com.fixcode.intellij.lua.debug.engine

import com.google.gson.JsonObject
import com.fixcode.intellij.lua.debug.engine.transport.Connector
import com.fixcode.intellij.lua.debug.engine.transport.MessageConnection
import com.fixcode.intellij.lua.debug.engine.transport.TargetGoneException
import com.fixcode.intellij.lua.debug.protocol.BreakpointHitEvent
import com.fixcode.intellij.lua.debug.protocol.BreakpointSpec
import com.fixcode.intellij.lua.debug.protocol.BreakpointStateNames
import com.fixcode.intellij.lua.debug.protocol.BreakpointStatusEvent
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.ClientInfo
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.DisconnectArgs
import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.EvalModes
import com.fixcode.intellij.lua.debug.protocol.EvaluateArgs
import com.fixcode.intellij.lua.debug.protocol.Events
import com.fixcode.intellij.lua.debug.protocol.ErrorsActions
import com.fixcode.intellij.lua.debug.protocol.ErrorsArgs
import com.fixcode.intellij.lua.debug.protocol.ErrorsResult
import com.fixcode.intellij.lua.debug.protocol.LuaErrorDto
import com.fixcode.intellij.lua.debug.protocol.HelloArgs
import com.fixcode.intellij.lua.debug.protocol.LoadHelpersArgs
import com.fixcode.intellij.lua.debug.protocol.OutputCategories
import com.fixcode.intellij.lua.debug.protocol.OutputEvent
import com.fixcode.intellij.lua.debug.protocol.ProfileActions
import com.fixcode.intellij.lua.debug.protocol.ProfileArgs
import com.fixcode.intellij.lua.debug.protocol.ProfileResult
import com.fixcode.intellij.lua.debug.protocol.ProfileStartResult
import com.fixcode.intellij.lua.debug.protocol.PausedEvent
import com.fixcode.intellij.lua.debug.protocol.Protocol
import com.fixcode.intellij.lua.debug.protocol.ProtocolMessage
import com.fixcode.intellij.lua.debug.protocol.ResumedBy
import com.fixcode.intellij.lua.debug.protocol.ResumedEvent
import com.fixcode.intellij.lua.debug.protocol.RunToLineArgs
import com.fixcode.intellij.lua.debug.protocol.ScopesArgs
import com.fixcode.intellij.lua.debug.protocol.ScopesResult
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsResult
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import com.fixcode.intellij.lua.debug.protocol.SetVariableArgs
import com.fixcode.intellij.lua.debug.protocol.SetViewRulesArgs
import com.fixcode.intellij.lua.debug.protocol.SetViewRulesResult
import com.fixcode.intellij.lua.debug.protocol.SourceRef
import com.fixcode.intellij.lua.debug.protocol.StackFrameDto
import com.fixcode.intellij.lua.debug.protocol.StackTraceArgs
import com.fixcode.intellij.lua.debug.protocol.StackTraceResult
import com.fixcode.intellij.lua.debug.protocol.TerminatedEvent
import com.fixcode.intellij.lua.debug.protocol.ThreadInfo
import com.fixcode.intellij.lua.debug.protocol.ThreadStatuses
import com.fixcode.intellij.lua.debug.protocol.ThreadsArgs
import com.fixcode.intellij.lua.debug.protocol.ThreadsResult
import com.fixcode.intellij.lua.debug.protocol.ExceptionModes
import com.fixcode.intellij.lua.debug.protocol.ValueResult
import com.fixcode.intellij.lua.debug.protocol.VariablesArgs
import com.fixcode.intellij.lua.debug.protocol.VariablesResult
import com.fixcode.intellij.lua.debug.protocol.VmClosedEvent
import com.fixcode.intellij.lua.debug.protocol.VmInfo
import com.fixcode.intellij.lua.debug.protocol.VmStateChangedEvent
import com.fixcode.intellij.lua.debug.protocol.Welcome
import com.fixcode.intellij.lua.debug.protocol.jsonObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 一个调试会话：一条到 runtime 的连接 + 它上面的全部状态。替代旧的 `EmmyDebugProcessBase`，
 * 不依赖 IntelliJ；XDebugger 适配层和 MCP 都是它的客户端，看到同一份状态。
 *
 * 线程模型：每个会话一个单线程事件循环，所有状态只在这个线程上读写；对外方法都是投递消息，
 * 返回的 future 在会话线程上完成。UI 和 Agent 不会同时改状态，所以不需要旧实现的租约和各种并发补丁。
 * 不要在事件监听器（会话线程）里对 future 调 `get()`，那会自己等自己。
 */
class DebugSession(
    val config: SessionConfig,
    private val connector: Connector,
    val sourceMapper: SourceMapper = SourceMapper(),
    val id: String = UUID.randomUUID().toString()
) : AutoCloseable {
    private val loopThreadName = "EmmyLua-Debug-${id.take(8)}"
    @Volatile
    private var loopThread: Thread? = null
    private val loop: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, loopThreadName).apply { isDaemon = true; loopThread = this }
    }
    private val io: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "$loopThreadName-io").apply { isDaemon = true }
    }

    val events = EventBus { error -> logEngine("事件监听器出错：$error") }

    // ---------- 只在会话线程访问的状态 ----------
    private var connection: MessageConnection? = null
    private var connectionEpoch = 0L
    private var nextRequestId = 1L
    private val pending = HashMap<Long, PendingRequest>()
    private val vms = VmRegistry()
    private val pauses = PauseStore()
    private val breakpoints = BreakpointStore(::toSpec)
    private var breakpointFlushScheduled = false
    private var lastBreakpointSend: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    private val initiators = HashMap<Long, Initiator>()
    private val stackCache = HashMap<StackKey, StackPage>()
    private val shaCache = HashMap<String, String?>()
    /** 当前选项：握手时全量下发，运行中由 [updateOptions] 累积修改。只在会话线程读写。 */
    private var options: SetOptionsArgs = config.options
    private var viewRules: JsonObject? = config.viewRules

    /** variables.maxValueLength（设置页「字符串预览长度」），任意线程可改，下次取变量生效。 */
    @Volatile
    var maxValueLength: Int? = config.maxValueLength
    private var reconnecting = false
    private var reconnectAttempt = 0
    /** 握手回 BUSY 后已经重试的次数。 */
    private var busyRetries = 0

    /** 协议跟踪：设置后每一帧收发都回调一行（IDE 日志级别「协议」时用），在会话线程调用，不要阻塞。 */
    @Volatile
    var protocolTrace: ((String) -> Unit)? = null
    private var runtimeTerminatedReason: String? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var heartbeatMisses = 0
    private var heartbeatInFlight = false
    /** 心跳没回应但目标进程还在：保持连接继续等，恢复时提示一次。 */
    private var heartbeatStalled = false
    private val startFuture = CompletableFuture<Welcome>()
    private val stopFuture = CompletableFuture<Unit>()

    private class PendingRequest(val command: String, val future: CompletableFuture<JsonObject>, var timeout: ScheduledFuture<*>?)
    private data class StackKey(val vmId: Long, val pauseId: Long, val epoch: Long, val start: Int, val count: Int, val threadId: String?)

    // ---------- 对外只读快照（任意线程） ----------
    @Volatile
    var state: SessionState = SessionState.CREATED
        private set
    @Volatile
    var welcome: Welcome? = null
        private set
    @Volatile
    private var snapshot = Snapshot()

    private data class Snapshot(
        val vms: List<VmView> = emptyList(),
        val pauses: List<PauseInfo> = emptyList(),
        val focused: PauseInfo? = null,
        val breakpoints: List<BreakpointView> = emptyList()
    )

    private val outputLog = SequencedLog<OutputEvent>(config.outputCapacity)
    private val hitLog = SequencedLog<BreakpointHitEvent>(config.breakpointHitCapacity)
    private val errorStore = ErrorStore()

    fun vms(): List<VmView> = snapshot.vms
    fun pauses(): List<PauseInfo> = snapshot.pauses
    fun focusedPause(): PauseInfo? = snapshot.focused
    fun breakpoints(): List<BreakpointView> = snapshot.breakpoints
    fun hasCapability(cap: String): Boolean = welcome?.has(cap) == true

    /** 引用是否还有效：暂停没被继续、没被取代、连接没重建。 */
    fun isCurrent(pause: PauseInfo): Boolean = snapshot.pauses.any {
        it.vmId == pause.vmId && it.pauseId == pause.pauseId && it.connectionEpoch == pause.connectionEpoch
    }

    fun output(sinceSeq: Long = 0, limit: Int = Int.MAX_VALUE): List<Sequenced<OutputEvent>> = outputLog.since(sinceSeq, limit)
    fun breakpointHits(sinceSeq: Long = 0, limit: Int = Int.MAX_VALUE): List<Sequenced<BreakpointHitEvent>> = hitLog.since(sinceSeq, limit)

    // =====================================================================
    // 生命周期
    // =====================================================================

    /** 建连并握手；握手、helpers、选项、断点全部下发且 configurationDone 后完成。 */
    fun start(): CompletableFuture<Welcome> {
        post {
            check(state == SessionState.CREATED) { "会话已经启动过" }
            setState(SessionState.CONNECTING)
            connectAttempt()
        }
        return startFuture
    }

    /**
     * 停止调试：发 disconnect（runtime 会恢复所有暂停的 VM，游戏不会卡在断点上），然后关连接。
     * [terminate] 为 true 时请求宿主退出进程（需要能力 terminate）。
     */
    fun stop(terminate: Boolean = false): CompletableFuture<Unit> {
        post {
            when {
                state.isTerminal || state == SessionState.STOPPING -> Unit
                connection == null -> finish("调试已停止")
                else -> {
                    setState(SessionState.STOPPING)
                    request(Commands.DISCONNECT, DisconnectArgs(terminate && hasCapability(Capabilities.TERMINATE)).toJson(), 2_000)
                        .whenComplete { _, _ ->
                            connection?.close()
                            finish("调试已停止")
                        }
                }
            }
        }
        return stopFuture
    }

    override fun close() {
        try {
            stop().get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            loop.shutdownNow()
            io.shutdownNow()
        }
    }

    private fun connectAttempt() {
        CompletableFuture.supplyAsync({ connector.connect() }, io).whenComplete { conn, error ->
            post {
                if (state.isTerminal || state == SessionState.STOPPING) {
                    conn?.close()
                    return@post
                }
                if (error != null) onConnectFailed(unwrap(error)) else attach(conn)
            }
        }
    }

    private fun onConnectFailed(error: Throwable) {
        if (error is TargetGoneException) {
            reconnecting = false
            // 目标没了：首次连接算失败，重连途中算正常结束（游戏 / 编辑器被关掉是常态）
            if (state == SessionState.RECONNECTING) finish(error.message ?: "目标进程已退出") else fail(error.message ?: "目标进程已退出")
            return
        }
        if (reconnecting) {
            scheduleReconnect(error.message ?: "连接失败")
        } else {
            fail("连不上调试 runtime：${error.message}")
        }
    }

    private fun attach(conn: MessageConnection) {
        val epoch = ++connectionEpoch
        connection = conn
        shaCache.clear()
        conn.start(object : MessageConnection.Listener {
            override fun onMessage(message: ProtocolMessage) = post { if (epoch == connectionEpoch) dispatch(message) }
            override fun onClosed(cause: Throwable?) = post { if (epoch == connectionEpoch) onConnectionLost(cause) }
        })
        handshake(epoch)
    }

    private fun handshake(epoch: Long) {
        val hello = HelloArgs(ClientInfo(Protocol.CLIENT_NAME, config.clientVersion), token = connection?.token ?: config.token)
        request(Commands.HELLO, hello.toJson(), config.handshakeTimeoutMillis)
            .thenCompose { body ->
                val welcome = Welcome.fromJson(body)
                if (welcome.protocol != Protocol.VERSION) {
                    throw DebugEngineException(ErrorCodes.PROTOCOL_MISMATCH, "runtime 使用协议 v${welcome.protocol}，本插件只支持 v3，请升级到同一版本。")
                }
                onWelcome(welcome)
                configure(welcome)
            }
            .whenComplete { _, error ->
                if (epoch != connectionEpoch) return@whenComplete
                if (error == null) onConfigured() else onHandshakeFailed(unwrap(error))
            }
    }

    /** 协议第 4 节的推荐顺序。请求在同一连接上按序处理，所以可以连发，只等最后的 configurationDone。 */
    private fun configure(welcome: Welcome): CompletableFuture<JsonObject> {
        setState(SessionState.CONFIGURING)
        if (config.helpers.isNotEmpty() && welcome.has(Capabilities.HELPERS)) {
            request(Commands.LOAD_HELPERS, LoadHelpersArgs(config.helpers, config.helperMain).toJson()).whenComplete { _, error ->
                if (error != null) logEngine("类型显示脚本加载失败：${unwrap(error).message}")
            }
        }
        // variable-view.md 13.4：loadHelpers 之后、setOptions 之前
        viewRules?.let { rules -> if (welcome.has(Capabilities.VIEW_RULES)) sendViewRules(rules) }
        request(Commands.SET_OPTIONS, effectiveOptions(options, welcome).toJson()).whenComplete { _, error ->
            if (error != null) logEngine("调试选项下发失败：${unwrap(error).message}")
        }
        sendBreakpoints(breakpoints.fullSnapshot())
        return request(Commands.CONFIGURATION_DONE, JsonObject())
    }

    /** 只发 runtime 认识的项：没有 luaEval 能力不发 allowLuaEval，没有 richVariables 不发 variableView。 */
    private fun effectiveOptions(options: SetOptionsArgs, welcome: Welcome): SetOptionsArgs = options.copy(
        allowLuaEval = options.allowLuaEval?.takeIf { welcome.has(Capabilities.LUA_EVAL) },
        // 没有 exceptions 能力的 runtime 只接受 none（协议 11 节），其他值不发，避免整条 setOptions 被拒
        exceptions = options.exceptions?.takeIf { it == ExceptionModes.NONE || welcome.has(Capabilities.EXCEPTIONS) },
        collectErrors = options.collectErrors?.takeIf { welcome.has(Capabilities.ERROR_REPORT) },
        variableView = options.variableView?.takeIf { welcome.has(Capabilities.RICH_VARIABLES) }
    )

    /**
     * 运行中改选项（设置页「应用」、MCP 打开任意求值）：只发给出的项，并记下来，重连后的握手带上最新值。
     * 没连上时只记下来，完成时不报错。
     */
    fun updateOptions(change: SetOptionsArgs): CompletableFuture<Unit> = callAsync {
        options = options.merge(change)
        val welcome = this.welcome
        if (!state.isConnected || welcome == null) return@callAsync CompletableFuture.completedFuture(Unit)
        val effective = effectiveOptions(change, welcome)
        if (effective.toJson().size() == 0) return@callAsync CompletableFuture.completedFuture(Unit)
        request(Commands.SET_OPTIONS, effective.toJson()).thenApply { }
    }

    /** 整体替换显示规则（variable-view.md 13.4）；runtime 不支持时返回 null。为空表示清空规则。 */
    fun setViewRules(rules: JsonObject?): CompletableFuture<SetViewRulesResult?> = callAsync {
        viewRules = rules
        val welcome = this.welcome
        if (!state.isConnected || welcome == null || !welcome.has(Capabilities.VIEW_RULES)) {
            return@callAsync CompletableFuture.completedFuture(null)
        }
        sendViewRules(rules ?: JsonObject()).thenApply { it }
    }

    private fun sendViewRules(rules: JsonObject): CompletableFuture<SetViewRulesResult?> =
        request(Commands.SET_VIEW_RULES, SetViewRulesArgs(rules).toJson()).handle { body, error ->
            if (error != null) {
                logEngine("显示规则下发失败：${unwrap(error).message}")
                return@handle null
            }
            val result = SetViewRulesResult.fromJson(body)
            result.warnings.forEach { logEngine("显示规则 ${it.type ?: ""}：${it.message}") }
            result
        }

    private fun onWelcome(welcome: Welcome) {
        val previous = this.welcome
        if (previous != null && previous.process?.pid != welcome.process?.pid) {
            // 连上的是另一个进程：vmId / pauseId 都会从头计数
            vms.resetProcess()
            pauses.resetProcess()
        }
        this.welcome = welcome
        sourceMapper.caseSensitive = welcome.platform == "linux"
        val changes = vms.applySnapshot(welcome.vms)
        changes.closed.forEach { emit(DebugEvent.VmClosed(it, "重连后 runtime 已没有这个 VM")) }
        changes.added.forEach { emit(DebugEvent.VmAdded(it)) }
        changes.changed.forEach { emit(DebugEvent.VmChanged(it)) }
    }

    private fun onConfigured() {
        busyRetries = 0
        val wasReconnect = reconnecting
        reconnecting = false
        reconnectAttempt = 0
        setState(SessionState.RUNNING)
        startHeartbeat()
        val welcome = this.welcome!!
        // runtime 每个新会话都从零计数，本地也清掉，避免重连后计数对不上
        errorStore.clear()
        emit(DebugEvent.Connected(welcome, wasReconnect))
        later { startFuture.complete(welcome) }
    }

    private fun onHandshakeFailed(error: Throwable) {
        connection?.close()
        connection = null
        failPending(error)
        if (error is DebugEngineException && error.code == ErrorCodes.BUSY && busyRetries < config.busyRetries) {
            // 刚停止的上一个调试会话还没断干净（或上次 IDE 被强杀、runtime 要等心跳超时才释放）：等一会儿再连
            if (busyRetries++ == 0) logEngine("进程里上一次的调试连接还没释放，稍后自动重试…")
            schedule(config.busyRetryDelayMillis) { if (!state.isTerminal && state != SessionState.STOPPING) connectAttempt() }
            return
        }
        if (reconnecting) {
            scheduleReconnect(error.message ?: "握手失败")
        } else {
            fail(error.message ?: "握手失败")
        }
    }

    private fun onConnectionLost(cause: Throwable?) {
        val reason = runtimeTerminatedReason ?: cause?.message ?: "调试连接已断开"
        val stateAtLoss = state
        connection = null
        stopHeartbeat()
        // 失败的请求回调可能已经结束会话（握手失败、停止中），之后以 stateAtLoss 和当前状态一起判断
        failPending(DebugEngineException(ErrorCodes.DISCONNECTED, reason, cause))
        if (state.isTerminal) return
        // runtime 断线后会恢复所有 VM、清空断点（协议第 4 节）
        for (pause in pauses.clear()) {
            vms.setPaused(pause.vmId, false)
            emit(DebugEvent.Resumed(pause.vmId, pause.pauseId, ResumedBy.DISCONNECT, Initiator.ENGINE))
        }
        emit(DebugEvent.FocusChanged(null))
        initiators.clear()
        stackCache.clear()
        breakpoints.removeIf { _, request -> request.origin == BreakpointOrigin.TEMPORARY }
        breakpoints.connectionLost()
        emit(DebugEvent.BreakpointsChanged(breakpoints.views()))
        when {
            stateAtLoss == SessionState.STOPPING -> finish("调试已停止")
            stateAtLoss == SessionState.CONNECTING || stateAtLoss == SessionState.CONFIGURING -> Unit // 握手失败路径处理
            runtimeTerminatedReason != null || config.reconnect.maxAttempts <= 0 -> finish(reason)
            else -> {
                reconnecting = true
                reconnectAttempt = 0
                setState(SessionState.RECONNECTING, reason)
                emit(DebugEvent.Disconnected(reason, willReconnect = true))
                scheduleReconnect(reason)
            }
        }
    }

    private fun scheduleReconnect(lastError: String) {
        reconnectAttempt++
        if (reconnectAttempt > config.reconnect.maxAttempts) {
            reconnecting = false
            finish("重连 ${config.reconnect.maxAttempts} 次都失败了：$lastError")
            return
        }
        if (state != SessionState.RECONNECTING) setState(SessionState.RECONNECTING, lastError)
        schedule(config.reconnect.delayFor(reconnectAttempt)) {
            if (state == SessionState.RECONNECTING) connectAttempt()
        }
    }

    private fun fail(message: String) {
        connection?.close()
        connection = null
        failPending(DebugEngineException(ErrorCodes.DISCONNECTED, message))
        setState(SessionState.FAILED, message)
        emit(DebugEvent.Terminated(message))
        later {
            startFuture.completeExceptionally(DebugEngineException(ErrorCodes.DISCONNECTED, message))
            stopFuture.complete(Unit)
        }
    }

    private fun finish(reason: String) {
        if (state.isTerminal) return
        connection?.close()
        connection = null
        stopHeartbeat()
        failPending(DebugEngineException(ErrorCodes.DISCONNECTED, reason))
        for (pause in pauses.clear()) {
            emit(DebugEvent.Resumed(pause.vmId, pause.pauseId, ResumedBy.DISCONNECT, Initiator.ENGINE))
        }
        setState(SessionState.TERMINATED, reason)
        emit(DebugEvent.Terminated(reason))
        later {
            startFuture.completeExceptionally(DebugEngineException(ErrorCodes.DISCONNECTED, reason))
            stopFuture.complete(Unit)
        }
    }

    private fun setState(next: SessionState, message: String? = null) {
        val previous = state
        if (previous == next) return
        state = next
        emit(DebugEvent.StateChanged(previous, next, message))
    }

    // ---------- 心跳 ----------

    private fun startHeartbeat() {
        stopHeartbeat()
        val interval = config.heartbeatIntervalMillis
        if (interval <= 0) return
        heartbeatMisses = 0
        heartbeat = loop.scheduleWithFixedDelay({ run { sendPing(interval) } }, interval, interval, TimeUnit.MILLISECONDS)
    }

    private fun sendPing(timeout: Long) {
        if (state != SessionState.RUNNING || heartbeatInFlight) return
        heartbeatInFlight = true
        request(Commands.PING, JsonObject(), timeout).whenComplete { _, error ->
            heartbeatInFlight = false
            if (error == null) {
                heartbeatMisses = 0
                if (heartbeatStalled) {
                    heartbeatStalled = false
                    logEngine("runtime 恢复响应")
                }
            } else if (unwrap(error).let { it is DebugEngineException && it.code == ErrorCodes.TIMEOUT }) {
                heartbeatMisses++
                if (heartbeatMisses >= config.heartbeatMaxMisses) onHeartbeatLost()
            }
        }
    }

    /**
     * 心跳连续没回应。连接还在、进程也还在时多半是整个进程被暂停了（VS 里中断、附加了别的调试器）
     * 或卡在一个很长的帧里：断开重连只会反复注入，所以保持连接继续等；进程没了就结束会话。
     * 查不了进程（手动地址、真机）时按断线处理，交给重连。
     */
    private fun onHeartbeatLost() {
        val conn = connection ?: return
        val misses = heartbeatMisses
        CompletableFuture.supplyAsync({ runCatching { connector.isTargetAlive() }.getOrNull() }, io).whenComplete { alive, _ ->
            post {
                if (connection !== conn || state.isTerminal) return@post
                when (alive) {
                    true -> {
                        heartbeatMisses = 0
                        if (!heartbeatStalled) {
                            heartbeatStalled = true
                            logEngine("runtime 连续 $misses 次没有回应心跳，但进程还在（可能在 VS 等调试器里被暂停或卡住），保持连接继续等")
                        }
                    }
                    false -> {
                        logEngine("runtime 没有回应心跳，目标进程已退出")
                        runtimeTerminatedReason = "目标进程已退出"
                        conn.close()
                    }
                    null -> {
                        logEngine("runtime 连续 $misses 次没有回应心跳，按断线处理")
                        conn.close()
                    }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
        heartbeatInFlight = false
        heartbeatStalled = false
    }

    // =====================================================================
    // 请求与分发
    // =====================================================================

    private fun request(command: String, args: JsonObject, timeoutMillis: Long = config.requestTimeoutMillis): CompletableFuture<JsonObject> {
        assertLoop()
        val future = CompletableFuture<JsonObject>()
        val conn = connection
        if (conn == null) {
            future.completeExceptionally(DebugEngineException(ErrorCodes.DISCONNECTED, "调试连接已断开，命令 $command 没有发出。"))
            return future
        }
        val id = nextRequestId++
        val entry = PendingRequest(command, future, null)
        pending[id] = entry
        entry.timeout = schedule(timeoutMillis) {
            if (pending.remove(id) != null) {
                future.completeExceptionally(
                    DebugEngineException(ErrorCodes.TIMEOUT, "runtime 在 ${timeoutMillis / 1000.0} 秒内没有响应 $command。")
                )
            }
        }
        try {
            protocolTrace?.invoke("→ #$id $command $args")
            conn.send(ProtocolMessage.Request(id, command, args))
        } catch (error: Exception) {
            pending.remove(id)?.timeout?.cancel(false)
            future.completeExceptionally(DebugEngineException(ErrorCodes.DISCONNECTED, "发送 $command 失败：${error.message}", error))
            conn.close()
        }
        return future
    }

    private fun failPending(error: Throwable) {
        val entries = pending.values.toList()
        pending.clear()
        entries.forEach {
            it.timeout?.cancel(false)
            it.future.completeExceptionally(error)
        }
    }

    private fun dispatch(message: ProtocolMessage) {
        protocolTrace?.let { trace ->
            when (message) {
                is ProtocolMessage.Response -> trace("← #${message.id} ${if (message.ok) "ok" else "error"} ${message.body}${message.error?.let { " $it" } ?: ""}")
                is ProtocolMessage.Event -> trace("← ${message.event} ${message.body}")
                else -> trace("← $message")
            }
        }
        when (message) {
            is ProtocolMessage.Response -> {
                val entry = pending.remove(message.id) ?: return
                entry.timeout?.cancel(false)
                if (message.ok) {
                    entry.future.complete(message.body)
                } else {
                    val error = message.error
                    val code = error?.code ?: ErrorCodes.INTERNAL
                    val text = error?.message?.takeIf { it.isNotBlank() } ?: "runtime 拒绝了 ${entry.command}（$code）"
                    entry.future.completeExceptionally(
                        if (code == ErrorCodes.STALE_REFERENCE) StaleReferenceException(text) else DebugEngineException(code, text)
                    )
                }
            }
            is ProtocolMessage.Event -> try {
                onEvent(message)
            } catch (error: Exception) {
                logEngine("处理事件 ${message.event} 出错：${error.message}")
            }
            is ProtocolMessage.Request -> logEngine("runtime 不应发 request（${message.command}），已忽略")
        }
    }

    private fun onEvent(event: ProtocolMessage.Event) {
        val body = event.body
        when (event.event) {
            Events.VM_ADDED -> vms.add(VmInfo.fromJson(body))?.let { emit(DebugEvent.VmAdded(it)) }
            Events.VM_STATE_CHANGED -> vms.applyState(VmStateChangedEvent.fromJson(body))?.let { emit(DebugEvent.VmChanged(it)) }
            Events.VM_CLOSED -> onVmClosed(VmClosedEvent.fromJson(body))
            Events.BREAKPOINT_STATUS -> onBreakpointStatus(BreakpointStatusEvent.fromJson(body))
            Events.BREAKPOINT_HIT -> {
                val hit = BreakpointHitEvent.fromJson(body)
                val item = hitLog.add(hit)
                emit(DebugEvent.BreakpointHit(item.seq, hit, breakpoints[hit.id]?.file))
            }
            Events.PAUSED -> onPaused(PausedEvent.fromJson(body))
            Events.RESUMED -> onResumed(ResumedEvent.fromJson(body))
            Events.OUTPUT -> {
                val output = OutputEvent.fromJson(body)
                val item = outputLog.add(output)
                emit(DebugEvent.Output(item.seq, output))
            }
            Events.LUA_ERROR -> emit(DebugEvent.LuaError(errorStore.merge(LuaErrorDto.fromJson(body))))
            Events.TERMINATED -> runtimeTerminatedReason = TerminatedEvent.fromJson(body).reason?.let { "目标进程已结束（$it）" } ?: "目标进程已结束"
            else -> Unit // 协议约定：未知事件忽略
        }
    }

    // =====================================================================
    // VM 与暂停
    // =====================================================================

    private fun onVmClosed(event: VmClosedEvent) {
        val closed = vms.close(event.vmId)
        pauses.forgetVm(event.vmId)?.let { release ->
            emit(DebugEvent.Resumed(event.vmId, release.released.pauseId, ResumedBy.HOST, Initiator.ENGINE))
            if (release.focusChanged) emit(DebugEvent.FocusChanged(release.newFocus))
        }
        initiators.remove(event.vmId)
        stackCache.keys.removeIf { it.vmId == event.vmId }
        val removed = breakpoints.removeIf { _, request -> request.origin == BreakpointOrigin.TEMPORARY && request.vmId == event.vmId }
        val changed = breakpoints.forgetVm(event.vmId)
        if (changed || removed.isNotEmpty()) emit(DebugEvent.BreakpointsChanged(breakpoints.views(), removed))
        if (closed != null) emit(DebugEvent.VmClosed(event.vmId, event.reason))
    }

    private fun onPaused(event: PausedEvent) {
        if (vms[event.vmId] == null) {
            // 未知 VM 的暂停不能丢：旧版就是因为 VM 标识对不上把暂停丢了，目标一直停着而 IDE 毫无反应
            val placeholder = vms.ensure(event.vmId) ?: return // 已关闭 VM 的迟到事件
            emit(DebugEvent.VmAdded(placeholder))
            logEngine("收到未登记的 VM ${event.vmId} 的暂停，已按新 VM 显示")
        }
        val pause = PauseInfo(
            event.vmId, event.pauseId, event.threadId, event.reason, event.top, event.breakpointIds,
            event.description, event.exception, connectionEpoch
        )
        val status = pauses.offer(pause)
        if (status == PauseStore.OfferStatus.STALE || status == PauseStore.OfferStatus.DUPLICATE) return
        initiators.remove(event.vmId)
        stackCache.keys.removeIf { it.vmId == event.vmId }
        // 该 VM 的任何一次暂停都会清除它的临时断点（协议 8.5），本地同步删除
        removeTemporaryBreakpoints(event.vmId)
        vms.setPaused(event.vmId, true)?.let { emit(DebugEvent.VmChanged(it)) }
        emit(DebugEvent.Paused(pause, focused = status == PauseStore.OfferStatus.FOCUSED))
    }

    private fun onResumed(event: ResumedEvent) {
        val current = pauses[event.vmId] ?: return
        if (event.pauseId != null && event.pauseId != current.pauseId) return
        releasePause(current, event.by)
    }

    private fun releasePause(pause: PauseInfo, by: String?) {
        if (!pauses.isCurrent(pause)) return
        val release = pauses.release(pause.vmId, pause.pauseId) ?: return
        stackCache.keys.removeIf { it.vmId == pause.vmId }
        val initiator = initiators.remove(pause.vmId) ?: Initiator.ENGINE
        vms.setPaused(pause.vmId, false)?.let { emit(DebugEvent.VmChanged(it)) }
        emit(DebugEvent.Resumed(pause.vmId, pause.pauseId, by, initiator))
        if (release.focusChanged) emit(DebugEvent.FocusChanged(release.newFocus))
    }

    /** UI 切换到另一个暂停中的 VM。 */
    fun focus(vmId: Long): CompletableFuture<PauseInfo?> = call {
        pauses.focus(vmId)?.also { emit(DebugEvent.FocusChanged(it)) }
    }

    // =====================================================================
    // 执行控制
    // =====================================================================

    /** 继续。[vmId] 为空时继续当前焦点 VM；没有暂停时什么都不做。 */
    fun resume(vmId: Long? = null, initiator: Initiator = Initiator.USER): CompletableFuture<Unit> =
        control(Commands.CONTINUE, vmId, initiator, ResumedBy.CONTINUE)

    fun stepOver(vmId: Long? = null, initiator: Initiator = Initiator.USER) = control(Commands.STEP_OVER, vmId, initiator, ResumedBy.STEP)
    fun stepInto(vmId: Long? = null, initiator: Initiator = Initiator.USER) = control(Commands.STEP_IN, vmId, initiator, ResumedBy.STEP)
    fun stepOut(vmId: Long? = null, initiator: Initiator = Initiator.USER) = control(Commands.STEP_OUT, vmId, initiator, ResumedBy.STEP)

    /** 继续所有暂停中的 VM。 */
    fun resumeAll(initiator: Initiator = Initiator.USER): CompletableFuture<Unit> = callAsync {
        val paused = pauses.all()
        if (paused.isEmpty()) return@callAsync CompletableFuture.completedFuture(Unit)
        CompletableFuture.allOf(*paused.map { controlNow(Commands.CONTINUE, it, initiator, ResumedBy.CONTINUE) }.toTypedArray())
            .thenApply { }
    }

    /** 暂停（Break All）。[vmId] 为空表示所有 VM。生效后会收到 [DebugEvent.Paused]。 */
    fun pause(vmId: Long? = null): CompletableFuture<Unit> = callAsync {
        request(Commands.PAUSE, jsonObject { put("vmId", vmId ?: 0L) }).thenApply { }
    }

    /**
     * 运行到指定行。必须先暂停在某个 VM 上。
     * runtime 有 runToLine 能力时由 runtime 处理临时断点；否则引擎下临时断点，**等 runtime 确认后**再继续
     * （旧版先发继续再发断点，断点还没生效游戏就跑过去了）。
     */
    fun runToLine(file: String, line: Int, vmId: Long? = null, initiator: Initiator = Initiator.USER): CompletableFuture<Unit> = callAsync {
        val pause = currentPauseFor(vmId)
            ?: throw DebugEngineException(ErrorCodes.NOT_PAUSED, "运行到光标需要先暂停在某个 Lua VM 上。")
        val target = pause.target() // 先记下暂停身份，后面任何清理都不能影响它
        if (hasCapability(Capabilities.RUN_TO_LINE)) {
            initiators[pause.vmId] = initiator
            request(Commands.RUN_TO_LINE, RunToLineArgs(target, sourceRef(file), line).toJson())
                .handle { _, error -> afterControl(pause, error, ResumedBy.RUN_TO_LINE) }
        } else {
            removeTemporaryBreakpoints(pause.vmId)
            breakpoints.add(BreakpointRequest(file, line, vmId = pause.vmId, origin = BreakpointOrigin.TEMPORARY))
            publishBreakpoints()
            flushBreakpoints().thenCompose {
                if (!pauses.isCurrent(pause)) {
                    // 等断点确认期间暂停已经结束（用户在别处继续了），不再追加继续
                    CompletableFuture.completedFuture(Unit)
                } else {
                    controlNow(Commands.CONTINUE, pause, initiator, ResumedBy.RUN_TO_LINE)
                }
            }
        }
    }

    private fun control(command: String, vmId: Long?, initiator: Initiator, by: String): CompletableFuture<Unit> = callAsync {
        val pause = currentPauseFor(vmId) ?: return@callAsync CompletableFuture.completedFuture(Unit)
        controlNow(command, pause, initiator, by)
    }

    /**
     * 发控制命令。暂停身份取自 [pause]（调用前就已确定），不会因为本地状态先被清掉而丢失：
     * 旧版先清暂停快照再发不带 pauseId 的继续，被 runtime 拒绝后无人处理，目标就一直停在断点上。
     */
    private fun controlNow(command: String, pause: PauseInfo, initiator: Initiator, by: String): CompletableFuture<Unit> {
        initiators[pause.vmId] = initiator
        return request(command, pause.target().toJson()).handle { _, error -> afterControl(pause, error, by) }
    }

    private fun afterControl(pause: PauseInfo, error: Throwable?, by: String) {
        if (error == null) {
            // 正常情况下 resumed 事件已经先到了（协议 3.3 的顺序保证），这里只是兜底
            releasePause(pause, by)
            return
        }
        val cause = unwrap(error)
        val code = (cause as? DebugEngineException)?.code
        when (code) {
            // runtime 那边这次暂停已经不在了：本地跟着结束，不算失败
            ErrorCodes.STALE_PAUSE, ErrorCodes.NOT_PAUSED, ErrorCodes.VM_NOT_FOUND -> releasePause(pause, by)
            else -> {
                // 没发成功或被拒绝：保持暂停状态并报错，绝不假装已经继续
                initiators.remove(pause.vmId)
                throw CompletionException(cause)
            }
        }
    }

    private fun currentPauseFor(vmId: Long?): PauseInfo? = if (vmId != null && vmId != 0L) pauses[vmId] else pauses.focused

    // =====================================================================
    // 断点
    // =====================================================================

    fun addBreakpoint(request: BreakpointRequest): CompletableFuture<String> = call {
        val id = breakpoints.add(request)
        scheduleFlush()
        emit(DebugEvent.BreakpointsChanged(listOfNotNull(breakpoints.view(id))))
        id
    }

    /** 批量添加：只下发一次（IDE 启动、Agent 一次下多个断点时用）。 */
    fun addBreakpoints(requests: List<BreakpointRequest>): CompletableFuture<List<String>> = call {
        val ids = requests.map { breakpoints.add(it) }
        if (ids.isNotEmpty()) {
            scheduleFlush()
            emit(DebugEvent.BreakpointsChanged(ids.mapNotNull { breakpoints.view(it) }))
        }
        ids
    }

    fun updateBreakpoint(id: String, request: BreakpointRequest): CompletableFuture<Boolean> = call {
        val updated = breakpoints.update(id, request)
        if (updated) {
            scheduleFlush()
            emit(DebugEvent.BreakpointsChanged(listOfNotNull(breakpoints.view(id))))
        }
        updated
    }

    fun removeBreakpoint(id: String): CompletableFuture<Boolean> = call {
        val removed = breakpoints.remove(id)
        if (removed) {
            scheduleFlush()
            emit(DebugEvent.BreakpointsChanged(emptyList(), listOf(id)))
        }
        removed
    }

    /** 批量删除，例如「清除 Agent 断点」。 */
    fun removeBreakpoints(predicate: (BreakpointView) -> Boolean): CompletableFuture<List<String>> = call {
        val views = breakpoints.views().associateBy { it.id }
        val removed = breakpoints.removeIf { id, _ -> views[id]?.let(predicate) == true }
        if (removed.isNotEmpty()) {
            scheduleFlush()
            emit(DebugEvent.BreakpointsChanged(emptyList(), removed))
        }
        removed
    }

    /** 本地文件内容变了（保存、热更新）：下次下发时重算 sha256。 */
    fun invalidateSource(path: String): CompletableFuture<Unit> = call {
        if (shaCache.remove(SourcePaths.normalize(path)) != null) scheduleFlush()
    }

    /**
     * 用户在「映射」页给 chunk 指定本地文件。断点的 chunkName 是下发时按映射算的，
     * 改完映射重新算一遍差异，受影响的断点自然会带着新的 chunkName 重发。
     */
    fun overrideMapping(chunkName: String, localPath: String): CompletableFuture<Unit> = call {
        sourceMapper.override(chunkName, localPath)
        scheduleFlush()
    }

    /** 等当前所有断点改动都被 runtime 确认。 */
    fun syncBreakpoints(): CompletableFuture<Unit> = callAsync { flushBreakpoints() }

    private fun scheduleFlush() {
        if (breakpointFlushScheduled) return
        breakpointFlushScheduled = true
        // 合并同一批改动：IDE 启动时会一口气注册很多断点，只发一次
        post {
            breakpointFlushScheduled = false
            flushBreakpoints()
        }
    }

    /** 没有新差异时返回最近一次还没确认的下发，调用方拿到的完成总是意味着「runtime 已经确认到最新」。 */
    private fun flushBreakpoints(): CompletableFuture<Unit> {
        if (!state.isConnected) return CompletableFuture.completedFuture(Unit) // 连上后发全量
        val delta = breakpoints.nextDelta() ?: return lastBreakpointSend
        return sendBreakpoints(delta)
    }

    private fun sendBreakpoints(args: SetBreakpointsArgs): CompletableFuture<Unit> {
        breakpoints.markSent(args)
        val future = request(Commands.SET_BREAKPOINTS, args.toJson()).handle { body, error ->
            if (error != null) {
                val cause = unwrap(error)
                if ((cause as? DebugEngineException)?.code != ErrorCodes.DISCONNECTED) logEngine("断点下发失败：${cause.message}")
                throw CompletionException(cause)
            }
            breakpoints.applyAcks(SetBreakpointsResult.fromJson(body).breakpoints)
            publishBreakpoints()
        }
        lastBreakpointSend = future
        return future
    }

    private fun onBreakpointStatus(event: BreakpointStatusEvent) {
        if (!breakpoints.applyStatus(event)) return
        if (event.state == BreakpointStateNames.MISMATCH) {
            emit(DebugEvent.SourceMismatch(event.id, event.vmId, event.chunkName, event.message))
        }
        emit(DebugEvent.BreakpointsChanged(listOfNotNull(breakpoints.view(event.id))))
    }

    private fun removeTemporaryBreakpoints(vmId: Long) {
        val removed = breakpoints.removeIf { _, request -> request.origin == BreakpointOrigin.TEMPORARY && request.vmId == vmId }
        if (removed.isNotEmpty()) {
            scheduleFlush()
            emit(DebugEvent.BreakpointsChanged(emptyList(), removed))
        }
    }

    private fun publishBreakpoints() {
        emit(DebugEvent.BreakpointsChanged(breakpoints.views()))
    }

    private fun toSpec(id: String, request: BreakpointRequest): BreakpointSpec = BreakpointSpec(
        id, sourceRef(request.file), request.line,
        condition = request.condition?.takeIf { it.isNotBlank() },
        hitCondition = request.hitCondition?.takeIf { it.isNotBlank() },
        logMessage = request.logMessage?.takeIf { it.isNotEmpty() },
        capture = request.capture,
        autoContinue = request.autoContinue,
        vmId = request.vmId
    )

    private fun sourceRef(file: String): SourceRef {
        val path = SourcePaths.normalize(file)
        val sha = if (hasCapability(Capabilities.SOURCE_HASH)) shaCache.getOrPut(path) { sourceIndexSha(path) } else null
        return SourceRef(path, sourceMapper.knownChunkFor(path), sha)
    }

    private fun sourceIndexSha(path: String): String? = sourceMapper.sha256(path)

    // =====================================================================
    // 按需取数据
    // =====================================================================

    /**
     * 取栈。[threadId] 为空时是暂停所在的线程；给了别的协程的 id（来自 [threads]）时取那个协程的栈，
     * 帧的 frameId 同样绑定这次暂停，变量和求值照常可用。
     */
    fun stackTrace(pause: PauseInfo, start: Int = 0, count: Int = 20, threadId: String? = null): CompletableFuture<StackPage> = callAsync {
        ensureCurrent(pause)
        val thread = threadId?.takeIf { it != pause.threadId }
        val key = StackKey(pause.vmId, pause.pauseId, pause.connectionEpoch, start, count, thread)
        stackCache[key]?.let { return@callAsync CompletableFuture.completedFuture(it) }
        val target = if (thread == null) pause.target() else pause.target().copy(threadId = thread)
        request(Commands.STACK_TRACE, StackTraceArgs(target, start, count).toJson()).handle { body, error ->
            if (error != null) throw staleOr(error)
            val result = StackTraceResult.fromJson(body)
            val page = StackPage(result.frames.map { FrameHandle(pause, it, localPathOf(it)) }, result.total)
            if (pauses.isCurrent(pause)) stackCache[key] = page
            page
        }
    }

    /**
     * 暂停的 VM 里的协程（含主线程）。runtime 没有 coroutines 能力时只返回暂停所在的线程。
     * 列表可能不完整：没被引用的协程会被 GC（协议 6.4）。
     */
    fun threads(pause: PauseInfo): CompletableFuture<List<ThreadInfo>> = callAsync {
        ensureCurrent(pause)
        if (!hasCapability(Capabilities.COROUTINES)) {
            return@callAsync CompletableFuture.completedFuture(listOf(ThreadInfo(pause.threadId ?: "main", ThreadStatuses.RUNNING, "main")))
        }
        request(Commands.THREADS, ThreadsArgs(pause.vmId, pause.pauseId).toJson()).handle { body, error ->
            if (error != null) throw staleOr(error)
            ThreadsResult.fromJson(body).threads
        }
    }

    /** 栈顶帧（paused 事件自带，不用请求）。 */
    fun topFrame(pause: PauseInfo): FrameHandle? = pause.top?.let { FrameHandle(pause, it, localPathOf(it)) }

    fun scopes(frame: FrameHandle): CompletableFuture<List<VariableContainer>> = callAsync {
        ensureCurrent(frame.pause)
        request(Commands.SCOPES, ScopesArgs(frame.frameId).toJson()).handle { body, error ->
            if (error != null) throw staleOr(error)
            ScopesResult.fromJson(body).scopes.map { VariableContainer(frame.pause, it.ref, it.name, it.count) }
        }
    }

    /**
     * 取一页子项。[view]、[expand] 和 maxValueLength 是 richVariables 增量，runtime 没有这个能力时不发。
     * 没有 richVariables 时把 v3.0 的 `(metatable)` 伪项挪进 groups，UI 和 MCP 只认一种模型（variable-view.md 15.1）。
     */
    fun variables(
        container: VariableContainer,
        start: Int = 0,
        count: Int = 100,
        filter: String? = null,
        view: String? = null,
        expand: List<List<String>>? = null
    ): CompletableFuture<VariablePage> =
        callAsync {
            ensureCurrent(container.pause)
            val rich = hasCapability(Capabilities.RICH_VARIABLES)
            val args = if (rich) {
                VariablesArgs(container.ref, start, count, filter, view, maxValueLength, expand)
            } else {
                VariablesArgs(container.ref, start, count, filter)
            }
            request(Commands.VARIABLES, args.toJson()).handle { body, error ->
                if (error != null) throw staleOr(error)
                val result = VariablesResult.fromJson(body)
                val handles = result.variables.map { VariableHandle(container.pause, it) }
                if (rich) {
                    VariablePage(handles, result.total, result.groups.map { VariableHandle(container.pause, it) }, result.truncated == true)
                } else {
                    val (meta, plain) = handles.partition { it.variable.name == METATABLE_PSEUDO }
                    VariablePage(plain, result.total - meta.size, meta)
                }
            }
        }

    /**
     * 求值。IDE 里默认任意 Lua（[EvalModes.LUA]）；Agent 默认只读路径（[EvalModes.VALUE_PATH]）。
     * [frame] 为空时在栈顶帧求值。
     */
    fun evaluate(
        pause: PauseInfo,
        expression: String,
        frame: FrameHandle? = null,
        mode: String = EvalModes.LUA,
        context: String? = null,
        maxLength: Int? = null
    ): CompletableFuture<EvalValue> = callAsync {
        ensureCurrent(pause)
        requireEvalMode(mode)
        val frameId = frame?.frameId ?: pause.top?.frameId ?: 0L
        request(Commands.EVALUATE, EvaluateArgs(frameId, expression, mode, context, maxLength).toJson()).handle { body, error ->
            if (error != null) throw staleOr(error)
            val result = ValueResult.fromJson(body)
            EvalValue(pause, expression, result.value, result.type, result.ref, result.typeName)
        }
    }

    fun setVariable(container: VariableContainer, name: String, value: String, mode: String = EvalModes.LUA): CompletableFuture<ValueResult> =
        callAsync {
            ensureCurrent(container.pause)
            if (!hasCapability(Capabilities.SET_VARIABLE)) {
                throw DebugEngineException(ErrorCodes.UNSUPPORTED, "这个 runtime 不支持修改变量（缺少能力 setVariable），请升级 runtime。")
            }
            requireEvalMode(mode)
            request(Commands.SET_VARIABLE, SetVariableArgs(container.ref, name, value, mode).toJson()).handle { body, error ->
                if (error != null) throw staleOr(error)
                stackCache.keys.removeIf { it.vmId == container.pause.vmId }
                ValueResult.fromJson(body)
            }
        }

    // =====================================================================
    // 错误聚合（协议第 21 节，能力 errorReport）
    // =====================================================================

    /** runtime 协商到了 errorReport 能力（「Lua 错误」页、MCP errors 据此决定能不能用）。 */
    val supportsErrorReport: Boolean get() = hasCapability(Capabilities.ERROR_REPORT)

    /** 本地缓存的错误（推送累积，计数可能略落后）；不发请求，UI 刷新列表用。 */
    fun cachedErrors(): List<LuaErrorDto> = errorStore.snapshot()

    /** 向 runtime 拉精确的全量并覆盖本地缓存；没连上或 runtime 不支持时返回本地缓存。 */
    fun errors(): CompletableFuture<ErrorsResult> = callAsync {
        if (!state.isConnected || !supportsErrorReport) {
            CompletableFuture.completedFuture(ErrorsResult(errorStore.snapshot(), errorStore.dropped()))
        } else {
            request(Commands.ERRORS, ErrorsArgs(ErrorsActions.LIST).toJson()).thenApply { body ->
                val result = ErrorsResult.fromJson(body)
                errorStore.replace(result.items, result.dropped)
                ErrorsResult(errorStore.snapshot(), result.dropped)
            }
        }
    }

    /** 清空（runtime 和本地）。 */
    fun clearErrors(): CompletableFuture<Unit> = callAsync {
        errorStore.clear()
        emit(DebugEvent.ErrorsCleared)
        if (!state.isConnected || !supportsErrorReport) CompletableFuture.completedFuture(Unit)
        else request(Commands.ERRORS, ErrorsArgs(ErrorsActions.CLEAR).toJson()).thenApply { }
    }

    // =====================================================================
    // 采样性能分析（协议第 20 节，能力 profiler）
    // =====================================================================

    /** runtime 协商到了 profiler 能力（UI 据此显示「开始采样」，MCP 据此决定 profile 动作能不能用）。 */
    val supportsProfiler: Boolean get() = hasCapability(Capabilities.PROFILER)

    /**
     * 开始采样。[vmId] 为 0 表示所有 VM；已经在采样时清空重来。[durationMs] > 0 时 runtime 到点自动停。
     * 采样不改变游戏状态（不暂停、不改变量），只在采样期间多一个 COUNT 钩子。
     */
    fun profileStart(vmId: Long = 0, intervalInstructions: Int? = null, durationMs: Int? = null): CompletableFuture<ProfileStartResult> =
        callAsync {
            requireProfiler()
            request(Commands.PROFILE, ProfileArgs(ProfileActions.START, vmId, intervalInstructions, durationMs).toJson())
                .thenApply { ProfileStartResult.fromJson(it) }
        }

    /** 停止采样并取结果（一个往返）。 */
    fun profileStop(vmId: Long = 0, sort: String? = null, limit: Int? = null, stackLimit: Int? = null): CompletableFuture<ProfileResult> =
        profileQuery(ProfileActions.STOP, vmId, sort, limit, stackLimit)

    /** 取当前结果（采样中也可以取，不停止）。 */
    fun profileResult(vmId: Long = 0, sort: String? = null, limit: Int? = null, stackLimit: Int? = null): CompletableFuture<ProfileResult> =
        profileQuery(ProfileActions.RESULT, vmId, sort, limit, stackLimit)

    private fun profileQuery(action: String, vmId: Long, sort: String?, limit: Int?, stackLimit: Int?): CompletableFuture<ProfileResult> =
        callAsync {
            requireProfiler()
            request(Commands.PROFILE, ProfileArgs(action, vmId, sort = sort, limit = limit, stackLimit = stackLimit).toJson())
                .thenApply { ProfileResult.fromJson(it) }
        }

    private fun requireProfiler() {
        if (!state.isConnected || welcome == null) {
            throw DebugEngineException(ErrorCodes.DISCONNECTED, "调试连接还没建立，连上游戏后再开始采样。")
        }
        if (!hasCapability(Capabilities.PROFILER)) {
            throw DebugEngineException(ErrorCodes.UNSUPPORTED, "这个 runtime 不支持采样性能分析（缺少能力 profiler），请升级游戏里的调试 runtime。")
        }
    }

    private fun requireEvalMode(mode: String) {
        when (mode) {
            EvalModes.LUA -> if (!hasCapability(Capabilities.LUA_EVAL)) {
                throw DebugEngineException(ErrorCodes.UNSUPPORTED, "这个 runtime 不支持任意 Lua 求值（缺少能力 luaEval），请改用取值路径（如 self.items[1].id）。")
            }
            EvalModes.VALUE_PATH -> if (!hasCapability(Capabilities.VALUE_PATH)) {
                throw DebugEngineException(ErrorCodes.UNSUPPORTED, "这个 runtime 不支持取值路径求值（缺少能力 valuePath），请升级 runtime。")
            }
            else -> throw DebugEngineException(ErrorCodes.INVALID_ARGS, "未知的求值模式 $mode，只能是 valuePath 或 lua。")
        }
    }

    private fun ensureCurrent(pause: PauseInfo) {
        if (!pauses.isCurrent(pause)) throw StaleReferenceException()
    }

    private fun staleOr(error: Throwable): Throwable {
        val cause = unwrap(error)
        val code = (cause as? DebugEngineException)?.code
        val stale = code == ErrorCodes.STALE_REFERENCE || code == ErrorCodes.STALE_PAUSE || code == ErrorCodes.NOT_PAUSED
        return CompletionException(if (stale && cause !is StaleReferenceException) StaleReferenceException() else cause)
    }

    /** chunkName 映射到的本地文件（变量的 location：函数定义处、模块文件）；未映射时为空。 */
    fun localPathOfChunk(chunkName: String): String? = sourceMapper.localPathOf(chunkName)

    private fun localPathOf(frame: StackFrameDto): String? = if (frame.hasSource) sourceMapper.localPathOf(frame.chunkName) else null

    // =====================================================================
    // 等待事件（MCP 的 wait_for_pause 用）
    // =====================================================================

    /** 等第一个满足条件的事件；超时完成为 null。只看调用之后发生的事件。 */
    fun awaitEvent(timeoutMillis: Long, predicate: (DebugEvent) -> Boolean): CompletableFuture<DebugEvent?> {
        val future = CompletableFuture<DebugEvent?>()
        val subscription = events.subscribe { event -> if (!future.isDone && predicate(event)) future.complete(event) }
        val timer = try {
            loop.schedule({ future.complete(null) }, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            future.complete(null)
            null
        }
        future.whenComplete { _, _ ->
            subscription.close()
            timer?.cancel(false)
        }
        return future
    }

    // =====================================================================
    // 事件循环工具
    // =====================================================================

    private fun post(action: () -> Unit) {
        try {
            loop.execute { run(action) }
        } catch (_: RejectedExecutionException) {
            // 会话已关闭
        }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit): ScheduledFuture<*>? = try {
        loop.schedule({ run(action) }, delayMillis, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    /** 每个循环任务结束后刷新对外快照，任意线程的读者看到的都是完整的一致状态。 */
    private fun run(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            logEngine("会话内部错误：$error")
        } finally {
            publishSnapshot()
        }
    }

    private fun <T> call(action: () -> T): CompletableFuture<T> = callAsync { CompletableFuture.completedFuture(action()) }

    private fun <T> callAsync(action: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val task = {
            try {
                action().whenComplete { value, error -> later { if (error != null) result.completeExceptionally(unwrap(error)) else result.complete(value) } }
            } catch (error: Throwable) {
                later { result.completeExceptionally(unwrap(error)) }
            }
        }
        if (Thread.currentThread() === loopThread) {
            try {
                task()
            } finally {
                publishSnapshot()
            }
        } else {
            try {
                loop.execute { run(task) }
            } catch (_: RejectedExecutionException) {
                result.completeExceptionally(DebugEngineException(ErrorCodes.DISCONNECTED, "调试会话已结束。"))
            }
        }
        return result
    }

    /**
     * 在会话线程上时，把对外 future 的完成推迟到当前任务结束（快照刷新）之后：
     * 调用方拿到结果时，[vms]、[pauses]、[breakpoints] 一定已经反映了这次操作。
     */
    private fun later(action: () -> Unit) {
        if (Thread.currentThread() !== loopThread) return action()
        try {
            loop.execute(action)
        } catch (_: RejectedExecutionException) {
            action()
        }
    }

    /** 先刷新快照再发事件：监听器在回调里读 [vms]、[pauses]、[breakpoints] 看到的是事件之后的状态。 */
    private fun emit(event: DebugEvent) {
        publishSnapshot()
        events.publish(event)
    }

    private fun publishSnapshot() {
        snapshot = Snapshot(vms.list(), pauses.all(), pauses.focused, breakpoints.views())
    }

    private fun assertLoop() {
        check(Thread.currentThread() === loopThread) { "只能在会话线程上调用" }
    }

    private fun logEngine(text: String) {
        val output = OutputEvent(OutputCategories.RUNTIME, "[EmmyLua] $text\n", level = "warn")
        val item = outputLog.add(output)
        emit(DebugEvent.Output(item.seq, output))
    }

    companion object {
        /** v3.0 runtime 把元表作为子项返回时用的名字。 */
        const val METATABLE_PSEUDO = "(metatable)"
        fun unwrap(error: Throwable): Throwable {
            var current = error
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) current = current.cause!!
            return current
        }
    }
}
