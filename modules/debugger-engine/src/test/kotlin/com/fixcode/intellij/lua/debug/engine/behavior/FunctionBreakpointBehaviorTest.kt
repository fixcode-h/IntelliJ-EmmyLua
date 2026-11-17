package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointOrigin
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.BreakpointVerification
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.fake.FakeFrame
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.engine.fake.FakeValue
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.OutputCategories
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 函数断点（协议 7.7）：按函数名下断点，不用知道文件；runtime 停在函数第一行。 */
class FunctionBreakpointBehaviorTest : BehaviorTestBase() {
    private fun setBreakpointRequests() = runtime.requests.filter { it.command == Commands.SET_BREAKPOINTS }.map { SetBreakpointsArgs.fromJson(it.args) }

    @Test
    fun `函数断点下发 function 不带行号和文件 命中后停在第一行并验证到定义处`() {
        connect()
        val id = session.addBreakpoint(BreakpointRequest.function("UI.BagPanel:OnItemClick")).await()
        session.syncBreakpoints().await()
        val spec = runtime.breakpointTable()[id]!!
        assertEquals("UI.BagPanel:OnItemClick", spec.function)
        assertNull(spec.source)
        assertEquals(0, spec.line)
        assertEquals(BreakpointVerification.PENDING, session.breakpoints().single().verification)

        assertEquals(
            FakeRuntime.HitResult.PAUSED,
            runtime.call(vm1, "UI.BagPanel:OnItemClick", "@Content/Script/UI/BagPanel.lua", firstLine = 121, lineDefined = 120)
        )
        val paused = events.await<DebugEvent.Paused>()
        assertEquals(listOf(id), paused.pause.breakpointIds)
        assertEquals(121, paused.pause.top?.line)
        val view = session.breakpoints().single()
        assertEquals(BreakpointVerification.VERIFIED, view.verification)
        assertEquals(120, view.actualLine)
        assertEquals("@Content/Script/UI/BagPanel.lua", view.chunkName)
    }

    @Test
    fun `函数断点的条件 命中次数 日志照行断点处理 限定文件时带 source`() {
        connect()
        session.addBreakpoints(
            listOf(
                BreakpointRequest.function("M.add", condition = "a > 5"),
                BreakpointRequest.function("tick", logMessage = "tick {i}", origin = BreakpointOrigin.AGENT),
                BreakpointRequest.function("Other.fn", file = "scripts/other.lua")
            )
        ).await()
        session.syncBreakpoints().await()
        assertEquals("scripts/other.lua", setBreakpointRequests().last().upserts.single { it.function == "Other.fn" }.source!!.path)
        fun frame(name: String, vararg locals: Pair<String, FakeValue>) = listOf(FakeFrame.lua(name, "@scripts/main.lua", 4, *locals))

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.call(vm1, "M.add", "@scripts/main.lua", 4, stack = frame("add", "a" to FakeValue.num(1))))
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.call(vm1, "M.add", "@scripts/main.lua", 4, stack = frame("add", "a" to FakeValue.num(7))))
        events.await<DebugEvent.Paused>()
        session.resume().await()

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.call(vm1, "tick", "@scripts/main.lua", 8, stack = frame("tick", "i" to FakeValue.num(3))))
        val log = events.await<DebugEvent.Output> { it.output.category == OutputCategories.LOGPOINT }
        assertEquals("tick 3\n", log.output.text)

        // 文件对不上：不停
        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.call(vm1, "Other.fn", "@scripts/main.lua", 30))
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.call(vm1, "Other.fn", "@scripts/other.lua", 3))
    }

    @Test
    fun `runtime 没有函数断点能力时不下发 显示原因 行断点照常`() {
        connect(caps = FakeRuntime.ALL_CAPS - com.fixcode.intellij.lua.debug.protocol.Capabilities.FUNCTION_BREAKPOINTS)
        val fn = session.addBreakpoint(BreakpointRequest.function("M.add")).await()
        val line = session.addBreakpoint(BreakpointRequest("scripts/main.lua", 3)).await()
        session.syncBreakpoints().await()
        assertEquals(setOf(line), runtime.breakpointTable().keys)
        val view = session.breakpoints().single { it.id == fn }
        assertEquals(BreakpointVerification.INVALID, view.verification)
        assertTrue(view.message!!.contains("functionBreakpoints"))
    }

    @Test
    fun `断线重连后函数断点随全量重发`() {
        connect { it.addBreakpoint(BreakpointRequest.function("GameMode:BeginPlay")) }
        runtime.dropClient()
        assertTrue(events.await<DebugEvent.Connected> { it.reconnect }.reconnect)
        session.syncBreakpoints().await()
        val resent = setBreakpointRequests().last()
        assertTrue(resent.replaceAll)
        assertEquals(listOf("GameMode:BeginPlay"), resent.upserts.map { it.function })
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.call(vm1, "GameMode:BeginPlay", "@scripts/GameMode.lua", 5))
    }
}
