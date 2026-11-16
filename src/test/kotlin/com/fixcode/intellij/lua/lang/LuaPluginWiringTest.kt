package com.fixcode.intellij.lua.lang

import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.intellij.codeInsight.generation.actions.CommentByLineCommentAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.LanguageServersRegistry

/** 插件在真 IDE 里装配正确：文件类型、扁平 PSI、注释、括号，以及 emmylua_ls 已登记到 LSP4IJ。 */
class LuaPluginWiringTest : BasePlatformTestCase() {
    fun testLuaFileUsesFlatPsi() {
        val file = myFixture.configureByText("a.lua", "local t = { x = 1 }\nreturn t\n")
        assertEquals(LuaFileType, file.fileType)
        assertInstanceOf(file, LuaPsiFile::class.java)
        // 扁平：文件下直接是 token，没有语句节点
        assertTrue(file.node.getChildren(null).all { it.firstChildNode == null })
    }

    fun testLineComment() {
        myFixture.configureByText("a.lua", "<caret>print(1)\n")
        CommentByLineCommentAction().actionPerformedImpl(project, myFixture.editor)
        myFixture.checkResult("--print(1)\n")
    }

    fun testBraceAutoClose() {
        myFixture.configureByText("a.lua", "local t = <caret>")
        myFixture.type("{")
        myFixture.checkResult("local t = {<caret>}")
    }

    fun testEmmyLuaRegisteredWithLsp4ij() {
        val registry = LanguageServersRegistry.getInstance()
        assertNotNull(registry.getServerDefinition(EmmyLuaServer.ID))
        val file = myFixture.configureByText("b.lua", "")
        assertTrue(registry.isFileSupported(file))
    }
}
