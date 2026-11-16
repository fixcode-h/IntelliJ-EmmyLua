package com.fixcode.intellij.lua.debugger.android.attach

import com.fixcode.intellij.lua.debugger.android.adb.AdbClient
import com.fixcode.intellij.lua.debugger.android.adb.AdbException
import com.fixcode.intellij.lua.debugger.android.adb.AndroidDevices
import com.fixcode.intellij.lua.debugger.android.adb.DeviceProcess
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolResolver
import java.io.File

enum class CheckStatus { OK, WARNING, FAILED }

data class CheckItem(val title: String, val status: CheckStatus, val detail: String, val fix: String? = null)

/**
 * 「检查环境」：逐项检查并说清失败原因（设计文档 10.3）。前一项失败时后面依赖它的项不再检查。
 * [adb] 为 null 表示没找到 adb。
 */
class AttachEnvironmentChecker(
    private val adb: AdbClient?,
    private val agents: AgentBinaryProvider,
    private val symbolResolver: LuaSymbolResolver,
) {
    fun check(serial: String?, packageName: String?, symbolFile: File?, moduleName: String = "libUnreal.so"): List<CheckItem> {
        val items = ArrayList<CheckItem>()
        if (adb == null) {
            items += CheckItem("adb", CheckStatus.FAILED, "没有找到 adb", "安装 Android SDK Platform-Tools，或在设置里指定 adb 路径")
            return items
        }
        val devices = AndroidDevices(adb)
        val list = try {
            devices.list()
        } catch (e: AdbException) {
            items += CheckItem("adb", CheckStatus.FAILED, e.message ?: "adb 无法运行", "确认 adb 能在命令行运行（adb devices）")
            return items
        }
        items += CheckItem("adb", CheckStatus.OK, "adb 可用")
        val device = serial?.let { s -> list.firstOrNull { it.serial == s } } ?: list.singleOrNull()
        if (device == null) {
            items += CheckItem(
                "设备", CheckStatus.FAILED,
                if (list.isEmpty()) "没有连接的设备" else "找不到设备 ${serial ?: ""}",
                "用 USB 连接手机并打开开发者选项里的 USB 调试，或用 adb pair / adb connect 连接无线调试",
            )
            return items
        }
        when {
            device.state == "unauthorized" -> {
                items += CheckItem("设备", CheckStatus.FAILED, "${device.model} 未授权", "在手机上点「允许 USB 调试」")
                return items
            }
            !device.isOnline -> {
                items += CheckItem("设备", CheckStatus.FAILED, "${device.model} 状态为 ${device.state}", "重新插拔 USB 或执行 adb kill-server 后重试")
                return items
            }
            else -> items += CheckItem("设备", CheckStatus.OK, "${device.model} 已授权")
        }
        items += if (device.supportsAttachAgent) {
            CheckItem("系统版本", CheckStatus.OK, "Android ${device.androidVersion} 支持 attach-agent")
        } else {
            CheckItem("系统版本", CheckStatus.WARNING, "Android ${device.androidVersion ?: "?"} 没有 attach-agent，将改用 JDWP 加载 agent",
                "附加时游戏需要在前台；如果 Android Studio 正连着这个应用，请先断开")
        }

        var symbolAbi: String? = null
        var symbolBuildId: String? = null
        if (symbolFile == null) {
            items += CheckItem("符号", CheckStatus.FAILED, "没有找到未裁剪的 $moduleName",
                "在运行配置里指定打包时保留的 so（一般在 Binaries/Android 或 Intermediate/Android 下）")
        } else {
            try {
                val table = symbolResolver.resolve(symbolFile)
                symbolAbi = table.abi
                symbolBuildId = table.buildId
                items += CheckItem("符号", if (table.usable) (if (table.missingOptional.isEmpty()) CheckStatus.OK else CheckStatus.WARNING) else CheckStatus.FAILED,
                    table.describe(), if (table.usable) null else "确认指定的是静态链接了 Lua 的未裁剪 so")
            } catch (e: Exception) {
                items += CheckItem("符号", CheckStatus.FAILED, e.message ?: "读取失败", "确认文件是未裁剪的 ELF so")
            }
        }
        if (symbolAbi != null) {
            items += when {
                device.abiList.isNotEmpty() && symbolAbi !in device.abiList ->
                    CheckItem("ABI", CheckStatus.FAILED, "so 是 $symbolAbi，设备只支持 ${device.abiList.joinToString()}", "换成对应架构的安装包和 so")
                agents.agentFor(symbolAbi) == null ->
                    CheckItem("ABI", CheckStatus.FAILED, "插件里没有 $symbolAbi 的 agent", "重新安装插件，或反馈这个架构的需求")
                else -> CheckItem("ABI", CheckStatus.OK, "$symbolAbi，agent 已就绪")
            }
        }

        if (packageName.isNullOrBlank()) return items
        if (!AndroidDevices.isValidPackage(packageName)) {
            items += CheckItem("应用", CheckStatus.FAILED, "包名格式不对：$packageName")
            return items
        }
        val runAs = adb.shell(device.serial, "run-as $packageName id")
        val runAsOut = (runAs.stdout + runAs.stderr).trim()
        if (!runAs.ok || !runAsOut.contains("uid=")) {
            items += CheckItem("应用", CheckStatus.FAILED,
                when {
                    runAsOut.contains("not debuggable") -> "$packageName 不可调试"
                    runAsOut.contains("unknown package") -> "设备上没有安装 $packageName"
                    else -> "run-as 失败：$runAsOut"
                },
                "安装 Development 包（android:debuggable=true）；正式签名包只能在 root 设备上附加，暂不支持")
            return items
        }
        items += CheckItem("应用", CheckStatus.OK, "$packageName 可调试")

        val pid = devices.findPid(device.serial, packageName)
        if (pid == null) {
            items += CheckItem("进程", CheckStatus.WARNING, "$packageName 没有在运行", "先启动游戏，或勾选「启动应用并附加」")
            return items
        }
        val lib = devices.mappedLibrary(device.serial, DeviceProcess(pid, packageName, true), moduleName)
        if (lib == null) {
            items += CheckItem("进程", CheckStatus.WARNING, "进程 $pid 里还没有加载 $moduleName", "等游戏进入主界面后再附加")
            return items
        }
        items += CheckItem("进程", CheckStatus.OK, "进程 $pid 已加载 $moduleName")
        if (symbolBuildId != null) {
            val remote = devices.remoteBuildId(device.serial, packageName, lib)
            items += when {
                remote == null -> CheckItem("Build ID", CheckStatus.WARNING, "无法读取设备上的 Build ID，附加时由 agent 校验")
                remote.equals(symbolBuildId, ignoreCase = true) -> CheckItem("Build ID", CheckStatus.OK, "与设备一致")
                else -> CheckItem("Build ID", CheckStatus.FAILED,
                    "本地 ${symbolBuildId.take(12)}…，设备 ${remote.take(12)}…，不是同一次构建",
                    "指定这次安装包对应的未裁剪 so，或重新打包安装")
            }
        }
        return items
    }
}
