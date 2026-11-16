package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.DebugEngineException
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.PauseInfo
import com.fixcode.intellij.lua.debug.engine.ReconnectPolicy
import com.fixcode.intellij.lua.debug.engine.SessionConfig
import com.fixcode.intellij.lua.debug.engine.SourceMapper
import com.fixcode.intellij.lua.debug.engine.fake.EventRecorder
import com.fixcode.intellij.lua.debug.engine.fake.FakeFrame
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.engine.transport.Connector
import com.fixcode.intellij.lua.debug.engine.transport.TcpConnector
import com.fixcode.intellij.lua.debug.protocol.ProtocolMessage
import com.fixcode.intellij.lua.debug.protocol.Welcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * 行为用例的公共部分：起一个假 runtime，用真实的 TCP 连接和引擎会话对接。
 *
 * 这些用例是重写的验收基线（设计文档 12.1 第 2 条）：把旧实现修过的问题写成针对新引擎的用例，
 * 新实现必须先过这些用例。每个用例的名字说清楚「什么行为」，注释写清楚对应以前哪个问题。
 */
abstract class BehaviorTestBase {
    protected lateinit var runtime: FakeRuntime
    protected lateinit var session: DebugSession
    protected lateinit var events: EventRecorder
    private val extraSessions = ArrayList<DebugSession>()

    protected fun testConfig(
        reconnect: ReconnectPolicy = ReconnectPolicy(maxAttempts = 20, initialDelayMillis = 20, maxDelayMillis = 100)
    ) = SessionConfig(
        name = "test",
        requestTimeoutMillis = 3_000,
        handshakeTimeoutMillis = 3_000,
        heartbeatIntervalMillis = 0,
        reconnect = reconnect
    )

    protected fun startRuntime(caps: List<String> = FakeRuntime.ALL_CAPS, token: String? = null): FakeRuntime {
        runtime = FakeRuntime(caps, token = token)
        return runtime
    }

    protected fun newSession(
        config: SessionConfig = testConfig(),
        mapper: SourceMapper = SourceMapper(),
        port: Int = runtime.port,
        wrap: (Connector) -> Connector = { it }
    ): DebugSession {
        session = DebugSession(config, wrap(TcpConnector("127.0.0.1", port)), mapper)
        events = EventRecorder(session)
        extraSessions += session
        return session
    }

    /** 起 runtime（带一个 VM）+ 会话并完成握手。 */
    protected fun connect(
        caps: List<String> = FakeRuntime.ALL_CAPS,
        config: SessionConfig = testConfig(),
        mapper: SourceMapper = SourceMapper(),
        wrap: (Connector) -> Connector = { it },
        beforeStart: (DebugSession) -> Unit = {}
    ): Welcome {
        if (!this::runtime.isInitialized) startRuntime(caps)
        if (runtime.vm(1) == null) runtime.addVm("GameLua")
        newSession(config, mapper, wrap = wrap)
        beforeStart(session)
        return session.start().await()
    }

    protected val vm1: FakeRuntime.FakeVm get() = runtime.vm(1)!!

    /** 让 VM 执行到某行并等 IDE 收到暂停。 */
    protected fun pauseAt(
        vm: FakeRuntime.FakeVm,
        chunkName: String = "@scripts/main.lua",
        line: Int = 10,
        stack: List<FakeFrame> = emptyList()
    ): PauseInfo {
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.hit(vm, chunkName, line, stack))
        return events.await<DebugEvent.Paused> { it.pause.vmId == vm.vmId }.pause
    }

    protected fun lastRequest(command: String): ProtocolMessage.Request =
        runtime.requests.last { it.command == command }

    protected fun <T> CompletableFuture<T>.await(): T = try {
        get(5, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }

    /** 期望 future 以 [DebugEngineException] 失败，返回它。 */
    protected fun CompletableFuture<*>.awaitFailure(): DebugEngineException {
        try {
            get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            val cause = DebugSession.unwrap(error)
            if (cause is DebugEngineException) return cause
            throw AssertionError("期望 DebugEngineException，实际是 $cause", cause)
        }
        fail("期望失败，实际成功了")
        throw IllegalStateException()
    }

    @After
    fun tearDownSessions() {
        extraSessions.forEach { it.close() }
        if (this::runtime.isInitialized) runtime.close()
    }
}
