package com.fixcode.intellij.lua.lang

import com.intellij.lang.Language

/** 语言 ID 保持 `Lua`：用户的代码风格、实时模板、语言注入设置都按它存。 */
object LuaLanguage : Language("Lua") {
    private fun readResolve(): Any = LuaLanguage
    override fun getDisplayName(): String = "Lua"
    override fun isCaseSensitive(): Boolean = true
}
