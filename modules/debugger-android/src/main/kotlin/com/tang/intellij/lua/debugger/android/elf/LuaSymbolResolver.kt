package com.tang.intellij.lua.debugger.android.elf

import java.io.File
import java.util.Properties

/**
 * agent 需要的 Lua 函数。调试核心（native/emmy_debugger_core，dynamic 模式）的 lua_* 全部经函数指针调用宿主的 Lua，
 * 所以把 so 里所有 lua_* 函数都交给 agent；其中 [requiredFor] 列出的缺一个核心就拒绝附加（有单测和 lua_dyn.c 对账）。
 * luaL_* 核心自带，不需要宿主提供。
 */
object LuaApiSymbols {
    /** 缺了就无法调试：挂钩子、读栈、读局部变量。 */
    val critical = listOf(
        "lua_sethook", "lua_gethook", "lua_getstack", "lua_getinfo", "lua_getlocal",
        "lua_gettop", "lua_settop", "lua_type", "lua_tolstring",
    )

    /** native/emmy_debugger_core/src/lua_dyn.cpp 的 kRequired 里属于 Lua 5.4 API 的部分（其余在 5.4 是宏）。缺一个核心就无法工作。 */
    val required54 = listOf(
        "lua_newstate", "lua_close", "lua_atpanic", "lua_newthread", "lua_status", "lua_resume", "lua_xmove",
        "lua_absindex", "lua_gettop", "lua_settop", "lua_pushvalue", "lua_rotate", "lua_checkstack",
        "lua_type", "lua_typename", "lua_toboolean", "lua_tolstring", "lua_tonumberx", "lua_tointegerx",
        "lua_touserdata", "lua_tothread", "lua_rawlen", "lua_pushnil", "lua_pushnumber", "lua_pushinteger",
        "lua_pushlstring", "lua_pushstring", "lua_pushcclosure", "lua_pushboolean", "lua_pushthread",
        "lua_getfield", "lua_getglobal", "lua_gettable", "lua_rawget", "lua_rawgeti", "lua_createtable",
        "lua_getmetatable", "lua_setfield", "lua_rawset", "lua_rawseti", "lua_setmetatable", "lua_next",
        "lua_settable", "lua_setglobal", "lua_callk", "lua_pcallk", "lua_load", "lua_error", "lua_getstack",
        "lua_getinfo", "lua_getlocal", "lua_getupvalue", "lua_setupvalue", "lua_sethook",
    )

    /** lua_dyn.cpp 的 kOptional：缺了核心换成替代实现，只降级对应功能（如 lua_setlocal 缺了就不能改局部变量）。 */
    val optional54 = listOf(
        "lua_setlocal", "lua_concat", "lua_pushvfstring", "lua_pushfstring", "lua_topointer",
        "lua_pushlightuserdata", "lua_rawequal", "lua_gethook", "lua_gethookmask", "lua_gethookcount",
        "lua_isinteger", "lua_setwarnf",
    )

    /** agent 的调试核心目前一份 so 只对应一个 Lua 大版本，Android 构建用 5.4（UnLua 用的就是 5.4）。 */
    val supportedVersions = setOf(DetectedLuaVersion.LUA_54)

    fun requiredFor(version: DetectedLuaVersion): List<String> = when (version) {
        DetectedLuaVersion.LUA_54 -> required54
        else -> critical
    }

    fun optionalFor(version: DetectedLuaVersion): List<String> = when (version) {
        DetectedLuaVersion.LUA_54 -> optional54
        else -> emptyList()
    }

    /** agent 用来抓 lua_State 和跟踪 VM 生命周期的 hook 点。 */
    val hookTargets = listOf("lua_pcall", "lua_pcallk", "lua_resume", "lua_newstate", "luaL_newstate", "lua_close")

    /**
     * 只用来判断 Lua 版本的内部函数（不传给 agent）。
     * 静态链接 + --gc-sections 时没被游戏用到的 lua_* 会被链接器丢掉，
     * 所以不能只靠 lua_newuserdatauv 等 API 是否存在来判断版本，改看虚拟机内部一定存在的函数。
     */
    val versionMarkers = listOf("lj_state_new", "luaE_warning", "luaV_div", "luaE_extendCI", "luaV_execute")

    private val API_NAME = Regex("^lua_[A-Za-z0-9_]+$")

