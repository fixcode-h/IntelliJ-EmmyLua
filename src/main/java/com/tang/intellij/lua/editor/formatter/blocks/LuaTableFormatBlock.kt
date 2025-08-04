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
        
        return createBlock(child, childIndent, childAlignment, childWrap)
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