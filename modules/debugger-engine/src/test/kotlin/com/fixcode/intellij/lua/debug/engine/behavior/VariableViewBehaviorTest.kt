package com.fixcode.intellij.lua.debug.engine.behavior

import com.google.gson.JsonObject
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.fake.FakeFrame
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.engine.fake.FakeValue
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import com.fixcode.intellij.lua.debug.protocol.VariablesArgs
import com.fixcode.intellij.lua.debug.protocol.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 变量视图增量（variable-view.md 13 节）：能力协商、选项下发、分组。没协商时行为和 v3.0 一致。 */
class VariableViewBehaviorTest : BehaviorTestBase() {
    private val view = jsonObject { put("sort", "smart"); put("groupMethods", true) }
    private val rules = jsonObject { put("types", JsonObject()) }

    private fun configWithView() = testConfig().copy(
        options = SetOptionsArgs(captureOutput = true, variableView = view),
        viewRules = rules,
        maxValueLength = 8
    )

    private fun pausedWithMeta(caps: List<String>) = run {
        val obj = FakeValue.table(
            "name" to FakeValue.str("a very long player name"),
            "(metatable)" to FakeValue.table("__index" to FakeValue.table())
        )
        connect(caps = caps, config = configWithView()) { it.addBreakpoint(BreakpointRequest("scripts/a.lua", 3)) }
        pauseAt(vm1, "@scripts/a.lua", 3, listOf(FakeFrame.lua("f", "@scripts/a.lua", 3, "obj" to obj)))
    }

    private fun objContainer(caps: List<String>) = run {
        val pause = pausedWithMeta(caps)
        val frame = session.stackTrace(pause).await().frames.first()
        val locals = session.scopes(frame).await().single { it.name == "locals" }
        session.variables(locals).await().variables.single().children()!!
    }

    @Test
    fun `协商了 richVariables 时握手下发显示规则和 variableView`() {
        connect(config = configWithView())
        assertEquals(view, runtime.options().variableView)
        assertEquals(rules, runtime.viewRules)
        val commands = runtime.commands()
        assertTrue(commands.indexOf(Commands.SET_VIEW_RULES) < commands.indexOf(Commands.SET_OPTIONS))
    }

    @Test
    fun `v3_0 runtime 不发 variableView 和显示规则`() {
        connect(caps = FakeRuntime.CAPS_V30, config = configWithView())
        assertNull(runtime.options().variableView)
        assertFalse(Commands.SET_VIEW_RULES in runtime.commands())
    }

    @Test
    fun `设置变化时只发改动的项 重连后握手带上最新值`() {
        connect(config = configWithView())
        val changed = jsonObject { put("sort", "name") }
        session.updateOptions(SetOptionsArgs(variableView = changed)).await()
        val last = runtime.requests.last { it.command == Commands.SET_OPTIONS }.args
        assertEquals(setOf("variableView"), last.keySet())
        assertEquals(changed, runtime.options().variableView)

        runtime.dropClient()
        events.await<com.fixcode.intellij.lua.debug.engine.DebugEvent.Connected> { it.reconnect }
        assertEquals(changed, runtime.options().variableView)
    }

    @Test
    fun `richVariables 时分组来自 runtime 并带上 maxValueLength`() {
        val obj = objContainer(FakeRuntime.ALL_CAPS)
        val page = session.variables(obj).await()
        assertEquals(listOf("name"), page.variables.map { it.variable.name })
        assertEquals(listOf("(metatable)"), page.groups.map { it.variable.name })
        assertEquals(8, VariablesArgs.fromJson(runtime.requests.last { it.command == Commands.VARIABLES }.args).maxValueLength)
        assertTrue(page.variables.single().variable.hasAttr("truncated"))
    }

    @Test
    fun `v3_0 runtime 的元表伪项由引擎挪进分组`() {
        val obj = objContainer(FakeRuntime.CAPS_V30)
        val page = session.variables(obj).await()
        assertEquals(listOf("name"), page.variables.map { it.variable.name })
        assertEquals(listOf("(metatable)"), page.groups.map { it.variable.name })
        assertEquals(1, page.total)
        assertNull(VariablesArgs.fromJson(runtime.requests.last { it.command == Commands.VARIABLES }.args).maxValueLength)
    }
}
