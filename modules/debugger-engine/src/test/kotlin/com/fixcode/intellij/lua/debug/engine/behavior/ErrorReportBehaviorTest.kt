package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.SessionConfig
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.LuaErrorDto
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 错误聚合（协议第 21 节）：选项下发、推送合并、拉全量、清空、老 runtime。 */
class ErrorReportBehaviorTest : BehaviorTestBase() {
    private val first = LuaErrorDto(
        7, 1, "main.lua:12: attempt to index a nil value", 1, 1000, 1000, "@scripts/main.lua", 12,
        uncaught = false, traceback = "\tscripts/main.lua:12: in function 'tick'\n"
    )

    @Test
    fun `协商到 errorReport 时下发 collectErrors，推送按 id 合并并保留第一次的栈`() {
        connect(config = testConfig().copy(options = SetOptionsArgs(captureOutput = true, collectErrors = "all")))
        assertTrue(session.supportsErrorReport)
        assertEquals("all", lastRequest(Commands.SET_OPTIONS).args.get("collectErrors").asString)

        runtime.pushLuaError(first)
        assertEquals(1L, events.await<DebugEvent.LuaError> { it.error.id == 7L }.error.count)
        // 之后的推送只带计数，不带栈
        runtime.pushLuaError(first.copy(count = 40, lastMs = 3000, traceback = null))
        val merged = events.await<DebugEvent.LuaError> { it.error.count == 40L }.error
        assertEquals(first.traceback, merged.traceback)
        assertEquals(3000L, merged.lastMs)
        assertEquals(1, session.cachedErrors().size)

        // 拉全量以 runtime 为准
        runtime.errorList = listOf(merged.copy(count = 55), first.copy(id = 8, message = "boom", lastMs = 4000))
        val all = session.errors().await()
        assertEquals(listOf(8L, 7L), all.items.map { it.id }) // 最近出现的在前
        assertEquals(55L, session.cachedErrors().first { it.id == 7L }.count)

        session.clearErrors().await()
        assertEquals(1, runtime.errorsCleared)
        assertTrue(session.cachedErrors().isEmpty())
    }

    @Test
    fun `老 runtime 没有 errorReport 时不发 collectErrors，errors 只回本地缓存`() {
        connect(caps = FakeRuntime.CAPS_V30, config = testConfig().copy(options = SetOptionsArgs(captureOutput = true, collectErrors = "all")))
        assertFalse(session.supportsErrorReport)
        assertNull(lastRequest(Commands.SET_OPTIONS).args.get("collectErrors"))
        assertTrue(session.errors().await().items.isEmpty())
        assertTrue(runtime.requests.none { it.command == Commands.ERRORS })
    }
}
