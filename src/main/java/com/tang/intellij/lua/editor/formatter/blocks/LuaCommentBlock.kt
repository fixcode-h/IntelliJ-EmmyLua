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
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.editor.formatter.LuaFormatContext

/**
 * 注释对齐块
 * 提供注释的对齐和格式化功能，提升代码可读性
 */
class LuaCommentBlock(psi: PsiElement,
                     wrap: Wrap?,
                     alignment: Alignment?,
                     indent: Indent,
                     ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // 行尾注释的处理
            if (psi2 is PsiComment && isLineComment(psi2)) {
                return if (ctx.luaSettings.SPACE_BEFORE_LINE_COMMENT) {
                    Spacing.createSpacing(2, Int.MAX_VALUE, 0, false, 0)
                } else {
                    Spacing.createSpacing(1, 1, 0, false, 0)
                }
            }

            // 块注释的处理
            if (psi1 is PsiComment && psi2 is PsiComment) {
                return Spacing.createSpacing(0, 0, 1, true, 1)
            }

            // 注释后的代码
            if (psi1 is PsiComment && !isLineComment(psi1)) {
                return Spacing.createSpacing(0, 0, 1, true, 1)
            }
        }

        return super.getSpacing(child1, child2)
    }

    private fun isLineComment(comment: PsiComment): Boolean {
        return comment.text.startsWith("--") && !comment.text.startsWith("--[[")
    }

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = when {
            child is PsiComment && isBlockComment(child) -> Indent.getNormalIndent()
            else -> indent ?: Indent.getNoneIndent()
        }
        
        return createBlock(child, childIndent)
    }

    private fun isBlockComment(comment: PsiComment): Boolean {
        return comment.text.startsWith("--[[")
    }

    /**
     * 检查是否应该对注释进行特殊处理
     */
    fun shouldAlignComment(): Boolean {
        return psi is PsiComment && ctx.luaSettings.ALIGN_LINE_COMMENTS
    }

    /**
     * 获取注释的对齐位置
     */
    fun getCommentAlignment(): Alignment? {
        return if (shouldAlignComment()) {
            Alignment.createAlignment(true)
        } else null
    }
}