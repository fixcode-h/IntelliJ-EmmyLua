package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.ReconnectPolicy
import com.fixcode.intellij.lua.debug.engine.SessionState
import com.fixcode.intellij.lua.debug.engine.StaleReferenceException
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.ResumedBy
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 断线重连（真机 App 切后台、Wi-Fi 切换必需）。 */
class ReconnectBehaviorTest : BehaviorTestBase() {
    @Test
    fun `断线后自动重连 重发全量断点 旧引用失效`() {
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        val pause = pauseAt(vm1, line = 10)
        runtime.dropClient()

        // runtime 断线后会恢复所有 VM：本地暂停先作废，再进入重连
        val resumed = events.await<DebugEvent.Resumed>()
        assertEquals(ResumedBy.DISCONNECT, resumed.by)
        val disconnected = events.await<DebugEvent.Disconnected>()
        assertTrue(disconnected.willReconnect)
        assertTrue(events.await<DebugEvent.Connected>().reconnect)
        assertEquals(SessionState.RUNNING, session.state)

        // runtime 断线时已经让游戏继续跑，没有卡在断点上
        assertEquals(listOf(ResumedBy.DISCONNECT), vm1.resumes)
        val resent = runtime.requests.filter { it.command == Commands.SET_BREAKPOINTS }.map { SetBreakpointsArgs.fromJson(it.args) }.last()
        assertTrue(resent.replaceAll)
        assertEquals(1L, resent.revision)
        assertEquals(1, resent.upserts.size)
        assertFalse(session.isCurrent(pause))
        try {
            session.stackTrace(pause).await()
            throw AssertionError("应当过期")
        } catch (expected: StaleReferenceException) {
        }

        // 重连后断点照常命中，pauseId 继续递增
        val again = pauseAt(vm1, line = 10)
        assertEquals(pause.pauseId + 1, again.pauseId)
    }

    @Test
    fun `目标进程结束时不重连`() {
        connect()
        runtime.terminate()
        val terminated = events.await<DebugEvent.Terminated>()
        assertTrue(terminated.reason, terminated.reason.contains("目标进程已结束"))
        assertEquals(SessionState.TERMINATED, session.state)
        assertEquals(1, runtime.connections)
    }

    @Test
    fun `心跳连续超时按断线处理`() {
        connect(config = testConfig().copy(heartbeatIntervalMillis = 100, heartbeatMaxMisses = 2, reconnect = ReconnectPolicy.NONE))
        runtime.ignorePings = true
        events.await<DebugEvent.Terminated>(timeoutMillis = 3_000)
        assertEquals(SessionState.TERMINATED, session.state)
    }

    @Test
    fun `重连次数用完后会话结束并说明原因`() {
        connect(config = testConfig(ReconnectPolicy(maxAttempts = 2, initialDelayMillis = 10, maxDelayMillis = 10)))
        runtime.close()
        val terminated = events.await<DebugEvent.Terminated>()
        assertTrue(terminated.reason, terminated.reason.contains("重连 2 次都失败了"))
    }
}
