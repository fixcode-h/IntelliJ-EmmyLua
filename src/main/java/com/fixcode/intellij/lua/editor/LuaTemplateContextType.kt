package com.fixcode.intellij.lua.editor

import com.fixcode.intellij.lua.lang.LuaLanguage
import com.fixcode.intellij.lua.lexer.LuaTokenTypes
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType

/** 实时模板的 Lua 上下文：Lua 文件里，不在注释和字符串中。 */
class LuaTemplateContextType : TemplateContextType("Lua") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        if (!file.language.isKindOf(LuaLanguage)) return false
        val offset = context.startOffset
        if (offset <= 0) return true
        val type = file.findElementAt(offset - 1)?.node?.elementType ?: return true
        return type !in LuaTokenTypes.COMMENTS && type !in LuaTokenTypes.STRINGS
    }
}
