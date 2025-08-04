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
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.tang.intellij.lua.editor.formatter.LuaFormatContext
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.LuaTypes.*
import java.util.*

/**

 * Created by tangzx on 2016/12/3.
 */
open class LuaScriptBlock(val psi: PsiElement,
                          wrap: Wrap?,
                          private val alignment: Alignment?,
                          private val indent: Indent,
                          val ctx: LuaFormatContext) : AbstractBlock(psi.node, wrap, alignment) {

    companion object {
        //不创建 ASTBlock
        private val fakeBlockSet = TokenSet.create(BLOCK)

        //回车时
        private val childAttrSet = TokenSet.orSet(fakeBlockSet, TokenSet.create(
                IF_STAT,
                DO_STAT,
                FUNC_BODY,
                FOR_A_STAT,
                FOR_B_STAT,
                REPEAT_STAT,
                WHILE_STAT,
                TABLE_EXPR,
                ARGS
        ))
    }

    protected var childBlocks:List<LuaScriptBlock>? = null
    val elementType: IElementType = node.elementType

    private var next: LuaScriptBlock? = null
    private var prev: LuaScriptBlock? = null

    val nextBlock get() = next
    val prevBlock get() = prev

    protected fun getPrevSkipComment(): LuaScriptBlock? =
            if (prev?.psi is PsiComment) prev?.getPrevSkipComment() else prev

    protected fun getNextSkipComment(): LuaScriptBlock? =
            if (next?.psi is PsiComment) next?.getNextSkipComment() else next

    private var parent: LuaScriptBlock? = null
    val parentBlock get() = parent

    private fun shouldCreateBlockFor(node: ASTNode) =
            node.textRange.length != 0 && node.elementType !== TokenType.WHITE_SPACE

    override fun buildChildren(): List<Block> {
        if (childBlocks == null) {
            val blocks = ArrayList<LuaScriptBlock>()
            buildChildren(myNode.psi, blocks)
            childBlocks = blocks
            var prev: LuaScriptBlock? = null
            blocks.forEach {
                it.prev = prev
                prev?.next = it
                prev = it
            }
            postBuildChildren(blocks)
        }
        return childBlocks!!
    }

    protected open fun postBuildChildren(children: List<LuaScriptBlock>) {

    }

    private fun buildChildren(parent: PsiElement, results: MutableList<LuaScriptBlock>) {
        LuaPsiTreeUtil.processChildren(parent) { child ->
            val childType = child.node.elementType
            if (fakeBlockSet.contains(childType)) {
                LuaPsiTreeUtil.processChildren(child) {
                    if (shouldCreateBlockFor(it.node))
                        results.add(buildChild(it, Indent.getNormalIndent()))
                    true
                }
            } else if (shouldCreateBlockFor(child.node)) {
                results.add(buildChild(child))
            }
            true
        }
    }

    protected open fun buildChild(child:PsiElement, indent: Indent? = null): LuaScriptBlock {
        val childIndent = indent ?: Indent.getNoneIndent()
        
        // 处理注释对齐 - 只对齐连续块中的行尾注释
        val alignment = if (child is PsiComment && ctx.luaSettings.ALIGN_LINE_COMMENTS) {
            // 检查是否是行尾注释
            if (isEndOfLineComment(child)) {
                getCommentAlignment(child)
            } else null
        } else null
        
        val block = createBlock(child, childIndent, alignment)
        
        // 如果是注释块，设置特殊的对齐处理
        if (child is PsiComment && block is LuaCommentBlock && isEndOfLineComment(child)) {
            block.setCommentContentAlignment(getCommentContentAlignment(child))
        }
        
        return block
    }

    protected fun createBlock(element: PsiElement, childIndent: Indent, alignment: Alignment? = null, wrap: Wrap? = null): LuaScriptBlock {
        val block = when (element) {
            is LuaUnaryExpr -> LuaUnaryExprBlock(element, wrap, alignment, childIndent, ctx)
            is LuaBinaryExpr -> LuaBinaryExprBlock(element, wrap, alignment, childIndent, ctx)
            is LuaParenExpr -> LuaParenExprBlock(element, wrap, alignment, childIndent, ctx)
            is LuaListArgs -> LuaListArgsBlock(element, wrap, alignment, childIndent, ctx)
            is LuaFuncBody -> LuaFuncBodyBlock(element, wrap, alignment, childIndent, ctx)
            is LuaTableExpr -> LuaTableFormatBlock(element, wrap, alignment, childIndent, ctx)
            is LuaCallExpr -> LuaFunctionCallBlock(element, wrap, alignment, childIndent, ctx)
            is LuaIndentRange -> LuaIndentBlock(element, wrap, alignment, childIndent, ctx)
            is LuaIndexExpr -> LuaIndexExprBlock(element, wrap, alignment, childIndent, ctx)
            is LuaAssignStat,
            is LuaLocalDef -> {
                // 检查是否是require语句
                if (isRequireStatement(element)) {
                    LuaRequireBlock(element, wrap, alignment, childIndent, ctx)
                } else {
                    LuaAssignBlock(element, wrap, alignment, childIndent, ctx)
                }
            }
            // 循环语句的特殊处理
            is LuaForAStat, is LuaForBStat, is LuaWhileStat, is LuaRepeatStat -> 
                LuaLoopBlock(element, wrap, alignment, childIndent, ctx)
            // 注释的特殊处理
            is PsiComment -> LuaCommentBlock(element, wrap, alignment, childIndent, ctx)
            // 空行管理
            else -> {
                if (shouldUseBlankLineBlock(element)) {
                    LuaBlankLineBlock(element, wrap, alignment, childIndent, ctx)
                } else {
                    LuaScriptBlock(element, wrap, alignment, childIndent, ctx)
                }
            }
        }
        block.parent = this
        return block
    }

    private fun isRequireStatement(element: PsiElement): Boolean {
        return when (element) {
            is LuaLocalDef -> {
                val exprList = element.exprList
                exprList?.exprList?.any { expr ->
                    isRequireCall(expr)
                } ?: false
            }
            is LuaAssignStat -> {
                element.valueExprList?.exprList?.any { expr ->
                    isRequireCall(expr)
                } ?: false
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

    private fun shouldUseBlankLineBlock(element: PsiElement): Boolean {
        // 对于函数定义、类定义等需要特殊空行处理的元素
        return element is LuaFuncDef || element is LuaLocalFuncDef ||
               isClassLikeTable(element) || isControlStructure(element)
    }

    private fun isClassLikeTable(element: PsiElement): Boolean {
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
        return element is LuaIfStat || element is LuaDoStat
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
        if ((child1 is LuaScriptBlock && child1.psi is LuaStatement) &&
                (child2 is LuaScriptBlock && child2.psi is LuaStatement)) {
            return Spacing.createSpacing(1, 0, 1, true, 1)
        }
        return ctx.spaceBuilder.getSpacing(this, child1, child2)
    }

    override fun getAlignment() = alignment

    override fun isLeaf() = myNode.firstChildNode == null

    override fun getIndent() = indent

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        if (childAttrSet.contains(elementType))
            return ChildAttributes(Indent.getNormalIndent(), null)
        return ChildAttributes(Indent.getNoneIndent(), null)
    }
}
