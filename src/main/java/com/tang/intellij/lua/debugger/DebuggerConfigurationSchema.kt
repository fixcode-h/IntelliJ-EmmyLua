package com.tang.intellij.lua.debugger

import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element

enum class DebuggerConfigurationStorage {
    FIELD,
    ATTRIBUTE
}

class DebuggerConfigurationSchema(
    private val storage: DebuggerConfigurationStorage,
    private val versionKey: String
) {
    fun migrate(
        source: Element,
        currentVersion: Int,
        migrateStep: (fromVersion: Int, state: Element) -> Unit
    ): Element {
        val state = source.clone()
        var version = read(state, versionKey)?.toIntOrNull() ?: 0
        while (version < currentVersion) {
            migrateStep(version, state)
            version++
            write(state, versionKey, version.toString())
        }
        return state
    }

    fun read(element: Element, key: String): String? = when (storage) {
        DebuggerConfigurationStorage.FIELD -> JDOMExternalizerUtil.readField(element, key)
        DebuggerConfigurationStorage.ATTRIBUTE -> element.getAttributeValue(key)
    }

    fun write(element: Element, key: String, value: String) {
        when (storage) {
            DebuggerConfigurationStorage.FIELD -> JDOMExternalizerUtil.writeField(element, key, value)
            DebuggerConfigurationStorage.ATTRIBUTE -> element.setAttribute(key, value)
        }
    }

    fun writeCurrentVersion(element: Element, currentVersion: Int) {
        write(element, versionKey, currentVersion.toString())
    }

    companion object {
        val FIELD = DebuggerConfigurationSchema(DebuggerConfigurationStorage.FIELD, "SCHEMA_VERSION")
        val ATTRIBUTE = DebuggerConfigurationSchema(DebuggerConfigurationStorage.ATTRIBUTE, "schemaVersion")
    }
}
