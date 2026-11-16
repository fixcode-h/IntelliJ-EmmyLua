package com.fixcode.intellij.lua.debugger.android.adb

import com.fixcode.intellij.lua.debugger.android.elf.ElfPrefix

data class DeviceInfo(
    val serial: String,
    val model: String,
    val state: String,
    val isWifi: Boolean,
    val isEmulator: Boolean,
    val androidVersion: String?,
    val apiLevel: Int?,
    val abi: String?,
    val abiList: List<String>,
) {
    val isOnline: Boolean get() = state == "device"
    /** Android 9（API 28）起可以对可调试应用 attach-agent。 */
    val supportsAttachAgent: Boolean get() = (apiLevel ?: 0) >= 28
}

data class DeviceProcess(
    val pid: Int,
    /** 进程名，例如 com.studio.game 或 com.studio.game:remote。 */
    val processName: String,
    val debuggable: Boolean,
    /** 进程里加载了 libUnreal.so。 */
    val isUnreal: Boolean = false,
) {
    val packageName: String get() = processName.substringBefore(':')
}

/** 设备上某个 so 的映射位置：可能是解压出来的文件，也可能直接从 APK 里按偏移映射（extractNativeLibs=false）。 */
data class MappedLibrary(val path: String, val fileOffset: Long)

/**
 * 设备与进程查询。只用 adb 自带能力和 toybox 命令，不在设备上装任何东西。
 */
class AndroidDevices(private val adb: AdbClient) {

