package com.fixcode.intellij.lua.highlighting

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenType
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/**
 * 词法高亮。关键字、运算符按旧版分成控制流 / 逻辑 / 比较 / 算术 / 位运算……几类，各用旧版的配色键；
 * 分类只在高亮时做（[LuaHighlightingLexer]），词法器本身和用它的格式化、调试器取名字都不受影响。
 */
class LuaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = LuaHighlightingLexer()

    override fun getTokenHighlights(type: IElementType?): Array<TextAttributesKey> = pack(MAP[type])

    companion object {
        private val H = LuaHighlightingData
        private val MAP: Map<IElementType, TextAttributesKey> = mapOf(
            T.KEYWORD to H.KEYWORD,
            LuaHighlightingLexer.CONTROL_FLOW to H.CONTROL_FLOW_KEYWORD,
            LuaHighlightingLexer.LOGICAL to H.LOGICAL_OPERATOR,
            LuaHighlightingLexer.GOTO to H.GOTO_KEYWORD,
            T.CONSTANT to H.PRIMITIVE_TYPE,
            T.SELF to H.SELF,
            T.LINE_COMMENT to H.LINE_COMMENT,
            T.BLOCK_COMMENT to H.LINE_COMMENT,
            T.DOC_COMMENT to H.DOC_COMMENT,
            T.DOC_TAG to H.DOC_COMMENT_TAG,
            T.REGION_MARK to H.REGION_HEADER,
            T.REGION_DESC to H.REGION_DESC,
            T.NUMBER to H.NUMBER,
            T.STRING to H.STRING,
            T.LONG_STRING to H.STRING,
            T.LPAREN to H.PARENTHESES,
            T.RPAREN to H.PARENTHESES,
            T.LBRACK to H.BRACKETS,
            T.RBRACK to H.BRACKETS,
            T.LCURLY to H.BRACES,
            T.RCURLY to H.BRACES,
            T.DOT to H.DOT,
            T.COLON to H.DOT,
            T.COMMA to H.COMMA,
            T.SEMI to H.SEMICOLON,
            T.DOUBLE_COLON to H.LABEL,
            T.ASSIGN to H.OPERATORS,
            T.CONCAT to H.STRING_CONCAT_OPERATOR,
            T.ELLIPSIS to H.ELLIPSIS,
            T.OPERATOR to H.OPERATORS,
            LuaHighlightingLexer.COMPARISON to H.COMPARISON_OPERATOR,
            LuaHighlightingLexer.ARITHMETIC to H.ARITHMETIC_OPERATOR,
            LuaHighlightingLexer.BITWISE to H.BITWISE_OPERATOR,
            LuaHighlightingLexer.LENGTH to H.LENGTH_OPERATOR,
            T.BAD_CHARACTER to HighlighterColors.BAD_CHARACTER,
        )
    }
}

/** 只给高亮用：把关键字、运算符按文字细分成旧版的几类。 */
class LuaHighlightingLexer : DelegateLexer(LuaLexer()) {
    override fun getTokenType(): IElementType? {
        val type = super.getTokenType()
        if (type != T.KEYWORD && type != T.OPERATOR) return type
        val text = bufferSequence.subSequence(tokenStart, tokenEnd).toString()
        return REFINED[text] ?: type
    }

    companion object {
        @JvmField val CONTROL_FLOW = LuaTokenType("CONTROL_FLOW_KEYWORD")
        @JvmField val LOGICAL = LuaTokenType("LOGICAL_OPERATOR")
        @JvmField val GOTO = LuaTokenType("GOTO_KEYWORD")
        @JvmField val COMPARISON = LuaTokenType("COMPARISON_OPERATOR")
        @JvmField val ARITHMETIC = LuaTokenType("ARITHMETIC_OPERATOR")
        @JvmField val BITWISE = LuaTokenType("BITWISE_OPERATOR")
        @JvmField val LENGTH = LuaTokenType("LENGTH_OPERATOR")

        // 和旧版一样：local 是普通关键字，function 算控制流
        private val REFINED: Map<String, IElementType> = buildMap {
            listOf("if", "then", "else", "elseif", "end", "for", "in", "do", "while", "repeat", "until", "break", "return", "function")
                .forEach { put(it, CONTROL_FLOW) }
            listOf("and", "or", "not").forEach { put(it, LOGICAL) }
            put("goto", GOTO)
            listOf("==", "~=", "<", "<=", ">", ">=").forEach { put(it, COMPARISON) }
            listOf("+", "-", "*", "/", "//", "%", "^").forEach { put(it, ARITHMETIC) }
            listOf("&", "|", "~", "<<", ">>").forEach { put(it, BITWISE) }
            put("#", LENGTH)
        }
    }
}

class LuaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = LuaSyntaxHighlighter()
}
