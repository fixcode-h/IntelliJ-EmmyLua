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
import com.intellij.formatting.Indent
import com.intellij.formatting.Wrap
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.tang.intellij.lua.editor.formatter.LuaFormatContext
import com.tang.intellij.lua.editor.formatter.LuaCodeStyleSettings
import com.tang.intellij.lua.psi.LuaAssignStat
import com.tang.intellij.lua.psi.LuaLocalDef
import com.tang.intellij.lua.psi.LuaTypes

class LuaAssignBlock(psi: PsiElement, wrap: Wrap?, alignment: Alignment?, indent: Indent, ctx: LuaFormatContext)
    : LuaScriptBlock(psi, wrap, alignment, indent, ctx)  {

    private val _assignAlign = Alignment.createAlignment(true)

    private val assignAlign:Alignment? get() {
        val alignmentOption = LuaCodeStyleSettings.VariableAlignmentOption.fromValue(ctx.luaSettings.VARIABLE_ALIGNMENT_OPTION)
        
        when (alignmentOption) {
            LuaCodeStyleSettings.VariableAlignmentOption.DO_NOT_ALIGN -> return null
            LuaCodeStyleSettings.VariableAlignmentOption.ALIGN_ALL -> {
                val prev = getPrevSkipComment()
                return if (prev is LuaAssignBlock) prev.assignAlign else _assignAlign
            }
            LuaCodeStyleSettings.VariableAlignmentOption.ALIGN_CONTIGUOUS_BLOCKS -> {
                return getContiguousBlockAlignment()
            }
        }
    }

    /**
     * Get alignment for contiguous blocks mode.
     * Only align with previous assignment if they are in the same contiguous block
     * (no empty lines between them).
     */
    private fun getContiguousBlockAlignment(): Alignment? {
        val prev = getPrevSkipComment()
        if (prev is LuaAssignBlock) {
            // Check if there are empty lines between current and previous assignment
            if (isContiguousWithPrevious()) {
                return prev.assignAlign
            }
        }
        return _assignAlign
    }

    /**
     * Check if current assignment is contiguous with the previous one
     * (no empty lines between them).
     */
    private fun isContiguousWithPrevious(): Boolean {
        var current: PsiElement? = psi.prevSibling
        
        while (current != null) {
            when {
                // Found another assignment statement
                current is LuaAssignStat || current is LuaLocalDef -> return true
                current is PsiWhiteSpace -> {
                    // Count newlines in whitespace
                    val newlineCount = current.text.count { it == '\n' }
                    if (newlineCount > 1) {
                        // More than one newline means there's an empty line
                        return false
                    }
                }
                // Skip comments
                current is PsiComment -> {
                    // Continue searching
                }
                current.text.trim().isEmpty() -> {
                    // Skip empty elements
                }
                else -> {
                    // Found non-assignment, non-whitespace, non-comment element
                    return false
                }
            }
            current = current.prevSibling
        }
        
        return false
    }

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        val alignmentOption = LuaCodeStyleSettings.VariableAlignmentOption.fromValue(ctx.luaSettings.VARIABLE_ALIGNMENT_OPTION)
        
        if (alignmentOption != LuaCodeStyleSettings.VariableAlignmentOption.DO_NOT_ALIGN && 
            child.node.elementType == LuaTypes.ASSIGN) {
            return createBlock(child, Indent.getContinuationIndent(), assignAlign)
        }
        return super.buildChild(child, indent)
    }
}