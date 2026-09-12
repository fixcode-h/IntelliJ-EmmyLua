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

package com.tang.intellij.lua.debugger.emmy.attach

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationSingletonPolicy
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
import com.tang.intellij.lua.debugger.emmy.EmmyWinArch
import com.tang.intellij.lua.lang.LuaIcons
import org.jdom.Element
import javax.swing.Icon

/**
 * Emmy附加调试配置类型
 */
class EmmyAttachConfigurationType : ConfigurationType {
    override fun getIcon(): Icon {
        return LuaIcons.FILE
    }

    override fun getConfigurationTypeDescription(): String {
        return "Emmy Attach Debugger"
    }

    override fun getId(): String {
        return "lua.emmy.attach.debugger"
    }

    override fun getDisplayName(): String {
        return "Emmy Attach Debugger"
    }

    override fun getConfigurationFactories(): Array<ConfigurationFactory> {
        return arrayOf(EmmyAttachDebuggerConfigurationFactory(this))
    }
}

/**
 * Emmy附加调试配置工厂
 */
class EmmyAttachDebuggerConfigurationFactory(val type: EmmyAttachConfigurationType) : LuaConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return EmmyAttachDebugConfiguration(project, this)
    }

    override fun getName(): String = "Emmy Attach Debugger"
    
    override fun getSingletonPolicy(): RunConfigurationSingletonPolicy {
        return RunConfigurationSingletonPolicy.MULTIPLE_INSTANCE  // 允许多个实例并行运行
    }
}

/**
 * Emmy附加调试配置
 */
class EmmyAttachDebugConfiguration(project: Project, factory: EmmyAttachDebuggerConfigurationFactory) 
    : LuaRunConfiguration(project, factory), RunConfigurationWithSuppressedDefaultRunAction {
    
    var pid: Int = 0  // 默认为0，通过进程选择对话框选择
    var processName: String = ""
    var winArch = EmmyWinArch.X64
    var captureLog: Boolean = false
    var autoAttachSingleProcess: Boolean = true
    /**
     * Checked by default for attach profiles: grant the local CLI/AI client
     * access to the session without a manual prompt. Still requires a trusted
     * project, and the grant stays withdrawable from Tools.
     */
    var autoAuthorizeCliClients: Boolean = true
    var filterUEProcesses: Boolean = true  // 默认勾选过滤虚幻引擎进程
    var threadFilterBlacklist: List<String> = listOf("winlogon", "csrss", "wininit", "services")
    var logLevel: DebugLogLevel = DebugLogLevel.RUNTIME
    var defaultName = ""

    /**
     * 设置自定义调试弹窗标题
     * @param title 自定义标题
     */
    fun setCustomDebugTitle(title: String) {
        if(defaultName.isEmpty()) defaultName=name
        name = title
    }

    fun restoreName(){
        if(defaultName.isNotEmpty()) name=defaultName
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        val group = SettingsEditorGroup<EmmyAttachDebugConfiguration>()
        group.addEditor("attach", EmmyAttachDebugSettingsPanel(project))
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
        JDOMExternalizerUtil.writeField(element, "PID", pid.toString())
        JDOMExternalizerUtil.writeField(element, "PROCESS_NAME", processName)
        JDOMExternalizerUtil.writeField(element, "WIN_ARCH", winArch.configId)
        JDOMExternalizerUtil.writeField(element, "CAPTURE_LOG", captureLog.toString())
        JDOMExternalizerUtil.writeField(element, "AUTO_ATTACH_SINGLE_PROCESS", autoAttachSingleProcess.toString())
        JDOMExternalizerUtil.writeField(element, "AUTO_AUTHORIZE_CLI_CLIENTS", autoAuthorizeCliClients.toString())
        JDOMExternalizerUtil.writeField(element, "FILTER_UE_PROCESSES", filterUEProcesses.toString())
        JDOMExternalizerUtil.writeField(element, "THREAD_FILTER_BLACKLIST", threadFilterBlacklist.joinToString(","))
        JDOMExternalizerUtil.writeField(element, "LOG_LEVEL", logLevel.value.toString())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        val state = DebuggerConfigurationSchema.FIELD.migrate(element, CURRENT_SCHEMA_VERSION) { version, migrated ->
            if (version == 0) {
                EmmyWinArch.fromStoredValue(JDOMExternalizerUtil.readField(migrated, "WIN_ARCH"))
                    ?.let { JDOMExternalizerUtil.writeField(migrated, "WIN_ARCH", it.configId) }
            }
        }
        val pidStr = JDOMExternalizerUtil.readField(state, "PID")
        pid = pidStr?.toIntOrNull() ?: 0
        processName = JDOMExternalizerUtil.readField(state, "PROCESS_NAME") ?: ""
        val archStr = JDOMExternalizerUtil.readField(state, "WIN_ARCH")
        winArch = EmmyWinArch.fromStoredValue(archStr) ?: EmmyWinArch.X64
        val captureLogStr = JDOMExternalizerUtil.readField(state, "CAPTURE_LOG")
        captureLog = captureLogStr?.toBoolean() ?: false
        val autoAttachStr = JDOMExternalizerUtil.readField(state, "AUTO_ATTACH_SINGLE_PROCESS")
        autoAttachSingleProcess = autoAttachStr?.toBoolean() ?: true
        val autoAuthorizeCliStr = JDOMExternalizerUtil.readField(state, "AUTO_AUTHORIZE_CLI_CLIENTS")
        autoAuthorizeCliClients = autoAuthorizeCliStr?.toBoolean() ?: true
        val filterUEStr = JDOMExternalizerUtil.readField(state, "FILTER_UE_PROCESSES")
        filterUEProcesses = filterUEStr?.toBoolean() ?: true  // 默认勾选过滤虚幻引擎进程
        val blacklistStr = JDOMExternalizerUtil.readField(state, "THREAD_FILTER_BLACKLIST")
        threadFilterBlacklist = if (blacklistStr.isNullOrEmpty()) listOf() else blacklistStr.split(",")
        val logLevelStr = JDOMExternalizerUtil.readField(state, "LOG_LEVEL")
        logLevel = DebugLogLevel.fromValue(logLevelStr?.toIntOrNull())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}
