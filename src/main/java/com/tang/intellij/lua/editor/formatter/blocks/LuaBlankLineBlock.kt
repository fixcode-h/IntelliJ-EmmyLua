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
 * 空行管理块
 * 让代码更整洁，管理各种语句之间的空行
 */
class LuaBlankLineBlock(psi: PsiElement,
                       wrap: Wrap?,
                       alignment: Alignment?,
                       indent: Indent,
                       ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // 函数前的空行
            if (isFunctionDefinition(psi2)) {
                return Spacing.createSpacing(0, 0, ctx.luaSettings.BLANK_LINES_BEFORE_FUNCTION + 1, true, 0)
            }

            // 函数后的空行
            if (isFunctionDefinition(psi1)) {
                return Spacing.createSpacing(0, 0, ctx.luaSettings.BLANK_LINES_AFTER_FUNCTION + 1, true, 0)
            }

            // 类定义前后的空行
            val blankLinesAroundClass = getBlankLinesAroundClass(psi1, psi2)
            if (blankLinesAroundClass != null) return blankLinesAroundClass

            // 控制结构前后的空行
            val blankLinesAroundControl = getBlankLinesAroundControlStructure(psi1, psi2)
            if (blankLinesAroundControl != null) return blankLinesAroundControl

            // 变量声明组之间的空行
            val blankLinesBetweenVarGroups = getBlankLinesBetweenVariableGroups(psi1, psi2)
            if (blankLinesBetweenVarGroups != null) return blankLinesBetweenVarGroups
        }

        return super.getSpacing(child1, child2)
    }

    private fun getBlankLinesBeforeFunction(psi1: PsiElement, psi2: PsiElement): Spacing? {
        if (isFunctionDefinition(psi2)) {
            val blankLines = ctx.luaSettings.BLANK_LINES_BEFORE_FUNCTION
            if (blankLines > 0) {
                return Spacing.createSpacing(0, 0, blankLines + 1, true, blankLines + 1)
            }
        }
        return null
    }

    private fun getBlankLinesAfterFunction(psi1: PsiElement, psi2: PsiElement): Spacing? {
        if (isFunctionDefinition(psi1)) {
            val blankLines = ctx.luaSettings.BLANK_LINES_AFTER_FUNCTION
            if (blankLines > 0) {
                return Spacing.createSpacing(0, 0, blankLines + 1, true, blankLines + 1)
            }
        }
        return null
    }

    private fun getBlankLinesAroundClass(psi1: PsiElement, psi2: PsiElement): Spacing? {
        // Lua中的"类"通常是表定义
        if (isClassLikeTable(psi2)) {
            return Spacing.createSpacing(0, 0, 2, true, 2)
        }
        if (isClassLikeTable(psi1)) {
            return Spacing.createSpacing(0, 0, 2, true, 2)
        }
        return null
    }

    private fun getBlankLinesAroundControlStructure(psi1: PsiElement, psi2: PsiElement): Spacing? {
        if (isControlStructure(psi2) && !isControlStructure(psi1)) {
            return Spacing.createSpacing(0, 0, 1, true, 1)
        }
        if (isControlStructure(psi1) && !isControlStructure(psi2)) {
            return Spacing.createSpacing(0, 0, 1, true, 1)
        }
        return null
    }

    private fun getBlankLinesBetweenVariableGroups(psi1: PsiElement, psi2: PsiElement): Spacing? {
        if (isVariableDeclaration(psi1) && !isVariableDeclaration(psi2)) {
            return Spacing.createSpacing(0, 0, 1, true, 1)
        }
        return null
    }

    private fun isFunctionDefinition(element: PsiElement): Boolean {
        return element is LuaFuncDef || element is LuaLocalFuncDef
    }

    private fun isClassLikeTable(element: PsiElement): Boolean {
        // 检查是否是类似类的表定义（包含多个函数的表）
        if (element is LuaAssignStat) {
            val valueExpr = element.valueExprList?.exprList?.firstOrNull()
            if (valueExpr is LuaTableExpr) {
                val fieldCount = valueExpr.tableFieldList.size
                val functionFieldCount = valueExpr.tableFieldList.count { field ->
                    field.valueExpr is LuaClosureExpr
                }
                return fieldCount > 3 && functionFieldCount > 1
            }
        }
        return false
    }

    private fun isControlStructure(element: PsiElement): Boolean {
        return element is LuaIfStat || 
               element is LuaWhileStat || 
               element is LuaForAStat || 
               element is LuaForBStat || 
               element is LuaRepeatStat ||
               element is LuaDoStat
    }

    private fun isVariableDeclaration(element: PsiElement): Boolean {
        return element is LuaLocalDef || 
               (element is LuaAssignStat && !isClassLikeTable(element))
    }

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = indent ?: Indent.getNoneIndent()
        return createBlock(child, childIndent)
    }

    /**
     * 检查是否需要在当前位置添加空行
     */
    fun shouldAddBlankLine(nextElement: PsiElement?): Boolean {
        if (nextElement == null) return false
        
        return when {
            isFunctionDefinition(nextElement) -> ctx.luaSettings.BLANK_LINES_BEFORE_FUNCTION > 0
            isClassLikeTable(nextElement) -> true
            isControlStructure(nextElement) -> true
            else -> false
        }
    }

    /**
     * 获取应该添加的空行数量
     */
    fun getBlankLineCount(nextElement: PsiElement?): Int {
        if (nextElement == null) return 0
        
        return when {
            isFunctionDefinition(nextElement) -> ctx.luaSettings.BLANK_LINES_BEFORE_FUNCTION
            isClassLikeTable(nextElement) -> 1
            isControlStructure(nextElement) -> 1
            else -> 0
        }
    }
}