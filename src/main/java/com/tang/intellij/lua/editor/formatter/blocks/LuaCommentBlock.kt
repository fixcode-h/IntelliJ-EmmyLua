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
        return super.getSpacing(child1, child2)
    }

    private fun isLineComment(comment: PsiComment): Boolean {
        return comment.text.startsWith("--") && !comment.text.startsWith("--[[")
    }

    override fun isLeaf(): Boolean {
        // 注释块通常是叶子节点，不需要创建子块
        return true
    }

    override fun buildChildren(): List<Block> {
        // 简化实现，不创建子块
        return emptyList()
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
        if (psi is PsiComment && shouldAlignComment()) {
            // 确保上下文中有注释内容对齐
            if (ctx.commentContentAlignment == null) {
                ctx.commentContentAlignment = Alignment.createAlignment(true)
            }
            return ctx.commentContentAlignment
        }
        return null
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