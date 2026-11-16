package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointOrigin
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import com.fixcode.intellij.lua.debug.protocol.RunToLineArgs
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 运行到光标（Run to Position）。旧版最近修过两次：暂停身份（1bbccd2）、断点与继续的顺序（3a913e6）。 */
class RunToLineBehaviorTest : BehaviorTestBase() {
    @Test
    fun `运行到光标带暂停身份 由 runtime 设置临时断点`() {
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        val pause = pauseAt(vm1, line = 10)

        session.runToLine("D:/proj/scripts/main.lua", 20).await()
        val args = RunToLineArgs.fromJson(lastRequest(Commands.RUN_TO_LINE).args)
        assertEquals(pause.target(), args.target)
        assertEquals(20, args.line)
        assertEquals(listOf("runToLine"), vm1.resumes)

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/main.lua", 15))
        val reached = pauseAt(vm1, line = 20)
        assertEquals(PauseReasons.RUN_TO_LINE, reached.reason)
    }

    @Test
    fun `没有 runToLine 能力时先等临时断点确认 再继续`() {
        connect(caps = FakeRuntime.CAPS_WITHOUT_RUN_TO_LINE) { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10)) }
        val pause = pauseAt(vm1, line = 10)
        runtime.breakpointAckDelayMillis = 300

        val done = session.runToLine("D:/proj/scripts/main.lua", 20)
        runtime.awaitCondition(description = "游戏继续运行") { vm1.resumes.isNotEmpty() }
        // 继续那一刻临时断点必须已经在 runtime 的断点表里，否则游戏会直接跑过目标行
        assertTrue(runtime.breakpointTable().values.any { it.id.startsWith("tmp-") && it.line == 20 && it.vmId == 1L })
        done.await()

        val requests = runtime.commands()
        assertTrue(requests.lastIndexOf(Commands.SET_BREAKPOINTS) < requests.lastIndexOf(Commands.CONTINUE))
        assertEquals(pause.pauseId, lastRequest(Commands.CONTINUE).args.get("pauseId").asLong)

        val reached = pauseAt(vm1, line = 20)
        assertEquals(PauseReasons.BREAKPOINT, reached.reason)
        assertTrue(reached.breakpointIds.single().startsWith("tmp-"))
    }

    @Test
    fun `先命中别的断点时临时断点也被清除`() {
        connect(caps = FakeRuntime.CAPS_WITHOUT_RUN_TO_LINE) {
            it.addBreakpoint(BreakpointRequest("scripts/main.lua", 10))
            it.addBreakpoint(BreakpointRequest("scripts/main.lua", 15))
        }
        pauseAt(vm1, line = 10)
        session.runToLine("scripts/main.lua", 30).await()
        pauseAt(vm1, line = 15)

        session.syncBreakpoints().await()
        assertTrue(session.breakpoints().none { it.request.origin == BreakpointOrigin.TEMPORARY })
        assertTrue(runtime.breakpointTable().keys.none { it.startsWith("tmp-") })
        val removes = SetBreakpointsArgs.fromJson(lastRequest(Commands.SET_BREAKPOINTS).args).removes
        assertTrue(removes.single().startsWith("tmp-"))

        session.resume().await()
        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/main.lua", 30))
    }

    @Test
    fun `没有暂停时运行到光标给出原因`() {
        connect()
        val error = session.runToLine("scripts/main.lua", 3).awaitFailure()
        assertEquals(ErrorCodes.NOT_PAUSED, error.code)
        events.assertNone<DebugEvent.BreakpointsChanged> { changed -> changed.breakpoints.any { it.request.origin == BreakpointOrigin.TEMPORARY } }
    }
}
