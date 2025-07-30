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
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * LuaPanda调试器运行配置状态
 * 负责启动调试会话和创建调试进程
 */
class LuaPandaRunProfileState(
    environment: ExecutionEnvironment,
    private val configuration: LuaPandaDebugConfiguration
) : CommandLineState(environment) {

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        // 对于调试器，我们不需要启动实际的进程
        // 调试会话将通过XDebuggerManager管理
        val debuggerManager = XDebuggerManager.getInstance(environment.project)
        
        val session = debuggerManager.startSession(environment, object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                return LuaPandaDebugProcess(session)
            }
        })
        
        // 返回一个空的ProcessHandler，因为调试器不需要实际的进程
        return object : ProcessHandler() {
            override fun destroyProcessImpl() {}
            override fun detachProcessImpl() {}
            override fun detachIsDefault(): Boolean = false
            override fun getProcessInput(): java.io.OutputStream? = null
        }
    }
}