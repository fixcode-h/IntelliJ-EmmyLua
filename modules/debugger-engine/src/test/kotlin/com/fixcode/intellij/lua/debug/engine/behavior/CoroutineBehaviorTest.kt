package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.fake.FakeFrame
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.engine.fake.FakeValue
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.ThreadStatuses
import org.junit.Assert.assertEquals
import org.junit.Test

/** 协程视图（协议 6.4、9.1）：暂停时列出协程，按 threadId 取别的协程的栈，变量照常可读。 */
class CoroutineBehaviorTest : BehaviorTestBase() {
    private fun pausedWithCoroutine(caps: List<String> = FakeRuntime.ALL_CAPS) = run {
        connect(caps = caps) { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        vm1.coroutines["co-1"] = listOf(
            FakeFrame.lua("Loader:Wait", "@scripts/loader.lua", 42, "pending" to FakeValue.num(3)),
            FakeFrame.lua("Loader:Run", "@scripts/loader.lua", 30)
        )
        pauseAt(vm1, "@scripts/main.lua", 10)
    }

    @Test
    fun `列出协程 取另一个协程的栈和变量`() {
        val pause = pausedWithCoroutine()
        val threads = session.threads(pause).await()
        assertEquals(listOf(ThreadStatuses.RUNNING, ThreadStatuses.SUSPENDED), threads.map { it.status })

        val co = session.stackTrace(pause, threadId = "co-1").await()
        assertEquals(listOf("Loader:Wait", "Loader:Run"), co.frames.map { it.frame.name })
        assertEquals(2, co.total)
        val locals = session.scopes(co.frames.first()).await().single { it.name == "locals" }
        assertEquals("3", session.variables(locals).await().variables.single().variable.value)

        // 当前线程的栈不受影响，各自缓存
        assertEquals("main", session.stackTrace(pause).await().frames.first().frame.name)
    }

    @Test
    fun `runtime 没有 coroutines 能力时只有当前线程 不发请求`() {
        val pause = pausedWithCoroutine(FakeRuntime.ALL_CAPS - Capabilities.COROUTINES)
        val threads = session.threads(pause).await()
        assertEquals(1, threads.size)
        assertEquals(0, runtime.commands().count { it == Commands.THREADS })
    }
}
