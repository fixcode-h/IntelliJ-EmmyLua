package com.fixcode.intellij.lua.debug.xdebugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugTargetConfiguration
import com.fixcode.intellij.lua.debugger.mcp.ErrorCode
import com.fixcode.intellij.lua.debugger.mcp.LuaDebugBackendException
import com.fixcode.intellij.lua.debugger.mcp.RunConfigInfo

/**
 * 给 MCP 用的运行配置列表和启动。IDE 自带 MCP 的 execute_run_configuration 只走 Run 执行器，
 * Agent 想调试（Lua 或别的语言）只能从 lua_debug_session action=launch 进来，和用户点调试按钮走同一条路。
 */
internal object LuaRunConfigs {
    fun list(project: Project): List<RunConfigInfo> {
        val runManager = RunManager.getInstance(project)
        val selected = runManager.selectedConfiguration
        val running = runningNames(project)
        return runManager.allSettings.filter { !it.isTemporary || it == selected }.map { s ->
            RunConfigInfo(
                name = s.name,
                type = s.type.displayName,
                lua = s.configuration is LuaDebugTargetConfiguration,
                canDebug = ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, s.configuration) != null,
                running = s.name in running,
                selected = s == selected,
            )
        }
    }

    fun find(project: Project, name: String): RunnerAndConfigurationSettings? =
        RunManager.getInstance(project).allSettings.firstOrNull { it.name == name }

    /** 在 EDT 上调用。启动失败抛 [LuaDebugBackendException]。 */
    fun execute(project: Project, settings: RunnerAndConfigurationSettings, debug: Boolean) {
        val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
        val builder = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
            ?: throw LuaDebugBackendException(ErrorCode.NOT_SUPPORTED,
                "运行配置「${settings.name}」（${settings.type.displayName}）不能以 ${if (debug) "Debug" else "Run"} 方式启动。")
        val env = builder.build()
        try {
            env.runner.execute(env)
        } catch (e: ExecutionException) {
            throw LuaDebugBackendException(ErrorCode.ATTACH_FAILED, "启动「${settings.name}」失败：${e.message ?: e}")
        }
    }

    private fun runningNames(project: Project): Set<String> {
        val names = HashSet<String>()
        runCatching {
            ExecutionManagerImpl.getInstance(project).getRunningDescriptors { true }.forEach { d -> d.displayName?.let { names += it } }
        }
        LuaDebugSessionManager.getInstance(project).sessions().forEach { e -> e.xSession.runProfile?.name?.let { names += it } }
        return names
    }
}