    fun list(): List<DeviceInfo> = adb.devices().map { d ->
        if (!d.isOnline) {
            DeviceInfo(d.serial, d.model, d.state, d.isWifi, d.isEmulator, null, null, null, emptyList())
        } else {
            // 一次 shell 取多个属性，少几次 adb 往返
            val out = adb.shell(
                d.serial,
                "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist",
            ).stdout.lines().map { it.trim() }
            DeviceInfo(
                serial = d.serial,
                model = d.model,
                state = d.state,
                isWifi = d.isWifi,
                isEmulator = d.isEmulator,
                androidVersion = out.getOrNull(0)?.takeIf { it.isNotEmpty() },
                apiLevel = out.getOrNull(1)?.toIntOrNull(),
                abi = out.getOrNull(2)?.takeIf { it.isNotEmpty() },
                abiList = out.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }
    }

    fun info(serial: String): DeviceInfo =
        list().firstOrNull { it.serial == serial } ?: throw AdbException("找不到设备 $serial，请确认 USB 已连接或无线调试已配对")

    /**
     * 列进程。可调试的判断用 adb jdwp（系统只为可调试应用开 JDWP），比逐个 run-as 快得多。
     * [detectUnreal] 时再对可调试进程查 /proc/<pid>/maps 里有没有 libUnreal.so。
     */
    fun processes(serial: String, onlyDebuggable: Boolean = true, detectUnreal: Boolean = true, soName: String = "libUnreal.so"): List<DeviceProcess> {
        val debuggablePids = adb.jdwpPids(serial)
        var psOut = adb.shell(serial, "ps -A -o PID,NAME").stdout
        if (!psOut.contains("PID")) {
            // Android 7 及以下的 ps 不认 -A / -o
            psOut = adb.shell(serial, "ps").stdout
        }
        val all = parsePs(psOut)
        val list = all.filter { !onlyDebuggable || it.first in debuggablePids }
            .filter { looksLikeAppProcess(it.second) }
            .map { (pid, name) -> DeviceProcess(pid, name, pid in debuggablePids) }
        if (!detectUnreal) return list
        return list.map { p ->
            if (p.debuggable && mappedLibrary(serial, p, soName) != null) p.copy(isUnreal = true) else p
        }.sortedWith(compareByDescending<DeviceProcess> { it.isUnreal }.thenBy { it.processName })
    }

    /** 已安装的可调试包：给"启动应用并附加"用。逐个包 run-as 太慢，这里读 dumpsys 的 flags。 */
    fun debuggablePackages(serial: String): List<String> {
        val pkgs = adb.shell(serial, "pm list packages -3", 20_000).stdout.lines()
            .mapNotNull { it.trim().removePrefix("package:").takeIf { s -> isValidPackage(s) } }
        if (pkgs.isEmpty()) return emptyList()
        // dumpsys package 一次输出所有包，按 "Package [xxx]" 分段找 DEBUGGABLE 标志
        val dump = adb.shell(serial, "dumpsys package packages", 30_000).stdout
        val debuggable = parseDebuggablePackages(dump)
        return pkgs.filter { it in debuggable }.sorted()
    }

    fun findPid(serial: String, packageName: String): Int? {
        requireValidPackage(packageName)
        val out = adb.shell(serial, "pidof $packageName").stdout.trim()
        return out.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    }

    /** 进程里 so 的映射：取 offset 最小的那一段（ELF 头所在段）。 */
    fun mappedLibrary(serial: String, process: DeviceProcess, soName: String): MappedLibrary? {
        requireValidPackage(process.packageName)
        val r = adb.shell(serial, "run-as ${process.packageName} cat /proc/${process.pid}/maps")
        if (!r.ok) return null
        return parseMappedLibrary(r.stdout, soName)
    }

    /**
     * 读设备上 so 的 Build ID：只读开头 8 KB（ELF 头、程序头、PT_NOTE 都在里面），用 od 转成十六进制带回来。
     * APK 内直接映射的 so 也能读：按映射里的文件偏移跳过去。
     */
    fun remoteBuildId(serial: String, packageName: String, lib: MappedLibrary): String? {
        requireValidPackage(packageName)
        requireSafePath(lib.path)
        if (lib.fileOffset % 4096 != 0L) return null
        val skip = lib.fileOffset / 4096
        val cmd = "run-as $packageName sh -c 'dd if=${lib.path} bs=4096 skip=$skip count=2 2>/dev/null | od -An -v -tx1'"
        val r = adb.shell(serial, cmd)
        if (!r.ok) return null
        val bytes = parseOdHex(r.stdout)
        return ElfPrefix.buildId(bytes)
    }

    companion object {
        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val SAFE_PATH_RE = Regex("^/[A-Za-z0-9_./~=+@-]+$")

        fun isValidPackage(name: String) = PACKAGE_RE.matches(name)

        fun requireValidPackage(name: String) {
            if (!isValidPackage(name)) throw AdbException("包名格式不对：$name")
        }

        fun requireSafePath(path: String) {
            if (!SAFE_PATH_RE.matches(path) || path.contains("..")) throw AdbException("设备路径含有不支持的字符：$path")
        }

        /** 兼容新旧两种 ps 输出：取第一列数字为 PID 的列和最后一列为名字。 */
        fun parsePs(output: String): List<Pair<Int, String>> {
            val lines = output.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()
            val header = lines.first().trim().split(Regex("\\s+"))
            val pidCol = header.indexOf("PID").takeIf { it >= 0 } ?: return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                val pid = cols.getOrNull(pidCol)?.toIntOrNull() ?: return@mapNotNull null
                val name = cols.lastOrNull() ?: return@mapNotNull null
                pid to name
            }
        }

        /** 应用进程名形如 com.a.b 或 com.a.b:xxx；过滤掉 [kthreadd]、/system/bin/xxx 这类。 */
        fun looksLikeAppProcess(name: String): Boolean = isValidPackage(name.substringBefore(':'))

        fun parseMappedLibrary(maps: String, soName: String): MappedLibrary? {
            var best: MappedLibrary? = null
            for (line in maps.lines()) {
                val cols = line.trim().split(Regex("\\s+"), limit = 6)
                if (cols.size < 6) continue
                val path = cols[5].trim()
                val offset = cols[2].toLongOrNull(16) ?: continue
                val matches = path.endsWith("/$soName") ||
                    // APK 内直接映射时路径是 base.apk，maps 里有时附带 "!/lib/arm64-v8a/libUnreal.so"
                    (path.contains(".apk") && path.contains(soName))
                if (!matches) continue
                val file = path.substringBefore("!/")
                if (best == null || offset < best.fileOffset) best = MappedLibrary(file, offset)
            }
            return best
        }

        fun parseOdHex(output: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (token in output.split(Regex("\\s+"))) {
                if (token.length == 2) token.toIntOrNull(16)?.let { out.write(it) }
            }
            return out.toByteArray()
        }

        fun parseDebuggablePackages(dump: String): Set<String> {
            val result = HashSet<String>()
            var current: String? = null
            for (line in dump.lines()) {
                val t = line.trim()
                if (t.startsWith("Package [")) {
                    current = t.removePrefix("Package [").substringBefore(']')
                    continue
                }
                if (current != null && (t.startsWith("flags=[") || t.startsWith("pkgFlags=[")) && t.contains(" DEBUGGABLE ")) {
                    result += current
                }
            }
            return result
        }
    }
}
