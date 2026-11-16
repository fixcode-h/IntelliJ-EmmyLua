package com.fixcode.intellij.lua.debug.api

import com.intellij.openapi.extensions.ExtensionPointName

/** 本机进程的基本信息，Attach to Process 列表和"Lua 附加"运行配置共用。 */
data class LuaLocalProcess(
    val pid: Int,
    val name: String,
    val executablePath: String?,
    val commandLine: String?,
    /** "x64" / "x86" / "arm64"；判断不了时为空。 */
    val arch: String?,
    /** 进程里看到的 Lua 运行时模块，例如 lua54.dll；空表示没检测到（或没法检测）。 */
    val luaModules: List<String> = emptyList(),
    val luaVersion: String? = null,
) {
    val hasLuaRuntime: Boolean get() = luaModules.isNotEmpty()
}

/**
 * 进程标签：例如 UE 编辑器(PIE)、UE 游戏、Unity。设计文档 10.2：
 * UE 识别做成一种标签提供者，不写死在通用进程列表里。
 */
interface LuaProcessTagProvider {
    /** 返回要显示的标签；不认识的进程返回空。要快：列表里每个进程都会调。 */
    fun tags(process: LuaLocalProcess): List<String>

    /** 这个进程即使没检测到 Lua 模块也应当归到"Lua"分组（例如 Lua 静态链接进 exe 的 UE 进程）。 */
    fun isLikelyLuaHost(process: LuaLocalProcess): Boolean = false

    /** 列表里的排序优先级，大的排前面（例如带 UELink 名片的 UE 进程）。要快。 */
    fun priority(process: LuaLocalProcess): Int = 0

    companion object {
        val EP: ExtensionPointName<LuaProcessTagProvider> =
            ExtensionPointName.create("com.fixcode.intellij.lua.debug.processTagProvider")
    }
}
