package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.project.LuaSettings
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path

/** 找 emmylua_ls 可执行文件：设置里填的 → 插件自带的 → PATH。 */
object EmmyLuaServerLocator {
    const val PLUGIN_ID = "com.fixcode.emmylua.enhanced"
    private val LOG = logger<EmmyLuaServerLocator>()

    val executableName: String get() = if (SystemInfo.isWindows) "emmylua_ls.exe" else "emmylua_ls"

    /** 插件包里的平台目录名，和出包脚本 `.github/scripts/fetch-emmylua-ls.sh` 一致。 */
    val platformDir: String?
        get() {
            val arch = when {
                CpuArch.isArm64() -> "arm64"
                CpuArch.isIntel64() -> "x64"
                else -> return null
            }
            val os = when {
                SystemInfo.isWindows -> "win32"
                SystemInfo.isMac -> "darwin"
                SystemInfo.isLinux -> "linux"
                else -> return null
            }
            return "$os-$arch"
        }

    data class Located(val path: Path, val source: Source)
    enum class Source { SETTINGS, BUNDLED, PATH }

    fun locate(): Located? {
        val configured = LuaSettings.instance.languageServerPath.trim()
        if (configured.isNotEmpty()) {
            val p = runCatching { Path.of(configured) }.getOrNull()
            // 填了但文件不在：不悄悄换成别的，让启动失败并提示改设置
            return if (p != null && Files.isRegularFile(p)) Located(p, Source.SETTINGS) else null
        }
        bundled()?.let { return Located(it, Source.BUNDLED) }
        PathEnvironmentVariableUtil.findInPath(executableName)?.toPath()?.let { return Located(it, Source.PATH) }
        return null
    }

    /** 「路径（来源）」，设置页和上手检查共用；找不到时返回 null。 */
    fun describe(): String? {
        val located = locate() ?: return null
        val source = when (located.source) {
            Source.SETTINGS -> "设置"
            Source.BUNDLED -> "插件自带"
            Source.PATH -> "PATH"
        }
        return "${located.path}（$source）"
    }

    fun bundled(): Path? {
        val dir = platformDir ?: return null
        val pluginPath = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath ?: return null
        val exe = pluginPath.resolve("server").resolve(dir).resolve(executableName)
        if (!Files.isRegularFile(exe)) return null
        // 插件 zip 解压后可能丢了可执行位
        if (!SystemInfo.isWindows && !Files.isExecutable(exe)) {
            runCatching { exe.toFile().setExecutable(true, false) }.onFailure { LOG.warn("chmod +x $exe failed", it) }
        }
        return exe
    }
}
