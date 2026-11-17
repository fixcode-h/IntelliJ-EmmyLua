package com.fixcode.intellij.lua.debug.ui.attach

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import javax.swing.Icon
import com.fixcode.intellij.lua.debug.api.LuaDebugTarget
import com.fixcode.intellij.lua.debug.api.LuaLocalProcess
import com.fixcode.intellij.lua.debug.ui.LuaDebugBundle
import com.fixcode.intellij.lua.debug.ui.LuaDebugIcons
import com.fixcode.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugSessionStarter
import com.fixcode.intellij.lua.debug.ui.session.LuaDebugTargetConfiguration
import com.fixcode.intellij.lua.debug.ui.session.NoProcessState
import com.fixcode.intellij.lua.debug.ui.settings.LuaDebugProjectState

/** 「附加到进程」工厂，挂在 [LuaRunConfigurationType] 下。ID 沿用旧的 EmmyLua.Attach，已有配置照常加载。 */
class LuaAttachConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = ID
    override fun getName(): String = LuaDebugBundle.message("attach.factory.name")
    override fun getIcon(): Icon = LuaDebugIcons.ATTACH
    override fun createTemplateConfiguration(project: Project): RunConfiguration = LuaAttachConfiguration(project, this)
    override fun getOptionsClass(): Class<LuaAttachOptions> = LuaAttachOptions::class.java

    companion object {
        const val ID = "EmmyLua.Attach"
    }
}

class LuaAttachOptions : RunConfigurationOptions() {
    /** 只有一个候选进程时直接附加，不弹列表。 */
    var autoAttachSingleProcess by property(true)
    /** 只列检测到 Lua 的进程和 UE 编辑器/游戏进程；关掉后列出全部进程（Lua 的排前面）。 */
    var onlyLuaProcesses by property(true)
    /** 已经在调试的进程不再列出，避免重复注入。 */
    var skipAttachedProcesses by property(true)
    /** 一个候选都没有时等进程启动。 */
    var waitForProcess by property(false)
    var waitSeconds by property(60)
    var captureOutput by property(true)
}

/**
 * 「Lua 附加」运行配置：不用填进程，运行时列出候选进程让人选（和旧版 Emmy Attach 一样），
 * 只有一个候选时直接附加。
 */
class LuaAttachConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<LuaAttachOptions>(project, factory, null), LuaDebugTargetConfiguration {

    public override fun getOptions(): LuaAttachOptions = super.getOptions() as LuaAttachOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = LuaAttachSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = NoProcessState

    override fun resolveTarget(env: ExecutionEnvironment): LuaDebugTarget {
        val candidates = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<List<LuaLocalProcess>, Exception>(
                { findCandidates() },
                LuaDebugBundle.message("attach.config.progress.finding"),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            throw ExecutionException(LuaDebugBundle.message("attach.config.error.cancelled"))
        }
        if (candidates.isEmpty()) {
            throw ExecutionException(
                if (options.waitForProcess) LuaDebugBundle.message("attach.config.error.timeout", options.waitSeconds)
                else LuaDebugBundle.message("attach.config.error.notFound")
            )
        }
        val chosen = if (candidates.size == 1 && options.autoAttachSingleProcess) candidates.single()
        else LuaProcessChooser.choose(project, candidates)
            ?: throw ExecutionException(LuaDebugBundle.message("attach.config.error.cancelled"))

        LuaDebugProjectState.getInstance(project).rememberAttach(chosen.executablePath ?: chosen.name)
        return LuaDebugTarget.LocalProcess(
            pid = chosen.pid,
            processName = chosen.name,
            executablePath = chosen.executablePath,
            captureOutput = options.captureOutput,
        )
    }

    /**
     * 候选进程：Lua / UE 进程（或全部），去掉已附加的。顺序：最近附加过的程序、Lua 进程、名字。
     * 勾了等待时，一个都没有就每 0.5 秒再找一次。
     */
    private fun findCandidates(): List<LuaLocalProcess> {
        val indicator = ProgressManager.getInstance().progressIndicator
        val deadline = System.currentTimeMillis() + options.waitSeconds.coerceIn(1, 3600) * 1000L
        val recent = LuaDebugProjectState.getInstance(project).recentAttachTargets
        val self = ProcessHandle.current().pid().toInt()
        while (true) {
            indicator?.checkCanceled()
            val all = LuaLocalProcessScanner.scan(LuaLocalProcessScanner.listProcesses()).values
                .filter { it.pid != self }
                .filter { !options.skipAttachedProcesses || !LuaDebugSessionStarter.isAttached(it.pid) }
            val candidates = if (options.onlyLuaProcesses) all.filter { LuaLocalProcessScanner.isLuaCandidate(it) } else all
            if (candidates.isNotEmpty()) {
                return candidates.sortedWith(
                    compareByDescending<LuaLocalProcess> { LuaLocalProcessScanner.priority(it) }
                        .thenBy { p -> recent.indexOfFirst { it == p.executablePath || it == p.name }.let { if (it < 0) Int.MAX_VALUE else it } }
                        .thenByDescending { LuaLocalProcessScanner.isLuaCandidate(it) }
                        .thenBy { it.name.lowercase() }
                        .thenByDescending { it.pid }
                )
            }
            if (!options.waitForProcess || System.currentTimeMillis() > deadline) return emptyList()
            Thread.sleep(500)
        }
    }
}
