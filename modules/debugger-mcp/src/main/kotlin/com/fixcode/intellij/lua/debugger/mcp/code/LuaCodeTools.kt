package com.fixcode.intellij.lua.debugger.mcp.code

import com.fixcode.intellij.lua.debugger.mcp.json.JsonArgs
import com.fixcode.intellij.lua.debugger.mcp.json.ToolArgumentException
import com.fixcode.intellij.lua.debugger.mcp.json.ToolException
import com.fixcode.intellij.lua.debugger.mcp.protocol.McpTool
import com.fixcode.intellij.lua.debugger.mcp.protocol.PermissionLevel
import com.fixcode.intellij.lua.debugger.mcp.protocol.ToolGuide
import com.fixcode.intellij.lua.debugger.mcp.tools.ResultJson
import com.fixcode.intellij.lua.debugger.mcp.tools.ResultJson.obj
import com.fixcode.intellij.lua.debugger.mcp.tools.schema
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `lua_code`：查找 Lua 代码（替代 UEToVS 的 search_lua / search_lua_files / get_lua_outline）。
 * 不是调试工具，所以不叫 `lua_debug_*`；规范见 AGENTS.md「MCP 工具规范」。
 *
 * - `search`：按名字找声明（模糊：前缀、子串、CamelHumps；'M:Fire'、'UI.MainPanel.Show' 限定容器）
 * - `files`：按文件名或 require 路径找 `.lua` 文件
 * - `outline`：一个文件按源码顺序的声明
 * - `references`：一个符号在哪些地方被用到（emmylua_ls 的 textDocument/references）
 *
 * 语义数据全部来自 emmylua_ls（documentSymbol、workspace/symbol、references）；这里只做参数校验、
 * 匹配排序、结果整形和按文件版本缓存的符号表。[backends] 每次调用时取，焦点项目排第一。
 */
