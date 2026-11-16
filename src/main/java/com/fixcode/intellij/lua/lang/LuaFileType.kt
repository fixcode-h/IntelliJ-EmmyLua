package com.fixcode.intellij.lua.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/** 文件类型名保持 `lua`：用户在「文件类型」里加的关联按名字存。 */
object LuaFileType : LanguageFileType(LuaLanguage) {
    override fun getName(): String = "lua"
    override fun getDescription(): String = "Lua"
    override fun getDefaultExtension(): String = "lua"
    override fun getIcon(): Icon = LuaIcons.FILE
}
