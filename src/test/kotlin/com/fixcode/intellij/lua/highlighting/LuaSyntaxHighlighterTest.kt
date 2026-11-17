package com.fixcode.intellij.lua.highlighting

import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** 关键字、运算符按旧版分类上色。 */
class LuaSyntaxHighlighterTest {
    private val h = LuaHighlightingData

    private fun keys(text: String): List<Pair<String, TextAttributesKey?>> {
        val highlighter = LuaSyntaxHighlighter()
        val lexer = highlighter.highlightingLexer
        lexer.start(text)
        val out = mutableListOf<Pair<String, TextAttributesKey?>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != T.WHITE_SPACE) {
                out += text.substring(lexer.tokenStart, lexer.tokenEnd) to highlighter.getTokenHighlights(lexer.tokenType).firstOrNull()
            }
            lexer.advance()
        }
        return out
    }

    @Test
    fun keywordsSplitLikeOldVersion() {
        assertEquals(
            listOf(
                "local" to h.KEYWORD, "function" to h.CONTROL_FLOW_KEYWORD, "if" to h.CONTROL_FLOW_KEYWORD,
                "and" to h.LOGICAL_OPERATOR, "not" to h.LOGICAL_OPERATOR, "goto" to h.GOTO_KEYWORD,
                "nil" to h.PRIMITIVE_TYPE, "self" to h.SELF,
            ),
            keys("local function if and not goto nil self"),
        )
    }

    @Test
    fun operatorsSplitLikeOldVersion() {
        assertEquals(
            listOf(
                "==" to h.COMPARISON_OPERATOR, "<=" to h.COMPARISON_OPERATOR, "+" to h.ARITHMETIC_OPERATOR,
                "//" to h.ARITHMETIC_OPERATOR, "&" to h.BITWISE_OPERATOR, "~" to h.BITWISE_OPERATOR,
                "<<" to h.BITWISE_OPERATOR, ".." to h.STRING_CONCAT_OPERATOR, "#" to h.LENGTH_OPERATOR,
                "..." to h.ELLIPSIS, "=" to h.OPERATORS,
            ),
            keys("== <= + // & ~ << .. # ... ="),
        )
    }

    @Test
    fun commentsAndRegions() {
        assertEquals(
            listOf("--[[x]]" to h.LINE_COMMENT, "--region" to h.REGION_HEADER, " desc" to h.REGION_DESC),
            keys("--[[x]]\n--region desc"),
        )
    }
}
