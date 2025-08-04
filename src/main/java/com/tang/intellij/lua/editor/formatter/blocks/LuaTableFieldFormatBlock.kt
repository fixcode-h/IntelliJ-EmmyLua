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
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.editor.formatter.LuaFormatContext
import com.tang.intellij.lua.psi.LuaTableField
import com.tang.intellij.lua.psi.LuaTypes

/**
 * 表格字段格式化块
 * 专门处理表格字段的等号对齐
 * 
 * 功能：
 * - 支持表格字段中等号的对齐
 * - 继承基础的格式化功能
 * - 与表格格式化块配合工作
 */
class LuaTableFieldFormatBlock(
    private val assignAlign: Alignment, 
    psi: LuaTableField, 
    wrap: Wrap?, 
    alignment: Alignment?, 
    indent: Indent, 
    ctx: LuaFormatContext
) : LuaScriptBlock(psi, wrap, alignment, indent, ctx) {

    override fun buildChild(child: PsiElement, indent: Indent?): LuaScriptBlock {
        // 如果启用了表格字段赋值对齐，并且当前子元素是赋值符号
        if (ctx.luaSettings.ALIGN_TABLE_FIELD_ASSIGN && child.node.elementType == LuaTypes.ASSIGN) {
            return createBlock(child, Indent.getNoneIndent(), assignAlign)
        }
        
        // 其他情况使用父类的默认处理
        return super.buildChild(child, indent)
    }
}