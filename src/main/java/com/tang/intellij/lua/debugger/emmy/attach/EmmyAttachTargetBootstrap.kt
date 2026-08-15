package com.tang.intellij.lua.debugger.emmy.attach

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.SystemInfoRt
import com.tang.intellij.lua.debugger.emmy.EmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.SocketClientTransporter
import com.tang.intellij.lua.debugger.emmy.Transporter
import java.io.File

class EmmyAttachTargetBootstrap(
    private val configuration: EmmyAttachDebugConfiguration,
    private val log: (String, LogLevel, ConsoleViewContentType?) -> Unit
) : EmmyTargetBootstrap {
    private var attachedPid = 0

    override fun prepareTransports(): List<Transporter> {
        check(SystemInfoRt.isWindows) { "附加调试目前仅支持 Windows 系统" }
        DebuggerPathUtils.validateDebuggerTools()?.let { error(it) }

        val detectedArch = ProcessUtils.detectProcessArch(configuration.pid)
        val configuredArch = configuration.winArch.toWinArch()
        val selectedArch = if (detectedArch != configuredArch) {
            log("检测到进程架构为 $detectedArch，配置为 $configuredArch，将使用检测结果", LogLevel.WARNING, null)
            detectedArch
        } else {
            configuredArch
        }

        val toolPath = DebuggerPathUtils.getEmmyToolPath(selectedArch)
            ?: error("找不到 $selectedArch 架构的调试工具 emmy_tool.exe")
        val hookPath = DebuggerPathUtils.getEmmyHookPath(selectedArch)
            ?: error("找不到 $selectedArch 架构的调试库 emmy_hook.dll")
        val toolDir = File(toolPath).parentFile

        runAttachTool(toolPath, hookPath, toolDir)
        attachedPid = configuration.pid
        log("成功附加到进程 ${configuration.pid}", LogLevel.NORMAL, null)

        if (configuration.captureLog) {
            startLogCapture(toolPath, toolDir)
        }
        reportLuaRuntime(selectedArch)

        Thread.sleep(100)
        val port = ProcessUtils.getPortFromPid(attachedPid)
        return listOf("127.0.0.1", "::1", "localhost").map { host ->
            SocketClientTransporter(host, port)
        }
    }

    override fun stop() {
        if (attachedPid != 0) {
            log("附加会话已释放；注入 DLL 由目标进程持有到进程退出", LogLevel.DEBUG, null)
            attachedPid = 0
        }
    }

    private fun runAttachTool(toolPath: String, hookPath: String, toolDir: File) {
        val commands = mutableListOf(
            toolPath,
            "attach",
            "-p",
            configuration.pid.toString(),
            "-dir",
            toolDir.absolutePath,
            "-dll",
            File(hookPath).name
        )
        if (configuration.captureLog) commands += "-capture-log"

        log("执行附加命令: ${commands.joinToString(" ")}", LogLevel.DEBUG, null)
        val process = ProcessBuilder(commands)
            .directory(toolDir)
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> log("attach: $line", LogLevel.DEBUG, null) }
        }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "附加失败，emmy_tool 退出码: $exitCode" }
    }

    private fun startLogCapture(toolPath: String, toolDir: File) {
        try {
            ProcessBuilder(toolPath, "receive_log", "-p", configuration.pid.toString())
                .directory(toolDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            log("已启动目标进程日志捕获", LogLevel.DEBUG, null)
        } catch (error: Exception) {
            log("启动日志捕获失败: ${error.message}", LogLevel.WARNING, ConsoleViewContentType.LOG_WARNING_OUTPUT)
        }
    }

    private fun reportLuaRuntime(arch: WinArch) {
        val analysis = ProcessUtils.analyzeProcessModules(configuration.pid, arch)
        when {
            analysis.errorMessage != null ->
                log("模块分析失败: ${analysis.errorMessage}", LogLevel.WARNING, null)
            analysis.hasLuaRuntime ->
                log("检测到 Lua 运行时: ${analysis.luaModules.joinToString()}", LogLevel.DEBUG, null)
            else ->
                log("未检测到标准 Lua 运行时，调试连接可能失败", LogLevel.WARNING, null)
        }
    }
}
