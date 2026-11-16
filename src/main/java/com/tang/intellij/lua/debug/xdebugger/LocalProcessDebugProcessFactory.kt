package com.tang.intellij.lua.debug.xdebugger

import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.tang.intellij.lua.debug.api.LuaDebugProcessFactory
import com.tang.intellij.lua.debug.api.LuaDebugTarget
import com.tang.intellij.lua.debug.engine.transport.Connector
import com.tang.intellij.lua.debug.engine.transport.MessageConnection
import com.tang.intellij.lua.debug.engine.transport.TargetGoneException
import com.tang.intellij.lua.debug.engine.transport.TcpConnector
import com.tang.intellij.lua.debugger.resources.DebuggerResourceService
import java.io.IOException

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
                tool.attach(pid, token, known)
            } catch (e: IOException) {
                // 注入途中进程退出时 emmy_tool 报的是各种「打不开进程」，统一成进程已退出
                if (!isTargetAlive()) throw TargetGoneException("进程 $pid 已退出，调试结束")
                throw e
            }
            return TcpConnector(result.host, result.port, token = token).connect()
        }
    }
}
