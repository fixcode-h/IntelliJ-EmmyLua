package com.tang.intellij.lua.debugger.android.elf

import java.io.File
import java.util.Properties

/**
 * agent 需要的 Lua 函数清单。名字必须和 native/emmy_debugger/src/api/lua_api_loader.cpp 里
 * SetupLuaAPI 用到的一致（有单测对账）。
 */
object LuaApiSymbols {
    /** 缺了就无法调试：挂钩子、读栈、读局部变量、抓 lua_State 必须的函数。 */
    val critical = listOf(
        "lua_sethook", "lua_gethook", "lua_getstack", "lua_getinfo", "lua_getlocal",
        "lua_gettop", "lua_settop", "lua_type", "lua_tolstring",
    )

    /** SetupLuaAPI 里用 LOAD_LUA_API 加载的函数（PC 上缺一个就整体失败）。 */
    val required = listOf(
        "lua_gettop", "lua_settop", "lua_type", "lua_typename", "lua_tolstring", "lua_toboolean",
        "lua_tothread", "lua_pushnil", "lua_pushnumber", "lua_pushlstring", "lua_pushstring",
        "lua_pushcclosure", "lua_pushboolean", "lua_pushvalue", "lua_getfield", "lua_next",
        "lua_createtable", "lua_setfield", "lua_setmetatable", "lua_getstack", "lua_getinfo",
        "lua_getlocal", "lua_getupvalue", "lua_setupvalue", "lua_sethook", "lua_gethook",
        "lua_gethookmask", "lua_gethookcount", "luaL_loadstring", "luaL_checklstring",
        "luaL_checknumber", "lua_topointer", "lua_getmetatable", "lua_rawget", "lua_rawset",
        "lua_pushlightuserdata", "lua_touserdata", "luaL_newstate", "lua_close", "lua_pushthread",
        "lua_rawseti", "lua_rawgeti",
    )

    /** LOAD_LUA_API_E 和 lua_getfenv：按 Lua 版本可有可无。 */
    val optional = listOf(
        "lua_getfenv", "lua_setfenv", "lua_tointeger", "lua_tonumber", "lua_call", "lua_pcall",
        "lua_remove", "lua_insert", "lua_tointegerx", "lua_tonumberx", "lua_getglobal", "lua_setglobal",
        "lua_callk", "lua_pcallk", "luaL_setfuncs", "lua_absindex", "lua_rawgetp", "lua_rawsetp",
        "lua_newuserdata", "lua_rotate", "lua_newuserdatauv", "luaopen_jit",
    )

    /** agent 用来抓 lua_State 和跟踪 VM 生命周期的 hook 点。 */
    val hookTargets = listOf("lua_pcall", "lua_pcallk", "lua_resume", "lua_newstate", "lua_newthread", "lua_close")

    /**
     * 只用来判断 Lua 版本的内部函数（不传给 agent）。
     * 静态链接 + --gc-sections 时没被游戏用到的 lua_* 会被链接器丢掉，
     * 所以不能像 PC 上那样靠 lua_newuserdatauv 等 API 是否存在来判断版本，改看虚拟机内部一定存在的函数。
     */
    val versionMarkers = listOf("lj_state_new", "luaE_warning", "luaV_div", "luaE_extendCI", "luaV_execute")

    val all: Set<String> = (required + optional + hookTargets).toSet()
    private val wanted: Set<String> = all + versionMarkers
    private val firstBytes: Set<Byte> = wanted.map { it[0].code.toByte() }.toSet()

    /** 按字节快速判断一个符号名是否可能是我们要的（含 C++ 编译时的 _Z 修饰名）。 */
    fun mayMatch(bytes: ByteArray, start: Int, len: Int): Boolean {
        if (len < 4) return false
        val c = bytes[start]
        if (c == '_'.code.toByte() && bytes[start + 1] == 'Z'.code.toByte()) return true
        return c in firstBytes
    }

    /**
     * 把符号名还原成 Lua API 名。UnLua 可以把 Lua 当 C++ 编译（LUA_COMPILE_AS_CPP），
     * 这时 lua_gettop 变成 _Z10lua_gettopP9lua_State，取 _Z 后的长度前缀部分。
     */
    fun canonicalName(symbol: String): String? {
        if (symbol in wanted) return symbol
        if (!symbol.startsWith("_Z")) return null
        var i = 2
        while (i < symbol.length && symbol[i].isDigit()) i++
        if (i == 2) return null
        val len = symbol.substring(2, i).toIntOrNull() ?: return null
        if (i + len > symbol.length) return null
        val base = symbol.substring(i, i + len)
        return base.takeIf { it in wanted }
    }
}

