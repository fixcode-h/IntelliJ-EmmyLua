package com.fixcode.intellij.lua.debug.xdebugger

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Windows 本机附加：调 emmy_tool 把 emmy_attach.dll 注入目标进程，拿到 runtime 实际监听的地址。
 *
 * 约定（native 线程，2026-11-16）：
 * - 位数：`emmy_tool.exe arch_pid <pid>`，退出码 0 = x64，否则 x86；用对应目录下的工具和 dll。
 * - 注入：`emmy_tool.exe attach -p <pid> -dir <位数目录> -dll emmy_hook.dll -auth-token <token> -protocol 3`。
 * - 结果：stdout 最后一行 JSON。成功 `{"ok":true,"protocol":3,"host":"127.0.0.1","port":N,"pid":P,"reinjected":false}`，
 *   失败 `{"ok":false,"error":"中文原因"}` + 非 0 退出码。端口以 JSON 为准（被占用时 runtime 会顺延）。
 * - 目标还没加载 Lua 时返回 `"waiting":true`（退出码 0）：组件在后台等 Lua 出现后才监听端口，IDE 持续连端口即可，不重新注入。
 * - 进程里已有 hook 时不重复注入，只更新 token 并返回同样的 JSON（reinjected:true），所以每次建连都可以先跑一遍。
 * - 启动并附加：`emmy_tool.exe launch -dir <位数目录> -auth-token <token> -protocol 3`，程序、工作目录、参数走环境变量
 *   EMMY_LAUNCH_EXE / EMMY_LAUNCH_WORK / EMMY_LAUNCH_ARGS（命令行转引号会弄坏带空格和引号的参数）。程序停在入口点时注入，
 *   结果和 attach 一样，多一个 `"launched":true`，`pid` 是新进程。
 */
