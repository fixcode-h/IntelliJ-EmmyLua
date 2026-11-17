package com.fixcode.intellij.lua.debugger.mcp

import com.fixcode.intellij.lua.debugger.mcp.code.CodeLocation
import com.fixcode.intellij.lua.debugger.mcp.code.DocSymbol
import com.fixcode.intellij.lua.debugger.mcp.code.LuaCodeBackend
import com.fixcode.intellij.lua.debugger.mcp.code.LuaFile
import com.fixcode.intellij.lua.debugger.mcp.code.ServerState
import com.fixcode.intellij.lua.debugger.mcp.code.WorkspaceSymbolHit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的 emmylua_ls：文件内容和符号都手写，形状照 emmylua_ls 0.25.1 的真实回包
 * （`M:OnButtonClicked` 这样的全名、参数作为函数的子项、表构造里的字段作为表的子项）。
 */
class FakeCodeBackend(override val projectName: String = "Game", val root: String = "/work/Game") : LuaCodeBackend {
    class Entry(val file: LuaFile, val text: String, var symbols: List<DocSymbol>?)

    val entries = LinkedHashMap<String, Entry>()
    val workspace = ArrayList<WorkspaceSymbolHit>()
    var state: ServerState = ServerState.Ready
    val docSymbolCalls = AtomicInteger()
    var lastReference: Triple<String, Int, Int>? = null
    var references: List<CodeLocation> = emptyList()

    fun add(rel: String, text: String, symbols: List<DocSymbol>?, library: Boolean = false, stamp: Long = 1): LuaFile {
        val f = LuaFile("$root/$rel", library, stamp)
        entries[f.path] = Entry(f, text, symbols)
        return f
    }

    override fun serverState() = state
    override fun luaFiles() = entries.values.map { it.file }
    override fun baseDirs() = listOf(root, "$root/Content/Script")
    override fun fileAt(path: String) = entries[path]?.file
    override fun owns(path: String) = path.replace('\\', '/').startsWith(root)
    override fun readText(file: LuaFile) = entries[file.path]?.text

    override fun documentSymbols(file: LuaFile): CompletableFuture<List<DocSymbol>?> {
        docSymbolCalls.incrementAndGet()
        return CompletableFuture.completedFuture(entries[file.path]?.symbols)
    }

    override fun workspaceSymbols(query: String): CompletableFuture<List<WorkspaceSymbolHit>> =
        CompletableFuture.completedFuture(workspace.filter { it.name.contains(query, ignoreCase = true) })

    override fun references(file: LuaFile, line: Int, col: Int, includeDeclaration: Boolean): CompletableFuture<List<CodeLocation>> {
        lastReference = Triple(file.path, line, col)
        return CompletableFuture.completedFuture(references)
    }

    companion object {
        val MAIN_PANEL = """
            ---@class MainPanel : UserWidget
            ---@field Title string
            local M = UnLua.Class()

            local helper = 1

            function M:OnButtonClicked(btn, idx)
                self.Count = self.Count + 1
            end

            function M.Show(x)
            end

            local function localFn(a) end

            function GlobalHelper(a, b) return a end

            return M
        """.trimIndent()

        fun mainPanelSymbols() = listOf(
            DocSymbol("M", 5, null, 2, 0),
            DocSymbol("helper", 16, "1", 4, 0),
            DocSymbol("M:OnButtonClicked", 12, "(btn, idx)", 6, 9, listOf(DocSymbol("btn", 13, null, 6, 27))),
            DocSymbol("M.Show", 12, "(x)", 10, 9),
            DocSymbol("localFn", 12, "(a)", 13, 15),
            DocSymbol("GlobalHelper", 12, "(a, b)", 15, 9),
        )

        val GUN = """
            local Gun = {}
            function Gun:Fire(target)
                GlobalHelper(1, 2)
            end
            GunConfig = { damage = 3 }
            return Gun
        """.trimIndent()

        fun gunSymbols() = listOf(
            DocSymbol("Gun", 5, null, 0, 0),
            DocSymbol("Gun:Fire", 12, "(target)", 1, 9),
            DocSymbol("GunConfig", 19, null, 4, 0, listOf(DocSymbol("damage", 8, null, 4, 14))),
        )
    }
}
