package com.fixcode.intellij.lua.debug.api

/**
 * 调试目标：UI（运行配置、Attach to Process、MCP）只负责描述"连到哪里"，
 * 引擎拿到后决定怎么注入、怎么建转发、怎么握手。
 *
 * 这里刻意只放纯数据，不依赖 IntelliJ，方便引擎和 MCP 模块直接复用。
 */
sealed class LuaDebugTarget {
    /** 给会话标题、通知和 MCP 的 list_sessions 用的一句话描述。 */
    abstract val displayName: String

    /** 本机进程附加（Windows 注入，以后 Linux ptrace）。 */
    data class LocalProcess(
        val pid: Int,
        val processName: String,
        /** 可执行文件路径，用于"最近附加"按路径匹配同一个程序。 */
        val executablePath: String? = null,
        /** 把游戏的 print / 错误输出转到调试控制台。 */
        val captureOutput: Boolean = true,
        /** 「启动并附加」时启动用的 token：之后每次建连沿用它，不换新的。为空时会话自己生成。 */
        val authToken: String? = null,
        /** 进程是这次调试启动的：结束调试时一起结束它。 */
        val terminateOnStop: Boolean = false,
    ) : LuaDebugTarget() {
        override val displayName: String get() = "$processName ($pid)"
    }

    /** Android 真机附加（attach-agent 注入，包体零改动），见设计文档 7.2。 */
    data class AndroidDevice(
        val deviceSerial: String,
        val packageName: String,
        /** 为空时由设备服务按包名找正在运行的进程。 */
        val pid: Int? = null,
        /** true = am start --attach-agent 启动应用并在第一行暂停。 */
        val launchAndAttach: Boolean = false,
        /** 本地未裁剪 libUnreal.so（或其他带 lua_* 符号的 so）；为空表示按设置里的搜索目录自动找。 */
        val symbolFile: String? = null,
        /** 参考 so（特征码兜底）；为空用设置里的。 */
        val referenceSo: String? = null,
        val sourceMapping: SourceMappingConfig = SourceMappingConfig(),
    ) : LuaDebugTarget() {
        override val displayName: String get() = "$packageName @ $deviceSerial"
    }

    /** 手动地址：内嵌 runtime / 局域网直连 / 已经建好的转发。 */
    data class Address(
        val host: String,
        val port: Int,
        /** 局域网直连时 runtime 要求的配对码。 */
        val pairingCode: String? = null,
        val sourceMapping: SourceMappingConfig = SourceMappingConfig(),
    ) : LuaDebugTarget() {
        override val displayName: String get() = "$host:$port"
    }
}

/** 设备上的 chunkName 前缀 → 本地目录，见设计文档 7.6。 */
data class SourceMappingRule(
    val devicePrefix: String,
    /** 本地目录，已展开宏（$PROJECT_DIR$ 等由运行配置展开后再交给引擎）。 */
    val localDirectory: String,
)

data class SourceMappingConfig(
    val rules: List<SourceMappingRule> = emptyList(),
    /** 未映射的 chunk 按"路径段从后往前"匹配项目文件；多个候选时让用户选一次并记入规则。 */
    val autoLearn: Boolean = true,
)
