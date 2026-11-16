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

package com.tang.intellij.lua.debug.ui.session

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.tang.intellij.lua.debug.api.LuaDebugTarget

/**
 * 新调试链路的运行配置都实现它：运行时先把配置解析成一个目标（可能要等进程、选设备），
 * 再交给 [LuaDebugSessionStarter]。
 */
interface LuaDebugTargetConfiguration : RunProfile {
    /**
     * 在 EDT 上调用；需要等待（例如等进程启动、查设备）时自己开带进度的模态任务。
     * 解析失败抛 [ExecutionException]，message 会直接显示给用户。
     */
    @Throws(ExecutionException::class)
    fun resolveTarget(env: ExecutionEnvironment): LuaDebugTarget
}

/** 附加类配置没有要启动的进程，状态对象只是占位。 */
internal object NoProcessState : RunProfileState {
    override fun execute(executor: Executor?, runner: ProgramRunner<*>) = null
}

class LuaDebugRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "EmmyLua.DebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is LuaDebugTargetConfiguration

    @Throws(ExecutionException::class)
    override fun doExecute(state: RunProfileState, env: ExecutionEnvironment): RunContentDescriptor? {
        // 真机上没有本地文件，断点行号以磁盘内容为准，先保存
        FileDocumentManager.getInstance().saveAllDocuments()
        val configuration = env.runProfile as LuaDebugTargetConfiguration
        val target = configuration.resolveTarget(env)
        return LuaDebugSessionStarter.start(env, target)
    }
}
