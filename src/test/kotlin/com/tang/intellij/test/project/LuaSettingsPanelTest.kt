package com.tang.intellij.test.project

import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.project.LuaSettingsPanel
import com.tang.intellij.test.LuaTestBase

class LuaSettingsPanelTest : LuaTestBase() {
    fun testCreateResetAndModifiedLifecycle() {
        val settings = LuaSettings.instance
        val originalConstructorNames = settings.constructorNames.copyOf()
        val panel = LuaSettingsPanel(project)

        try {
            assertNotNull(panel.createComponent())
            assertFalse(panel.isModified)

            settings.constructorNamesString = "changedConstructor"
            assertTrue(panel.isModified)

            panel.reset()
            assertFalse(panel.isModified)
        } finally {
            settings.constructorNames = originalConstructorNames
            panel.reset()
        }
    }
}
