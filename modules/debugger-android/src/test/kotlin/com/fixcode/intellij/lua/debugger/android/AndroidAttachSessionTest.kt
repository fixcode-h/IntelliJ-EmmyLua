package com.fixcode.intellij.lua.debugger.android

import com.fixcode.intellij.lua.debugger.android.adb.AdbClient
import com.fixcode.intellij.lua.debugger.android.adb.AdbExecutor
import com.fixcode.intellij.lua.debugger.android.adb.AdbResult
import com.fixcode.intellij.lua.debugger.android.adb.AndroidDevices
import com.fixcode.intellij.lua.debugger.android.attach.AgentBinaryProvider
import com.fixcode.intellij.lua.debugger.android.attach.AgentConfig
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachException
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachRequest
import com.fixcode.intellij.lua.debugger.android.attach.AndroidAttachSession
import com.fixcode.intellij.lua.debugger.android.attach.AttachEnvironmentChecker
import com.fixcode.intellij.lua.debugger.android.attach.CheckStatus
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 按命令前缀返回预设结果的假 adb，记录所有调用。 */
class FakeAdb : AdbExecutor {
    val calls = ArrayList<List<String>>()
    val pushed = HashMap<String, String>()
    private val rules = ArrayList<Pair<(List<String>) -> Boolean, (List<String>) -> AdbResult>>()

    fun on(match: (List<String>) -> Boolean, result: (List<String>) -> AdbResult) = apply { rules.add(0, match to result) }
    fun onShell(prefix: String, stdout: String, exit: Int = 0) =
        on({ it.getOrNull(2) == "shell" && it[3].startsWith(prefix) }) { AdbResult(exit, stdout, "") }

    override fun run(args: List<String>, timeoutMs: Long): AdbResult {
        calls += args
        if (args.getOrNull(2) == "push") pushed[args[4]] = File(args[3]).readText(Charsets.ISO_8859_1)
        return rules.firstOrNull { it.first(args) }?.second?.invoke(args) ?: AdbResult(0, "", "")
    }

    fun shellCommands(): List<String> = calls.filter { it.getOrNull(2) == "shell" }.map { it[3] }
}

class AndroidAttachSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var symbolFile: File
    private lateinit var agentDir: File
    private lateinit var adb: FakeAdb
    private val pkg = "com.studio.game"
    private val dataDir = "/data/user/0/com.studio.game"

    @Before
    fun setUp() {
        symbolFile = tmp.newFile("libUnreal.so")
        javaClass.classLoader.getResourceAsStream("elf/fake_lua54_arm64.so")!!.use { i -> symbolFile.outputStream().use { i.copyTo(it) } }
        agentDir = tmp.newFolder("agents")
        File(agentDir, "arm64-v8a").mkdirs()
        File(agentDir, "arm64-v8a/${AndroidAttachSession.AGENT_FILE}").writeText("ELF")

        val prefixHex = symbolFile.readBytes().copyOf(8192).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        adb = FakeAdb()
            .on({ it == listOf("devices", "-l") }) { AdbResult(0, "List of devices attached\nSER123 device usb:1-1 product:x model:Pixel_8 device:shiba\n", "") }
            .onShell("getprop ro.build.version.release", "14\n34\narm64-v8a\narm64-v8a,armeabi-v7a\n")
            .onShell("pidof $pkg", "12345\n")
            .onShell("run-as $pkg pwd", "$dataDir\n")
            .onShell("run-as $pkg cat /proc/12345/maps",
                "7000000000-7001000000 r--p 00000000 fd:01 123 /data/app/~~abc/com.studio.game-xyz/lib/arm64/libUnreal.so\n" +
                    "7001000000-7002000000 r-xp 01000000 fd:01 123 /data/app/~~abc/com.studio.game-xyz/lib/arm64/libUnreal.so\n")
            .onShell("run-as $pkg sh -c 'dd if=/data/app/~~abc/com.studio.game-xyz/lib/arm64/libUnreal.so", prefixHex)
            .onShell("run-as $pkg cat $dataDir/code_cache/emmylua/agent.status", "version=3.0.0\nstate=listening\nsocket=emmylua-12345\npid=12345\n")
            .on({ it.getOrNull(2) == "forward" && it[4] == "localabstract:emmylua-12345" }) { AdbResult(0, "54321\n", "") }
    }

    private fun session() = AndroidAttachSession(
        AdbClient(adb), AgentBinaryProvider.fromDirectory(agentDir), LuaSymbolResolver(null), sleeper = {},
    )

    @Test
    fun attachAgentFlow() {
        val result = session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile))
        assertEquals(54321, result.port)
        assertEquals(12345, result.pid)
        assertEquals("emmylua-12345", result.socketName)
        assertFalse(result.reused)
        assertEquals(32, result.token.length)

        val shell = adb.shellCommands()
        val attach = shell.single { it.startsWith("cmd activity attach-agent") }
        assertEquals("cmd activity attach-agent 12345 $dataDir/code_cache/emmylua/libemmylua_agent.so=$dataDir/code_cache/emmylua/agent.cfg", attach)
        // 配置推到共享目录后必须删掉（里面有 token）
        assertTrue(shell.contains("rm -f /data/local/tmp/emmylua/$pkg.cfg"))
        val cfg = adb.pushed["/data/local/tmp/emmylua/$pkg.cfg"]!!
        assertEquals(result.token, AgentConfig.parseToken(cfg))
        assertTrue(cfg.contains("buildId=67dce0dff707ac8d48d3ca83d8a7e5ed2ec1a2e1"))
        assertTrue(cfg.contains("fn.lua_sethook=0x2ed4"))
        assertTrue(cfg.contains("waitIde=0"))
        // 执行顺序：部署在注入之前，注入在转发之前
        val idxCopy = shell.indexOfFirst { it.contains("cp /data/local/tmp/emmylua/") }
        val idxAttach = shell.indexOf(attach)
        assertTrue(idxCopy in 0 until idxAttach)
    }

    @Test
    fun buildIdMismatchStopsBeforeDeploy() {
        val other = File(symbolFile.parentFile, "other.so")
        javaClass.classLoader.getResourceAsStream("elf/fake_lua54_x64.so")!!.use { i -> other.outputStream().use { i.copyTo(it) } }
        // 设备上的 so 换成另一个 Build ID
        val hex = other.readBytes().copyOf(8192).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        adb.onShell("run-as $pkg sh -c 'dd if=", hex)
        try {
            session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile))
            fail()
        } catch (e: AndroidAttachException) {
            assertTrue(e.message, e.message!!.contains("不是同一次构建"))
        }
        assertTrue(adb.calls.none { it.getOrNull(2) == "push" })
    }

    @Test
    fun agentErrorIsReported() {
        adb.onShell("run-as $pkg cat $dataDir/code_cache/emmylua/agent.status", "state=error\nerror=找不到 libUnreal.so\n")
        try {
            session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile))
            fail()
        } catch (e: AndroidAttachException) {
            assertTrue(e.message!!.contains("找不到 libUnreal.so"))
        }
    }

    @Test
    fun reusesLoadedAgent() {
        adb.onShell("run-as $pkg cat /proc/12345/maps",
            "7000000000-7001000000 r--p 00000000 fd:01 123 /data/app/x/lib/arm64/libUnreal.so\n" +
                "7100000000-7100100000 r-xp 00000000 fd:01 9 $dataDir/code_cache/emmylua/libemmylua_agent.so\n")
        adb.onShell("run-as $pkg sh -c 'dd if=", symbolFile.readBytes().copyOf(8192).joinToString(" ") { "%02x".format(it.toInt() and 0xff) })
        adb.onShell("run-as $pkg cat $dataDir/code_cache/emmylua/agent.cfg", "format=1\ntoken=abcdef0123\n")
        val result = session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile))
        assertTrue(result.reused)
        assertEquals("abcdef0123", result.token)
        assertTrue(adb.shellCommands().none { it.startsWith("cmd activity attach-agent") })
    }

    @Test
    fun oldAndroidUsesJdwp() {
        adb.onShell("getprop ro.build.version.release", "8.1.0\n27\narm64-v8a\narm64-v8a\n")
        // JDWP 端口转发指向一个不存在的端口，期望在 JDWP 这一步失败并给出清楚的原因
        adb.on({ it.getOrNull(2) == "forward" && it[4] == "jdwp:12345" }) { AdbResult(0, "1\n", "") }
        try {
            session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile))
            fail()
        } catch (e: AndroidAttachException) {
            assertTrue(e.message, e.message!!.contains("JDWP"))
        }
        assertTrue(adb.shellCommands().none { it.startsWith("cmd activity attach-agent") })
        assertTrue(adb.calls.any { it.getOrNull(2) == "forward" && it[3] == "--remove" })
    }

    @Test
    fun launchModeUsesAmStart() {
        adb.onShell("cmd package resolve-activity --brief $pkg", "priority=0 preferredOrder=0\ncom.studio.game/com.epicgames.unreal.SplashActivity\n")
        val result = session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile, launch = true))
        val shell = adb.shellCommands()
        assertTrue(shell.contains("am force-stop $pkg"))
        assertTrue(shell.any { it == "am start -n com.studio.game/com.epicgames.unreal.SplashActivity --attach-agent $dataDir/code_cache/emmylua/libemmylua_agent.so=$dataDir/code_cache/emmylua/agent.cfg" })
        assertTrue(adb.pushed["/data/local/tmp/emmylua/$pkg.cfg"]!!.contains("waitIde=1"))
        assertEquals(12345, result.pid)
    }

    @Test
    fun launchModeAcceptsAgentWaitingForModule() {
        adb.onShell("cmd package resolve-activity --brief $pkg", "com.studio.game/.Main\n")
        adb.onShell("run-as $pkg cat $dataDir/code_cache/emmylua/agent.status", "state=waiting-module\npid=12345\n")
        val result = session().attach(AndroidAttachRequest("SER123", pkg, symbolFile = symbolFile, launch = true))
        assertEquals("emmylua-12345", result.socketName)
    }

    @Test
    fun rejectsBadPackageName() {
        try {
            session().attach(AndroidAttachRequest("SER123", "com.x; rm -rf /", symbolFile = symbolFile))
            fail()
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("包名"))
        }
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun environmentCheck() {
        adb.onShell("run-as $pkg id", "uid=10234(u0_a234) gid=10234(u0_a234)\n")
        val items = AttachEnvironmentChecker(AdbClient(adb), AgentBinaryProvider.fromDirectory(agentDir), LuaSymbolResolver(null))
            .check("SER123", pkg, symbolFile)
        val byTitle = items.associateBy { it.title }
        assertEquals(CheckStatus.OK, byTitle["adb"]!!.status)
        assertEquals(CheckStatus.OK, byTitle["系统版本"]!!.status)
        assertEquals(CheckStatus.OK, byTitle["符号"]!!.status)
        assertEquals(CheckStatus.OK, byTitle["ABI"]!!.status)
        assertEquals(CheckStatus.OK, byTitle["应用"]!!.status)
        assertEquals(CheckStatus.OK, byTitle["Build ID"]!!.status)
    }

    @Test
    fun environmentCheckWithoutAdb() {
        val items = AttachEnvironmentChecker(null, AgentBinaryProvider.fromDirectory(agentDir), LuaSymbolResolver(null)).check(null, null, null)
        assertEquals(CheckStatus.FAILED, items.single().status)
    }

    @Test
    fun parsers() {
        val devices = AdbClient.parseDevices(
            "List of devices attached\n" +
                "R58N123 device usb:2-1 product:a model:SM_G9910 device:o1s transport_id:3\n" +
                "192.168.1.5:5555 unauthorized transport_id:4\n" +
                "emulator-5554 offline\n"
        )
        assertEquals(3, devices.size)
        assertEquals("SM G9910", devices[0].model)
        assertTrue(devices[1].isWifi && devices[1].isUnauthorized)
        assertTrue(devices[2].isEmulator)

        val ps = AndroidDevices.parsePs("  PID NAME\n    1 init\n 1234 com.a.b\n 1235 com.a.b:remote\n 99 [kthreadd]\n")
        assertEquals(listOf(1 to "init", 1234 to "com.a.b", 1235 to "com.a.b:remote", 99 to "[kthreadd]"), ps)
        assertTrue(AndroidDevices.looksLikeAppProcess("com.a.b:remote"))
        assertFalse(AndroidDevices.looksLikeAppProcess("[kthreadd]"))
        assertFalse(AndroidDevices.looksLikeAppProcess("/system/bin/surfaceflinger"))

        // 旧版 ps：USER PID PPID VSIZE RSS WCHAN PC NAME
        val oldPs = AndroidDevices.parsePs("USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME\nu0_a1     2000  300   100    10    ffffffff 00000000 S com.x.y\n")
        assertEquals(listOf(2000 to "com.x.y"), oldPs)

        val apk = AndroidDevices.parseMappedLibrary(
            "70000-80000 r-xp 0045a000 fd:01 1 /data/app/com.x-1/base.apk!/lib/arm64-v8a/libUnreal.so\n" +
                "60000-70000 r--p 00459000 fd:01 1 /data/app/com.x-1/base.apk!/lib/arm64-v8a/libUnreal.so\n", "libUnreal.so")!!
        assertEquals("/data/app/com.x-1/base.apk", apk.path)
        assertEquals(0x459000L, apk.fileOffset)

        val dbg = AndroidDevices.parseDebuggablePackages(
            "Packages:\n  Package [com.a.b] (1):\n    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]\n" +
                "  Package [com.c.d] (2):\n    flags=[ HAS_CODE ]\n"
        )
        assertEquals(setOf("com.a.b"), dbg)
    }
}
