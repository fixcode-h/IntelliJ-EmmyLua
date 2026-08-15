package com.tang.intellij.test.project

import com.tang.intellij.lua.project.LuaProjectSettings
import com.tang.intellij.lua.project.LuaSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaProjectSettingsMigrationTest {
    @Test
    fun `legacy application settings migrate once into project settings`() {
        val legacy = LuaSettings().apply {
            additionalSourcesRoot = arrayOf("src/shared")
            customHelperPath = "helpers"
            customHelperExtName = "custom_helper"
            ueProcessNames = arrayOf("GameEditor")
            debugProcessBlacklist = arrayOf("system")
            enableDevMode = true
        }
        val settings = LuaProjectSettings()

        settings.migrateFromApplicationSettings(legacy)

        assertArrayEquals(arrayOf("src/shared"), settings.additionalSourcesRoot)
        assertEquals("helpers", settings.customHelperPath)
        assertEquals("custom_helper", settings.customHelperExtName)
        assertArrayEquals(arrayOf("GameEditor"), settings.ueProcessNames)
        assertArrayEquals(arrayOf("system"), settings.debugProcessBlacklist)
        assertTrue(settings.enableDevMode)
        assertTrue(settings.migratedFromApplicationSettings)

        legacy.customHelperPath = "changed"
        settings.migrateFromApplicationSettings(legacy)
        assertEquals("helpers", settings.customHelperPath)
    }
}
