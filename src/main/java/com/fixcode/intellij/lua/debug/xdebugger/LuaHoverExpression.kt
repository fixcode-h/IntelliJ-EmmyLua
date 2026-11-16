package com.fixcode.intellij.lua.debug.xdebugger

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.psi.tree.IElementType

/**
 * 调试时鼠标悬停：从一行源码里找出光标下要求值的表达式（[LuaV3Evaluator] 用）。
 *
 * 和 [LuaInlineValueScanner] 一样只用词法器切本行，不依赖 PSI（语言支持迁到 LSP 后 IDE 里只剩扁平 PSI）。
 * 规则：
 * 1. 光标在名字（含 `self`）上才求值；关键字、字面量、注释、字符串里不求值。
 * 2. 向左接上路径前缀：`a.b.c` 悬停在 `b` 上求 `a.b`；字面量下标 `a[1].b`、`a["k"].b` 也接上。
 * 3. 方法调用 `self:Foo()` 悬停在 `Foo` 上按 `self.Foo` 求值（高亮范围仍是原文 `self:Foo`）。
 * 4. 前缀不是纯路径（`foo().x`、`(a).x`、`t[i].x`）时不求值，免得把 `x` 当成全局变量。
 */
object LuaHoverExpression {

    /** 本行内的范围 [[start], [end]) 和要求值的表达式文本。 */
    data class Hover(val start: Int, val end: Int, val expression: String)

    private class Tok(val type: IElementType, val start: Int, val end: Int, val text: String)

    fun find(line: CharSequence, column: Int): Hover? {
        val toks = tokenize(line)
        val index = toks.indexOfFirst { column >= it.start && column < it.end }
        if (index < 0 || !isName(toks.getOrNull(index))) return null

        var first = index
        var colonAt = -1
        while (true) {
            val sep = toks.getOrNull(first - 1)?.type
            if (sep != T.DOT && !(sep == T.COLON && first == index)) break
            // 分隔符左边：先跳过若干个 `[字面量]`，再必须是名字
            var k = first - 2
            while (isLiteralIndex(toks, k)) k -= 3
            if (!isName(toks.getOrNull(k))) return null
            if (sep == T.COLON) colonAt = first - 1
            first = k
        }

        val expression = buildString {
            for (k in first..index) append(if (k == colonAt) "." else toks[k].text)
        }
        return Hover(toks[first].start, toks[index].end, expression)
    }

    /** [i] 是 `[数字]` / `["字符串"]` 的右括号。 */
    private fun isLiteralIndex(toks: List<Tok>, i: Int): Boolean {
        if (toks.getOrNull(i)?.type != T.RBRACK) return false
        val literal = toks.getOrNull(i - 1)?.type
        return (literal == T.NUMBER || literal == T.STRING) && toks.getOrNull(i - 2)?.type == T.LBRACK
    }

    private fun isName(t: Tok?) = t != null && (t.type == T.NAME || t.type == T.SELF)

    private fun tokenize(line: CharSequence): List<Tok> {
        val lexer = LuaLexer()
        lexer.start(line, 0, line.length, LuaLexer.STATE_NORMAL)
        val out = ArrayList<Tok>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != T.WHITE_SPACE) out += Tok(type, lexer.tokenStart, lexer.tokenEnd, line.subSequence(lexer.tokenStart, lexer.tokenEnd).toString())
            lexer.advance()
        }
        return out
    }
}
