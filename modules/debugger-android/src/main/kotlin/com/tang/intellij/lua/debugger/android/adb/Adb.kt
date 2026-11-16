package com.tang.intellij.lua.debugger.android.adb

import java.io.File
import java.util.concurrent.TimeUnit

/** 一次 adb 调用的结果。[timedOut] 时 stdout 是超时前已经读到的部分（adb jdwp 这类不会自己退出的命令要用）。 */
data class AdbResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/** 抽象出来便于单测：测试里用脚本化的假实现。 */
fun interface AdbExecutor {
    fun run(args: List<String>, timeoutMs: Long): AdbResult
}

class AdbException(message: String, val result: AdbResult? = null) : Exception(message)

/** 找 adb：设置里指定的 > ANDROID_HOME / ANDROID_SDK_ROOT 的 platform-tools > PATH。 */
object AdbLocator {
    fun locate(configured: String?, env: Map<String, String> = System.getenv()): File? {
        val exe = if (isWindows()) "adb.exe" else "adb"
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            val f = File(path)
            return when {
                f.isFile -> f
                f.isDirectory && File(f, exe).isFile -> File(f, exe)
                f.isDirectory && File(f, "platform-tools/$exe").isFile -> File(f, "platform-tools/$exe")
                else -> null
            }
        }
        for (key in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val sdk = env[key]?.takeIf { it.isNotBlank() } ?: continue
            val f = File(sdk, "platform-tools/$exe")
            if (f.isFile) return f
        }
        val path = env["PATH"] ?: env["Path"] ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val f = File(dir, exe)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}

/** 用真实的 adb 进程执行。 */
class ProcessAdbExecutor(private val adb: File) : AdbExecutor {
    override fun run(args: List<String>, timeoutMs: Long): AdbResult {
        val process = ProcessBuilder(listOf(adb.absolutePath) + args)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
        process.outputStream.close()
        val out = StreamCollector(process.inputStream).also { it.start() }
        val err = StreamCollector(process.errorStream).also { it.start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        out.join(1000)
        err.join(1000)
        return AdbResult(if (finished) process.exitValue() else -1, out.text(), err.text(), timedOut = !finished)
    }

    private class StreamCollector(private val input: java.io.InputStream) : Thread("adb-output") {
        private val buffer = java.io.ByteArrayOutputStream()
        init { isDaemon = true }
        override fun run() {
            try {
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
            } catch (_: java.io.IOException) {
            }
        }
        fun text(): String = synchronized(buffer) { buffer.toString(Charsets.UTF_8) }
    }
}

/**
 * adb 的薄封装。所有设备命令都带 -s，避免多设备时打到别的设备上。
 * 设备端的 shell 命令里出现的包名、路径都先校验格式，不拼接任意字符串。
 */
class AdbClient(private val executor: AdbExecutor) {

    fun devices(): List<AdbDevice> {
        val r = executor.run(listOf("devices", "-l"), DEFAULT_TIMEOUT)
        if (!r.ok) throw AdbException("adb devices 失败：${r.stderr.trim().ifEmpty { r.stdout.trim() }}", r)
        return parseDevices(r.stdout)
    }

    fun shell(serial: String, command: String, timeoutMs: Long = DEFAULT_TIMEOUT): AdbResult =
        executor.run(listOf("-s", serial, "shell", command), timeoutMs)

    fun shellChecked(serial: String, command: String, timeoutMs: Long = DEFAULT_TIMEOUT): String {
        val r = shell(serial, command, timeoutMs)
        if (!r.ok) throw AdbException("设备命令失败（$command）：${(r.stderr + r.stdout).trim().ifEmpty { "退出码 ${r.exitCode}" }}", r)
        return r.stdout
    }

    fun getProp(serial: String, name: String): String =
        shell(serial, "getprop $name").stdout.trim()

    fun push(serial: String, local: File, remote: String) {
        val r = executor.run(listOf("-s", serial, "push", local.absolutePath, remote), 60_000)
        if (!r.ok) throw AdbException("推送 ${local.name} 失败：${(r.stderr + r.stdout).trim()}", r)
    }

    /** adb forward tcp:0 <remote>，返回系统分配的本地端口。 */
    fun forward(serial: String, remote: String, localPort: Int = 0): Int {
        val r = executor.run(listOf("-s", serial, "forward", "tcp:$localPort", remote), DEFAULT_TIMEOUT)
        if (!r.ok) throw AdbException("建立端口转发失败（$remote）：${(r.stderr + r.stdout).trim()}", r)
        if (localPort != 0) return localPort
        return r.stdout.trim().lines().firstOrNull()?.trim()?.toIntOrNull()
            ?: throw AdbException("adb forward 没有返回端口：${r.stdout.trim()}", r)
    }

    fun removeForward(serial: String, localPort: Int) {
        executor.run(listOf("-s", serial, "forward", "--remove", "tcp:$localPort"), DEFAULT_TIMEOUT)
    }

    /**
     * 可调试进程的 pid。adb jdwp 会一直等新进程，不会自己退出，所以靠超时收尾，取已输出的部分。
     */
    fun jdwpPids(serial: String, waitMs: Long = 1500): Set<Int> {
        val r = executor.run(listOf("-s", serial, "jdwp"), waitMs)
        return r.stdout.lines().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    companion object {
        const val DEFAULT_TIMEOUT = 10_000L

        fun parseDevices(output: String): List<AdbDevice> = output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val props = parts.drop(2).mapNotNull { p ->
                    val i = p.indexOf(':')
                    if (i > 0) p.substring(0, i) to p.substring(i + 1) else null
                }.toMap()
                AdbDevice(serial = parts[0], state = parts[1], properties = props)
            }
    }
}

data class AdbDevice(val serial: String, val state: String, val properties: Map<String, String>) {
    val model: String get() = properties["model"]?.replace('_', ' ') ?: serial
    val isOnline: Boolean get() = state == "device"
    val isUnauthorized: Boolean get() = state == "unauthorized"
    val isEmulator: Boolean get() = serial.startsWith("emulator-")
    /** adb connect 的无线设备序列号是 ip:port，adb pair 的是 adb-xxx._adb-tls-connect._tcp。 */
    val isWifi: Boolean get() = serial.contains(':') || serial.contains("._adb-tls-connect.")
}
