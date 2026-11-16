package com.fixcode.intellij.lua.highlighting

import com.fixcode.intellij.lua.lexer.LuaLexer
import com.fixcode.intellij.lua.lexer.LuaTokenTypes as T
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class LuaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = LuaLexer()

    override fun getTokenHighlights(type: IElementType?): Array<TextAttributesKey> = pack(MAP[type])

    companion object {
        private val H = LuaHighlightingData
        private val MAP: Map<IElementType, TextAttributesKey> = mapOf(
            T.KEYWORD to H.KEYWORD,
            T.CONSTANT to H.CONSTANT,
            T.SELF to H.SELF,
            T.LINE_COMMENT to H.LINE_COMMENT,
            T.BLOCK_COMMENT to H.BLOCK_COMMENT,
            T.DOC_COMMENT to H.DOC_COMMENT,
            T.DOC_TAG to H.DOC_TAG,
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
            T.CONCAT to H.OPERATORS,
            T.ELLIPSIS to H.OPERATORS,
            T.OPERATOR to H.OPERATORS,
            T.BAD_CHARACTER to HighlighterColors.BAD_CHARACTER,
        )
    }
}

class LuaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = LuaSyntaxHighlighter()
}
