package com.fixcode.intellij.lua.debug.ui.session

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget

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

    /**
     * 也接 Run：Lua 附加配置没有「只运行」的意思，按「运行」等于开始调试。这样 IDE 自带 MCP 的
     * execute_run_configuration（只走 Run 执行器）和工具栏的运行按钮也能开始 Lua 调试。
     */
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        (executorId == DefaultDebugExecutor.EXECUTOR_ID || executorId == DefaultRunExecutor.EXECUTOR_ID) &&
            profile is LuaDebugTargetConfiguration

    @Throws(ExecutionException::class)
    override fun doExecute(state: RunProfileState, env: ExecutionEnvironment): RunContentDescriptor? {
        if (env.executor.id != DefaultDebugExecutor.EXECUTOR_ID) {
            // 换成 Debug 执行器重新启动，会话出现在「调试」工具窗口，和用户点调试按钮一样
            val debugEnv = ExecutionEnvironmentBuilder(env).executor(DefaultDebugExecutor.getDebugExecutorInstance()).runner(this).build()
            ExecutionManager.getInstance(env.project).restartRunProfile(debugEnv)
            return null
        }
        // 真机上没有本地文件，断点行号以磁盘内容为准，先保存
        FileDocumentManager.getInstance().saveAllDocuments()
        val configuration = env.runProfile as LuaDebugTargetConfiguration
        val target = configuration.resolveTarget(env)
        return LuaDebugSessionStarter.start(env, target)
    }
}
