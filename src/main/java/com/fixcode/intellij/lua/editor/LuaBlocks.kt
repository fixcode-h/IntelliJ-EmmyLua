package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.psi.tree.IElementType

/**
 * 只靠词法器认出块结构：`function` / `if` / `while` / `for` / `do` / `repeat` 开块，`end` / `until` 关块，
 * `{` `(` 是括号块。回车补 `end`、算缩进、Surround、后缀模板、生成注解都用它，不需要语法树。
 *
 * 认不准的地方（写到一半的代码、不配对的文件）一律往「不动」的方向退：宁可少补一个 `end`，也不多补。
 */
object LuaBlocks {
    class Tok(val type: IElementType, val start: Int, val end: Int, val text: String) {
        val isKeyword get() = type == T.KEYWORD
        fun isKeyword(word: String) = type == T.KEYWORD && text == word
        override fun toString() = text
    }

    enum class Category { BLOCK, CURLY, PAREN }

    /** 块开头的 token：关键字块（`while`/`for` 后面的 `do` 不算新块）和括号。 */
    class Opener(val tok: Tok, val category: Category, val lineStart: Int, val indent: String) {
        /** 这个块该用什么收尾。 */
        val closer: String
            get() = when (category) {
                Category.CURLY -> "}"
                Category.PAREN -> ")"
                Category.BLOCK -> if (tok.text == "repeat") "until" else "end"
            }
    }

    val BLOCK_CLOSERS = setOf("end", "until", "else", "elseif")
    private val SIGNIFICANT_SKIP = setOf(T.WHITE_SPACE)

