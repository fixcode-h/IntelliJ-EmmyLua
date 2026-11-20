package com.fixcode.intellij.lua.debugger.mcp.code

/** 给 Agent 看的声明种类（照 UEToVS 的 Lua 索引）。 */
enum class LuaDeclKind(val rank: Int) {
    CLASS(0), TABLE(1), METHOD(2), FUNCTION(2), FIELD(3), VARIABLE(4);

    val wire: String get() = name.lowercase()
}

/**
 * 文件里的一个声明。位置 0 起；[col] 指向名字本身（`function M:Foo` 指向 `Foo`），拿去查引用也对。
 * [sig] 是函数的参数表或 `---@class` 的基类、`---@field` 的类型；[value] 是变量的初值（emmylua_ls 的 detail）。
 */
data class LuaDecl(
    val name: String,
    val kind: LuaDeclKind,
    val container: String? = null,
    val line: Int,
    val col: Int,
    val sig: String? = null,
    val value: String? = null,
    val isLocal: Boolean = false,
) {
    /** `M:Foo` / `UI.MainPanel.Show` 这样的全名。 */
    val qualifiedName: String
        get() = if (container == null) name else container + (if (kind == LuaDeclKind.METHOD) ":" else ".") + name
}

/**
 * emmylua_ls 的结果整形成 [LuaDecl]，以及模块名、文件查找这类纯路径逻辑。不依赖 IDE，便于单测。
 */
object LuaSymbols {
    /** 种类过滤参数的写法（照 UEToVS 的 `kinds`）：function 含方法，table 含类。 */
    val KIND_GROUPS: Map<String, Set<LuaDeclKind>> = linkedMapOf(
        "function" to setOf(LuaDeclKind.FUNCTION, LuaDeclKind.METHOD),
        "method" to setOf(LuaDeclKind.METHOD),
        "class" to setOf(LuaDeclKind.CLASS),
        "table" to setOf(LuaDeclKind.TABLE, LuaDeclKind.CLASS),
        "field" to setOf(LuaDeclKind.FIELD),
        "variable" to setOf(LuaDeclKind.VARIABLE),
    )

    private const val MAX_DETAIL = 80

    // ------------------------------------------------------------------ 声明

    /**
     * 一个文件的声明，按源码顺序：emmylua_ls 的 documentSymbol（函数、方法、表、文件级变量、表构造里的字段），
     * 再加上它不列出的 `---@class` / `---@enum` / `---@field` 注解（只看注释行，不做语义分析）。
     * [text] 用来判断 `local`、把列号对准名字；为 null 时这两项从简。
     */
    fun declarations(symbols: List<DocSymbol>, text: String?): List<LuaDecl> {
        val lines = text?.lines()
        val out = ArrayList<LuaDecl>()
        for (s in symbols) visit(s, null, lines, out)
        if (lines != null) out += annotations(lines)
        // 注解和服务器给的可能重名同行（emmylua_ls 以后也许会列出 ---@class），去重
        val seen = HashSet<String>()
        return out.filter { seen.add("${it.line}:${it.col}:${it.kind.wire}:${it.name}") }
            .sortedWith(compareBy<LuaDecl> { it.line }.thenBy { it.col })
    }

