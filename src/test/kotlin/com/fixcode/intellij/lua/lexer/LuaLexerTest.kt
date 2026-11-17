package com.fixcode.intellij.lua.lexer

import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Test

class LuaLexerTest {
    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = LuaLexer()
        lexer.start(text, 0, text.length, 0)
        val out = mutableListOf<Pair<IElementType, String>>()
        var guard = 0
        while (lexer.tokenType != null) {
            if (lexer.tokenType != T.WHITE_SPACE) out += lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
            check(++guard < 10_000) { "lexer did not advance" }
        }
        return out
    }

    private fun types(text: String) = lex(text).map { it.first }

    @Test
    fun keywordsNamesConstants() {
        assertEquals(
            listOf(T.KEYWORD, T.NAME, T.ASSIGN, T.CONSTANT, T.KEYWORD, T.SELF, T.DOT, T.NAME),
            types("local a = nil or self.x"),
        )
    }

    @Test
    fun numbers() {
        assertEquals(listOf("1", "0x1F", "3.14", ".5", "1e-3", "0x1p4", "10ULL"), lex("1 0x1F 3.14 .5 1e-3 0x1p4 10ULL").map { it.second })
        assertEquals(List(7) { T.NUMBER }, types("1 0x1F 3.14 .5 1e-3 0x1p4 10ULL"))
    }

    @Test
    fun stringsAndLongBrackets() {
        assertEquals(
            listOf(T.STRING to "'a\\'b'", T.STRING to "\"c\"", T.LONG_STRING to "[==[x]]y]==]"),
            lex("'a\\'b' \"c\" [==[x]]y]==]"),
        )
        // 没闭合的短字符串到行尾为止，下一行照常
        assertEquals(listOf(T.STRING, T.NAME), types("'abc\nfoo"))
        // 下标不是长字符串
        assertEquals(listOf(T.NAME, T.LBRACK, T.NUMBER, T.RBRACK), types("t[1]"))
    }

    @Test
    fun comments() {
        assertEquals(listOf(T.LINE_COMMENT, T.NAME), types("-- hi\nx"))
        assertEquals(listOf(T.BLOCK_COMMENT, T.NAME), types("--[[ a\n b ]] x"))
        assertEquals(listOf(T.BLOCK_COMMENT), types("--[=[ ]] ]=]"))
        assertEquals(listOf(T.LINE_COMMENT), types("---- separator"))
    }

    @Test
    fun docComments() {
        assertEquals(
            listOf(T.DOC_COMMENT to "--- ", T.DOC_TAG to "@class", T.DOC_COMMENT to " Foo : Bar", T.NAME to "x"),
            lex("--- @class Foo : Bar\nx"),
        )
        assertEquals(listOf(T.DOC_COMMENT to "--- plain doc"), lex("--- plain doc"))
        assertEquals(listOf(T.DOC_COMMENT to "---", T.DOC_TAG to "@type"), lex("---@type"))
    }

    @Test
    fun regionComments() {
        assertEquals(
            listOf(T.REGION_MARK to "--region", T.REGION_DESC to " members", T.NAME to "x", T.REGION_MARK to "--endregion"),
            lex("--region members\nx\n--endregion"),
        )
        assertEquals(listOf(T.LINE_COMMENT), types("--regional"))
    }

    @Test
    fun operators() {
        assertEquals(
            listOf(T.ELLIPSIS, T.CONCAT, T.OPERATOR, T.OPERATOR, T.OPERATOR, T.DOUBLE_COLON, T.COLON, T.OPERATOR),
            types("... .. == ~= // :: : #"),
        )
    }

    @Test
    fun restartFromAnyNormalStateGivesSameTokens() {
        val text = "local s = [[a\nb]] --[[c]] ---@param x number\nreturn x .. 'y'"
        val full = lex(text)
        val lexer = LuaLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            if (lexer.state == 0 && lexer.tokenStart > 0) {
                val restarted = LuaLexer()
                restarted.start(text, lexer.tokenStart, text.length, 0)
                val tail = mutableListOf<Pair<IElementType, String>>()
                while (restarted.tokenType != null) {
                    if (restarted.tokenType != T.WHITE_SPACE) tail += restarted.tokenType!! to text.substring(restarted.tokenStart, restarted.tokenEnd)
                    restarted.advance()
                }
                assertEquals(full.takeLast(tail.size), tail)
            }
            lexer.advance()
        }
    }

    @Test
    fun coversWholeBufferWithoutGaps() {
        val text = "a = {1, 2; [3]='x'} @ 中文 = 1\r\n::lbl:: goto lbl"
        val lexer = LuaLexer()
        lexer.start(text, 0, text.length, 0)
        var expected = 0
        while (lexer.tokenType != null) {
            assertEquals(expected, lexer.tokenStart)
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, expected)
    }
}
