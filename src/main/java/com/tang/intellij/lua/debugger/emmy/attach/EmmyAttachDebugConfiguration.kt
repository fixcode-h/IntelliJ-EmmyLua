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
}

/**
 * Emmy附加调试配置
 */
class EmmyAttachDebugConfiguration(project: Project, factory: EmmyAttachDebuggerConfigurationFactory) 
    : LuaRunConfiguration(project, factory), RunConfigurationWithSuppressedDefaultRunAction {
    
    init {
        name = "Emmy Attach Debug"
    }
    
    var pid: Int = 0  // 默认为0，通过进程选择对话框选择
    var processName: String = ""
    var winArch = EmmyWinArch.X64
    var captureLog: Boolean = false
    var autoAttachSingleProcess: Boolean = true
    var filterUEProcesses: Boolean = false
    var threadFilterBlacklist: List<String> = listOf("winlogon", "csrss", "wininit", "services")
    var logLevel: LogLevel = LogLevel.NORMAL  // 默认日志等级为1级（普通日志）

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
        JDOMExternalizerUtil.writeField(element, "PID", pid.toString())
        JDOMExternalizerUtil.writeField(element, "PROCESS_NAME", processName)
        JDOMExternalizerUtil.writeField(element, "WIN_ARCH", winArch.name)
        JDOMExternalizerUtil.writeField(element, "CAPTURE_LOG", captureLog.toString())
        JDOMExternalizerUtil.writeField(element, "AUTO_ATTACH_SINGLE_PROCESS", autoAttachSingleProcess.toString())
        JDOMExternalizerUtil.writeField(element, "FILTER_UE_PROCESSES", filterUEProcesses.toString())
        JDOMExternalizerUtil.writeField(element, "THREAD_FILTER_BLACKLIST", threadFilterBlacklist.joinToString(","))
        JDOMExternalizerUtil.writeField(element, "LOG_LEVEL", logLevel.level.toString())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        val pidStr = JDOMExternalizerUtil.readField(element, "PID")
        pid = pidStr?.toIntOrNull() ?: 0
        processName = JDOMExternalizerUtil.readField(element, "PROCESS_NAME") ?: ""
        val archStr = JDOMExternalizerUtil.readField(element, "WIN_ARCH")
        winArch = if (archStr != null) EmmyWinArch.valueOf(archStr) else EmmyWinArch.X64
        val captureLogStr = JDOMExternalizerUtil.readField(element, "CAPTURE_LOG")
        captureLog = captureLogStr?.toBoolean() ?: false
        val autoAttachStr = JDOMExternalizerUtil.readField(element, "AUTO_ATTACH_SINGLE_PROCESS")
        autoAttachSingleProcess = autoAttachStr?.toBoolean() ?: true
        val filterUEStr = JDOMExternalizerUtil.readField(element, "FILTER_UE_PROCESSES")
        filterUEProcesses = filterUEStr?.toBoolean() ?: false
        val blacklistStr = JDOMExternalizerUtil.readField(element, "THREAD_FILTER_BLACKLIST")
        threadFilterBlacklist = if (blacklistStr.isNullOrEmpty()) listOf() else blacklistStr.split(",")
        val logLevelStr = JDOMExternalizerUtil.readField(element, "LOG_LEVEL")
        logLevel = LogLevel.fromLevel(logLevelStr?.toIntOrNull() ?: 1)  // 默认为1级（普通日志）
    }
}