    private fun visit(s: DocSymbol, parent: String?, lines: List<String>?, out: MutableList<LuaDecl>) {
        var name = s.name
        var container = parent
        val sep = name.lastIndexOfAny(charArrayOf(':', '.'))
        val colon = sep > 0 && name[sep] == ':'
        if (sep > 0 && sep < name.length - 1) {
            val owner = name.substring(0, sep).replace(':', '.')
            container = if (parent != null) "$parent.$owner" else owner
            name = name.substring(sep + 1)
        }
        if (name.isEmpty()) return
        val kind = when (s.kind) {
            SK_CLASS, SK_ENUM, SK_INTERFACE, SK_STRUCT -> LuaDeclKind.CLASS
            SK_MODULE, SK_NAMESPACE, SK_PACKAGE, SK_OBJECT -> LuaDeclKind.TABLE
            SK_METHOD -> LuaDeclKind.METHOD
            SK_FUNCTION, SK_CONSTRUCTOR -> if (colon) LuaDeclKind.METHOD else LuaDeclKind.FUNCTION
            SK_PROPERTY, SK_FIELD, SK_KEY, SK_ENUM_MEMBER -> LuaDeclKind.FIELD
            else -> if (container != null) LuaDeclKind.FIELD else LuaDeclKind.VARIABLE
        }
        val lineText = lines?.getOrNull(s.line)
        val col = lineText?.let { nameColumn(it, name, s.col) } ?: s.col
        val isLocal = parent == null && container == null && lineText != null && LOCAL.containsMatchIn(lineText)
        val detail = s.detail?.trim()?.takeIf { it.isNotEmpty() }?.let(::clip)
        val function = kind == LuaDeclKind.FUNCTION || kind == LuaDeclKind.METHOD
        out += LuaDecl(
            name = name,
            kind = kind,
            container = container,
            line = s.line,
            col = col,
            sig = if (function) detail else null,
            value = if (!function && kind != LuaDeclKind.CLASS && kind != LuaDeclKind.TABLE) detail else null,
            isLocal = isLocal,
        )
        // 函数的子项是参数和函数体里的局部，不算文件的声明；表和类的子项是字段
        if (kind == LuaDeclKind.CLASS || kind == LuaDeclKind.TABLE) {
            val full = if (container != null) "$container.$name" else name
            for (c in s.children) visit(c, full, lines, out)
        }
    }

    /** 名字在这一行里从 [from] 往后的位置；找不到时 null（就用服务器给的列）。 */
    private fun nameColumn(line: String, name: String, from: Int): Int? {
        var i = line.indexOf(name, from.coerceIn(0, line.length))
        while (i >= 0) {
            val before = if (i > 0) line[i - 1] else ' '
            val after = if (i + name.length < line.length) line[i + name.length] else ' '
            if (!isIdent(before) && !isIdent(after)) return i
            i = line.indexOf(name, i + 1)
        }
        return null
    }

    private fun isIdent(c: Char) = c == '_' || c.isLetterOrDigit()

    private val LOCAL = Regex("^\\s*local\\b")
    private val CLASS = Regex("^\\s*---\\s*@(class|enum)\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w.]*)(.*)$")
    private val FIELD = Regex("^\\s*---\\s*@field\\s+(?:(?:public|private|protected|package)\\s+)?([A-Za-z_]\\w*)\\??\\s+(.+)$")

    /** `---@class Name : Base`、`---@enum Name`、`---@field [访问级别] name type`；字段挂在最近的类下。 */
    private fun annotations(lines: List<String>): List<LuaDecl> {
        val out = ArrayList<LuaDecl>()
        var currentClass: String? = null
        for ((i, line) in lines.withIndex()) {
            if (!line.contains("@")) continue
            val c = CLASS.find(line)
            if (c != null) {
                val name = c.groupValues[2]
                currentClass = name
                val rest = c.groupValues[3].substringBefore('#').trim()
                val base = rest.removePrefix(":").trim().takeIf { rest.startsWith(":") && it.isNotEmpty() }
                out += LuaDecl(name, LuaDeclKind.CLASS, null, i, line.indexOf(name, line.indexOf('@')),
                    sig = base?.let { clip(": $it") })
                continue
            }
            val f = FIELD.find(line) ?: continue
            val name = f.groupValues[1]
            val type = f.groupValues[2].substringBefore('#').trim().takeIf { it.isNotEmpty() }
            out += LuaDecl(name, LuaDeclKind.FIELD, currentClass, i, line.indexOf(name, line.indexOf("@field") + 6),
                sig = type?.let(::clip))
        }
        return out
    }

    private fun clip(s: String): String = if (s.length <= MAX_DETAIL) s else s.take(MAX_DETAIL - 1) + "…"

