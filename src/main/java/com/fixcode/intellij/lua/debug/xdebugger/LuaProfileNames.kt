package com.fixcode.intellij.lua.debug.xdebugger

import java.io.File

/**
 * 采样结果的显示名补救（「Lua 采样」页和 MCP 的 profile 共用）：
 * - runtime 取不到名字的函数（UnLua 从 C++ 调的 `M:ReceiveTick`、委托回调）是 `<匿名>`，按定义行推断名字（同 [LuaFrameName]）；
 * - 用字符串 load 出来的 chunk，chunkName 就是代码本身，显示成 Lua 报错里那样的 `[string "第一行..."]`。
 */
object LuaProfileNames {
    /** runtime 给取不到名字的 Lua 函数的名字（native/emmy_debugger_core/src/profiler.cpp）。 */
    const val ANONYMOUS = "<匿名>"
    private const val C_SOURCE = "=[C]"
    private const val MAX_STRING_CHUNK = 40

    /** chunkName 的显示形式：`@a/b.lua` → `a/b.lua`，`=name` → `name`，字符串 chunk → `[string "..."]`。 */
    fun chunkDisplay(source: String): String = when {
        source.isEmpty() -> ""
        source.startsWith("@") || source.startsWith("=") -> source.substring(1)
        else -> {
            val first = source.trimStart().lineSequence().first().trimEnd()
            val clipped = first.length > MAX_STRING_CHUNK || source.trim().contains('\n')
            "[string \"${first.take(MAX_STRING_CHUNK)}${if (clipped) "..." else ""}\"]"
        }
    }

    /** 本地文件第 [line] 行（从 1 开始）；读不到为 null。 */
    fun fileLine(path: String, line: Int): String? = try {
        File(path).useLines(Charsets.UTF_8) { lines -> lines.drop(line - 1).firstOrNull() }
    } catch (_: Exception) {
        null
    }

    fun isStringChunk(source: String) = source.isNotEmpty() && !source.startsWith("@") && !source.startsWith("=")

    /** 函数名：runtime 给了就用；是 `<匿名>` 时按定义行推断，推断不出仍是 `<匿名>`。 */
    fun functionName(fn: String, line: Int, lineText: (Int) -> String?): String {
        if (fn != ANONYMOUS || line <= 0) return fn
        return lineText(line)?.let { LuaFrameName.fromDefinition(it) } ?: fn
    }

    /** runtime 折叠栈里这个函数的帧标签（profiler.cpp 的 Label，Lua 函数）。 */
    fun runtimeLabel(fn: String, source: String, line: Int): String {
        if (source == C_SOURCE || source.isEmpty()) return sanitize(fn)
        val src = if (source.startsWith("@") || source.startsWith("=")) source.substring(1) else source
        return sanitize("$fn ($src:$line)")
    }

    fun label(fn: String, source: String, line: Int): String = sanitize("$fn (${chunkDisplay(source)}:$line)")

    /**
     * 按 `旧帧标签 → 新帧标签` 改写折叠栈文本（每行「帧;帧;帧 样本数」）。
     * 帧名里的 `;`、换行已被 runtime 换成 `_`，所以按 `;` 切帧是安全的。
     */
    fun rewriteFolded(folded: String, renames: Map<String, String>): String {
        if (renames.isEmpty() || folded.isEmpty()) return folded
        return folded.lineSequence().filter { it.isNotEmpty() }.joinToString("\n", postfix = "\n") { line ->
            val cut = line.lastIndexOf(' ')
            if (cut <= 0) return@joinToString line
            line.substring(0, cut).split(';').joinToString(";") { renames[it] ?: it } + line.substring(cut)
        }
    }

    private fun sanitize(s: String) = s.map { if (it == ';' || it == '\n' || it == '\r' || it == '\t') '_' else it }.joinToString("")
}
