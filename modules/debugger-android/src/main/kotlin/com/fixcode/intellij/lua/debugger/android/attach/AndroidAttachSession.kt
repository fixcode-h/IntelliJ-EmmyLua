package com.fixcode.intellij.lua.debugger.android.attach

import com.fixcode.intellij.lua.debugger.android.adb.AdbClient
import com.fixcode.intellij.lua.debugger.android.adb.AdbException
import com.fixcode.intellij.lua.debugger.android.adb.AndroidDevices
import com.fixcode.intellij.lua.debugger.android.adb.DeviceInfo
import com.fixcode.intellij.lua.debugger.android.adb.DeviceProcess
import com.fixcode.intellij.lua.debugger.android.adb.MappedLibrary
import com.fixcode.intellij.lua.debugger.android.elf.DeviceLibrary
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolLocator
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolTable
import com.fixcode.intellij.lua.debugger.android.elf.SymbolSearchOptions
import com.fixcode.intellij.lua.debugger.android.jdwp.JdwpLoader
import java.io.File
import java.security.SecureRandom

/** 按 ABI 找本地的 agent 二进制（插件包里 debugger/emmy/android/<abi>/libemmylua_agent.so）。 */
fun interface AgentBinaryProvider {
    fun agentFor(abi: String): File?

    companion object {
        fun fromDirectory(dir: File) = AgentBinaryProvider { abi ->
            File(dir, "$abi/${AndroidAttachSession.AGENT_FILE}").takeIf { it.isFile }
        }
    }
}

data class AndroidAttachRequest(
    val serial: String,
    val packageName: String,
    /** 为空时按包名找正在运行的进程。 */
    val pid: Int? = null,
    /** true = 重启应用并在启动时加载 agent，第一次执行 Lua 时停住等 IDE。 */
    val launch: Boolean = false,
    /** 运行配置里指定的未裁剪 so；为空表示按 [symbolDirs]、设备 so、[referenceSo] 逐级找。 */
    val symbolFile: File? = null,
    /** 静态链接 Lua 的 so 名，UE 是 libUnreal.so。 */
    val moduleName: String = "libUnreal.so",
    /** 设置里的符号目录，按 Build ID 找同一次构建的未裁剪 so。 */
    val symbolDirs: List<File> = emptyList(),
    /** 参考 so：没有同一次构建的符号时按特征码找函数。 */
    val referenceSo: File? = null,
) {
    val symbolOptions: SymbolSearchOptions
        get() = SymbolSearchOptions(symbolFile, symbolDirs, referenceSo, moduleName)
}

data class AndroidAttachResult(
    val host: String,
    val port: Int,
    val token: String,
    val pid: Int,
    val socketName: String,
    val symbols: LuaSymbolTable,
    /** 进程里已经有 agent（之前附加过），这次直接复用。 */
    val reused: Boolean,
)

class AndroidAttachException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 真机附加的完整流程（设计文档 7.2）：校验符号 → 部署 agent → 注入 → 等 agent 就绪 → 建 adb 转发。
 * 连上之后走普通 TCP + 协议 v3，和 PC 附加一样由引擎处理。
 *
 * 全部是阻塞调用，调用方在后台线程执行。[log] 用来把每一步显示到调试控制台。
 */
