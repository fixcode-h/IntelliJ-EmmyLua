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

package com.tang.intellij.lua.editor.formatter.blocks

import com.intellij.formatting.*
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.editor.formatter.LuaFormatContext
import com.tang.intellij.lua.psi.*

/**
 * 循环语句格式化块
 * 处理for、while、repeat等循环语句的格式化
 */
class LuaLoopBlock(psi: PsiElement,
                  wrap: Wrap?,
                  alignment: Alignment?,
                  indent: Indent,
                  ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    enum class LoopType {
        FOR_NUMERIC,    // for i = 1, 10 do
        FOR_GENERIC,    // for k, v in pairs(t) do
        WHILE,          // while condition do
        REPEAT          // repeat ... until condition
    }

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = getLoopChildIndent(child)
        val childAlignment = getLoopChildAlignment(child)
        val childWrap = getLoopChildWrap(child)
        
        return createBlock(child, childIndent, childAlignment, childWrap)
    }

    private fun getLoopChildIndent(child: PsiElement): Indent {
        return when {
            isLoopBody(child) -> Indent.getNormalIndent()
            isLoopKeyword(child) -> Indent.getNoneIndent()
            isLoopCondition(child) -> Indent.getContinuationIndent()
            else -> Indent.getNoneIndent()
        }
    }

    private fun getLoopChildAlignment(child: PsiElement): Alignment? {
        // 循环条件的对齐
        if (isLoopCondition(child) && ctx.luaSettings.ALIGN_LOOP_CONDITIONS) {
            return Alignment.createAlignment(true)
        }
        return null
    }

    private fun getLoopChildWrap(child: PsiElement): Wrap? {
        return when {
            isLoopCondition(child) && shouldWrapLoopCondition() -> 
                Wrap.createWrap(WrapType.NORMAL, false)
            isLoopBody(child) && shouldWrapLoopBody() -> 
                Wrap.createWrap(WrapType.ALWAYS, true)
            else -> null
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // 循环关键字后的间距
            val keywordSpacing = getLoopKeywordSpacing(psi1, psi2)
            if (keywordSpacing != null) return keywordSpacing

            // 循环体前的间距
            val bodySpacing = getLoopBodySpacing(psi1, psi2)
            if (bodySpacing != null) return bodySpacing

            // 循环条件的间距
            val conditionSpacing = getLoopConditionSpacing(psi1, psi2)
            if (conditionSpacing != null) return conditionSpacing
        }

        return super.getSpacing(child1, child2)
    }

    private fun getLoopKeywordSpacing(psi1: PsiElement, psi2: PsiElement): Spacing? {
        val loopType = getLoopType()
        
        return when {
            // for 关键字后的间距
            isForKeyword(psi1) && loopType in listOf(LoopType.FOR_NUMERIC, LoopType.FOR_GENERIC) -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            // while 关键字后的间距
            isWhileKeyword(psi1) && loopType == LoopType.WHILE -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            // do 关键字前的间距
            isDoKeyword(psi2) -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            // until 关键字前的间距
            isUntilKeyword(psi2) && loopType == LoopType.REPEAT -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            else -> null
        }
    }

    private fun getLoopBodySpacing(psi1: PsiElement, psi2: PsiElement): Spacing? {
        return when {
            isDoKeyword(psi1) && isLoopBody(psi2) -> {
                Spacing.createSpacing(0, 0, 1, true, 1)
            }
            isLoopBody(psi1) && isEndKeyword(psi2) -> {
                Spacing.createSpacing(0, 0, 1, true, 1)
            }
            isLoopBody(psi1) && isUntilKeyword(psi2) -> {
                Spacing.createSpacing(0, 0, 1, true, 1)
            }
            else -> null
        }
    }

    private fun getLoopConditionSpacing(psi1: PsiElement, psi2: PsiElement): Spacing? {
        return when {
            // 赋值操作符周围的间距
            psi1.node?.elementType == LuaTypes.ASSIGN || psi2.node?.elementType == LuaTypes.ASSIGN -> {
                if (ctx.settings.SPACE_AROUND_ASSIGNMENT_OPERATORS) {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                } else {
                    Spacing.createSpacing(0, 0, 0, false, 0)
                }
            }
            // 逗号后的间距
            psi1.node?.elementType == LuaTypes.COMMA -> {
                if (ctx.settings.SPACE_AFTER_COMMA) {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                } else {
                    Spacing.createSpacing(0, 0, 0, false, 0)
                }
            }
            // in 关键字周围的间距
            isInKeyword(psi1) || isInKeyword(psi2) -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            else -> null
        }
    }

    private fun isLoopStatement(element: PsiElement): Boolean {
        return element is LuaForAStat || element is LuaForBStat || 
               element is LuaWhileStat || element is LuaRepeatStat
    }

    private fun getLoopType(): LoopType {
        return when (psi) {
            is LuaForAStat -> LoopType.FOR_NUMERIC
            is LuaForBStat -> LoopType.FOR_GENERIC
            is LuaWhileStat -> LoopType.WHILE
            is LuaRepeatStat -> LoopType.REPEAT
            else -> LoopType.FOR_NUMERIC
        }
    }

    private fun isLoopBody(element: PsiElement): Boolean {
        // 检查是否是循环体内的语句
        val parent = element.parent
        return parent is LuaIndentRange && parent == psi
    }

    private fun isLoopCondition(element: PsiElement): Boolean {
        return when (psi) {
            is LuaWhileStat -> element == (psi as LuaWhileStat).expr
            is LuaRepeatStat -> element == (psi as LuaRepeatStat).expr
            is LuaForAStat -> {
                val forStat = psi as LuaForAStat
                element in forStat.exprList
            }
            is LuaForBStat -> {
                val forStat = psi as LuaForBStat
                forStat.exprList?.let { element in it.exprList } ?: false
            }
            else -> false
        }
    }

    private fun isLoopKeyword(element: PsiElement): Boolean {
        val elementType = element.node?.elementType
        return elementType == LuaTypes.FOR || elementType == LuaTypes.WHILE || 
               elementType == LuaTypes.REPEAT || elementType == LuaTypes.DO ||
               elementType == LuaTypes.END || elementType == LuaTypes.UNTIL
    }

    private fun isForKeyword(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.FOR
    }

    private fun isWhileKeyword(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.WHILE
    }

    private fun isDoKeyword(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.DO
    }

    private fun isEndKeyword(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.END
    }

    private fun isUntilKeyword(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.UNTIL
    }

    private fun isInKeyword(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.IN
    }

    private fun shouldWrapLoopCondition(): Boolean {
        return ctx.settings.WRAP_LONG_LINES && 
               ctx.settings.RIGHT_MARGIN > 0
    }

    private fun shouldWrapLoopBody(): Boolean {
        return true // 循环体总是换行
    }
}