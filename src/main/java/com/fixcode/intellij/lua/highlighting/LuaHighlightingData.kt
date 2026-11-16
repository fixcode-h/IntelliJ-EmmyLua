package com.fixcode.intellij.lua.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey as key

/**
 * 配色键。外部名沿用旧版（`LUA_KEYWORD` 等），用户在配色方案里改过的颜色升级后还在。
 * 词法类的由 [LuaSyntaxHighlighter] 用，语义类的由 emmylua_ls 的语义高亮（[com.fixcode.intellij.lua.lsp.EmmyLuaSemanticTokensColors]）用。
 */
object LuaHighlightingData {
    // 词法
    @JvmField val KEYWORD: TextAttributesKey = key("LUA_KEYWORD", D.KEYWORD)
    @JvmField val CONSTANT: TextAttributesKey = key("LUA_PRIMITIVE_TYPE", D.KEYWORD)
    @JvmField val SELF: TextAttributesKey = key("LUA_SELF", D.KEYWORD)
    @JvmField val LINE_COMMENT: TextAttributesKey = key("LUA_LINE_COMMENT", D.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT: TextAttributesKey = key("LUA_BLOCK_COMMENT", D.BLOCK_COMMENT)
    @JvmField val DOC_COMMENT: TextAttributesKey = key("LUA_DOC_COMMENT", D.DOC_COMMENT)
    @JvmField val DOC_TAG: TextAttributesKey = key("LUA_DOC_TAG", D.DOC_COMMENT_TAG)
    @JvmField val DOC_TAG_VALUE: TextAttributesKey = key("LUA_DOC_VALUE", D.DOC_COMMENT_TAG_VALUE)
    @JvmField val NUMBER: TextAttributesKey = key("LUA_NUMBER", D.NUMBER)
    @JvmField val STRING: TextAttributesKey = key("LUA_STRING", D.STRING)
    @JvmField val BRACKETS: TextAttributesKey = key("LUA_BRACKETS", D.BRACKETS)
    @JvmField val BRACES: TextAttributesKey = key("LUA_BRACES", D.BRACES)
    @JvmField val PARENTHESES: TextAttributesKey = key("LUA_PARENTHESES", D.PARENTHESES)
    @JvmField val DOT: TextAttributesKey = key("LUA_DOT", D.DOT)
    @JvmField val COMMA: TextAttributesKey = key("LUA_COMMA", D.COMMA)
    @JvmField val SEMICOLON: TextAttributesKey = key("LUA_SEMICOLON", D.SEMICOLON)
    @JvmField val OPERATORS: TextAttributesKey = key("LUA_OPERATORS", D.OPERATION_SIGN)
    @JvmField val LABEL: TextAttributesKey = key("LUA_LABEL", D.LABEL)

    // 语义（emmylua_ls 语义高亮）
    @JvmField val LOCAL_VAR: TextAttributesKey = key("LUA_LOCAL_VAR", D.LOCAL_VARIABLE)
    @JvmField val GLOBAL_VAR: TextAttributesKey = key("LUA_GLOBAL_VAR", D.STATIC_FIELD)
    @JvmField val CONSTANT_VAR: TextAttributesKey = key("LUA_CONSTANT_VAR", D.CONSTANT)
    @JvmField val PARAMETER: TextAttributesKey = key("LUA_PARAMETER", D.PARAMETER)
    @JvmField val FIELD: TextAttributesKey = key("LUA_FIELD", D.INSTANCE_FIELD)
    @JvmField val ENUM_VALUE: TextAttributesKey = key("LUA_ENUM_VALUE", D.STATIC_FIELD)
    @JvmField val CLASS_NAME: TextAttributesKey = key("LUA_CLASS_NAME", D.CLASS_NAME)
    @JvmField val CLASS_REFERENCE: TextAttributesKey = key("LUA_CLASS_REFERENCE", D.CLASS_REFERENCE)
    @JvmField val TYPE_ALIAS: TextAttributesKey = key("LUA_TYPE_ALIAS", D.CLASS_NAME)
    @JvmField val MODULE: TextAttributesKey = key("LUA_MODULE_VAR", D.CLASS_REFERENCE)
    @JvmField val FUNCTION_DECLARATION: TextAttributesKey = key("LUA_FUNCTION_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val METHOD_DECLARATION: TextAttributesKey = key("LUA_METHOD_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val FUNCTION_CALL: TextAttributesKey = key("LUA_FUNCTION_CALL", D.FUNCTION_CALL)
    @JvmField val METHOD_CALL: TextAttributesKey = key("LUA_METHOD_CALL", D.INSTANCE_METHOD)
    @JvmField val STD_API: TextAttributesKey = key("LUA_STD_API", D.PREDEFINED_SYMBOL)
    @JvmField val ANNOTATION: TextAttributesKey = key("LUA_ANNOTATION", D.METADATA)
}
