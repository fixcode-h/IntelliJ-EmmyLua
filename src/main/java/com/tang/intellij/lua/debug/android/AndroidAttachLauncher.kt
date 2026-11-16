package com.tang.intellij.lua.debug.android

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.tang.intellij.lua.debug.api.LuaDebugTarget
import com.tang.intellij.lua.debug.api.LuaDeviceService
import com.tang.intellij.lua.debug.ui.settings.LuaDebugSettings
import com.tang.intellij.lua.debugger.android.adb.AdbClient
import com.tang.intellij.lua.debugger.android.adb.AdbLocator
import com.tang.intellij.lua.debugger.android.adb.ProcessAdbExecutor
import com.tang.intellij.lua.debugger.android.attach.AndroidAttachException
import com.tang.intellij.lua.debugger.android.attach.AndroidAttachRequest
import com.tang.intellij.lua.debugger.android.attach.AndroidAttachResult
import com.tang.intellij.lua.debugger.android.attach.AndroidAttachSession
import com.tang.intellij.lua.debugger.android.elf.LuaSymbolResolver
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

    fun attach(project: Project, target: LuaDebugTarget.AndroidDevice, log: (String) -> Unit): Handle {
        val adbFile = AdbLocator.locate(LuaDebugSettings.getInstance().adbPath)
            ?: throw AndroidAttachException("没有找到 adb：安装 Android SDK Platform-Tools，或在设置里指定 adb 路径")
        val adb = AdbClient(ProcessAdbExecutor(adbFile))
        val symbolPath = (LuaDeviceService.getInstance() ?: AndroidLuaDeviceService())
            .resolveSymbolFile(project, target.symbolFile, target.deviceSerial, target.packageName)
            ?: throw AndroidAttachException(
                "没有找到未裁剪的 ${AndroidLuaDeviceService.MODULE_NAME}：在运行配置里指定，或在设置里添加符号搜索目录"
            )
        val session = AndroidAttachSession(
            adb,
            AndroidLuaDeviceService.agentBinaries(),
            LuaSymbolResolver(File(PathManager.getSystemPath(), "emmylua/android-symbols")),
            log,
        )
        val result = session.attach(
            AndroidAttachRequest(
                serial = target.deviceSerial,
                packageName = target.packageName,
                pid = target.pid,
                launch = target.launchAndAttach,
                symbolFile = File(symbolPath),
                moduleName = AndroidLuaDeviceService.MODULE_NAME,
            )
        )
        return Handle(result, session)
    }
}
