package com.tang.intellij.lua.debug.engine.behavior

import com.tang.intellij.lua.debug.engine.BreakpointRequest
import com.tang.intellij.lua.debug.engine.DebugEngineException
import com.tang.intellij.lua.debug.engine.MappingRule
import com.tang.intellij.lua.debug.engine.SourceFileIndex
import com.tang.intellij.lua.debug.engine.SourceMapper
import com.tang.intellij.lua.debug.engine.StaleReferenceException
import com.tang.intellij.lua.debug.engine.fake.FakeFrame
import com.tang.intellij.lua.debug.engine.fake.FakeRuntime
import com.tang.intellij.lua.debug.engine.fake.FakeValue
import com.tang.intellij.lua.debug.protocol.Capabilities
import com.tang.intellij.lua.debug.protocol.Commands
import com.tang.intellij.lua.debug.protocol.ErrorCodes
import com.tang.intellij.lua.debug.protocol.EvalModes
import com.tang.intellij.lua.debug.protocol.SetOptionsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 暂停后按需取数据（协议第 9、10 节）。 */
class VariableBehaviorTest : BehaviorTestBase() {
    private val bag = FakeValue.table(
        "items" to FakeValue.list(FakeValue.table("id" to FakeValue.num(1003), "count" to FakeValue.num(2))),
        "owner" to FakeValue.str("player"),
        typeName = "BagPanel"
    )

    private fun pausedInBag(caps: List<String> = FakeRuntime.ALL_CAPS, options: SetOptionsArgs = SetOptionsArgs(captureOutput = true), depth: Int = 1) = run {
        connect(caps = caps, config = testConfig().copy(options = options)) { it.addBreakpoint(BreakpointRequest("scripts/bag.lua", 120)) }
        val stack = listOf(FakeFrame.lua("BagPanel:OnItemClick", "@scripts/bag.lua", 120, "self" to bag, "index" to FakeValue.num(1))) +
            (1 until depth).map { FakeFrame.lua("caller$it", "@scripts/caller.lua", it) }
        pauseAt(vm1, "@scripts/bag.lua", 120, stack)
    }

    @Test
    fun `暂停事件自带栈顶帧 栈按需分页`() {
        val pause = pausedInBag(depth = 30)
        assertEquals("BagPanel:OnItemClick", pause.top!!.name)
        assertTrue(Commands.STACK_TRACE !in runtime.commands()) // 暂停时不主动拉栈

        val first = session.stackTrace(pause, 0, 20).await()
        assertEquals(30, first.total)
        assertEquals(20, first.frames.size)
        val rest = session.stackTrace(pause, 20, 20).await()
        assertEquals(10, rest.frames.size)
        assertEquals("caller29", rest.frames.last().frame.name)
    }

    @Test
    fun `同一次暂停的栈只请求一次`() {
        // XDebugger 的栈视图和内联值都要栈，不应各请求一次
        val pause = pausedInBag()
        session.stackTrace(pause).await()
        session.stackTrace(pause).await()
        assertEquals(1, runtime.commands().count { it == Commands.STACK_TRACE })
    }

    @Test
    fun `作用域和嵌套 table 按引用逐层展开`() {
        // 旧版生产调试里变量引用展开失败（29d82f9），这里覆盖逐层展开
        val pause = pausedInBag()
        val frame = session.stackTrace(pause).await().frames.first()
        val locals = session.scopes(frame).await().single { it.name == "locals" }
        val vars = session.variables(locals).await().variables.associateBy { it.variable.name }
        assertEquals("BagPanel", vars.getValue("self").variable.typeName)
        assertEquals("1", vars.getValue("index").variable.value)

        val self = session.variables(vars.getValue("self").children()!!).await().variables.associateBy { it.variable.name }
        val items = session.variables(self.getValue("items").children()!!).await()
        assertEquals(1, items.total)
        val item = session.variables(items.variables.single().children()!!).await().variables
        assertEquals(listOf("id" to "1003", "count" to "2"), item.map { it.variable.name to it.variable.value })
        assertNull(item.first().children())
    }

