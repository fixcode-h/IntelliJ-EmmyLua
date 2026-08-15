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
    var customHelperPath: String = ""
    var customHelperExtName: String = ""
    var ueProcessNames: Array<String> = DEFAULT_UE_PROCESS_NAMES.copyOf()
    var debugProcessBlacklist: Array<String> = DEFAULT_PROCESS_BLACKLIST.copyOf()
    var enableDevMode: Boolean = false

    override fun getState(): LuaProjectSettings = this

    override fun loadState(state: LuaProjectSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    @Synchronized
    internal fun migrateFromApplicationSettings(legacy: LuaSettings = LuaSettings.instance) {
        if (migratedFromApplicationSettings) return
        additionalSourcesRoot = legacy.additionalSourcesRoot.copyOf()
        customHelperPath = legacy.customHelperPath
        customHelperExtName = legacy.customHelperExtName
        ueProcessNames = legacy.ueProcessNames.copyOf()
        debugProcessBlacklist = legacy.debugProcessBlacklist.copyOf()
        enableDevMode = legacy.enableDevMode
        migratedFromApplicationSettings = true
        schemaVersion = CURRENT_SCHEMA_VERSION
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val DEFAULT_UE_PROCESS_NAMES = arrayOf("UnrealEngine", "UE4Editor", "UE5Editor", "UnrealEditor")
        private val DEFAULT_PROCESS_BLACKLIST = arrayOf("winlogon", "csrss", "wininit", "services")

        @JvmStatic
        fun getInstance(project: Project): LuaProjectSettings =
            project.getService(LuaProjectSettings::class.java).also { it.migrateFromApplicationSettings() }
    }
}
