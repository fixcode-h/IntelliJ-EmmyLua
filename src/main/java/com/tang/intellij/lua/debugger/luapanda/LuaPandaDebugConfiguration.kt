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

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.WriteExternalException
import com.tang.intellij.lua.debugger.LuaRunConfiguration
import org.jdom.Element

class LuaPandaDebugConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : LuaRunConfiguration(project, factory) {

    var transportType: LuaPandaTransportType = LuaPandaTransportType.TCP_SERVER
    var host: String = "localhost"
    var port: Int = 8818
    var stopOnEntry: Boolean = false
    var useCHook: Boolean = true
    var logLevel: Int = 1
    var stopConfirmTimeout: Int = 3
    var autoReconnect: Boolean = true  // 自动重连配置，默认开启
    
    // ========== 参照VSCode插件添加的配置项 ==========
    var luaFileExtension: String = "lua"
    var tempFilePath: String = ""
    var autoPathMode: Boolean = false
    var distinguishSameNameFile: Boolean = false
    var truncatedOPath: String = ""
    var developmentMode: Boolean = false

    init {
        this.name = name
    }

    override fun getValidModules(): Collection<Module> {
        return emptyList()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return LuaPandaSettingsEditor()
    }

    @Throws(ExecutionException::class)
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return LuaPandaRunProfileState(environment, this)
    }

    override fun checkConfiguration() {
        super.checkConfiguration()
        if (port <= 0 || port > 65535) {
            throw RuntimeConfigurationException("Invalid port number")
        }
        if (host.isBlank() && transportType == LuaPandaTransportType.TCP_CLIENT) {
            throw RuntimeConfigurationException("Host cannot be empty for TCP client")
        }
    }

    @Throws(InvalidDataException::class)
    override fun readExternal(element: Element) {
        super.readExternal(element)
        transportType = LuaPandaTransportType.valueOf(
            element.getAttributeValue("transportType") ?: LuaPandaTransportType.TCP_SERVER.name
        )
        host = element.getAttributeValue("host") ?: "localhost"
        port = element.getAttributeValue("port")?.toIntOrNull() ?: 8818
        stopOnEntry = element.getAttributeValue("stopOnEntry")?.toBoolean() ?: false
        useCHook = element.getAttributeValue("useCHook")?.toBoolean() ?: true
        logLevel = element.getAttributeValue("logLevel")?.toIntOrNull() ?: 1
        stopConfirmTimeout = element.getAttributeValue("stopConfirmTimeout")?.toIntOrNull() ?: 3
        autoReconnect = element.getAttributeValue("autoReconnect")?.toBoolean() ?: true
        
        // 读取新添加的配置项
        luaFileExtension = element.getAttributeValue("luaFileExtension") ?: "lua"
        tempFilePath = element.getAttributeValue("tempFilePath") ?: ""
        autoPathMode = element.getAttributeValue("autoPathMode")?.toBoolean() ?: false
        distinguishSameNameFile = element.getAttributeValue("distinguishSameNameFile")?.toBoolean() ?: false
        truncatedOPath = element.getAttributeValue("truncatedOPath") ?: ""
        developmentMode = element.getAttributeValue("developmentMode")?.toBoolean() ?: false
    }

    @Throws(WriteExternalException::class)
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("transportType", transportType.name)
        element.setAttribute("host", host)
        element.setAttribute("port", port.toString())
        element.setAttribute("stopOnEntry", stopOnEntry.toString())
        element.setAttribute("useCHook", useCHook.toString())
        element.setAttribute("logLevel", logLevel.toString())
        element.setAttribute("stopConfirmTimeout", stopConfirmTimeout.toString())
        element.setAttribute("autoReconnect", autoReconnect.toString())
        
        // 写入新添加的配置项
        element.setAttribute("luaFileExtension", luaFileExtension)
        element.setAttribute("tempFilePath", tempFilePath)
        element.setAttribute("autoPathMode", autoPathMode.toString())
        element.setAttribute("distinguishSameNameFile", distinguishSameNameFile.toString())
        element.setAttribute("truncatedOPath", truncatedOPath)
        element.setAttribute("developmentMode", developmentMode.toString())
    }
}