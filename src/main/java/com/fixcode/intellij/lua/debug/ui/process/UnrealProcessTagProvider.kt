package com.fixcode.intellij.lua.debug.ui.process

import com.fixcode.intellij.lua.debug.api.LuaLocalProcess
import com.fixcode.intellij.lua.debug.api.LuaProcessTagProvider
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle

/**
 * 识别 UE 进程。UnLua 把 Lua 静态链接进模块，tasklist 看不到 lua*.dll，
 * 所以 UE 编辑器和游戏进程即使没检测到 Lua 模块也放进"Lua"分组。
 *
 * 只按可执行文件名和 Binaries/<平台>/ 目录判断，不按窗口标题猜（旧实现按标题里的
 * "Server""Engine"等词猜，误判很多）。
 */
class UnrealProcessTagProvider : LuaProcessTagProvider {

    override fun tags(process: LuaLocalProcess): List<String> = when (classify(process)) {
        Kind.EDITOR -> listOf(
            if (isStandaloneGame(process)) LuaDebugBundle.message("tag.ue.editorGame")
            else LuaDebugBundle.message("tag.ue.editor")
        )
        Kind.SERVER -> listOf(LuaDebugBundle.message("tag.ue.server"))
        Kind.GAME -> listOf(LuaDebugBundle.message("tag.ue.game"))
        Kind.TOOL, Kind.NONE -> emptyList()
    }

    override fun isLikelyLuaHost(process: LuaLocalProcess): Boolean =
        classify(process).let { it == Kind.EDITOR || it == Kind.SERVER || it == Kind.GAME }

    private enum class Kind { EDITOR, SERVER, GAME, TOOL, NONE }

    private fun classify(process: LuaLocalProcess): Kind {
        val name = process.name.substringBeforeLast('.')
        if (TOOLS.any { name.startsWith(it, ignoreCase = true) }) return Kind.TOOL
        if (EDITORS.any { name.startsWith(it, ignoreCase = true) }) {
            return if (process.commandLine?.contains(" -server", ignoreCase = true) == true) Kind.SERVER else Kind.EDITOR
        }
        val path = process.executablePath?.replace('\\', '/') ?: return Kind.NONE
        // 打包后的 UE 程序都在 <项目>/Binaries/<平台>/ 下
        if (!UE_BINARIES.containsMatchIn(path)) return Kind.NONE
        return if (name.endsWith("Server", ignoreCase = true)) Kind.SERVER else Kind.GAME
    }

    /** 编辑器带 -game 启动 = 独立进程运行（Standalone Game）。 */
    private fun isStandaloneGame(process: LuaLocalProcess) =
        process.commandLine?.contains(" -game", ignoreCase = true) == true

    private companion object {
        val EDITORS = listOf("UnrealEditor", "UE4Editor", "UE5Editor")
        val TOOLS = listOf(
            "UnrealHeaderTool", "UnrealBuildTool", "UnrealLightmass", "ShaderCompileWorker",
            "CrashReportClient", "UnrealCEFSubProcess", "UnrealTraceServer", "UnrealInsights",
            "EpicWebHelper", "EpicGamesLauncher",
        )
        val UE_BINARIES = Regex("/Binaries/(Win64|Win32|Linux|LinuxArm64|Mac)/[^/]+$", RegexOption.IGNORE_CASE)
    }
}
