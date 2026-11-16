/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debug.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tang.intellij.lua.debug.api.LuaLocalProcess
import com.tang.intellij.lua.debug.api.LuaSymbolReport
import com.tang.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import com.tang.intellij.lua.debug.ui.process.UnrealProcessTagProvider
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class DebugUiTest : BasePlatformTestCase() {

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
        val cfg = com.tang.intellij.lua.debug.ui.settings.LuaDebugConfigurable()
        val c = cfg.createComponent()
        assertNotNull(c)
        cfg.reset(); cfg.apply(); cfg.disposeUIResources()
        val deviceType = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType("EmmyLua.Device")!!
        val conf = deviceType.configurationFactories[0].createTemplateConfiguration(project) as com.tang.intellij.lua.debug.ui.device.LuaDeviceConfiguration
        val editor = conf.configurationEditor as com.intellij.openapi.options.SettingsEditor<com.tang.intellij.lua.debug.ui.device.LuaDeviceConfiguration>
        editor.component
        conf.options.packageName = "com.x.y"
        editor.resetFrom(conf)
        editor.applyTo(conf)
        assertEquals("com.x.y", conf.options.packageName)
        com.intellij.openapi.util.Disposer.dispose(editor)
        val a = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType("EmmyLua.Attach")!!
            .configurationFactories[0].createTemplateConfiguration(project) as com.tang.intellij.lua.debug.ui.attach.LuaAttachConfiguration
        val ae = a.configurationEditor as com.intellij.openapi.options.SettingsEditor<com.tang.intellij.lua.debug.ui.attach.LuaAttachConfiguration>
        ae.component
        a.options.processName = "UnrealEditor"
        ae.resetFrom(a); ae.applyTo(a)
        assertEquals("UnrealEditor", a.options.processName)
        com.intellij.openapi.util.Disposer.dispose(ae)
    }
}
