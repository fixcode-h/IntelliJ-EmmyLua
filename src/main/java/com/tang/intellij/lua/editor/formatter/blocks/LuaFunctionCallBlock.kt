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
 * 函数调用格式化块
 * 处理函数调用的参数对齐、换行等格式化
 */
class LuaFunctionCallBlock(psi: PsiElement,
                          wrap: Wrap?,
                          alignment: Alignment?,
                          indent: Indent,
                          ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    private var argumentAlignment: Alignment? = null

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = getFunctionCallChildIndent(child)
        val childAlignment = getFunctionCallChildAlignment(child)
        val childWrap = getFunctionCallChildWrap(child)
        
        return when (child) {
            is LuaArgs -> LuaFunctionArgsBlock(child, childWrap, childAlignment, childIndent, ctx)
            else -> createBlock(child, childIndent, childAlignment, childWrap)
        }
    }

    private fun getFunctionCallChildIndent(child: PsiElement): Indent {
        return when {
            isFunctionArguments(child) -> Indent.getContinuationIndent()
            isFunctionName(child) -> Indent.getNoneIndent()
            else -> Indent.getNoneIndent()
        }
    }

    private fun getFunctionCallChildAlignment(child: PsiElement): Alignment? {
        if (isFunctionArguments(child) && ctx.settings.ALIGN_MULTILINE_PARAMETERS_IN_CALLS) {
            if (argumentAlignment == null) {
                argumentAlignment = Alignment.createAlignment(true)
            }
            return argumentAlignment
        }
        return null
    }

    private fun getFunctionCallChildWrap(child: PsiElement): Wrap? {
        return when {
            isFunctionArguments(child) && shouldWrapArguments() -> 
                Wrap.createWrap(WrapType.NORMAL, false)
            else -> null
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // 函数名和参数之间的间距
            if (isFunctionName(psi1) && isFunctionArguments(psi2)) {
                return if (ctx.luaSettings.SPACE_BEFORE_FUNCTION_CALL_PARENTHESES) {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                } else {
                    Spacing.createSpacing(0, 0, 0, false, 0)
                }
            }

            // 方法调用中的点或冒号后的间距
            if (isMethodOperator(psi1)) {
                return Spacing.createSpacing(0, 0, 0, false, 0)
            }
            if (isMethodOperator(psi2)) {
                return Spacing.createSpacing(0, 0, 0, false, 0)
            }
        }

        return super.getSpacing(child1, child2)
    }

    private fun isFunctionArguments(element: PsiElement): Boolean {
        return element is LuaArgs
    }

    private fun isFunctionName(element: PsiElement): Boolean {
        return element is LuaNameExpr || element is LuaIndexExpr
    }

    private fun isMethodOperator(element: PsiElement): Boolean {
        val elementType = element.node?.elementType
        return elementType == LuaTypes.DOT || elementType == LuaTypes.COLON
    }

    private fun shouldWrapArguments(): Boolean {
        return ctx.settings.WRAP_LONG_LINES && hasMultipleArguments()
    }

    private fun hasMultipleArguments(): Boolean {
        if (psi is LuaCallExpr) {
            val args = psi.args
            return when (args) {
                is LuaListArgs -> args.exprList.size > 1
                is LuaSingleArg -> false
                else -> false
            }
        }
        return false
    }
}

/**
 * 函数参数格式化块
 * 专门处理函数参数的格式化
 */
class LuaFunctionArgsBlock(psi: PsiElement,
                          wrap: Wrap?,
                          alignment: Alignment?,
                          indent: Indent,
                          ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    private var parameterAlignment: Alignment? = null

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = getArgumentChildIndent(child)
        val childAlignment = getArgumentChildAlignment(child)
        val childWrap = getArgumentChildWrap(child)
        
        return createBlock(child, childIndent, childAlignment, childWrap)
    }

    private fun getArgumentChildIndent(child: PsiElement): Indent {
        return when {
            isArgument(child) -> Indent.getContinuationIndent()
            isParenthesis(child) -> Indent.getNoneIndent()
            else -> Indent.getNoneIndent()
        }
    }

    private fun getArgumentChildAlignment(child: PsiElement): Alignment? {
        if (isArgument(child) && ctx.settings.ALIGN_MULTILINE_PARAMETERS_IN_CALLS) {
            if (parameterAlignment == null) {
                parameterAlignment = Alignment.createAlignment(true)
            }
            return parameterAlignment
        }
        return null
    }

    private fun getArgumentChildWrap(child: PsiElement): Wrap? {
        return when {
            isArgument(child) && shouldWrapParameters() -> 
                Wrap.createWrap(WrapType.NORMAL, false)
            else -> null
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // 参数之间的间距
            if (isArgument(psi1) && isComma(psi2)) {
                return Spacing.createSpacing(0, 0, 0, false, 0)
            }

            // 逗号后的间距
            if (isComma(psi1) && isArgument(psi2)) {
                return if (ctx.luaSettings.SPACE_AFTER_COMMA_IN_FUNCTION_CALLS) {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                } else {
                    Spacing.createSpacing(0, 0, 0, false, 0)
                }
            }

            // 括号内的间距
            if (isOpenParenthesis(psi1) && isArgument(psi2)) {
                return if (ctx.luaSettings.SPACE_WITHIN_FUNCTION_CALL_PARENTHESES) {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                } else {
                    Spacing.createSpacing(0, 0, 0, false, 0)
                }
            }

            if (isArgument(psi1) && isCloseParenthesis(psi2)) {
                return if (ctx.luaSettings.SPACE_WITHIN_FUNCTION_CALL_PARENTHESES) {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                } else {
                    Spacing.createSpacing(0, 0, 0, false, 0)
                }
            }
        }

        return super.getSpacing(child1, child2)
    }

    private fun isArgument(element: PsiElement): Boolean {
        return element is LuaExpr
    }

    private fun isComma(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.COMMA
    }

    private fun isParenthesis(element: PsiElement): Boolean {
        val elementType = element.node?.elementType
        return elementType == LuaTypes.LPAREN || elementType == LuaTypes.RPAREN
    }

    private fun isOpenParenthesis(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.LPAREN
    }

    private fun isCloseParenthesis(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.RPAREN
    }

    private fun shouldWrapParameters(): Boolean {
        return ctx.settings.WRAP_LONG_LINES && hasMultipleParameters()
    }

    private fun hasMultipleParameters(): Boolean {
        if (psi is LuaListArgs) {
            return psi.exprList.size > 1
        }
        return false
    }
}