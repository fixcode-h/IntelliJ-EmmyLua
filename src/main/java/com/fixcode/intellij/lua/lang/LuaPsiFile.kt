package com.fixcode.intellij.lua.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * 扁平 PSI：文件节点下直接挂 token，不建语法树。
 * 语义（引用、类型、作用域）全部由 emmylua_ls 提供，IDE 这边只要平台能把文件当成 Lua 处理就够了
 * （注释切换、括号匹配、代码片段编辑器、断点）。
 */
class LuaPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, LuaLanguage) {
    override fun getFileType(): FileType = LuaFileType
    override fun toString(): String = "Lua file"
}
