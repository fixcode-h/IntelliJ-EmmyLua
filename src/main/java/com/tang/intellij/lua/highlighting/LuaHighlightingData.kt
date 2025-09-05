/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.highlighting

import com.intellij.execution.process.ConsoleHighlighter
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 *
 * Created by TangZX on 2016/11/22.
 */
object LuaHighlightingData {
    val DOC_COMMENT_TAG = TextAttributesKey.createTextAttributesKey("LUA_DOC_TAG", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG)
    val DOC_COMMENT_TAG_VALUE = TextAttributesKey.createTextAttributesKey("LUA_DOC_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE)
    val DOC_KEYWORD = TextAttributesKey.createTextAttributesKey("LUA_DOC_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val CLASS_NAME = TextAttributesKey.createTextAttributesKey("LUA_CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)
    val CLASS_REFERENCE = TextAttributesKey.createTextAttributesKey("LUA_CLASS_REFERENCE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)

    val LOCAL_VAR = TextAttributesKey.createTextAttributesKey("LUA_LOCAL_VAR", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val LOCAL_FUNCTION = TextAttributesKey.createTextAttributesKey("LUA_LOCAL_FUNC", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val PARAMETER = TextAttributesKey.createTextAttributesKey("LUA_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
    val FIELD = TextAttributesKey.createTextAttributesKey("LUA_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val ENUM_VALUE = TextAttributesKey.createTextAttributesKey("LUA_ENUM_VALUE", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val GLOBAL_FUNCTION = TextAttributesKey.createTextAttributesKey("LUA_GLOBAL_FUNCTION_ID", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val GLOBAL_VAR = TextAttributesKey.createTextAttributesKey("LUA_GLOBAL_VAR", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val KEYWORD = TextAttributesKey.createTextAttributesKey("LUA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val SELF = TextAttributesKey.createTextAttributesKey("LUA_SELF", DefaultLanguageHighlighterColors.KEYWORD)
    val LINE_COMMENT = TextAttributesKey.createTextAttributesKey("LUA_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val DOC_COMMENT = TextAttributesKey.createTextAttributesKey("LUA_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val NUMBER = TextAttributesKey.createTextAttributesKey("LUA_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val STRING = TextAttributesKey.createTextAttributesKey("LUA_STRING", DefaultLanguageHighlighterColors.STRING)
    val BRACKETS = TextAttributesKey.createTextAttributesKey("LUA_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val BRACES = TextAttributesKey.createTextAttributesKey("LUA_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val PARENTHESES = TextAttributesKey.createTextAttributesKey("LUA_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val DOT = TextAttributesKey.createTextAttributesKey("LUA_DOT", DefaultLanguageHighlighterColors.DOT)
    val OPERATORS = TextAttributesKey.createTextAttributesKey("LUA_OPERATORS", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val SEMICOLON = TextAttributesKey.createTextAttributesKey("LUA_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    val COMMA = TextAttributesKey.createTextAttributesKey("LUA_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val PRIMITIVE_TYPE = TextAttributesKey.createTextAttributesKey("LUA_PRIMITIVE_TYPE", ConsoleHighlighter.CYAN_BRIGHT)
    val UP_VALUE = TextAttributesKey.createTextAttributesKey("LUA_UP_VALUE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val STD_API = TextAttributesKey.createTextAttributesKey("LUA_STD_API", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    val TYPE_ALIAS = TextAttributesKey.createTextAttributesKey("LUA_TYPE_ALIAS", DefaultLanguageHighlighterColors.CLASS_NAME)
    val INSTANCE_METHOD = TextAttributesKey.createTextAttributesKey("LUA_INSTANCE_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    val STATIC_METHOD = TextAttributesKey.createTextAttributesKey("LUA_STATIC_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD)
    
    // 函数声明颜色（区别于函数调用）
    val FUNCTION_DECLARATION = TextAttributesKey.createTextAttributesKey("LUA_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val METHOD_DECLARATION = TextAttributesKey.createTextAttributesKey("LUA_METHOD_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    
    // 函数调用颜色（区别于函数声明和局部变量）- 使用自定义颜色
    val FUNCTION_CALL = TextAttributesKey.createTextAttributesKey("LUA_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val METHOD_CALL = TextAttributesKey.createTextAttributesKey("LUA_METHOD_CALL", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    
    // 更细致的标识符颜色定义
    val CLASS_INSTANCE_VAR = TextAttributesKey.createTextAttributesKey("LUA_CLASS_INSTANCE_VAR", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val FUNCTION_LOCAL_VAR = TextAttributesKey.createTextAttributesKey("LUA_FUNCTION_LOCAL_VAR", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val METHOD_LOCAL_VAR = TextAttributesKey.createTextAttributesKey("LUA_METHOD_LOCAL_VAR", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val CLOSURE_VAR = TextAttributesKey.createTextAttributesKey("LUA_CLOSURE_VAR", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val MODULE_VAR = TextAttributesKey.createTextAttributesKey("LUA_MODULE_VAR", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val CONSTANT_VAR = TextAttributesKey.createTextAttributesKey("LUA_CONSTANT_VAR", DefaultLanguageHighlighterColors.CONSTANT)
    val TABLE_KEY = TextAttributesKey.createTextAttributesKey("LUA_TABLE_KEY", DefaultLanguageHighlighterColors.IDENTIFIER)
    val METATABLE_KEY = TextAttributesKey.createTextAttributesKey("LUA_METATABLE_KEY", DefaultLanguageHighlighterColors.IDENTIFIER)
    
    // 控制流关键字
    val CONTROL_FLOW_KEYWORD = TextAttributesKey.createTextAttributesKey("LUA_CONTROL_FLOW_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    // 逻辑操作符
    val LOGICAL_OPERATOR = TextAttributesKey.createTextAttributesKey("LUA_LOGICAL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 比较操作符
    val COMPARISON_OPERATOR = TextAttributesKey.createTextAttributesKey("LUA_COMPARISON_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 算术操作符
    val ARITHMETIC_OPERATOR = TextAttributesKey.createTextAttributesKey("LUA_ARITHMETIC_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 位操作符
    val BITWISE_OPERATOR = TextAttributesKey.createTextAttributesKey("LUA_BITWISE_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 字符串连接操作符
    val STRING_CONCAT_OPERATOR = TextAttributesKey.createTextAttributesKey("LUA_STRING_CONCAT_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 长度操作符
    val LENGTH_OPERATOR = TextAttributesKey.createTextAttributesKey("LUA_LENGTH_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 省略号
    val ELLIPSIS = TextAttributesKey.createTextAttributesKey("LUA_ELLIPSIS", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // 标签
    val LABEL = TextAttributesKey.createTextAttributesKey("LUA_LABEL", DefaultLanguageHighlighterColors.LABEL)
    // goto语句
    val GOTO_KEYWORD = TextAttributesKey.createTextAttributesKey("LUA_GOTO_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

    //region
    val REGION_HEADER = TextAttributesKey.createTextAttributesKey("LUA_REGION_START", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val REGION_DESC = TextAttributesKey.createTextAttributesKey("LUA_REGION_DESC", DefaultLanguageHighlighterColors.DOC_COMMENT)
}