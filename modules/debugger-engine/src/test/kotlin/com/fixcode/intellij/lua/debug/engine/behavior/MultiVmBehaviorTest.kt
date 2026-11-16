package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.StaleReferenceException
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.Events
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import com.fixcode.intellij.lua.debug.protocol.PausedEvent
import com.fixcode.intellij.lua.debug.protocol.ResumedBy
import com.fixcode.intellij.lua.debug.protocol.StackFrameDto
import com.fixcode.intellij.lua.debug.protocol.VmInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多 VM（UE 的 PIE 多实例、多个 lua_State）：路由、排队、关闭、上下文重置。 */
class MultiVmBehaviorTest : BehaviorTestBase() {
    private fun twoVms(): Pair<com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime.FakeVm, com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime.FakeVm> {
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        val vm2 = runtime.addVm("PIE-1")
        events.await<DebugEvent.VmAdded> { it.vm.vmId == vm2.vmId }
        return vm1 to vm2
    }

    @Test
    fun `两个 VM 同时暂停时一个呈现 另一个排队 继续后自动轮到它`() {
        val (a, b) = twoVms()
        runtime.hit(a, "@scripts/main.lua", 10)
        assertTrue(events.await<DebugEvent.Paused> { it.pause.vmId == a.vmId }.focused)
        runtime.hit(b, "@scripts/main.lua", 10)
        val queued = events.await<DebugEvent.Paused> { it.pause.vmId == b.vmId }
        assertFalse(queued.focused)
        assertEquals(a.vmId, session.focusedPause()!!.vmId)

        session.resume().await()
        assertEquals(a.vmId, lastRequest(Commands.CONTINUE).args.get("vmId").asLong)
        assertTrue(b.paused) // 只继续了焦点 VM
        assertEquals(b.vmId, events.await<DebugEvent.FocusChanged>().pause!!.vmId)
        assertEquals(b.vmId, session.focusedPause()!!.vmId)
    }

    @Test
    fun `控制命令按 VM 路由`() {
        val (a, b) = twoVms()
        runtime.hit(a, "@scripts/main.lua", 10)
        runtime.hit(b, "@scripts/main.lua", 10)
        events.await<DebugEvent.Paused> { it.pause.vmId == b.vmId }

        session.stepOver(b.vmId).await()
        assertEquals(b.vmId, lastRequest(Commands.STEP_OVER).args.get("vmId").asLong)
        assertTrue(a.paused)
        assertFalse(b.paused)

        session.focus(a.vmId).await()
        session.resumeAll().await()
        assertFalse(a.paused)
    }

    @Test
    fun `迟到的旧暂停不会复活`() {
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        val pause = pauseAt(vm1, line = 10)
        session.resume().await()
        events.await<DebugEvent.Resumed>()

        runtime.sendRawEvent(Events.PAUSED, PausedEvent(1, pause.pauseId, PauseReasons.BREAKPOINT, top = pause.top))
        events.assertNone<DebugEvent.Paused>()
        assertTrue(session.pauses().isEmpty())
    }

    @Test
    fun `未登记 VM 的暂停照常呈现 不能丢`() {
        // 旧版 legacy 附加时 IDE 和原生的 VM 标识对不上，暂停被静默丢弃，游戏停着 IDE 却毫无反应
        connect()
        val top = StackFrameDto(900, "main", "@scripts/main.lua", 3)
        runtime.sendRawEvent(Events.PAUSED, PausedEvent(77, 1, PauseReasons.BREAKPOINT, "t77", top))

        val added = events.await<DebugEvent.VmAdded> { it.vm.vmId == 77L }
        assertTrue(added.vm.placeholder)
        val paused = events.await<DebugEvent.Paused>()
        assertEquals(77L, paused.pause.vmId)
        assertTrue(paused.focused)
    }

    @Test
    fun `VM 关闭后它的暂停和引用失效 迟到事件不能让它复活`() {
        val (a, b) = twoVms()
        val pause = pauseAt(a, line = 10)
        runtime.closeVm(a)

        val closed = events.await<DebugEvent.VmClosed>()
        assertEquals(a.vmId, closed.vmId)
        assertNull(session.focusedPause())
        try {
            session.stackTrace(pause).await()
            throw AssertionError("应当过期")
        } catch (expected: StaleReferenceException) {
        }

        runtime.sendRawEvent(Events.VM_ADDED, VmInfo(a.vmId, "GameLua"))
        runtime.sendRawEvent(Events.PAUSED, PausedEvent(a.vmId, pause.pauseId + 1, PauseReasons.BREAKPOINT))
        events.assertNone<DebugEvent.Paused>()
        assertEquals(listOf(b.vmId), session.vms().map { it.vmId })
    }

    @Test
    fun `上下文重置时 VM 的 sourceEpoch 更新 断点重新验证`() {
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        pauseAt(vm1, line = 10)
        session.resume().await()
        runtime.resetContext(vm1)
        val changed = events.await<DebugEvent.VmChanged> { it.vm.sourceEpoch == 2L }
        assertEquals(1L, changed.vm.vmId)
        assertEquals(ResumedBy.CONTINUE, vm1.resumes.single())
    }
}
