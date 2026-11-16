package com.tang.intellij.lua.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "LuaProjectSettings", storages = [Storage("emmyLua.xml")])
class LuaProjectSettings : PersistentStateComponent<LuaProjectSettings> {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var migratedFromApplicationSettings: Boolean = false

    var additionalSourcesRoot: Array<String> = emptyArray()

    override fun getState(): LuaProjectSettings = this

    override fun loadState(state: LuaProjectSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    @Synchronized
    internal fun migrateFromApplicationSettings(
        legacy: LegacyLuaProjectSettings = LuaSettings.instance.legacyProjectSettings()
    ) {
        if (migratedFromApplicationSettings) return
        additionalSourcesRoot = legacy.additionalSourcesRoot.copyOf()
        migratedFromApplicationSettings = true
        schemaVersion = CURRENT_SCHEMA_VERSION
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        @JvmStatic
        fun getInstance(project: Project): LuaProjectSettings =
            project.getService(LuaProjectSettings::class.java).also { it.migrateFromApplicationSettings() }
    }
}
