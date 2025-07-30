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

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.tang.intellij.lua.lang.LuaIcons
import javax.swing.Icon

class LuaPandaConfigurationType : ConfigurationType {
    
    private val factory = LuaPandaConfigurationFactory(this)
    
    companion object {
        fun getInstance(): LuaPandaConfigurationType {
            return ConfigurationTypeUtil.findConfigurationType(LuaPandaConfigurationType::class.java)
        }
    }
    
    override fun getDisplayName(): String {
        return "LuaPanda Debugger"
    }
    
    override fun getConfigurationTypeDescription(): String {
        return "LuaPanda remote debugger configuration"
    }
    
    override fun getIcon(): Icon {
        return LuaIcons.FILE
    }
    
    override fun getId(): String {
        return "LuaPandaDebugger"
    }
    
    override fun getConfigurationFactories(): Array<LuaPandaConfigurationFactory> {
        return arrayOf(factory)
    }
    
    fun getFactory(): LuaPandaConfigurationFactory {
        return factory
    }
}