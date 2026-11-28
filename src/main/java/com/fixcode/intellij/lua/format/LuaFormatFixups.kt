package com.fixcode.intellij.lua.format

import com.fixcode.intellij.lua.editor.LuaBlocks
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T

/**
 * 格式化结果的小修补：只处理 emmylua_formatter 已知的排版毛病，不做别的排版。
 *
 * 调用里匿名函数后面还有参数时（`foo(a, function() ... end, b)`），emmylua_formatter（0.25.1，上游主干也一样，
 * `format_call_args_with_inline_block_item`）把右括号单独放一行，和 `end` 同一缩进：
 * ```
 * foo(a, function ()
 *     x()
 * end, b
 * )
 * ```
 * 这里把这种 `)` 接回上一行，变成 `end, b)`，和没有尾参数时的 `end)` 一样。
 * 只认「上一行以 `end` 开头、两行缩进相同、`)` 这行除了右括号和逗号分号没有别的」，`foo(\n    a\n)` 这种正常的不动。
 */
object LuaFormatFixups {
    fun apply(text: String): String = joinDanglingParens(text)

    fun joinDanglingParens(text: String): String {
        val tokens = LuaBlocks.lexAll(text)
        val removals = ArrayList<IntRange>() // 要删掉的「换行 + 缩进」
        for ((i, tok) in tokens.withIndex()) {
            if (tok.type != T.RPAREN) continue
            val ls = LuaBlocks.lineStart(text, tok.start)
            if (text.substring(ls, tok.start).isNotBlank()) continue
            // `)` 这行后面只能是右括号、逗号、分号
            var j = i + 1
            var restOk = true
            while (j < tokens.size && !tokens[j].text.contains('\n')) {
                val t = tokens[j]
                if (t.type != T.WHITE_SPACE && t.type != T.RPAREN && t.type != T.COMMA && t.type != T.SEMI) { restOk = false; break }
                j++
            }
            if (!restOk) continue
            // 上一个非空白 token 在紧挨着的上一行，不是注释、不是开括号或逗号
            var k = i - 1
            while (k >= 0 && tokens[k].type == T.WHITE_SPACE) k--
            if (k < 0) continue
            val prev = tokens[k]
            if (prev.type in T.COMMENTS || prev.type == T.LPAREN || prev.type == T.COMMA || prev.type == T.LCURLY) continue
            val gap = text.substring(prev.end, tok.start)
            if (gap.count { it == '\n' } != 1) continue
            val prevLineStart = LuaBlocks.lineStart(text, prev.start)
            val prevIndent = LuaBlocks.indentAt(text, prevLineStart)
            if (prevIndent != text.substring(ls, tok.start)) continue
            val firstOnPrev = tokens.firstOrNull { it.start >= prevLineStart && it.type != T.WHITE_SPACE } ?: continue
            if (!firstOnPrev.isKeyword("end")) continue
            removals.add(prev.end until tok.start)
        }
        if (removals.isEmpty()) return text
        val sb = StringBuilder(text)
        for (r in removals.asReversed()) sb.delete(r.first, r.last + 1)
        return sb.toString()
    }
}
