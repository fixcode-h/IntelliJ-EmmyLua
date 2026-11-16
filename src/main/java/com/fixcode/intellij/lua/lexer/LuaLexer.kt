package com.fixcode.intellij.lua.lexer

import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * 手写的 Lua 词法器（5.1–5.4 + LuaJIT 数字后缀），只给编辑器用：高亮、括号、注释、调试器取名字。
 *
 * 状态只有三种：普通、注解行里标签之前、注解行里标签之后。长字符串、长注释都是一整个 token，
 * 所以 token 开头总是状态 0（注解行内部除外），平台增量重扫时从最近的状态 0 处开始即可。
 */
class LuaLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    /** 下一个 token 开始时的状态。 */
    private var state = STATE_NORMAL
    /** 当前 token 开始时的状态：平台从这个 token 重新开始扫时用它（[getState] 的约定）。 */
    private var tokenStartState = STATE_NORMAL

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.tokenEnd = startOffset
        this.state = initialState
        advance()
    }

    override fun getState(): Int = tokenStartState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        tokenStartState = state
        if (tokenStart >= bufferEnd) {
            tokenType = null
            state = STATE_NORMAL
            tokenStartState = STATE_NORMAL
            return
        }
        when (state) {
            STATE_DOC_BEFORE_TAG -> lexDocBeforeTag()
            STATE_DOC_REST -> lexDocRest()
            else -> lexNormal()
        }
    }

    private fun lexNormal() {
        var p = tokenStart
        val c = buffer[p]
        when {
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000c' || c == '\u000b' -> {
                while (p < bufferEnd && buffer[p].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000c' || it == '\u000b' }) p++
                finish(p, T.WHITE_SPACE)
            }
            c == '-' && at(p + 1) == '-' -> lexComment(p)
            c.isNameStart() -> {
                while (p < bufferEnd && buffer[p].isNamePart()) p++
                val word = buffer.subSequence(tokenStart, p).toString()
                finish(p, when {
                    word in T.KEYWORDS -> T.KEYWORD
                    word in T.CONSTANTS -> T.CONSTANT
                    word == "self" -> T.SELF
                    else -> T.NAME
                })
            }
            c.isDigit() || (c == '.' && at(p + 1).isDigit()) -> finish(scanNumber(p), T.NUMBER)
            c == '"' || c == '\'' -> finish(scanShortString(p, c), T.STRING)
            c == '[' && longBracketLevel(p) >= 0 -> finish(scanLongBracket(p, longBracketLevel(p)), T.LONG_STRING)
            else -> lexOperator(p, c)
        }
    }

    private fun lexComment(start: Int) {
        // --[[ 长注释 ]]、--[==[ ]==]
        val afterDashes = start + 2
        val level = if (at(afterDashes) == '[') longBracketLevel(afterDashes) else -1
        if (level >= 0) {
            finish(scanLongBracket(afterDashes, level), T.BLOCK_COMMENT)
            return
        }
        // --- 注解行：拆成前缀 / 标签 / 其余，标签单独上色
        if (at(afterDashes) == '-' && at(afterDashes + 1) != '-') {
            var p = afterDashes + 1
            while (p < bufferEnd && (buffer[p] == ' ' || buffer[p] == '\t')) p++
            if (at(p) == '@' || at(p) == '|') {
                state = STATE_DOC_BEFORE_TAG
                finish(p, T.DOC_COMMENT)
            } else {
                finish(lineEnd(p), T.DOC_COMMENT)
            }
            return
        }
        finish(lineEnd(afterDashes), T.LINE_COMMENT)
    }

    private fun lexDocBeforeTag() {
        var p = tokenStart
        if (at(p) == '@') {
            p++
            while (p < bufferEnd && buffer[p].isNamePart()) p++
            state = STATE_DOC_REST
            if (p >= bufferEnd || buffer[p] == '\n' || buffer[p] == '\r') state = STATE_NORMAL
            finish(p, T.DOC_TAG)
        } else {
            lexDocRest()
        }
    }

    private fun lexDocRest() {
        state = STATE_NORMAL
        finish(lineEnd(tokenStart), T.DOC_COMMENT)
    }

    private fun lexOperator(p: Int, c: Char) {
        val n = at(p + 1)
        when (c) {
            '(' -> finish(p + 1, T.LPAREN)
            ')' -> finish(p + 1, T.RPAREN)
            '[' -> finish(p + 1, T.LBRACK)
            ']' -> finish(p + 1, T.RBRACK)
            '{' -> finish(p + 1, T.LCURLY)
            '}' -> finish(p + 1, T.RCURLY)
            ',' -> finish(p + 1, T.COMMA)
            ';' -> finish(p + 1, T.SEMI)
            ':' -> if (n == ':') finish(p + 2, T.DOUBLE_COLON) else finish(p + 1, T.COLON)
            '.' -> when {
                n == '.' && at(p + 2) == '.' -> finish(p + 3, T.ELLIPSIS)
                n == '.' -> finish(p + 2, T.CONCAT)
                else -> finish(p + 1, T.DOT)
            }
            '=' -> if (n == '=') finish(p + 2, T.OPERATOR) else finish(p + 1, T.ASSIGN)
            '~', '<', '>' -> {
                val two = (c == '~' && n == '=') || (c == '<' && (n == '=' || n == '<')) || (c == '>' && (n == '=' || n == '>'))
                finish(if (two) p + 2 else p + 1, T.OPERATOR)
            }
            '/' -> finish(if (n == '/') p + 2 else p + 1, T.OPERATOR)
            '+', '-', '*', '%', '^', '#', '&', '|' -> finish(p + 1, T.OPERATOR)
            else -> finish(p + 1, T.BAD_CHARACTER)
        }
    }

    private fun scanNumber(start: Int): Int {
        var p = start
        if (buffer[p] == '0' && (at(p + 1) == 'x' || at(p + 1) == 'X')) {
            p += 2
            while (p < bufferEnd) {
                val c = buffer[p]
                if (c.isHexDigit() || c == '.') p++
                else if ((c == 'p' || c == 'P') && (at(p + 1).isDigit() || at(p + 1) == '+' || at(p + 1) == '-')) p += 2
                else break
            }
        } else {
            while (p < bufferEnd) {
                val c = buffer[p]
                if (c.isDigit() || c == '.' && at(p + 1) != '.') p++
                else if ((c == 'e' || c == 'E') && (at(p + 1).isDigit() || at(p + 1) == '+' || at(p + 1) == '-')) p += 2
                else break
            }
        }
        // LuaJIT 的 ULL / LL / i 后缀，以及写错的数字，都并进同一个 token，不拆成名字
        while (p < bufferEnd && buffer[p].isNamePart()) p++
        return p
    }

    private fun scanShortString(start: Int, quote: Char): Int {
        var p = start + 1
        while (p < bufferEnd) {
            val c = buffer[p]
            when {
                c == '\\' -> {
                    p += 2
                    // \z 跳过后面的空白（含换行）
                    if (at(p - 1) == 'z') while (p < bufferEnd && buffer[p].isWhitespace()) p++
                }
                c == quote -> return p + 1
                c == '\n' || c == '\r' -> return p // 没闭合的字符串到行尾为止
                else -> p++
            }
        }
        return bufferEnd
    }

    /** `[` 开头的长括号层级：`[[` 为 0，`[==[` 为 2；不是长括号返回 -1。 */
    private fun longBracketLevel(start: Int): Int {
        var p = start + 1
        var level = 0
        while (at(p) == '=') { level++; p++ }
        return if (at(p) == '[') level else -1
    }

    private fun scanLongBracket(start: Int, level: Int): Int {
        var p = start + level + 2
        while (p < bufferEnd) {
            if (buffer[p] == ']') {
                var q = p + 1
                var eq = 0
                while (at(q) == '=') { eq++; q++ }
                if (eq == level && at(q) == ']') return q + 1
                p = q
            } else {
                p++
            }
        }
        return bufferEnd
    }

    private fun lineEnd(from: Int): Int {
        var p = from
        while (p < bufferEnd && buffer[p] != '\n' && buffer[p] != '\r') p++
        return p
    }

    private fun at(i: Int): Char = if (i < bufferEnd) buffer[i] else '\u0000'

    private fun finish(end: Int, type: IElementType) {
        tokenEnd = maxOf(end, tokenStart + 1).coerceAtMost(bufferEnd)
        tokenType = type
    }

    companion object {
        const val STATE_NORMAL = 0
        const val STATE_DOC_BEFORE_TAG = 1
        const val STATE_DOC_REST = 2

        private fun Char.isNameStart() = this == '_' || this in 'a'..'z' || this in 'A'..'Z' || this.code >= 0x80
        private fun Char.isNamePart() = isNameStart() || this in '0'..'9'
        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
