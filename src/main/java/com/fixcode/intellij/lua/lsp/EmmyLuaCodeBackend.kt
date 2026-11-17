package com.fixcode.intellij.lua.lsp

import com.fixcode.intellij.lua.debugger.mcp.code.CodeLocation
import com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeBackend
import com.fixcode.intellij.lua.debugger.mcp.code.LuaFile
import com.fixcode.intellij.lua.debugger.mcp.code.ServerState
import com.fixcode.intellij.lua.debugger.mcp.code.WorkspaceSymbolHit
import com.fixcode.intellij.lua.debugger.mcp.json.ToolException
import com.fixcode.intellij.lua.project.LuaProjectSettings
import com.fixcode.intellij.lua.project.UnrealIntelliSenseRoots
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * `lua_code` 工具在一个项目上的适配：语义查询都转给 emmylua_ls（经 LSP4IJ 拿到的 LanguageServer），
 * 文件列表用 IDE 的文件名索引（项目内容 + 「外部库」里的 Lua 库目录）。这里不做任何语义分析。
 */
class EmmyLuaCodeBackend(private val project: Project) : LuaCodeBackend {
    override val projectName: String get() = project.name

    override fun serverState(): ServerState {
        if (project.isDisposed) return ServerState.Unavailable("项目已关闭。")
        val manager = LanguageServerManager.getInstance(project)
        val status = runCatching { manager.getServerStatus(EmmyLuaServer.ID) }.getOrNull()
        if (status == ServerStatus.started) return ServerState.Ready
        if (EmmyLuaServerLocator.locate() == null) {
            return ServerState.Unavailable(
                "找不到 emmylua_ls：请用户在「设置 → 语言和框架 → EmmyLua」里填写 emmylua_ls 的路径或把它加进 PATH；在那之前用 grep 找 Lua 代码。"
            )
        }
        when (status) {
            ServerStatus.starting, ServerStatus.installing, ServerStatus.checking_installed, ServerStatus.installed -> {}
            // 还没启动过（LSP4IJ 只在打开 Lua 文件时启动）或已停止：顺手拉起来，Agent 几秒后重试即可
            else -> runCatching { manager.start(EmmyLuaServer.ID) }
        }
        return ServerState.Starting
    }

    // ------------------------------------------------------------------ 文件

    override fun luaFiles(): List<LuaFile> {
        if (project.isDisposed) return emptyList()
        val libRoots = libraryRoots()
        return ReadAction.nonBlocking<List<LuaFile>> {
            val files: Collection<VirtualFile> = if (!DumbService.isDumb(project)) {
                FilenameIndex.getAllFilesByExt(project, LUA_EXT, GlobalSearchScope.allScope(project))
            } else {
                walkLuaFiles(libRoots)
            }
            files.filter { it.isValid && !it.isDirectory }.map { toLuaFile(it, libRoots) }
        }.executeSynchronously()
    }

