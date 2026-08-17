package com.tang.intellij.test.debugger

import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.Disposer
import com.tang.intellij.lua.debugger.DebugLogLevel
import com.tang.intellij.lua.debugger.DebuggerConfigurationSchema
import com.tang.intellij.lua.debugger.emmy.ConfiguredEmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.EmmyDebugConfiguration
import com.tang.intellij.lua.debugger.emmy.EmmyDebugConfigurationType
import com.tang.intellij.lua.debugger.emmy.EmmyDebugSettingsPanel
import com.tang.intellij.lua.debugger.emmy.EmmyDebugTransportType
import com.tang.intellij.lua.debugger.emmy.EmmyDebuggerConfigurationFactory
import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import com.tang.intellij.lua.debugger.emmy.SocketClientTransporter
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachConfigurationType
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebugConfiguration
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebugSettingsPanel
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebuggerConfigurationFactory
import com.tang.intellij.lua.debugger.luapanda.LuaPandaConfigurationType
import com.tang.intellij.lua.debugger.luapanda.LuaPandaDebugConfiguration
import com.tang.intellij.lua.debugger.luapanda.LuaPandaSettingsEditor
import com.tang.intellij.lua.debugger.luapanda.LuaPandaTransportType
import com.tang.intellij.test.LuaTestBase
import org.jdom.Element

class DebuggerConfigurationMigrationTest : LuaTestBase() {
    fun testEmmyReadsLegacyOrdinalsAndWritesStableIds() {
        val configuration = EmmyDebugConfiguration(
            project,
            EmmyDebuggerConfigurationFactory(EmmyDebugConfigurationType())
        )
        val legacy = Element("configuration")
        JDOMExternalizerUtil.writeField(legacy, "TYPE", "1")
        JDOMExternalizerUtil.writeField(legacy, "WIN_ARCH", "0")

        configuration.readExternal(legacy)

        assertEquals(EmmyDebugTransportType.TCP_SERVER, configuration.type)
        assertEquals(EmmyWinArch.X86, configuration.winArch)

        val saved = Element("configuration")
        configuration.writeExternal(saved)
        assertEquals("tcp-server", JDOMExternalizerUtil.readField(saved, "TYPE"))
        assertEquals("x86", JDOMExternalizerUtil.readField(saved, "WIN_ARCH"))
        assertEquals("1", JDOMExternalizerUtil.readField(saved, "LOG_LEVEL"))
        assertEquals("4", JDOMExternalizerUtil.readField(saved, "SCHEMA_VERSION"))
    }

    fun testAttachReadsLegacyArchAndWritesStableId() {
        val configuration = EmmyAttachDebugConfiguration(
            project,
            EmmyAttachDebuggerConfigurationFactory(EmmyAttachConfigurationType())
        )
        val legacy = Element("configuration")
        JDOMExternalizerUtil.writeField(legacy, "WIN_ARCH", "0")

        configuration.readExternal(legacy)

        assertEquals(EmmyWinArch.X86, configuration.winArch)
        val saved = Element("configuration")
        configuration.writeExternal(saved)
        assertEquals("x86", JDOMExternalizerUtil.readField(saved, "WIN_ARCH"))
        assertEquals("1", JDOMExternalizerUtil.readField(saved, "LOG_LEVEL"))
        assertEquals("4", JDOMExternalizerUtil.readField(saved, "SCHEMA_VERSION"))
    }

    fun testLuaPandaReadsLegacyOrdinalAndWritesStableId() {
        val type = LuaPandaConfigurationType()
        val configuration = LuaPandaDebugConfiguration(project, type.getFactory(), "test")
        val legacy = Element("configuration").setAttribute("transportType", "0")

        configuration.readExternal(legacy)

        assertEquals(LuaPandaTransportType.TCP_CLIENT, configuration.transportType)
        val saved = Element("configuration")
        configuration.writeExternal(saved)
        assertEquals("tcp-client", saved.getAttributeValue("transportType"))
        assertEquals("1", saved.getAttributeValue("logLevel"))
        assertEquals("4", saved.getAttributeValue("schemaVersion"))
    }

    fun testSchemaMigratorRunsEveryVersionInOrderWithoutMutatingSource() {
        val source = Element("configuration")
        val visited = mutableListOf<Int>()

        val migrated = DebuggerConfigurationSchema.ATTRIBUTE.migrate(source, 4) { version, state ->
            visited += version
            state.setAttribute("v$version", "done")
        }

        assertEquals(listOf(0, 1, 2, 3), visited)
        assertNull(source.getAttributeValue("schemaVersion"))
        assertEquals("4", migrated.getAttributeValue("schemaVersion"))
        assertEquals("done", migrated.getAttributeValue("v3"))
    }

