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
 * require语句管理块
 * 改善项目结构，提供require语句的格式化和组织功能
 */
class LuaRequireBlock(psi: PsiElement,
                     wrap: Wrap?,
                     alignment: Alignment?,
                     indent: Indent,
                     ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // require语句之间的间距
            if (isRequireStatement(psi1) && isRequireStatement(psi2)) {
                return Spacing.createSpacing(0, 0, 1, true, 1)
            }

            // require语句块后的空行
            if (isRequireStatement(psi1) && !isRequireStatement(psi2)) {
                return Spacing.createSpacing(0, 0, ctx.luaSettings.BLANK_LINES_AFTER_REQUIRE_BLOCK + 1, true, 0)
            }

            // require语句内部的间距
            if (isInRequireStatement(psi1, psi2)) {
                return getRequireInternalSpacing(psi1, psi2)
            }
        }

        return super.getSpacing(child1, child2)
    }

    private fun isRequireStatement(element: PsiElement): Boolean {
        return when (element) {
            is LuaLocalDef -> {
                // 检查是否是 local xxx = require("xxx") 形式
                val exprList = element.exprList
                exprList?.exprList?.any { expr ->
                    isRequireCall(expr)
                } ?: false
            }
            is LuaAssignStat -> {
                // 检查是否是 xxx = require("xxx") 形式
                element.valueExprList?.exprList?.any { expr ->
                    isRequireCall(expr)
                } ?: false
            }
            is LuaExprStat -> {
                // 检查是否是直接的 require("xxx") 调用
                isRequireCall(element.expr)
            }
            else -> false
        }
    }

    private fun isRequireCall(expr: LuaExpr?): Boolean {
        return when (expr) {
            is LuaCallExpr -> {
                val prefixExpr = expr.expr
                prefixExpr is LuaNameExpr && prefixExpr.name == "require"
            }
            else -> false
        }
    }

    private fun isInRequireStatement(psi1: PsiElement, psi2: PsiElement): Boolean {
        val parent1 = findRequireStatementParent(psi1)
        val parent2 = findRequireStatementParent(psi2)
        return parent1 != null && parent1 == parent2
    }

    private fun findRequireStatementParent(element: PsiElement): PsiElement? {
        var current = element.parent
        while (current != null) {
            if (isRequireStatement(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun getRequireInternalSpacing(psi1: PsiElement, psi2: PsiElement): Spacing? {
        // require语句内部的具体间距规则
        return when {
            // 等号前后的间距
            psi1.node?.elementType == LuaTypes.ASSIGN && ctx.settings.SPACE_AROUND_ASSIGNMENT_OPERATORS -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            psi2.node?.elementType == LuaTypes.ASSIGN && ctx.settings.SPACE_AROUND_ASSIGNMENT_OPERATORS -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            // 逗号后的间距
            psi1.node?.elementType == LuaTypes.COMMA && ctx.settings.SPACE_AFTER_COMMA -> {
                Spacing.createSpacing(1, 1, 0, false, 0)
            }
            else -> null
        }
    }

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = when {
            isRequireStatement(child) -> Indent.getNoneIndent()
            else -> indent ?: Indent.getNoneIndent()
        }
        
        return createBlock(child, childIndent)
    }

    /**
     * 检查是否是require语句块的开始
     */
    fun isRequireBlockStart(): Boolean {
        val prevBlock = getPrevSkipComment()
        return isRequireStatement(psi) && (prevBlock == null || !isRequireStatement(prevBlock.psi))
    }

    /**
     * 检查是否是require语句块的结束
     */
    fun isRequireBlockEnd(): Boolean {
        val nextBlock = getNextSkipComment()
        return isRequireStatement(psi) && (nextBlock == null || !isRequireStatement(nextBlock.psi))
    }
}