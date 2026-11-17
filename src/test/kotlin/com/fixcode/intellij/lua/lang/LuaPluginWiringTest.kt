package com.fixcode.intellij.lua.lang

import com.fixcode.intellij.lua.lsp.EmmyLuaServer
import com.fixcode.intellij.lua.highlighting.LuaColorSettingsPage
import com.fixcode.intellij.lua.highlighting.LuaHighlightingData
import com.intellij.codeInsight.generation.actions.CommentByLineCommentAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Color
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.fixcode.intellij.lua.structure.LuaOutlineElement
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

    /** 旧版的配色方案已经装上：Darcula 下成员变量、函数调用还是原来的颜色。 */
    fun testOldColorSchemesLoaded() {
        val darcula = EditorColorsManager.getInstance().getScheme("Darcula")
        assertNotNull("测试环境里应有 Darcula 配色方案", darcula)
        assertEquals(Color(0x87CEEB), darcula!!.getAttributes(LuaHighlightingData.FIELD)?.foregroundColor)
        assertEquals(Color(0xA4F65E), darcula!!.getAttributes(LuaHighlightingData.FUNCTION_CALL)?.foregroundColor)
        assertEquals(Color(0xCC542E), darcula!!.getAttributes(LuaHighlightingData.STD_API)?.foregroundColor)
    }

    fun testColorSettingsPageDemoTagsKnown() {
        val page = LuaColorSettingsPage()
        val tags = Regex("<(\\w+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), tags - page.additionalHighlightingTagToDescriptorMap.keys)
    }

    /** 结构窗口用词法大纲，不等语言服务器。 */
    fun testStructureViewListsDeclarations() {
        val file = myFixture.configureByText("s.lua", "local M = {}\nfunction M:Foo(a) end\nlocal function bar() end\nreturn M\n")
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            val top = model.root.children.map { it.presentation.presentableText }
            assertEquals(listOf("M", "bar()"), top)
            val m = model.root.children.first() as LuaOutlineElement
            assertEquals(listOf("Foo(a)"), m.children.map { it.presentation.presentableText })
        } finally {
            model.dispose()
        }
    }
}
