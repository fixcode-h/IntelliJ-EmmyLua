package com.fixcode.intellij.lua.debug.engine.e2e

import com.fixcode.intellij.lua.debug.engine.BreakpointOrigin
import com.fixcode.intellij.lua.debug.engine.BreakpointRequest
import com.fixcode.intellij.lua.debug.engine.BreakpointVerification
import com.fixcode.intellij.lua.debug.engine.DebugEvent
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.MappingRule
import com.fixcode.intellij.lua.debug.engine.PauseInfo
import com.fixcode.intellij.lua.debug.engine.ReconnectPolicy
import com.fixcode.intellij.lua.debug.engine.SessionConfig
import com.fixcode.intellij.lua.debug.engine.SessionState
import com.fixcode.intellij.lua.debug.engine.SourceMapper
import com.fixcode.intellij.lua.debug.engine.fake.EventRecorder
import com.fixcode.intellij.lua.debug.engine.transport.TcpConnector
import com.fixcode.intellij.lua.debug.protocol.Capabilities
import com.fixcode.intellij.lua.debug.protocol.EvalModes
import com.fixcode.intellij.lua.debug.protocol.HelperScript
import com.fixcode.intellij.lua.debug.protocol.PauseReasons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 对着真的原生核心（native/emmy_debugger_core）跑引擎：协议两端各自的单测都过了，不代表互相能对上。
 *
 * 需要先编出宿主程序，再用环境变量指给 Gradle：
 * ```
 * cmake -S native/emmy_debugger_core -B build/core && cmake --build build/core --target emmy_core_ide_host
 * EMMY_CORE_HOST=$PWD/build/core/emmy_core_ide_host ./gradlew :modules:debugger-engine:test
 * ```
 * 没设置时整组跳过。
 */
class NativeRuntimeE2eTest {
    private lateinit var host: Process
    private lateinit var root: File
    private lateinit var script: File
    private var port = 0
    private val sessions = ArrayList<DebugSession>()

    @Before
    fun startHost() {
        val binary = System.getProperty("emmy.core.host")?.let(::File)
        assumeTrue("没有设置 EMMY_CORE_HOST，跳过真 runtime 用例", binary != null && binary.canExecute())
        root = Files.createTempDirectory("emmy-e2e").toFile()
        script = File(root, "game/main.lua").apply {
            parentFile.mkdirs()
            writeText(SCRIPT)
        }
        port = ServerSocket(0).use { it.localPort }
        host = ProcessBuilder(binary!!.path, port.toString(), script.path, "@game/main.lua")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val ready = CompletableFuture.supplyAsync { host.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)
        assertEquals("READY", ready)
    }

    @After
    fun stopHost() {
        sessions.forEach { it.close() }
        if (this::host.isInitialized) {
            host.outputStream.close()
            if (!host.waitFor(5, TimeUnit.SECONDS)) host.destroyForcibly()
        }
        if (this::root.isInitialized) root.deleteRecursively()
    }

    private fun session(configure: (SessionConfig) -> SessionConfig = { it }): Pair<DebugSession, EventRecorder> {
        val config = configure(SessionConfig(
            name = "e2e", requestTimeoutMillis = 5_000, handshakeTimeoutMillis = 5_000,
            heartbeatIntervalMillis = 0, reconnect = ReconnectPolicy.NONE
        ))
        val mapper = SourceMapper(listOf(MappingRule("game", File(root, "game").path)))
        val session = DebugSession(config, TcpConnector("127.0.0.1", port), mapper)
        sessions += session
        val events = EventRecorder(session)
        session.start().get(10, TimeUnit.SECONDS)
        return session to events
    }