    fun lex(text: CharSequence, start: Int = 0, end: Int = text.length): List<Tok> {
        val lexer = LuaLexer()
        lexer.start(text, start, end, LuaLexer.STATE_NORMAL)
        val out = ArrayList<Tok>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type !in SIGNIFICANT_SKIP && type !in T.COMMENTS) {
                out.add(Tok(type, lexer.tokenStart, lexer.tokenEnd, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()))
            }
            lexer.advance()
        }
        return out
    }

    /** 带注释的 token 列表（判断「是不是在注释里」时要用）。 */
    fun lexAll(text: CharSequence, start: Int = 0, end: Int = text.length): List<Tok> {
        val lexer = LuaLexer()
        lexer.start(text, start, end, LuaLexer.STATE_NORMAL)
        val out = ArrayList<Tok>()
        while (true) {
            val type = lexer.tokenType ?: break
            out.add(Tok(type, lexer.tokenStart, lexer.tokenEnd, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()))
            lexer.advance()
        }
        return out
    }

    fun lineStart(text: CharSequence, offset: Int): Int {
        var p = offset.coerceIn(0, text.length)
        while (p > 0 && text[p - 1] != '\n') p--
        return p
    }

    fun lineEnd(text: CharSequence, offset: Int): Int {
        var p = offset.coerceIn(0, text.length)
        while (p < text.length && text[p] != '\n' && text[p] != '\r') p++
        return p
    }

    fun indentAt(text: CharSequence, lineStart: Int): String {
        var p = lineStart
        while (p < text.length && (text[p] == ' ' || text[p] == '\t')) p++
        return text.subSequence(lineStart, p).toString()
    }

    /**
     * 按顺序扫 token，维护一个开块栈。`visitor` 在每个 token 处理完后被调用。
     * `else` / `elseif` 当作「关掉再开一个」：后面一行照样缩进一级。
     */
    fun scan(text: CharSequence, tokens: List<Tok>, visitor: ((Tok, ArrayDeque<Opener>) -> Unit)? = null): ArrayDeque<Opener> {
        val stack = ArrayDeque<Opener>()
        var pendingDo = 0 // 已经出现、还没碰到 do 的 while / for
        for (tok in tokens) {
            when {
                tok.isKeyword -> when (tok.text) {
                    "function", "if", "repeat" -> stack.addLast(opener(text, tok, Category.BLOCK))
                    "while", "for" -> { stack.addLast(opener(text, tok, Category.BLOCK)); pendingDo++ }
                    "do" -> if (pendingDo > 0) pendingDo-- else stack.addLast(opener(text, tok, Category.BLOCK))
                    "end", "until" -> popCategory(stack, Category.BLOCK)
                    "else", "elseif" -> {
                        // if 块里换段：关掉旧的、在这一行开新的，缩进按 if 那一行
                        val top = popCategory(stack, Category.BLOCK)
                        val indent = top?.indent ?: indentAt(text, lineStart(text, tok.start))
                        stack.addLast(Opener(tok, Category.BLOCK, lineStart(text, tok.start), indent))
                    }
                }
                tok.type == T.LCURLY -> stack.addLast(opener(text, tok, Category.CURLY))
                tok.type == T.LPAREN -> stack.addLast(opener(text, tok, Category.PAREN))
                tok.type == T.RCURLY -> popCategory(stack, Category.CURLY)
                tok.type == T.RPAREN -> popCategory(stack, Category.PAREN)
            }
            visitor?.invoke(tok, stack)
        }
        return stack
    }

    /** 关块：弹到同类的开块为止；栈里没有同类的就当多余的关块，不动栈。 */
    private fun popCategory(stack: ArrayDeque<Opener>, category: Category): Opener? {
        val idx = stack.indexOfLast { it.category == category }
        if (idx < 0) return null
        // 关键字块里没关的括号（写到一半的调用）一起丢掉；括号里没关的关键字块同理
        val found = stack[idx]
        while (stack.size > idx) stack.removeLast()
        return found
    }

    private fun opener(text: CharSequence, tok: Tok, category: Category): Opener {
        val ls = lineStart(text, tok.start)
        return Opener(tok, category, ls, indentAt(text, ls))
    }

    /** 一个 token 对块计数的影响：+1 开、-1 关、0 无关。`while`/`for` 与其 `do` 只算一次。 */
    fun isCloserToken(tok: Tok): Boolean =
        (tok.isKeyword && (tok.text == "end" || tok.text == "until")) || tok.type == T.RCURLY || tok.type == T.RPAREN

    fun categoryOf(tok: Tok): Category? = when {
        tok.type == T.LCURLY || tok.type == T.RCURLY -> Category.CURLY
        tok.type == T.LPAREN || tok.type == T.RPAREN -> Category.PAREN
        tok.isKeyword && tok.text in setOf("function", "if", "repeat", "while", "for", "do", "end", "until") -> Category.BLOCK
        else -> null
    }

    /** 整个文件里某一类块「开」比「关」多几个（`while`/`for` 的 `do` 不重复算）。 */
    fun balance(tokens: List<Tok>, category: Category): Int {
        var n = 0
        var pendingDo = 0
        for (tok in tokens) {
            if (categoryOf(tok) != category) continue
            when {
                tok.isKeyword("while") || tok.isKeyword("for") -> { n++; pendingDo++ }
                tok.isKeyword("do") -> if (pendingDo > 0) pendingDo-- else n++
                isCloserToken(tok) -> n--
                else -> n++
            }
        }
        return n
    }

    /**
     * `opener` 后面有没有配它的收尾。
     *
     * 从开块往后数同类 token：数回 0 的那个收尾，所在行的缩进不比开块行浅，才算是它的；
     * 比开块行浅说明是外层块的 `end`（在已有函数里新写 `if` 的常见情况）。整个文件开多于关时才可能缺。
     */
    fun hasMatchingCloser(text: CharSequence, tokens: List<Tok>, opener: Opener): Boolean {
        if (balance(tokens, opener.category) <= 0) return true
        val from = tokens.indexOfFirst { it.start == opener.tok.start }
        if (from < 0) return true
        var depth = 0
        var pendingDo = 0
        for (i in from until tokens.size) {
            val tok = tokens[i]
            if (tok.isKeyword("else") || tok.isKeyword("elseif")) {
                // else 段落本身不改深度；从 else 开始扫时它就是开块
                if (i == from) depth++
                continue
            }
            if (categoryOf(tok) != opener.category) continue
            when {
                tok.isKeyword("while") || tok.isKeyword("for") -> { depth++; pendingDo++ }
                tok.isKeyword("do") -> if (pendingDo > 0) pendingDo-- else depth++
                isCloserToken(tok) -> {
                    depth--
                    if (depth == 0) {
                        val closerIndent = indentAt(text, lineStart(text, tok.start))
                        return closerIndent.length >= opener.indent.length
                    }
                }
                else -> depth++
            }
        }
        return false
    }

    /** 行首是收尾词（`end` `until` `else` `elseif` `}` `)`）时返回它。 */
    fun leadingCloser(text: CharSequence, lineStart: Int): String? {
        val end = lineEnd(text, lineStart)
        val first = lex(text, lineStart, end).firstOrNull() ?: return null
        return when {
            first.isKeyword && first.text in BLOCK_CLOSERS -> first.text
            first.type == T.RCURLY -> "}"
            first.type == T.RPAREN -> ")"
            else -> null
        }
    }
}