    /** IDE 还在建索引（dumb mode）时文件名索引用不了：直接走项目内容和库目录。 */
    private fun walkLuaFiles(libRoots: List<Path>): Collection<VirtualFile> {
        val out = LinkedHashSet<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory && isLua(vf)) out += vf
            true
        }
        val fs = LocalFileSystem.getInstance()
        for (root in libRoots) {
            val dir = fs.findFileByNioFile(root) ?: continue
            VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && isLua(file)) out += file
                    return true
                }
            })
        }
        return out
    }

    private fun toLuaFile(vf: VirtualFile, libRoots: List<Path>): LuaFile {
        val doc = FileDocumentManager.getInstance().getCachedDocument(vf)
        // 文件和没保存的编辑都算版本：任意一个变了就重新问服务器
        val stamp = vf.modificationStamp * 31 + (doc?.modificationStamp ?: 0)
        val library = ProjectFileIndex.getInstance(project).isInLibrary(vf) || under(vf.toNioPathOrNull(), libRoots)
        return LuaFile(FileUtil.toSystemDependentName(vf.path), library, stamp)
    }

    override fun baseDirs(): List<String> {
        val out = LinkedHashSet<String>()
        val root = EmmyLuaWorkspaceConfig.projectRoot(project)
        root?.let { r ->
            UnrealIntelliSenseRoots.findUnrealProjectRoot(r)?.resolve("Content")?.resolve("Script")
                ?.takeIf { Files.isDirectory(it) }?.let { out += it.toString() }
            out += r.toString()
        }
        runCatching {
            ReadAction.compute<List<String>, RuntimeException> {
                ProjectRootManager.getInstance(project).contentRoots.map { FileUtil.toSystemDependentName(it.path) }
            }
        }.getOrNull()?.let { out += it }
        libraryRoots().forEach { out += it.toString() }
        return out.toList()
    }

    override fun fileAt(path: String): LuaFile? {
        val vf = findFile(path) ?: return null
        if (vf.isDirectory || !isLua(vf)) return null
        val libRoots = libraryRoots()
        return ReadAction.compute<LuaFile, RuntimeException> { toLuaFile(vf, libRoots) }
    }

    override fun owns(path: String): Boolean {
        val p = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() ?: return false
        return baseDirs().any { runCatching { p.startsWith(Path.of(it)) }.getOrDefault(false) }
    }

    override fun readText(file: LuaFile): String? {
        val vf = findFile(file.path)
        if (vf == null) {
            // 工作区外、VFS 没见过的文件（emmylua_ls 自带的 std 声明）：直接读盘
            return runCatching { Files.readString(Path.of(file.path)) }.getOrNull()
        }
        return runCatching {
            ReadAction.compute<String, RuntimeException> {
                FileDocumentManager.getInstance().getCachedDocument(vf)?.text ?: VfsUtilCore.loadText(vf)
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ LSP

    override fun documentSymbols(file: LuaFile): CompletableFuture<List<DocSymbol>?> = withServer { item ->
        val uri = uriOf(item, file) ?: return@withServer CompletableFuture.completedFuture(null)
        item.server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).thenApply { list ->
            list?.mapNotNull { e ->
                when {
                    e == null -> null
                    e.isRight -> convert(e.right)
                    else -> e.left?.let(::convert)
                }
            }
        }
    }

    override fun workspaceSymbols(query: String): CompletableFuture<List<WorkspaceSymbolHit>> = withServer { item ->
        item.server.workspaceService.symbol(WorkspaceSymbolParams(query)).thenApply { either ->
            when {
                either == null -> emptyList()
                either.isLeft -> either.left.orEmpty().mapNotNull { s ->
                    val loc = s.location ?: return@mapNotNull null
                    val path = pathOf(loc.uri) ?: return@mapNotNull null
                    WorkspaceSymbolHit(s.name, s.kind?.value ?: 0, s.containerName, path, loc.range.start.line, loc.range.start.character)
                }
                else -> either.right.orEmpty().mapNotNull { s ->
                    val loc = s.location ?: return@mapNotNull null
                    val (uri, line, col) = if (loc.isLeft) {
                        Triple(loc.left.uri, loc.left.range.start.line, loc.left.range.start.character)
                    } else {
                        Triple(loc.right.uri, 0, 0)
                    }
                    val path = pathOf(uri) ?: return@mapNotNull null
                    WorkspaceSymbolHit(s.name, s.kind?.value ?: 0, s.containerName, path, line, col)
                }
            }
        }
    }

    override fun references(file: LuaFile, line: Int, col: Int, includeDeclaration: Boolean): CompletableFuture<List<CodeLocation>> =
        withServer { item ->
            val uri = uriOf(item, file) ?: return@withServer CompletableFuture.completedFuture(emptyList())
            val params = ReferenceParams(TextDocumentIdentifier(uri), Position(line, col), ReferenceContext(includeDeclaration))
            item.server.textDocumentService.references(params).thenApply { list ->
                list.orEmpty().mapNotNull { loc: Location? ->
                    val l = loc ?: return@mapNotNull null
                    val path = pathOf(l.uri) ?: return@mapNotNull null
                    CodeLocation(path, l.range.start.line, l.range.start.character)
                }
            }
        }

    @Volatile
    private var server: LanguageServerItem? = null

    /** 取已经启动的 emmylua_ls；缓存起来，search 一次要发几千个请求。 */
    private fun <T> withServer(block: (LanguageServerItem) -> CompletableFuture<T>): CompletableFuture<T> {
        val cached = server
        if (cached != null && runCatching { cached.serverWrapper.serverStatus }.getOrNull() == ServerStatus.started) {
            return block(cached)
        }
        return LanguageServerManager.getInstance(project).getLanguageServer(EmmyLuaServer.ID).thenCompose { item ->
            if (item == null) {
                CompletableFuture.failedFuture(
                    ToolException("engine_unavailable", "emmylua_ls 没有运行（可能被停用了）：请用户在 IDE 的「语言服务器」视图里启动它；在那之前用 grep。")
                )
            } else {
                server = item
                block(item)
            }
        }
    }

    private fun uriOf(item: LanguageServerItem, file: LuaFile): String? {
        val vf = findFile(file.path)
        return if (vf != null) item.serverWrapper.toUriString(vf) else runCatching { Path.of(file.path).toUri().toString() }.getOrNull()
    }

    private fun convert(s: DocumentSymbol): DocSymbol {
        val start = (s.selectionRange ?: s.range).start
        return DocSymbol(
            name = s.name.orEmpty(),
            kind = s.kind?.value ?: 0,
            detail = s.detail,
            line = start.line,
            col = start.character,
            children = s.children.orEmpty().filterNotNull().map(::convert),
        )
    }

    private fun convert(s: SymbolInformation): DocSymbol {
        val start = s.location.range.start
        val name = s.containerName?.takeIf { it.isNotBlank() }?.let { "$it.${s.name}" } ?: s.name
        return DocSymbol(name, s.kind?.value ?: 0, null, start.line, start.character)
    }

    /** 服务器回的 URI → 绝对路径（系统写法）。先按 URI 解码；不是 file: 的再问 LSP4IJ。 */
    private fun pathOf(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        runCatching { Path.of(URI(uri)).toString() }.getOrNull()?.let { return it }
        return LSPIJUtils.findResourceFor(uri)?.let { FileUtil.toSystemDependentName(it.path) }
    }

    // ------------------------------------------------------------------ 库目录

    @Volatile
    private var libCache: Pair<Long, List<Path>>? = null

    /**
     * 库目录：设置里的额外库 + UE 声明目录（和 [EmmyLuaWorkspaceConfig.libraries] 同源，但不去掉项目里面的：
     * UELink 生成的声明在项目的 Intermediate 下，也算库，search 不逐个扫）。要找 .uproject，缓存 30 秒。
     */
    private fun libraryRoots(): List<Path> {
        val now = System.currentTimeMillis()
        libCache?.let { (at, roots) -> if (now - at < 30_000) return roots }
        val roots = runCatching {
            val root = EmmyLuaWorkspaceConfig.projectRoot(project)
            val settings = LuaProjectSettings.getInstance(project)
            val out = LinkedHashSet<Path>()
            for (raw in settings.additionalSourcesRoot) {
                val text = raw.trim()
                if (text.isEmpty()) continue
                val p = runCatching { Path.of(text) }.getOrNull() ?: continue
                val abs = (if (p.isAbsolute || root == null) p else root.resolve(p)).normalize()
                if (Files.isDirectory(abs)) out.add(abs)
            }
            if (settings.detectUnrealProject) out.addAll(EmmyLuaWorkspaceConfig.unrealLibraries(project))
            out.toList()
        }.getOrDefault(emptyList())
        libCache = now to roots
        return roots
    }

    private fun under(p: Path?, roots: List<Path>): Boolean = p != null && roots.any { p.startsWith(it) }

    private fun findFile(path: String): VirtualFile? {
        val independent = FileUtil.toSystemIndependentName(path)
        val fs = LocalFileSystem.getInstance()
        return fs.findFileByPath(independent) ?: runCatching { fs.refreshAndFindFileByPath(independent) }.getOrNull()
    }

    private fun isLua(vf: VirtualFile) = vf.extension.equals(LUA_EXT, ignoreCase = true)

    private fun VirtualFile.toNioPathOrNull(): Path? = runCatching { toNioPath() }.getOrNull()

    companion object {
        private const val LUA_EXT = "lua"
    }
}