    fun testUnifiedDebuggerLogLevelThresholds() {
        assertEquals(DebugLogLevel.DEBUG, DebugLogLevel.fromValue(0))
        assertEquals(DebugLogLevel.RUNTIME, DebugLogLevel.fromValue(1))
        assertEquals(DebugLogLevel.WARNING, DebugLogLevel.fromValue(2))
        assertEquals(DebugLogLevel.ERROR, DebugLogLevel.fromValue(3))
        assertEquals(DebugLogLevel.RUNTIME, DebugLogLevel.fromValue(null))

        assertTrue(DebugLogLevel.DEBUG.isEnabledFor(DebugLogLevel.DEBUG))
        assertFalse(DebugLogLevel.DEBUG.isEnabledFor(DebugLogLevel.RUNTIME))
        assertTrue(DebugLogLevel.RUNTIME.isEnabledFor(DebugLogLevel.RUNTIME))
        assertTrue(DebugLogLevel.WARNING.isEnabledFor(DebugLogLevel.RUNTIME))
        assertTrue(DebugLogLevel.ERROR.isEnabledFor(DebugLogLevel.RUNTIME))
    }

    fun testAllDebuggerConfigurationsPersistLog0() {
        val emmy = EmmyDebugConfiguration(
            project,
            EmmyDebuggerConfigurationFactory(EmmyDebugConfigurationType())
        ).apply { logLevel = DebugLogLevel.DEBUG }
        val attach = EmmyAttachDebugConfiguration(
            project,
            EmmyAttachDebuggerConfigurationFactory(EmmyAttachConfigurationType())
        ).apply { logLevel = DebugLogLevel.DEBUG }
        val luaPandaType = LuaPandaConfigurationType()
        val luaPanda = LuaPandaDebugConfiguration(project, luaPandaType.getFactory(), "test").apply {
            logLevel = DebugLogLevel.DEBUG
        }

        val emmySaved = Element("configuration").also(emmy::writeExternal)
        val attachSaved = Element("configuration").also(attach::writeExternal)
        val luaPandaSaved = Element("configuration").also(luaPanda::writeExternal)

        assertEquals("0", JDOMExternalizerUtil.readField(emmySaved, "LOG_LEVEL"))
        assertEquals("0", JDOMExternalizerUtil.readField(attachSaved, "LOG_LEVEL"))
        assertEquals("0", luaPandaSaved.getAttributeValue("logLevel"))
    }

    fun testAllDebuggerSettingsEditorsPreserveLog0() {
        val emmy = EmmyDebugConfiguration(
            project,
            EmmyDebuggerConfigurationFactory(EmmyDebugConfigurationType())
        ).apply { logLevel = DebugLogLevel.DEBUG }
        val attach = EmmyAttachDebugConfiguration(
            project,
            EmmyAttachDebuggerConfigurationFactory(EmmyAttachConfigurationType())
        ).apply { logLevel = DebugLogLevel.DEBUG }
        val luaPandaType = LuaPandaConfigurationType()
        val luaPanda = LuaPandaDebugConfiguration(project, luaPandaType.getFactory(), "test").apply {
            logLevel = DebugLogLevel.DEBUG
        }

        val emmyEditor = EmmyDebugSettingsPanel(project)
        val attachEditor = EmmyAttachDebugSettingsPanel(project)
        val luaPandaEditor = LuaPandaSettingsEditor()
        try {
            emmyEditor.resetFrom(emmy)
            emmyEditor.applyTo(emmy)
            attachEditor.resetFrom(attach)
            attachEditor.applyTo(attach)
            luaPandaEditor.resetFrom(luaPanda)
            luaPandaEditor.applyTo(luaPanda)
        } finally {
            Disposer.dispose(emmyEditor)
            Disposer.dispose(attachEditor)
            Disposer.dispose(luaPandaEditor)
        }

        assertEquals(DebugLogLevel.DEBUG, emmy.logLevel)
        assertEquals(DebugLogLevel.DEBUG, attach.logLevel)
        assertEquals(DebugLogLevel.DEBUG, luaPanda.logLevel)
    }

    fun testConfiguredBootstrapCreatesSelectedTransport() {
        val configuration = EmmyDebugConfiguration(
            project,
            EmmyDebuggerConfigurationFactory(EmmyDebugConfigurationType())
        ).apply {
            type = EmmyDebugTransportType.TCP_CLIENT
            host = "127.0.0.1"
            port = 19001
        }

        val transport = ConfiguredEmmyTargetBootstrap(configuration).prepareTransports().single()
        try {
            assertInstanceOf(transport, SocketClientTransporter::class.java)
            transport as SocketClientTransporter
            assertEquals("127.0.0.1", transport.host)
            assertEquals(19001, transport.port)
        } finally {
            transport.close()
        }
    }

    fun testOnlySupportedDebuggerEntryNamesRemain() {
        assertEquals("Emmy Debugger(NEW)", EmmyDebugConfigurationType().displayName)
        assertEquals("Emmy Attach Debugger", EmmyAttachConfigurationType().displayName)
        assertEquals("LuaPandaDebugger", LuaPandaConfigurationType().displayName)
    }
}
