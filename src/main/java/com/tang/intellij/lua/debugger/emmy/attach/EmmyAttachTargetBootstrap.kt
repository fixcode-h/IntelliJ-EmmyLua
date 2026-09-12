package com.tang.intellij.lua.debugger.emmy.attach

import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfoRt
import com.tang.intellij.lua.debugger.DebugLogLevel
import com.tang.intellij.lua.debugger.emmy.EmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.SocketClientTransporter
import com.tang.intellij.lua.debugger.emmy.Transporter
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

        val port = waitForDebugPort(attachedPid)
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
        val capabilities = AttachToolRunner(timeoutMillis = 5_000L)
            .runCapabilities(listOf(toolPath, "capabilities"), toolDir)
        if (capabilities.exitCode != 0 || !isAttachToolCompatible(capabilities.capabilities)) {
            error(
                "Native 附加工具过旧或不兼容，已在注入前终止: " +
                    "exitCode=${capabilities.exitCode} tool=$toolPath " +
                    "输出=${summarizeToolOutput(capabilities.output)}"
            )
        }
        log("Native 附加工具能力探测通过: ${capabilities.capabilities}", DebugLogLevel.DEBUG)

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

        val displayCommands = commands.mapIndexed { index, value ->
            if (index > 0 && commands[index - 1] == "-auth-token") "<redacted>" else value
        }
        log("执行附加命令: ${displayCommands.joinToString(" ")} (认证令牌通过进程环境传递)", DebugLogLevel.DEBUG)
        val result = AttachToolRunner().run(commands, toolDir, authToken) { line ->
            val parsed = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
            if (parsed?.get("schemaVersion")?.asInt == 1) {
                val status = AttachBootstrapStatus.fromJson(parsed)
                log("attach 状态: ${status.summary()}", DebugLogLevel.DEBUG)
                status
            } else {
                log("attach: $line", DebugLogLevel.DEBUG)
                null
            }
        }
        val exitCode = result.exitCode
        if (exitCode != 0) {
            val status = result.status
            error(
                "附加失败，emmy_tool 退出码: $exitCode，状态=${status?.status ?: "unknown"}，" +
                    "错误=${status?.message ?: "未提供"}，输出=${summarizeToolOutput(result.output)}"
            )
        }
        val status = checkNotNull(result.status) {
            "附加工具未返回结构化启动状态（无法证明已注入/监听），输出=${summarizeToolOutput(result.output)}"
        }
        check(isAttachBootstrapReady(status, configuration.pid)) {
            if (status.alreadyAttached) {
                "目标进程已有 Emmy Agent，当前无法安全重协商认证 token"
            } else {
                "Emmy Agent 启动证据不完整: status=${status.status} pid=${status.pid} " +
                    "expectedPid=${configuration.pid} injected=${status.injected}, " +
                    "listening=${status.listening}, authReady=${status.authReady}"
            }
        }
    }

    private fun waitForDebugPort(pid: Int): Int {
        var delayMillis = 50L
        var interrupted = false
        repeat(7) {
            if (interrupted) return@repeat
            val port = ProcessUtils.getPortFromPid(pid)
            if (port > 0) return port
            try {
                TimeUnit.MILLISECONDS.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
                return@repeat
            }
            delayMillis = (delayMillis * 2).coerceAtMost(800L)
        }
        error("Emmy Agent 已报告 auth-ready，但未能在超时内发现调试端口；请检查目标进程日志（不会自动重复注入）")
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

internal data class AttachToolResult(
    val exitCode: Int,
    val status: AttachBootstrapStatus?,
    val output: List<String> = emptyList()
)

internal data class AttachToolCapabilitiesResult(
    val exitCode: Int,
    val capabilities: AttachToolCapabilities?,
    val output: List<String> = emptyList()
)

internal class AttachToolRunner(
    private val timeoutMillis: Long = 90_000L
) {
    fun run(command: List<String>, directory: File, authToken: String, onLine: (String) -> AttachBootstrapStatus?): AttachToolResult {
        val result = runProcess(command, directory, authToken, onLine)
        return AttachToolResult(result.exitCode, result.payload, result.output)
    }

    fun runCapabilities(command: List<String>, directory: File): AttachToolCapabilitiesResult {
        val result = runProcess(command, directory, null) { line ->
            val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
            if (json?.get("schemaVersion")?.asInt == 1) AttachToolCapabilities.fromJson(json) else null
        }
        return AttachToolCapabilitiesResult(result.exitCode, result.payload, result.output)
    }

    private data class ProcessResult<T>(
        val exitCode: Int,
        val payload: T?,
        val output: List<String>
    )

    private fun <T> runProcess(
        command: List<String>,
        directory: File,
        authToken: String?,
        onLine: (String) -> T?
    ): ProcessResult<T> {
        val builder = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
        if (authToken != null) {
            // Avoid exposing the attach token in command-line inspection tools.
            builder.environment()["EMMY_ATTACH_AUTH_TOKEN"] = authToken
        }
        val process = builder.start()
        val payload = AtomicReference<T?>(null)
        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = Thread {
            try {
                readLines(process) { line ->
                    output.add(line)
                    onLine(line)?.let(payload::set)
                }
            } catch (_: Exception) {
                // Process shutdown may close the stream while the reader is blocked.
            }
        }.apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminate(process)
                throw IllegalStateException(
                    "工具进程在 ${timeoutMillis / 1000} 秒内未退出，已终止（可能卡在注入或继承输出管道）"
                )
            }
            reader.join(1_000L)
            return ProcessResult(process.exitValue(), payload.get(), output.toList())
        } catch (error: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw IllegalStateException("工具进程等待被中断，已终止", error)
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { reader.join(1_000L) }
            if (process.isAlive) terminate(process)
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (process.isAlive && !runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        }
    }

    private fun readLines(process: Process, onLine: (String) -> Unit) {
        val input = process.inputStream
        val buffer = ByteArray(8 * 1024)
        val line = ByteArrayOutputStream(4 * 1024)
        var truncated = false
        var totalBytes = 0L
        fun flushLine() {
            if (!truncated && line.size() > 0) {
                onLine(line.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r'))
            }
            line.reset()
            truncated = false
        }
        while (true) {
            if (input.available() == 0) {
                if (!process.isAlive) {
                    flushLine()
                    return
                }
                Thread.sleep(10L)
                continue
            }
            val count = input.read(buffer)
            if (count < 0) {
                flushLine()
                return
            }
            totalBytes += count
            if (totalBytes > 1024 * 1024) {
                process.destroy()
                return
            }
            for (index in 0 until count) {
                when (buffer[index].toInt()) {
                    '\n'.code -> {
                        if (!truncated) onLine(line.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r'))
                        line.reset()
                        truncated = false
                    }
                    else -> if (!truncated) {
                        if (line.size() < 64 * 1024) line.write(buffer[index].toInt()) else truncated = true
                    }
                }
            }
        }
    }
}

internal fun isAttachBootstrapReady(status: AttachBootstrapStatus, expectedPid: Int): Boolean =
    status.pid == expectedPid && status.injected && status.listening &&
        status.authReady && status.status == "auth-ready"

internal data class AttachToolCapabilities(
    val schemaVersion: Int,
    val tool: String,
    val attachBootstrapStatus: Boolean,
    val attachStatusSchemaVersion: Int,
    val attachAuthTokenEnv: String?
) {
    companion object {
        fun fromJson(json: com.google.gson.JsonObject): AttachToolCapabilities = AttachToolCapabilities(
            schemaVersion = json.get("schemaVersion")?.asInt ?: 0,
            tool = json.get("tool")?.asString ?: "",
            attachBootstrapStatus = json.get("attachBootstrapStatus")?.asBoolean ?: false,
            attachStatusSchemaVersion = json.get("attachStatusSchemaVersion")?.asInt ?: 0,
            attachAuthTokenEnv = json.get("attachAuthTokenEnv")?.asString
        )
    }
}

internal fun isAttachToolCompatible(capabilities: AttachToolCapabilities?): Boolean =
    capabilities != null &&
        capabilities.schemaVersion == 1 &&
        capabilities.tool == "emmy_tool" &&
        capabilities.attachBootstrapStatus &&
        capabilities.attachStatusSchemaVersion == 1 &&
        capabilities.attachAuthTokenEnv == "EMMY_ATTACH_AUTH_TOKEN"

internal fun summarizeToolOutput(output: List<String>): String =
    output.takeLast(8).joinToString(" | ").ifBlank { "无输出" }.take(1_000)

internal data class AttachBootstrapStatus(
    val status: String,
    val pid: Int,
    val injected: Boolean,
    val listening: Boolean,
    val authReady: Boolean,
    val alreadyAttached: Boolean,
    val message: String?
) {
    fun summary(): String = "status=$status pid=$pid injected=$injected listening=$listening authReady=$authReady alreadyAttached=$alreadyAttached"

    companion object {
        fun fromJson(json: com.google.gson.JsonObject): AttachBootstrapStatus = AttachBootstrapStatus(
            status = json.get("status")?.asString ?: "unknown",
            pid = json.get("pid")?.asInt ?: 0,
            injected = json.get("injected")?.asBoolean ?: false,
            listening = json.get("listening")?.asBoolean ?: false,
            authReady = json.get("authReady")?.asBoolean ?: false,
            alreadyAttached = json.get("alreadyAttached")?.asBoolean ?: false,
            message = json.get("message")?.asString
        )
    }
}
