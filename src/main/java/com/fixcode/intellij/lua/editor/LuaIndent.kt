package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider

/**
 * 基于词法器算一行该有的缩进（没有语法树，也不走格式化器：格式化由 emmylua_ls 做）。
 *
 * - 行首是 `end` / `until` / `else` / `elseif` / `}` / `)`：对齐开块那一行。
 * - 上一行开了块（`then` `do` `repeat` `function(...)` `{` `(` 之后）：上一行缩进 + 一级。
 * - 上一行关掉了它前面开的块（`foo(a,\n  b)` 的第二行）：回到外层块缩进 + 一级。
 * - 其余：跟上一行。用户自己的缩进风格（续行、对齐）不被改掉。
 */
object LuaIndent {
    fun unit(project: Project, document: Document): String {
        val options = CodeStyle.getIndentOptions(project, document)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
    }

    /** 返回 null 表示「不知道」，调用方保持原样。 */
    fun lineIndent(text: CharSequence, lineStart: Int, unit: String): String? {
        if (insideMultilineToken(text, lineStart)) return null
        val prefix = LuaBlocks.lex(text, 0, lineStart)
        val closer = LuaBlocks.leadingCloser(text, lineStart)
        if (closer != null) {
            val category = when (closer) {
                "}" -> LuaBlocks.Category.CURLY
                ")" -> LuaBlocks.Category.PAREN
                else -> LuaBlocks.Category.BLOCK
            }
            val stack = LuaBlocks.scan(text, prefix)
            return stack.lastOrNull { it.category == category }?.indent
        }

        val prevLineStart = previousNonBlankLine(text, lineStart) ?: return ""
        val prevIndent = LuaBlocks.indentAt(text, prevLineStart)
        val stack = LuaBlocks.scan(text, prefix)
        val top = stack.lastOrNull()
        if (top != null && top.lineStart == prevLineStart) return prevIndent + unit
        val before = LuaBlocks.scan(text, prefix.filter { it.start < prevLineStart })
        if (stack.size < before.size) return top?.let { it.indent + unit } ?: ""
        return prevIndent
    }

    private fun previousNonBlankLine(text: CharSequence, lineStart: Int): Int? {
        var end = lineStart - 1 // 上一行的换行符
        while (end > 0) {
            val start = LuaBlocks.lineStart(text, end)
            val line = text.subSequence(start, end)
            if (line.isNotBlank()) return start
            end = start - 1
        }
        return null
    }

    /** 这一行开头落在长字符串 / 长注释里面时不动缩进。 */
    fun insideMultilineToken(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0) return false
        // 从前面最近的、肯定在普通状态的位置开始扫太复杂；Lua 文件不大，直接从头扫
        for (tok in LuaBlocks.lexAll(text, 0, minOf(text.length, offset + 1))) {
            if (tok.start >= offset) break
            if (tok.end > offset && (tok.type == T.LONG_STRING || tok.type == T.BLOCK_COMMENT)) return true
        }
        return false
    }

    /** 把 `lineStart` 那一行的前导空白换成 `indent`，返回长度变化。 */
    fun setIndent(document: Document, lineStart: Int, indent: String): Int {
        val current = LuaBlocks.indentAt(document.charsSequence, lineStart)
        if (current == indent) return 0
        document.replaceString(lineStart, lineStart + current.length, indent)
        return indent.length - current.length
    }
}

class LuaLineIndentProvider : LineIndentProvider {
    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val document = editor.document
        val text = document.charsSequence
        val lineStart = LuaBlocks.lineStart(text, offset)
        return LuaIndent.lineIndent(text, lineStart, LuaIndent.unit(project, document))
    }

    override fun isSuitableFor(language: Language?): Boolean = language != null && language.isKindOf(LuaLanguage)
}
