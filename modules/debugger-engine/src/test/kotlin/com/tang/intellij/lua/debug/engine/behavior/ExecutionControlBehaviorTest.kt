package com.tang.intellij.lua.debug.engine.behavior

import com.tang.intellij.lua.debug.engine.DebugEvent
import com.tang.intellij.lua.debug.engine.Initiator
import com.tang.intellij.lua.debug.engine.SessionState
import com.tang.intellij.lua.debug.protocol.Commands
import com.tang.intellij.lua.debug.protocol.ErrorCodes
import com.tang.intellij.lua.debug.protocol.ExceptionModes
import com.tang.intellij.lua.debug.protocol.PauseReasons
import com.tang.intellij.lua.debug.protocol.PauseTarget
import com.tang.intellij.lua.debug.protocol.ResumedBy
import com.tang.intellij.lua.debug.protocol.SetOptionsArgs
import com.tang.intellij.lua.debug.engine.BreakpointRequest
import com.tang.intellij.lua.debug.engine.fake.FakeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionControlBehaviorTest : BehaviorTestBase() {
    private fun pausedOnBreakpoint() = run {
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        pauseAt(vm1, "@scripts/main.lua", 10)
    }

    @Test
    fun `继续带上暂停身份 目标真正恢复运行`() {
        // 旧版先清空暂停快照再发不带 pauseId 的继续，runtime 拒绝后无人处理，游戏一直停在断点上
        val pause = pausedOnBreakpoint()
        session.resume().await()

        assertEquals(PauseTarget(1, pause.pauseId, pause.threadId), PauseTarget.fromJson(lastRequest(Commands.CONTINUE).args))
        assertEquals(listOf(ResumedBy.CONTINUE), vm1.resumes)
        val resumed = events.await<DebugEvent.Resumed>()
        assertEquals(Initiator.USER, resumed.initiator)
        assertTrue(session.pauses().isEmpty())
    }

    @Test
    fun `三种单步都带暂停身份 下一行停下时原因是 step`() {
        val first = pausedOnBreakpoint()
        val steps = listOf(
            Commands.STEP_OVER to { session.stepOver() },
            Commands.STEP_IN to { session.stepInto() },
            Commands.STEP_OUT to { session.stepOut() }
        )
        var pause = first
        for ((command, step) in steps) {
            step().await()
            assertEquals(pause.target(), PauseTarget.fromJson(lastRequest(command).args))
            pause = pauseAt(vm1, "@scripts/main.lua", 11)
            assertEquals(PauseReasons.STEP, pause.reason)
        }
        assertEquals(first.pauseId + 3, pause.pauseId)
    }

    @Test
    fun `runtime 拒绝继续时保持暂停并报错 不假装已经恢复`() {
        val pause = pausedOnBreakpoint()
        runtime.failNext(Commands.CONTINUE, ErrorCodes.INTERNAL, "命令队列已满")

        val error = session.resume().awaitFailure()
        assertEquals("命令队列已满", error.message)
        assertEquals(listOf(pause), session.pauses())
        assertTrue(session.isCurrent(pause))
        events.assertNone<DebugEvent.Resumed>()

        // 再点一次继续可以成功
        session.resume().await()
        assertEquals(listOf(ResumedBy.CONTINUE), vm1.resumes)
    }

    @Test
    fun `runtime 那边暂停已结束时本地跟着结束 不报错`() {
        pausedOnBreakpoint()
        runtime.failNext(Commands.CONTINUE, ErrorCodes.STALE_PAUSE, "这次暂停已经结束")
        session.resume().await()
        assertTrue(session.pauses().isEmpty())
        events.await<DebugEvent.Resumed>()
    }

    @Test
    fun `没有暂停时继续什么都不做`() {
        connect()
        session.resume().await()
        assertTrue(Commands.CONTINUE !in runtime.commands())
    }

    @Test
    fun `Break All 在 VM 下一次执行 Lua 时生效`() {
        connect()
        session.pause().await()
        assertEquals(0L, lastRequest(Commands.PAUSE).args.get("vmId").asLong)
        val pause = pauseAt(vm1, line = 42)
        assertEquals(PauseReasons.PAUSE, pause.reason)
        assertEquals(42, pause.top!!.line)
    }

    @Test
    fun `停止调试时 runtime 恢复所有暂停的 VM`() {
        // 旧版断开附加后目标停在断点上，只能重启进程
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        val vm2 = runtime.addVm("Second")
        events.await<DebugEvent.VmAdded> { it.vm.vmId == vm2.vmId }
        pauseAt(vm1, line = 10)
        pauseAt(vm2, line = 10)

        session.stop().await()
        assertEquals(SessionState.TERMINATED, session.state)
        assertTrue(Commands.DISCONNECT in runtime.commands())
        runtime.awaitCondition(description = "两个 VM 都恢复") { !vm1.paused && !vm2.paused }
        assertEquals(listOf(ResumedBy.DISCONNECT), vm1.resumes)
        assertEquals(listOf(ResumedBy.DISCONNECT), vm2.resumes)
        assertTrue(runtime.breakpointTable().isEmpty())
        events.await<DebugEvent.Terminated>()
    }

    @Test
    fun `出错即停带上错误信息`() {
        connect(config = testConfig().copy(options = SetOptionsArgs(exceptions = ExceptionModes.UNCAUGHT)))
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.error(vm1, "@scripts/bag.lua", 7, "attempt to index a nil value (local 'item')"))
        val pause = events.await<DebugEvent.Paused>().pause
        assertEquals(PauseReasons.EXCEPTION, pause.reason)
        assertEquals("attempt to index a nil value (local 'item')", pause.exception!!.message)
    }

    @Test
    fun `Agent 发起的继续在事件里标明来源`() {
        pausedOnBreakpoint()
        session.resume(initiator = Initiator.AGENT).await()
        assertEquals(Initiator.AGENT, events.await<DebugEvent.Resumed>().initiator)
        assertNull(session.focusedPause())
    }
}
