package com.fixcode.intellij.lua.debug.ui.process

import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.fixcode.intellij.lua.debug.api.LuaLocalProcess
import com.fixcode.intellij.lua.debug.api.LuaProcessTagProvider
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 找出本机哪些进程加载了 Lua。
 *
 * 旧实现对每个进程起一次 PowerShell 查模块，列表一长就要等几十秒；
 * 这里一次 `tasklist /M lua*` 拿到所有进程的 Lua 模块，Linux 读 /proc/<pid>/maps。
 * 架构从 PE 头判断（设计文档 10.2：删掉"选择 x86/x64"）。
 */
object LuaLocalProcessScanner {
    private val LOG = logger<LuaLocalProcessScanner>()

    /** 模块名 → Lua 版本。只认确定是 Lua 运行时的名字，避免 luacheck.dll 这类误判。 */
    private val LUA_MODULES = linkedMapOf(
        "lua51.dll" to "5.1", "lua5.1.dll" to "5.1",
        "lua52.dll" to "5.2", "lua5.2.dll" to "5.2",
        "lua53.dll" to "5.3", "lua5.3.dll" to "5.3",
        "lua54.dll" to "5.4", "lua5.4.dll" to "5.4",
        "luajit.dll" to "LuaJIT", "lua.dll" to null,
        "liblua.so" to null, "liblua5.1.so" to "5.1", "liblua5.2.so" to "5.2",
        "liblua5.3.so" to "5.3", "liblua5.4.so" to "5.4", "libluajit-5.1.so" to "LuaJIT",
    )

    fun listProcesses(): List<ProcessInfo> = OSProcessUtil.getProcessList().toList()

    /** 一次扫描所有进程；返回 pid → 结果。耗时操作，只在后台线程调。 */
    fun scan(processes: Collection<ProcessInfo>): Map<Int, LuaLocalProcess> {
        val modules = when {
            SystemInfo.isWindows -> windowsLuaModules()
            SystemInfo.isLinux -> processes.associate { it.pid to linuxLuaModules(it.pid) }
            else -> emptyMap()
        }
        return processes.associate { info ->
            val path = info.executableCannonicalPath.orElse(null)
            val luaModules = modules[info.pid].orEmpty()
            info.pid to LuaLocalProcess(
                pid = info.pid,
                name = info.executableName,
                executablePath = path,
                commandLine = info.commandLine,
                arch = path?.let { detectArch(it) },
                luaModules = luaModules,
                luaVersion = luaModules.firstNotNullOfOrNull { LUA_MODULES[it.lowercase()] },
            )
        }
    }

    fun tags(process: LuaLocalProcess): List<String> =
        LuaProcessTagProvider.EP.extensionList.flatMap { it.tags(process) }.distinct()

    /** 排序优先级：各标签提供者给的最大值（带 UELink 名片的 UE 进程最高）。 */
    fun priority(process: LuaLocalProcess): Int =
        LuaProcessTagProvider.EP.extensionList.maxOfOrNull { runCatching { it.priority(process) }.getOrDefault(0) } ?: 0

    /** 归到"Lua"分组：检测到 Lua 模块，或者标签提供者认为它是 Lua 宿主（Lua 静态链接的 UE 进程）。 */
    fun isLuaCandidate(process: LuaLocalProcess): Boolean =
        process.hasLuaRuntime || LuaProcessTagProvider.EP.extensionList.any { it.isLikelyLuaHost(process) }

    private fun isLuaModule(name: String) = LUA_MODULES.containsKey(name.lowercase())

    private fun windowsLuaModules(): Map<Int, List<String>> {
        return try {
            val proc = ProcessBuilder("tasklist", "/M", "lua*", "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return emptyMap()
            }
            parseTasklistCsv(output)
        } catch (e: Exception) {
            LOG.info("tasklist 扫描 Lua 模块失败", e)
            emptyMap()
        }
    }

    /** `"UE4Editor.exe","1234","lua54.dll,luasocket.dll"` → 1234 → [lua54.dll]。 */
    internal fun parseTasklistCsv(output: String): Map<Int, List<String>> {
        val result = HashMap<Int, List<String>>()
        for (line in output.lineSequence()) {
            val cols = Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }.toList()
            if (cols.size < 3) continue
            val pid = cols[1].toIntOrNull() ?: continue
            val luaModules = cols[2].split(',').map { it.trim() }.filter { isLuaModule(it) }
            if (luaModules.isNotEmpty()) result[pid] = luaModules
        }
        return result
    }

    private fun linuxLuaModules(pid: Int): List<String> = try {
        File("/proc/$pid/maps").useLines { lines ->
            lines.mapNotNull { line -> line.substringAfterLast('/', "").takeIf { it.isNotEmpty() } }
                .map { it.substringBefore(".so") + ".so" }
                .filter { isLuaModule(it) }
                .distinct()
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 读 PE 头的 Machine 字段；不是 PE 或读不了返回 null。 */
    internal fun detectArch(path: String): String? {
        if (!path.endsWith(".exe", ignoreCase = true)) return null
        return try {
            RandomAccessFile(path, "r").use { f ->
                f.seek(0x3C)
                val peOffset = Integer.reverseBytes(f.readInt()).toLong()
                f.seek(peOffset)
                if (Integer.reverseBytes(f.readInt()) != 0x00004550) return null // "PE\0\0"
                when (java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xFFFF) {
                    0x8664 -> "x64"
                    0x014C -> "x86"
                    0xAA64 -> "arm64"
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
