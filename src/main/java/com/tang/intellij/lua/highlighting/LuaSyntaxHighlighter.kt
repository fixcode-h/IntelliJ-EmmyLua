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

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.StringEscapesTokenTypes
import com.intellij.psi.tree.IElementType
import com.jetbrains.rd.util.remove
import com.tang.intellij.lua.comment.psi.LuaDocTokenType
import com.tang.intellij.lua.comment.psi.LuaDocTypes
import com.tang.intellij.lua.lang.LuaParserDefinition.Companion.DOC_KEYWORD_TOKENS
import com.tang.intellij.lua.lang.LuaParserDefinition.Companion.DOC_TAG_TOKENS
import com.tang.intellij.lua.lang.LuaParserDefinition.Companion.KEYWORD_TOKENS
import com.tang.intellij.lua.lang.LuaParserDefinition.Companion.PRIMITIVE_TYPE_SET
import com.tang.intellij.lua.psi.LuaElementTypes
import com.tang.intellij.lua.psi.LuaRegionTypes
import com.tang.intellij.lua.psi.LuaStringTypes
import com.tang.intellij.lua.psi.LuaTypes

/**
 * Created by tangzx
 * Date : 2015/11/15.
 */
class LuaSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer {
        return LuaFileLexer()
    }

    override fun getTokenHighlights(type: IElementType): Array<TextAttributesKey> {
        return when {
            ourMap1.containsKey(type) -> pack(ourMap1[type], ourMap2[type])
            type is LuaDocTokenType -> pack(LuaHighlightingData.DOC_COMMENT)
            type === LuaStringTypes.NEXT_LINE -> pack(DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
            type === StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN -> pack(DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
            type === StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN -> pack(DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)
            type === LuaStringTypes.INVALID_NEXT_LINE -> pack(DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)//for string
            else -> pack(null)
        }
    }

    companion object {



        private val ourMap1: Map<IElementType, TextAttributesKey>
        private val ourMap2: Map<IElementType, TextAttributesKey>

        init {
            ourMap1 = HashMap()
            ourMap2 = HashMap()
            
            // 控制流关键字
            val controlFlowKeywords = arrayOf(
                LuaTypes.IF, LuaTypes.THEN, LuaTypes.ELSE, LuaTypes.ELSEIF, LuaTypes.END,
                LuaTypes.FOR, LuaTypes.IN, LuaTypes.DO, LuaTypes.WHILE, LuaTypes.REPEAT, LuaTypes.UNTIL,
                LuaTypes.BREAK, LuaTypes.RETURN, LuaTypes.FUNCTION
            )
            
            // 逻辑操作符
            val logicalOperators = arrayOf(LuaTypes.AND, LuaTypes.OR, LuaTypes.NOT)
            
            // 比较操作符
            val comparisonOperators = arrayOf(
                LuaTypes.EQ, LuaTypes.NE, LuaTypes.LT, LuaTypes.LE, LuaTypes.GT, LuaTypes.GE
            )
            
            // 算术操作符
            val arithmeticOperators = arrayOf(
                LuaTypes.PLUS, LuaTypes.MINUS, LuaTypes.MULT, LuaTypes.DIV, LuaTypes.DOUBLE_DIV, LuaTypes.MOD, LuaTypes.EXP
            )
            
            // 位操作符
            val bitwiseOperators = arrayOf(
                LuaTypes.BIT_AND, LuaTypes.BIT_OR, LuaTypes.BIT_TILDE, LuaTypes.BIT_LTLT, LuaTypes.BIT_RTRT
            )
            
            // 基本关键字（除了控制流）
            val basicKeywords = arrayOf(LuaTypes.LOCAL)
            
            fillMap(ourMap1, LuaHighlightingData.KEYWORD, basicKeywords)
            fillMap(ourMap1, LuaHighlightingData.CONTROL_FLOW_KEYWORD, controlFlowKeywords)
            fillMap(ourMap1, LuaHighlightingData.LOGICAL_OPERATOR, logicalOperators)
            fillMap(ourMap1, LuaHighlightingData.COMPARISON_OPERATOR, comparisonOperators)
            fillMap(ourMap1, LuaHighlightingData.ARITHMETIC_OPERATOR, arithmeticOperators)
            fillMap(ourMap1, LuaHighlightingData.BITWISE_OPERATOR, bitwiseOperators)
            
            fillMap(ourMap1, LuaHighlightingData.STRING_CONCAT_OPERATOR, arrayOf(LuaTypes.CONCAT))
            fillMap(ourMap1, LuaHighlightingData.LENGTH_OPERATOR, arrayOf(LuaTypes.GETN))
            fillMap(ourMap1, LuaHighlightingData.ELLIPSIS, arrayOf(LuaTypes.ELLIPSIS))
            fillMap(ourMap1, LuaHighlightingData.GOTO_KEYWORD, arrayOf(LuaTypes.GOTO))
            
            fillMap(ourMap1, LuaHighlightingData.SEMICOLON, arrayOf(LuaTypes.SEMI))
            fillMap(ourMap1, LuaHighlightingData.COMMA, arrayOf(LuaTypes.COMMA))
            fillMap(ourMap1, LuaHighlightingData.BRACKETS, arrayOf(LuaTypes.LBRACK, LuaTypes.RBRACK))
            fillMap(ourMap1, LuaHighlightingData.BRACES, arrayOf(LuaTypes.LCURLY, LuaTypes.RCURLY))
            fillMap(ourMap1, LuaHighlightingData.PARENTHESES, arrayOf(LuaTypes.LPAREN, LuaTypes.RPAREN))
            fillMap(ourMap1, LuaHighlightingData.DOT, arrayOf(LuaTypes.DOT))
            //comment
            fillMap(ourMap1, LuaHighlightingData.LINE_COMMENT, arrayOf(LuaTypes.SHEBANG))
            fillMap(ourMap1, LuaHighlightingData.DOC_COMMENT, arrayOf(LuaTypes.SHEBANG_CONTENT))

            fillMap(ourMap1, LuaHighlightingData.LINE_COMMENT, arrayOf(LuaTypes.SHORT_COMMENT, LuaTypes.BLOCK_COMMENT))
            fillMap(ourMap1, LuaHighlightingData.DOC_COMMENT, arrayOf(LuaTypes.REGION, LuaTypes.ENDREGION))
            fillMap(ourMap1, LuaHighlightingData.DOC_COMMENT_TAG, DOC_TAG_TOKENS.types)
            fillMap(ourMap1, LuaHighlightingData.DOC_COMMENT_TAG, arrayOf(LuaDocTypes.TAG_NAME))
            fillMap(ourMap1, LuaHighlightingData.DOC_KEYWORD, DOC_KEYWORD_TOKENS.types)
            fillMap(ourMap1, LuaHighlightingData.BRACKETS, arrayOf(LuaDocTypes.ARR))
            fillMap(ourMap1, LuaHighlightingData.PARENTHESES, arrayOf(LuaDocTypes.LPAREN, LuaDocTypes.RPAREN))

            //primitive types
            fillMap(ourMap1, LuaHighlightingData.NUMBER, arrayOf(LuaTypes.NUMBER))
            fillMap(ourMap1, LuaHighlightingData.STRING, arrayOf(LuaTypes.STRING))
            fillMap(ourMap1, LuaHighlightingData.PRIMITIVE_TYPE, PRIMITIVE_TYPE_SET.types)

            //region
            fillMap(ourMap1, LuaHighlightingData.REGION_HEADER, arrayOf(LuaRegionTypes.REGION_START))
            fillMap(ourMap1, LuaHighlightingData.REGION_DESC, arrayOf(LuaRegionTypes.REGION_DESC))
            fillMap(ourMap1, LuaHighlightingData.REGION_HEADER, arrayOf(LuaRegionTypes.REGION_END))
        }

        private fun fillMap(map: HashMap<IElementType, TextAttributesKey>, key: TextAttributesKey, types: Array<IElementType>) {
            types.forEach {
                map[it] = key
            }
        }
    }
}
