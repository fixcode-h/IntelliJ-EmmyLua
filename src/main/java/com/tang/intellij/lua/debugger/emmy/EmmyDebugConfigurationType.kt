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

package com.tang.intellij.lua.debugger.emmy

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.tang.intellij.lua.debugger.LuaCommandLineState
import com.tang.intellij.lua.debugger.LuaConfigurationFactory
import com.tang.intellij.lua.debugger.LuaRunConfiguration
import com.tang.intellij.lua.debugger.DebugLogLevel
import com.tang.intellij.lua.debugger.DebuggerConfigurationSchema
import com.tang.intellij.lua.lang.LuaIcons
import org.jdom.Element
import javax.swing.Icon

class EmmyDebugConfigurationType : ConfigurationType {
    override fun getIcon(): Icon {
        return LuaIcons.FILE
    }

    override fun getConfigurationTypeDescription(): String {
        return "Emmy Debugger(NEW)"
    }

    override fun getId(): String {
        return "lua.emmy.debugger"
    }

    override fun getDisplayName(): String {
        return "Emmy Debugger(NEW)"
    }

    override fun getConfigurationFactories(): Array<ConfigurationFactory> {
        return arrayOf(EmmyDebuggerConfigurationFactory(this))
    }
}

enum class EmmyDebugTransportType(val configId: String, val desc: String) {
    TCP_CLIENT("tcp-client", "Tcp ( IDE connect debugger )"),
    TCP_SERVER("tcp-server", "Tcp ( Debugger connect IDE )"),
    PIPE_CLIENT("pipe-client", "Pipeline ( IDE connect debugger )"),
    PIPE_SERVER("pipe-server", "Pipeline ( Debugger connect IDE )");

    override fun toString(): String {
        return desc
    }

    companion object {
        fun fromStoredValue(value: String?): EmmyDebugTransportType? {
            if (value == null) return null
            return entries.firstOrNull { it.configId == value || it.name == value }
                ?: value.toIntOrNull()?.let(entries::getOrNull)
        }
    }
}

enum class EmmyWinArch(val configId: String, val desc: String) {
    X86("x86", "x86"),
    X64("x64", "x64");

    override fun toString(): String {
        return desc
    }

    companion object {
        fun fromStoredValue(value: String?): EmmyWinArch? {
            if (value == null) return null
            return entries.firstOrNull { it.configId == value || it.name == value }
                ?: value.toIntOrNull()?.let(entries::getOrNull)
        }
    }
}

class EmmyDebuggerConfigurationFactory(val type: EmmyDebugConfigurationType) : LuaConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return EmmyDebugConfiguration(project, this)
    }
}

class EmmyDebugConfiguration(project: Project, factory: EmmyDebuggerConfigurationFactory) : LuaRunConfiguration(project, factory), RunConfigurationWithSuppressedDefaultRunAction {
    var type = EmmyDebugTransportType.TCP_CLIENT

    var host = "localhost"
    var port = 9966
    var winArch = EmmyWinArch.X64
    var pipeName = "emmy"
    var logLevel = DebugLogLevel.RUNTIME
    /**
     * Checked by default: grant the local CLI/AI client access to the session
     * without a manual prompt. Still requires a trusted project, and the grant
     * stays withdrawable from Tools.
     */
    var autoAuthorizeCliClients = true

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        val group = SettingsEditorGroup<EmmyDebugConfiguration>()
        group.addEditor("emmy", EmmyDebugSettingsPanel(project))
        return group
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return LuaCommandLineState(environment)
    }

    override fun getValidModules(): Collection<Module> {
        return emptyList()
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        DebuggerConfigurationSchema.FIELD.writeCurrentVersion(element, CURRENT_SCHEMA_VERSION)
        JDOMExternalizerUtil.writeField(element, "TYPE", type.configId)
        JDOMExternalizerUtil.writeField(element, "HOST", host)
        JDOMExternalizerUtil.writeField(element, "PORT", port.toString())
        JDOMExternalizerUtil.writeField(element, "PIPE", pipeName)
        JDOMExternalizerUtil.writeField(element, "WIN_ARCH", winArch.configId)
        JDOMExternalizerUtil.writeField(element, "LOG_LEVEL", logLevel.value.toString())
        JDOMExternalizerUtil.writeField(element, "AUTO_AUTHORIZE_CLI_CLIENTS", autoAuthorizeCliClients.toString())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        val state = DebuggerConfigurationSchema.FIELD.migrate(element, CURRENT_SCHEMA_VERSION) { version, migrated ->
            if (version == 0) {
                EmmyDebugTransportType.fromStoredValue(JDOMExternalizerUtil.readField(migrated, "TYPE"))
                    ?.let { JDOMExternalizerUtil.writeField(migrated, "TYPE", it.configId) }
                EmmyWinArch.fromStoredValue(JDOMExternalizerUtil.readField(migrated, "WIN_ARCH"))
                    ?.let { JDOMExternalizerUtil.writeField(migrated, "WIN_ARCH", it.configId) }
            }
        }
        JDOMExternalizerUtil.readField(state, "HOST")?.let {
            host = it
        }
        JDOMExternalizerUtil.readField(state, "PORT")?.let {
            port = it.toIntOrNull() ?: port
        }
        JDOMExternalizerUtil.readField(state, "PIPE")?.let {
            pipeName = it
        }
        EmmyDebugTransportType.fromStoredValue(JDOMExternalizerUtil.readField(state, "TYPE"))?.let { type = it }
        EmmyWinArch.fromStoredValue(JDOMExternalizerUtil.readField(state, "WIN_ARCH"))?.let { winArch = it }
        logLevel = DebugLogLevel.fromValue(JDOMExternalizerUtil.readField(state, "LOG_LEVEL")?.toIntOrNull())
        autoAuthorizeCliClients =
            JDOMExternalizerUtil.readField(state, "AUTO_AUTHORIZE_CLI_CLIENTS")?.toBoolean() ?: true
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}
