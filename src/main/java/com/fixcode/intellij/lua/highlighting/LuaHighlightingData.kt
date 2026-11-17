package com.fixcode.intellij.lua.highlighting

import com.intellij.execution.process.ConsoleHighlighter
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey as key

/**
 * 配色键。外部名和默认回退色都照旧版（PSI 版 EmmyLua）来，`colorSchemes/Default.xml`、`Darcula.xml`
 * 也是旧版那两份，所以升级后颜色和以前一样，用户在配色方案里改过的颜色也还在。
 *
 * 词法类的由 [LuaSyntaxHighlighter] 用，语义类的由 emmylua_ls 的语义高亮
 * （[com.fixcode.intellij.lua.lsp.EmmyLuaSemanticTokensColors]）用。
 * 旧版有、但 emmylua_ls 给不出区分的键（上值、闭包变量……）也留着，配色页和用户的方案不丢。
 */
object LuaHighlightingData {
    // 关键字（词法）
    @JvmField val KEYWORD: TextAttributesKey = key("LUA_KEYWORD", D.KEYWORD)
    @JvmField val CONTROL_FLOW_KEYWORD: TextAttributesKey = key("LUA_CONTROL_FLOW_KEYWORD", D.KEYWORD)
    @JvmField val GOTO_KEYWORD: TextAttributesKey = key("LUA_GOTO_KEYWORD", D.KEYWORD)
    @JvmField val SELF: TextAttributesKey = key("LUA_SELF", D.KEYWORD)
    @JvmField val PRIMITIVE_TYPE: TextAttributesKey = key("LUA_PRIMITIVE_TYPE", ConsoleHighlighter.CYAN_BRIGHT)
    @JvmField val NUMBER: TextAttributesKey = key("LUA_NUMBER", D.NUMBER)
    @JvmField val STRING: TextAttributesKey = key("LUA_STRING", D.STRING)

    // 注释（词法）；块注释旧版就和行注释同色
    @JvmField val LINE_COMMENT: TextAttributesKey = key("LUA_LINE_COMMENT", D.LINE_COMMENT)
    @JvmField val DOC_COMMENT: TextAttributesKey = key("LUA_DOC_COMMENT", D.DOC_COMMENT)
    @JvmField val DOC_COMMENT_TAG: TextAttributesKey = key("LUA_DOC_TAG", D.DOC_COMMENT_TAG)
    @JvmField val DOC_COMMENT_TAG_VALUE: TextAttributesKey = key("LUA_DOC_VALUE", D.DOC_COMMENT_TAG_VALUE)
    @JvmField val DOC_KEYWORD: TextAttributesKey = key("LUA_DOC_KEYWORD", D.KEYWORD)
    @JvmField val REGION_HEADER: TextAttributesKey = key("LUA_REGION_START", D.DOC_COMMENT)
    @JvmField val REGION_DESC: TextAttributesKey = key("LUA_REGION_DESC", D.DOC_COMMENT)

    // 运算符和括号（词法）
    @JvmField val OPERATORS: TextAttributesKey = key("LUA_OPERATORS", D.OPERATION_SIGN)
    @JvmField val LOGICAL_OPERATOR: TextAttributesKey = key("LUA_LOGICAL_OPERATOR", D.OPERATION_SIGN)
    @JvmField val COMPARISON_OPERATOR: TextAttributesKey = key("LUA_COMPARISON_OPERATOR", D.OPERATION_SIGN)
    @JvmField val ARITHMETIC_OPERATOR: TextAttributesKey = key("LUA_ARITHMETIC_OPERATOR", D.OPERATION_SIGN)
    @JvmField val BITWISE_OPERATOR: TextAttributesKey = key("LUA_BITWISE_OPERATOR", D.OPERATION_SIGN)
    @JvmField val STRING_CONCAT_OPERATOR: TextAttributesKey = key("LUA_STRING_CONCAT_OPERATOR", D.OPERATION_SIGN)
    @JvmField val LENGTH_OPERATOR: TextAttributesKey = key("LUA_LENGTH_OPERATOR", D.OPERATION_SIGN)
    @JvmField val ELLIPSIS: TextAttributesKey = key("LUA_ELLIPSIS", D.OPERATION_SIGN)
    @JvmField val LABEL: TextAttributesKey = key("LUA_LABEL", D.LABEL)
    @JvmField val BRACKETS: TextAttributesKey = key("LUA_BRACKETS", D.BRACKETS)
    @JvmField val BRACES: TextAttributesKey = key("LUA_BRACES", D.BRACES)
    @JvmField val PARENTHESES: TextAttributesKey = key("LUA_PARENTHESES", D.PARENTHESES)
    @JvmField val DOT: TextAttributesKey = key("LUA_DOT", D.DOT)
    @JvmField val SEMICOLON: TextAttributesKey = key("LUA_SEMICOLON", D.SEMICOLON)
    @JvmField val COMMA: TextAttributesKey = key("LUA_COMMA", D.COMMA)

