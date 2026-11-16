package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.fixcode.intellij.lua.debug.android.AndroidAttachLauncher
import com.fixcode.intellij.lua.debug.api.LuaDebugProcessFactory
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.engine.transport.Connector
import com.fixcode.intellij.lua.debug.engine.transport.MessageConnection
import com.fixcode.intellij.lua.debug.engine.transport.TcpConnector
import java.io.IOException

/**
 * Android 真机附加：注入 agent、建 adb 转发都交给 [AndroidAttachLauncher]，连上之后走普通 TCP + 协议 v3。
 * 注入在引擎的 IO 线程里做（Connector.connect），不卡 EDT；token 注入后才生成，随连接一起交给引擎。
 */
class AndroidDebugProcessFactory : LuaDebugProcessFactory {
    override fun supports(target: LuaDebugTarget): Boolean = target is LuaDebugTarget.AndroidDevice

    override fun createDebugProcess(session: XDebugSession, target: LuaDebugTarget): XDebugProcess {
        val device = target as? LuaDebugTarget.AndroidDevice
            ?: throw IllegalArgumentException("不支持的调试目标：${target.displayName}")
        val connector = DeviceConnector(session, device)
        val process = LuaV3DebugProcessFactory.createProcess(
            session, target, connector,
            mapping = device.sourceMapping,
            onClosed = connector::detach
        )
        connector.log = process::log
        return process
    }

    private class DeviceConnector(
        private val session: XDebugSession,
        private val target: LuaDebugTarget.AndroidDevice
    ) : Connector {
        @Volatile
        var log: (String) -> Unit = {}
        @Volatile
        private var handle: AndroidAttachLauncher.Handle? = null

        override fun connect(): MessageConnection {
            handle?.let { existing ->
                try {
                    return tcp(existing)
                } catch (_: IOException) {
                    // 断线重连：USB 重插、adb 重启后转发没了。再走一遍附加，进程里的 agent 会被复用，不会重复注入
                    existing.detach()
                }
            }
            val attached = try {
                AndroidAttachLauncher.attach(session.project, target, log)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message ?: "真机附加失败", e)
            }
            handle = attached
            return tcp(attached)
        }

        private fun tcp(handle: AndroidAttachLauncher.Handle): MessageConnection {
            val result = handle.result
            return TcpConnector(result.host, result.port, token = result.token).connect()
        }

        fun detach() {
            handle?.detach()
            handle = null
        }
    }
}
