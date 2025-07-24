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

import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.tang.intellij.lua.debugger.DebuggerType

/**
 * LuaPanda调试器运行器
 */
class LuaPandaDebuggerRunner : GenericProgramRunner<RunnerSettings>() {
    
    companion object {
        const val ID = "LuaPandaDebuggerRunner"
    }
    
    override fun getRunnerId(): String = ID
    
    override fun canRun(executorId: String, profile: com.intellij.execution.configurations.RunProfile): Boolean {
        return executorId == DebuggerType.LUA_PANDA.id && profile is LuaPandaRunConfiguration
    }
    
    override fun doExecute(
        state: com.intellij.execution.configurations.RunProfileState,
        environment: ExecutionEnvironment
    ): RunContentDescriptor? {
        val executionResult = state.execute(environment.executor, this)
            ?: return null
        
        return showRunContent(executionResult, environment)
    }
}