enum class DetectedLuaVersion(val code: String) {
    LUA_51("51"), LUA_52("52"), LUA_53("53"), LUA_54("54"), LUAJIT("jit"), UNKNOWN("");
}

/** 从未裁剪 so 里得到的符号偏移表。偏移即 st_value（armv7 Thumb 函数带最低位 1，agent 原样加基址）。 */
data class LuaSymbolTable(
    val buildId: String,
    val abi: String?,
    val luaVersion: DetectedLuaVersion,
    val functions: Map<String, Long>,
    val missingCritical: List<String>,
    val missingRequired: List<String>,
    val conflicts: Map<String, List<Long>>,
    val sourcePath: String,
) {
    val usable: Boolean get() = missingCritical.isEmpty() && conflicts.isEmpty() && hasCaptureHook
    val hasCaptureHook: Boolean get() = functions.containsKey("lua_pcallk") || functions.containsKey("lua_pcall") ||
        functions.containsKey("lua_resume")

    /** 给用户看的一句话结论。 */
    fun describe(): String = buildString {
        append("Build ID ${buildId.take(12)}…，找到 ${functions.size} 个 Lua 函数")
        if (luaVersion != DetectedLuaVersion.UNKNOWN) append("（Lua ${luaVersion.code}）")
        if (conflicts.isNotEmpty()) append("；同名函数有多份实现：${conflicts.keys.joinToString()}，so 里可能静态链接了两份 Lua")
        if (missingCritical.isNotEmpty()) append("；缺少调试必需的函数：${missingCritical.joinToString()}")
        else if (!hasCaptureHook) append("；找不到 lua_pcall / lua_pcallk / lua_resume，无法抓到 lua_State")
        else if (missingRequired.isNotEmpty()) append("；以下函数被链接器裁掉，相关功能不可用：${missingRequired.joinToString()}")
    }
}

class SymbolResolveException(message: String) : Exception(message)

/**
 * 从本地未裁剪的 libUnreal.so（或任何静态链接了 Lua 的 so）提取 Lua 函数偏移，结果按 Build ID 缓存。
 */
class LuaSymbolResolver(private val cacheDir: File?) {

    fun readBuildId(file: File): String? = ElfFile.open(file).use { it.buildId() }

    fun resolve(file: File): LuaSymbolTable {
        if (!file.isFile) throw SymbolResolveException("找不到文件：${file.path}")
        ElfFile.open(file).use { elf ->
            val buildId = elf.buildId()
                ?: throw SymbolResolveException("${file.name} 没有 GNU Build ID，无法确认和设备上的是同一次构建（链接时需要 --build-id）")
            loadCache(buildId, file)?.let { return it }
            if (elf.section(".symtab") == null) {
                throw SymbolResolveException("${file.name} 已被裁剪（没有 .symtab），请指定打包时保留的未裁剪 so，一般在 Binaries/Android 或 Intermediate/Android 下")
            }
            val found = HashMap<String, MutableSet<Long>>()
            elf.forEachSymbol(LuaApiSymbols::mayMatch) { sym ->
                if (!sym.isDefined || !sym.isFunction || sym.value == 0L) return@forEachSymbol
                val name = LuaApiSymbols.canonicalName(sym.name) ?: return@forEachSymbol
                found.getOrPut(name) { LinkedHashSet() }.add(sym.value)
            }
            val version = detectVersion(found.keys)
            val functions = LinkedHashMap<String, Long>()
            val conflicts = LinkedHashMap<String, List<Long>>()
            for (name in LuaApiSymbols.all.sorted()) {
                val values = found[name] ?: continue
                if (values.size == 1) functions[name] = values.first()
                else conflicts[name] = values.sorted()
            }
            val table = LuaSymbolTable(
                buildId = buildId,
                abi = elf.abi,
                luaVersion = version,
                functions = functions,
                missingCritical = LuaApiSymbols.critical.filter { it !in functions },
                missingRequired = LuaApiSymbols.required.filter { it !in functions },
                conflicts = conflicts,
                sourcePath = file.absolutePath,
            )
            saveCache(table, file)
            return table
        }
    }