class LuaCodeTools(
    private val backends: () -> List<LuaCodeBackend>,
    private val timeouts: Timeouts = Timeouts(),
) {
    class Timeouts(
        /** 单个 LSP 请求等多久。 */
        val requestMillis: Long = 15_000,
        /** search 扫项目文件的总时长上限；超过时返回已有的结果并说明。 */
        val scanMillis: Long = 45_000,
        /** 同时发给服务器的 documentSymbol 请求数。 */
        val parallel: Int = 24,
        /**
         * emmylua_ls 还在启动或建索引时最多等多久再返回。原来马上返回「稍后重试」，
         * Agent 拿到不全的结果常常就当成「没有」（用户 2026-11-20 报：第一次 search 因为还有 854 个文件没索引返回空）。
         */
        val indexWaitMillis: Long = 60_000,
        /** 等索引时隔多久再问一次服务器。 */
        val indexPollMillis: Long = 1_000,
        /**
         * 连续这么多次再问、没索引的文件一个都没少，就认定服务器不收它们（被 .emmyrc.json 排除、不在它的工作区里），
         * 不再等：用户 2026-11-20 报每次 search 都带「还有 854 个文件没索引完」，其实那批文件 emmylua_ls 永远不给结果。
         */
        val stallPolls: Int = 3,
        /** 认定不收的文件多久后再问一次（文件版本变了也会马上再问）。 */
        val excludedRecheckMillis: Long = 10 * 60_000,
    )

    fun all(): List<McpTool> = listOf(tool())

    private val actions = arrayOf("search", "files", "outline", "references")

    private fun tool() = McpTool(
        name = NAME,
        title = "Find Lua code",
        titleZh = "Lua 代码查找",
        description = ToolGuide(
            what = "Finds Lua code via the IDE's EmmyLua language server (UnLua / slua / plain Lua). search: declarations by " +
                "name (functions, methods M:Foo, classes, module tables, fields, file-level variables); files: .lua files by name " +
                "or require path; outline: one file's declarations in source order; references: where a symbol is used.",
            useWhen = "you have a Lua name or part of one (function, method, class, GM/debug command, UI handler, name from a " +
                "Lua error or log) and need file:line: search query='OBC' (CamelHumps -> OnButtonClicked), 'M:Fire', " +
                "'UI.MainPanel.Show'. A require path or script name: files ('UI.MainPanel', 'MainPan'). Before reading a big " +
                "script: outline, then read only the lines you need. Who calls it: references.",
            insteadOf = "grep/glob over script folders: one indexed lookup, declarations only, knows require paths and ':' methods.",
            notFor = "C++ / Blueprint (UE or VS tools); runtime values (lua_debug_*). It waits up to a minute for indexing; on " +
                "engine_unavailable grep instead.",
            results = "search: name, kind, container, file, line/col (1-based), sig, module, local, lib; files: name, path, " +
                "module; outline: file, module, items; references: target, items {file, line, col, text}.",
        ).render(),
        inputSchema = schema {
            string("action", "What to do: search / files / outline / references.", required = true, enum = actions.toList())
            string("query", "search: name or fragment, 'Container.Name' / 'Container:Name' restricts the container. " +
                "files: file name, fragment or module path. references: symbol name (alone, or with file+line to pick the spot).")
            string("path", "outline: Lua file (absolute, relative to the project or the script root) or module name 'UI.MainPanel'.")
            string("file", "references: Lua file of the symbol (same forms as path).")
            integer("line", "references: 1-based line of the symbol in file.", minimum = 1)
            integer("col", "references: 1-based column on that line (default: where query appears on the line).", minimum = 1)
            string("match", "search: fuzzy (default) or exact (case-sensitive name).", enum = listOf("fuzzy", "exact"))
            stringArray("kinds", "search/outline: filter, any of function (incl. methods), method, class, table (incl. classes), " +
                "field, variable.", 6)
            string("container", "search: owning table/class or module, e.g. 'M' or 'UI.MainPanel' (suffix match).")
            boolean("include_locals", "search: include 'local' declarations (default true).")
            string("scope", "search/files: project (default for search) = project scripts; all = also libraries (UE declarations, " +
                "extra libraries, emmylua std).", enum = listOf("project", "all"))
            boolean("include_declaration", "references: list the declaration too (default true).")
            string("project", "Open IDE project name when several are open (default: the focused one).")
            integer("limit", "Max items (search/files 30, references 50, outline 100; max 200).", minimum = 1, maximum = 200)
            integer("offset", "Items to skip (use nextOffset from the previous call).", minimum = 0)
        },
        level = PermissionLevel.READ,
        hiddenArgs = setOf(PROJECT_PATH),
    ) { call ->
        val a = call.args
        when (a.choice("action", null, *actions)) {
            "search" -> search(a)
            "files" -> files(a)
            "outline" -> outline(a)
            "references" -> references(a)
            else -> throw ToolArgumentException("缺少参数 'action'（search / files / outline / references）")
        }
    }

    // ======================================================================= search

    private fun search(a: JsonArgs): Map<String, Any?> {
        val query = a.requireString("query").also { if (it.length > 256) throw ToolArgumentException("'query' 超过 256 个字符") }
        val exact = a.choice("match", "fuzzy", "fuzzy", "exact") == "exact"
        val kinds = kinds(a)
        val locals = a.bool("include_locals", true)
        val all = a.choice("scope", "project", "project", "all") == "all"
        val (limit, offset) = paging(a, 30)
        val (qualifier, name) = LuaSymbols.splitQuery(query)
        if (name.isEmpty()) throw ToolArgumentException("'query' 里没有名字")
        val container = a.string("container")?.trim()?.takeIf { it.isNotEmpty() } ?: qualifier

        val b = pickBackend(a, null)
        requireServer(b)
        val index = projectIndex(b)
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()
        val matcher = FuzzyMatcher(name)
        fun consider(ctx: FileCtx, d: LuaDecl) {
            if (kinds != null && d.kind !in kinds) return
            if (!locals && d.isLocal) return
            if (!all && ctx.file.library) return
            if (!LuaSymbols.containerMatches(d, ctx.module, container, exact)) return
            val score = if (exact) {
                if (d.name != name) return
                0
            } else {
                matcher.match(d.name) ?: return
            }
            if (!seen.add("${norm(ctx.file.path)}:${d.line}:${d.name}")) return
            val penalty = (if (d.isLocal) 50 else 0) + d.kind.rank * 10 + (if (ctx.file.library) 1000 else 0)
            hits += Hit(ctx, d, score - penalty)
        }
        for ((ctx, decls) in index.files) for (d in decls) consider(ctx, d)
        // workspace/symbol 补上 documentSymbol 不列的类型和全局（含库目录里的，项目范围时滤掉）
        val ws = runCatching { await(b.workspaceSymbols(name), timeouts.requestMillis, "workspace/symbol") }.getOrNull().orEmpty()
        val known = index.byPath
        for (h in ws) {
            val ctx = known[norm(h.path)] ?: FileCtx(LuaFile(h.path, library = true), LuaSymbols.moduleName(h.path, index.bases))
            consider(ctx, LuaSymbols.fromWorkspace(h))
        }

        hits.sortWith(
            compareByDescending<Hit> { it.score }.thenBy { it.decl.kind.rank }
                .thenBy { it.ctx.file.path.lowercase() }.thenBy { it.decl.line }
        )
        val slice = hits.drop(offset).take(limit)
        val m = page(slice, offset, hits.size > offset + slice.size, hits.size, ResultJson.BUDGET) { declJson(it.decl, it.ctx, withFile = true) }
        projectNote(b, m)
        index.note(hits.isEmpty())?.let { m["note"] = it }
        if (hits.isEmpty() && m["note"] == null) {
            m["note"] = "没有找到名字匹配 '$query' 的 Lua 声明" + (if (!all) "（只查了项目脚本，库里的用 scope=all）" else "") +
                "。emmylua_ls 不列出 `M.x = 1` 这类在表外赋值的字段：找这种用 grep。"
        }
        return m
    }

    private class Hit(val ctx: FileCtx, val decl: LuaDecl, val score: Int)

    // ======================================================================= files

    private fun files(a: JsonArgs): Map<String, Any?> {
        val query = a.requireString("query").also { if (it.length > 512) throw ToolArgumentException("'query' 超过 512 个字符") }
        val all = a.choice("scope", "all", "project", "all") == "all"
        val (limit, offset) = paging(a, 30)
        val b = pickBackend(a, null)
        val bases = b.baseDirs()
        val files = b.luaFiles().filter { all || !it.library }.map { it to LuaSymbols.moduleName(it.path, bases) }
        val found = LuaSymbols.searchFiles(query, files)
        val slice = found.drop(offset).take(limit)
        val m = page(slice, offset, found.size > offset + slice.size, found.size, ResultJson.BUDGET) { (f, module) ->
            obj("name" to LuaSymbols.fileName(f.path), "path" to f.path, "module" to module, "lib" to f.library)
        }
        projectNote(b, m)
        if (files.isEmpty()) m["note"] = "项目里没有 .lua 文件（IDE 还在建索引时也会这样，稍后重试）。"
        else if (found.isEmpty()) m["note"] = "没有文件名或模块路径匹配 '$query' 的 .lua 文件。"
        return m
    }

    // ======================================================================= outline

    private fun outline(a: JsonArgs): Map<String, Any?> {
        val path = a.requireString("path")
        val kinds = kinds(a)
        val (limit, offset) = paging(a, 100)
        val b = pickBackend(a, path)
        val bases = b.baseDirs()
        val file = resolve(b, path, bases)
        requireServer(b)
        val module = LuaSymbols.moduleName(file.path, bases)
        val symbols = untilIndexed(System.nanoTime(), fileWaitMillis(b, file)) { await(b.documentSymbols(file), timeouts.requestMillis, "textDocument/documentSymbol") }
            ?: throw indexing("emmylua_ls 还没索引到 ${file.path}（刚启动或文件在工作区和库目录之外）。几秒后重试；一直这样就检查 .emmyrc.json 的 workspace 设置。")
        val decls = LuaSymbols.declarations(symbols, b.readText(file)).filter { kinds == null || it.kind in kinds }
        val slice = decls.drop(offset).take(limit)
        val m = obj("file" to file.path, "module" to module, "lib" to file.library)
        m.putAll(page(slice, offset, decls.size > offset + slice.size, decls.size, ResultJson.MAX_BUDGET) { declJson(it, null, withFile = false) })
        projectNote(b, m)
        return m
    }

    // ======================================================================= references

    private fun references(a: JsonArgs): Map<String, Any?> {
        val includeDecl = a.bool("include_declaration", true)
        val (limit, offset) = paging(a, 50)
        val query = a.string("query")?.trim()?.takeIf { it.isNotEmpty() }
        val fileArg = a.string("file")?.trim()?.takeIf { it.isNotEmpty() }
        val line = a.int("line", 1, Int.MAX_VALUE)
        val col = a.int("col", 1, Int.MAX_VALUE)
        if (fileArg == null && query == null) throw ToolArgumentException("references 要给 'query'（符号名），或 'file' + 'line' + 'col' / 'query'")
        if (fileArg != null && line == null) throw ToolArgumentException("给了 'file' 还要给 'line'（1 起）")
        if ((line != null || col != null) && fileArg == null) throw ToolArgumentException("'line' / 'col' 要和 'file' 一起给")

        val b = pickBackend(a, fileArg)
        requireServer(b)
        val bases = b.baseDirs()
        var note: String? = null
        val target: Triple<LuaFile, Int, Int>
        val targetName: String?
        if (fileArg != null) {
            val file = resolve(b, fileArg, bases)
            val text = b.readText(file)
            val lineText = text?.lines()?.getOrNull(line!! - 1)
            val c0 = when {
                col != null -> col - 1
                query != null -> {
                    val name = LuaSymbols.splitQuery(query).second
                    lineText?.let { identifierColumn(it, name) }
                        ?: throw ToolArgumentException("第 $line 行里没有 '$name'；给出 'col'（1 起）或改正 'line'")
                }
                else -> throw ToolArgumentException("给了 'file' + 'line' 还要给 'col' 或 'query'")
            }
            target = Triple(file, line!! - 1, c0)
            targetName = query ?: lineText?.let { identifierAt(it, c0) }
        } else {
            // 只给名字：先按名字找声明（区分大小写的完全匹配优先），用最好的那个
            val (qualifier, name) = LuaSymbols.splitQuery(query!!)
            val index = projectIndex(b)
            val candidates = ArrayList<Pair<FileCtx, LuaDecl>>()
            for ((ctx, decls) in index.files) for (d in decls) {
                if (d.name == name && LuaSymbols.containerMatches(d, ctx.module, qualifier, exact = false)) candidates += ctx to d
            }
            if (candidates.isEmpty()) {
                val ws = runCatching { await(b.workspaceSymbols(name), timeouts.requestMillis, "workspace/symbol") }.getOrNull().orEmpty()
                for (h in ws.filter { it.name.substringAfterLast(':').substringAfterLast('.') == name }) {
                    val ctx = index.byPath[norm(h.path)] ?: FileCtx(LuaFile(h.path, library = true), null)
                    candidates += ctx to LuaSymbols.fromWorkspace(h)
                }
            }
            if (candidates.isEmpty()) {
                throw ToolException("target_not_found", "没有找到名为 '$query' 的 Lua 声明：先 $NAME action=search 找到它，再用 file + line + col 查引用。")
            }
            candidates.sortWith(compareBy<Pair<FileCtx, LuaDecl>> { it.second.isLocal }.thenBy { it.first.file.library }
                .thenBy { it.second.kind.rank }.thenBy { it.first.file.path.lowercase() }.thenBy { it.second.line })
            val (ctx, d) = candidates[0]
            target = Triple(ctx.file, d.line, d.col)
            targetName = d.qualifiedName
            if (candidates.size > 1) {
                note = "'$query' 有 ${candidates.size} 处声明，查的是第一处；别的用 $NAME action=search 找到后给 file + line + col。"
            }
        }

        val locations = await(b.references(target.first, target.second, target.third, includeDecl), timeouts.requestMillis, "textDocument/references")
            .sortedWith(compareBy<CodeLocation> { it.path.lowercase() }.thenBy { it.line }.thenBy { it.col })
        val slice = locations.drop(offset).take(limit)
        // 每个文件读一次，给出那一行的内容，Agent 不用再逐个打开
        val texts = HashMap<String, List<String>?>()
        val m = obj("target" to obj("name" to targetName, "file" to target.first.path, "line" to target.second + 1, "col" to target.third + 1))
        m.putAll(page(slice, offset, locations.size > offset + slice.size, locations.size, ResultJson.BUDGET) { loc ->
            val lines = texts.getOrPut(norm(loc.path)) {
                (b.fileAt(loc.path) ?: LuaFile(loc.path)).let { f -> b.readText(f)?.lines() }
            }
            obj("file" to loc.path, "line" to loc.line + 1, "col" to loc.col + 1,
                "text" to lines?.getOrNull(loc.line)?.trim()?.let { if (it.length > 160) it.take(159) + "…" else it })
        })
        projectNote(b, m)
        if (locations.isEmpty()) note = (note?.let { "$it " } ?: "") + "emmylua_ls 没有找到引用（位置不在名字上时也会这样：检查 line/col）。"
        note?.let { m["note"] = it }
        return m
    }

    private fun identifierColumn(line: String, name: String): Int? {
        var i = line.indexOf(name)
        while (i >= 0) {
            val before = if (i > 0) line[i - 1] else ' '
            val after = if (i + name.length < line.length) line[i + name.length] else ' '
            if (!isIdent(before) && !isIdent(after)) return i
            i = line.indexOf(name, i + 1)
        }
        return null
    }

    private fun identifierAt(line: String, col: Int): String? {
        if (col !in line.indices || !isIdent(line[col])) return null
        var s = col
        var e = col
        while (s > 0 && isIdent(line[s - 1])) s--
        while (e < line.length && isIdent(line[e])) e++
        return line.substring(s, e)
    }

    private fun isIdent(c: Char) = c == '_' || c.isLetterOrDigit()

    // ======================================================================= 项目符号表

    private class FileCtx(val file: LuaFile, val module: String?)

    /** [excludedAt] 不为 0：emmylua_ls 一直不给这个文件结果，那时起认定它不收，到 excludedRecheckMillis 再问。 */
    private class CachedFile(val stamp: Long, val decls: List<LuaDecl>, val excludedAt: Long = 0)

    private inner class ProjectIndex(
        val files: List<Pair<FileCtx, List<LuaDecl>>>,
        val byPath: Map<String, FileCtx>,
        val bases: List<String>,
        val pending: Int,
        val skipped: Int,
        /** emmylua_ls 不收的项目文件（路径），没查它们 */
        val excluded: List<String>,
    ) {
        /** [empty]：这次没有命中。不收的文件只在没命中时提一句，免得每次搜索都带同一句话。 */
        fun note(empty: Boolean): String? = when {
            skipped > 0 -> "扫描超时或请求出错，还有 $skipped 个文件没查到，结果不全：稍后重试（查到的会缓存，第二次快得多）。"
            pending > 0 -> "emmylua_ls 等了 ${timeouts.indexWaitMillis / 1000} 秒还有 $pending 个文件没索引完，结果可能不全：稍后再查一次。"
            empty && excluded.isNotEmpty() -> "有 ${excluded.size} 个项目里的 .lua 文件 emmylua_ls 不给结果（多半被 .emmyrc.json 排除或不在它的工作区里，" +
                "例如 ${LuaSymbols.sampleDirs(excluded, 3).joinToString("、")}），没查这些；要找它们里面的代码用 rg。"
            else -> null
        }
    }

    /** 每个项目一份：路径 → (文件版本, 声明)。文件变了（版本不同）才重新问服务器。 */
    private val caches = ConcurrentHashMap<String, ConcurrentHashMap<String, CachedFile>>()

    /** 项目脚本（不含库目录：UE 声明动辄上万个文件，库里的类型和全局由 workspace/symbol 补）的声明。 */
    private fun projectIndex(b: LuaCodeBackend): ProjectIndex {
        val bases = b.baseDirs()
        val all = b.luaFiles()
        val project = all.filter { !it.library }
        val cache = caches.getOrPut(b.projectName) { ConcurrentHashMap() }
        // 删掉已经不在的文件
        val live = project.mapTo(HashSet()) { norm(it.path) }
        cache.keys.removeIf { it !in live }

        val byPath = HashMap<String, FileCtx>()
        for (f in all) byPath[norm(f.path)] = FileCtx(f, LuaSymbols.moduleName(f.path, bases))
        val result = ArrayList<Pair<FileCtx, List<LuaDecl>>>()
        val todo = ArrayList<FileCtx>()
        val excluded = ArrayList<String>()
        val nowMs = System.currentTimeMillis()
        for (f in project) {
            val ctx = byPath.getValue(norm(f.path))
            val hit = cache[norm(f.path)]
            when {
                hit == null || hit.stamp != f.stamp -> todo += ctx
                hit.excludedAt == 0L -> result += ctx to hit.decls
                nowMs - hit.excludedAt < timeouts.excludedRecheckMillis -> excluded += f.path
                else -> todo += ctx
            }
        }
        var skipped = 0
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeouts.scanMillis)
        var waiting = scan(b, todo, deadline, cache, result) { skipped++ }
        // 服务器还没索引到的文件（documentSymbol 回 null）：等它建完再查，免得把「没索引完」当成「没有」交给 Agent。
        // 一直不少的那批是服务器不收的，等几次没进展就不等了，记下来以后直接跳过
        val indexDeadline = started + TimeUnit.MILLISECONDS.toNanos(timeouts.indexWaitMillis)
        var stalls = 0
        while (waiting.isNotEmpty() && stalls < timeouts.stallPolls && System.nanoTime() < indexDeadline) {
            pause()
            val before = waiting.size
            waiting = scan(b, waiting, indexDeadline, cache, result) { skipped++ }
            // 一个结果都还没有时是服务器整体还在建索引，一直等到上限；有了结果、剩下的又不动，才算「不收」
            stalls = if (waiting.size < before || result.isEmpty()) 0 else stalls + 1
        }
        if (waiting.isNotEmpty() && stalls >= timeouts.stallPolls) {
            val at = System.currentTimeMillis()
            for (ctx in waiting) {
                cache[norm(ctx.file.path)] = CachedFile(ctx.file.stamp, emptyList(), at)
                excluded += ctx.file.path
            }
            waiting = emptyList()
        }
        val pending = waiting.size
        if (project.isNotEmpty() && pending + skipped + excluded.size >= project.size && pending + skipped > 0) {
            throw indexing("emmylua_ls 还在建立索引（等了 ${timeouts.indexWaitMillis / 1000} 秒，${project.size} 个项目脚本都还没有结果），稍后重试。")
        }
        return ProjectIndex(result, byPath, bases, pending, skipped, excluded)
    }

    /** 问一批文件的符号，结果放进 [result]；返回服务器还没索引到的文件。超时的记进 [onSkip]。 */
    private fun scan(b: LuaCodeBackend, todo: List<FileCtx>, deadline: Long, cache: ConcurrentHashMap<String, CachedFile>, result: MutableList<Pair<FileCtx, List<LuaDecl>>>, onSkip: () -> Unit): List<FileCtx> {
        val notYet = ArrayList<FileCtx>()
        for (chunk in todo.chunked(timeouts.parallel.coerceAtLeast(1))) {
            if (System.nanoTime() > deadline) { chunk.forEach { _ -> onSkip() }; continue }
            val futures = chunk.map { it to b.documentSymbols(it.file) }
            for ((ctx, future) in futures) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                val symbols = try {
                    future.get(minOf(left, timeouts.requestMillis), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true); onSkip(); continue
                } catch (e: ExecutionException) {
                    // 请求本身失败不是「没索引到」：不进等待、也不会被记成不收，算进跳过的，note 里会说结果不全
                    onSkip(); continue
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ToolException("cancelled", "调用被客户端取消。")
                }
                if (symbols == null) { notYet += ctx; continue }
                val decls = LuaSymbols.declarations(symbols, b.readText(ctx.file))
                // 服务器给了空表、文件却不空：多半是还没索引到，别缓存
                if (symbols.isNotEmpty() || decls.isNotEmpty()) cache[norm(ctx.file.path)] = CachedFile(ctx.file.stamp, decls)
                result += ctx to decls
            }
        }
        return notYet
    }

    /** 等索引时睡一会儿；客户端取消时中断。 */
    private fun pause() {
        try {
            Thread.sleep(timeouts.indexPollMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ToolException("cancelled", "调用被客户端取消。")
        }
    }

    /** [ask] 回 null（服务器还没索引到）时隔一会儿再问，最多等到 [started] 起 [maxMillis]。 */
    private fun <T> untilIndexed(started: Long, maxMillis: Long, ask: () -> T?): T? {
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(maxMillis)
        while (true) {
            ask()?.let { return it }
            if (System.nanoTime() >= deadline) return null
            pause()
        }
    }

    // ======================================================================= 公共

    private fun kinds(a: JsonArgs): Set<LuaDeclKind>? {
        val list = a.stringList("kinds", 6)
        if (list.isEmpty()) return null
        val set = HashSet<LuaDeclKind>()
        for (k in list) {
            set += LuaSymbols.KIND_GROUPS[k.lowercase()]
                ?: throw ToolArgumentException("'kinds' 里的 '$k' 不认识，可用：${LuaSymbols.KIND_GROUPS.keys.joinToString()}")
        }
        return set
    }

    private fun paging(a: JsonArgs, defaultLimit: Int): Pair<Int, Int> =
        a.int("limit", defaultLimit, 1, 200) to a.int("offset", 0, 0, 1_000_000)

    private fun declJson(d: LuaDecl, ctx: FileCtx?, withFile: Boolean): Map<String, Any?> = obj(
        "name" to d.name,
        "kind" to d.kind.wire,
        "container" to d.container,
        "file" to if (withFile) ctx?.file?.path else null,
        "line" to d.line + 1,
        "col" to d.col + 1,
        "sig" to d.sig,
        "value" to d.value,
        "module" to if (withFile) ctx?.module else null,
        "local" to d.isLocal,
        "lib" to (withFile && ctx?.file?.library == true),
    )

    /** 分页信封：{count, offset, total?, hasMore, nextOffset?, truncated?, items}。[slice] 已按 offset/limit 切好。 */
    private fun <T> page(slice: List<T>, offset: Int, hasMore: Boolean, total: Int?, budget: Int, render: (T) -> Any?): LinkedHashMap<String, Any?> {
        val (items, truncated) = ResultJson.fit(slice, budget, render)
        val more = hasMore || truncated
        val m = LinkedHashMap<String, Any?>()
        m["count"] = items.size
        m["offset"] = offset
        if (total != null) m["total"] = total
        m["hasMore"] = more
        if (more) m["nextOffset"] = offset + items.size
        if (truncated) m["truncated"] = true
        m["items"] = items
        return m
    }

    /**
     * 选项目：给了 `project` 按名字；给了 `projectPath`（IDE 自带工具的写法，见 [McpTool.hiddenArgs]）按路径；
     * 给了绝对路径选包含它的；否则焦点项目（列表第一个）。
     */
    private fun pickBackend(a: JsonArgs, path: String?): LuaCodeBackend {
        val list = backends()
        if (list.isEmpty()) throw ToolException("engine_unavailable", "IDE 里没有打开的项目：请用户在 IDE 里打开 Lua 项目后重试，或改用 grep。")
        val wanted = a.string("project")?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            return list.firstOrNull { it.projectName.equals(wanted, ignoreCase = true) }
                ?: throw ToolArgumentException("没有打开名为 '$wanted' 的项目，打开的是：${list.joinToString { it.projectName }}")
        }
        val dir = a.string(PROJECT_PATH)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        if (dir != null) {
            byDir(list, dir)?.let { return it }
            // 只开了一个项目时 projectPath 对不上也照查：Agent 常填工作区根（项目的上级目录之外），为这个让整次失败不划算
            if (list.size > 1) throw ToolArgumentException(
                "projectPath '$dir' 不属于任何打开的项目；改用 project 传项目名，打开的是：${list.joinToString { it.projectName }}")
        }
        if (path != null && list.size > 1) list.firstOrNull { it.owns(path.trim().trim('"')) }?.let { return it }
        return list[0]
    }

    /**
     * 按 projectPath 选项目：路径在项目里面的优先，项目嵌套时取根最深（最具体）的那个；
     * 否则路径是项目根的上级目录（Agent 常填工作区根，IDE 打开的是它下面的子目录），只有一个项目对得上才算，多个算对不上。
     */
    private fun byDir(list: List<LuaCodeBackend>, dir: String): LuaCodeBackend? {
        val p = normPath(dir) ?: return null
        val bases = list.associateWith { b -> b.baseDirs().mapNotNull { normPath(it) } }
        list.filter { it.owns(dir) }
            .maxByOrNull { b -> bases.getValue(b).filter { p.startsWith(it) }.maxOfOrNull { it.nameCount } ?: -1 }
            ?.let { return it }
        return list.filter { b -> bases.getValue(b).any { it.startsWith(p) } }.singleOrNull()
    }

    private fun normPath(s: String): Path? = runCatching { Path.of(s).toAbsolutePath().normalize() }.getOrNull()

    private fun projectNote(b: LuaCodeBackend, m: MutableMap<String, Any?>) {
        if (backends().size > 1) m["project"] = b.projectName
    }

    private fun requireServer(b: LuaCodeBackend) {
        when (val s = b.serverState()) {
            ServerState.Ready -> {}
            ServerState.Starting -> {
                // 刚打开项目时服务器还在启动：等它起来，不让 Agent 自己隔几秒重试
                val ready = untilIndexed(System.nanoTime(), timeouts.indexWaitMillis) { b.serverState().takeIf { it !is ServerState.Starting } }
                when (ready) {
                    null -> throw indexing("emmylua_ls 等了 ${timeouts.indexWaitMillis / 1000} 秒还在启动，稍后重试。")
                    is ServerState.Unavailable -> throw ToolException("engine_unavailable", ready.message)
                    else -> {}
                }
            }
            is ServerState.Unavailable -> throw ToolException("engine_unavailable", s.message)
        }
    }

    private fun resolve(b: LuaCodeBackend, input: String, bases: List<String>): LuaFile {
        val known = b.luaFiles().map { it to LuaSymbols.moduleName(it.path, bases) }
        return LuaSymbols.resolveFile(input, bases, known, b::fileAt)
            ?: throw ToolException("target_not_found", "找不到 Lua 文件 '$input'（绝对路径、相对项目或脚本根的路径、模块名都试过了）：用 $NAME action=files query=... 先找文件。")
    }

    /**
     * 单个文件等多久：search 已经认定服务器不收的文件不等；项目里已经有文件查到过（服务器在工作）时只等几次，
     * 剩下的情况（刚启动）等满 indexWaitMillis。
     */
    private fun fileWaitMillis(b: LuaCodeBackend, file: LuaFile): Long {
        val cache = caches[b.projectName] ?: return timeouts.indexWaitMillis
        val hit = cache[norm(file.path)]
        if (hit != null && hit.stamp == file.stamp && hit.excludedAt != 0L) return 0
        return if (cache.values.any { it.excludedAt == 0L }) timeouts.stallPolls * timeouts.indexPollMillis else timeouts.indexWaitMillis
    }

    private fun indexing(message: String) = ToolException("indexing", message)

    private fun <T> await(future: CompletableFuture<T>, timeoutMillis: Long, what: String): T {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw ToolException("timeout", "emmylua_ls ${timeoutMillis / 1000} 秒内没有回 $what（可能还在建索引），稍后重试。")
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is ToolException) throw cause
            throw ToolException("engine_unavailable", "emmylua_ls 的 $what 出错：${cause.message ?: cause.javaClass.simpleName}。稍后重试，一直这样就请用户在 IDE 里重启语言服务器。")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ToolException("cancelled", "调用被客户端取消。")
        }
    }

    private fun norm(path: String) = path.replace('\\', '/').lowercase()

    companion object {
        const val NAME = "lua_code"

        /** IDE 自带工具通用的项目参数，我们不写进 schema 但接受（见 [McpTool.hiddenArgs]） */
        const val PROJECT_PATH = "projectPath"
    }
}
