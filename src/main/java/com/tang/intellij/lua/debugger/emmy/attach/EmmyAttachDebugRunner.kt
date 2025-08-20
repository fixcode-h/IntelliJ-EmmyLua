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

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.tang.intellij.lua.debugger.LuaRunner

/**
 * Emmy附加调试运行器
 */
class EmmyAttachDebugRunner : LuaRunner() {
    
    companion object {
        const val ID = "lua.emmy.attach.runner"
    }

    override fun getRunnerId() = ID

    override fun canRun(executorId: String, runProfile: RunProfile): Boolean {
        return DefaultDebugExecutor.EXECUTOR_ID == executorId && runProfile is EmmyAttachDebugConfiguration
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor {
        val configuration = environment.runProfile as EmmyAttachDebugConfiguration
        
        // 每次启动调试时都重置PID为0，强制用户选择进程
        configuration.pid = 0
        
        // 验证配置
        if (configuration.pid <= 0) {
            // 尝试自动选择进程
            val processSelector = ProcessSelector(environment.project)
            val selectedProcess = processSelector.showProcessSelectionDialog(
                "",
                configuration.autoAttachSingleProcess
            )
            
            selectedProcess?.let { process ->
                configuration.pid = process.pid
                                 // 检测并更新架构
                 val detectedArch = ProcessUtils.detectProcessArch(process.pid)
                configuration.winArch = detectedArch.toEmmyWinArch()
            } ?: run {
                Messages.showErrorDialog(
                    environment.project,
                    "必须选择一个目标进程才能启动附加调试",
                    "错误"
                )
                throw RuntimeException("没有选择目标进程")
            }
        }

        // 启动调试会话
        val manager = XDebuggerManager.getInstance(environment.project)
        val session = manager.startSession(environment, object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                return EmmyAttachDebugProcess(session)
            }
        })

        return session.runContentDescriptor
    }
}