package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.fixcode.intellij.lua.debug.api.LuaDebugProcessFactory
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.engine.transport.Connector
import com.fixcode.intellij.lua.debug.engine.transport.MessageConnection
import com.fixcode.intellij.lua.debug.engine.transport.TargetGoneException
import com.fixcode.intellij.lua.debug.engine.transport.TcpConnector
import com.fixcode.intellij.lua.debugger.resources.DebuggerResourceService
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Windows 本机进程附加：每次建连（包括断线重连）先跑一遍 emmy_tool attach——已注入时它只更新 token、
 * 确认在监听——再按它报告的端口连上，之后走标准 v3 握手。token 每个会话生成一次。
 */
class LocalProcessDebugProcessFactory : LuaDebugProcessFactory {
    override fun supports(target: LuaDebugTarget): Boolean = target is LuaDebugTarget.LocalProcess && SystemInfo.isWindows

    override fun createDebugProcess(session: XDebugSession, target: LuaDebugTarget): XDebugProcess {
        val local = target as? LuaDebugTarget.LocalProcess
            ?: throw IllegalArgumentException("不支持的调试目标：${target.displayName}")
        val connector = InjectingConnector(local.pid, LocalAttachTool.newToken())
        val process = LuaV3DebugProcessFactory.createProcess(session, target, connector) { config ->
            config.copy(options = config.options.copy(captureOutput = local.captureOutput))
        }
        connector.log = process::log
        connector.notice = process::notice
        connector.bundledRuntime = { process.expectedRuntimeVersion = it }
        return process
    }

    /**
     * 每次建连前先确认进程还在：进程退出（游戏、编辑器关了）就抛 [TargetGoneException]，引擎直接结束会话，
     * 不再反复注入。位数第一次查到后记住，之后重连不再查。
     */
    private class InjectingConnector(private val pid: Int, private val token: String) : Connector {
        @Volatile
        var log: (String) -> Unit = {}
        @Volatile
        var notice: (String) -> Unit = {}
        @Volatile
        var bundledRuntime: (String?) -> Unit = {}
        @Volatile
        private var arch: String? = null

        override fun isTargetAlive(): Boolean = ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)

        override fun connect(): MessageConnection {
            if (!isTargetAlive()) throw TargetGoneException("进程 $pid 已退出，调试结束")
            val root = try {
                DebuggerResourceService.emmyNativeToolsDirectory().toFile()
            } catch (e: Exception) {
                throw IOException("无法准备注入工具：${e.message}", e)
            }
            val tool = LocalAttachTool(root, log)
            val result = try {
                val known = arch ?: tool.detectArch(pid).also { arch = it }
                tool.attach(pid, token, known).also { bundledRuntime(it.bundledRuntime) }
            } catch (e: IOException) {
                // 注入途中进程退出时 emmy_tool 报的是各种「打不开进程」，统一成进程已退出
                if (!isTargetAlive()) throw TargetGoneException("进程 $pid 已退出，调试结束")
                throw e
            }
            if (!result.waiting) return TcpConnector(result.host, result.port, token = token).connect()
            notice(result.message ?: "目标进程还没有加载 Lua，调试器已就位，Lua 加载后自动连上")
            return waitForLua(result)
        }

        /**
         * 组件在等 Lua 加载：每 200 毫秒连一次端口，直到 runtime 开始监听；进程退出就结束，期间不重新注入。
         * 连得快很重要：Lua 加载后第一个 VM 会在 Lua 线程上等 IDE 下完断点（最多 5 秒）再跑，等得越短游戏卡得越短。
         */
        private fun waitForLua(result: LocalAttachTool.Result): MessageConnection {
            val connector = TcpConnector(result.host, result.port, connectTimeoutMillis = 1000, token = token)
            var lastLog = System.nanoTime()
            while (true) {
                if (!isTargetAlive()) throw TargetGoneException("进程 $pid 已退出，调试结束")
                try {
                    return connector.connect().also { log("Lua 已加载，连上调试 runtime（${result.host}:${result.port}）") }
                } catch (_: IOException) {
                    // 还没开始监听
                }
                if (System.nanoTime() - lastLog > TimeUnit.SECONDS.toNanos(30)) {
                    lastLog = System.nanoTime()
                    notice("仍在等待目标进程加载 Lua …")
                }
                try {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("已取消等待 Lua 加载", e)
                }
            }
        }
    }
}
