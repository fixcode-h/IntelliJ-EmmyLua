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
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.tang.intellij.lua.editor.formatter.LuaFormatContext
import com.intellij.openapi.util.TextRange

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
        // 处理注释符号块和内容块之间的间距
        if (child1 is CommentSymbolBlock && child2 is CommentContentBlock) {
            return Spacing.createSpacing(1, 1, 0, false, 0)
        }
        
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

    override fun isLeaf(): Boolean {
        // 如果有注释内容对齐，需要创建子块
        return commentContentAlignment == null
    }

    override fun buildChildren(): List<Block> {
        if (commentContentAlignment != null && psi is PsiComment && isLineComment(psi)) {
            // 创建注释符号和内容的子块
            val blocks = mutableListOf<Block>()
            
            // 注释符号块
            val symbolBlock = CommentSymbolBlock(psi, ctx)
            blocks.add(symbolBlock)
            
            // 注释内容块  
            val contentBlock = CommentContentBlock(psi, commentContentAlignment, ctx)
            blocks.add(contentBlock)
            
            return blocks
        }
        return super.buildChildren()
    }

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = when {
            child is PsiComment && isBlockComment(child) -> Indent.getNormalIndent()
            else -> indent ?: Indent.getNoneIndent()
        }
        
        return createBlock(child, childIndent, null)
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

    private var commentContentAlignment: Alignment? = null

    /**
     * 设置注释内容对齐
     */
    fun setCommentContentAlignment(alignment: Alignment?) {
        this.commentContentAlignment = alignment
    }

    /**
     * 获取注释的对齐位置
     */
    fun getCommentAlignment(): Alignment? {
        return if (shouldAlignComment()) {
            Alignment.createAlignment(true)
        } else null
    }

    /**
     * 获取注释内容对齐
     */
    fun getCommentContentAlignment(): Alignment? {
        return commentContentAlignment
    }
}

/**
 * 注释符号块（处理 "--" 部分）
 */
class CommentSymbolBlock(
    comment: PsiElement,
    ctx: LuaFormatContext
) : LuaScriptBlock(comment, null, null, Indent.getNoneIndent(), ctx) {

    override fun isLeaf(): Boolean = true

    override fun getTextRange(): com.intellij.openapi.util.TextRange {
        val commentText = psi.text
        val symbolEnd = if (commentText.startsWith("---")) 3 else 2
        return com.intellij.openapi.util.TextRange(
            psi.textRange.startOffset,
            psi.textRange.startOffset + symbolEnd
        )
    }
}

/**
 * 注释内容块（处理注释文本部分）
 */
class CommentContentBlock(
    comment: PsiElement,
    contentAlignment: Alignment?,
    ctx: LuaFormatContext
) : LuaScriptBlock(comment, null, contentAlignment, Indent.getNoneIndent(), ctx) {

    override fun isLeaf(): Boolean = true

    override fun getTextRange(): com.intellij.openapi.util.TextRange {
        val commentText = psi.text
        val symbolEnd = if (commentText.startsWith("---")) 3 else 2
        
        // 跳过符号后的空格
        var contentStart = psi.textRange.startOffset + symbolEnd
        val fileText = psi.containingFile.text
        while (contentStart < psi.textRange.endOffset && 
               fileText[contentStart] == ' ') {
            contentStart++
        }
        
        return com.intellij.openapi.util.TextRange(contentStart, psi.textRange.endOffset)
    }
}