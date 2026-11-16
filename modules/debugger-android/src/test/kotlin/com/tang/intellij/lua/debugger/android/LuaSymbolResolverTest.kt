package com.tang.intellij.lua.debugger.android

import com.tang.intellij.lua.debugger.android.elf.DetectedLuaVersion
import com.tang.intellij.lua.debugger.android.elf.ElfFile
import com.tang.intellij.lua.debugger.android.elf.ElfPrefix
import com.tang.intellij.lua.debugger.android.elf.LuaApiSymbols
import com.tang.intellij.lua.debugger.android.elf.LuaSymbolResolver
import com.tang.intellij.lua.debugger.android.elf.SymbolResolveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LuaSymbolResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val url = javaClass.classLoader.getResource("elf/$name") ?: error("缺少夹具 $name")
        // 复制出来：资源可能在 jar 里，且缓存要比对文件大小和修改时间
        val f = tmp.newFile(name)
        url.openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun readsArm64BuildIdAndOffsets() {
        val table = LuaSymbolResolver(null).resolve(fixture("fake_lua54_arm64.so"))
        assertEquals("67dce0dff707ac8d48d3ca83d8a7e5ed2ec1a2e1", table.buildId)
        assertEquals("arm64-v8a", table.abi)
        assertEquals(0x2ed4L, table.functions["lua_sethook"])
        assertEquals(DetectedLuaVersion.LUA_54, table.luaVersion)
        assertTrue(table.describe(), table.usable)
        assertTrue(table.missingRequired.isEmpty())
        // luaL_newstate 是 hook 点，要带上；其他 luaL_* 核心自带，不交给 agent
        assertTrue(table.functions.containsKey("luaL_newstate"))
        assertFalse(table.functions.containsKey("luaL_checkinteger"))
        assertFalse(table.functions.containsKey("luaE_warning"))
    }

    @Test
    fun keepsThumbBitOnArmv7() {
        val table = LuaSymbolResolver(null).resolve(fixture("fake_lua54_armv7.so"))
        assertEquals("armeabi-v7a", table.abi)
        assertEquals("0c44cd5b77f5dcd4e373041479526526317204e0", table.buildId)
        // 同一个函数同时出现在 .dynsym 和 .symtab，不能算成冲突
        assertTrue(table.conflicts.isEmpty())
        assertEquals(0x226dL, table.functions["lua_sethook"])
    }

    @Test
    fun hiddenSymbolsOnlyInSymtab() {
        val f = fixture("fake_lua54_x64.so")
        ElfFile.open(f).use { elf ->
            var inDynsym = false
            elf.forEachSymbol({ _, _, _ -> true }) { if (it.table == ".dynsym" && it.name == "lua_sethook") inDynsym = true }
            assertFalse("夹具用 -fvisibility=hidden 编译，lua_sethook 不该导出", inDynsym)
        }
        val table = LuaSymbolResolver(null).resolve(f)
        assertEquals(0x1513L, table.functions["lua_sethook"])
        // 不在 lua.h 里的 lua_* 也会带上：核心按名字只取自己要的，多给无害
        assertTrue(table.functions.containsKey("lua_gettop_wrapper"))
    }

    @Test
    fun demanglesCppBuild() {
        val table = LuaSymbolResolver(null).resolve(fixture("fake_lua54_cpp_x64.so"))
        assertEquals(0x1513L, table.functions["lua_sethook"])
        assertTrue(table.usable)
        assertEquals(DetectedLuaVersion.LUA_54, table.luaVersion)
    }

    @Test
    fun canonicalNames() {
        assertEquals("lua_gettop", LuaApiSymbols.canonicalName("_Z10lua_gettopP9lua_State"))
        assertEquals("lua_gettop_wrapper", LuaApiSymbols.canonicalName("_Z18lua_gettop_wrapperP9lua_State"))
        assertNull(LuaApiSymbols.canonicalName("_Z17luaL_checkintegerP9lua_Statei"))
        assertNull(LuaApiSymbols.canonicalName("luaH_get"))
        assertNull(LuaApiSymbols.canonicalName("_Z999lua"))
        assertEquals("lua_pcallk", LuaApiSymbols.canonicalName("lua_pcallk"))
    }

    @Test
    fun strippedSoIsRejectedButBuildIdMatches() {
        val stripped = fixture("fake_lua54_x64_stripped.so")
        val resolver = LuaSymbolResolver(null)
        assertEquals(resolver.readBuildId(fixture("fake_lua54_x64.so")), resolver.readBuildId(stripped))
        try {
            resolver.resolve(stripped)
            fail()
        } catch (e: SymbolResolveException) {
            assertTrue(e.message!!.contains("已被裁剪"))
        }
    }

    @Test
    fun buildIdFromPrefixMatchesFullParse() {
        for (name in listOf("fake_lua54_arm64.so", "fake_lua54_armv7.so", "fake_lua54_x64_stripped.so")) {
            val f = fixture(name)
            val prefix = f.readBytes().copyOf(8192)
            assertEquals(name, LuaSymbolResolver(null).readBuildId(f), ElfPrefix.buildId(prefix))
        }
        assertNull(ElfPrefix.buildId(ByteArray(100)))
    }

    @Test
    fun cachesByBuildId() {
        val cache = tmp.newFolder("cache")
        val f = fixture("fake_lua54_arm64.so")
        val first = LuaSymbolResolver(cache).resolve(f)
        val cached = File(cache, "lua-symbols-${first.buildId}.properties")
        assertTrue(cached.isFile)
        val second = LuaSymbolResolver(cache).resolve(f)
        assertEquals(first.functions, second.functions)
        assertEquals(first.luaVersion, second.luaVersion)
        assertEquals(first.abi, second.abi)
    }

    @Test
    fun findsCandidatesByBuildId() {
        val root = tmp.newFolder("proj")
        val dir = File(root, "Binaries/Android").apply { mkdirs() }
        fixture("fake_lua54_arm64.so").copyTo(File(dir, "libUnreal.so"))
        val other = File(root, "Intermediate/Android/arm64").apply { mkdirs() }
        fixture("fake_lua54_x64_stripped.so").copyTo(File(other, "libUnreal.so"))
        val resolver = LuaSymbolResolver(null)
        val all = resolver.findCandidates(listOf(root))
        assertEquals(1, all.size) // 裁剪过的不算候选
        assertEquals(1, resolver.findCandidates(listOf(root), expectedBuildId = "67dce0dff707ac8d48d3ca83d8a7e5ed2ec1a2e1").size)
        assertEquals(0, resolver.findCandidates(listOf(root), expectedBuildId = "deadbeef").size)
    }

    /** Kotlin 侧必需清单必须和调试核心 lua_dyn.cpp 的 kRequired（5.4 部分）、kOptional 一致，否则附加到一半才发现缺函数。 */
    @Test
    fun requiredListMatchesCore() {
        val root = System.getProperty("emmylua.repoRoot")?.let(::File) ?: File("../..").canonicalFile
        val dyn = File(root, "native/emmy_debugger_core/src/lua_dyn.cpp")
        val luaH = File(root, "native/third-party/lua-5.4.6/src/lua.h")
        assumeTrue("找不到 $dyn，跳过", dyn.isFile && luaH.isFile)
        val api = Regex("LUA_API[^;]*\\((lua_[A-Za-z0-9_]+)\\)\\s*\\(").findAll(luaH.readText()).map { it.groupValues[1] }.toSet()
        val text = dyn.readText()
        val block = text.substring(text.indexOf("kRequired[] = {"), text.indexOf("};", text.indexOf("kRequired[] = {")))
        val required = Regex("\"(lua_\\w+)\"").findAll(block).map { it.groupValues[1] }.filter { it in api }.toSet()
        assertEquals(required, LuaApiSymbols.required54.toSet())
        val optBlock = text.substring(text.indexOf("kOptional[] = {"), text.indexOf("};", text.indexOf("kOptional[] = {")))
        val optional = Regex("EMMY_OPT\\((lua_\\w+)").findAll(optBlock).map { it.groupValues[1] }.toSet()
        assertEquals(optional, LuaApiSymbols.optional54.toSet())
        assertTrue(LuaApiSymbols.critical.all { it in api })
    }

    @Test
    fun unsupportedVersionAndMissingFunctionsAreNotUsable() {
        val base = LuaSymbolResolver(null).resolve(fixture("fake_lua54_arm64.so"))
        val jit = base.copy(luaVersion = DetectedLuaVersion.LUAJIT)
        assertFalse(jit.usable)
        assertTrue(jit.describe().contains("5.4"))
        val trimmed = base.copy(missingRequired = listOf("lua_getlocal"))
        assertFalse(trimmed.usable)
        assertTrue(trimmed.describe().contains("lua_getlocal"))
    }

    /** 可选函数被裁掉不影响附加，只在说明里提示降级（核心会换成替代实现）。 */
    @Test
    fun missingOptionalFunctionsOnlyDegrade() {
        val base = LuaSymbolResolver(null).resolve(fixture("fake_lua54_arm64.so"))
        assertTrue(base.missingOptional.isEmpty())
        val trimmed = base.copy(functions = base.functions - "lua_setlocal" - "lua_concat")
        assertTrue(trimmed.usable)
        assertEquals(listOf("lua_setlocal", "lua_concat"), trimmed.missingOptional)
        assertTrue(trimmed.describe().contains("降级"))
    }
}
