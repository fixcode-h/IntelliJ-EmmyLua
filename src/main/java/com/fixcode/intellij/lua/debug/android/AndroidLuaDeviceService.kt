package com.fixcode.intellij.lua.debug.android

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.fixcode.intellij.lua.debug.api.LuaCheckStatus
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaDevice
import com.fixcode.intellij.lua.debug.api.LuaDeviceException
import com.fixcode.intellij.lua.debug.api.LuaDeviceProcess
import com.fixcode.intellij.lua.debug.api.LuaDeviceService
import com.fixcode.intellij.lua.debug.api.LuaDeviceState
import com.fixcode.intellij.lua.debug.api.LuaDeviceTransport
import com.fixcode.intellij.lua.debug.api.LuaEnvCheckItem
import com.fixcode.intellij.lua.debug.api.LuaSymbolReport
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.fixcode.intellij.lua.debugger.android.adb.AdbClient
import com.fixcode.intellij.lua.debugger.android.adb.AdbException
import com.fixcode.intellij.lua.debugger.android.adb.AdbLocator
import com.fixcode.intellij.lua.debugger.android.adb.AndroidDevices
import com.fixcode.intellij.lua.debugger.android.adb.DeviceProcess
import com.fixcode.intellij.lua.debugger.android.adb.ProcessAdbExecutor
import com.fixcode.intellij.lua.debugger.android.attach.AgentBinaryProvider
import com.fixcode.intellij.lua.debugger.android.attach.AttachEnvironmentChecker
import com.fixcode.intellij.lua.debugger.android.attach.CheckStatus
import com.fixcode.intellij.lua.debugger.android.elf.DetectedLuaVersion
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolResolver
import com.fixcode.intellij.lua.debugger.resources.DebuggerResourceService
import java.io.File

/**
 * 把纯 Kotlin 的 debugger-android 模块接到 UI 的 [LuaDeviceService] 上。
 * 这里只做设置读取和类型转换，逻辑都在模块里（有单测）。
 */
class AndroidLuaDeviceService : LuaDeviceService {

    private val symbolResolver by lazy {
        LuaSymbolResolver(File(PathManager.getSystemPath(), "emmylua/android-symbols"))
    }

    override fun listDevices(): List<LuaDevice> = wrap {
        AndroidDevices(adb()).list().map { d ->
            LuaDevice(
                serial = d.serial,
                model = d.model,
                transport = when {
                    d.isEmulator -> LuaDeviceTransport.EMULATOR
                    d.isWifi -> LuaDeviceTransport.WIFI
                    else -> LuaDeviceTransport.USB
                },
                state = when (d.state) {
                    "device" -> LuaDeviceState.ONLINE
                    "unauthorized" -> LuaDeviceState.UNAUTHORIZED
                    else -> LuaDeviceState.OFFLINE
                },
                androidVersion = d.androidVersion,
                apiLevel = d.apiLevel,
                abi = d.abi,
            )
        }
    }

    override fun listProcesses(serial: String, onlyDebuggable: Boolean): List<LuaDeviceProcess> = wrap {
        AndroidDevices(adb()).processes(serial, onlyDebuggable).map { p ->
            LuaDeviceProcess(
                pid = p.pid,
                packageName = p.processName,
                debuggable = p.debuggable,
                tags = if (p.isUnreal) listOf("UE 游戏") else emptyList(),
            )
        }
    }

    override fun listDebuggablePackages(serial: String): List<String> = wrap {
        AndroidDevices(adb()).debuggablePackages(serial)
    }

    override fun resolveSymbolFile(project: Project, configured: String?, serial: String?, packageName: String?): String? {
        configured?.takeIf { it.isNotBlank() }?.let { return it }
        val roots = LuaDebugSettings.getInstance().expandedSymbolSearchDirs(project).map(::File)
        // 有设备和包名时按设备上 so 的 Build ID 挑：项目里常有多次打包留下的多个 libUnreal.so
        val deviceBuildId = if (serial != null && packageName != null) deviceBuildId(serial, packageName) else null
        val candidates = symbolResolver.findCandidates(roots, expectedBuildId = deviceBuildId)
        return candidates.firstOrNull()?.absolutePath
    }

