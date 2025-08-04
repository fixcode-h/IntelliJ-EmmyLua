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
import com.tang.intellij.lua.psi.*

/**
 * 表格格式化增强块
 * 提供对Lua表格的高级格式化功能，包括对齐、换行等
 */
class LuaTableFormatBlock(psi: PsiElement,
                         wrap: Wrap?,
                         alignment: Alignment?,
                         indent: Indent,
                         ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    private var tableAlignment: Alignment? = null
    private val assignAlign: Alignment = Alignment.createAlignment(true)

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val childIndent = indent ?: getChildIndent(child)
        val childAlignment = getChildAlignment(child)
        val childWrap = getChildWrap(child)
        
        // 如果是表字段，需要特殊处理以支持等号对齐
        if (child is LuaTableField) {
            return LuaTableFieldFormatBlock(assignAlign, child, childWrap, childAlignment, childIndent, ctx)
        }
        
        // 处理注释对齐 - 复制父类的逻辑
        val finalAlignment = if (child is PsiComment && ctx.luaSettings.ALIGN_LINE_COMMENTS) {
            // 检查是否是行尾注释
            if (isEndOfLineComment(child)) {
                getCommentAlignment(child)
            } else childAlignment
        } else childAlignment
        
        val block = createBlock(child, childIndent, finalAlignment, childWrap)
        
        // 如果是注释块，设置特殊的对齐处理
        if (child is PsiComment && block is LuaCommentBlock && isEndOfLineComment(child)) {
            block.setCommentContentAlignment(getCommentContentAlignment(child))
        }
        
        return block
    }

    private fun getChildIndent(child: PsiElement): Indent {
        return when {
            isTableField(child) -> Indent.getNormalIndent()
            isTableSeparator(child) -> Indent.getNoneIndent()
            else -> Indent.getNoneIndent()
        }
    }

    private fun getChildAlignment(child: PsiElement): Alignment? {
        if (ctx.luaSettings.ALIGN_TABLE_FIELDS && isTableField(child)) {
            if (tableAlignment == null) {
                tableAlignment = Alignment.createAlignment(true)
            }
            return tableAlignment
        }
        return null
    }

    private fun getChildWrap(child: PsiElement): Wrap? {
        return when {
            isTableField(child) && ctx.luaSettings.WRAP_TABLE_FIELDS -> Wrap.createWrap(WrapType.NORMAL, false)
            else -> null
        }
    }

    private fun isTableField(element: PsiElement): Boolean {
        return element is LuaTableField
    }

    private fun isTableSeparator(element: PsiElement): Boolean {
        return element.node?.elementType == LuaTypes.COMMA || 
               element.node?.elementType == LuaTypes.SEMI
    }

    private fun isEndOfLineComment(comment: PsiComment): Boolean {
        // 检查注释是否在行尾（同一行有代码）
        val commentLine = comment.containingFile.viewProvider.document?.getLineNumber(comment.textOffset) ?: return false
        val lineStartOffset = comment.containingFile.viewProvider.document?.getLineStartOffset(commentLine) ?: return false
        val commentOffset = comment.textOffset
        
        // 检查注释前是否有非空白字符
        val textBeforeComment = comment.containingFile.text.substring(lineStartOffset, commentOffset)
        return textBeforeComment.trim().isNotEmpty()
    }

    /**
     * 获取注释对齐，只对齐连续块中的行尾注释
     */
    private fun getCommentAlignment(comment: PsiComment): Alignment? {
        // 使用上下文中的注释对齐映射来管理连续块
        return getContiguousCommentAlignment(comment)
    }

    /**
     * 获取连续块注释对齐
     */
    private fun getContiguousCommentAlignment(comment: PsiComment): Alignment? {
        // 检查是否与前一行的注释连续
        if (isCommentContiguousWithPrevious(comment)) {
            // 如果连续，使用当前的对齐组
            return ctx.commentAlignment ?: run {
                // 如果还没有对齐组，创建一个新的
                ctx.commentAlignment = Alignment.createAlignment(true)
                ctx.commentContentAlignment = Alignment.createAlignment(true)
                ctx.commentAlignment
            }
        } else {
            // 如果不连续，重置并创建新的对齐组
            ctx.commentAlignment = Alignment.createAlignment(true)
            ctx.commentContentAlignment = Alignment.createAlignment(true)
            return ctx.commentAlignment
        }
    }

    /**
     * 获取注释内容对齐
     */
    private fun getCommentContentAlignment(comment: PsiComment): Alignment? {
        // 确保注释内容对齐与注释位置对齐同步
        getContiguousCommentAlignment(comment) // 这会确保对齐组被创建
        return ctx.commentContentAlignment
    }

    /**
     * 检查注释是否与前一行的注释连续（中间没有空行）
     */
    private fun isCommentContiguousWithPrevious(comment: PsiComment): Boolean {
        val document = comment.containingFile.viewProvider.document ?: return false
        val currentLine = document.getLineNumber(comment.textOffset)
        
        // 检查前面的行，寻找最近的行尾注释
        var checkLine = currentLine - 1
        while (checkLine >= 0) {
            val lineStart = document.getLineStartOffset(checkLine)
            val lineEnd = document.getLineEndOffset(checkLine)
            val lineText = document.text.substring(lineStart, lineEnd)
            
            // 如果是空行，说明不连续
            if (lineText.trim().isEmpty()) {
                return false
            }
            
            // 检查这一行是否包含行尾注释
            val commentIndex = lineText.indexOf("--")
            if (commentIndex >= 0) {
                // 检查注释前是否有代码
                val codeBeforeComment = lineText.substring(0, commentIndex).trim()
                if (codeBeforeComment.isNotEmpty()) {
                    // 找到了前一个行尾注释，检查是否紧邻
                    return (currentLine - checkLine) == 1
                }
            }
            
            // 如果这一行有代码但没有注释，继续向上查找
            checkLine--
            
            // 只检查相邻的几行，避免过度查找
            if (currentLine - checkLine > 3) {
                break
            }
        }
        
        return false
    }



    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 is LuaScriptBlock && child2 is LuaScriptBlock) {
            val psi1 = child1.psi
            val psi2 = child2.psi

            // 表格字段之间的间距
            if (ctx.luaSettings.SPACE_BETWEEN_TABLE_FIELDS && isTableField(psi1) && isTableField(psi2)) {
                return Spacing.createSpacing(1, 1, 0, true, 0)
            }

            // 逗号后的间距
            if (ctx.luaSettings.SPACE_AFTER_COMMA_IN_TABLE && isTableSeparator(psi1) && isTableField(psi2)) {
                return Spacing.createSpacing(1, 1, 0, true, 0)
            }
        }

        return super.getSpacing(child1, child2)
    }
}

/**
 * 表格字段格式化块
 * 专门处理表格字段的等号对齐
 */
class LuaTableFieldFormatBlock(private val assignAlign: Alignment, 
                              psi: LuaTableField, 
                              wrap: Wrap?, 
                              alignment: Alignment?, 
                              indent: Indent, 
                              ctx: LuaFormatContext) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        if (ctx.luaSettings.ALIGN_TABLE_FIELD_ASSIGN) {
            if (child.node.elementType == LuaTypes.ASSIGN) {
                return createBlock(child, Indent.getNoneIndent(), assignAlign)
            }
        }
        return super.buildChild(child, indent)
    }
}