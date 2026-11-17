package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.fixcode.intellij.lua.debug.api.LuaDebugProcessFactory
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.SourceMappingConfig
import com.fixcode.intellij.lua.debug.engine.DebugSession
import com.fixcode.intellij.lua.debug.engine.MappingRule
import com.fixcode.intellij.lua.debug.engine.SessionConfig
import com.fixcode.intellij.lua.debug.engine.SourceMapper
import com.fixcode.intellij.lua.debug.engine.transport.Connector
import com.fixcode.intellij.lua.debug.engine.transport.TcpConnector
import com.fixcode.intellij.lua.debug.protocol.ExceptionModes
import com.fixcode.intellij.lua.debug.protocol.HelperScript
import com.fixcode.intellij.lua.debug.protocol.SetOptionsArgs
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings

/**
 * 新引擎的 [LuaDebugProcessFactory]，处理「手动地址」。其他目标各有一个 factory，只负责「怎么连上」，
 * 再调 [createProcess] 交给同一个引擎（设计文档 3.1）：Android 真机见 [AndroidDebugProcessFactory]，
 * Windows 本机注入见 [LocalProcessDebugProcessFactory]。
 */
class LuaV3DebugProcessFactory : LuaDebugProcessFactory {
    override fun supports(target: LuaDebugTarget): Boolean = target is LuaDebugTarget.Address

    override fun createDebugProcess(session: XDebugSession, target: LuaDebugTarget): XDebugProcess {
        val address = target as? LuaDebugTarget.Address
            ?: throw IllegalArgumentException("不支持的调试目标：${target.displayName}")
        return createProcess(
            session, target,
            connector = TcpConnector(address.host, address.port),
            mapping = address.sourceMapping,
            token = address.pairingCode
        )
    }

    companion object {
        private val LOG = logger<LuaV3DebugProcessFactory>()

        /**
         * 用给定的建连方式起一个调试进程。在 EDT 上调用，不阻塞：[connector] 会在引擎的 IO 线程上执行，
         * 注入、adb 转发这类慢操作放进 connector 里做；断线重连时它会被再调一次。
         */
        fun createProcess(
            session: XDebugSession,
            target: LuaDebugTarget,
            connector: Connector,
            mapping: SourceMappingConfig = SourceMappingConfig(),
            token: String? = null,
            onClosed: () -> Unit = {},
            configure: (SessionConfig) -> SessionConfig = { it }
        ): LuaV3DebugProcess {
            val mapper = SourceMapper(
                rules = mapping.rules.map { MappingRule(it.devicePrefix, it.localDirectory) },
                index = IdeaSourceFileIndex(session.project),
                autoLearn = mapping.autoLearn,
                caseSensitive = SystemInfo.isFileSystemCaseSensitive
            )
            // 错误聚合（「Lua 错误」页、MCP errors）：老 runtime 没有 errorReport 能力时引擎不下发
            val collect = if (LuaDebugSettings.getInstance().collectCaughtLuaErrors) ExceptionModes.ALL else ExceptionModes.UNCAUGHT
            val base = SessionConfig(name = target.displayName, token = token, helpers = helpers(), helperMain = HELPER_MAIN,
                options = SetOptionsArgs(captureOutput = true, collectErrors = collect))
            val config = configure(LuaVariableViewSync.configure(session.project, base))
            val engine = DebugSession(config, connector, mapper)
            return LuaV3DebugProcess(session, engine, target, onClosed)
        }

        /**
         * 类型显示脚本（helper v2，variable-view.md 11.2）：debugger/emmy/v3 下的 emmy（接口）、emmy_ue（UE 类型）、
         * emmy_main（入口）。runtime 有 helpers 能力时下发内容（不传路径）。旧链路的 emmyHelper.lua 不再给新核心用。
         */
        private fun helpers(): List<HelperScript> = HELPERS

        private val HELPERS: List<HelperScript> by lazy {
            HELPER_NAMES.mapNotNull { name ->
                val stream = LuaV3DebugProcessFactory::class.java.getResourceAsStream("/debugger/emmy/v3/$name.lua")
                if (stream == null) {
                    LOG.warn("插件包里缺少 $name.lua，变量将按默认方式显示")
                    null
                } else {
                    HelperScript(name, stream.use { it.readBytes().toString(Charsets.UTF_8) })
                }
            }
        }

        private val HELPER_NAMES = listOf("emmy", "emmy_ue", "emmy_main")
        const val HELPER_MAIN = "emmy_main"
    }
}