    /** workspace/symbol 的一项换成声明（只有全局和类型，没有 local）。 */
    fun fromWorkspace(h: WorkspaceSymbolHit): LuaDecl {
        val d = declarations(listOf(DocSymbol(h.name, h.kind, null, h.line, h.col)), null).first()
        val container = d.container ?: h.container?.takeIf { it.isNotBlank() }
        return d.copy(container = container)
    }

    // ------------------------------------------------------------------ 查询

    /** `a.b:c` / `a.b.c` 拆成 (容器, 名字)。 */
    /** 一批文件最常见的几个所在目录（按文件数），提示里举例用。 */
    fun sampleDirs(paths: List<String>, n: Int): List<String> =
        paths.groupingBy { it.replace('\\', '/').substringBeforeLast('/', "") }.eachCount()
            .entries.sortedByDescending { it.value }.take(n).map { it.key.ifEmpty { "/" } }

    fun splitQuery(query: String): Pair<String?, String> {
        val q = query.trim()
        val cut = q.lastIndexOfAny(charArrayOf('.', ':'))
        if (cut <= 0 || cut == q.length - 1) return null to q.trim('.', ':')
        return q.substring(0, cut) to q.substring(cut + 1)
    }

    /**
     * 容器是否符合：和声明的容器相同或是它的后缀（'MainPanel' 配 'UI.MainPanel'），模糊时子串也算；
     * 也拿文件的模块名比（UnLua 的 `local M = ...; return M`：'UI.MainPanel.Show' 要找到 `M.Show`）。
     */
    fun containerMatches(decl: LuaDecl, module: String?, wanted: String?, exact: Boolean): Boolean {
        if (wanted.isNullOrEmpty()) return true
        val w = wanted.replace(':', '.')
        fun test(actual: String?): Boolean {
            if (actual.isNullOrEmpty()) return false
            if (actual.equals(w, ignoreCase = true)) return true
            if (actual.endsWith(".$w", ignoreCase = true)) return true
            return !exact && actual.contains(w, ignoreCase = true)
        }
        if (test(decl.container)) return true
        // 文件返回的表（M）在别处按模块名引用：模块名本身、或模块名 + 容器
        if (module != null && decl.container != null && !decl.container.contains('.')) {
            if (test(module)) return true
            if (test(module + "." + decl.container)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ 文件

    /**
     * 文件 → require 用的模块名：路径里有 `Content/Script`（UnLua 的脚本根）就从它后面算，
     * 否则相对 [bases] 里最长的那个根。不是 `.lua`、不在任何根下、目录名带点（换成模块名会歧义）时 null。
     */
    fun moduleName(path: String, bases: List<String>): String? {
        val norm = path.replace('\\', '/')
        if (!norm.endsWith(".lua", ignoreCase = true)) return null
        val segments = norm.split('/')
        for (i in segments.size - 3 downTo 0) {
            if (segments[i].equals("Content", true) && segments[i + 1].equals("Script", true)) {
                return join(segments.subList(i + 2, segments.size))
            }
        }
        val base = bases.map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotEmpty() && norm.startsWith("$it/", ignoreCase = true) }
            .maxByOrNull { it.length } ?: return null
        return join(norm.substring(base.length + 1).split('/'))
    }

    private fun join(rel: List<String>): String? {
        if (rel.isEmpty()) return null
        val last = rel.last().let { it.substring(0, it.length - ".lua".length) }
        val parts = rel.dropLast(1) + last
        if (parts.any { it.isEmpty() || it.contains('.') }) return null
        return parts.joinToString(".")
    }

    fun fileName(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    /**
     * 按文件名或模块路径找文件（照 UEToVS）：'MainPanel'、'MainPan'、'UI.MainPanel'、'UI/MainPanel.lua'。
     * 带点或斜杠时先按模块名比（完全相同 > 后缀 > 子串），再按文件名模糊匹配且目录部分要出现在模块名里。
     */
    fun searchFiles(query: String, files: List<Pair<LuaFile, String?>>): List<Pair<LuaFile, String?>> {
        var q = query.trim().replace('\\', '/')
        if (q.endsWith(".lua", ignoreCase = true)) q = q.dropLast(4)
        if (q.isEmpty()) return emptyList()
        val dotted = '.' in q || '/' in q
        val moduleQuery = q.replace('/', '.').trim('.')
        val matcher = FuzzyMatcher(if (dotted) moduleQuery.substringAfterLast('.') else q)
        val folder = if (dotted && '.' in moduleQuery) moduleQuery.substringBeforeLast('.') else null
        val scored = ArrayList<Triple<LuaFile, String?, Int>>()
        for ((f, module) in files) {
            val base = fileName(f.path).let { if (it.endsWith(".lua", true)) it.dropLast(4) else it }
            val mod = module ?: base
            if (dotted) {
                val s = when {
                    mod.equals(moduleQuery, true) -> 200000
                    mod.endsWith(".$moduleQuery", true) -> 150000
                    mod.contains(moduleQuery, true) -> 90000
                    else -> 0
                }
                if (s > 0) { scored += Triple(f, module, s - if (f.library) 1000 else 0); continue }
            }
            val m = matcher.match(base) ?: continue
            if (folder != null && !mod.contains(folder, true) && !f.path.replace('\\', '/').contains(folder.replace('.', '/'), true)) continue
            scored += Triple(f, module, m - if (f.library) 1000 else 0)
        }
        return scored.sortedWith(
            compareByDescending<Triple<LuaFile, String?, Int>> { it.third }
                .thenBy { it.first.path.length }
                .thenBy { it.first.path.lowercase() }
        ).map { it.first to it.second }
    }

    /**
     * 解析 `path` 参数：绝对路径；相对 [bases]（项目根、脚本根、库目录）的路径，可省略 `.lua`；模块名（'UI.MainPanel'）。
     * [fileAt] 按绝对路径取文件，[known] 是已知的 Lua 文件和它们的模块名（模块名匹配用）。
     */
    fun resolveFile(
        input: String,
        bases: List<String>,
        known: List<Pair<LuaFile, String?>>,
        fileAt: (String) -> LuaFile?,
    ): LuaFile? {
        val q = input.trim().trim('"', '\'').trim()
        if (q.isEmpty()) return null
        val norm = q.replace('\\', '/')
        val absolute = norm.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(norm)
        val candidates = ArrayList<String>()
        if (absolute) {
            candidates += norm
            if (!norm.endsWith(".lua", true)) candidates += "$norm.lua"
        } else {
            for (b in bases) {
                val base = b.replace('\\', '/').trimEnd('/')
                if (base.isEmpty()) continue
                candidates += normalize("$base/$norm")
                if (!norm.endsWith(".lua", true)) candidates += normalize("$base/$norm.lua")
            }
        }
        for (c in candidates) {
            known.firstOrNull { it.first.path.replace('\\', '/').equals(c, ignoreCase = true) }?.let { return it.first }
        }
        for (c in candidates) fileAt(c)?.let { return it }
        if (absolute) return null
        val module = (if (norm.endsWith(".lua", true)) norm.dropLast(4) else norm).replace('/', '.')
        return known.firstOrNull { it.second.equals(module, ignoreCase = true) }?.first
    }

    /** 去掉 `.` 和 `..` 段（不碰文件系统）。 */
    private fun normalize(path: String): String {
        val drive = Regex("^[A-Za-z]:").find(path)?.value ?: ""
        val rest = path.substring(drive.length)
        val parts = ArrayList<String>()
        for (p in rest.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        return drive + "/" + parts.joinToString("/")
    }

    // LSP SymbolKind
    const val SK_MODULE = 2
    const val SK_NAMESPACE = 3
    const val SK_PACKAGE = 4
    const val SK_CLASS = 5
    const val SK_METHOD = 6
    const val SK_PROPERTY = 7
    const val SK_FIELD = 8
    const val SK_CONSTRUCTOR = 9
    const val SK_ENUM = 10
    const val SK_INTERFACE = 11
    const val SK_FUNCTION = 12
    const val SK_VARIABLE = 13
    const val SK_OBJECT = 19
    const val SK_KEY = 20
    const val SK_ENUM_MEMBER = 22
    const val SK_STRUCT = 23
}