    @Test
    fun `继续之后旧的栈帧和变量给出明确的过期提示`() {
        val pause = pausedInBag()
        val frame = session.stackTrace(pause).await().frames.first()
        val locals = session.scopes(frame).await().first()
        session.resume().await()

        for (call in listOf({ session.scopes(frame) }, { session.variables(locals) }, { session.evaluate(pause, "index") })) {
            try {
                call().await()
                fail("应当过期")
            } catch (error: StaleReferenceException) {
                assertTrue(error.message!!.contains("已失效"))
            }
        }
        // 过期判断在本地完成，不用往返 runtime
        assertEquals(1, runtime.commands().count { it == Commands.SCOPES })
    }

    @Test
    fun `取值路径求值 和 任意 Lua 求值的权限`() {
        val pause = pausedInBag(options = SetOptionsArgs(allowLuaEval = false))
        val value = session.evaluate(pause, "self.items[1].id", mode = EvalModes.VALUE_PATH).await()
        assertEquals("1003", value.value)
        assertEquals("number", value.type)

        val forbidden = session.evaluate(pause, "index + 1", mode = EvalModes.LUA).awaitFailure()
        assertEquals("EVAL_FORBIDDEN", forbidden.code)
        assertTrue(forbidden.message!!.contains("valuePath"))
    }

    @Test
    fun `runtime 没有 luaEval 能力时本地直接拒绝`() {
        val pause = pausedInBag(caps = FakeRuntime.ALL_CAPS - Capabilities.LUA_EVAL)
        val error = session.evaluate(pause, "index").awaitFailure()
        assertEquals(ErrorCodes.UNSUPPORTED, error.code)
        assertTrue(Commands.EVALUATE !in runtime.commands())
    }

    @Test
    fun `求值出错时返回 Lua 错误原文`() {
        val pause = pausedInBag()
        val error = session.evaluate(pause, "nope.field", mode = EvalModes.VALUE_PATH).awaitFailure()
        assertEquals(ErrorCodes.EVAL_ERROR, error.code)
        assertTrue(error !is StaleReferenceException)
    }

    @Test
    fun `修改变量后重新读取到新值`() {
        val pause = pausedInBag()
        val frame = session.topFrame(pause)!!
        val locals = session.scopes(frame).await().first()
        val result = session.setVariable(locals, "index", "8").await()
        assertEquals("8", result.value)
        val index = session.variables(locals).await().variables.first { it.variable.name == "index" }
        assertEquals("8", index.variable.value)
    }

    @Test
    fun `栈帧映射到本地文件`() {
        val index = object : SourceFileIndex {
            override fun exists(path: String) = path == "d:/Game/Content/Script/bag.lua"
            override fun findBySuffix(segments: List<String>, caseSensitive: Boolean) = emptyList<String>()
            override fun sha256(path: String): String? = null
        }
        startRuntime()
        connect(mapper = SourceMapper(listOf(MappingRule("scripts", "D:/Game/Content/Script")), index)) {
            it.addBreakpoint(BreakpointRequest("D:/Game/Content/Script/bag.lua", 120))
        }
        val pause = pauseAt(vm1, "@scripts/bag.lua", 120)
        assertEquals("d:/Game/Content/Script/bag.lua", session.topFrame(pause)!!.localPath)
        val cFrame = FakeFrame.c("pcall")
        session.resume().await()
        val withC = pauseAt(vm1, "@scripts/bag.lua", 120, listOf(FakeFrame.lua("f", "@scripts/bag.lua", 120), cFrame))
        val frames = session.stackTrace(withC).await().frames
        assertNull(frames[1].localPath)
    }

    @Test
    fun `没有 setVariable 能力时说明原因`() {
        val pause = pausedInBag(caps = FakeRuntime.ALL_CAPS - Capabilities.SET_VARIABLE)
        val locals = session.scopes(session.topFrame(pause)!!).await().first()
        val error = session.setVariable(locals, "index", "2").awaitFailure()
        assertEquals(ErrorCodes.UNSUPPORTED, error.code)
        assertTrue(error is DebugEngineException)
    }
}
