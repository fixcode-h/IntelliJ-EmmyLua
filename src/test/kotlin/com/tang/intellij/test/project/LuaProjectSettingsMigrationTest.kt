package com.tang.intellij.test.project

import com.tang.intellij.lua.project.LuaProjectSettings
import com.tang.intellij.lua.project.LuaSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class LuaProjectSettingsMigrationTest {
    @Test
    fun `legacy application settings migrate once into project settings`() {
        val legacy = LuaSettings().apply {
            additionalSourcesRoot = arrayOf("src/shared")
        }
        val settings = LuaProjectSettings()

        settings.migrateFromApplicationSettings(legacy.legacyProjectSettings())

        assertArrayEquals(arrayOf("src/shared"), settings.additionalSourcesRoot)
        assertTrue(settings.migratedFromApplicationSettings)

        legacy.additionalSourcesRoot = arrayOf("changed")
        settings.migrateFromApplicationSettings(legacy.legacyProjectSettings())
        assertArrayEquals(arrayOf("src/shared"), settings.additionalSourcesRoot)
    }
}
