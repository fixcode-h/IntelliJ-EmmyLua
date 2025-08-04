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

import com.intellij.formatting.Alignment
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.editor.formatter.LuaFormatContext

/**
 * Lua注释对齐工具类
 * 提供通用的注释对齐功能，减少代码重复
 */
object LuaCommentAlignmentUtil {
    
    /**
     * 检查注释是否在行尾（同一行有代码）
     */
    fun isEndOfLineComment(comment: PsiComment, document: Document?): Boolean {
        val doc = document ?: return false
        val commentLine = doc.getLineNumber(comment.textOffset)
        val lineStartOffset = doc.getLineStartOffset(commentLine)
        val commentOffset = comment.textOffset
        
        // 检查注释前是否有非空白字符
        val textBeforeComment = comment.containingFile.text.substring(lineStartOffset, commentOffset)
        return textBeforeComment.trim().isNotEmpty()
    }
    
    /**
     * 检查注释是否与前一行的注释连续（中间没有空行）
     */
    fun isCommentContiguousWithPrevious(comment: PsiComment, document: Document?): Boolean {
        val doc = document ?: return false
        val currentLine = doc.getLineNumber(comment.textOffset)
        
        // 只检查前一行，简化逻辑并提高性能
        if (currentLine <= 0) return false
        
        val prevLine = currentLine - 1
        val lineStart = doc.getLineStartOffset(prevLine)
        val lineEnd = doc.getLineEndOffset(prevLine)
        val lineText = doc.text.substring(lineStart, lineEnd)
        
        // 如果前一行是空行，说明不连续
        if (lineText.trim().isEmpty()) {
            return false
        }
        
        // 检查前一行是否包含行尾注释
        val commentIndex = lineText.indexOf("--")
        if (commentIndex >= 0) {
            // 检查注释前是否有代码
            val codeBeforeComment = lineText.substring(0, commentIndex).trim()
            return codeBeforeComment.isNotEmpty()
        }
        
        return false
    }
    
    /**
     * 获取注释对齐对象
     */
    fun getCommentAlignment(comment: PsiComment, document: Document?, ctx: LuaFormatContext): Alignment? {
        if (!ctx.luaSettings.ALIGN_LINE_COMMENTS) return null
        if (!isEndOfLineComment(comment, document)) return null
        
        return getContiguousCommentAlignment(comment, document, ctx)
    }
    
    /**
     * 获取连续注释的对齐对象
     */
    fun getContiguousCommentAlignment(comment: PsiComment, document: Document?, ctx: LuaFormatContext): Alignment? {
        val doc = document ?: return null
        val currentLine = doc.getLineNumber(comment.textOffset)
        
        // 找到注释块的起始行
        val blockStartLine = findCommentBlockStart(comment, currentLine, doc)
        val blockSize = getCommentBlockSize(comment, blockStartLine, doc)
        
        // 只有当注释块包含多个注释时才创建对齐组
        if (blockSize <= 1) {
            return null
        }
        
        // 使用注释块起始行作为对齐组的标识
        val blockKey = "comment_block_$blockStartLine"
        
        // 检查是否已经为这个注释块创建了对齐组
        if (ctx.commentAlignment == null || ctx.currentCommentBlockKey != blockKey) {
            // 创建新的对齐组
            ctx.commentAlignment = Alignment.createAlignment(true)
            ctx.commentContentAlignment = Alignment.createAlignment(true)
            ctx.currentCommentBlockKey = blockKey
        }
        
        return ctx.commentAlignment
    }
    
    /**
     * 找到注释块的起始行
     */
    private fun findCommentBlockStart(comment: PsiComment, currentLine: Int, document: Document): Int {
        var checkLine = currentLine
        
        // 向上查找连续的行尾注释
        while (checkLine > 0) {
            val prevLine = checkLine - 1
            val lineStart = document.getLineStartOffset(prevLine)
            val lineEnd = document.getLineEndOffset(prevLine)
            val lineText = document.text.substring(lineStart, lineEnd)
            
            // 如果是空行，停止查找
            if (lineText.trim().isEmpty()) {
                break
            }
            
            // 检查这一行是否包含行尾注释
            val commentIndex = lineText.indexOf("--")
            if (commentIndex >= 0) {
                // 检查注释前是否有代码
                val codeBeforeComment = lineText.substring(0, commentIndex).trim()
                if (codeBeforeComment.isNotEmpty()) {
                    // 找到了行尾注释，继续向上查找
                    checkLine = prevLine
                    continue
                }
            }
            
            // 如果这一行没有行尾注释，停止查找
            break
        }
        
        return checkLine
    }
    
    /**
     * 获取注释块的大小（连续的行尾注释数量）
     */
    private fun getCommentBlockSize(comment: PsiComment, startLine: Int, document: Document): Int {
        var count = 0
        var checkLine = startLine
        
        // 从起始行开始向下查找连续的行尾注释
        while (checkLine < document.lineCount) {
            val lineStart = document.getLineStartOffset(checkLine)
            val lineEnd = document.getLineEndOffset(checkLine)
            val lineText = document.text.substring(lineStart, lineEnd)
            
            // 如果是空行，停止查找
            if (lineText.trim().isEmpty()) {
                break
            }
            
            // 检查这一行是否包含行尾注释
            val commentIndex = lineText.indexOf("--")
            if (commentIndex >= 0) {
                // 检查注释前是否有代码
                val codeBeforeComment = lineText.substring(0, commentIndex).trim()
                if (codeBeforeComment.isNotEmpty()) {
                    // 找到了行尾注释，计数并继续
                    count++
                    checkLine++
                    continue
                }
            }
            
            // 如果这一行没有行尾注释，停止查找
            break
        }
        
        return count
    }
    
    /**
     * 获取注释内容对齐
     */
    fun getCommentContentAlignment(comment: PsiComment, document: Document?, ctx: LuaFormatContext): Alignment? {
        // 确保注释内容对齐与注释位置对齐同步
        getContiguousCommentAlignment(comment, document, ctx) // 这会确保对齐组被创建
        return ctx.commentContentAlignment
    }
    
    /**
     * 为注释块创建对齐配置
     */
    fun createCommentBlockAlignment(
        comment: PsiComment, 
        document: Document?, 
        ctx: LuaFormatContext
    ): CommentAlignmentConfig {
        val symbolAlignment = getCommentAlignment(comment, document, ctx)
        val contentAlignment = getCommentContentAlignment(comment, document, ctx)
        
        return CommentAlignmentConfig(symbolAlignment, contentAlignment)
    }
    
    /**
     * 注释对齐配置数据类
     */
    data class CommentAlignmentConfig(
        val symbolAlignment: Alignment?,
        val contentAlignment: Alignment?
    )
}