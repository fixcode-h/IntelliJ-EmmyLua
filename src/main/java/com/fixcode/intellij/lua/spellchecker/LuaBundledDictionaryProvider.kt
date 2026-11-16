package com.fixcode.intellij.lua.spellchecker

import com.intellij.spellchecker.BundledDictionaryProvider

/** Lua 关键字、标准库和 EmmyLua / UnLua 常用词，免得注释里写 `ipairs`、`upvalue` 被标成拼写错误。 */
class LuaBundledDictionaryProvider : BundledDictionaryProvider {
    override fun getBundledDictionaries(): Array<String> = arrayOf("/dictionaries/lua.dic")
}
