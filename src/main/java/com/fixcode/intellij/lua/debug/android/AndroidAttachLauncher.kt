package com.fixcode.intellij.lua.debug.android

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaDeviceService
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.fixcode.intellij.lua.debugger.android.adb.AdbClient
import com.fixcode.intellij.lua.debugger.android.adb.AdbLocator
import com.fixcode.intellij.lua.debugger.android.adb.ProcessAdbExecutor
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachException
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachRequest
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachResult
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachSession
import java.io.File

/**
 * 给引擎的 LuaDebugProcessFactory 用：拿到 [LuaDebugTarget.AndroidDevice] 后调 [attach]，
 * 得到本机端口和握手 token，之后按普通 TCP + 协议 v3 连接；会话结束时调返回对象的 [Handle.detach]。
 * 阻塞调用，在后台线程执行；失败抛 [AndroidAttachException]，message 可直接显示给用户。
 */
object AndroidAttachLauncher {

    class Handle(val result: AndroidAttachResult, private val session: AndroidAttachSession) {
        /** 只撤掉 adb 转发；agent 留在进程里，下次附加复用。 */
        fun detach() = session.detach()
    }

    /**
     * 符号按「指定的 so → 符号目录（Build ID）→ 设备 so 自带的符号 → 参考 so 特征码」逐级找，见 LuaSymbolLocator。
     * [cancelled] 返回 true（调试会话已停）时特征码搜索和等待 agent 尽快停下。
     */
    fun attach(project: Project, target: LuaDebugTarget.AndroidDevice, log: (String) -> Unit, cancelled: () -> Boolean = { false }): Handle {
        val settings = LuaDebugSettings.getInstance()
        val adbFile = AdbLocator.locate(settings.adbPath)
            ?: throw AndroidAttachException("没有找到 adb：安装 Android SDK Platform-Tools，或在设置里指定 adb 路径")
        val adb = AdbClient(ProcessAdbExecutor(adbFile))
        val session = AndroidAttachSession(
            adb,
            AndroidLuaDeviceService.agentBinaries(),
            AndroidLuaDeviceService.symbolLocator,
            log,
            cancelled = cancelled,
        )
        val result = session.attach(
            AndroidAttachRequest(
                serial = target.deviceSerial,
                packageName = target.packageName,
                pid = target.pid,
                launch = target.launchAndAttach,
                symbolFile = target.symbolFile?.takeIf { it.isNotBlank() }?.let(::File),
                moduleName = AndroidLuaDeviceService.MODULE_NAME,
                symbolDirs = settings.expandedSymbolSearchDirs(project).map(::File),
                referenceSo = (target.referenceSo?.takeIf { it.isNotBlank() } ?: settings.expandedReferenceSo(project))?.let(::File),
            )
        )
        return Handle(result, session)
    }
}
