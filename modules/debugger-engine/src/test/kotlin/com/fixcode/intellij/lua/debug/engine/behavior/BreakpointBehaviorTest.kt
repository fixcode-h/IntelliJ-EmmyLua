package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointOrigin
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.BreakpointVerification
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.fake.FakeFrame
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.engine.fake.FakeValue
import com.fixcode.intellij.lua.debug.protocol.BreakpointStateNames
import com.fixcode.intellij.lua.debug.protocol.BreakpointStatusEvent
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.Events
import com.fixcode.intellij.lua.debug.protocol.OutputCategories
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakpointBehaviorTest : BehaviorTestBase() {
    private fun setBreakpointRequests() = runtime.requests.filter { it.command == Commands.SET_BREAKPOINTS }.map { SetBreakpointsArgs.fromJson(it.args) }

    @Test
    fun `断点增量下发 同一批改动只发一次`() {
        connect()
        val ids = session.addBreakpoints((1..3).map { BreakpointRequest("scripts/a.lua", it) }).await()
        session.syncBreakpoints().await()
        val batch = setBreakpointRequests().last()
        assertEquals(3, batch.upserts.size)
        assertEquals(2L, batch.revision)

        session.updateBreakpoint(ids[1], BreakpointRequest("scripts/a.lua", 2, condition = "x > 1")).await()
        session.removeBreakpoint(ids[2]).await()
        session.syncBreakpoints().await()
        val deltas = setBreakpointRequests().drop(2)
        assertEquals(listOf(ids[1]), deltas.flatMap { d -> d.upserts.map { it.id } })
        assertEquals(listOf(ids[2]), deltas.flatMap { it.removes })
        assertEquals(setOf(ids[0], ids[1]), runtime.breakpointTable().keys)
    }

    @Test
    fun `手动指定映射后 断点带着新的 chunkName 重新下发`() {
        connect()
        val id = session.addBreakpoint(BreakpointRequest("/project/Content/Script/ui/main.lua", 5)).await()
        session.syncBreakpoints().await()
        assertEquals(null, runtime.breakpointTable()[id]!!.source.chunkName)

        session.overrideMapping("@scripts/ui/main.lua", "/project/Content/Script/ui/main.lua").await()
        session.syncBreakpoints().await()
        assertEquals("@scripts/ui/main.lua", runtime.breakpointTable()[id]!!.source.chunkName)
    }

    @Test
    fun `IDE 断点和 Agent 断点在同一张表 清除 Agent 断点不影响 IDE 断点`() {
        connect()
        val ide = session.addBreakpoint(BreakpointRequest("scripts/a.lua", 1)).await()
        val agent = session.addBreakpoint(BreakpointRequest("scripts/a.lua", 2, origin = BreakpointOrigin.AGENT)).await()
        session.syncBreakpoints().await()
        assertEquals(setOf(ide, agent), runtime.breakpointTable().keys)

        val removed = session.removeBreakpoints { it.request.origin == BreakpointOrigin.AGENT }.await()
        session.syncBreakpoints().await()
        assertEquals(listOf(agent), removed)
        assertEquals(setOf(ide), runtime.breakpointTable().keys)
    }

    @Test
    fun `条件 命中次数 日志断点在 runtime 侧执行`() {
        connect(config = testConfig())
        session.addBreakpoints(
            listOf(
                BreakpointRequest("scripts/bag.lua", 5, condition = "index > 5"),
                BreakpointRequest("scripts/bag.lua", 6, hitCondition = ">= 2"),
                BreakpointRequest("scripts/bag.lua", 7, logMessage = "click {index}")
            )
        ).await()
        session.syncBreakpoints().await()
        fun frame(line: Int, index: Int) = listOf(FakeFrame.lua("OnClick", "@scripts/bag.lua", line, "index" to FakeValue.num(index)))

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/bag.lua", 5, frame(5, 3)))
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.hit(vm1, "@scripts/bag.lua", 5, frame(5, 7)))
        events.await<DebugEvent.Paused>()
        session.resume().await()

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/bag.lua", 6, frame(6, 1)))
        assertEquals(FakeRuntime.HitResult.PAUSED, runtime.hit(vm1, "@scripts/bag.lua", 6, frame(6, 1)))
        events.await<DebugEvent.Paused>()
        session.resume().await()

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/bag.lua", 7, frame(7, 9)))
        val log = events.await<DebugEvent.Output> { it.output.category == OutputCategories.LOGPOINT }
        assertEquals("click 9\n", log.output.text)
    }

    @Test
    fun `Probe 命中不停 采集值按序号可读`() {
        // 原 CLI 的 Probe 变成断点的 capture + autoContinue 属性
        connect()
        val id = session.addBreakpoint(
            BreakpointRequest("scripts/bag.lua", 9, capture = listOf("item.id", "missing"), autoContinue = true, origin = BreakpointOrigin.AGENT)
        ).await()
        session.syncBreakpoints().await()
        val stack = listOf(FakeFrame.lua("Use", "@scripts/bag.lua", 9, "item" to FakeValue.table("id" to FakeValue.num(1003))))

        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/bag.lua", 9, stack))
        assertEquals(FakeRuntime.HitResult.CONTINUED, runtime.hit(vm1, "@scripts/bag.lua", 9, stack))
        val hit = events.await<DebugEvent.BreakpointHit>()
        assertEquals(id, hit.hit.id)
        assertEquals("1003", hit.hit.captures.first().value)
        events.await<DebugEvent.BreakpointHit> { it.hit.hitCount == 2L }

        val records = session.breakpointHits(sinceSeq = hit.seq)
        assertEquals(listOf(2L), records.map { it.value.hitCount })
        events.assertNone<DebugEvent.Paused>()
    }

    @Test
    fun `验证状态 pending 到 verified 语法错误为 invalid`() {
        connect()
        val good = session.addBreakpoint(BreakpointRequest("scripts/a.lua", 3)).await()
        val bad = session.addBreakpoint(BreakpointRequest("scripts/a.lua", 4, condition = "syntax error here")).await()
        session.syncBreakpoints().await()
        val views = session.breakpoints().associateBy { it.id }
        assertEquals(BreakpointVerification.PENDING, views[good]!!.verification)
        assertEquals(BreakpointVerification.INVALID, views[bad]!!.verification)
        assertEquals("条件表达式语法错误", views[bad]!!.message)

        runtime.hit(vm1, "@scripts/a.lua", 3)
        events.await<DebugEvent.BreakpointsChanged> { e -> e.breakpoints.any { it.id == good && it.verification == BreakpointVerification.VERIFIED } }
    }

    @Test
    fun `禁用的断点不下发`() {
        connect()
        val id = session.addBreakpoint(BreakpointRequest("scripts/a.lua", 3, enabled = false)).await()
        session.syncBreakpoints().await()
        assertTrue(runtime.breakpointTable().isEmpty())
        session.updateBreakpoint(id, BreakpointRequest("scripts/a.lua", 3)).await()
        session.syncBreakpoints().await()
        assertEquals(setOf(id), runtime.breakpointTable().keys)
    }

    @Test
    fun `设备上的代码和本地不一致时提示`() {
        connect()
        val id = session.addBreakpoint(BreakpointRequest("scripts/a.lua", 3)).await()
        session.syncBreakpoints().await()
        runtime.sendRawEvent(Events.BREAKPOINT_STATUS, BreakpointStatusEvent(id, 1, BreakpointStateNames.MISMATCH, "@scripts/a.lua", 3))
        val mismatch = events.await<DebugEvent.SourceMismatch>()
        assertEquals(id, mismatch.breakpointId)
        assertEquals(BreakpointVerification.MISMATCH, session.breakpoints().single().verification)
    }
}
