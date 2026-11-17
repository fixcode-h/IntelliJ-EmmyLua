package com.fixcode.intellij.lua.debug.engine.behavior

import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.SessionState
import com.fixcode.intellij.lua.debug.engine.fake.FakeRuntime
import com.fixcode.intellij.lua.debug.engine.transport.TcpConnector
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.Commands
import com.fixcode.intellij.lua.debug.protocol.ErrorCodes
import com.fixcode.intellij.lua.debug.protocol.HelperScript
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import com.fixcode.intellij.lua.debug.protocol.SetBreakpointsArgs
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HandshakeBehaviorTest : BehaviorTestBase() {
    @Test
    fun `握手后依次下发 helpers 选项 全量断点 最后 configurationDone`() {
        val welcome = connect(config = testConfig().copy(helpers = listOf(HelperScript("emmyHelper", "-- helper")))) {
            it.addBreakpoint(BreakpointRequest("D:/Game/Script/main.lua", 3))
        }
        assertEquals("fake-runtime", welcome.runtime.name)
        assertEquals(
            listOf(Commands.HELLO, Commands.LOAD_HELPERS, Commands.SET_OPTIONS, Commands.SET_BREAKPOINTS, Commands.CONFIGURATION_DONE),
            runtime.commands()
        )
        val full = SetBreakpointsArgs.fromJson(lastRequest(Commands.SET_BREAKPOINTS).args)
        assertTrue(full.replaceAll)
        assertEquals(1L, full.revision)
        assertEquals("d:/Game/Script/main.lua", full.upserts.single().source!!.path)
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(listOf(1L), session.vms().map { it.vmId })
    }

    @Test
    fun `类型显示脚本发的是内容而不是本机路径`() {
        // 旧协议 InitReq 传的是本机磁盘路径，真机上不存在
        connect(config = testConfig().copy(helpers = listOf(HelperScript("emmyHelper_ue", "return { FVector = true }"))))
        val helpers = runtime.helpers!!
        assertEquals("return { FVector = true }", helpers.scripts.single().source)
        assertEquals("emmyHelper", helpers.main)
    }

    @Test
    fun `启动即停在 configurationDone 之后的第一行停下`() {
        connect(config = testConfig().copy(options = SetOptionsArgs(stopOnEntry = true)))
        val pause = pauseAt(vm1, line = 1)
        assertEquals(PauseReasons.ENTRY, pause.reason)
    }

    @Test
    fun `首个断点在游戏开始执行前就已生效`() {
        // configurationDone 之前 runtime 不放行 waitIDE；断点在它之前下发，第一次执行就能命中
        connect { it.addBreakpoint(BreakpointRequest("scripts/main.lua", 1)) }
        assertTrue(runtime.isConfigured())
        val pause = pauseAt(vm1, "@scripts/main.lua", 1)
        assertEquals(PauseReasons.BREAKPOINT, pause.reason)
    }

    @Test
    fun `旧版 runtime 握手失败并提示升级`() {
        val legacy = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            legacy.accept().use { socket ->
                socket.getOutputStream().write("0\n{\"cmd\":0}\n".toByteArray())
                socket.getOutputStream().flush()
                Thread.sleep(500)
            }
        }
        legacy.use {
            val session = DebugSession(testConfig(), TcpConnector("127.0.0.1", legacy.localPort))
            session.use {
                val error = it.start().awaitFailure()
                assertTrue(error.message, error.message!!.contains("版本过旧"))
                assertEquals(SessionState.FAILED, it.state)
            }
        }
    }

    @Test
    fun `配对码不对时握手失败并说明原因`() {
        startRuntime(token = "8F3K-29QX")
        runtime.addVm()
        newSession(testConfig().copy(token = "WRONG"))
        val error = session.start().awaitFailure()
        assertTrue(error.message, error.message!!.contains("配对码"))
        assertEquals(SessionState.FAILED, session.state)
    }

    @Test
    fun `建连时才拿到的 token 优先于配置 真机附加注入后才生成`() {
        startRuntime(token = "AGENT-TOKEN")
        runtime.addVm()
        val session = DebugSession(testConfig().copy(token = "STALE"), TcpConnector("127.0.0.1", runtime.port, token = "AGENT-TOKEN"))
        session.use {
            it.start().await()
            assertEquals(SessionState.RUNNING, it.state)
        }
    }

    @Test
    fun `连不上时给出地址和原因`() {
        val port = ServerSocket(0).use { it.localPort }
        val session = DebugSession(testConfig(), TcpConnector("127.0.0.1", port, 500))
        session.use {
            val error = it.start().awaitFailure()
            assertEquals(ErrorCodes.DISCONNECTED, error.code)
            assertTrue(error.message, error.message!!.contains("127.0.0.1:$port"))
        }
    }

    @Test
    fun `能力取交集 没有 luaEval 时不下发 allowLuaEval`() {
        connect(caps = FakeRuntime.ALL_CAPS - Capabilities.LUA_EVAL, config = testConfig().copy(options = SetOptionsArgs(allowLuaEval = true)))
        assertFalse(session.hasCapability(Capabilities.LUA_EVAL))
        assertTrue(session.hasCapability(Capabilities.VALUE_PATH))
        assertNull(runtime.options().allowLuaEval)
    }

    @Test
    fun `停止后可以对同一个进程再次附加`() {
        // 旧版：会话停止后残留的附加记录拦住了第二次附加
        connect()
        session.stop().await()
        assertEquals(SessionState.TERMINATED, session.state)
        runtime.awaitCondition(description = "runtime 回到未连接状态") { !runtime.hasClient() }

        newSession()
        session.start().await()
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(2, runtime.connections)
        session.addBreakpoint(BreakpointRequest("scripts/main.lua", 5)).await()
        session.syncBreakpoints().await()
        assertEquals(1L, pauseAt(vm1, line = 5).vmId)
    }
}
