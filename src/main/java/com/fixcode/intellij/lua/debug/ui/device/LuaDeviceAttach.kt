package com.fixcode.intellij.lua.debug.ui.device

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import com.intellij.xdebugger.attach.XAttachHostPresentationGroup
import com.intellij.xdebugger.attach.XAttachHostProvider
import com.intellij.xdebugger.attach.XAttachPresentationGroup
import com.intellij.xdebugger.attach.XAttachProcessPresentationGroup
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaDevice
import com.fixcode.intellij.lua.debug.api.LuaDeviceException
import com.fixcode.intellij.lua.debug.api.LuaDeviceService
import com.fixcode.intellij.lua.debug.api.LuaDeviceState
import com.fixcode.intellij.lua.debug.api.SourceMappingConfig
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugSessionStarter
import javax.swing.Icon

/**
 * `Run > Attach to Process…` 的主机下拉里出现已连接的 Android 设备（设计文档 10.3），
 * 选中设备后列出设备上可调试的进程，选一个就附加。
 *
 * 从这里附加用自动找的符号文件和"自动学习"的源码映射；要固定映射规则就建「Lua 真机附加」运行配置。
 */
class LuaDeviceAttachHost(val device: LuaDevice) : XAttachHost {
    @Throws(ExecutionException::class)
    override suspend fun getProcessListAsync(): List<ProcessInfo> = listProcesses()

    /** 平台仍把它声明为抽象方法，只能实现；新版平台走 [getProcessListAsync]。 */
    @Deprecated("Use getProcessListAsync")
    @Throws(ExecutionException::class)
    override fun getProcessList(): List<ProcessInfo> = listProcesses()

    private fun listProcesses(): List<ProcessInfo> {
        val service = LuaDeviceService.getInstance() ?: return emptyList()
        val processes = try {
            service.listProcesses(device.serial, onlyDebuggable = true)
        } catch (e: LuaDeviceException) {
            throw ExecutionException(e.message, e)
        }
        return processes.map { p ->
            val tags = p.tags.joinToString(" ")
            ProcessInfo(p.pid, "${p.packageName} $tags".trim(), p.packageName, tags)
        }
    }
}

class LuaDeviceAttachHostProvider : XAttachHostProvider<LuaDeviceAttachHost> {
    override fun getPresentationGroup(): XAttachPresentationGroup<out XAttachHost> = DeviceHostGroup

    override suspend fun getAvailableHostsAsync(project: Project?): List<LuaDeviceAttachHost> = onlineHosts()

    /** 平台仍把它声明为抽象方法，只能实现；新版平台走 [getAvailableHostsAsync]。 */
    @Deprecated("Use getAvailableHostsAsync")
    override fun getAvailableHosts(project: Project?): List<LuaDeviceAttachHost> = onlineHosts()

    private fun onlineHosts(): List<LuaDeviceAttachHost> {
        val service = LuaDeviceService.getInstance() ?: return emptyList()
        return try {
            service.listDevices().filter { it.state == LuaDeviceState.ONLINE }.map { LuaDeviceAttachHost(it) }
        } catch (_: LuaDeviceException) {
            // 没有 adb 时主机列表里不出现设备即可，不在 Attach 对话框里报错
            emptyList()
        }
    }
}

private object DeviceHostGroup : XAttachHostPresentationGroup<LuaDeviceAttachHost> {
    override fun getOrder(): Int = 100
    override fun getGroupName(): String = LuaDebugBundle.message("device.attach.hostGroup")
    override fun getItemIcon(project: Project, info: LuaDeviceAttachHost, dataHolder: UserDataHolder): Icon = LuaDebugIcons.DEVICE
    override fun getItemDisplayText(project: Project, info: LuaDeviceAttachHost, dataHolder: UserDataHolder): String {
        val d = info.device
        return listOfNotNull(d.model, d.androidVersion?.let { "Android $it" }, d.serial).joinToString(" · ")
    }
    override fun compare(a: LuaDeviceAttachHost, b: LuaDeviceAttachHost): Int = a.device.serial.compareTo(b.device.serial)
}

class LuaDeviceAttachDebuggerProvider : XAttachDebuggerProvider {
    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LuaDeviceAttachHost

    override fun getPresentationGroup(): XAttachPresentationGroup<ProcessInfo> = DeviceProcessGroup

    override fun getAvailableDebuggers(
        project: Project,
        attachHost: XAttachHost,
        processInfo: ProcessInfo,
        contextHolder: UserDataHolder,
    ): List<XAttachDebugger> = if (attachHost is LuaDeviceAttachHost) listOf(DeviceAttachDebugger) else emptyList()
}

private object DeviceAttachDebugger : XAttachDebugger {
    override fun getDebuggerDisplayName(): String = LuaDebugBundle.message("device.attach.debugger")

    override fun attachDebugSession(project: Project, attachHost: XAttachHost, processInfo: ProcessInfo) {
        val host = attachHost as LuaDeviceAttachHost
        LuaDebugSessionStarter.startWithoutConfiguration(
            project,
            LuaDebugTarget.AndroidDevice(
                deviceSerial = host.device.serial,
                packageName = processInfo.executableName,
                pid = processInfo.pid,
                sourceMapping = SourceMappingConfig(autoLearn = true),
            ),
        )
    }
}

private object DeviceProcessGroup : XAttachProcessPresentationGroup {
    override fun getOrder(): Int = -100
    override fun getGroupName(): String = LuaDebugBundle.message("device.attach.processGroup")
    override fun getItemIcon(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): Icon = LuaDebugIcons.LUA
    override fun getItemDisplayText(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): String =
        listOf(info.executableName, info.args).filter { it.isNotBlank() }.joinToString("  ")
}
