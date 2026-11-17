package com.fixcode.intellij.lua.debug.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.fixcode.intellij.lua.debug.api.LuaLocalProcess
import com.fixcode.intellij.lua.debug.api.LuaSymbolReport
import com.fixcode.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import com.fixcode.intellij.lua.debug.ui.process.UnrealProcessTagProvider
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class DebugUiTest : BasePlatformTestCase() {

    fun testFramesLocationFit() {
        val w: (String) -> Int = { it.length }
        val fit = com.fixcode.intellij.lua.debug.ui.session.LuaFramesListLayout::fit
        assertEquals("  a/b/c.lua:3", fit("  a/b/c.lua:3", 100, w))
        assertEquals("  …/c.lua:3", fit("  a/b/c.lua:3", 11, w))
        assertEquals("  …c.lua:3", fit("  a/b/c.lua:3", 10, w))
        assertNull("连文件名都放不下就不省略", fit("  a/b/c.lua:3", 5, w))
    }

    fun testFramesLocationRightAligned() {
        val list = com.intellij.ui.components.JBList(listOf("f"))
        list.setSize(600, 100)
        val renderer = object : com.intellij.ui.ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(l: javax.swing.JList<out String>, value: String, index: Int, selected: Boolean, focus: Boolean) {
                append("main.update", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  Scripts/Game/Main.lua:12", com.intellij.ui.SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
        }
        val c = renderer.getListCellRendererComponent(list, "f", 0, false, false)
        com.fixcode.intellij.lua.debug.ui.session.LuaFramesListLayout.alignLocation(list, c)
        assertTrue("右对齐后首选宽度撑到列表右边", renderer.preferredSize.width >= 500)
        assertEquals("  Scripts/Game/Main.lua:12", renderer.getCharSequence(false).toString().removePrefix("main.update"))

        list.setSize(renderer.getFontMetrics(renderer.font).stringWidth("main.update  …Main.lua:12") + 40, 100)
        val c2 = renderer.getListCellRendererComponent(list, "f", 0, false, false)
        com.fixcode.intellij.lua.debug.ui.session.LuaFramesListLayout.alignLocation(list, c2)
        val text = renderer.getCharSequence(false).toString()
        assertTrue("窄的时候从左边省略路径: $text", text.startsWith("main.update  …") && text.endsWith("Main.lua:12"))
    }

    /** 2025.3 起帧在前端由序列化的样式重建（FrontendXStackFrame），灰色不再是 GRAY_ATTRIBUTES 本身，也要右对齐。 */
    fun testFramesLocationRightAlignedWithRebuiltAttributes() {
        val list = com.intellij.ui.components.JBList(listOf("f"))
        list.setSize(600, 100)
        val rebuilt = com.intellij.ui.SimpleTextAttributes(com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN, java.awt.Color(0x80, 0x80, 0x80))
        val renderer = object : com.intellij.ui.ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(l: javax.swing.JList<out String>, value: String, index: Int, selected: Boolean, focus: Boolean) {
                append("M:ReceiveBeginPlay", com.intellij.ui.SimpleTextAttributes(com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN, null))
                append("  Player/BP_PlayerCharacter_C.lua:32", rebuilt)
            }
        }
        val c = renderer.getListCellRendererComponent(list, "f", 0, false, false)
        com.fixcode.intellij.lua.debug.ui.session.LuaFramesListLayout.alignLocation(list, c)
        assertTrue("重建的样式也右对齐", renderer.preferredSize.width >= 500)
    }

    fun testParseTasklistCsv() {
        val out = """
            "UnrealEditor.exe","1234","lua54.dll,luasocket.dll"
            "luacheck.exe","2000","luacheck.dll"
            "MyGame.exe","3456","LuaJIT.dll"
        """.trimIndent()
        val result = LuaLocalProcessScanner.parseTasklistCsv(out)
        assertEquals(listOf("lua54.dll"), result[1234])
        assertNull("luacheck 不是 Lua 运行时", result[2000])
        assertEquals(listOf("LuaJIT.dll"), result[3456])
    }

    fun testDetectArchFromPeHeader() {
        val file = File.createTempFile("fake", ".exe")
        try {
            val bytes = ByteArray(0x100)
            bytes[0x3C] = 0x80.toByte() // e_lfanew = 0x80
            "PE\u0000\u0000".forEachIndexed { i, c -> bytes[0x80 + i] = c.code.toByte() }
            bytes[0x84] = 0x64; bytes[0x85] = 0x86.toByte() // IMAGE_FILE_MACHINE_AMD64
            file.writeBytes(bytes)
            assertEquals("x64", LuaLocalProcessScanner.detectArch(file.path))
        } finally {
            file.delete()
        }
    }

    fun testUnrealTags() {
        val provider = UnrealProcessTagProvider()
        fun proc(name: String, path: String?, cmd: String? = null) = LuaLocalProcess(1, name, path, cmd, "x64")
        assertTrue(provider.isLikelyLuaHost(proc("UnrealEditor.exe", "D:/UE_5.4/Engine/Binaries/Win64/UnrealEditor.exe")))
        assertTrue(provider.isLikelyLuaHost(proc("MyGame.exe", "D:/Build/MyGame/Binaries/Win64/MyGame.exe")))
        assertFalse(provider.isLikelyLuaHost(proc("ShaderCompileWorker.exe", "D:/UE/Engine/Binaries/Win64/ShaderCompileWorker.exe")))
        assertFalse(provider.isLikelyLuaHost(proc("chrome.exe", "C:/Program Files/Google/chrome.exe")))
        assertEquals(
            listOf(LuaDebugBundle.message("tag.ue.server")),
            provider.tags(proc("UnrealEditor.exe", null, "UnrealEditor.exe Game.uproject -server")),
        )
    }

    fun testSymbolReportBuildIdMatch() {
        assertNull(LuaSymbolReport("a.so", "abc").buildIdMatches)
        assertEquals(true, LuaSymbolReport("a.so", "ABC", deviceBuildId = "abc").buildIdMatches)
        assertEquals(false, LuaSymbolReport("a.so", "abc", deviceBuildId = "abd").buildIdMatches)
    }

    /** emmylua-debug.xml 在 P8 前不被 plugin.xml 引用，这里至少保证里面写的类都存在。 */
    fun testDebugXmlClassesExist() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/emmylua-debug.xml")!!
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val all = doc.getElementsByTagName("*")
        val missing = mutableListOf<String>()
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            for (attr in listOf("implementation", "class", "instance", "interface")) {
                val name = e.getAttribute(attr).takeIf { it.isNotEmpty() } ?: continue
                try {
                    Class.forName(name, false, javaClass.classLoader)
                } catch (_: ClassNotFoundException) {
                    missing += name
                }
            }
        }
        assertEmpty(missing)
    }

    /** 中英文案的 key 必须一致，缺一边就会在另一种语言下显示 key。 */
    fun testBundlesHaveSameKeys() {
        fun keys(name: String) = Properties().apply {
            DebugUiTest::class.java.classLoader.getResourceAsStream(name)!!.reader(Charsets.UTF_8).use { load(it) }
        }.stringPropertyNames()
        assertEquals(keys("messages/LuaDebugBundle.properties"), keys("messages/LuaDebugBundle_zh_CN.properties"))
    }
}