    /** 是否交给 agent：所有 lua_* 函数，加上 hook 用的 luaL_newstate。 */
    fun isApi(name: String): Boolean = API_NAME.matches(name) || name in hookTargets

    private fun isWanted(name: String): Boolean = isApi(name) || name in versionMarkers || name == "luaopen_jit"

    /** 按字节快速判断一个符号名是否可能是我们要的（含 C++ 编译时的 _Z 修饰名）。 */
    fun mayMatch(bytes: ByteArray, start: Int, len: Int): Boolean {
        if (len < 4) return false
        val c = bytes[start]
        if (c == '_'.code.toByte() && bytes[start + 1] == 'Z'.code.toByte()) return true
        return c == 'l'.code.toByte()
    }

    /**
     * 把符号名还原成 Lua API 名。UnLua 可以把 Lua 当 C++ 编译（LUA_COMPILE_AS_CPP），
     * 这时 lua_gettop 变成 _Z10lua_gettopP9lua_State，取 _Z 后的长度前缀部分。
     */
    fun canonicalName(symbol: String): String? {
        if (isWanted(symbol)) return symbol
        if (!symbol.startsWith("_Z")) return null
        var i = 2
        while (i < symbol.length && symbol[i].isDigit()) i++
        if (i == 2) return null
        val len = symbol.substring(2, i).toIntOrNull() ?: return null
        if (i + len > symbol.length) return null
        val base = symbol.substring(i, i + len)
        return base.takeIf { isWanted(it) }
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
    val versionSupported: Boolean get() = luaVersion in LuaApiSymbols.supportedVersions
    val usable: Boolean get() = missingCritical.isEmpty() && missingRequired.isEmpty() && conflicts.isEmpty() &&
        hasCaptureHook && versionSupported
    /** 被裁掉的可选函数：不影响附加，对应功能降级。 */
    val missingOptional: List<String> get() = LuaApiSymbols.optionalFor(luaVersion).filter { it !in functions }
    val hasCaptureHook: Boolean get() = functions.containsKey("lua_pcallk") || functions.containsKey("lua_pcall") ||
        functions.containsKey("lua_resume")

    /** 给用户看的一句话结论。 */
    fun describe(): String = buildString {
        append("Build ID ${buildId.take(12)}…，找到 ${functions.size} 个 Lua 函数")
        if (luaVersion != DetectedLuaVersion.UNKNOWN) append("（Lua ${luaVersion.code}）")
        if (conflicts.isNotEmpty()) append("；同名函数有多份实现：${conflicts.keys.joinToString()}，so 里可能静态链接了两份 Lua")
        if (!versionSupported) append("；真机附加的调试核心目前只支持 Lua 5.4")
        else if (missingCritical.isNotEmpty()) append("；缺少调试必需的函数：${missingCritical.joinToString()}")
        else if (!hasCaptureHook) append("；找不到 lua_pcall / lua_pcallk / lua_resume，无法抓到 lua_State")
        else if (missingRequired.isNotEmpty()) append("；以下函数被链接器裁掉，调试核心无法工作：${missingRequired.joinToString()}")
        else if (missingOptional.isNotEmpty()) append("；以下函数被链接器裁掉，对应功能降级：${missingOptional.joinToString()}")
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
            for (name in found.keys.filter(LuaApiSymbols::isApi).sorted()) {
                val values = found.getValue(name)
                if (values.size == 1) functions[name] = values.first()
                else conflicts[name] = values.sorted()
            }
            val table = LuaSymbolTable(
                buildId = buildId,
                abi = elf.abi,
                luaVersion = version,
                functions = functions,
                missingCritical = LuaApiSymbols.critical.filter { it !in functions },
                missingRequired = LuaApiSymbols.requiredFor(version).filter { it !in functions },
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
            val version = DetectedLuaVersion.entries.firstOrNull { it.name == p.getProperty("lua") } ?: DetectedLuaVersion.UNKNOWN
            LuaSymbolTable(
                buildId = buildId,
                abi = p.getProperty("abi")?.takeIf { it.isNotEmpty() },
                luaVersion = version,
                functions = functions.toSortedMap(),
                missingCritical = LuaApiSymbols.critical.filter { it !in functions },
                missingRequired = LuaApiSymbols.requiredFor(version).filter { it !in functions },
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
        private const val CACHE_FORMAT = "2"
        private val SKIP_DIRS = setOf(".git", ".svn", "node_modules", "DerivedDataCache", "Saved", "Content")
    }
}
