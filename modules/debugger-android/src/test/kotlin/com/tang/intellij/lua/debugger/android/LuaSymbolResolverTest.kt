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
        assertEquals("60e566a71d04c16832f66552a95c98d475075427", table.buildId)
        assertEquals("arm64-v8a", table.abi)
        assertEquals(0x25acL, table.functions["lua_sethook"])
        assertEquals(DetectedLuaVersion.LUA_54, table.luaVersion)
        assertTrue(table.usable)
        // 夹具故意没有 luaL_newstate，模拟被 --gc-sections 裁掉
        assertEquals(listOf("luaL_newstate"), table.missingRequired)
        assertTrue(table.describe().contains("luaL_newstate"))
    }

    @Test
    fun keepsThumbBitOnArmv7() {
        val table = LuaSymbolResolver(null).resolve(fixture("fake_lua54_armv7.so"))
        assertEquals("armeabi-v7a", table.abi)
        assertEquals("a83800963c5fea7c44796ba2409ede4eefa1fc95", table.buildId)
        // 同一个函数同时出现在 .dynsym 和 .symtab，不能算成冲突
        assertTrue(table.conflicts.isEmpty())
        assertEquals(0x1d21L, table.functions["lua_sethook"])
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
        assertEquals(0x12f1L, table.functions["lua_sethook"])
        assertFalse(table.functions.containsKey("lua_gettop_wrapper"))
    }

    @Test
    fun demanglesCppBuild() {
        val table = LuaSymbolResolver(null).resolve(fixture("fake_lua54_cpp_x64.so"))
        assertEquals(0x12f1L, table.functions["lua_sethook"])
        assertTrue(table.usable)
        assertEquals(DetectedLuaVersion.LUA_54, table.luaVersion)
    }

    @Test
    fun canonicalNames() {
        assertEquals("lua_gettop", LuaApiSymbols.canonicalName("_Z10lua_gettopP9lua_State"))
        assertNull(LuaApiSymbols.canonicalName("_Z18lua_gettop_wrapperP9lua_State"))
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
        assertEquals(1, resolver.findCandidates(listOf(root), expectedBuildId = "60e566a71d04c16832f66552a95c98d475075427").size)
        assertEquals(0, resolver.findCandidates(listOf(root), expectedBuildId = "deadbeef").size)
    }

    /** Kotlin 侧清单必须和原生 lua_api_loader.cpp 的 SetupLuaAPI 一致，否则 agent 会缺函数。 */
    @Test
    fun symbolListMatchesNativeLoader() {
        val root = System.getProperty("emmylua.repoRoot")?.let(::File) ?: File("../..").canonicalFile
        val loader = File(root, "native/emmy_debugger/src/api/lua_api_loader.cpp")
        assumeTrue("找不到 $loader，跳过", loader.isFile)
        val text = loader.readText()
        val setup = text.substring(text.indexOf("SetupLuaAPI()"))
        val required = Regex("LOAD_LUA_API\\((\\w+)\\)").findAll(setup).map { it.groupValues[1] }.toSet()
        val optional = Regex("LOAD_LUA_API_E\\((\\w+)\\)").findAll(setup).map { it.groupValues[1] }.toSet() +
            Regex("LoadAPI\\(\"(\\w+)\"\\)").findAll(setup).map { it.groupValues[1] }.toSet()
        assertEquals(required, LuaApiSymbols.required.toSet())
        val missing = optional - LuaApiSymbols.optional.toSet()
        assertTrue("Kotlin 清单缺少：$missing", missing.isEmpty())
    }
}
