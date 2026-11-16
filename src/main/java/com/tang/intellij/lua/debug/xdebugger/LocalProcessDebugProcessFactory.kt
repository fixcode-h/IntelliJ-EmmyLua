package com.tang.intellij.lua.debug.xdebugger

import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.tang.intellij.lua.debug.api.LuaDebugProcessFactory
import com.tang.intellij.lua.debug.api.LuaDebugTarget
import com.tang.intellij.lua.debug.engine.transport.Connector
import com.tang.intellij.lua.debug.engine.transport.MessageConnection
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

    private class InjectingConnector(private val pid: Int, private val token: String) : Connector {
        @Volatile
        var log: (String) -> Unit = {}

        override fun connect(): MessageConnection {
            val root = try {
                // 工具随插件打包，首次使用时解压；P8 切换时随旧 debugger 包一起迁移
                DebuggerResourceService.emmyNativeToolsDirectory().toFile()
            } catch (e: Exception) {
                throw IOException("无法准备注入工具：${e.message}", e)
            }
            val result = LocalAttachTool(root, log).attach(pid, token)
            return TcpConnector(result.host, result.port, token = token).connect()
        }
    }
}
