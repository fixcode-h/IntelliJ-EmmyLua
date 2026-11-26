package com.fixcode.intellij.lua.format

import com.fixcode.intellij.lua.editor.LuaBlocks
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T

/**
 * 给表字段的 `=` 补「对齐提示」，让 emmylua_formatter 对齐它们。
 *
 * emmylua_formatter（0.25.1，上游主干也一样）的 `align.table_field` 不是主动对齐：只有某个字段在源码里
 * `=` 前已经有两个以上空格时，才把同一组（连续、中间没有空行和注释）字段的 `=` 对齐
 * （`formatter/expr.rs` 的 `field_requests_alignment`）。旧版插件 Ctrl+Alt+L 会主动对齐，用户习惯这样，
 * 所以格式化前把每个表字段 `=` 前补到两个空格；格式化器重排空白，提示本身不会留在结果里，
 * 只有一个字段的组和单行的表照常排成一个空格。
 *
 * 只靠词法器：`=` 的最近一层开块是 `{` 才算表字段（函数体里的赋值语句最近一层是 `function`，不算）。
 */
object LuaAlignHints {
    fun apply(text: CharSequence): String {
        val tokens = LuaBlocks.lex(text)
        val inserts = ArrayList<Pair<Int, Int>>() // 位置, 补几个空格
        LuaBlocks.scan(text, tokens) { tok, stack ->
            if (tok.type != T.ASSIGN || stack.lastOrNull()?.category != LuaBlocks.Category.CURLY) return@scan
            var gap = 0
            var p = tok.start
            while (p > 0 && (text[p - 1] == ' ' || text[p - 1] == '\t')) { gap++; p-- }
            // 键和 `=` 不在一行（`=` 在行首）：格式化器本来就不按它对齐
            if (p == 0 || text[p - 1] == '\n' || text[p - 1] == '\r') return@scan
            if (gap < 2) inserts.add(tok.start to 2 - gap)
        }
        if (inserts.isEmpty()) return text.toString()
        val sb = StringBuilder(text.length + inserts.size * 2)
        var last = 0
        for ((pos, n) in inserts) {
            sb.append(text, last, pos)
            repeat(n) { sb.append(' ') }
            last = pos
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }
}
