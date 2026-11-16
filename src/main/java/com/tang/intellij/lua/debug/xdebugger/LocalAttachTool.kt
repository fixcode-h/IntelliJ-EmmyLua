package com.tang.intellij.lua.debug.xdebugger

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Windows 本机附加：调 emmy_tool 把 emmy_hook 注入目标进程，拿到 runtime 实际监听的地址。
 *
 * 约定（native 线程，2026-11-16）：
 * - 位数：`emmy_tool.exe arch_pid <pid>`，退出码 0 = x64，否则 x86；用对应目录下的工具和 dll。
 * - 注入：`emmy_tool.exe attach -p <pid> -dir <位数目录> -dll emmy_hook.dll -auth-token <token> -protocol 3`。
 * - 结果：stdout 最后一行 JSON。成功 `{"ok":true,"protocol":3,"host":"127.0.0.1","port":N,"pid":P,"reinjected":false}`，
 *   失败 `{"ok":false,"error":"中文原因"}` + 非 0 退出码。端口以 JSON 为准（被占用时 runtime 会顺延）。
 * - 进程里已有 hook 时不重复注入，只更新 token 并返回同样的 JSON（reinjected:true），所以每次建连都可以先跑一遍。
 */
class LocalAttachTool(
    /** 含 x86/、x64/ 子目录的工具根目录。 */
    private val toolsRoot: File,
    private val log: (String) -> Unit = {},
    private val timeoutSeconds: Long = 30
) {
    data class Result(val host: String, val port: Int, val pid: Int, val reinjected: Boolean)

    fun attach(pid: Int, token: String): Result {
        val arch = detectArch(pid)
        val dir = File(toolsRoot, arch)
        val tool = File(dir, TOOL)
        if (!tool.isFile) throw IOException("插件里缺少 $arch/$TOOL，请确认插件包完整")
        log("注入 $arch 调试组件到进程 $pid …")
        val (exit, output) = run(listOf(tool.path, "attach", "-p", pid.toString(), "-dir", dir.path, "-dll", HOOK, "-auth-token", token, "-protocol", "3"), dir)
        val result = parseResult(output, exit)
        log(if (result.reinjected) "进程里已有调试组件，复用它（${result.host}:${result.port}）" else "注入完成，runtime 监听 ${result.host}:${result.port}")
        return result
    }

    private fun detectArch(pid: Int): String {
        val probe = File(File(toolsRoot, "x86"), TOOL).takeIf { it.isFile } ?: File(File(toolsRoot, "x64"), TOOL)
        if (!probe.isFile) throw IOException("插件里缺少 $TOOL，请确认插件包完整")
        val (exit, _) = run(listOf(probe.path, "arch_pid", pid.toString()), probe.parentFile)
        return if (exit == 0) "x64" else "x86"
    }

    private fun run(command: List<String>, dir: File): Pair<Int, String> {
        val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
        process.outputStream.close()
        // emmy_tool 以 UTF-8 输出
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        val output = StringBuilder()
        val pump = Thread({ reader.forEachLine { line -> synchronized(output) { output.appendLine(line) } } }, "emmy-tool-output").apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("注入超时（${timeoutSeconds} 秒没有结果），目标进程可能卡住或被安全软件拦截")
        }
        pump.join(1000)
        return process.exitValue() to synchronized(output) { output.toString() }
    }

    companion object {
        const val TOOL = "emmy_tool.exe"
        const val HOOK = "emmy_hook.dll"

        /** 32 位十六进制，每次附加新生成。 */
        fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** 取 stdout 最后一个非空行解析；其余行是注入过程的日志。 */
        fun parseResult(output: String, exitCode: Int): Result {
            val last = output.lines().lastOrNull { it.isNotBlank() }?.trim()
            val json = last?.takeIf { it.startsWith("{") }?.let {
                try {
                    JsonParser.parseString(it).asJsonObject
                } catch (_: Exception) {
                    null
                }
            } ?: throw IOException("注入失败（emmy_tool 退出码 $exitCode，没有返回结果）：${output.trim().takeLast(300)}")
            if (json.get("ok")?.asBoolean != true || exitCode != 0) {
                throw IOException("注入失败：" + (json.get("error")?.asString ?: "emmy_tool 退出码 $exitCode"))
            }
            val protocol = json.get("protocol")?.asInt
            if (protocol != 3) throw IOException("目标进程里的调试组件是旧版（协议 $protocol），请重启游戏后再附加")
            return Result(
                host = json.get("host")?.asString ?: "127.0.0.1",
                port = json.get("port")?.asInt ?: throw IOException("注入结果里没有端口：$last"),
                pid = json.get("pid")?.asInt ?: 0,
                reinjected = json.get("reinjected")?.asBoolean == true
            )
        }
    }
}