class AndroidAttachSession(
    private val adb: AdbClient,
    private val agents: AgentBinaryProvider,
    private val symbolLocator: LuaSymbolLocator,
    private val log: (String) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /** 调试会话已停止时返回 true：特征码搜索、等 agent 都会尽快停下。 */
    private val cancelled: () -> Boolean = { false },
) {
    private val devices = AndroidDevices(adb)
    private var forwardedPort: Int? = null
    private var forwardedSerial: String? = null

    fun attach(request: AndroidAttachRequest): AndroidAttachResult = try {
        doAttach(request)
    } catch (e: AdbException) {
        throw AndroidAttachException(e.message ?: "adb 调用失败", e)
    } catch (e: java.util.concurrent.CancellationException) {
        throw AndroidAttachException("已取消附加", e)
    }

    private fun doAttach(request: AndroidAttachRequest): AndroidAttachResult {
        AndroidDevices.requireValidPackage(request.packageName)
        val device = devices.info(request.serial)
        if (device.state == "unauthorized") throw AndroidAttachException("设备未授权：请在手机上点「允许 USB 调试」")
        if (!device.isOnline) throw AndroidAttachException("设备 ${device.model} 当前状态是 ${device.state}，无法附加")

        if (!request.launch) {
            val pid = request.pid ?: devices.findPid(device.serial, request.packageName)
            ?: throw AndroidAttachException("${request.packageName} 没有在运行：先启动游戏，或勾选「启动应用并附加」")
            val process = DeviceProcess(pid, request.packageName, debuggable = true)
            val lib = devices.mappedLibrary(device.serial, process, request.moduleName)
                ?: throw AndroidAttachException("进程 ${process.pid} 里没有加载 ${request.moduleName}：确认选的是游戏进程，或等游戏进入主界面后再附加")
            val remoteId = devices.remoteBuildId(device.serial, process.packageName, lib)
            if (remoteId == null) log("无法在附加前读取设备上 ${request.moduleName} 的 Build ID，交给 agent 校验")
            val symbols = resolveSymbols(request, device, lib, remoteId)
            verifyRemoteBuildId(remoteId, symbols)
            val agentBinary = agentFor(device, symbols)
            val dataDir = appDataDir(device.serial, request.packageName)
            val agentPath = "$dataDir/$REMOTE_DIR/$AGENT_FILE"
            val configPath = "$dataDir/$REMOTE_DIR/$CONFIG_FILE"
            reuseExisting(device, process, configPath)?.let { token ->
                log("进程里已经有 agent，直接复用")
                return finish(device, pid, token, symbols, reused = true, statusPath = "$dataDir/$REMOTE_DIR/$STATUS_FILE", packageName = request.packageName, launched = false)
            }
            val token = newToken()
            deploy(device, request.packageName, agentBinary, AgentConfig.from(symbols, token, request.moduleName, request.packageName, waitForIde = false))
            inject(device, process, agentPath, configPath)
            return finish(device, pid, token, symbols, reused = false, statusPath = "$dataDir/$REMOTE_DIR/$STATUS_FILE", packageName = request.packageName, launched = false)
        }

        // 启动模式：应用还没跑，从安装目录 / APK 里找 so 读 Build ID；找不到就只能靠本地文件，由 agent 校验
        val lib = try {
            devices.installedLibrary(device.serial, request.packageName, request.moduleName, device.abiList)
        } catch (e: AdbException) {
            null
        }
        val remoteId = lib?.let { devices.remoteBuildId(device.serial, request.packageName, it) }
        val symbols = resolveSymbols(request, device, lib, remoteId)
        verifyRemoteBuildId(remoteId, symbols)
        val agentBinary = agentFor(device, symbols)
        val dataDir = appDataDir(device.serial, request.packageName)
        val agentPath = "$dataDir/$REMOTE_DIR/$AGENT_FILE"
        val configPath = "$dataDir/$REMOTE_DIR/$CONFIG_FILE"
        // 先停掉应用，部署好 agent 再带着 agent 启动
        val token = newToken()
        adb.shellChecked(device.serial, "am force-stop ${request.packageName}")
        deploy(device, request.packageName, agentBinary, AgentConfig.from(symbols, token, request.moduleName, request.packageName, waitForIde = true))
        val pid = launch(device, request.packageName, agentPath, configPath)
        return finish(device, pid, token, symbols, reused = false, statusPath = "$dataDir/$REMOTE_DIR/$STATUS_FILE", packageName = request.packageName, launched = true)
    }

    /** 断开：只撤掉本机端口转发。agent 留在进程里继续监听，下次附加直接复用（JVMTI agent 无法卸载）。 */
    fun detach() {
        val port = forwardedPort ?: return
        val serial = forwardedSerial ?: return
        try {
            adb.removeForward(serial, port)
        } catch (_: Exception) {
        }
        forwardedPort = null
    }

    // ---- 各步骤 ----

    /** 逐级找 Lua 函数偏移（见 [LuaSymbolLocator]），拿不全必需函数就报错并说明每一级为什么不行。 */
    private fun resolveSymbols(request: AndroidAttachRequest, device: DeviceInfo, lib: MappedLibrary?, remoteId: String?): LuaSymbolTable {
        val deviceLib = lib?.let { l ->
            DeviceLibrary(remoteId, "${device.serial}:${l.path}") { devices.openRemoteElf(device.serial, request.packageName, l) }
        }
        val lookup = symbolLocator.locate(request.symbolOptions, deviceLib, log, cancelled)
        val table = lookup.table ?: throw AndroidAttachException(lookup.failureMessage())
        log(table.describe())
        return table
    }

    private fun agentFor(device: DeviceInfo, symbols: LuaSymbolTable): File {
        val abi = symbols.abi ?: throw AndroidAttachException("无法从 ${File(symbols.sourcePath).name} 判断 ABI")
        if (device.abiList.isNotEmpty() && abi !in device.abiList) {
            throw AndroidAttachException("${File(symbols.sourcePath).name} 是 $abi，设备只支持 ${device.abiList.joinToString()}")
        }
        return agents.agentFor(abi)
            ?: throw AndroidAttachException("插件里没有 $abi 的 agent（$AGENT_FILE），请确认插件包完整")
    }

    private fun verifyRemoteBuildId(remote: String?, symbols: LuaSymbolTable) {
        if (remote == null) return
        if (!remote.equals(symbols.buildId, ignoreCase = true)) {
            throw AndroidAttachException(
                "本地 so 和设备上的不是同一次构建（本地 Build ID ${symbols.buildId.take(12)}…，设备 ${remote.take(12)}…）：" +
                    "请指定这次安装包对应的未裁剪 so，或重新打包安装"
            )
        }
        log("Build ID 与设备一致")
    }

    private fun reuseExisting(device: DeviceInfo, process: DeviceProcess, configPath: String): String? {
        devices.mappedLibrary(device.serial, process, AGENT_FILE) ?: return null
        val r = adb.shell(device.serial, "run-as ${process.packageName} cat $configPath")
        if (!r.ok) return null
        return AgentConfig.parseToken(r.stdout)
    }

    private fun appDataDir(serial: String, pkg: String): String {
        val r = adb.shell(serial, "run-as $pkg pwd")
        val dir = r.stdout.trim().lines().lastOrNull()?.trim().orEmpty()
        if (!r.ok || !dir.startsWith("/")) {
            val reason = (r.stdout + r.stderr).trim()
            throw AndroidAttachException(
                if (reason.contains("not debuggable")) "$pkg 不是可调试包：请安装 Development 包（android:debuggable=true）"
                else if (reason.contains("unknown package")) "设备上没有安装 $pkg"
                else "run-as $pkg 失败：$reason"
            )
        }
        AndroidDevices.requireSafePath(dir)
        return dir
    }

    private fun deploy(device: DeviceInfo, pkg: String, agentBinary: File, config: AgentConfig) {
        log("部署 agent（${agentBinary.parentFile?.name ?: ""}）")
        val stagingAgent = "$STAGING_DIR/${agentBinary.parentFile?.name ?: "agent"}-$AGENT_FILE"
        val stagingConfig = "$STAGING_DIR/$pkg.cfg"
        adb.shellChecked(device.serial, "mkdir -p $STAGING_DIR")
        adb.push(device.serial, agentBinary, stagingAgent)
        val tmpCfg = File.createTempFile("emmylua-agent", ".cfg")
        try {
            tmpCfg.writeText(config.render())
            adb.push(device.serial, tmpCfg, stagingConfig)
        } finally {
            tmpCfg.delete()
        }
        try {
            // run-as 以应用身份执行，cp 后文件属于应用、在应用私有目录，attach-agent 和 System.load 都能读
            adb.shellChecked(
                device.serial,
                "run-as $pkg sh -c 'mkdir -p $REMOTE_DIR && cp $stagingAgent $REMOTE_DIR/$AGENT_FILE && " +
                    "cp $stagingConfig $REMOTE_DIR/$CONFIG_FILE && chmod 700 $REMOTE_DIR/$AGENT_FILE && " +
                    "chmod 600 $REMOTE_DIR/$CONFIG_FILE && rm -f $REMOTE_DIR/$STATUS_FILE'",
            )
        } finally {
            // 配置里有握手 token，不在共享目录多留
            adb.shell(device.serial, "rm -f $stagingConfig")
        }
    }

    private fun inject(device: DeviceInfo, process: DeviceProcess, agentPath: String, configPath: String) {
        if (device.supportsAttachAgent) {
            log("attach-agent → 进程 ${process.pid}")
            val r = adb.shell(device.serial, "cmd activity attach-agent ${process.pid} $agentPath=$configPath", 20_000)
            val out = (r.stdout + r.stderr).trim()
            if (!r.ok || out.contains("Exception") || out.contains("Error", ignoreCase = false)) {
                throw AndroidAttachException(
                    if (out.contains("not debuggable")) "${process.packageName} 不是可调试包：请安装 Development 包"
                    else "attach-agent 失败：${out.ifEmpty { "退出码 ${r.exitCode}" }}"
                )
            }
        } else {
            log("Android ${device.androidVersion ?: ""} 没有 attach-agent，改用 JDWP 加载 agent")
            loadViaJdwp(device, process.pid, agentPath)
        }
    }

    private fun loadViaJdwp(device: DeviceInfo, pid: Int, agentPath: String) {
        val port = adb.forward(device.serial, "jdwp:$pid")
        try {
            JdwpLoader("127.0.0.1", port, log).loadLibrary(agentPath)
        } catch (e: java.io.IOException) {
            throw AndroidAttachException("通过 JDWP 加载 agent 失败：${e.message}", e)
        } finally {
            adb.removeForward(device.serial, port)
        }
    }

    private fun launch(device: DeviceInfo, pkg: String, agentPath: String, configPath: String): Int {
        val component = adb.shellChecked(device.serial, "cmd package resolve-activity --brief $pkg").lines()
            .map { it.trim() }.lastOrNull { it.contains('/') }
            ?: throw AndroidAttachException("找不到 $pkg 的启动 Activity")
        if (device.supportsAttachAgent) {
            log("启动 $component 并加载 agent")
            val out = adb.shellChecked(device.serial, "am start -n $component --attach-agent $agentPath=$configPath")
            if (out.contains("Error")) throw AndroidAttachException("启动失败：${out.trim()}")
            return waitPid(device, pkg)
        }
        // Android 8 及以下：-D 让应用启动后等调试器，再用 JDWP 加载 agent
        log("启动 $component（等待调试器），再用 JDWP 加载 agent")
        adb.shellChecked(device.serial, "am start -D -n $component")
        val pid = waitPid(device, pkg)
        loadViaJdwp(device, pid, agentPath)
        return pid
    }

    private fun waitPid(device: DeviceInfo, pkg: String): Int {
        repeat(50) {
            devices.findPid(device.serial, pkg)?.let { return it }
            sleeper(200)
        }
        throw AndroidAttachException("$pkg 启动后 10 秒内没有找到进程")
    }

    private fun finish(device: DeviceInfo, pid: Int, token: String, symbols: LuaSymbolTable, reused: Boolean, statusPath: String, packageName: String, launched: Boolean): AndroidAttachResult {
        // 启动模式下 libUnreal.so 还没加载，agent 处于 waiting-module 就可以先建转发；
        // 运行中附加时 so 早已加载，必须等到 listening，否则会错过随后的 Build ID 校验错误
        val status = waitAgent(device, packageName, statusPath, acceptWaitingModule = launched)
        val socket = status.socket ?: "$SOCKET_PREFIX$pid"
        val port = adb.forward(device.serial, "localabstract:$socket")
        forwardedPort = port
        forwardedSerial = device.serial
        log("已转发 127.0.0.1:$port → $socket")
        return AndroidAttachResult("127.0.0.1", port, token, pid, socket, symbols, reused)
    }

    /** 轮询 agent 写的状态文件。agent 报错（Build ID 不符、找不到符号、hook 失败）在这里变成给用户的提示。 */
    private fun waitAgent(device: DeviceInfo, pkg: String, statusPath: String, acceptWaitingModule: Boolean, timeoutMs: Long = 10_000): AgentStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: AgentStatus? = null
        while (System.currentTimeMillis() < deadline) {
            if (cancelled()) throw AndroidAttachException("已取消附加")
            val r = adb.shell(device.serial, "run-as $pkg cat $statusPath")
            if (r.ok && r.stdout.isNotBlank()) {
                val s = AgentStatus.parse(r.stdout)
                last = s
                if (s.isError) throw AndroidAttachException("agent 报错：${s.error ?: "未知错误"}（详细日志：adb logcat -s EmmyLuaAgent）")
                if (s.isListening || (acceptWaitingModule && s.state == "waiting-module")) return s
            }
            sleeper(200)
        }
        if (last == null) {
            throw AndroidAttachException("agent 没有启动（10 秒内没有状态）：查看 adb logcat -s EmmyLuaAgent")
        }
        throw AndroidAttachException("agent 10 秒内没有就绪（当前状态 ${last.state}）：查看 adb logcat -s EmmyLuaAgent")
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val AGENT_FILE = "libemmylua_agent.so"
        const val CONFIG_FILE = "agent.cfg"
        const val STATUS_FILE = "agent.status"
        const val REMOTE_DIR = "code_cache/emmylua"
        const val STAGING_DIR = "/data/local/tmp/emmylua"
        const val SOCKET_PREFIX = "emmylua-"
    }
}