    override fun inspectSymbols(symbolFile: String, serial: String?, packageName: String?): LuaSymbolReport {
        val file = File(symbolFile)
        val deviceId = if (serial != null && packageName != null) deviceBuildId(serial, packageName) else null
        return try {
            val table = symbolResolver.resolve(file)
            val warnings = ArrayList<String>()
            if (table.conflicts.isNotEmpty()) warnings += "同名函数有多份实现：${table.conflicts.keys.joinToString()}，so 里可能静态链接了两份 Lua"
            if (!table.hasCaptureHook) warnings += "找不到 lua_pcall / lua_pcallk / lua_resume，无法抓到 lua_State"
            if (table.missingOptional.isNotEmpty()) warnings += "以下函数被链接器裁掉，相关功能降级：${table.missingOptional.joinToString()}"
            LuaSymbolReport(
                symbolFile = file.absolutePath,
                buildId = table.buildId,
                deviceBuildId = deviceId,
                luaFunctionCount = table.functions.size,
                luaVersion = when (table.luaVersion) {
                    DetectedLuaVersion.LUAJIT -> "LuaJIT"
                    DetectedLuaVersion.UNKNOWN -> null
                    else -> table.luaVersion.code.let { "${it[0]}.${it.substring(1)}" }
                },
                error = if (table.missingCritical.isNotEmpty()) "缺少调试必需的函数：${table.missingCritical.joinToString()}" else null,
                missingFunctions = table.missingRequired,
                warnings = warnings,
            )
        } catch (e: Exception) {
            LuaSymbolReport(
                symbolFile = file.absolutePath,
                buildId = try { symbolResolver.readBuildId(file) } catch (_: Exception) { null },
                deviceBuildId = deviceId,
                error = e.message ?: "读取失败",
            )
        }
    }

    override fun checkEnvironment(project: Project, target: LuaDebugTarget.AndroidDevice): List<LuaEnvCheckItem> {
        val adbFile = AdbLocator.locate(LuaDebugSettings.getInstance().adbPath)
        val adb = adbFile?.let { AdbClient(ProcessAdbExecutor(it)) }
        val symbol = try {
            resolveSymbolFile(project, target.symbolFile, target.deviceSerial, target.packageName)
        } catch (_: Exception) {
            null
        }
        return AttachEnvironmentChecker(adb, agentBinaries(), symbolResolver)
            .check(target.deviceSerial, target.packageName, symbol?.let(::File))
            .map { item ->
                LuaEnvCheckItem(
                    title = item.title,
                    status = when (item.status) {
                        CheckStatus.OK -> LuaCheckStatus.OK
                        CheckStatus.WARNING -> LuaCheckStatus.WARNING
                        CheckStatus.FAILED -> LuaCheckStatus.FAILED
                    },
                    detail = item.detail,
                    fix = item.fix,
                )
            }
    }

    // ---- 内部 ----

    private fun adb(): AdbClient {
        val file = AdbLocator.locate(LuaDebugSettings.getInstance().adbPath)
            ?: throw LuaDeviceException("没有找到 adb：安装 Android SDK Platform-Tools，或在设置里指定 adb 路径")
        return AdbClient(ProcessAdbExecutor(file))
    }

    private fun deviceBuildId(serial: String, packageName: String): String? = try {
        val devices = AndroidDevices(adb())
        val pid = devices.findPid(serial, packageName)
        val lib = pid?.let { devices.mappedLibrary(serial, DeviceProcess(it, packageName, true), MODULE_NAME) }
        lib?.let { devices.remoteBuildId(serial, packageName, it) }
    } catch (_: Exception) {
        null
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: AdbException) {
        throw LuaDeviceException(e.message ?: "adb 调用失败", e)
    }

    companion object {
        const val MODULE_NAME = "libUnreal.so"

        /**
         * 插件包里的 agent：资源 debugger/emmy/android/<abi>/libemmylua_agent.so（由 native/platforms/android/build_agent.sh 产出），
         * 用到时解压到 system 目录（adb push 需要真实文件）。插件包里还没有时返回 null，环境检查会提示。
         */
        fun agentBinaries(): AgentBinaryProvider = AgentBinaryProvider { abi ->
            val resource = "debugger/emmy/android/$abi/$AGENT_FILE"
            val bytes = AndroidLuaDeviceService::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: return@AgentBinaryProvider null
            val dir = DebuggerResourceService.extractResources(
                mapOf(AGENT_FILE to bytes),
                java.nio.file.Path.of(PathManager.getSystemPath(), "emmylua", "android-agent", abi),
            )
            dir.resolve(AGENT_FILE).toFile()
        }

        private const val AGENT_FILE = "libemmylua_agent.so"
    }
}