class LocalAttachTool(
    /** 含 x86/、x64/ 子目录的工具根目录。 */
    private val toolsRoot: File,
    private val log: (String) -> Unit = {},
    private val timeoutSeconds: Long = 30
) {
    data class Result(
        val host: String,
        val port: Int,
        val pid: Int,
        val reinjected: Boolean,
        /** 目标还没加载 Lua：调试组件已就位，Lua 加载后才开始监听 [port]，这期间连不上是正常的。 */
        val waiting: Boolean = false,
        val message: String? = null,
        /** 插件自带的 runtime 版本（出包时写的 runtime-version.txt）；本地开发版没有。 */
        val bundledRuntime: String? = null
    )

    /** [arch] 为空时现查；同一个会话里应该只查一次（进程退出后 arch_pid 会失败，不能拿它的结果回退成 x86）。 */
    fun attach(pid: Int, token: String, arch: String = detectArch(pid)): Result {
        val dir = File(toolsRoot, arch)
        val tool = File(dir, TOOL)
        if (!tool.isFile) throw IOException("插件里缺少 $arch/$TOOL，请确认插件包完整")
        log("注入 $arch 调试组件到进程 $pid …")
        val (exit, output) = run(listOf(tool.path, "attach", "-p", pid.toString(), "-dir", dir.path, "-dll", HOOK, "-auth-token", token, "-protocol", "3"), dir)
        val bundled = File(dir, "runtime-version.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        val result = parseResult(output, exit).copy(bundledRuntime = bundled)
        // waiting 的提示由调用方用「任何级别都显示」的方式打出（用户要知道在等什么）
        if (!result.waiting) log(
            when {
                result.reinjected -> "进程里已有调试组件，复用它（${result.host}:${result.port}）"
                else -> "注入完成，runtime 监听 ${result.host}:${result.port}"
            }
        )
        return result
    }

    /** 启动 [exe] 并在它跑任何自己的代码前注入调试组件（协议 v3 第 16 节）。位数按 exe 的 PE 头选。 */
    fun launch(exe: File, args: String, workDir: File?, token: String): Result {
        val arch = exeArch(exe)
        val dir = File(toolsRoot, arch)
        val tool = File(dir, TOOL)
        if (!tool.isFile) throw IOException("插件里缺少 $arch/$TOOL，请确认插件包完整")
        log("启动 ${exe.name}（$arch）并注入调试组件 …")
        val env = buildMap {
            put("EMMY_LAUNCH_EXE", exe.path)
            put("EMMY_LAUNCH_ARGS", args)
            workDir?.let { put("EMMY_LAUNCH_WORK", it.path) }
        }
        val (exit, output) = run(listOf(tool.path, "launch", "-dir", dir.path, "-auth-token", token, "-protocol", "3"), dir, env, LAUNCH_TIMEOUT_SECONDS)
        val bundled = File(dir, "runtime-version.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        val result = parseResult(output, exit).copy(bundledRuntime = bundled)
        if (result.pid <= 0) throw IOException("启动结果里没有进程号：${output.trim().takeLast(200)}")
        log("已启动进程 ${result.pid}，调试组件已就位")
        return result
    }

    /** 目标进程的位数（x64 / x86）。emmy_tool arch_pid 退出码 0 = x64，1 = x86，其他是出错（进程不存在、没权限）。 */
    fun detectArch(pid: Int): String {
        val probe = File(File(toolsRoot, "x86"), TOOL).takeIf { it.isFile } ?: File(File(toolsRoot, "x64"), TOOL)
        if (!probe.isFile) throw IOException("插件里缺少 $TOOL，请确认插件包完整")
        val (exit, output) = run(listOf(probe.path, "arch_pid", pid.toString()), probe.parentFile)
        return when (exit) {
            0 -> "x64"
            1 -> "x86"
            else -> throw IOException("无法判断进程 $pid 的位数（emmy_tool 退出码 $exit）：${output.trim().takeLast(200)}")
        }
    }

    private fun run(command: List<String>, dir: File, env: Map<String, String> = emptyMap(), timeout: Long = timeoutSeconds): Pair<Int, String> {
        val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).also { it.environment().putAll(env) }.start()
        process.outputStream.close()
        // emmy_tool 以 UTF-8 输出
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        val output = StringBuilder()
        val pump = Thread({ reader.forEachLine { line -> synchronized(output) { output.appendLine(line) } } }, "emmy-tool-output").apply { isDaemon = true; start() }
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("注入超时（${timeout} 秒没有结果），目标进程可能卡住或被安全软件拦截")
        }
        pump.join(1000)
        return process.exitValue() to synchronized(output) { output.toString() }
    }

    companion object {
        const val TOOL = "emmy_tool.exe"
        const val HOOK = "emmy_hook.dll"
        /** emmy_tool 自己最多等 90 秒程序跑到入口点，这里多留一点。 */
        private const val LAUNCH_TIMEOUT_SECONDS = 120L

        /**
         * 读 exe 的 PE 头判断位数：x64 / x86。ARM64 等不支持的直接报错。
         * 不用 emmy_tool arch_file：它按 ANSI 代码页收路径，中文路径会找不到文件。
         */
        fun exeArch(exe: File): String {
            if (!exe.isFile) throw IOException("找不到程序：${exe.path}")
            val machine = try {
                java.io.RandomAccessFile(exe, "r").use { f ->
                    if (f.length() < 0x40) null
                    else {
                        f.seek(0x3C)
                        val peOffset = Integer.reverseBytes(f.readInt()).toLong()
                        if (peOffset <= 0 || peOffset + 6 > f.length()) null
                        else {
                            f.seek(peOffset)
                            if (Integer.reverseBytes(f.readInt()) != 0x00004550) null
                            else java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xFFFF
                        }
                    }
                }
            } catch (e: IOException) {
                throw IOException("读不了程序文件 ${exe.path}：${e.message}", e)
            }
            return when (machine) {
                0x8664 -> "x64"
                0x014C -> "x86"
                null -> throw IOException("${exe.name} 不是 Windows 可执行文件")
                else -> throw IOException("${exe.name} 的架构（0x${machine.toString(16)}）不支持，只支持 x64 和 x86")
            }
        }

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
                reinjected = json.get("reinjected")?.asBoolean == true,
                waiting = json.get("waiting")?.asBoolean == true,
                message = json.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            )
        }
    }
}