    private fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)

    private fun EventRecorder.pause(): PauseInfo = await<DebugEvent.Paused>(10_000).pause

    /**
     * 交互延迟（设计文档 5.3「交互延迟（IDE 侧）」本机一栏）。CI 是共享机器，断言放宽到目标的 3 倍，实测值打印出来看趋势。
     * 「修改断点 → 停下」包含等游戏执行到那一行（宿主每 2 毫秒 tick 一次）。
     */
    @Test
    fun `交互延迟在目标以内`() {
        val (session, events) = session()
        fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0

        var t = System.nanoTime()
        session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        session.syncBreakpoints().await()
        val pause = events.pause()
        val hitMs = ms(t)

        t = System.nanoTime()
        val frames = session.stackTrace(pause).await().frames
        val scopes = session.scopes(frames.first()).await()
        session.variables(scopes.single { it.name == "locals" }).await()
        val localsMs = ms(t)

        val upvalues = session.variables(scopes.single { it.name == "upvalues" }).await().variables.associateBy { it.variable.name }
        val m = session.variables(upvalues.getValue("M").children()!!).await().variables.associateBy { it.variable.name }
        val big = m.getValue("big").children()!!
        t = System.nanoTime()
        val page = session.variables(big, 0, 100).await()
        val bigMs = ms(t)
        assertEquals(10000, page.total)
        assertEquals(100, page.variables.size)

        session.removeBreakpoint(session.breakpoints().single().id).await()
        t = System.nanoTime()
        session.stepOver(pause.vmId).await()
        events.pause()
        val stepMs = ms(t)

        println("交互延迟（毫秒）：断点生效到停下 %.1f，顶层帧局部变量 %.1f，1 万项表首页 %.1f，单步 %.1f".format(hitMs, localsMs, bigMs, stepMs))
        // 目标：修改断点生效 30 + 高亮 50；局部变量 100；1 万项首页 100；单步 50
        assertTrue("断点生效到停下 $hitMs ms", hitMs <= 80 * 3)
        assertTrue("顶层帧局部变量 $localsMs ms", localsMs <= 100 * 3)
        assertTrue("1 万项表首页 $bigMs ms", bigMs <= 100 * 3)
        assertTrue("单步 $stepMs ms", stepMs <= 50 * 3)
        session.resume().await()
    }

    @Test
    fun `握手后看到宿主的 VM`() {
        val (session, _) = session()
        assertEquals(SessionState.RUNNING, session.state)
        assertEquals(listOf("GameVM"), session.vms().map { it.name })
    }

    @Test
    fun `断点验证 命中 看栈和变量 单步 继续`() {
        val (session, events) = session()
        val id = session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        session.syncBreakpoints().await()

        val pause = events.pause()
        assertEquals(PauseReasons.BREAKPOINT, pause.reason)
        assertEquals(LINE_LOCAL_R, pause.top!!.line)
        assertEquals(BreakpointVerification.VERIFIED, session.breakpoints().single { it.id == id }.verification)

        val frames = session.stackTrace(pause).await().frames
        assertEquals("game/main.lua", frames.first().localPath!!.substringAfter(root.path.replace('\\', '/') + "/"))
        val locals = session.scopes(frames.first()).await().single { it.name == "locals" }
        val vars = session.variables(locals).await().variables.associateBy { it.variable.name }
        assertTrue(vars.keys.toString(), "i" in vars && "t" in vars)
        val t = session.variables(vars.getValue("t").children()!!).await().variables.associateBy { it.variable.name }
        assertEquals("\"x\"", t.getValue("name").variable.value)

        if (session.hasCapability(Capabilities.VALUE_PATH)) {
            assertEquals("3", session.evaluate(pause, "t.list[3]", mode = EvalModes.VALUE_PATH).await().value)
        }

        session.removeBreakpoint(id).await()
        session.stepInto(pause.vmId).await()
        val stepped = events.pause()
        assertEquals(PauseReasons.STEP, stepped.reason)
        assertEquals(LINE_ADD_BODY, stepped.top!!.line)

        session.resume(stepped.vmId).await()
        events.await<DebugEvent.Resumed>()
    }

    @Test
    fun `richVariables 下发变量视图设置 返回分组和 kind`() {
        // 和设置页 VariableViewSettings.toProtocolOptions() 的字段一致
        val view = com.fixcode.intellij.lua.debug.protocol.jsonObject {
            put("sort", "smart"); put("groupMethods", true); put("showInherited", true); put("showMetatable", true)
            put("toString", "tables"); put("hideStdGlobals", true); put("showTemporaries", false)
        }
        val (session, events) = session { it.copy(options = it.options.copy(variableView = view), maxValueLength = 16) }
        assumeTrue("runtime 没有声明 richVariables", session.hasCapability(Capabilities.RICH_VARIABLES))
        session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        val pause = events.pause()
        val frame = session.stackTrace(pause).await().frames.first()
        val locals = session.scopes(frame).await().single { it.name == "locals" }
        val t = session.variables(locals).await().variables.single { it.variable.name == "t" }
        assertTrue("t 应该有 kind：${t.variable}", t.variable.kind != null)
        val page = session.variables(t.children()!!).await()
        assertTrue("应该有继承 / 元表分组：${page.groups.map { it.variable.name }}", page.groups.isNotEmpty())
        assertTrue("name" in page.variables.map { it.variable.name })
        val failed = session.output().map { it.value.text }.filter { "选项下发失败" in it }
        assertTrue(failed.toString(), failed.isEmpty())
    }

    @Test
    fun `修改变量后重新读取到新值`() {
        val (session, events) = session()
        assumeTrue(session.hasCapability(Capabilities.SET_VARIABLE))
        session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        val pause = events.pause()
        val frame = session.stackTrace(pause).await().frames.first()
        val locals = session.scopes(frame).await().single { it.name == "locals" }
        val t = session.variables(locals).await().variables.single { it.variable.name == "t" }.children()!!
        session.setVariable(t, "name", "\"changed\"").await()
        val again = session.variables(t).await().variables.single { it.variable.name == "name" }
        assertTrue(again.variable.value, again.variable.value.contains("changed"))
    }

    @Test
    fun `协程列表和协程的栈`() {
        val (session, events) = session()
        assumeTrue(session.hasCapability(Capabilities.COROUTINES))
        session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        val pause = events.pause()
        val threads = session.threads(pause).await()
        val loader = threads.firstOrNull { it.threadId != pause.threadId && it.status == "suspended" }
        assertTrue("应该列出挂起的 loader 协程：$threads", loader != null)
        val frames = session.stackTrace(pause, threadId = loader!!.threadId).await().frames
        assertTrue("取到的应该是 loader 协程的栈：${frames.map { it.frame }}", frames.all { it.frame.threadId == loader.threadId })
        val luaFrame = frames.firstOrNull { it.frame.hasSource } ?: error("协程栈里没有 Lua 帧：${frames.map { it.frame }}")
        val scopes = session.scopes(luaFrame).await()
        val locals = scopes.firstOrNull { it.name == "locals" } ?: error("没有 locals：${scopes.map { it.name }}")
        val vars = session.variables(locals).await().variables
        val waiting = vars.firstOrNull { it.variable.name == "waiting" } ?: error("没有 waiting：${vars.map { it.variable }}")
        assertEquals("\"ui/bag\"", waiting.variable.value)
    }

    @Test
    fun `条件断点只在条件成立时停`() {
        val (session, events) = session()
        assumeTrue(session.hasCapability(Capabilities.CONDITION))
        session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R, condition = "i % 7 == 0")).await()
        val pause = events.pause()
        val frame = session.stackTrace(pause).await().frames.first()
        val locals = session.scopes(frame).await().single { it.name == "locals" }
        val i = session.variables(locals).await().variables.single { it.variable.name == "i" }.variable.value.toLong()
        assertEquals(0, i % 7)
    }

    @Test
    fun `Probe 断点不停 记录采集值`() {
        val (session, events) = session()
        assumeTrue(session.hasCapability(Capabilities.CAPTURE))
        session.addBreakpoint(
            BreakpointRequest(script.path, LINE_LOCAL_R, capture = listOf("i"), autoContinue = true, origin = BreakpointOrigin.AGENT)
        ).await()
        val hit = events.await<DebugEvent.BreakpointHit>(10_000).hit
        assertEquals("i", hit.captures.single().expression)
        events.assertNone<DebugEvent.Paused>(300)
    }

    @Test
    fun `断开后游戏继续运行 可以重新连上`() {
        val (first, events) = session()
        first.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        events.pause()
        first.stop().await()

        // 断开时 runtime 恢复所有 VM 并清空断点：新会话不会立刻停在旧断点上
        val (second, secondEvents) = session()
        assertEquals(SessionState.RUNNING, second.state)
        secondEvents.assertNone<DebugEvent.Paused>(300)
    }

    /**
     * 用户场景：游戏一直开着，IDE 停止调试、再附加，反复几次都要能正常调试。
     * 轮流覆盖「停在断点上时停止」「运行中停止」「单步途中停止」「关掉会话不发 disconnect（IDE 崩溃 / 强杀）」。
     */
    @Test
    fun `游戏不重启 停止调试再附加 反复多次都能正常断点和看变量`() {
        // 和 IDE 一样每次附加都下发类型显示脚本，反复加载不能出错
        val helperDir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/resources/debugger/emmy/v3") }.firstOrNull { it.isDirectory }
        val helpers = helperDir?.let { dir -> listOf("emmy", "emmy_ue", "emmy_main").map { HelperScript(it, File(dir, "$it.lua").readText()) } }.orEmpty()
        repeat(4) { round ->
            val (session, events) = session { it.copy(helpers = helpers, helperMain = "emmy_main") }
            assertEquals("第 ${round + 1} 次附加", SessionState.RUNNING, session.state)
            // 上一轮留下的断点、单步状态不能带过来：没下断点前不应该停
            events.assertNone<DebugEvent.Paused>(300)

            val id = session.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
            val pause = events.pause()
            assertEquals("第 ${round + 1} 次附加", PauseReasons.BREAKPOINT, pause.reason)
            assertEquals(BreakpointVerification.VERIFIED, session.breakpoints().single { it.id == id }.verification)
            val frame = session.stackTrace(pause).await().frames.first()
            val locals = session.scopes(frame).await().single { it.name == "locals" }
            assertTrue(session.variables(locals).await().variables.any { it.variable.name == "i" })

            when (round) {
                0 -> session.stop().await()
                1 -> {
                    session.removeBreakpoint(id).await()
                    session.resume(pause.vmId).await()
                    session.stop().await()
                }
                2 -> {
                    session.stepOver(pause.vmId).await()
                    session.stop().await()
                }
                // 不打招呼直接断开 TCP（IDE 被强杀、网络断了）：runtime 也要恢复游戏、清掉断点
                else -> session.close()
            }
        }
        val (last, lastEvents) = session { it.copy(helpers = helpers, helperMain = "emmy_main") }
        lastEvents.assertNone<DebugEvent.Paused>(300)
        last.addBreakpoint(BreakpointRequest(script.path, LINE_ADD_BODY)).await()
        assertEquals(LINE_ADD_BODY, lastEvents.pause().top!!.line)
    }

    /** 旧会话还连着时再附加：先收到 BUSY 自动重试，旧会话一停就连上，断点照常生效。 */
    @Test
    fun `上一个会话还没停时再附加 等它停了自动连上`() {
        val (first, _) = session()
        val config = SessionConfig(
            name = "e2e-2", requestTimeoutMillis = 5_000, handshakeTimeoutMillis = 5_000,
            heartbeatIntervalMillis = 0, reconnect = ReconnectPolicy.NONE, busyRetryDelayMillis = 200
        )
        val second = DebugSession(config, TcpConnector("127.0.0.1", port), SourceMapper(listOf(MappingRule("game", File(root, "game").path))))
        sessions += second
        val events = EventRecorder(second)
        val started = second.start()
        Thread.sleep(600)
        assertTrue("旧会话还连着，新会话不应该已经连上", !started.isDone)
        first.stop().await()
        started.get(10, TimeUnit.SECONDS)
        second.addBreakpoint(BreakpointRequest(script.path, LINE_LOCAL_R)).await()
        assertEquals(LINE_LOCAL_R, events.pause().top!!.line)
    }

    @Test
    fun `采样分析取到函数表和折叠栈 停止后结果不再增长`() {
        val (session, _) = session()
        assumeTrue("runtime 没有声明 profiler", session.supportsProfiler)
        session.profileStart(intervalInstructions = 100).await()
        Thread.sleep(400)
        val result = session.profileStop(sort = "total").await()
        assertTrue("应该有样本：$result", result.samples > 0)
        val tick = result.functions.firstOrNull { it.source == "@game/main.lua" && it.line == 7 }
            ?: error("函数表里没有 tick：${result.functions}")
        assertTrue("tick 的总占比应该最高：$tick", tick.totalPercent > 50.0)
        assertEquals("game/main.lua", session.localPathOfChunk(tick.source)!!.replace('\\', '/').substringAfter(root.path.replace('\\', '/') + "/"))
        assertTrue(result.folded().lines().any { it.contains("add (game/main.lua:3)") })
        val again = session.profileResult().await()
        Thread.sleep(100)
        assertEquals(again.samples, session.profileResult().await().samples)
    }

    private companion object {
        const val LINE_ADD_BODY = 4
        const val LINE_LOCAL_R = 9

        val SCRIPT = """
            local M = {}
            local counter = 0
            function M.add(a, b)
              local sum = a + b
              return sum
            end
            function M.tick(i)
              local t = setmetatable({ name = "x", list = {1, 2, 3} }, { __index = { base = 2, Show = function() end } })
              local r = M.add(i, 1)
              counter = counter + r
              return r
            end
            M.loader = coroutine.create(function(path)
              local waiting = path
              coroutine.yield()
            end)
            coroutine.resume(M.loader, "ui/bag")
            M.big = {}
            for k = 1, 10000 do M.big[k] = k end
            return M
        """.trimIndent() + "\n"
    }
}
