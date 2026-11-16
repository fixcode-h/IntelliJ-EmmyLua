package com.fixcode.intellij.lua.spellchecker

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.spellchecker.BundledDictionaryProvider
import com.intellij.spellchecker.SpellCheckerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Lua 词典已注册，Lua 常用词不算拼写错误。 */
class LuaDictionaryTest : BasePlatformTestCase() {
    fun `test 词典已注册且能读到`() {
        val ep = ExtensionPointName<BundledDictionaryProvider>("com.intellij.spellchecker.bundledDictionaryProvider")
        val provider = ep.extensionList.filterIsInstance<LuaBundledDictionaryProvider>().single()
        val words = provider.getBundledDictionaries().flatMap { path: String ->
            provider.javaClass.getResourceAsStream(path)!!.bufferedReader().readLines()
        }
        assertTrue("ipairs" in words && "upvalue" in words)
    }

    fun `test Lua 常用词不算拼错`() {
        val manager = SpellCheckerManager.getInstance(project)
        for (word in listOf("ipairs", "upvalue", "metatable", "emmylua", "luajit")) {
            assertFalse(word, manager.hasProblem(word))
        }
    }
}