    // 变量（语义）
    @JvmField val LOCAL_VAR: TextAttributesKey = key("LUA_LOCAL_VAR", D.LOCAL_VARIABLE)
    @JvmField val LOCAL_FUNCTION: TextAttributesKey = key("LUA_LOCAL_FUNC", D.LOCAL_VARIABLE)
    @JvmField val PARAMETER: TextAttributesKey = key("LUA_PARAMETER", D.PARAMETER)
    @JvmField val GLOBAL_VAR: TextAttributesKey = key("LUA_GLOBAL_VAR", D.STATIC_FIELD)
    @JvmField val GLOBAL_FUNCTION: TextAttributesKey = key("LUA_GLOBAL_FUNCTION_ID", D.STATIC_FIELD)
    @JvmField val UP_VALUE: TextAttributesKey = key("LUA_UP_VALUE", D.LOCAL_VARIABLE)
    @JvmField val CLASS_INSTANCE_VAR: TextAttributesKey = key("LUA_CLASS_INSTANCE_VAR", D.INSTANCE_FIELD)
    @JvmField val FUNCTION_LOCAL_VAR: TextAttributesKey = key("LUA_FUNCTION_LOCAL_VAR", D.LOCAL_VARIABLE)
    @JvmField val METHOD_LOCAL_VAR: TextAttributesKey = key("LUA_METHOD_LOCAL_VAR", D.LOCAL_VARIABLE)
    @JvmField val CLOSURE_VAR: TextAttributesKey = key("LUA_CLOSURE_VAR", D.LOCAL_VARIABLE)
    @JvmField val MODULE_VAR: TextAttributesKey = key("LUA_MODULE_VAR", D.STATIC_FIELD)
    @JvmField val CONSTANT_VAR: TextAttributesKey = key("LUA_CONSTANT_VAR", D.CONSTANT)
    @JvmField val TABLE_KEY: TextAttributesKey = key("LUA_TABLE_KEY", D.IDENTIFIER)
    @JvmField val METATABLE_KEY: TextAttributesKey = key("LUA_METATABLE_KEY", D.IDENTIFIER)
    @JvmField val STD_API: TextAttributesKey = key("LUA_STD_API", D.PREDEFINED_SYMBOL)

    // 成员和函数（语义）
    @JvmField val FIELD: TextAttributesKey = key("LUA_FIELD", D.INSTANCE_METHOD)
    @JvmField val ENUM_VALUE: TextAttributesKey = key("LUA_ENUM_VALUE", D.STATIC_FIELD)
    @JvmField val INSTANCE_METHOD: TextAttributesKey = key("LUA_INSTANCE_METHOD", D.INSTANCE_METHOD)
    @JvmField val STATIC_METHOD: TextAttributesKey = key("LUA_STATIC_METHOD", D.STATIC_METHOD)
    @JvmField val FUNCTION_DECLARATION: TextAttributesKey = key("LUA_FUNCTION_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val METHOD_DECLARATION: TextAttributesKey = key("LUA_METHOD_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val FUNCTION_CALL: TextAttributesKey = key("LUA_FUNCTION_CALL", D.FUNCTION_CALL)
    @JvmField val METHOD_CALL: TextAttributesKey = key("LUA_METHOD_CALL", D.INSTANCE_METHOD)

    // 注解里的类型（语义）
    @JvmField val CLASS_NAME: TextAttributesKey = key("LUA_CLASS_NAME", D.CLASS_NAME)
    @JvmField val CLASS_REFERENCE: TextAttributesKey = key("LUA_CLASS_REFERENCE", D.CLASS_REFERENCE)
    @JvmField val TYPE_ALIAS: TextAttributesKey = key("LUA_TYPE_ALIAS", D.CLASS_NAME)
}
