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
import com.tang.intellij.lua.debugger.emmy.attach.ProcessAttachmentManager

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
        val attachmentManager = ProcessAttachmentManager.getInstance()
        
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
                // 检查进程是否已被附加
                if (attachmentManager.isProcessAttached(process.pid)) {
                    val attachedInfo = attachmentManager.getAttachedProcessInfo(process.pid)
                    Messages.showWarningDialog(
                        environment.project,
                        "进程 ${process.name}(PID:${process.pid}) 已经被附加调试。\n" +
                        "附加时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(attachedInfo?.attachTime ?: 0))}\n" +
                        "请选择其他进程或先断开现有调试会话。",
                        "进程已附加"
                    )
                    throw RuntimeException("进程已被附加，无法重复附加")
                }
                
                configuration.pid = process.pid
                // 检测并更新架构
                val detectedArch = ProcessUtils.detectProcessArch(process.pid)
                configuration.winArch = detectedArch.toEmmyWinArch()
            } ?: run {
                // 用户取消了进程选择，静默退出而不显示错误
                throw RuntimeException("用户取消了进程选择")
            }
        }

        // 获取附加的进程信息并设置自定义调试标题
        val processSelector = ProcessSelector(environment.project)
        val processes = try {
            processSelector.getProcessList()
        } catch (e: Exception) {
            emptyList()
        }
        val attachedProcess = processes.find { it.pid == configuration.pid }
        
        // 设置自定义调试弹窗标题
        if (attachedProcess != null) {
            val processTypeText = if (attachedProcess.ueProcessType != UEProcessType.NON_UE) {
                val icon = UEProcessClassifier.getProcessTypeIcon(attachedProcess.ueProcessType)
                "$icon[${attachedProcess.ueProcessType.displayName}] "
            } else {
                ""
            }
            // 如果title是路径，只取最后部分
            val displayTitle = if (attachedProcess.title.contains("\\") || attachedProcess.title.contains("/")) {
                attachedProcess.title.substringAfterLast("\\").substringAfterLast("/")
            } else {
                attachedProcess.title
            }
            val customTitle = "$processTypeText$displayTitle (PID: ${attachedProcess.pid})"
            configuration.setCustomDebugTitle(customTitle)
        }
        
        // 启动调试会话
        val manager = XDebuggerManager.getInstance(environment.project)
        val session = manager.startSession(environment, object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                return EmmyAttachDebugProcess(session)
            }
        })
        configuration.restoreName()

        // 记录进程附加状态
        if (attachedProcess != null) {
            attachmentManager.recordProcessAttachment(
                configuration.pid,
                attachedProcess.name,
                session
            )
        }

        return session.runContentDescriptor
    }
}