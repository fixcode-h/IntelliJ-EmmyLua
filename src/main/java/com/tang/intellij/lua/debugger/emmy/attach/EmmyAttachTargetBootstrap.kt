package com.tang.intellij.lua.debugger.emmy.attach

import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfoRt
import com.tang.intellij.lua.debugger.DebugLogLevel
import com.tang.intellij.lua.debugger.emmy.EmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.SocketClientTransporter
import com.tang.intellij.lua.debugger.emmy.Transporter
import java.io.File
import java.security.SecureRandom

class EmmyAttachTargetBootstrap(
    private val configuration: EmmyAttachDebugConfiguration,
    private val log: (String, DebugLogLevel) -> Unit
) : EmmyTargetBootstrap {
    private var attachedPid = 0
    private var debugPort = 0
    private var generatedAuthToken: String? = null

    override val authToken: String?
        get() = generatedAuthToken

    override fun prepareTransports(): List<Transporter> {
        check(SystemInfoRt.isWindows) { "附加调试目前仅支持 Windows 系统" }
        DebuggerPathUtils.validateDebuggerTools()?.let { error(it) }

        val detectedArch = ProcessUtils.detectProcessArch(configuration.pid)
        val configuredArch = configuration.winArch.toWinArch()
        val selectedArch = if (detectedArch != configuredArch) {
            log("检测到进程架构为 $detectedArch，配置为 $configuredArch，将使用检测结果", DebugLogLevel.WARNING)
            detectedArch
        } else {
            configuredArch
        }

        val toolPath = DebuggerPathUtils.getEmmyToolPath(selectedArch)
            ?: error("找不到 $selectedArch 架构的调试工具 emmy_tool.exe")
        val hookPath = DebuggerPathUtils.getEmmyHookPath(selectedArch)
            ?: error("找不到 $selectedArch 架构的调试库 emmy_hook.dll")
        val toolDir = File(toolPath).parentFile

        generatedAuthToken = generateAuthToken()
        runAttachTool(toolPath, hookPath, toolDir, generatedAuthToken!!)
        attachedPid = configuration.pid
        log("成功附加到进程 ${configuration.pid}", DebugLogLevel.RUNTIME)

        if (configuration.captureLog) {
            startLogCapture(toolPath, toolDir)
        }
        reportLuaRuntime(selectedArch)

        val port = ProcessUtils.getPortFromPid(attachedPid)
        debugPort = port
        return listOf("127.0.0.1", "::1", "localhost").map { host ->
            SocketClientTransporter(host, port)
        }
    }

    override fun prepareReconnectTransports(): List<Transporter> {
        check(debugPort > 0) { "附加调试端点尚未准备完成" }
        return listOf("127.0.0.1", "::1", "localhost").map { host ->
            SocketClientTransporter(host, debugPort)
        }
    }

    override fun stop() {
        if (attachedPid != 0) {
            log("附加会话已释放；注入 DLL 由目标进程持有到进程退出", DebugLogLevel.DEBUG)
            attachedPid = 0
        }
        debugPort = 0
        generatedAuthToken = null
    }

    private fun runAttachTool(toolPath: String, hookPath: String, toolDir: File, authToken: String) {
        val commands = mutableListOf(
            toolPath,
            "attach",
            "-p",
            configuration.pid.toString(),
            "-dir",
            toolDir.absolutePath,
            "-dll",
            File(hookPath).name,
            "-auth-token",
            authToken
        )
        if (configuration.captureLog) commands += "-capture-log"

        val displayCommands = commands.mapIndexed { index, value ->
            if (index > 0 && commands[index - 1] == "-auth-token") "<redacted>" else value
        }
        log("执行附加命令: ${displayCommands.joinToString(" ")}", DebugLogLevel.DEBUG)
        val process = ProcessBuilder(commands)
            .directory(toolDir)
            .redirectErrorStream(true)
            .start()

        var bootstrapStatus: com.google.gson.JsonObject? = null
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                log("attach: $line", DebugLogLevel.DEBUG)
                val parsed = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                if (parsed?.get("schemaVersion")?.asInt == 1) bootstrapStatus = parsed
            }
        }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "附加失败，emmy_tool 退出码: $exitCode" }
        val status = checkNotNull(bootstrapStatus) { "附加工具未返回结构化启动状态" }
        check(status.get("status")?.asString == "auth-ready") {
            if (status.get("alreadyAttached")?.asBoolean == true) {
                "目标进程已有 Emmy Agent，当前无法安全重协商认证 token"
            } else {
                "Emmy Agent 未进入 auth-ready 状态: ${status.get("status")?.asString}"
            }
        }
    }

    private fun generateAuthToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun startLogCapture(toolPath: String, toolDir: File) {
        try {
            ProcessBuilder(toolPath, "receive_log", "-p", configuration.pid.toString())
                .directory(toolDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            log("已启动目标进程日志捕获", DebugLogLevel.DEBUG)
        } catch (error: Exception) {
            log("启动日志捕获失败: ${error.message}", DebugLogLevel.WARNING)
        }
    }

    private fun reportLuaRuntime(arch: WinArch) {
        val analysis = ProcessUtils.analyzeProcessModules(configuration.pid, arch)
        when {
            analysis.errorMessage != null ->
                log("模块分析失败: ${analysis.errorMessage}", DebugLogLevel.WARNING)
            analysis.hasLuaRuntime ->
                log("检测到 Lua 运行时: ${analysis.luaModules.joinToString()}", DebugLogLevel.DEBUG)
            else ->
                log("未检测到标准 Lua 运行时，调试连接可能失败", DebugLogLevel.WARNING)
        }
    }
}
