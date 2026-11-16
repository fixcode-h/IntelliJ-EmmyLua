package com.fixcode.intellij.lua.debugger.android.attach

import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolTable

/**
 * IDE 写给 agent 的配置文件（code_cache/emmylua/agent.cfg）。
 * 一行一个 key=value，agent 用几十行 C 就能解析，不需要 JSON 库。
 * 不走 attach-agent 的参数字符串：40 多个偏移拼进 shell 参数太长，也不好转义。
 */
data class AgentConfig(
    val token: String,
    val buildId: String,
    val moduleName: String,
    val packageName: String,
    val luaVersion: String,
    /** true = 第一次抓到 lua_State 时停住 Lua 线程等 IDE 连上（"启动并在第一行暂停"）。 */
    val waitForIde: Boolean,
    val functions: Map<String, Long>,
) {
    fun render(): String = buildString {
        append("format=").append(FORMAT).append('\n')
        append("token=").append(token).append('\n')
        append("buildId=").append(buildId).append('\n')
        append("module=").append(moduleName).append('\n')
        append("package=").append(packageName).append('\n')
        append("lua=").append(luaVersion).append('\n')
        append("waitIde=").append(if (waitForIde) 1 else 0).append('\n')
        for ((name, offset) in functions.toSortedMap()) {
            append("fn.").append(name).append("=0x").append(java.lang.Long.toHexString(offset)).append('\n')
        }
    }

    companion object {
        const val FORMAT = 1

        fun from(table: LuaSymbolTable, token: String, moduleName: String, packageName: String, waitForIde: Boolean) = AgentConfig(
            token = token,
            buildId = table.buildId,
            moduleName = moduleName,
            packageName = packageName,
            luaVersion = table.luaVersion.code,
            waitForIde = waitForIde,
            functions = table.functions,
        )

        fun parseToken(text: String): String? =
            text.lines().firstOrNull { it.startsWith("token=") }?.removePrefix("token=")?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** agent 写回的状态文件（code_cache/emmylua/agent.status），IDE 注入后轮询它拿结果和报错。 */
data class AgentStatus(val values: Map<String, String>) {
    val state: String get() = values["state"].orEmpty()
    val error: String? get() = values["error"]?.takeIf { it.isNotBlank() }
    val socket: String? get() = values["socket"]
    val pid: Int? get() = values["pid"]?.toIntOrNull()
    val version: String? get() = values["version"]
    val isListening: Boolean get() = state == "listening" || state == "connected"
    val isError: Boolean get() = state == "error"

    companion object {
        fun parse(text: String): AgentStatus = AgentStatus(
            text.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
            }.toMap()
        )
    }
}
