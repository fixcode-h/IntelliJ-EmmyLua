package com.fixcode.intellij.lua.debugger.mcp.code

import java.util.concurrent.CompletableFuture

/**
 * `lua_code` 工具要的东西，由 IDE 侧按项目实现（一个打开的项目一个）。语义数据全部来自 emmylua_ls
 * （documentSymbol / workspace/symbol / references），文件列表来自 IDE 的文件索引；这里不依赖 IntelliJ 和 LSP4J，
 * 只用下面这些纯数据，便于单测。
 *
 * 位置一律 0 起（LSP 的写法），工具输出时再换成 1 起。
 */
interface LuaCodeBackend {
    /** 项目名：打开了多个项目时写进结果，也可以用 `project` 参数指定。 */
    val projectName: String

    /** emmylua_ls 能不能用。不是 [ServerState.Ready] 时语义类动作直接报错。 */
    fun serverState(): ServerState

    /** 项目里和库目录（UE 声明、设置里的额外库）里的所有 `.lua` 文件。 */
    fun luaFiles(): List<LuaFile>

    /** 解析相对路径和模块名用的根：项目根、脚本根（UE 的 Content/Script）、内容根、库目录。 */
    fun baseDirs(): List<String>

    /** 这个绝对路径上的 `.lua` 文件（可以不在 [luaFiles] 里，例如工作区外的库）；没有时 null。 */
    fun fileAt(path: String): LuaFile?

    /** 这个路径是不是属于这个项目（内容根或库目录下），多个项目时选项目用。 */
    fun owns(path: String): Boolean

    /** 文件当前内容（编辑器里没保存的也算）；读不到时 null。 */
    fun readText(file: LuaFile): String?

    /** textDocument/documentSymbol。服务器还没索引到这个文件时 null。 */
    fun documentSymbols(file: LuaFile): CompletableFuture<List<DocSymbol>?>

    /** workspace/symbol（emmylua_ls 只返回全局和 `---@class` 这类类型，不含方法和局部）。 */
    fun workspaceSymbols(query: String): CompletableFuture<List<WorkspaceSymbolHit>>

    /** textDocument/references。 */
    fun references(file: LuaFile, line: Int, col: Int, includeDeclaration: Boolean): CompletableFuture<List<CodeLocation>>
}

sealed class ServerState {
    object Ready : ServerState()

    /** 正在启动（或刚被这次调用拉起来）。 */
    object Starting : ServerState()

    /** 用不了：[message] 是给用户和 Agent 看的中文说明（怎么办）。 */
    class Unavailable(val message: String) : ServerState()
}

/**
 * 一个 Lua 文件。[path] 是绝对路径（按系统写法），[library] 表示在库目录（UE 声明、额外库）里，
 * [stamp] 是内容版本，变了就重新向服务器要符号。
 */
data class LuaFile(val path: String, val library: Boolean = false, val stamp: Long = 0)

/** LSP 的 DocumentSymbol，只留用得上的字段。位置取 selectionRange 的起点。 */
data class DocSymbol(
    val name: String,
    /** LSP SymbolKind 的数值。 */
    val kind: Int,
    val detail: String? = null,
    val line: Int,
    val col: Int,
    val children: List<DocSymbol> = emptyList(),
)

/** workspace/symbol 的一项。[path] 是绝对路径。 */
data class WorkspaceSymbolHit(
    val name: String,
    val kind: Int,
    val container: String? = null,
    val path: String,
    val line: Int,
    val col: Int,
)

data class CodeLocation(val path: String, val line: Int, val col: Int)