/**
 * 加载 emmylua-debug.xml 后的冒烟测试：运行配置注册、设置页和两个配置页能建、能存取。
 * P8 前这个文件不被 plugin.xml 引用，测试自动跳过；本地在 plugin.xml 临时加上 xi:include 即可跑。
 */
class DebugUiSmokeTest : BasePlatformTestCase() {
    fun testRegistered() {
        val types = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.map { it.id }
        if ("EmmyLua.Attach" !in types) return
        assertTrue(types.toString(), "EmmyLua.Device" in types)
        val cfg = com.fixcode.intellij.lua.debug.ui.settings.LuaDebugConfigurable()
        val c = cfg.createComponent()
        assertNotNull(c)
        cfg.reset(); cfg.apply(); cfg.disposeUIResources()
        // 「添加新配置」里只有一个「Lua」，下面两个工厂；旧的真机类型隐藏但还在，旧配置能加载
        val luaType = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType("EmmyLua.Attach")!!
        assertEquals(listOf("EmmyLua.Attach", "EmmyLua.Device"), luaType.configurationFactories.map { it.id })
        assertTrue(com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType("EmmyLua.Device")
            is com.intellij.execution.configurations.VirtualConfigurationType)
        val conf = luaType.configurationFactories[1].createTemplateConfiguration(project) as com.fixcode.intellij.lua.debug.ui.device.LuaDeviceConfiguration
        val editor = conf.configurationEditor
        editor.component
        conf.options.packageName = "com.x.y"
        editor.resetFrom(conf)
        editor.applyTo(conf)
        assertEquals("com.x.y", conf.options.packageName)
        com.intellij.openapi.util.Disposer.dispose(editor)
        val a = luaType.configurationFactories[0].createTemplateConfiguration(project) as com.fixcode.intellij.lua.debug.ui.attach.LuaAttachConfiguration
        val ae = a.configurationEditor
        ae.component
        a.options.onlyLuaProcesses = false
        ae.resetFrom(a); ae.applyTo(a)
        assertFalse(a.options.onlyLuaProcesses)
        com.intellij.openapi.util.Disposer.dispose(ae)
    }
}
