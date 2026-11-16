package com.fixcode.intellij.lua.editor

import com.intellij.lang.Commenter

class LuaCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "--"
    override fun getBlockCommentPrefix(): String = "--[["
    override fun getBlockCommentSuffix(): String = "]]"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
