package com.tang.intellij.test.debugger

import com.intellij.openapi.util.JDOMExternalizerUtil
import com.tang.intellij.lua.debugger.emmy.ConfiguredEmmyTargetBootstrap
import com.tang.intellij.lua.debugger.emmy.EmmyDebugConfiguration
import com.tang.intellij.lua.debugger.emmy.EmmyDebugConfigurationType
import com.tang.intellij.lua.debugger.emmy.EmmyDebugTransportType
import com.tang.intellij.lua.debugger.emmy.EmmyDebuggerConfigurationFactory
import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import com.tang.intellij.lua.debugger.emmy.SocketClientTransporter
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachConfigurationType
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebugConfiguration
import com.tang.intellij.lua.debugger.emmy.attach.EmmyAttachDebuggerConfigurationFactory
import com.tang.intellij.lua.debugger.luapanda.LuaPandaConfigurationType
import com.tang.intellij.lua.debugger.luapanda.LuaPandaDebugConfiguration
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
        assertEquals("2", JDOMExternalizerUtil.readField(saved, "SCHEMA_VERSION"))
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
        assertEquals("2", JDOMExternalizerUtil.readField(saved, "SCHEMA_VERSION"))
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
        assertEquals("2", saved.getAttributeValue("schemaVersion"))
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
