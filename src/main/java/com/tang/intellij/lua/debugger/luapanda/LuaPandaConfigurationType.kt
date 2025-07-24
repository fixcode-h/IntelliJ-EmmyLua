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

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * LuaPanda配置类型
 */
class LuaPandaConfigurationType : ConfigurationType {
    
    companion object {
        const val ID = "LuaPandaDebugger"
    }
    
    override fun getDisplayName(): String = "LuaPanda Debugger"
    
    override fun getConfigurationTypeDescription(): String = "LuaPanda Lua debugger configuration"
    
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/lua.png", javaClass)
    
    override fun getId(): String = ID
    
    override fun getConfigurationFactories(): Array<ConfigurationFactory> {
        return arrayOf(LuaPandaConfigurationFactory(this))
    }
}