    private fun detectVersion(names: Set<String>): DetectedLuaVersion = when {
        "lj_state_new" in names || "luaopen_jit" in names -> DetectedLuaVersion.LUAJIT
        "luaE_warning" in names || "lua_newuserdatauv" in names -> DetectedLuaVersion.LUA_54
        "luaV_div" in names || "lua_rotate" in names -> DetectedLuaVersion.LUA_53
        "luaE_extendCI" in names || "lua_callk" in names || "lua_pcallk" in names -> DetectedLuaVersion.LUA_52
        "luaV_execute" in names -> DetectedLuaVersion.LUA_51
        else -> DetectedLuaVersion.UNKNOWN
    }

    // ---- 缓存：一个 Build ID 一个 properties 文件，记下来源文件的大小和修改时间，变了就重新解析 ----

    private fun cacheFile(buildId: String) = cacheDir?.let { File(it, "lua-symbols-$buildId.properties") }

    private fun loadCache(buildId: String, source: File): LuaSymbolTable? {
        val f = cacheFile(buildId) ?: return null
        if (!f.isFile) return null
        return try {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            if (p.getProperty("format") != CACHE_FORMAT) return null
            if (p.getProperty("source.path") != source.absolutePath ||
                p.getProperty("source.size") != source.length().toString() ||
                p.getProperty("source.mtime") != source.lastModified().toString()
            ) return null
            val functions = LinkedHashMap<String, Long>()
            val conflicts = LinkedHashMap<String, List<Long>>()
            for (key in p.stringPropertyNames()) {
                if (key.startsWith("fn.")) functions[key.removePrefix("fn.")] = p.getProperty(key).removePrefix("0x").toLong(16)
                if (key.startsWith("conflict.")) conflicts[key.removePrefix("conflict.")] =
                    p.getProperty(key).split(',').map { it.removePrefix("0x").toLong(16) }
            }
            LuaSymbolTable(
                buildId = buildId,
                abi = p.getProperty("abi")?.takeIf { it.isNotEmpty() },
                luaVersion = DetectedLuaVersion.entries.firstOrNull { it.name == p.getProperty("lua") } ?: DetectedLuaVersion.UNKNOWN,
                functions = functions.toSortedMap(),
                missingCritical = LuaApiSymbols.critical.filter { it !in functions },
                missingRequired = LuaApiSymbols.required.filter { it !in functions },
                conflicts = conflicts,
                sourcePath = source.absolutePath,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCache(table: LuaSymbolTable, source: File) {
        val f = cacheFile(table.buildId) ?: return
        try {
            f.parentFile?.mkdirs()
            val p = Properties()
            p["format"] = CACHE_FORMAT
            p["abi"] = table.abi ?: ""
            p["lua"] = table.luaVersion.name
            p["source.path"] = source.absolutePath
            p["source.size"] = source.length().toString()
            p["source.mtime"] = source.lastModified().toString()
            table.functions.forEach { (k, v) -> p["fn.$k"] = "0x" + java.lang.Long.toHexString(v) }
            table.conflicts.forEach { (k, v) -> p["conflict.$k"] = v.joinToString(",") { "0x" + java.lang.Long.toHexString(it) } }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { p.store(it, "EmmyLua Android attach symbol cache") }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
            // 缓存失败不影响本次附加
        }
    }

    /**
     * 在项目里找候选的未裁剪 so：UE 打包后保留在 Binaries/Android、Intermediate/Android 下。
     * 有 [expectedBuildId] 时只返回 Build ID 一致的。
     */
    fun findCandidates(roots: List<File>, soName: String = "libUnreal.so", expectedBuildId: String? = null, maxDepth: Int = 8): List<File> {
        val result = ArrayList<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown().maxDepth(maxDepth)
                .onEnter { dir -> dir.name !in SKIP_DIRS }
                .filter { it.isFile && it.name == soName }
                .forEach { result += it }
        }
        val withSymtab = result.filter { f ->
            try {
                ElfFile.open(f).use { elf ->
                    elf.section(".symtab") != null && (expectedBuildId == null || elf.buildId() == expectedBuildId)
                }
            } catch (_: Exception) {
                false
            }
        }
        return withSymtab.sortedByDescending { it.lastModified() }
    }

    companion object {
        private const val CACHE_FORMAT = "1"
        private val SKIP_DIRS = setOf(".git", ".svn", "node_modules", "DerivedDataCache", "Saved", "Content")
    }
}
