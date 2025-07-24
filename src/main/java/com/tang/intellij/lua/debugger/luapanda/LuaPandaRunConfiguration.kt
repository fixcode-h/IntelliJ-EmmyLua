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

package com.tang.intellij.lua.debugger.luapanda

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

/**
 * LuaPanda运行配置
 */
class LuaPandaRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<LuaPandaRunConfigurationOptions>(project, factory, name) {
    
    var debugPort: Int = 8818
    var workingDirectory: String = ""
    var luaExecutable: String = ""
    var scriptPath: String = ""
    var programArguments: String = ""
    
    override fun getOptions(): LuaPandaRunConfigurationOptions {
        return super.getOptions() as LuaPandaRunConfigurationOptions
    }
    
    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return LuaPandaSettingsEditor()
    }
    
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return LuaPandaCommandLineState(environment)
    }
    
    override fun readExternal(element: Element) {
        super.readExternal(element)
        debugPort = element.getAttributeValue("debugPort")?.toIntOrNull() ?: 8818
        workingDirectory = element.getAttributeValue("workingDirectory") ?: ""
        luaExecutable = element.getAttributeValue("luaExecutable") ?: ""
        scriptPath = element.getAttributeValue("scriptPath") ?: ""
        programArguments = element.getAttributeValue("programArguments") ?: ""
    }
    
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("debugPort", debugPort.toString())
        element.setAttribute("workingDirectory", workingDirectory)
        element.setAttribute("luaExecutable", luaExecutable)
        element.setAttribute("scriptPath", scriptPath)
        element.setAttribute("programArguments", programArguments)
    }
}