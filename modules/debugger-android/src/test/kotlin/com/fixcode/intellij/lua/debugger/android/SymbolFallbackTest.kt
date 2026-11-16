package com.fixcode.intellij.lua.debugger.android

import com.fixcode.intellij.lua.debugger.android.adb.AdbClient
import com.fixcode.intellij.lua.debugger.android.adb.AdbExecutor
import com.fixcode.intellij.lua.debugger.android.adb.AdbResult
import com.fixcode.intellij.lua.debugger.android.adb.ApkZip
import com.fixcode.intellij.lua.debugger.android.adb.RemoteFileSource
import com.fixcode.intellij.lua.debugger.android.elf.ArmCodeMasker
import com.fixcode.intellij.lua.debugger.android.elf.ArrayByteSource
import com.fixcode.intellij.lua.debugger.android.elf.AttemptStatus
import com.fixcode.intellij.lua.debugger.android.elf.CodeArch
import com.fixcode.intellij.lua.debugger.android.elf.DetectedLuaVersion
import com.fixcode.intellij.lua.debugger.android.elf.DeviceLibrary
import com.fixcode.intellij.lua.debugger.android.elf.ElfFile
import com.fixcode.intellij.lua.debugger.android.elf.FunctionSignature
import com.fixcode.intellij.lua.debugger.android.elf.LuaApiSymbols
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolLocator
import com.fixcode.intellij.lua.debugger.android.elf.LuaSymbolResolver
import com.fixcode.intellij.lua.debugger.android.elf.MiniDebugInfo
import com.fixcode.intellij.lua.debugger.android.elf.SignatureMatch
import com.fixcode.intellij.lua.debugger.android.elf.SignatureScanner
import com.fixcode.intellij.lua.debugger.android.elf.SymbolDirIndex
import com.fixcode.intellij.lua.debugger.android.elf.SymbolSearchOptions
import com.fixcode.intellij.lua.debugger.android.elf.SymbolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 没有未裁剪 so 时的逐级兜底：符号目录按 Build ID、设备 so 的 .dynsym / MiniDebugInfo、特征码。
 * 真实场景用 make_lua546_fixtures.sh 生成的夹具：真 Lua 5.4.6 核心两次构建（布局不同、目标裁剪过）。
 */
class SymbolFallbackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("elf/$name")!!.use { it.readBytes() }

    private fun fixture(name: String): File {
        val bytes = if (name.endsWith(".xz")) XZInputStream(resource(name).inputStream()).use { it.readBytes() } else resource(name)
        return File(tmp.root, name.removeSuffix(".xz")).apply { writeBytes(bytes) }
    }

    private fun expected(name: String): Map<String, Long> = String(resource(name)).lines().filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter("=0x").toLong(16) }

    private fun localDevice(file: File, buildId: String? = ElfFile.open(file).use { it.buildId() }) =
        DeviceLibrary(buildId, file.path) { ElfFile.open(file) }

    // ---------------- 第 1 级：符号目录 ----------------

    @Test
    fun symbolDirMatchesByBuildId() {
        val ref = fixture("lua546_arm64_ref.so.xz")
        val dir = tmp.newFolder("archive", "build-1234", "Binaries", "Android")
        ref.copyTo(File(dir, "libUnreal-arm64.so"))
        fixture("fake_lua54_arm64.so").copyTo(File(dir, "libUnreal.so")) // 另一次构建，不该选中
        val stripped = fixture("lua546_arm64_minidebug.so.xz") // 和 ref 同一个 Build ID，但裁剪过
        val cache = tmp.newFolder("cache")
        val locator = LuaSymbolLocator(cache)
        val result = locator.locate(SymbolSearchOptions(symbolDirs = listOf(tmp.root.resolve("archive"))), localDevice(stripped))
        assertTrue(result.attempts.toString(), result.ok)
        assertEquals(SymbolSource.SYMBOL_DIR, result.table!!.source)
        assertEquals(File(dir, "libUnreal-arm64.so").path, result.table!!.sourceDetail)
        // 扫描结果写进缓存，第二次只 stat
        assertTrue(File(cache, "symbol-dir-index.properties").isFile)
        val index = SymbolDirIndex(cache)
        assertEquals(1, index.find(listOf(tmp.root.resolve("archive")), result.table!!.buildId).size)
    }

    @Test
    fun buildIdDirectoryLayout() {
        val ref = fixture("lua546_arm64_ref.so.xz")
        val id = ElfFile.open(ref).use { it.buildId()!! }
        val root = tmp.newFolder("symbols")
        File(root, ".build-id/${id.take(2)}").mkdirs()
        ref.copyTo(File(root, ".build-id/${id.take(2)}/${id.drop(2)}.debug"))
        val found = SymbolDirIndex(null).find(listOf(root), id, maxDepth = 0) // 深度 0：证明不是靠扫描找到的
        assertEquals(1, found.size)
        assertTrue(found[0].path.endsWith(".debug"))
    }

    @Test
    fun configuredFileWithWrongBuildIdFallsThrough() {
        val ref = fixture("lua546_arm64_ref.so.xz")
        val other = fixture("fake_lua54_arm64.so")
        val dir = tmp.newFolder("dir")
        ref.copyTo(File(dir, "x.so"))
        val result = LuaSymbolLocator(null).locate(
            SymbolSearchOptions(configuredFile = other, symbolDirs = listOf(dir)), localDevice(ref))
        assertTrue(result.ok)
        assertEquals(SymbolSource.LOCAL_FILE, result.attempts[0].source)
        assertTrue(result.attempts[0].detail, result.attempts[0].detail.contains("不是同一次构建"))
        assertEquals(SymbolSource.SYMBOL_DIR, result.table!!.source)
    }

    // ---------------- 第 2 级：设备 so 自带的符号 ----------------

    @Test
    fun dynsymOfStrippedSo() {
        val stripped = fixture("fake_lua54_arm64_stripped.so")
        ElfFile.open(stripped).use { assertNull(it.section(".symtab")) }
        val result = LuaSymbolLocator(null).locate(SymbolSearchOptions(), localDevice(stripped))
        assertTrue(result.attempts.toString(), result.ok)
        val table = result.table!!
        assertEquals(SymbolSource.DEVICE_SYMBOLS, table.source)
        assertEquals(0x2ed4L, table.functions["lua_sethook"])
        assertTrue(table.sourceDetail, table.sourceDetail.contains(".dynsym"))
    }

    @Test
    fun miniDebugInfo() {
        val md = fixture("lua546_arm64_minidebug.so.xz")
        val ref = fixture("lua546_arm64_ref.so.xz")
        ElfFile.open(md).use { elf ->
            assertNull(elf.section(".symtab"))
            MiniDebugInfo.open(elf)!!.use { mini -> assertNotNull(mini.section(".symtab")) }
        }
        val result = LuaSymbolLocator(null).locate(SymbolSearchOptions(), localDevice(md))
        assertTrue(result.attempts.toString(), result.ok)
        val table = result.table!!
        assertEquals(SymbolSource.DEVICE_SYMBOLS, table.source)
        assertTrue(table.sourceDetail, table.sourceDetail.contains("MiniDebugInfo"))
        assertEquals(DetectedLuaVersion.LUA_54, table.luaVersion)
        val refTable = LuaSymbolResolver(null).resolve(ref)
        for (name in LuaApiSymbols.required54) assertEquals(name, refTable.functions[name], table.functions[name])
    }

    // ---------------- 第 3 级：特征码（真 Lua 5.4.6） ----------------

    private fun signatureRoundTrip(abi: String, thumb: Boolean) {
        val ref = fixture("lua546_${abi}_ref.so.xz")
        val target = fixture("lua546_${abi}_target.so.xz")
        val expect = expected("lua546_${abi}_target.expected")
        val cache = tmp.newFolder("cache-$abi")
        val logs = ArrayList<String>()
        val result = LuaSymbolLocator(cache).locate(SymbolSearchOptions(referenceSo = ref), localDevice(target), { logs += it })
        assertTrue(result.failureMessage(), result.ok)
        val table = result.table!!
        assertEquals(SymbolSource.SIGNATURE, table.source)
        assertTrue(table.usable)
        assertEquals(ElfFile.open(target).use { it.buildId() }, table.buildId)
        // 找到的每个都必须是真实地址：错一个就是附加后崩游戏
        for ((name, value) in table.functions) {
            val want = expect.getValue(name).let { if (thumb) it or 1L else it }
            assertEquals(name, want, value)
        }
        assertTrue(LuaApiSymbols.required54.all { it in table.functions })
        assertEquals(AttemptStatus.SKIPPED, result.attempts.first { it.source == SymbolSource.SYMBOL_DIR }.status)

        // 第二次：命中「目标 + 参考」缓存，不再打开设备 so
        val again = LuaSymbolLocator(cache).locate(SymbolSearchOptions(referenceSo = ref),
            DeviceLibrary(table.buildId, "x") { error("命中缓存时不该再读设备 so") })
        assertTrue(again.ok)
        assertEquals(table.functions, again.table!!.functions)
    }

    @Test
    fun signatureArm64() = signatureRoundTrip("arm64", thumb = false)

    @Test
    fun signatureThumb() = signatureRoundTrip("armv7", thumb = true)

    @Test
    fun wrongAbiReference() {
        val result = LuaSymbolLocator(null).locate(
            SymbolSearchOptions(referenceSo = fixture("lua546_armv7_ref.so.xz")), localDevice(fixture("lua546_arm64_target.so.xz")))
        assertFalse(result.ok)
        assertTrue(result.attempts.last().detail, result.attempts.last().detail.contains("ABI"))
    }

    @Test
    fun failureListsMissingAndSuggestions() {
        val target = fixture("lua546_arm64_target.so.xz")
        val result = LuaSymbolLocator(null).locate(SymbolSearchOptions(), localDevice(target))
        assertFalse(result.ok)
        val msg = result.failureMessage()
        assertTrue(msg, msg.contains("lua_sethook"))
        assertTrue(msg, msg.contains("未裁剪") && msg.contains("符号目录") && msg.contains("参考 so"))
        assertEquals(listOf(SymbolSource.SYMBOL_DIR, SymbolSource.DEVICE_SYMBOLS, SymbolSource.SIGNATURE), result.attempts.map { it.source })
    }

    // ---------------- 特征码细节（构造的指令序列） ----------------

    private fun words(vararg w: Long): ByteArray {
        val b = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        w.forEach { b.putInt(it.toInt()) }
        return b.array()
    }

    @Test
    fun arm64AddressFieldsAreWildcarded() {
        val code = words(
            0xA9BF7BFD, // stp x29, x30, [sp, #-16]!
            0x90000088, // adrp x8, #0x10000
            0x91004108, // add x8, x8, #0x10（:lo12:，基址是 ADRP 的目标）
            0x94000010, // bl #0x40
            0x54000080, // b.eq #0x10
            0xB4000060, // cbz x0, #0xc
            0x37080041, // tbnz w1, #1, #8
            0x58000082, // ldr x2, #0x10（literal）
            0x91001000, // add x0, x0, #4（基址不是 ADRP 的目标，保留）
            0xD65F03C0, // ret
        )
        val masked = ArmCodeMasker.mask(code, CodeArch.ARM64)
        val other = words(
            0xA9BF7BFD, 0x90FFFFE8, 0x913FF108, 0x97FFFFF0, 0x54FFFF00, 0xB4FFFF00, 0x370FFFE1, 0x58FFFFE2,
            0x91001000, 0xD65F03C0,
        )
        assertTrue(masked.matches(other, 0, other.size))
        // 寄存器、操作码、普通立即数不能被通配
        val diffReg = other.copyOf().also { it[4 * 1] = (it[4 * 1].toInt() xor 1).toByte() } // adrp x9
        assertFalse(masked.matches(diffReg, 0, diffReg.size))
        val diffImm = other.copyOf().also { it[4 * 8 + 1] = (it[4 * 8 + 1].toInt() xor 0x04).toByte() } // add #4 → 别的数
        assertFalse(masked.matches(diffImm, 0, diffImm.size))
    }

    @Test
    fun thumbBranchesMovwAndLiteralPool() {
        fun hw(vararg h: Int): ByteArray {
            val b = ByteBuffer.allocate(h.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            h.forEach { b.putShort(it.toShort()) }
            return b.array()
        }
        // push {r7, lr}; ldr r0, [pc, #4]; bl xxx; movw r1, #0x1234; b.n; nop; .word 0x00012345（文字池）
        val code = hw(0xB580, 0x4803, 0xF000, 0xF810, 0xF241, 0x2134, 0xE7FE, 0xBF00, 0x2345, 0x0001)
        val masked = ArmCodeMasker.mask(code, CodeArch.THUMB, address = 0x1000)
        val other = hw(0xB580, 0x4802, 0xF7FF, 0xFFEE, 0xF245, 0x6178, 0xE7F0, 0xBF00, 0xABCD, 0x7777)
        assertTrue(masked.matches(other, 0, other.size))
        val diffRt = other.copyOf().also { it[3] = 0x49 } // ldr r1 而不是 r0
        assertFalse(masked.matches(diffRt, 0, diffRt.size))
    }

    private fun region(bytes: ByteArray, vaddr: Long = 0x10000) =
        SignatureScanner.CodeRegion(vaddr, 0, bytes.size.toLong()) { off, len -> bytes.copyOfRange(off.toInt(), minOf(bytes.size, off.toInt() + len)) }

    /** 一段「随机」但确定的 arm64 指令：mov w<n>, #imm，各不相同。 */
    private fun body(seed: Int, n: Int) = words(*LongArray(n) { 0x52800000L or (((seed * 131 + it * 7) and 0xffff).toLong() shl 5) or (it % 29).toLong() })

    @Test
    fun lengthensUntilUnique() {
        val common = body(1, 16) // 64 字节一样
        val tailA = body(2, 16)
        val tailB = body(3, 16)
        val fnA = common + tailA
        val decoy = common + tailB
        val filler = ByteArray(4096)
        val target = filler + decoy + filler + fnA + filler
        val sig = FunctionSignature("lua_a", 0x100, ArmCodeMasker.mask(fnA, CodeArch.ARM64))
        val r = SignatureScanner(CodeArch.ARM64).search(listOf(sig), listOf(region(target)))
        val m = r.getValue("lua_a") as SignatureMatch.Found
        assertEquals(0x10000L + filler.size * 2 + decoy.size, m.value)
        assertEquals(128, m.length)
    }

    @Test
    fun ambiguousAndMissingAreRejected() {
        val fn = body(5, 20)
        val target = ByteArray(1024) + fn + ByteArray(512) + fn + ByteArray(512)
        val gone = body(9, 20)
        val r = SignatureScanner(CodeArch.ARM64).search(
            listOf(
                FunctionSignature("lua_twice", 0x100, ArmCodeMasker.mask(fn, CodeArch.ARM64)),
                FunctionSignature("lua_gone", 0x200, ArmCodeMasker.mask(gone, CodeArch.ARM64)),
            ),
            listOf(region(target)),
        )
        val twice = r.getValue("lua_twice") as SignatureMatch.NotFound
        assertTrue(twice.reason, twice.reason.contains("不唯一"))
        val missing = r.getValue("lua_gone") as SignatureMatch.NotFound
        assertTrue(missing.reason, missing.reason.contains("没有匹配"))
    }

    @Test
    fun scansAcrossChunkBoundaries() {
        val fn = body(11, 24)
        // 放在 8 MB 分块边界上，前后都是空
        val target = ByteArray(8 * 1024 * 1024 - 40) + fn + ByteArray(1000)
        val r = SignatureScanner(CodeArch.ARM64).search(listOf(FunctionSignature("lua_x", 0, ArmCodeMasker.mask(fn, CodeArch.ARM64))), listOf(region(target, 0)))
        assertEquals(8L * 1024 * 1024 - 40, (r.getValue("lua_x") as SignatureMatch.Found).value)
    }

    // ---------------- 设备文件读取 ----------------

    @Test
    fun apkStoredEntry() {
        val so = fixture("fake_lua54_arm64.so").readBytes()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex").apply { method = ZipEntry.DEFLATED })
            zip.write(ByteArray(5000) { it.toByte() })
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libUnreal.so").apply {
                method = ZipEntry.STORED
                size = so.size.toLong()
                crc = CRC32().apply { update(so) }.value
            })
            zip.write(so)
        }
        val apk = out.toByteArray()
        val off = ApkZip.findStoredEntry(ArrayByteSource(apk), apk.size.toLong(), "lib/arm64-v8a/libUnreal.so")!!
        assertEquals(so.toList(), apk.copyOfRange(off.toInt(), off.toInt() + so.size).toList())
        assertNull(ApkZip.findStoredEntry(ArrayByteSource(apk), apk.size.toLong(), "lib/armeabi-v7a/libUnreal.so"))
        assertNull(ApkZip.findStoredEntry(ArrayByteSource(apk), apk.size.toLong(), "classes.dex")) // 压缩过的不算
    }

    @Test
    fun remoteElfThroughAdbDd() {
        val so = fixture("fake_lua54_arm64_stripped.so").readBytes()
        // 模拟 so 放在 APK 里 8 KB 处
        val file = ByteArray(8192) + so
        val calls = ArrayList<String>()
        val exec = object : AdbExecutor {
            override fun run(args: List<String>, timeoutMs: Long) = AdbResult(0, "", "")
            override fun runBinary(args: List<String>, timeoutMs: Long): ByteArray {
                val cmd = args[3]
                calls += cmd
                val m = Regex("bs=(\\d+) skip=(\\d+) count=(\\d+)").find(cmd)!!.groupValues
                val start = m[1].toInt() * m[2].toInt()
                val end = minOf(file.size, start + m[1].toInt() * m[3].toInt())
                return if (start >= file.size) ByteArray(0) else file.copyOfRange(start, end)
            }
        }
        val source = RemoteFileSource(AdbClient(exec), "SER", "com.studio.game", "/data/app/x/base.apk", 8192)
        val result = ElfFile.open(source, "remote").use { elf ->
            LuaSymbolLocator(null).locate(SymbolSearchOptions(), DeviceLibrary(elf.buildId(), "remote") { elf })
        }
        assertTrue(result.ok)
        assertEquals(0x2ed4L, result.table!!.functions["lua_sethook"])
        assertTrue(calls.all { it.startsWith("run-as com.studio.game dd if=/data/app/x/base.apk bs=4096 skip=") })
        assertTrue("按块缓存，不该每次读都调 adb：${calls.size}", calls.size <= 4)
    }
}
