package com.fixcode.intellij.lua.lexer

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class LuaTokenType(debugName: String) : IElementType(debugName, LuaLanguage)

/**
 * 词法 token。只分高亮、括号匹配、注释和调试器取变量名要用到的类别，
 * 不为每个关键字单独建类型：语义上的区别由 emmylua_ls 的语义高亮给。
 */
object LuaTokenTypes {
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val LINE_COMMENT = LuaTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = LuaTokenType("BLOCK_COMMENT")
    /** `---` 注解行里除标签外的部分。 */
    @JvmField val DOC_COMMENT = LuaTokenType("DOC_COMMENT")
    /** `---@class` 里的 `@class`。 */
    @JvmField val DOC_TAG = LuaTokenType("DOC_TAG")
    /** `--region` / `--endregion` 本身。 */
    @JvmField val REGION_MARK = LuaTokenType("REGION_MARK")
    /** `--region` 后面的说明文字。 */
    @JvmField val REGION_DESC = LuaTokenType("REGION_DESC")

    @JvmField val STRING = LuaTokenType("STRING")
    @JvmField val LONG_STRING = LuaTokenType("LONG_STRING")
    @JvmField val NUMBER = LuaTokenType("NUMBER")
    @JvmField val NAME = LuaTokenType("NAME")
    @JvmField val KEYWORD = LuaTokenType("KEYWORD")
    /** `nil` `true` `false`，单独一类方便高亮和调试器跳过。 */
    @JvmField val CONSTANT = LuaTokenType("CONSTANT")
    @JvmField val SELF = LuaTokenType("SELF")

    @JvmField val LPAREN = LuaTokenType("(")
    @JvmField val RPAREN = LuaTokenType(")")
    @JvmField val LBRACK = LuaTokenType("[")
    @JvmField val RBRACK = LuaTokenType("]")
    @JvmField val LCURLY = LuaTokenType("{")
    @JvmField val RCURLY = LuaTokenType("}")
    @JvmField val DOT = LuaTokenType(".")
    @JvmField val COLON = LuaTokenType(":")
    @JvmField val DOUBLE_COLON = LuaTokenType("::")
    @JvmField val COMMA = LuaTokenType(",")
    @JvmField val SEMI = LuaTokenType(";")
    @JvmField val ASSIGN = LuaTokenType("=")
    @JvmField val ELLIPSIS = LuaTokenType("...")
    @JvmField val CONCAT = LuaTokenType("..")
    @JvmField val OPERATOR = LuaTokenType("OPERATOR")

    val KEYWORDS: Set<String> = setOf(
        "and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
        "local", "not", "or", "repeat", "return", "then", "until", "while",
    )
    val CONSTANTS: Set<String> = setOf("nil", "true", "false")

    @JvmField val WHITESPACES: TokenSet = TokenSet.create(WHITE_SPACE)
    @JvmField val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT, DOC_TAG, REGION_MARK, REGION_DESC)
    @JvmField val STRINGS: TokenSet = TokenSet.create(STRING, LONG_STRING)
}
