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

package com.tang.intellij.lua.debug.ui.attach

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.tang.intellij.lua.debug.api.LuaDebugTarget
import com.tang.intellij.lua.debug.api.LuaLocalProcess
import com.tang.intellij.lua.debug.ui.LuaDebugBundle
import com.tang.intellij.lua.debug.ui.LuaDebugIcons
import com.tang.intellij.lua.debug.ui.process.LuaLocalProcessScanner
import com.tang.intellij.lua.debug.ui.session.LuaDebugTargetConfiguration
import com.tang.intellij.lua.debug.ui.session.NoProcessState
import com.tang.intellij.lua.debug.ui.settings.LuaDebugProjectState

/**
 * 「Lua 附加」运行配置：给需要固定进程名、等进程起来自动附加的场景用（设计文档 10.2）。
 * 临时附加直接用 Run > Attach to Process…，不需要建配置。
 */
class LuaAttachConfigurationType : ConfigurationTypeBase(
    ID,
    LuaDebugBundle.message("attach.config.name"),
    LuaDebugBundle.message("attach.config.description"),
    NotNullLazyValue.createValue { LuaDebugIcons.ATTACH },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                LuaAttachConfiguration(project, this)
            override fun getOptionsClass(): Class<LuaAttachOptions> = LuaAttachOptions::class.java
        })
    }

    companion object {
        const val ID = "EmmyLua.Attach"
    }
}

class LuaAttachOptions : RunConfigurationOptions() {
    /** 进程名或可执行文件名，不区分大小写，可以只写一部分。 */
    var processName by string("")
    /** 进程还没起来时等它启动再附加。 */
    var waitForProcess by property(false)
    var waitSeconds by property(60)
    var captureOutput by property(true)
}

class LuaAttachConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<LuaAttachOptions>(project, factory, null), LuaDebugTargetConfiguration {

    public override fun getOptions(): LuaAttachOptions = super.getOptions() as LuaAttachOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = LuaAttachSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = NoProcessState

    override fun checkConfiguration() {
        if (options.processName.isNullOrBlank()) {
            throw RuntimeConfigurationError(LuaDebugBundle.message("attach.config.error.noProcess"))
        }
    }

    override fun resolveTarget(env: ExecutionEnvironment): LuaDebugTarget {
        val wanted = options.processName.orEmpty().trim()
        if (wanted.isEmpty()) throw ExecutionException(LuaDebugBundle.message("attach.config.error.noProcess"))

        val found = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<LuaLocalProcess?, Exception>(
                { findProcess(wanted) },
                LuaDebugBundle.message("attach.config.progress.finding", wanted),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            throw ExecutionException(LuaDebugBundle.message("attach.config.error.cancelled"))
        }
            ?: throw ExecutionException(
                if (options.waitForProcess) LuaDebugBundle.message("attach.config.error.timeout", wanted, options.waitSeconds)
                else LuaDebugBundle.message("attach.config.error.notFound", wanted)
            )

        LuaDebugProjectState.getInstance(project).rememberAttach(found.executablePath ?: found.name)
        return LuaDebugTarget.LocalProcess(
            pid = found.pid,
            processName = found.name,
            executablePath = found.executablePath,
            captureOutput = options.captureOutput,
        )
    }

    /**
     * 按名字找进程；多个匹配时优先检测到 Lua 的、再优先最近启动的（pid 大的不一定新，
     * 但同名多开时通常是想调新开的那个）。
     */
    private fun findProcess(wanted: String): LuaLocalProcess? {
        val indicator = ProgressManager.getInstance().progressIndicator
        val deadline = System.currentTimeMillis() + options.waitSeconds.coerceIn(1, 3600) * 1000L
        while (true) {
            indicator?.checkCanceled()
            val candidates = LuaLocalProcessScanner.listProcesses().filter {
                it.executableName.contains(wanted, ignoreCase = true) ||
                    it.executableDisplayName.contains(wanted, ignoreCase = true)
            }
            if (candidates.isNotEmpty()) {
                val scanned = LuaLocalProcessScanner.scan(candidates).values
                return scanned.sortedWith(
                    compareByDescending<LuaLocalProcess> { LuaLocalProcessScanner.isLuaCandidate(it) }
                        .thenByDescending { it.pid }
                ).first()
            }
            if (!options.waitForProcess || System.currentTimeMillis() > deadline) return null
            Thread.sleep(500)
        }
    }
}
