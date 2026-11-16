package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.ProfileFunctionDto
import com.fixcode.intellij.lua.debug.protocol.ProfileSorts
import com.fixcode.intellij.lua.debug.protocol.ProfileStackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 采样性能分析（协议第 20 节）：能力协商、开始 / 停止 / 取结果、断线停止。 */
class ProfilerBehaviorTest : BehaviorTestBase() {
    private fun seed() {
        runtime.profileData[1L] = listOf(
            ProfileFunctionDto("tick", "@scripts/main.lua", 7, 10, 90, 0.0, 0.0),
            ProfileFunctionDto("add", "@scripts/main.lua", 3, 80, 80, 0.0, 0.0)
        ) to listOf(
            ProfileStackDto("tick (scripts/main.lua:7);add (scripts/main.lua:3)", 80),
            ProfileStackDto("tick (scripts/main.lua:7)", 10)
        )
    }

    @Test
    fun `握手协商到 profiler 后可以开始 停止并取到函数表和折叠栈`() {
        connect()
        seed()
        assertTrue(session.supportsProfiler)
        val started = session.profileStart(intervalInstructions = 500).await()
        assertEquals(listOf(1L), started.vms)
        assertEquals(500, started.intervalInstructions)
        assertTrue(runtime.isProfiling(1))
        assertEquals("start", lastRequest(Commands.PROFILE).args.get("action").asString)

        val live = session.profileResult(sort = ProfileSorts.TOTAL, limit = 1).await()
        assertTrue(live.running)
        assertEquals(1, live.functions.size)
        assertEquals("tick", live.functions[0].fn)
        assertEquals(2, live.totalFunctions)

        val result = session.profileStop(stackLimit = 1).await()
        assertFalse(result.running)
        assertFalse(runtime.isProfiling(1))
        assertEquals("add", result.functions[0].fn) // 默认按自身样本排序
        assertEquals(80.0 * 100 / 90, result.functions[0].selfPercent, 0.01)
        assertEquals(1, result.stacks.size)
        assertTrue(result.stacksTruncated)
        assertEquals("tick (scripts/main.lua:7);add (scripts/main.lua:3) 80\n", result.folded())
    }

    @Test
    fun `老 runtime 没有 profiler 能力时不发 profile 直接报不支持`() {
        connect(caps = FakeRuntime.CAPS_V30)
        assertFalse(session.supportsProfiler)
        assertFalse(session.hasCapability(Capabilities.PROFILER))
        val error = session.profileStart().awaitFailure()
        assertEquals(ErrorCodes.UNSUPPORTED, error.code)
        assertFalse(runtime.commands().contains(Commands.PROFILE))
    }

    @Test
    fun `参数越界时把 runtime 的说明原样带回`() {
        connect()
        val error = session.profileStart(intervalInstructions = 5).awaitFailure()
        assertEquals(ErrorCodes.INVALID_ARGS, error.code)
        assertTrue(error.message!!.contains("intervalInstructions"))
        assertEquals(ErrorCodes.VM_NOT_FOUND, session.profileResult(vmId = 99).awaitFailure().code)
    }

    @Test
    fun `断开连接后 runtime 停止采样`() {
        connect()
        session.profileStart().await()
        assertTrue(runtime.isProfiling(1))
        session.stop().await()
        runtime.awaitCondition(description = "断开后停止采样") { !runtime.isProfiling(1) }
    }
}
